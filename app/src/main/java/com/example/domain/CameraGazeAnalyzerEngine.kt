package com.example.domain

import android.graphics.PointF
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.data.model.GazeFeatureVector
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.nio.ByteBuffer
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * On-device CameraX ImageAnalysis pipeline optimized for visible-light front cameras (e.g. Google Pixel 10 Pro XL):
 * 1. Crops out the top 25% upper-lid/eyelash shadow band and bottom 15% lower-lid margin of each eye contour ROI.
 * 2. Performs local 10th-to-90th percentile contrast stretching inside the central iris band so dark brown irises
 *    separate cleanly from surrounding sclera.
 * 3. Computes per-eye confidence weights w_L, w_R in [0.05, 1.0] combining eye-open probability, local iris/sclera
 *    contrast, and specular eyeglass glare rejection—fusing (gx, gy) as a confidence-weighted average so a
 *    glare-occluded eye is automatically down-weighted in favor of the clear eye.
 */
class CameraGazeAnalyzerEngine(
    private val onFeatureFrame: (GazeFeatureVector) -> Unit
) : ImageAnalysis.Analyzer {

    @Volatile
    var isPerformanceModeEnabled: Boolean = false

    // Reusable Y-plane buffer to eliminate ~900KB per-frame heap allocations on Pixel 10 Pro XL
    private var reusableYBuffer: ByteArray = ByteArray(0)

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.20f)
            .build()
    )

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val width = imageProxy.width
        val height = imageProxy.height

        val yPlane = imageProxy.planes.firstOrNull()
        val yRowStride = yPlane?.rowStride ?: width
        val yPixelStride = yPlane?.pixelStride ?: 1
        val yBytes = yPlane?.buffer?.copyIntoReusableBuffer()

        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                val primaryFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                if (primaryFace == null) {
                    onFeatureFrame(
                        GazeFeatureVector(
                            faceDetected = false,
                            timestampMs = System.currentTimeMillis()
                        )
                    )
                } else {
                    val vector = extractGazeFeatureVector(
                        face = primaryFace,
                        yBytes = yBytes,
                        imgWidth = width,
                        imgHeight = height,
                        rowStride = yRowStride,
                        pixelStride = yPixelStride
                    )
                    onFeatureFrame(vector)
                }
            }
            .addOnFailureListener {
                onFeatureFrame(
                    GazeFeatureVector(
                        faceDetected = false,
                        timestampMs = System.currentTimeMillis()
                    )
                )
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun extractGazeFeatureVector(
        face: Face,
        yBytes: ByteArray?,
        imgWidth: Int,
        imgHeight: Int,
        rowStride: Int,
        pixelStride: Int
    ): GazeFeatureVector {
        val yaw = face.headEulerAngleY.toDouble()
        val pitch = face.headEulerAngleX.toDouble()
        val roll = face.headEulerAngleZ.toDouble()

        val leftOpen = (face.leftEyeOpenProbability ?: 0.88f).coerceIn(0f, 1f)
        val rightOpen = (face.rightEyeOpenProbability ?: 0.88f).coerceIn(0f, 1f)

        val leftEyePts = face.getContour(FaceContour.LEFT_EYE)?.points.orEmpty()
        val rightEyePts = face.getContour(FaceContour.RIGHT_EYE)?.points.orEmpty()

        val sampleStep = if (isPerformanceModeEnabled) 2 else 1

        val leftRoi = analyzeEyeContourRoi(
            points = leftEyePts,
            eyeOpenProb = leftOpen,
            yBytes = yBytes,
            imgWidth = imgWidth,
            imgHeight = imgHeight,
            rowStride = rowStride,
            pixelStride = pixelStride,
            sampleStep = sampleStep
        )
        val rightRoi = analyzeEyeContourRoi(
            points = rightEyePts,
            eyeOpenProb = rightOpen,
            yBytes = yBytes,
            imgWidth = imgWidth,
            imgHeight = imgHeight,
            rowStride = rowStride,
            pixelStride = pixelStride,
            sampleStep = sampleStep
        )

        val eyeDistancePx = if (leftRoi.geometricCenter != null && rightRoi.geometricCenter != null) {
            hypot(
                (rightRoi.geometricCenter.x - leftRoi.geometricCenter.x).toDouble(),
                (rightRoi.geometricCenter.y - leftRoi.geometricCenter.y).toDouble()
            ).coerceIn(30.0, 1200.0)
        } else {
            (face.boundingBox.width() * 0.42).coerceIn(40.0, 800.0)
        }

        // Explicit Single-Eye Mode detection: if one eye is severely degraded (< 0.25 confidence) or
        // asymmetric by > 0.40 while the other eye is clear (>= 0.38), discard the garbage eye completely.
        val lConf = leftRoi.confidence
        val rConf = rightRoi.confidence
        val useLeftOnly = (rConf < 0.25f && lConf >= 0.38f) || (lConf - rConf > 0.40f && lConf >= 0.45f)
        val useRightOnly = (lConf < 0.25f && rConf >= 0.38f) || (rConf - lConf > 0.40f && rConf >= 0.45f)

        val isSingleEye = useLeftOnly || useRightOnly
        val activeEyeLabel = when {
            useLeftOnly -> "LEFT EYE ONLY"
            useRightOnly -> "RIGHT EYE ONLY"
            else -> "BINOCULAR"
        }

        val fusedEyeGx: Double
        val fusedEyeGy: Double
        when {
            useLeftOnly -> {
                fusedEyeGx = leftRoi.normGx
                fusedEyeGy = leftRoi.normGy
            }
            useRightOnly -> {
                fusedEyeGx = rightRoi.normGx
                fusedEyeGy = rightRoi.normGy
            }
            else -> {
                val wL = lConf.toDouble().coerceAtLeast(0.05)
                val wR = rConf.toDouble().coerceAtLeast(0.05)
                fusedEyeGx = (wL * leftRoi.normGx + wR * rightRoi.normGx) / (wL + wR)
                fusedEyeGy = (wL * leftRoi.normGy + wR * rightRoi.normGy) / (wL + wR)
            }
        }

        // Extract Normalized Brow-to-Eye Vertical Ratio from LEFT_EYEBROW_TOP and RIGHT_EYEBROW_TOP
        // compensated for upward head pitch and upward eye-in-orbit gaze (so looking at top-row tiles
        // does not artificially inflate brow height).
        val leftBrowPts = face.getContour(FaceContour.LEFT_EYEBROW_TOP)?.points.orEmpty()
        val rightBrowPts = face.getContour(FaceContour.RIGHT_EYEBROW_TOP)?.points.orEmpty()
        val rawBrowDistPx = computeMeanBrowToEyeVerticalPx(
            leftBrowPts = leftBrowPts,
            rightBrowPts = rightBrowPts,
            leftEyeCenter = leftRoi.geometricCenter,
            rightEyeCenter = rightRoi.geometricCenter
        )
        val rawBrowRatio = if (rawBrowDistPx != null && eyeDistancePx > 25.0) {
            (rawBrowDistPx / eyeDistancePx).toFloat().coerceIn(0.18f, 0.65f)
        } else {
            0.34f
        }
        // Upward gaze (gy < 0) and upward head pitch co-contract the frontalis slightly; subtract that coupling:
        val upwardPitchCoupling = (max(0.0, pitch) * 0.0018).toFloat()
        val upwardGazeCoupling = (max(0.0, -fusedEyeGy) * 0.08).toFloat()
        val compensatedBrowRatio = (rawBrowRatio - upwardPitchCoupling - upwardGazeCoupling).coerceIn(0.18f, 0.65f)

        return GazeFeatureVector(
            gx = fusedEyeGx.coerceIn(-0.5, 0.5),
            gy = fusedEyeGy.coerceIn(-0.5, 0.5),
            yaw = yaw,
            pitch = pitch,
            roll = roll,
            eyeDistancePx = eyeDistancePx,
            leftEyeOpenProb = leftOpen,
            rightEyeOpenProb = rightOpen,
            leftEyeConfidence = lConf,
            rightEyeConfidence = rConf,
            browToEyeRatio = compensatedBrowRatio,
            isSingleEyeMode = isSingleEye,
            activeEyeLabel = activeEyeLabel,
            hasGlareWarning = leftRoi.hasSpecularGlare || rightRoi.hasSpecularGlare,
            faceDetected = true,
            timestampMs = System.currentTimeMillis()
        )
    }

    private fun computeMeanBrowToEyeVerticalPx(
        leftBrowPts: List<PointF>,
        rightBrowPts: List<PointF>,
        leftEyeCenter: PointF?,
        rightEyeCenter: PointF?
    ): Double? {
        var sum = 0.0
        var count = 0
        if (leftBrowPts.isNotEmpty() && leftEyeCenter != null) {
            val browY = leftBrowPts.map { it.y }.average()
            val diff = (leftEyeCenter.y - browY)
            if (diff > 2.0) {
                sum += diff
                count++
            }
        }
        if (rightBrowPts.isNotEmpty() && rightEyeCenter != null) {
            val browY = rightBrowPts.map { it.y }.average()
            val diff = (rightEyeCenter.y - browY)
            if (diff > 2.0) {
                sum += diff
                count++
            }
        }
        return if (count > 0) sum / count else null
    }

    private data class EyeRoiResult(
        val normGx: Double,
        val normGy: Double,
        val confidence: Float,
        val hasSpecularGlare: Boolean,
        val geometricCenter: PointF?
    )

    /**
     * Crops out the upper 25% eyelash band and lower 15% lid margin, normalizes local contrast,
     * masks specular glare pixels (> 240 luma), and returns both the iris centroid and a
     * per-eye confidence score in [0.05f, 1.0f].
     */
    private fun analyzeEyeContourRoi(
        points: List<PointF>,
        eyeOpenProb: Float,
        yBytes: ByteArray?,
        imgWidth: Int,
        imgHeight: Int,
        rowStride: Int,
        pixelStride: Int,
        sampleStep: Int = 1
    ): EyeRoiResult {
        if (points.size < 4) {
            return EyeRoiResult(0.0, 0.0, (eyeOpenProb * 0.4f).coerceIn(0.05f, 1f), false, null)
        }

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var sumCx = 0f
        var sumCy = 0f

        for (pt in points) {
            minX = min(minX, pt.x)
            maxX = max(maxX, pt.x)
            minY = min(minY, pt.y)
            maxY = max(maxY, pt.y)
            sumCx += pt.x
            sumCy += pt.y
        }

        val geomCenter = PointF(sumCx / points.size, sumCy / points.size)
        val boxW = (maxX - minX).coerceAtLeast(6f)
        val boxH = (maxY - minY).coerceAtLeast(4f)

        if (yBytes == null || imgWidth <= 0 || imgHeight <= 0) {
            val relX = ((geomCenter.x - (minX + maxX) * 0.5f) / boxW).toDouble()
            val relY = ((geomCenter.y - (minY + maxY) * 0.5f) / boxH).toDouble()
            return EyeRoiResult(relX, relY, (eyeOpenProb * 0.6f).coerceIn(0.05f, 1f), false, geomCenter)
        }

        // Crop out top 25% eyelash shadow band and bottom 15% lower eyelid margin
        val trimmedMinY = minY + boxH * 0.25f
        val trimmedMaxY = maxY - boxH * 0.15f
        val trimmedMinX = minX + boxW * 0.08f
        val trimmedMaxX = maxX - boxW * 0.08f

        val x0 = trimmedMinX.toInt().coerceIn(0, imgWidth - 1)
        val x1 = trimmedMaxX.toInt().coerceIn(0, imgWidth - 1)
        val y0 = trimmedMinY.toInt().coerceIn(0, imgHeight - 1)
        val y1 = trimmedMaxY.toInt().coerceIn(0, imgHeight - 1)

        if (x1 <= x0 + 2 || y1 <= y0 + 1) {
            return EyeRoiResult(0.0, 0.0, 0.2f, false, geomCenter)
        }

        val step = sampleStep.coerceIn(1, 2)

        // Pass 1: Find local min/max luminance (excluding specular glare > 240) for local contrast normalization
        var localMinLuma = 255
        var localMaxLuma = 0
        var brightGlarePixels = 0
        var totalPixels = 0

        for (py in y0..y1 step step) {
            val rowOffset = py * rowStride
            for (px in x0..x1 step step) {
                val idx = rowOffset + px * pixelStride
                if (idx in yBytes.indices) {
                    val luma = yBytes[idx].toInt() and 0xFF
                    totalPixels++
                    if (luma > 240) {
                        brightGlarePixels++
                    } else {
                        if (luma < localMinLuma) localMinLuma = luma
                        if (luma > localMaxLuma) localMaxLuma = luma
                    }
                }
            }
        }

        if (totalPixels == 0) {
            return EyeRoiResult(0.0, 0.0, 0.2f, false, geomCenter)
        }

        val dynamicRange = (localMaxLuma - localMinLuma).coerceAtLeast(12)
        val contrastScore = (dynamicRange.toFloat() / 75f).coerceIn(0.25f, 1.0f)
        val glareRatio = brightGlarePixels.toFloat() / totalPixels.toFloat()
        val hasGlare = glareRatio > 0.14f
        val glarePenalty = (glareRatio * 3.8f).coerceIn(0f, 0.90f)

        // Pass 2: Compute contrast-normalized dark-iris centroid while ignoring specular glare pixels
        var darkWeightSum = 0.0
        var weightedX = 0.0
        var weightedY = 0.0

        for (py in y0..y1 step step) {
            val rowOffset = py * rowStride
            for (px in x0..x1 step step) {
                val idx = rowOffset + px * pixelStride
                if (idx in yBytes.indices) {
                    val luma = yBytes[idx].toInt() and 0xFF
                    if (luma <= 240) {
                        val normDarkness = ((localMaxLuma - luma).coerceAtLeast(0).toDouble()) / dynamicRange.toDouble()
                        if (normDarkness > 0.35) {
                            val w = normDarkness * normDarkness * normDarkness
                            darkWeightSum += w
                            weightedX += px * w
                            weightedY += py * w
                        }
                    }
                }
            }
        }

        if (darkWeightSum <= 1e-4) {
            return EyeRoiResult(0.0, 0.0, 0.15f, hasGlare, geomCenter)
        }

        val pupilX = weightedX / darkWeightSum
        val pupilY = weightedY / darkWeightSum
        val midX = (minX + maxX) * 0.5
        val midY = (trimmedMinY + trimmedMaxY) * 0.5

        val normGx = ((pupilX - midX) / boxW).coerceIn(-0.5, 0.5)
        val normGy = ((pupilY - midY) / boxH).coerceIn(-0.5, 0.5)
        val confidence = (eyeOpenProb.coerceIn(0.1f, 1f) * (1f - glarePenalty) * contrastScore).coerceIn(0.05f, 1.0f)

        return EyeRoiResult(
            normGx = normGx,
            normGy = normGy,
            confidence = confidence,
            hasSpecularGlare = hasGlare,
            geometricCenter = geomCenter
        )
    }

    private fun ByteBuffer.copyIntoReusableBuffer(): ByteArray {
        rewind()
        val len = remaining()
        if (reusableYBuffer.size < len) {
            reusableYBuffer = ByteArray(len)
        }
        get(reusableYBuffer, 0, len)
        return reusableYBuffer
    }

    fun close() {
        try {
            detector.close()
        } catch (_: Exception) {
        }
    }
}
