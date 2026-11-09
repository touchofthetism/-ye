package com.example.data.model

import android.os.Build
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp

/**
 * Default 2x6 affine matrix mapping standardized feature vector:
 * f_std = [1, gx/0.25, gy/0.20, yaw/20.0, pitch/15.0, (roll/15.0) + 0.5 * distScale] -> (x_screen, y_screen).
 * Sign convention (front camera mirrored to user's perspective):
 * +gx / +yaw = looking right (+x_screen); +gy / +pitch = looking down (+y_screen).
 */
val DEFAULT_AFFINE_MATRIX_2X6: List<Double> = listOf(
    0.50, 0.42, 0.00, 0.32, 0.00, 0.01,
    0.50, 0.00, 0.44, 0.00, 0.36, -0.01
)

/**
 * A saved 2x6 calibration.
 * - baselineEyeDistancePx: inter-ocular distance at calibration, in the same reference pixels as
 *   [GazeFeatureVector.eyeDistancePx].
 * - meanErrorMm / meanErrorPct / maxErrorPct: held-out validation error when [heldOutValidated] is true;
 *   otherwise the in-sample residual on the 5 fit targets, which is not a validated accuracy.
 * - heldOutValidated: true only when the error metrics come from the 4 held-out validation targets
 *   (CalibrationWizardState.metricsAreHeldOut at save time). Records saved before this field existed read
 *   back false, because nothing shows they were validated.
 * - calibrationPosture: [GazePostureProfile] name the fit was recorded in; read it with
 *   [calibrationPostureProfile], which maps unknown or blank values to UPRIGHT.
 */
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
    // Firestore's mapper strips the "is" prefix from the Kotlin getter isActive() and would map it to
    // "active", so the stored "isActive" key never deserialized and every cloud profile read back
    // active. Naming the getter's property explicitly binds the stored key (and the backing field).
    @get:PropertyName("isActive") val isActive: Boolean = true,
    // No "is" prefix, so Firestore's mapper and the local vault both use the key "heldOutValidated".
    val heldOutValidated: Boolean = false,
    val calibrationPosture: String = GazePostureProfile.UPRIGHT.name,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

/**
 * The posture [CalibrationProfile.calibrationPosture] names, or UPRIGHT for a blank or unknown value.
 * An extension rather than a member so Firestore's mapper does not serialize it as an extra field.
 */
fun CalibrationProfile.calibrationPostureProfile(): GazePostureProfile =
    GazePostureProfile.entries.firstOrNull { it.name == calibrationPosture } ?: GazePostureProfile.UPRIGHT

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
 * - eyeDistancePx: inter-ocular distance in reference pixels of a frame whose long upright side is
 *   1280 px (AnalysisResolutionTier.TIER_HIGH_1280; equivalently a 960 px short side for 4:3), not raw
 *   analysis pixels, so a resolution tier change does not look like a distance change.
 * - leftEyeOpenProb, rightEyeOpenProb: ML Kit eye-open probabilities. No-face frames carry 0 for both,
 *   so read them (and [combinedEyeOpenProb]) as eyelid closure only when [faceDetected] is true.
 * - leftEyeConfidence, rightEyeConfidence: Per-eye fusion weights in [0, 1.0] accounting for
 *   eye-openness, local ROI contrast, and specular eyeglass glare rejection: 0 on no-face frames, at most
 *   0.05 for an eye whose ROI produced no pupil measurement, and at least 0.10 for a measured pupil.
 * - browToEyeRatio: Vertical distance from eyebrow top contour to eye center normalized by inter-ocular distance,
 *   compensated for upward head pitch and upward gaze so looking at top-row tiles does not false-trigger.
 * - isSimulated: true for frames synthesized by the simulator proxy / emulator ticker rather than measured
 *   by the camera pipeline, so calibration and session code can tell them apart from real telemetry.
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
    val timestampMs: Long = System.currentTimeMillis(),
    val isSimulated: Boolean = false
) {
    val combinedEyeOpenProb: Float
        get() = when {
            isSingleEyeMode && leftEyeConfidence > rightEyeConfidence -> leftEyeOpenProb
            isSingleEyeMode && rightEyeConfidence > leftEyeConfidence -> rightEyeOpenProb
            else -> (leftEyeOpenProb + rightEyeOpenProb) * 0.5f
        }

    /**
     * Converts raw measurements into the dimensionless 6D feature vector used by both the ridge fit
     * and runtime projection: [1, gx/0.25, gy/0.20, yaw/20, pitch/15, roll/15 + 0.5 * distScale].
     * The divisors are fixed nominal ranges, not per-user unit variances: real phone-distance iris
     * offsets (especially gx) span far less than +/-1 after scaling, so a uniform ridge penalty does
     * NOT weigh the features evenly. The divisors stay fixed because DEFAULT_AFFINE_MATRIX_2X6 and every
     * stored matrix were fitted in these units.
     * The mapping is identical in every lighting state. Low light (isLowLightDegraded) is reported for
     * diagnostics only; rescaling the inputs per frame would change the regressor's gain relative to the
     * lighting the matrix was fitted under.
     */
    fun toStandardizedFeatureArray(baselineEyeDistPx: Double = 148.0): DoubleArray {
        val safeBaseDist = baselineEyeDistPx.coerceAtLeast(30.0)
        val distScale = ((eyeDistancePx - safeBaseDist) / safeBaseDist).coerceIn(-0.6, 0.6)
        val stdGx = (gx / 0.25).coerceIn(-2.5, 2.5)
        val stdGy = (gy / 0.20).coerceIn(-2.5, 2.5)
        val stdYaw = (yaw / 20.0).coerceIn(-2.5, 2.5)
        val stdPitch = (pitch / 15.0).coerceIn(-2.5, 2.5)
        val stdRollAndDist = ((roll / 15.0) + 0.5 * distScale).coerceIn(-2.5, 2.5)
        return doubleArrayOf(1.0, stdGx, stdGy, stdYaw, stdPitch, stdRollAndDist)
    }
}

/**
 * Adaptive 3-tier ImageAnalysis resolution controller. Each tier is the stream size REQUESTED from
 * CameraX (at most 1280×960, about 1.2 MP); CameraX may pick the closest size the device supports,
 * and the sensor's full resolution is never used for analysis. Starts at 1280×960 so iris ROIs keep
 * as many pixels as possible, and steps down (1280 → 960 → 640) when measured FPS drops or thermal
 * throttling is detected over a long continuous session.
 */
enum class AnalysisResolutionTier(
    val targetWidth: Int,
    val targetHeight: Int,
    val shortLabel: String
) {
    TIER_HIGH_1280(1280, 960, "1280w (High)"),
    TIER_MED_960(960, 720, "960w (Step-Down 1)"),
    TIER_LOW_640(640, 480, "640w (Thermal Saver)")
}

/**
 * Canonical Blink Classification Timing Specification:
 * - NATURAL: 80 ms .. 349 ms (logged for blink-rate telemetry only)
 * - INTENTIONAL_COMMAND: 350 ms .. 800 ms (triggers focused in-app tile)
 * - EXTENDED_REST: > 800 ms (cancelled to prevent false triggers during eye rest)
 */
enum class BlinkType {
    NATURAL,
    INTENTIONAL_COMMAND,
    EXTENDED_REST
}

enum class GazeTriggerMode(val label: String) {
    ALL_ACTIVE("Brow Raise + Blink + Dwell"),
    BROW_RAISE("Brow Raise (400ms Hold) + Dwell"),
    COMMAND_BLINK("Command Blink (350-800ms) + Dwell"),
    DWELL_ONLY("Dwell Only (No Gesture)")
}

enum class DwellSpeedPreset(
    val title: String,
    val subtitle: String,
    val durationMs: Long,
    val radiusToleranceMultiplier: Float
) {
    FAST_READER("Fast Reader", "Rapid scanning • 650 ms", 650L, 0.85f),
    BALANCED("Balanced", "Default tracking • 1,000 ms", 1000L, 1.0f),
    RELAXED("Relaxed / Steady", "Stable & deliberate • 1,400 ms", 1400L, 1.25f),
    MOTOR_ASSIST("High Precision Assist", "Forgiving dwell • 1,900 ms", 1900L, 1.55f)
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

enum class GazePostureProfile(
    val label: String,
    val shortName: String,
    val xBias: Float,
    val yBias: Float
) {
    UPRIGHT("Sitting Upright (Desk / Mount)", "Upright", 0.0f, 0.0f),
    RECLINING("Reclining (45° Tilt)", "Reclining", 0.0f, -0.035f),
    LYING_DOWN("Lying Down Flat (15° Tilt)", "Lying Down", 0.0f, -0.070f)
}

enum class GazeFilterMode(val label: String, val shortName: String) {
    ONE_EURO("1€ Speed-Adaptive Filter (Recommended)", "1€ Filter"),
    KALMAN("Closed-Form 2D Kalman Filter", "Kalman"),
    RAW("Raw Unfiltered Stream", "Raw")
}

/**
 * A user's interaction preferences and brow-raise calibration, kept per account in the on-device vault
 * (LocalTelemetryStore.saveInteractionSettings) so they survive restarts. Defaults match LiveGazeHudState's.
 * The store returns values as saved; callers apply their own setter ranges when restoring them.
 */
data class InteractionSettings(
    val dwellTriggerMs: Long = 1000L,
    val dwellSensitivityRadiusMultiplier: Float = 1.0f,
    val activeDwellPreset: DwellSpeedPreset = DwellSpeedPreset.BALANCED,
    val triggerMode: GazeTriggerMode = GazeTriggerMode.ALL_ACTIVE,
    val filterMode: GazeFilterMode = GazeFilterMode.ONE_EURO,
    val activePosture: GazePostureProfile = GazePostureProfile.UPRIGHT,
    val neutralBrowRatio: Float = 0.34f,
    val raisedBrowRatio: Float = 0.42f
)

/**
 * The derived result of one face & eye enrollment scan (domain EnrollmentScanEngine), kept per account in
 * the on-device vault (LocalTelemetryStore.saveEnrollmentBaseline) so the personalized blink and brow
 * thresholds survive restarts.
 *
 * Derived numbers only: eyelid-openness levels, blink timings, brow ratios and the thresholds computed from
 * them. No image, frame or landmark data is ever part of it. It is stored on this device only and is never
 * synced to Firestore or any other remote store; wiping the vault (LocalTelemetryStore.clearAllLocalData)
 * removes it.
 *
 * - openEyeLevel / closedEyeLevel: median [GazeFeatureVector.combinedEyeOpenProb] with the eyes open
 *   (blink frames excluded) and held closed; leftOpenLevel / rightOpenLevel: the per-eye open medians.
 * - baselineEyeDistancePx: median [GazeFeatureVector.eyeDistancePx] during the scan, in the same reference
 *   pixels as [CalibrationProfile.baselineEyeDistancePx].
 * - neutralBrowRatio / raisedBrowRatio: median [GazeFeatureVector.browToEyeRatio] at rest and the 75th
 *   percentile while raised; raisedBrowRatio is null when the brow step was skipped or timed out.
 * - naturalBlinkCount / naturalBlinkMedianMs / naturalBlinkP95Ms: natural blinks seen during the eyes-open
 *   step and their durations.
 * - closeBlinkThreshold / openBlinkThreshold / intentionalBlinkMinMs: the personalized detector settings
 *   derived from the levels above (or the app defaults when the measured range was too small, see
 *   [warnings]). The store returns them as saved; callers apply their own setter ranges when restoring them.
 * - meanAmbientLuma / meanEyeConfidence / frameCount: conditions the scan ran under.
 * - warnings: EnrollmentWarning names raised during the scan (LOW_LIGHT, GLARE, SINGLE_EYE,
 *   FEW_NATURAL_BLINKS, EYE_RANGE_SMALL_DEFAULTS_USED, BROW_SKIPPED).
 */
data class EnrollmentBaseline(
    val version: Int = 1,
    val capturedAtMs: Long = 0L,
    val deviceModel: String = "",
    val openEyeLevel: Float,
    val closedEyeLevel: Float,
    val leftOpenLevel: Float,
    val rightOpenLevel: Float,
    val baselineEyeDistancePx: Double,
    val neutralBrowRatio: Float,
    val raisedBrowRatio: Float?,
    val naturalBlinkCount: Int,
    val naturalBlinkMedianMs: Long,
    val naturalBlinkP95Ms: Long,
    val closeBlinkThreshold: Float,
    val openBlinkThreshold: Float,
    val intentionalBlinkMinMs: Long,
    val meanAmbientLuma: Int,
    val meanEyeConfidence: Float,
    val frameCount: Int,
    val warnings: List<String> = emptyList()
)
