package com.example.domain

import android.graphics.PointF
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-device CameraX ImageAnalysis pipeline tuned specifically for Google Pixel 10 Pro XL
 * (Tensor G5, 16 GB RAM, 42 MP autofocus front camera with ~103° wide FOV, no IR illuminator):
 * 1. Runs ML Kit Task callbacks and the Y-plane pixel loops on the dedicated background
 *    [analysisExecutor] (Google Play Services `Task.addOnSuccessListener` defaults to MAIN_THREAD
 *    unless an explicit Executor is supplied). Pixels are read in place from the camera plane while
 *    the ImageProxy is still open. The finished [GazeFeatureVector] is then handed to
 *    `onFeatureFrame` on the MAIN thread, so consumers (the ViewModel) stay main-thread confined.
 * 2. Sets `minFaceSize(0.11f)` to accommodate the wide ~103° front FOV at arm's length on high-res
 *    analysis frames (1280×960 → 960×720 → 640×480).
 * 3. Measures ambient eye-ROI luminance and contrast to detect dim visible-light conditions
 *    (`isLowLightDegraded`) honestly, since Pixel 10 Pro XL has no IR illuminator. Only measured
 *    pixels feed the low-light state, which uses hysteresis so it cannot flicker frame to frame.
 */
class CameraGazeAnalyzerEngine(
    private val onFeatureFrame: (GazeFeatureVector) -> Unit
) : ImageAnalysis.Analyzer {

    /**
     * Retained for API compatibility; it no longer changes the analysis. Subsampling the ROI loops
     * saved only a few microseconds per frame against tens of milliseconds of ML Kit detection, so
     * it could not recover FPS and only cost pupil-centroid precision. Lowering the analysis
     * resolution tier is the lever that actually reduces detection cost.
     */
    @Volatile
    var isPerformanceModeEnabled: Boolean = false

    @Volatile
    private var isClosed: Boolean = false

    // Single dedicated background executor for CameraX ImageAnalysis, the ML Kit Task callbacks and
    // all per-frame pixel loops and filter state below. onFeatureFrame is NOT called on it.
    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ML Kit delivers a cancelled task (after detector.close()) via a main-thread post that runs after
    // close() has shut analysisExecutor down. Falling back to running the listener inline keeps that
    // dispatch from throwing RejectedExecutionException and guarantees imageProxy.close() still runs.
    private val taskListenerExecutor: Executor = rejectionSafeExecutor(analysisExecutor)

    // Feature vectors are always delivered on the main thread (see class KDoc).
    private val frameCallbackExecutor: Executor = mainThreadExecutor()

    // Speed-adaptive One Euro Filters (Casiez et al. 2012) for pupil coordinate stream
    // and visual eye diagnostics, heavily damping high-frequency jitter during fixations.
    // Only touched on analysisExecutor.
    private val pupilOneEuroFilter = newPupilGazeFilter()
    private val leftPupilVisualFilter = OneEuroFilter2D(minCutoff = 0.90, beta = 0.040, dCutoff = 1.0)
    private val rightPupilVisualFilter = OneEuroFilter2D(minCutoff = 0.90, beta = 0.040, dCutoff = 1.0)

    // Low-light hysteresis state and the last luma reported to consumers. Only touched on analysisExecutor.
    // lastReportedAmbientLuma keeps the data-class default only until the first readable Y plane.
    private val lowLightLatch = LowLightHysteresisLatch()
    private var lastReportedAmbientLuma: Int = GazeFeatureVector().ambientLuma

    // Pass-1 lumas of the eye ROI being analysed, reused across frames so pass 2 never re-reads the camera
    // plane. Only touched on analysisExecutor. Nullable (allocated on first use) so an instance created
    // without running the constructor, as the reflection-based tests and benchmarks do, still works.
    private var roiLumaBuffer: IntArray? = null

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
        // Read in place with absolute gets (no copy, buffer position untouched). The ImageProxy stays
        // open until the OnComplete listener, which runs after the success/failure listener.
        val yBuffer: ByteBuffer? = yPlane?.buffer

        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        try {
            detector.process(inputImage)
                .addOnSuccessListener(taskListenerExecutor) { faces ->
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
                        deliverFrame(
                            buildNoFaceVector(
                                yBuffer = yBuffer,
                                rawWidth = rawWidth,
                                rawHeight = rawHeight,
                                rotationDegrees = rotationDegrees,
                                rowStride = yRowStride,
                                pixelStride = yPixelStride
                            )
                        )
                    } else {
                        val vector = extractGazeFeatureVector(
                            face = primaryFace,
                            yBuffer = yBuffer,
                            rawWidth = rawWidth,
                            rawHeight = rawHeight,
                            rotationDegrees = rotationDegrees,
                            rowStride = yRowStride,
                            pixelStride = yPixelStride
                        )
                        deliverFrame(vector)
                    }
                }
                .addOnFailureListener(taskListenerExecutor) {
                    if (isClosed) return@addOnFailureListener
                    deliverFrame(
                        buildNoFaceVector(
                            yBuffer = yBuffer,
                            rawWidth = rawWidth,
                            rawHeight = rawHeight,
                            rotationDegrees = rotationDegrees,
                            rowStride = yRowStride,
                            pixelStride = yPixelStride
                        )
                    )
                }
                .addOnCompleteListener(taskListenerExecutor) {
                    imageProxy.close()
                }
        } catch (_: Exception) {
            imageProxy.close()
        }
    }

    /** Hands a finished vector (built on the analysis thread) to [onFeatureFrame] on the main thread. */
    private fun deliverFrame(vector: GazeFeatureVector) {
        frameCallbackExecutor.execute {
            if (!isClosed) onFeatureFrame(vector)
        }
    }

    /**
     * No-face / detector-failure frame. Nothing about the eyes was measured, so eye confidences and
     * eye-open probabilities are 0 (consumers gate eyelid/blink logic on faceDetected; the ViewModel
     * holds its blink state on such frames). Luma is still measured from a coarse grid over the whole
     * Y plane and feeds the low-light state, because losing the face is the most common symptom of a
     * dark room.
     */
    private fun buildNoFaceVector(
        yBuffer: ByteBuffer?,
        rawWidth: Int,
        rawHeight: Int,
        rotationDegrees: Int,
        rowStride: Int,
        pixelStride: Int
    ): GazeFeatureVector {
        val uprightW = if (rotationDegrees == 90 || rotationDegrees == 270) rawHeight else rawWidth
        val uprightH = if (rotationDegrees == 90 || rotationDegrees == 270) rawWidth else rawHeight
        val nowMs = System.currentTimeMillis()
        val frameLuma = yBuffer?.let { coarseFrameMeanLuma(it, rawWidth, rawHeight, rowStride, pixelStride) }
        val isLowLight = lowLightLatch.update(frameLuma, null, nowMs)
        val ambientLuma = lowLightLatch.reportedLuma() ?: frameLuma ?: lastReportedAmbientLuma
        lastReportedAmbientLuma = ambientLuma
        return GazeFeatureVector(
            leftEyeOpenProb = 0f,
            rightEyeOpenProb = 0f,
            leftEyeConfidence = 0f,
            rightEyeConfidence = 0f,
            faceDetected = false,
            frameWidthPx = uprightW.coerceAtLeast(1),
            frameHeightPx = uprightH.coerceAtLeast(1),
            sensorRotationDeg = rotationDegrees,
            ambientLuma = ambientLuma,
            isLowLightDegraded = isLowLight,
            timestampMs = nowMs
        )
    }

    private fun extractGazeFeatureVector(
        face: Face,
        yBuffer: ByteBuffer?,
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

        // Always sample every ROI pixel: subsampling cannot relieve ML Kit detection cost (see
        // isPerformanceModeEnabled) and only degrades the pupil centroid.
        val sampleStep = 1

        val leftRoi = analyzeEyeContourRoi(
            points = leftEyePts,
            eyeOpenProb = leftOpen,
            yBuffer = yBuffer,
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
            yBuffer = yBuffer,
            rawWidth = rawWidth,
            rawHeight = rawHeight,
            rotationDegrees = rotationDegrees,
            rowStride = rowStride,
            pixelStride = pixelStride,
            sampleStep = sampleStep
        )

        val bbox = face.boundingBox

        // Inter-ocular distance in raw upright analysis pixels (used for the in-frame brow ratio) ...
        val rawEyeDistancePx = if (leftRoi.geometricCenter != null && rightRoi.geometricCenter != null) {
            hypot(
                (rightRoi.geometricCenter.x - leftRoi.geometricCenter.x).toDouble(),
                (rightRoi.geometricCenter.y - leftRoi.geometricCenter.y).toDouble()
            ).coerceIn(30.0, 1600.0)
        } else {
            (bbox.width() * 0.42).coerceIn(40.0, 1200.0)
        }
        // ... and rescaled to the TIER_HIGH_1280 frame size, so a resolution tier change does not look
        // like the head moving closer or further away.
        val eyeDistancePx = normalizeEyeDistancePx(rawEyeDistancePx, uprightW, uprightH)

        val nowMs = System.currentTimeMillis()

        // Low-light degraded mode check (Pixel 10 Pro XL has no IR illuminator; visible-light iris contrast drops in dim rooms).
        // Only ROIs that actually sampled pixels contribute; the state itself is smoothed with hysteresis.
        val measuredLuma = meanOfMeasured(leftRoi.meanLuma, rightRoi.meanLuma)
        val measuredRange = maxOfMeasured(leftRoi.dynamicRange, rightRoi.dynamicRange)
        val isLowLight = lowLightLatch.update(measuredLuma, measuredRange, nowMs)
        val ambientLuma = lowLightLatch.reportedLuma()
            ?: yBuffer?.let {
                coarseUprightRegionMeanLuma(
                    buf = it,
                    left = bbox.left,
                    top = bbox.top,
                    right = bbox.right,
                    bottom = bbox.bottom,
                    rawWidth = rawWidth,
                    rawHeight = rawHeight,
                    rotationDegrees = rotationDegrees,
                    rowStride = rowStride,
                    pixelStride = pixelStride
                )
            }
            ?: lastReportedAmbientLuma
        lastReportedAmbientLuma = ambientLuma

        // Explicit Single-Eye Mode detection: an eye without a pupil measurement never enters fusion.
        // Between two measured eyes, if one is severely degraded (< 0.25 confidence) or asymmetric by
        // > 0.40 while the other eye is clear (>= 0.38), discard the garbage eye completely.
        val lConf = leftRoi.confidence
        val rConf = rightRoi.confidence
        val lMeasured = leftRoi.hasPupilMeasurement
        val rMeasured = rightRoi.hasPupilMeasurement
        val eyeSelection = selectActiveEye(lConf, rConf, lMeasured, rMeasured)
        val useLeftOnly = eyeSelection == EyeSelection.LEFT_ONLY
        val useRightOnly = eyeSelection == EyeSelection.RIGHT_ONLY

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
        val rawBrowRatio = if (rawBrowDistPx != null && rawEyeDistancePx > 25.0) {
            (rawBrowDistPx / rawEyeDistancePx).toFloat().coerceIn(0.18f, 0.65f)
        } else {
            0.34f
        }
        val upwardPitchCoupling = (max(0.0, -pitch) * 0.0018).toFloat()
        val upwardGazeCoupling = (max(0.0, -fusedEyeGy) * 0.08).toFloat()
        val compensatedBrowRatio = (rawBrowRatio - upwardPitchCoupling - upwardGazeCoupling).coerceIn(0.18f, 0.65f)

        // Compute normalized front-camera-mirrored [0, 1] coordinates for the live PreviewView overlay
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

        // Same openness and threshold as GazeFeatureVector.combinedEyeOpenProb and the ViewModel's
        // freeze, taken from the eye(s) actually used for gaze.
        val activeOpen = activeEyeOpenness(eyeSelection, leftOpen, rightOpen)
        val isEyesClosing = activeOpen < EYE_CLOSING_OPEN_PROB_THRESHOLD

        // Apply One Euro Filter to the fused pupil coordinate stream:
        // Freezes during eye closure so the eyelid sweep never injects false pupil motion, and holds
        // when neither eye produced a pupil measurement so no placeholder offset is filtered in.
        val (cleanGx, cleanGy) = when {
            !lMeasured && !rMeasured -> pupilOneEuroFilter.lastPosition ?: Pair(0.0, 0.0)
            isEyesClosing -> pupilOneEuroFilter.lastPosition ?: Pair(fusedEyeGx, fusedEyeGy)
            else -> pupilOneEuroFilter.filter(fusedEyeGx, fusedEyeGy, nowMs)
        }

        // Overlay pupil dots: smooth the pupil offset relative to the (unsmoothed) eye center so the dot
        // follows the eye outline during head motion instead of trailing behind it.
        val (cleanLeftPupilX, cleanLeftPupilY) = smoothPupilAroundEyeCenter(leftPupilVisualFilter, leftEyeNX, leftEyeNY, leftPupilNX, leftPupilNY, nowMs)
        val (cleanRightPupilX, cleanRightPupilY) = smoothPupilAroundEyeCenter(rightPupilVisualFilter, rightEyeNX, rightEyeNY, rightPupilNX, rightPupilNY, nowMs)

        // An eye without a pupil measurement exports at most the minimum fusion weight, so
        // combinedEyeOpenProb follows the measured eye and the HUD does not show a made-up confidence.
        val exportedLConf = if (lMeasured) lConf else min(lConf, UNMEASURED_EYE_MAX_CONFIDENCE)
        val exportedRConf = if (rMeasured) rConf else min(rConf, UNMEASURED_EYE_MAX_CONFIDENCE)

        return GazeFeatureVector(
            gx = cleanGx.coerceIn(-0.5, 0.5),
            gy = cleanGy.coerceIn(-0.5, 0.5),
            yaw = yaw,
            pitch = pitch,
            roll = roll,
            eyeDistancePx = eyeDistancePx,
            leftEyeOpenProb = leftOpen,
            rightEyeOpenProb = rightOpen,
            leftEyeConfidence = exportedLConf,
            rightEyeConfidence = exportedRConf,
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
            leftPupilNormX = cleanLeftPupilX,
            leftPupilNormY = cleanLeftPupilY,
            rightPupilNormX = cleanRightPupilX,
            rightPupilNormY = cleanRightPupilY,
            leftBrowNormY = leftBrowNY,
            rightBrowNormY = rightBrowNY,
            frameWidthPx = uprightW,
            frameHeightPx = uprightH,
            sensorRotationDeg = rotationDegrees,
            ambientLuma = ambientLuma,
            isLowLightDegraded = isLowLight,
            timestampMs = nowMs
        )
    }

    private fun meanOfMeasured(a: Int?, b: Int?): Int? = when {
        a != null && b != null -> (a + b) / 2
        else -> a ?: b
    }

    private fun maxOfMeasured(a: Int?, b: Int?): Int? = when {
        a != null && b != null -> max(a, b)
        else -> a ?: b
    }

    /**
     * Coarse mean luma of an upright-space rectangle (e.g. the face box), read through the same
     * rotation mapping as the ROI passes. Returns null when no pixel could be read.
     */
    private fun coarseUprightRegionMeanLuma(
        buf: ByteBuffer,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        rawWidth: Int,
        rawHeight: Int,
        rotationDegrees: Int,
        rowStride: Int,
        pixelStride: Int,
        step: Int = 8
    ): Int? {
        val uprightW = if (rotationDegrees == 90 || rotationDegrees == 270) rawHeight else rawWidth
        val uprightH = if (rotationDegrees == 90 || rotationDegrees == 270) rawWidth else rawHeight
        if (uprightW <= 0 || uprightH <= 0) return null
        val x0 = left.coerceIn(0, uprightW - 1)
        val x1 = right.coerceIn(0, uprightW - 1)
        val y0 = top.coerceIn(0, uprightH - 1)
        val y1 = bottom.coerceIn(0, uprightH - 1)
        if (x1 < x0 || y1 < y0) return null
        val s = step.coerceAtLeast(1)
        val cols = (x1 - x0) / s + 1
        val rows = (y1 - y0) / s + 1
        // Same strided index walk as the ROI pass in analyzeEyeContourRoi (see uprightOriginIndex).
        val dX = uprightIndexDeltaX(rotationDegrees, rowStride, pixelStride)
        val dY = uprightIndexDeltaY(rotationDegrees, rowStride, pixelStride)
        val colStep = dX * s
        val rowStep = dY * s
        var rowIdx = (uprightOriginIndex(rawWidth, rawHeight, rotationDegrees, rowStride, pixelStride) +
            x0.toLong() * dX + y0.toLong() * dY).toInt()
        val limit = buf.limit()
        var sum = 0L
        var count = 0
        for (r in 0 until rows) {
            var idx = rowIdx
            for (c in 0 until cols) {
                if (idx >= 0 && idx < limit) {
                    sum += buf.get(idx).toInt() and 0xFF
                    count++
                }
                idx += colStep
            }
            rowIdx += rowStep
        }
        return if (count > 0) (sum / count).toInt() else null
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

    /**
     * Per-eye ROI analysis result. [meanLuma] / [dynamicRange] are null when no ROI pixel was sampled,
     * and [hasPupilMeasurement] is true only when [normGx] / [normGy] come from a dark-pupil centroid
     * (every other path returns a placeholder or geometry-only offset that must not be fused as gaze).
     */
    private data class EyeRoiResult(
        val normGx: Double,
        val normGy: Double,
        val confidence: Float,
        val hasSpecularGlare: Boolean,
        val geometricCenter: PointF?,
        val pupilPoint: PointF? = null,
        val meanLuma: Int? = null,
        val dynamicRange: Int? = null,
        val hasPupilMeasurement: Boolean = false
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
        yBuffer: ByteBuffer?,
        rawWidth: Int,
        rawHeight: Int,
        rotationDegrees: Int,
        rowStride: Int,
        pixelStride: Int,
        sampleStep: Int = 1
    ): EyeRoiResult {
        val pointCount = points.size
        if (pointCount < 4) {
            return EyeRoiResult(0.0, 0.0, (eyeOpenProb * 0.4f).coerceIn(0.05f, 1f), false, null)
        }

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var sumCx = 0f
        var sumCy = 0f

        for (i in 0 until pointCount) {
            val pt = points[i]
            minX = min(minX, pt.x)
            maxX = max(maxX, pt.x)
            minY = min(minY, pt.y)
            maxY = max(maxY, pt.y)
            sumCx += pt.x
            sumCy += pt.y
        }

        val geomCenter = PointF(sumCx / pointCount, sumCy / pointCount)
        val boxW = (maxX - minX).coerceAtLeast(6f)
        val boxH = (maxY - minY).coerceAtLeast(4f)

        // Anatomical canthus anchor (medial corner = index 0, lateral corner = index 8 in ML Kit 16-pt eye contour)
        val canthusMidX = if (pointCount >= 9) {
            ((points[0].x + points[8].x) * 0.5f).toDouble()
        } else {
            ((minX + maxX) * 0.5f).toDouble()
        }
        val canthusMidY = if (pointCount >= 9) {
            ((points[0].y + points[8].y) * 0.5f).toDouble()
        } else {
            ((minY + maxY) * 0.5f).toDouble()
        }

        val uprightW = if (rotationDegrees == 90 || rotationDegrees == 270) rawHeight else rawWidth
        val uprightH = if (rotationDegrees == 90 || rotationDegrees == 270) rawWidth else rawHeight

        if (yBuffer == null || uprightW <= 0 || uprightH <= 0) {
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
        // Sampled grid, row-major in upright space: x = ux0, ux0 + step, .. <= ux1 (cols) for each
        // y = uy0, uy0 + step, .. <= uy1 (rows).
        val cols = (ux1 - ux0) / step + 1
        val rows = (uy1 - uy0) / step + 1
        val sampleCount = cols * rows

        // The upright -> raw rotation is affine in the buffer index (see uprightOriginIndex), so it is resolved
        // once per ROI rather than per pixel and the index is walked with per-sample strides. Every (ux, uy) of
        // the clamped ROI lies inside the raw frame, so only the plane-buffer index check can fail, and it is
        // skipped entirely when the ROI's extreme corners (exact, in Long) both lie inside the buffer.
        val dX = uprightIndexDeltaX(rotationDegrees, rowStride, pixelStride)
        val dY = uprightIndexDeltaY(rotationDegrees, rowStride, pixelStride)
        val colStep = dX * step
        val rowStep = dY * step
        val firstIdx = uprightOriginIndex(rawWidth, rawHeight, rotationDegrees, rowStride, pixelStride) +
            ux0.toLong() * dX + uy0.toLong() * dY
        val spanX = (cols - 1).toLong() * step * dX
        val spanY = (rows - 1).toLong() * step * dY
        val limit = yBuffer.limit()
        val allReadable = firstIdx + min(0L, spanX) + min(0L, spanY) >= 0L &&
            firstIdx + max(0L, spanX) + max(0L, spanY) < limit
        val lumas = roiLumaScratch(sampleCount)

        // Pass 1: Find local min/max/mean luminance (excluding specular glare > 240) in rotated sensor buffer,
        // reading every ROI pixel exactly once into lumas (ROI_LUMA_UNREADABLE = index outside the plane buffer).
        // Absolute gets: the plane buffer's position never moves.
        var localMinLuma = 255
        var localMaxLuma = 0
        var lumaSum = 0
        var brightGlarePixels = 0
        var totalPixels = sampleCount
        var rowIdx = firstIdx.toInt()
        var k = 0

        if (allReadable) {
            for (r in 0 until rows) {
                var idx = rowIdx
                for (c in 0 until cols) {
                    val luma = yBuffer.get(idx).toInt() and 0xFF
                    lumas[k++] = luma
                    lumaSum += luma
                    if (luma > 240) {
                        brightGlarePixels++
                    } else {
                        if (luma < localMinLuma) localMinLuma = luma
                        if (luma > localMaxLuma) localMaxLuma = luma
                    }
                    idx += colStep
                }
                rowIdx += rowStep
            }
        } else {
            for (r in 0 until rows) {
                var idx = rowIdx
                for (c in 0 until cols) {
                    if (idx >= 0 && idx < limit) {
                        val luma = yBuffer.get(idx).toInt() and 0xFF
                        lumas[k] = luma
                        lumaSum += luma
                        if (luma > 240) {
                            brightGlarePixels++
                        } else {
                            if (luma < localMinLuma) localMinLuma = luma
                            if (luma > localMaxLuma) localMaxLuma = luma
                        }
                    } else {
                        lumas[k] = ROI_LUMA_UNREADABLE
                        totalPixels--
                    }
                    k++
                    idx += colStep
                }
                rowIdx += rowStep
            }
        }

        if (totalPixels == 0) {
            return EyeRoiResult(0.0, 0.0, 0.2f, false, geomCenter)
        }

        val meanLuma = (lumaSum / totalPixels).coerceIn(0, 255)
        val rawDynamicRange = (localMaxLuma - localMinLuma).coerceAtLeast(1)
        val contrastScore = (rawDynamicRange.toFloat() / 55f).coerceIn(0.25f, 1.0f)
        val glareRatio = brightGlarePixels.toFloat() / totalPixels.toFloat()
        val hasGlare = glareRatio > 0.14f
        val glarePenalty = (glareRatio * 3.8f).coerceIn(0f, 0.85f)

        // Pass 2: Adaptive dark-pupil thresholding with quadratic weighting, over the pass-1 lumas.
        // Focuses heavily on the darkest 35% of the dynamic range to isolate the iris/pupil from sclera
        val darkThreshold = localMinLuma + (rawDynamicRange * 0.40).toInt().coerceAtLeast(6)
        val darkWeightSum: Double
        val weightedUx: Double
        val weightedUy: Double
        // Every weight and weighted coordinate is a non-negative integer, so while the sums stay below 2^52 the
        // Double accumulation is exact in any order: the same sums are then taken in Long, with uy factored out
        // per row. A larger ROI (far beyond any camera frame) keeps the Double accumulation in its original order.
        val maxDarkness = darkThreshold + 1
        if (sampleCount.toDouble() * maxDarkness * maxDarkness * max(ux1, uy1) < EXACT_INTEGER_DOUBLE_SUM_LIMIT) {
            var sumW = 0L
            var sumUxW = 0L
            var sumUyW = 0L
            k = 0
            var uy = uy0
            for (r in 0 until rows) {
                var rowW = 0L
                var rowUxW = 0L
                var ux = ux0
                for (c in 0 until cols) {
                    // darkness > 0 exactly when 0 <= luma <= darkThreshold; ROI_LUMA_UNREADABLE never qualifies.
                    val darkness = darkThreshold - lumas[k++] + 1
                    if (darkness > 0) {
                        val w = darkness * darkness
                        rowW += w
                        rowUxW += ux.toLong() * w
                    }
                    ux += step
                }
                sumW += rowW
                sumUxW += rowUxW
                sumUyW += uy * rowW
                uy += step
            }
            darkWeightSum = sumW.toDouble()
            weightedUx = sumUxW.toDouble()
            weightedUy = sumUyW.toDouble()
        } else {
            var sumW = 0.0
            var sumUxW = 0.0
            var sumUyW = 0.0
            k = 0
            var uy = uy0
            for (r in 0 until rows) {
                var ux = ux0
                for (c in 0 until cols) {
                    val luma = lumas[k++]
                    if (luma in 0..darkThreshold) {
                        val darkness = (darkThreshold - luma + 1).toDouble()
                        val w = darkness * darkness
                        sumW += w
                        sumUxW += ux * w
                        sumUyW += uy * w
                    }
                    ux += step
                }
                uy += step
            }
            darkWeightSum = sumW
            weightedUx = sumUxW
            weightedUy = sumUyW
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
            dynamicRange = rawDynamicRange,
            hasPupilMeasurement = true
        )
    }

    /** [roiLumaBuffer], grown to hold [size] samples; an ROI above the retention cap gets a one-off array. */
    private fun roiLumaScratch(size: Int): IntArray {
        val retained = roiLumaBuffer
        if (retained != null && retained.size >= size) return retained
        if (size > ROI_LUMA_BUFFER_MAX_RETAINED) return IntArray(size)
        val grown = IntArray(min(ROI_LUMA_BUFFER_MAX_RETAINED, max(ROI_LUMA_BUFFER_INITIAL, size + (size shr 1))))
        roiLumaBuffer = grown
        return grown
    }

    /**
     * Inverse-maps an upright ML Kit pixel `(ux, uy)` back into the raw sensor buffer according to
     * `imageProxy.imageInfo.rotationDegrees`: the raw pixel `(sx, sy)` is `(uy, H-1-ux)` for 90,
     * `(W-1-ux, H-1-uy)` for 180, `(W-1-uy, ux)` for 270 and `(ux, uy)` for anything else, so its index
     * `sy * rowStride + sx * pixelStride` is `uprightOriginIndex + ux * uprightIndexDeltaX + uy * uprightIndexDeltaY`.
     * For a pixel inside the raw frame that index fits in a Long, so the Long form (which may wrap only in
     * between) is exact, and its low 32 bits are what the Int form gives: an Int strided walk started from
     * `.toInt()` of it reads exactly the bytes the per-pixel Int computation read.
     */
    private fun uprightOriginIndex(rawWidth: Int, rawHeight: Int, rotationDegrees: Int, rowStride: Int, pixelStride: Int): Long =
        when (rotationDegrees) {
            90 -> (rawHeight - 1).toLong() * rowStride
            180 -> (rawHeight - 1).toLong() * rowStride + (rawWidth - 1).toLong() * pixelStride
            270 -> (rawWidth - 1).toLong() * pixelStride
            else -> 0L
        }

    /** Raw index change per +1 upright x; see [uprightOriginIndex]. */
    private fun uprightIndexDeltaX(rotationDegrees: Int, rowStride: Int, pixelStride: Int): Int =
        when (rotationDegrees) {
            90 -> -rowStride
            180 -> -pixelStride
            270 -> rowStride
            else -> pixelStride
        }

    /** Raw index change per +1 upright y; see [uprightOriginIndex]. */
    private fun uprightIndexDeltaY(rotationDegrees: Int, rowStride: Int, pixelStride: Int): Int =
        when (rotationDegrees) {
            90 -> pixelStride
            180 -> -rowStride
            270 -> -pixelStride
            else -> rowStride
        }

    /**
     * Called on the main thread. The filters and low-light state are deliberately not reset here:
     * they belong to the analysis thread, and a closed engine never emits another frame.
     */
    fun close() {
        isClosed = true
        try {
            detector.close()
        } catch (_: Exception) {
        }
        try {
            analysisExecutor.shutdown()
        } catch (_: Exception) {
        }
    }

    internal enum class EyeSelection { BINOCULAR, LEFT_ONLY, RIGHT_ONLY }

    /**
     * Low-light degraded state with hysteresis. Each EMA is updated only from measured (non-null)
     * values, the state enters below [ENTER_LUMA] luma or [ENTER_RANGE] dynamic range, leaves only
     * above [EXIT_LUMA] luma with range unknown or above [EXIT_RANGE], and changes at most once per
     * [MIN_DWELL_MS]. Not thread-safe: owned by the analysis thread.
     */
    internal class LowLightHysteresisLatch {
        var lumaEma: Double = Double.NaN
            private set
        var rangeEma: Double = Double.NaN
            private set
        var isLowLight: Boolean = false
            private set
        private var lastToggleMs: Long? = null

        fun update(luma: Int?, range: Int?, nowMs: Long): Boolean {
            if (luma != null) {
                lumaEma = if (lumaEma.isNaN()) luma.toDouble() else lumaEma + EMA_ALPHA * (luma - lumaEma)
            }
            if (range != null) {
                rangeEma = if (rangeEma.isNaN()) range.toDouble() else rangeEma + EMA_ALPHA * (range - rangeEma)
            }
            // NaN comparisons are false, so an unmeasured statistic neither enters nor exits the state.
            val wantLowLight = if (isLowLight) {
                !(lumaEma > EXIT_LUMA && (rangeEma.isNaN() || rangeEma > EXIT_RANGE))
            } else {
                lumaEma < ENTER_LUMA || rangeEma < ENTER_RANGE
            }
            val last = lastToggleMs
            if (wantLowLight != isLowLight && (last == null || nowMs - last >= MIN_DWELL_MS)) {
                isLowLight = wantLowLight
                lastToggleMs = nowMs
            }
            return isLowLight
        }

        /** Smoothed measured luma, or null before the first measurement. */
        fun reportedLuma(): Int? = if (lumaEma.isNaN()) null else lumaEma.roundToInt().coerceIn(0, 255)

        companion object {
            const val EMA_ALPHA = 0.2
            const val ENTER_LUMA = 55.0
            const val ENTER_RANGE = 16.0
            const val EXIT_LUMA = 62.0
            const val EXIT_RANGE = 22.0
            const val MIN_DWELL_MS = 1000L
        }
    }

    companion object {
        /** Pupil gaze filter tuning; beta is sized for gx/gy units (about ±0.3 across the screen). */
        internal const val PUPIL_FILTER_MIN_CUTOFF_HZ = 0.85
        internal const val PUPIL_FILTER_BETA = 2.0
        internal const val PUPIL_FILTER_D_CUTOFF_HZ = 1.0

        /** Matches GazeFeatureVector.combinedEyeOpenProb's use in the ViewModel's eyelid freeze. */
        internal const val EYE_CLOSING_OPEN_PROB_THRESHOLD = 0.50f

        /** Exported confidence ceiling for an eye whose ROI produced no pupil measurement. */
        internal const val UNMEASURED_EYE_MAX_CONFIDENCE = 0.05f

        /**
         * eyeDistancePx is reported in pixels of a frame whose long upright side is 1280 px
         * (TIER_HIGH_1280; 960 px short side for 4:3), so existing profiles and the 148 px default stay valid.
         */
        internal const val IOD_REFERENCE_LONG_SIDE_PX = 1280.0

        /** Initial and largest retained roiLumaBuffer sizes (a 140 px eye samples about 3.8k pixels). */
        private const val ROI_LUMA_BUFFER_INITIAL = 4096
        private const val ROI_LUMA_BUFFER_MAX_RETAINED = 1 shl 16

        /** roiLumaBuffer marker for a sample whose index lies outside the plane buffer (above any dark threshold). */
        private const val ROI_LUMA_UNREADABLE = Int.MAX_VALUE

        /** 2^52: integer-valued Double sums below this are exact (with margin to 2^53 for the bound's own rounding). */
        private const val EXACT_INTEGER_DOUBLE_SUM_LIMIT = 4503599627370496.0

        internal fun newPupilGazeFilter(): OneEuroFilter2D = OneEuroFilter2D(
            minCutoff = PUPIL_FILTER_MIN_CUTOFF_HZ,
            beta = PUPIL_FILTER_BETA,
            dCutoff = PUPIL_FILTER_D_CUTOFF_HZ
        )

        /** Runs on [delegate], or inline on the calling thread once [delegate] rejects work (shut down). */
        internal fun rejectionSafeExecutor(delegate: Executor): Executor = Executor { r ->
            try {
                delegate.execute(r)
            } catch (_: RejectedExecutionException) {
                r.run()
            }
        }

        private fun mainThreadExecutor(): Executor {
            val handler = Handler(Looper.getMainLooper())
            return Executor { r -> handler.post(r) }
        }

        /**
         * Picks the eyes that feed gaze fusion. An eye without a pupil measurement is never fused;
         * between two measured eyes the confidence-asymmetry rules decide.
         */
        internal fun selectActiveEye(
            lConf: Float,
            rConf: Float,
            leftMeasured: Boolean,
            rightMeasured: Boolean
        ): EyeSelection {
            val leftRule = (rConf < 0.25f && lConf >= 0.38f) || (lConf - rConf > 0.40f && lConf >= 0.45f)
            val rightRule = (lConf < 0.25f && rConf >= 0.38f) || (rConf - lConf > 0.40f && rConf >= 0.45f)
            val useLeftOnly = (leftMeasured && !rightMeasured) || (leftMeasured && rightMeasured && leftRule)
            val useRightOnly = (rightMeasured && !leftMeasured) || (leftMeasured && rightMeasured && rightRule)
            return when {
                useLeftOnly -> EyeSelection.LEFT_ONLY
                useRightOnly -> EyeSelection.RIGHT_ONLY
                else -> EyeSelection.BINOCULAR
            }
        }

        /** Eye openness of the eye(s) used for gaze, as GazeFeatureVector.combinedEyeOpenProb computes it. */
        internal fun activeEyeOpenness(selection: EyeSelection, leftOpen: Float, rightOpen: Float): Float =
            when (selection) {
                EyeSelection.LEFT_ONLY -> leftOpen
                EyeSelection.RIGHT_ONLY -> rightOpen
                EyeSelection.BINOCULAR -> (leftOpen + rightOpen) * 0.5f
            }

        /** Rescales a raw upright-pixel inter-ocular distance to [IOD_REFERENCE_LONG_SIDE_PX] reference pixels. */
        internal fun normalizeEyeDistancePx(rawEyeDistancePx: Double, uprightW: Int, uprightH: Int): Double =
            rawEyeDistancePx * (IOD_REFERENCE_LONG_SIDE_PX / max(uprightW, uprightH).coerceAtLeast(1).toDouble())

        /**
         * Filters the pupil offset from the raw eye center (not the absolute pupil position) and adds
         * it back, so smoothing never makes the overlay dot lag behind the eye during head motion.
         */
        internal fun smoothPupilAroundEyeCenter(
            filter: OneEuroFilter2D,
            eyeNX: Float,
            eyeNY: Float,
            pupilNX: Float,
            pupilNY: Float,
            nowMs: Long
        ): Pair<Float, Float> {
            val (dx, dy) = filter.filter((pupilNX - eyeNX).toDouble(), (pupilNY - eyeNY).toDouble(), nowMs)
            return Pair(
                (eyeNX + dx.toFloat()).coerceIn(0.05f, 0.95f),
                (eyeNY + dy.toFloat()).coerceIn(0.05f, 0.95f)
            )
        }

        /**
         * Mean luma of every [step]-th pixel in x and y of a Y plane (about 4.8k absolute reads at
         * 1280×960 with step 16). Row padding is never sampled and the buffer position is untouched.
         * Returns null when nothing could be read.
         */
        internal fun coarseFrameMeanLuma(
            buf: ByteBuffer,
            rawWidth: Int,
            rawHeight: Int,
            rowStride: Int,
            pixelStride: Int,
            step: Int = 16
        ): Int? {
            if (rawWidth <= 0 || rawHeight <= 0) return null
            val s = step.coerceAtLeast(1)
            val limit = buf.limit()
            // idx = sy * rowStride + sx * pixelStride, walked with strides (Int wrap-around is identical).
            val first = s / 2
            val colStep = s * pixelStride
            val rowStep = s * rowStride
            var rowIdx = first * rowStride + first * pixelStride
            var sum = 0L
            var count = 0
            var sy = first
            while (sy < rawHeight) {
                var idx = rowIdx
                var sx = first
                while (sx < rawWidth) {
                    if (idx >= 0 && idx < limit) {
                        sum += buf.get(idx).toInt() and 0xFF
                        count++
                    }
                    sx += s
                    idx += colStep
                }
                sy += s
                rowIdx += rowStep
            }
            return if (count > 0) (sum / count).toInt() else null
        }
    }
}
