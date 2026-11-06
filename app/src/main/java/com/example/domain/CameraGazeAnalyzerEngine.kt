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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * On-device CameraX ImageAnalysis pipeline tuned specifically for Google Pixel 10 Pro XL
 * (Tensor G5, 16 GB RAM, 42 MP autofocus front camera with ~103° wide FOV, no IR illuminator):
 * 1. Offloads 100% of ML Kit Task callbacks and Y-plane pixel loops onto a dedicated background
 *    `workerExecutor` (Google Play Services `Task.addOnSuccessListener` defaults to MAIN_THREAD
 *    unless an explicit Executor is supplied). The UI thread only renders at 120 Hz.
 * 2. Sets `minFaceSize(0.11f)` to accommodate the wide ~103° front FOV at arm's length on high-res
 *    analysis frames (1280×960 → 960×720 → 640×480).
 * 3. Measures ambient eye-ROI luminance and contrast to detect dim visible-light conditions
 *    (`isLowLightDegraded`) honestly, since Pixel 10 Pro XL has no IR illuminator.
 */
class CameraGazeAnalyzerEngine(
    private val onFeatureFrame: (GazeFeatureVector) -> Unit
) : ImageAnalysis.Analyzer {

    @Volatile
    var isPerformanceModeEnabled: Boolean = false

    @Volatile
    private var isClosed: Boolean = false

    // Single dedicated background executor for both CameraX ImageAnalysis and ML Kit Task callbacks
    // so reusableYBuffer is accessed strictly on one background thread and the UI thread only renders at 120 Hz.
    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Reusable Y-plane buffer to eliminate per-frame heap allocations on Tensor G5
    private var reusableYBuffer: ByteArray = ByteArray(0)

    // Speed-adaptive One Euro Filters (Casiez et al. 2012) for pupil coordinate stream
    // and visual eye diagnostics, heavily damping high-frequency jitter during fixations
    private val pupilOneEuroFilter = OneEuroFilter2D(minCutoff = 0.85, beta = 0.035, dCutoff = 1.0)
    private val leftPupilVisualFilter = OneEuroFilter2D(minCutoff = 0.90, beta = 0.040, dCutoff = 1.0)
    private val rightPupilVisualFilter = OneEuroFilter2D(minCutoff = 0.90, beta = 0.040, dCutoff = 1.0)

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.11f) // Tuned for Pixel 10 Pro XL ~103° wide-FOV front lens at arm's length
            .build()
    )

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (isClosed) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val rawWidth = imageProxy.width
        val rawHeight = imageProxy.height

        val yPlane = imageProxy.planes.firstOrNull()
        val yRowStride = yPlane?.rowStride ?: rawWidth
        val yPixelStride = yPlane?.pixelStride ?: 1
        val yBytes = yPlane?.buffer?.copyIntoReusableBuffer()

        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        val uprightW = if (rotationDegrees == 90 || rotationDegrees == 270) rawHeight else rawWidth
        val uprightH = if (rotationDegrees == 90 || rotationDegrees == 270) rawWidth else rawHeight

        try {
            detector.process(inputImage)
                .addOnSuccessListener(analysisExecutor) { faces ->
                    if (isClosed) return@addOnSuccessListener
                    var primaryFace: Face? = null
                    var maxArea = -1
                    for (i in 0 until faces.size) {
                        val f = faces[i]
                        val area = f.boundingBox.width() * f.boundingBox.height()
                        if (area > maxArea) {
                            maxArea = area
                            primaryFace = f
                        }
                    }
                    if (primaryFace == null) {
                        onFeatureFrame(
                            GazeFeatureVector(
                                faceDetected = false,
                                frameWidthPx = uprightW.coerceAtLeast(1),
                                frameHeightPx = uprightH.coerceAtLeast(1),
                                sensorRotationDeg = rotationDegrees,
                                timestampMs = System.currentTimeMillis()
                            )
                        )
                    } else {
                        val vector = extractGazeFeatureVector(
                            face = primaryFace,
                            yBytes = yBytes,
                            rawWidth = rawWidth,
                            rawHeight = rawHeight,
                            rotationDegrees = rotationDegrees,
                            rowStride = yRowStride,
                            pixelStride = yPixelStride
                        )
                        onFeatureFrame(vector)
                    }
                }
                .addOnFailureListener(analysisExecutor) {
                    if (isClosed) return@addOnFailureListener
                    onFeatureFrame(
                        GazeFeatureVector(
                            faceDetected = false,
                            frameWidthPx = uprightW.coerceAtLeast(1),
                            frameHeightPx = uprightH.coerceAtLeast(1),
                            sensorRotationDeg = rotationDegrees,
                            timestampMs = System.currentTimeMillis()
                        )
                    )
                }
                .addOnCompleteListener(analysisExecutor) {
                    imageProxy.close()
                }
        } catch (_: Exception) {
            imageProxy.close()
        }
    }

    private fun extractGazeFeatureVector(
        face: Face,
        yBytes: ByteArray?,
        rawWidth: Int,
        rawHeight: Int,
        rotationDegrees: Int,
        rowStride: Int,
        pixelStride: Int
    ): GazeFeatureVector {
        val uprightW = (if (rotationDegrees == 90 || rotationDegrees == 270) rawHeight else rawWidth).coerceAtLeast(1)
        val uprightH = (if (rotationDegrees == 90 || rotationDegrees == 270) rawWidth else rawHeight).coerceAtLeast(1)

        // Mirror front-camera yaw and pitch signs so user looking right = +yaw and looking down = +pitch
        val yaw = -face.headEulerAngleY.toDouble()
        val pitch = -face.headEulerAngleX.toDouble()
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
            rawWidth = rawWidth,
            rawHeight = rawHeight,
            rotationDegrees = rotationDegrees,
            rowStride = rowStride,
            pixelStride = pixelStride,
            sampleStep = sampleStep
        )
        val rightRoi = analyzeEyeContourRoi(
            points = rightEyePts,
            eyeOpenProb = rightOpen,
            yBytes = yBytes,
            rawWidth = rawWidth,
            rawHeight = rawHeight,
            rotationDegrees = rotationDegrees,
            rowStride = rowStride,
            pixelStride = pixelStride,
            sampleStep = sampleStep
        )

        val eyeDistancePx = if (leftRoi.geometricCenter != null && rightRoi.geometricCenter != null) {
            hypot(
                (rightRoi.geometricCenter.x - leftRoi.geometricCenter.x).toDouble(),
                (rightRoi.geometricCenter.y - leftRoi.geometricCenter.y).toDouble()
            ).coerceIn(30.0, 1600.0)
        } else {
            (face.boundingBox.width() * 0.42).coerceIn(40.0, 1200.0)
        }

        // Low-light degraded mode check (Pixel 10 Pro XL has no IR illuminator; visible-light iris contrast drops in dim rooms)
        val meanAmbientLuma = ((leftRoi.meanLuma + rightRoi.meanLuma) / 2).coerceIn(0, 255)
        val bestDynamicRange = max(leftRoi.dynamicRange, rightRoi.dynamicRange)
        val isLowLight = meanAmbientLuma < 58 || bestDynamicRange < 18

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
        // using zero-allocation loops and compensating for upward head pitch and upward eye-in-orbit gaze.
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
        val upwardPitchCoupling = (max(0.0, -pitch) * 0.0018).toFloat()
        val upwardGazeCoupling = (max(0.0, -fusedEyeGy) * 0.08).toFloat()
        val compensatedBrowRatio = (rawBrowRatio - upwardPitchCoupling - upwardGazeCoupling).coerceIn(0.18f, 0.65f)

        // Compute normalized front-camera-mirrored [0, 1] coordinates for the live PreviewView overlay
        val bbox = face.boundingBox
        val mirroredBoxLeft = (1f - (bbox.right.toFloat() / uprightW)).coerceIn(0.02f, 0.98f)
        val mirroredBoxRight = (1f - (bbox.left.toFloat() / uprightW)).coerceIn(0.02f, 0.98f)
        val boxTop = (bbox.top.toFloat() / uprightH).coerceIn(0.02f, 0.98f)
        val boxBottom = (bbox.bottom.toFloat() / uprightH).coerceIn(0.02f, 0.98f)

        val lCenter = leftRoi.geometricCenter
        val rCenter = rightRoi.geometricCenter
        val lPupil = leftRoi.pupilPoint ?: lCenter
        val rPupil = rightRoi.pupilPoint ?: rCenter

        val leftEyeNX = if (lCenter != null) (1f - (lCenter.x / uprightW)).coerceIn(0.05f, 0.95f) else (mirroredBoxLeft + 0.32f * (mirroredBoxRight - mirroredBoxLeft))
        val leftEyeNY = if (lCenter != null) (lCenter.y / uprightH).coerceIn(0.05f, 0.95f) else (boxTop + 0.38f * (boxBottom - boxTop))
        val rightEyeNX = if (rCenter != null) (1f - (rCenter.x / uprightW)).coerceIn(0.05f, 0.95f) else (mirroredBoxLeft + 0.68f * (mirroredBoxRight - mirroredBoxLeft))
        val rightEyeNY = if (rCenter != null) (rCenter.y / uprightH).coerceIn(0.05f, 0.95f) else (boxTop + 0.38f * (boxBottom - boxTop))

        val leftPupilNX = if (lPupil != null) (1f - (lPupil.x / uprightW)).coerceIn(0.05f, 0.95f) else leftEyeNX
        val leftPupilNY = if (lPupil != null) (lPupil.y / uprightH).coerceIn(0.05f, 0.95f) else leftEyeNY
        val rightPupilNX = if (rPupil != null) (1f - (rPupil.x / uprightW)).coerceIn(0.05f, 0.95f) else rightEyeNX
        val rightPupilNY = if (rPupil != null) (rPupil.y / uprightH).coerceIn(0.05f, 0.95f) else rightEyeNY

        val leftBrowNY = if (leftBrowPts.isNotEmpty()) {
            (meanYOfPoints(leftBrowPts).toFloat() / uprightH).coerceIn(0.04f, 0.94f)
        } else {
            (leftEyeNY - 0.06f).coerceAtLeast(0.04f)
        }
        val rightBrowNY = if (rightBrowPts.isNotEmpty()) {
            (meanYOfPoints(rightBrowPts).toFloat() / uprightH).coerceIn(0.04f, 0.94f)
        } else {
            (rightEyeNY - 0.06f).coerceAtLeast(0.04f)
        }

        val nowMs = System.currentTimeMillis()
        val isEyesClosing = leftOpen < 0.40f && rightOpen < 0.40f

        // Apply One Euro Filter to the fused pupil coordinate stream:
        // Freezes during eye closure so the eyelid sweep never injects false pupil motion
        val (cleanGx, cleanGy) = if (!isEyesClosing) {
            pupilOneEuroFilter.filter(fusedEyeGx, fusedEyeGy, nowMs)
        } else {
            pupilOneEuroFilter.lastPosition ?: Pair(fusedEyeGx, fusedEyeGy)
        }

        val (cleanLeftPupilX, cleanLeftPupilY) = leftPupilVisualFilter.filter(leftPupilNX.toDouble(), leftPupilNY.toDouble(), nowMs)
        val (cleanRightPupilX, cleanRightPupilY) = rightPupilVisualFilter.filter(rightPupilNX.toDouble(), rightPupilNY.toDouble(), nowMs)

        return GazeFeatureVector(
            gx = cleanGx.coerceIn(-0.5, 0.5),
            gy = cleanGy.coerceIn(-0.5, 0.5),
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
            faceBoxLeft = min(mirroredBoxLeft, mirroredBoxRight),
            faceBoxTop = min(boxTop, boxBottom),
            faceBoxRight = max(mirroredBoxLeft, mirroredBoxRight),
            faceBoxBottom = max(boxTop, boxBottom),
            leftEyeNormX = leftEyeNX,
            leftEyeNormY = leftEyeNY,
            rightEyeNormX = rightEyeNX,
            rightEyeNormY = rightEyeNY,
            leftPupilNormX = cleanLeftPupilX.toFloat().coerceIn(0.05f, 0.95f),
            leftPupilNormY = cleanLeftPupilY.toFloat().coerceIn(0.05f, 0.95f),
            rightPupilNormX = cleanRightPupilX.toFloat().coerceIn(0.05f, 0.95f),
            rightPupilNormY = cleanRightPupilY.toFloat().coerceIn(0.05f, 0.95f),
            leftBrowNormY = leftBrowNY,
            rightBrowNormY = rightBrowNY,
            frameWidthPx = uprightW,
            frameHeightPx = uprightH,
            sensorRotationDeg = rotationDegrees,
            ambientLuma = meanAmbientLuma,
            isLowLightDegraded = isLowLight,
            timestampMs = nowMs
        )
    }

    private fun meanYOfPoints(pts: List<PointF>): Double {
        if (pts.isEmpty()) return 0.0
        var sum = 0.0
        for (i in 0 until pts.size) {
            sum += pts[i].y
        }
        return sum / pts.size
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
            val browY = meanYOfPoints(leftBrowPts)
            val diff = (leftEyeCenter.y - browY)
            if (diff > 2.0) {
                sum += diff
                count++
            }
        }
        if (rightBrowPts.isNotEmpty() && rightEyeCenter != null) {
            val browY = meanYOfPoints(rightBrowPts)
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
        val geometricCenter: PointF?,
        val pupilPoint: PointF? = null,
        val meanLuma: Int = 140,
        val dynamicRange: Int = 65
    )

    /**
     * Maps upright ML Kit contour coordinates `(ux, uy)` into the unrotated raw Y-plane sensor buffer
     * `(sx, sy)` based on `rotationDegrees` (0, 90, 180, 270), crops out the upper 25% eyelash shadow
     * band and lower 15% lid margin, and measures the dark iris centroid relative to the anatomical
     * medial/lateral canthus eye corners (`points[0]` and `points[8]`).
     */
    private fun analyzeEyeContourRoi(
        points: List<PointF>,
        eyeOpenProb: Float,
        yBytes: ByteArray?,
        rawWidth: Int,
        rawHeight: Int,
        rotationDegrees: Int,
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

        for (i in 0 until points.size) {
            val pt = points[i]
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

        // Anatomical canthus anchor (medial corner = index 0, lateral corner = index 8 in ML Kit 16-pt eye contour)
        val canthusMidX = if (points.size >= 9) {
            ((points[0].x + points[8].x) * 0.5f).toDouble()
        } else {
            ((minX + maxX) * 0.5f).toDouble()
        }
        val canthusMidY = if (points.size >= 9) {
            ((points[0].y + points[8].y) * 0.5f).toDouble()
        } else {
            ((minY + maxY) * 0.5f).toDouble()
        }

        val uprightW = if (rotationDegrees == 90 || rotationDegrees == 270) rawHeight else rawWidth
        val uprightH = if (rotationDegrees == 90 || rotationDegrees == 270) rawWidth else rawHeight

        if (yBytes == null || uprightW <= 0 || uprightH <= 0) {
            val relX = -((geomCenter.x - canthusMidX) / boxW)
            val relY = ((geomCenter.y - canthusMidY) / boxH)
            return EyeRoiResult(relX, relY, (eyeOpenProb * 0.6f).coerceIn(0.05f, 1f), false, geomCenter)
        }

        // Crop out top 25% eyelash shadow band and bottom 15% lower eyelid margin in upright space
        val trimmedMinY = minY + boxH * 0.25f
        val trimmedMaxY = maxY - boxH * 0.15f
        val trimmedMinX = minX + boxW * 0.08f
        val trimmedMaxX = maxX - boxW * 0.08f

        val ux0 = trimmedMinX.toInt().coerceIn(0, uprightW - 1)
        val ux1 = trimmedMaxX.toInt().coerceIn(0, uprightW - 1)
        val uy0 = trimmedMinY.toInt().coerceIn(0, uprightH - 1)
        val uy1 = trimmedMaxY.toInt().coerceIn(0, uprightH - 1)

        if (ux1 <= ux0 + 2 || uy1 <= uy0 + 1) {
            return EyeRoiResult(0.0, 0.0, 0.2f, false, geomCenter)
        }

        val step = sampleStep.coerceIn(1, 2)

        // Pass 1: Find local min/max/mean luminance (excluding specular glare > 240) in rotated sensor buffer
        var localMinLuma = 255
        var localMaxLuma = 0
        var lumaSum = 0
        var brightGlarePixels = 0
        var totalPixels = 0

        for (uy in uy0..uy1 step step) {
            for (ux in ux0..ux1 step step) {
                val luma = sampleUprightLuma(yBytes, ux, uy, rawWidth, rawHeight, rotationDegrees, rowStride, pixelStride)
                if (luma >= 0) {
                    totalPixels++
                    lumaSum += luma
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

        val meanLuma = (lumaSum / totalPixels).coerceIn(0, 255)
        val rawDynamicRange = (localMaxLuma - localMinLuma).coerceAtLeast(1)
        val dynamicRange = rawDynamicRange.coerceAtLeast(10)
        val contrastScore = (rawDynamicRange.toFloat() / 55f).coerceIn(0.25f, 1.0f)
        val glareRatio = brightGlarePixels.toFloat() / totalPixels.toFloat()
        val hasGlare = glareRatio > 0.14f
        val glarePenalty = (glareRatio * 3.8f).coerceIn(0f, 0.85f)

        // Pass 2: Adaptive dark-pupil thresholding with quadratic weighting
        // Focuses heavily on the darkest 35% of the dynamic range to isolate the iris/pupil from sclera
        val darkThreshold = localMinLuma + (rawDynamicRange * 0.40).toInt().coerceAtLeast(6)
        var darkWeightSum = 0.0
        var weightedUx = 0.0
        var weightedUy = 0.0

        for (uy in uy0..uy1 step step) {
            for (ux in ux0..ux1 step step) {
                val luma = sampleUprightLuma(yBytes, ux, uy, rawWidth, rawHeight, rotationDegrees, rowStride, pixelStride)
                if (luma in 0..darkThreshold) {
                    val darkness = (darkThreshold - luma + 1).toDouble()
                    val w = darkness * darkness
                    darkWeightSum += w
                    weightedUx += ux * w
                    weightedUy += uy * w
                }
            }
        }

        if (darkWeightSum <= 1e-4) {
            // Fallback to geometric center if contrast is too flat
            val relX = -((geomCenter.x - canthusMidX) / boxW).coerceIn(-0.5, 0.5)
            val relY = ((geomCenter.y - canthusMidY) / boxH).coerceIn(-0.5, 0.5)
            return EyeRoiResult(relX, relY, 0.20f, hasGlare, geomCenter, null, meanLuma, rawDynamicRange)
        }

        val pupilUx = weightedUx / darkWeightSum
        val pupilUy = weightedUy / darkWeightSum

        // Normalized relative pupil displacement (-0.5 to +0.5).
        // Front-camera horizontal axis is mirrored so looking user-right yields +normGx.
        val normGx = -((pupilUx - canthusMidX) / boxW).coerceIn(-0.5, 0.5)
        val normGy = ((pupilUy - canthusMidY) / boxH).coerceIn(-0.5, 0.5)
        val confidence = (eyeOpenProb.coerceIn(0.2f, 1f) * (1f - glarePenalty) * contrastScore).coerceIn(0.10f, 1.0f)

        return EyeRoiResult(
            normGx = normGx,
            normGy = normGy,
            confidence = confidence,
            hasSpecularGlare = hasGlare,
            geometricCenter = geomCenter,
            pupilPoint = PointF(pupilUx.toFloat(), pupilUy.toFloat()),
            meanLuma = meanLuma,
            dynamicRange = rawDynamicRange
        )
    }

    /**
     * Inverse-maps an upright ML Kit pixel `(ux, uy)` back into the raw sensor buffer `(sx, sy)`
     * according to `imageProxy.imageInfo.rotationDegrees` (0, 90, 180, 270).
     */
    private fun sampleUprightLuma(
        yBytes: ByteArray,
        ux: Int,
        uy: Int,
        rawWidth: Int,
        rawHeight: Int,
        rotationDegrees: Int,
        rowStride: Int,
        pixelStride: Int
    ): Int {
        val sx: Int
        val sy: Int
        when (rotationDegrees) {
            90 -> {
                sx = uy
                sy = rawHeight - 1 - ux
            }
            180 -> {
                sx = rawWidth - 1 - ux
                sy = rawHeight - 1 - uy
            }
            270 -> {
                sx = rawWidth - 1 - uy
                sy = ux
            }
            else -> {
                sx = ux
                sy = uy
            }
        }
        if (sx !in 0 until rawWidth || sy !in 0 until rawHeight) return -1
        val idx = sy * rowStride + sx * pixelStride
        return if (idx in yBytes.indices) (yBytes[idx].toInt() and 0xFF) else -1
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
        isClosed = true
        pupilOneEuroFilter.reset()
        leftPupilVisualFilter.reset()
        rightPupilVisualFilter.reset()
        try {
            detector.close()
        } catch (_: Exception) {
        }
        try {
            analysisExecutor.shutdown()
        } catch (_: Exception) {
        }
    }
}
