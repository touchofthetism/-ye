package com.example.data.model

import android.os.Build
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

/**
 * Default 2x6 affine matrix mapping standardized feature vector:
 * f_std = [1, gx/0.25, gy/0.20, yaw/20.0, pitch/15.0, (roll/15.0) + 0.5 * distScale] -> (x_screen, y_screen).
 * Sign convention (front camera mirrored to user's perspective):
 * +gx / +yaw = looking right (+x_screen); +gy / +pitch = looking down (+y_screen).
 */
val DEFAULT_AFFINE_MATRIX_2X6: List<Double> = listOf(
    0.50, 0.30, 0.00, 0.24, 0.00, 0.02,
    0.50, 0.00, 0.32, 0.00, 0.26, -0.02
)

@IgnoreExtraProperties
data class CalibrationProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val name: String = "Pixel 10 Pro XL Desk Profile",
    val deviceModel: String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Pixel 10 Pro XL",
    val matrixCoeffs: List<Double> = DEFAULT_AFFINE_MATRIX_2X6,
    val baselineEyeDistancePx: Double = 148.0,
    val meanErrorMm: Double = 13.4,
    val meanErrorPct: Double = 10.9,
    val maxErrorPct: Double = 14.2,
    val closeBlinkThreshold: Double = 0.45,
    val openBlinkThreshold: Double = 0.65,
    val intentionalBlinkMinMs: Long = 350L,
    val isActive: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class TrackingSession(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val deviceModel: String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Pixel 10 Pro XL",
    val contentType: String = "READING_SECTIONS",
    val durationSeconds: Int = 1,
    val naturalBlinkCount: Int = 0,
    val intentionalBlinkCount: Int = 0,
    val blinksPerMinute: Double = 0.0,
    val effectiveWordsPerMinute: Int = 0,
    val fixationClusterCount: Int = 0,
    val topZonePercentage: Int = 34,
    val middleZonePercentage: Int = 33,
    val bottomZonePercentage: Int = 33,
    val sectionLoads: List<String> = emptyList(),
    val clusterPoints: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

/**
 * 6D standardized feature vector + per-eye confidence diagnostics + normalized brow-raise signal.
 * - gx, gy: Confidence-weighted fused pupil/iris centroid offset in [-0.5, +0.5].
 * - yaw, pitch, roll: 3D head Euler angles in degrees from ML Kit Face Detection.
 * - leftEyeConfidence, rightEyeConfidence: Per-eye fusion weights in [0.05, 1.0] accounting for
 *   eye-openness, local ROI contrast, and specular eyeglass glare rejection.
 * - browToEyeRatio: Vertical distance from eyebrow top contour to eye center normalized by inter-ocular distance,
 *   compensated for upward head pitch and upward gaze so looking at top-row tiles does not false-trigger.
 */
data class GazeFeatureVector(
    val gx: Double = 0.0,
    val gy: Double = 0.0,
    val yaw: Double = 0.0,
    val pitch: Double = 0.0,
    val roll: Double = 0.0,
    val eyeDistancePx: Double = 148.0,
    val leftEyeOpenProb: Float = 0.92f,
    val rightEyeOpenProb: Float = 0.92f,
    val leftEyeConfidence: Float = 0.90f,
    val rightEyeConfidence: Float = 0.90f,
    val browToEyeRatio: Float = 0.34f,
    val isSingleEyeMode: Boolean = false,
    val activeEyeLabel: String = "BINOCULAR",
    val hasGlareWarning: Boolean = false,
    val faceDetected: Boolean = false,
    val faceBoxLeft: Float = 0.25f,
    val faceBoxTop: Float = 0.18f,
    val faceBoxRight: Float = 0.75f,
    val faceBoxBottom: Float = 0.82f,
    val leftEyeNormX: Float = 0.38f,
    val leftEyeNormY: Float = 0.42f,
    val rightEyeNormX: Float = 0.62f,
    val rightEyeNormY: Float = 0.42f,
    val leftPupilNormX: Float = 0.38f,
    val leftPupilNormY: Float = 0.42f,
    val rightPupilNormX: Float = 0.62f,
    val rightPupilNormY: Float = 0.42f,
    val leftBrowNormY: Float = 0.33f,
    val rightBrowNormY: Float = 0.33f,
    val frameWidthPx: Int = 960,
    val frameHeightPx: Int = 1280,
    val sensorRotationDeg: Int = 270,
    val ambientLuma: Int = 142,
    val isLowLightDegraded: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val combinedEyeOpenProb: Float
        get() = when {
            isSingleEyeMode && leftEyeConfidence > rightEyeConfidence -> leftEyeOpenProb
            isSingleEyeMode && rightEyeConfidence > leftEyeConfidence -> rightEyeOpenProb
            else -> (leftEyeOpenProb + rightEyeOpenProb) * 0.5f
        }

    /**
     * Converts raw physical measurements into a dimensionless, unit-scaled 6D feature vector
     * so Ridge regularization penalizes iris offsets, Euler angles, and inter-ocular scale evenly.
     * In Low-Light Degraded Mode (Pixel 10 Pro XL has no IR illuminator), noisy visible-light iris
     * offsets (gx, gy) are damped by 0.45x so head-pose (yaw, pitch) keeps region tracking stable.
     */
    fun toStandardizedFeatureArray(baselineEyeDistPx: Double = 148.0): DoubleArray {
        val safeBaseDist = baselineEyeDistPx.coerceAtLeast(30.0)
        val distScale = ((eyeDistancePx - safeBaseDist) / safeBaseDist).coerceIn(-0.6, 0.6)
        val irisDamping = if (isLowLightDegraded) 0.45 else 1.0
        val stdGx = ((gx * irisDamping) / 0.25).coerceIn(-2.5, 2.5)
        val stdGy = ((gy * irisDamping) / 0.20).coerceIn(-2.5, 2.5)
        val stdYaw = (yaw / 20.0).coerceIn(-2.5, 2.5)
        val stdPitch = (pitch / 15.0).coerceIn(-2.5, 2.5)
        val stdRollAndDist = ((roll / 15.0) + 0.5 * distScale).coerceIn(-2.5, 2.5)
        return doubleArrayOf(1.0, stdGx, stdGy, stdYaw, stdPitch, stdRollAndDist)
    }
}

/**
 * Adaptive 3-tier analysis resolution controller for Google Pixel 10 Pro XL's 42 MP (~103° FOV)
 * autofocus front camera. Starts at 1280×960 so iris ROIs retain high spatial detail despite the
 * wide 103° FOV, and steps down automatically (1280 → 960 → 640) whenever measured FPS < 15 or
 * Tensor G5 thermal throttling is detected over a 20+ minute continuous session.
 */
enum class AnalysisResolutionTier(
    val targetWidth: Int,
    val targetHeight: Int,
    val shortLabel: String
) {
    TIER_HIGH_1280(1280, 960, "1280w (42MP Sharp)"),
    TIER_MED_960(960, 720, "960w (Step-Down 1)"),
    TIER_LOW_640(640, 480, "640w (Thermal Saver)")
}

/**
 * Canonical Blink Classification Timing Specification:
 * - NATURAL: 80 ms .. 349 ms (logged for blink-rate telemetry only)
 * - INTENTIONAL_COMMAND: 350 ms .. 800 ms (triggers focused in-app tile)
 * - EXTENDED_REST: > 900 ms (cancelled to prevent false triggers during eye rest)
 */
enum class BlinkType {
    NATURAL,
    INTENTIONAL_COMMAND,
    EXTENDED_REST
}

enum class GazeTriggerMode(val label: String) {
    ALL_ACTIVE("Brow Raise + Blink + Dwell"),
    BROW_RAISE("Brow Raise (380ms Hold) + Dwell"),
    COMMAND_BLINK("Command Blink (350-800ms) + Dwell"),
    DWELL_ONLY("Dwell Only (No Gesture)")
}

enum class GazeOverlayVisualMode(val shortLabel: String) {
    RETICLE_AND_HEATMAP("Cursor + Heatmap"),
    RETICLE_ONLY("Cursor Only"),
    HEATMAP_ONLY("Heatmap Only")
}

data class LiveGazeTrailPoint(
    val normX: Float,
    val normY: Float,
    val intensity: Float = 0.35f,
    val timestampMs: Long = System.currentTimeMillis()
)

data class BlinkClassificationEvent(
    val type: BlinkType,
    val durationMs: Long,
    val timestampMs: Long
)

data class CalibrationTargetPoint(
    val index: Int,
    val label: String,
    val normX: Float,
    val normY: Float,
    val isValidationPoint: Boolean,
    val shortName: String = label,
    val guidancePrompt: String = "Keep your head still and fixate steadily on the pulsing center dot."
)

data class ValidationMetricsResult(
    val meanErrorPct: Double,
    val maxErrorPct: Double,
    val approxMeanErrorMm: Double,
    val recommendedGridDesc: String
)

data class SectionPassageItem(
    val sectionId: String,
    val heading: String,
    val body: String,
    val wordCount: Int
)

data class SectionDwellSummary(
    val sectionId: String,
    val heading: String,
    val dwellMs: Long,
    val percentage: Int
)

data class FixationClusterPoint(
    val normX: Float,
    val normY: Float,
    val dwellMs: Long
)

data class AacActionTile(
    val id: String,
    val title: String,
    val subtitle: String,
    val speechPhrase: String,
    val category: String
)
