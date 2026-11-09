package com.example.ui.viewmodel

import android.os.Build
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AacActionTile
import com.example.data.model.AnalysisResolutionTier
import com.example.data.model.BlinkClassificationEvent
import com.example.data.model.BlinkType
import com.example.data.model.CalibrationProfile
import com.example.data.model.DEFAULT_AFFINE_MATRIX_2X6
import com.example.data.model.EnrollmentBaseline
import com.example.data.model.FixationClusterPoint
import com.example.data.model.GazeFeatureVector
import com.example.data.model.GazePostureProfile
import com.example.data.model.InteractionSettings
import com.example.data.model.LiveGazeTrailPoint
import com.example.data.model.SectionDwellSummary
import com.example.data.model.SectionPassageItem
import com.example.data.model.TrackingSession
import com.example.data.model.calibrationPostureProfile
import com.example.data.repository.EyeTrackingRepository
import com.example.data.repository.LocalTelemetryStore
import com.example.domain.BenchmarkScorecard
import com.example.domain.BlinkHysteresisDetector
import com.example.domain.EnrollmentFailure
import com.example.domain.EnrollmentPhase
import com.example.domain.EnrollmentScanEngine
import com.example.domain.EnrollmentScanState
import com.example.domain.EnrollmentWarning
import com.example.domain.GazeCalibrationMath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt

/** HUD profile name while no calibration profile is applied (the default matrix is in use). */
const val UNCALIBRATED_PROFILE_NAME = "Uncalibrated (default matrix)"

sealed interface UiDataState<out T> {
    data object Loading : UiDataState<Nothing>
    data class Success<T>(val data: T) : UiDataState<T>
    data class Error(val message: String) : UiDataState<Nothing>
}

enum class CalibrationFlowStep {
    IDLE,
    FIT_5_POINTS,
    VALIDATE_4_POINTS,
    COMPLETED_SUMMARY
}

data class CalibrationWizardState(
    val step: CalibrationFlowStep = CalibrationFlowStep.IDLE,
    val includeHeldOutValidation: Boolean = false,
    val currentTargetIndex: Int = 0,
    val currentTargetProgress: Float = 0f,
    val samplesCollectedForCurrentTarget: Int = 0,
    val requiredSamplesPerTarget: Int = 18,
    val pointSampleCounts: Map<Int, Int> = emptyMap(),
    val totalCollectedSamples: Int = 0,
    val isSettlingDelay: Boolean = false,
    val fittedMatrix: List<Double> = DEFAULT_AFFINE_MATRIX_2X6,
    val capturedBaselineEyeDistPx: Double = 148.0,
    // NaN = not measured (the wizard sets these when it reaches COMPLETED_SUMMARY)
    val validationMeanErrorMm: Double = Double.NaN,
    val validationMeanErrorPct: Double = Double.NaN,
    val validationMaxErrorPct: Double = Double.NaN,
    val recommendedGridLabel: String = "Not validated",
    val draftProfileName: String = "Pixel 10 Pro XL Desk",
    // True only when the validation* metrics come from the 4 held-out targets. In 5-point-only mode
    // they are the in-sample residual on the fit targets themselves and must not be shown as validated.
    val metricsAreHeldOut: Boolean = false
)

data class LiveGazeHudState(
    val normX: Float = 0.50f,
    val normY: Float = 0.50f,
    val rawFeature: GazeFeatureVector = GazeFeatureVector(),
    val blinkHoldProgress: Float = 0f,
    val browHoldProgress: Float = 0f,
    val lastBlinkLabel: String = "Ready (Brow 400ms • Blink 350-800ms • Nat 80-349ms)",
    val naturalBlinkCount: Int = 0,
    val intentionalBlinkCount: Int = 0,
    val browRaiseTriggerCount: Int = 0,
    val triggerMode: com.example.data.model.GazeTriggerMode = com.example.data.model.GazeTriggerMode.ALL_ACTIVE,
    val overlayVisualMode: com.example.data.model.GazeOverlayVisualMode = com.example.data.model.GazeOverlayVisualMode.RETICLE_AND_HEATMAP,
    val liveTrailPoints: List<com.example.data.model.LiveGazeTrailPoint> = emptyList(),
    val neutralBrowRatio: Float = 0.34f,
    val raisedBrowRatio: Float = 0.42f,
    val hasDistanceDriftWarning: Boolean = false,
    val showRecalibrateNudge: Boolean = false,
    val isPerformanceModeActive: Boolean = false,
    val resolutionTier: com.example.data.model.AnalysisResolutionTier = com.example.data.model.AnalysisResolutionTier.TIER_HIGH_1280,
    val isSimulatorProxyEnabled: Boolean = false,
    val cloudSyncEnabled: Boolean = false,
    val isDeletingCloudData: Boolean = false,
    val lastCloudDeletionSummary: String? = null,
    val activeProfileName: String = UNCALIBRATED_PROFILE_NAME,
    // Id of the profile applied to the engine (null = default matrix), so screens can match rows by id
    val activeProfileId: String? = null,
    val activeProfileDeviceModel: String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Pixel 10 Pro XL",
    // null = not validated (no calibration applied, or a 5-point fit without held-out validation).
    val activeErrorRadiusPct: Float? = null,
    val activeMaxQuadrantErrorPct: Float? = null,
    val activeErrorMm: Double? = null,
    // True once a calibration profile (validated or not) is applied instead of the default matrix.
    val hasActiveCalibration: Boolean = false,
    // 0 until a frame has been analysed, and again when frames stop arriving.
    val analyzerFps: Int = 0,
    val dwellTriggerMs: Long = 1000L,
    val dwellSensitivityRadiusMultiplier: Float = 1.0f,
    val activeDwellPreset: com.example.data.model.DwellSpeedPreset = com.example.data.model.DwellSpeedPreset.BALANCED,
    val focusedTileId: String? = null,
    val focusedTileDwellProgress: Float = 0f,
    val activePosture: com.example.data.model.GazePostureProfile = com.example.data.model.GazePostureProfile.UPRIGHT,
    val filterMode: com.example.data.model.GazeFilterMode = com.example.data.model.GazeFilterMode.ONE_EURO,
    val backgroundRecalibrationCount: Int = 0,
    val lastBenchmarkReport: com.example.domain.BenchmarkScorecard? = null,
    val isBenchmarkRunning: Boolean = false,
    val lastTriggeredSpeech: String = "Fixate on any tile and raise brows (400ms), command-blink (350–800ms), or dwell to speak.",
    // When this account's face & eye enrollment baseline was captured (EnrollmentBaseline.capturedAtMs);
    // null while no enrollment is applied, so the blink/brow detectors run on the app defaults or a profile.
    val enrolledAtMs: Long? = null
)

data class AttentionSessionLiveState(
    val isRecording: Boolean = false,
    val elapsedSeconds: Int = 0,
    val activeSectionId: String? = null,
    val sectionDwellMsMap: Map<String, Long> = emptyMap(),
    val topZoneMs: Long = 0L,
    val middleZoneMs: Long = 0L,
    val bottomZoneMs: Long = 0L,
    val sessionNaturalBlinks: Int = 0,
    val sessionIntentionalBlinks: Int = 0,
    val computedClusters: List<FixationClusterPoint> = emptyList(),
    val fpsTimeline: List<Int> = emptyList(),
    val thermalThrottleDetected: Boolean = false,
    val selectedReplaySession: TrackingSession? = null
)

class EyeTrackingViewModel(
    private val repository: EyeTrackingRepository,
    private val currentUserId: String,
    private val localStore: LocalTelemetryStore? = null
) : ViewModel() {

    // Emulators have no camera pipeline: every frame there comes from the synthetic simulator ticker.
    private val onEmulator: Boolean = isRunningOnEmulatorHardware()

    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

    private val _localProfilesFlow = MutableStateFlow<List<CalibrationProfile>>(
        localStore?.loadLocalProfiles(currentUserId).orEmpty()
    )
    private val _localSessionsFlow = MutableStateFlow<List<TrackingSession>>(
        localStore?.loadLocalSessions(currentUserId).orEmpty()
    )

    // Consent is per account: a newly signed-in user never inherits another account's opt-in.
    // Mirrors LiveGazeHudState.cloudSyncEnabled (both change only in toggleCloudSync). The cloud streams key
    // off this flag instead of mapping every HUD emission, which happens on every camera frame.
    private val _cloudSyncEnabledFlow = MutableStateFlow(localStore?.isCloudSyncEnabled(currentUserId) ?: false)

    private val _liveHudState = MutableStateFlow(
        LiveGazeHudState(
            cloudSyncEnabled = _cloudSyncEnabledFlow.value,
            isSimulatorProxyEnabled = onEmulator
        )
    )
    val liveHudState: StateFlow<LiveGazeHudState> = _liveHudState.asStateFlow()

    private val _calibrationState = MutableStateFlow(CalibrationWizardState())
    val calibrationState: StateFlow<CalibrationWizardState> = _calibrationState.asStateFlow()

    private val _attentionState = MutableStateFlow(AttentionSessionLiveState())
    val attentionState: StateFlow<AttentionSessionLiveState> = _attentionState.asStateFlow()

    // Active-profile bookkeeping (declared before the flows below, which read it once subscribed).
    // selectedProfileId is the user's explicit choice (activate/save), restored from the local store.
    private var selectedProfileId: String? = localStore?.getActiveProfileId(currentUserId)
    private var appliedProfileKey: String? = null
    private var activeProfileId: String? = null
    private var latestCloudProfiles: List<CalibrationProfile> = emptyList()
    private var latestMergedProfiles: List<CalibrationProfile> = emptyList()
    private val deletedProfileIds = mutableSetOf<String>()

    // Facts about profiles saved in this session. CalibrationProfile persists them too, but a cloud copy
    // (which wins the merge) may not carry them, so the in-session record takes precedence.
    private data class SessionProfileMeta(
        val heldOutValidated: Boolean,
        val calibrationPosture: GazePostureProfile
    )
    private val sessionProfileMeta = mutableMapOf<String, SessionProfileMeta>()

    // Engine state captured when the wizard starts, restored on cancel/retry and when the wizard ends
    // without a save, so an unsaved fit never stays live under the previous profile's name.
    private data class EngineSnapshot(
        val activeMatrix: List<Double>,
        val calibratedMatrix: List<Double>,
        val driftAnchorMatrix: List<Double>,
        val baselineEyeDistPx: Double,
        val calibrationPosture: GazePostureProfile
    )
    private var preWizardSnapshot: EngineSnapshot? = null
    private var pendingCalibrationPosture: GazePostureProfile = GazePostureProfile.UPRIGHT
    private var calibrationUsedSimulatedFrames: Boolean = false

    // Strictly gate Cloud Firestore telemetry streams behind explicit user opt-in (cloudSyncEnabled)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val cloudProfilesResultFlow = _cloudSyncEnabledFlow
        .flatMapLatest { syncEnabled ->
            if (syncEnabled) {
                repository.observeCalibrationProfiles(currentUserId)
                    .map<List<CalibrationProfile>, Result<List<CalibrationProfile>>> { Result.success(it) }
                    .catch { emit(Result.failure(it)) }
            } else {
                flowOf(Result.success(emptyList()))
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val cloudSessionsResultFlow = _cloudSyncEnabledFlow
        .flatMapLatest { syncEnabled ->
            if (syncEnabled) {
                repository.observeTrackingSessions(currentUserId)
                    .map<List<TrackingSession>, Result<List<TrackingSession>>> { Result.success(it) }
                    .catch { emit(Result.failure(it)) }
            } else {
                flowOf(Result.success(emptyList()))
            }
        }

    // Merge Cloud Firestore profiles (when opted-in) with on-device LocalTelemetryStore so sync-off users never lose calibrations
    val calibrationProfilesState: StateFlow<UiDataState<List<CalibrationProfile>>> =
        combine(
            cloudProfilesResultFlow,
            _localProfilesFlow
        ) { cloudResult, localList ->
            val cloudList = cloudResult.getOrNull().orEmpty()
            latestCloudProfiles = cloudList
            val merged = (cloudList + localList)
                .distinctBy { profileKey(it) }
                .sortedByDescending { it.createdAt?.seconds ?: 0L }

            // Only (re)apply when the profile that should be active actually changed. Re-applying on every
            // emission reverted explicit activations and discarded runtime drift/recenter adjustments.
            reconcileActiveProfile(merged)
            if (cloudResult.isFailure && merged.isEmpty()) {
                UiDataState.Error(cloudResult.exceptionOrNull()?.message ?: "Failed to load calibration profiles")
            } else {
                UiDataState.Success(merged)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiDataState.Loading
        )

    val trackingSessionsState: StateFlow<UiDataState<List<TrackingSession>>> =
        combine(
            cloudSessionsResultFlow,
            _localSessionsFlow
        ) { cloudResult, localList ->
            val cloudList = cloudResult.getOrNull().orEmpty()
            val merged = (cloudList + localList)
                .distinctBy { it.id.ifBlank { it.title + it.createdAt } }
                .sortedByDescending { it.createdAt?.seconds ?: 0L }

            if (cloudResult.isFailure && merged.isEmpty()) {
                UiDataState.Error(cloudResult.exceptionOrNull()?.message ?: "Failed to load tracking sessions")
            } else {
                UiDataState.Success(merged)
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiDataState.Loading
        )

    private var activeMatrix: List<Double> = DEFAULT_AFFINE_MATRIX_2X6
    // Matrix as fitted/loaded (bounds for recenter), and the anchor that background drift updates stay near
    private var calibratedMatrix: List<Double> = DEFAULT_AFFINE_MATRIX_2X6
    private var driftAnchorMatrix: List<Double> = DEFAULT_AFFINE_MATRIX_2X6
    private var activeBaselineEyeDistPx: Double = 148.0
    // Posture the active matrix was calibrated in; only the posture difference is applied live
    private var calibrationPosture: GazePostureProfile = GazePostureProfile.UPRIGHT
    private val blinkDetector = BlinkHysteresisDetector(
        closeThreshold = DEFAULT_CLOSE_BLINK_THRESHOLD,
        openThreshold = DEFAULT_OPEN_BLINK_THRESHOLD,
        intentionalMinMs = DEFAULT_INTENTIONAL_BLINK_MIN_MS,
        maxCommandMs = 800L
    )
    private val browDetector = com.example.domain.BrowRaiseHysteresisDetector(
        neutralBrowRatio = 0.34f,
        raisedBrowRatio = 0.42f,
        holdTargetMs = 400L
    )

    // Face & eye enrollment scan (domain state machine) and the baseline it produced for this account.
    // The baseline holds derived numbers only (no images) and lives in the on-device vault; it is never
    // synced. While the engine is idle the per-frame pipeline does not touch it.
    private val enrollmentEngine = EnrollmentScanEngine(
        deviceModel = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Pixel 10 Pro XL"
    )
    private val _enrollmentState = MutableStateFlow(EnrollmentScanState())
    val enrollmentState: StateFlow<EnrollmentScanState> = _enrollmentState.asStateFlow()
    private val _enrollmentBaseline = MutableStateFlow(localStore?.loadEnrollmentBaseline(currentUserId))
    val enrollmentBaseline: StateFlow<EnrollmentBaseline?> = _enrollmentBaseline.asStateFlow()
    // The profile currently applied to the engine (null = default matrix), so clearing the enrollment can
    // put that profile's stored blink settings back instead of the app defaults.
    private var appliedProfile: CalibrationProfile? = null

    // Speed-adaptive One Euro Filter and closed-form Kalman Filter for screen-space gaze reticle.
    // Screen coordinates are normalized [0,1] (saccades ~1-10 units/s), so beta must be of order 1 for the
    // 1€ cutoff to rise during saccades (beta 0.04 behaved as a fixed ~1 Hz low-pass, ~460 ms to 90%).
    // Kalman Q = 1.0 settles a step in ~330 ms (Q = 1e-3 lagged ~1.7 s and overshot).
    private val screenGazeFilter = com.example.domain.OneEuroFilter2D(minCutoff = 0.90, beta = 1.2, dCutoff = 1.0)
    private val screenKalmanFilter = com.example.domain.GazeKalmanFilter2D(processNoiseQ = 1.0, measurementNoiseR = 1e-2)
    // Per-frame scratch for the allocation-free projection and filter calls (a Pair and two boxed values
    // each per frame otherwise). Main-thread single writer; read back into locals right after each call.
    private val projectedGazeScratch = FloatArray(2)
    private val filteredGazeScratch = DoubleArray(2)

    // Calibration sample buffers
    private val fitSamples = mutableListOf<Pair<GazeFeatureVector, Pair<Float, Float>>>()
    private val validationSamples = mutableListOf<Pair<GazeFeatureVector, Pair<Float, Float>>>()
    private var samplesForCurrentTarget = 0
    private var targetStartTimeMs = 0L

    // Screen dimensions in mm (defaults to Google Pixel 10 Pro XL 6.8" LTPO OLED active area: 71.3mm x 158.4mm)
    private var displayWidthMm: Double = GazeCalibrationMath.PIXEL_10_PRO_XL_WIDTH_MM
    private var displayHeightMm: Double = GazeCalibrationMath.PIXEL_10_PRO_XL_HEIGHT_MM

    // Frame cadence, <15 FPS resolution auto-step-down (1280 -> 960 -> 640), thermal guard & edge-pinning tracker
    private var lastFrameTimestampMs: Long = 0L
    private var smoothedFrameIntervalMs: Double = 66.0
    private var below15FpsDurationMs: Long = 0L
    private var lastResolutionStepChangeMs: Long = 0L
    private var lowFpsDurationMs: Long = 0L
    private var fpsRecoveryDurationMs: Long = 0L
    private var above20FpsDurationMs: Long = 0L
    private var sessionBaselineFps: Double = 15.0
    private var edgePinnedDurationMs: Long = 0L
    // Wall-clock arrival of the last frame (frame timestamps may use another time base in tests)
    private var lastFrameWallClockMs: Long = 0L
    private var frameWatchdogJob: Job? = null

    // Dwell tracking & layout-measured section ID. Dwell time accumulates only on frames with valid gaze.
    private var currentHoveredTileId: String? = null
    private var hoverAccumulatedMs: Long = 0L
    private var hoverInvalidRunMs: Long = 0L
    private var dwellTriggeredForCurrentHover: Boolean = false
    private var lastGazeFireTileId: String? = null
    private var lastGazeFireMs: Long = 0L
    private var onTileTriggeredCallback: ((AacActionTile) -> Unit)? = null
    private var layoutHitSectionId: String? = null
    private var readingLayoutAttached: Boolean = false
    // Reticle normalized to the Attention passage Box (the heatmap frame); null = off the visible passage
    private var layoutPassageGazePoint: Offset? = null
    // Normalized (root-overlay frame) tile centres, the reference for background drift correction
    private val tileCentersNorm = mutableMapOf<String, Pair<Float, Float>>()
    // This frame's smoothed gaze, so trigger side effects use the current position, not the previous frame's
    private var currentGazeX: Float = 0.50f
    private var currentGazeY: Float = 0.50f
    private var lastBrowEvalMs: Long = 0L

    // 'Recenter Gaze' look-at-centre capture
    private var recenterCaptureFromMs: Long = 0L
    private var recenterDeadlineMs: Long = 0L
    private val recenterSamples = ArrayList<Pair<Float, Float>>(RECENTER_SAMPLE_COUNT)

    // Simulator input bookkeeping: ticker repeats only count for calibration after a fresh simulator input
    private var lastSimulatorInputMs: Long = 0L

    // Attention session raw trajectory (older samples are folded into archived clusters when the buffer fills)
    private val rawSessionGazeSamples = mutableListOf<Triple<Float, Float, Long>>()
    private val archivedSessionClusters = mutableListOf<FixationClusterPoint>()
    private var sessionUsedSimulatedFrames: Boolean = false
    private var lastAttentionSampleMs = 0L
    private var sessionTimerJob: Job? = null
    private var simulatorTickerJob: Job? = null

    // Full 9-tile deck supporting 3x3 Grid (when validation error <= 12% mean / <= 16.5% max) or 2x2 Grid
    private val defaultAacTiles: List<AacActionTile> = listOf(
        AacActionTile("tile_yes", "Affirm / Yes", "Confirm current prompt", "Yes, that is correct.", "COMMUNICATION"),
        AacActionTile("tile_no", "Decline / No", "Reject or pause", "No, please wait a moment.", "COMMUNICATION"),
        AacActionTile("tile_water", "Request Water", "Hydration assistance", "Could I please have some water?", "NEEDS"),
        AacActionTile("tile_comfort", "Adjust Position", "Posture or headrest", "I need help adjusting my seating position.", "NEEDS"),
        AacActionTile("tile_read_aloud", "Read Summary", "Synthesize passage status", "Reading current passage analytics aloud.", "READER"),
        AacActionTile("tile_recenter", "Recenter Gaze", "Zero-point offset snap", "Recentering. Look at the centre of the screen.", "SYSTEM"),
        AacActionTile("tile_thanks", "Thank You", "Express gratitude", "Thank you very much for your help.", "COMMUNICATION"),
        AacActionTile("tile_question", "Ask Question", "Request clarification", "I have a question I would like to ask.", "COMMUNICATION"),
        AacActionTile("tile_rest", "Eyes Tired", "Tell your carer you need a rest", "My eyes need a short rest break.", "NEEDS")
    )
    private val defaultReadAloudPhrase: String =
        defaultAacTiles.first { it.id == "tile_read_aloud" }.speechPhrase

    private val _aacTilesState = MutableStateFlow<List<AacActionTile>>(
        localStore?.loadCustomAacTiles(currentUserId, defaultAacTiles) ?: defaultAacTiles
    )
    val aacTilesState: StateFlow<List<AacActionTile>> = _aacTilesState.asStateFlow()
    val aacTiles: List<AacActionTile>
        get() = _aacTilesState.value

    // wordCount is derived from the body text so effective-WPM figures use the real passage length
    val readingPassageSections: List<SectionPassageItem> = listOf(
        passageSection(
            sectionId = "sec_1",
            heading = "1. Optical Corneal Reflection & Visible-Light Constraints",
            body = "Unlike dedicated infrared eye trackers that illuminate the retina to produce a high-contrast bright-pupil reflex, mobile front-facing RGB cameras rely on ambient visible light. Consequently, OculaGaze crops out the upper 25% eyelash shadow band, stretches local ROI contrast, and weights each eye by its glare-free confidence to achieve reliable region-level gaze estimation."
        ),
        passageSection(
            sectionId = "sec_2",
            heading = "2. Standardized 2×6 Ridge Regression & Head-Pose Coupling",
            body = "When viewing a handheld smartphone, subtle head rotations cross-couple with eye-in-orbit rotations. Mapping a unit-scaled 6D feature vector—combining confidence-fused iris centroids, 3D head Euler angles, and inter-ocular scale—through a regularized 2×6 affine matrix compensates for head-pose drift across the screen."
        ),
        passageSection(
            sectionId = "sec_3",
            heading = "3. Expression & Hysteresis Gating vs. Dwell Ergonomics",
            body = "Humans blink involuntarily 12 to 18 times per minute with typical closures lasting 80 to 349 milliseconds. By combining pitch-compensated brow-raise triggers (400 ms hold) with Schmitt-trigger command blinks (350 to 800 ms) and region dwell, hands-free UI tiles avoid false activations during natural reading."
        )
    )

    init {
        // Layering at launch: the enrollment baseline personalizes the detectors first; the saved interaction
        // settings (which mirror the enrolled brow ratios, or a later manual brow calibration) and then the
        // active profile's stored blink settings are applied on top of it.
        _enrollmentBaseline.value?.let { applyEnrollmentBaselineToDetectors(it) }
        restoreInteractionSettings()
        val localProfiles = _localProfilesFlow.value
        val localInitial = localProfiles.firstOrNull { it.id == selectedProfileId }
            ?: localProfiles.firstOrNull { it.isActive }
            ?: localProfiles.firstOrNull()
        if (localInitial != null) {
            applyProfileToEngine(localInitial)
        }
        if (onEmulator) {
            // No camera on emulators: the HUD reports SIM PROXY (set in the initial state), never an optical lock
            startOrUpdateSyntheticTicker(forceRun = true)
        }
    }

    /**
     * Entry point for frames from the camera analysis thread. Hops to the main thread so all ViewModel
     * state (filters, detectors, dwell, calibration and attention buffers) has a single writer, and the
     * tile-trigger listener (haptics/TTS) runs on Main. Frames are dropped while the simulator is on.
     */
    fun submitCameraFrame(feature: GazeFeatureVector) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
            if (!_liveHudState.value.isSimulatorProxyEnabled) processIncomingFeatureFrame(feature)
        }
    }

    fun setTriggerMode(mode: com.example.data.model.GazeTriggerMode) {
        _liveHudState.update { it.copy(triggerMode = mode) }
        persistInteractionSettings()
    }

    /**
     * Restores this account's saved dwell, trigger, filter, posture and brow settings (A101). The store
     * returns values unclamped, so they pass through the setters' own ranges; brow ratios that no valid
     * calibration could have produced are ignored and the defaults kept.
     */
    private fun restoreInteractionSettings() {
        val saved = localStore?.loadInteractionSettings(currentUserId) ?: return
        val neutral = saved.neutralBrowRatio
        val raised = saved.raisedBrowRatio
        val browValid = neutral in 0.20f..0.52f && raised in 0.24f..0.62f &&
            raised >= neutral + MIN_BROW_RAISE_SEPARATION
        if (browValid) {
            browDetector.neutralBrowRatio = neutral
            browDetector.raisedBrowRatio = raised
        }
        _liveHudState.update {
            it.copy(
                dwellTriggerMs = saved.dwellTriggerMs.coerceIn(400L, 2500L),
                dwellSensitivityRadiusMultiplier = saved.dwellSensitivityRadiusMultiplier.coerceIn(0.6f, 2.0f),
                activeDwellPreset = saved.activeDwellPreset,
                triggerMode = saved.triggerMode,
                filterMode = saved.filterMode,
                activePosture = saved.activePosture,
                neutralBrowRatio = if (browValid) neutral else it.neutralBrowRatio,
                raisedBrowRatio = if (browValid) raised else it.raisedBrowRatio
            )
        }
    }

    /** Saves the current interaction settings for this account (cleared with the local vault). */
    private fun persistInteractionSettings() {
        val hud = _liveHudState.value
        localStore?.saveInteractionSettings(
            currentUserId,
            InteractionSettings(
                dwellTriggerMs = hud.dwellTriggerMs,
                dwellSensitivityRadiusMultiplier = hud.dwellSensitivityRadiusMultiplier,
                activeDwellPreset = hud.activeDwellPreset,
                triggerMode = hud.triggerMode,
                filterMode = hud.filterMode,
                activePosture = hud.activePosture,
                neutralBrowRatio = hud.neutralBrowRatio,
                raisedBrowRatio = hud.raisedBrowRatio
            )
        )
    }

    fun cycleOverlayVisualMode() {
        val modes = com.example.data.model.GazeOverlayVisualMode.entries
        val current = _liveHudState.value.overlayVisualMode
        val next = modes[(modes.indexOf(current) + 1) % modes.size]
        _liveHudState.update { it.copy(overlayVisualMode = next) }
    }

    fun cycleAnalysisResolutionTier() {
        val tiers = com.example.data.model.AnalysisResolutionTier.entries
        val current = _liveHudState.value.resolutionTier
        val next = tiers[(tiers.indexOf(current) + 1) % tiers.size]
        below15FpsDurationMs = 0L
        above20FpsDurationMs = 0L
        lastResolutionStepChangeMs = System.currentTimeMillis()
        _liveHudState.update { it.copy(resolutionTier = next) }
    }

    fun calibrateNeutralBrowBaseline() {
        val feature = _liveHudState.value.rawFeature
        if (!feature.faceDetected) {
            // Face-less frames carry the default 0.34 ratio, which is not a measurement
            _statusBannerMessage.value = "No face detected — face the front camera and try again."
            return
        }
        val currentRatio = feature.browToEyeRatio.coerceIn(0.20f, 0.52f)
        val raised = maxOf(currentRatio + 0.055f, browDetector.raisedBrowRatio)
        browDetector.neutralBrowRatio = currentRatio
        browDetector.raisedBrowRatio = raised
        _liveHudState.update {
            it.copy(neutralBrowRatio = currentRatio, raisedBrowRatio = raised)
        }
        persistInteractionSettings()
        _statusBannerMessage.value = "Captured Neutral Brow baseline (${String.format(Locale.US, "%.3f", currentRatio)}). Now raise eyebrows and tap 'Sample Raised Brow'."
    }

    fun calibrateRaisedBrowPeak() {
        val feature = _liveHudState.value.rawFeature
        if (!feature.faceDetected) {
            _statusBannerMessage.value = "No face detected — face the front camera and try again."
            return
        }
        val currentRatio = feature.browToEyeRatio.coerceIn(0.24f, 0.62f)
        val neutral = browDetector.neutralBrowRatio
        if (currentRatio < neutral + MIN_BROW_RAISE_SEPARATION) {
            // Never substitute a made-up raise: keep the previous calibration and ask again
            _statusBannerMessage.value = "Brow raise not detected (${String.format(Locale.US, "%.3f", currentRatio)} vs neutral ${String.format(Locale.US, "%.3f", neutral)}) — raise higher and retry."
            return
        }
        browDetector.raisedBrowRatio = currentRatio
        _liveHudState.update {
            it.copy(raisedBrowRatio = currentRatio)
        }
        persistInteractionSettings()
        _statusBannerMessage.value = "Expression calibrated: Neutral ${String.format(Locale.US, "%.3f", neutral)} → Raised ${String.format(Locale.US, "%.3f", currentRatio)} (Trigger @ ${String.format(Locale.US, "%.3f", browDetector.raiseThreshold)})."
    }

    fun simulateBrowRaiseCommand() {
        val now = System.currentTimeMillis()
        lastSimulatorInputMs = now
        val base = _liveHudState.value.rawFeature
        val peakRatio = (browDetector.raisedBrowRatio + 0.03f).coerceAtMost(0.60f)
        processFrameInternal(base.copy(browToEyeRatio = peakRatio, faceDetected = true, isSimulated = true, timestampMs = now), synthetic = true)
        viewModelScope.launch {
            delay(430L)
            val holdTs = System.currentTimeMillis()
            lastSimulatorInputMs = holdTs
            processFrameInternal(base.copy(browToEyeRatio = peakRatio, faceDetected = true, isSimulated = true, timestampMs = holdTs), synthetic = true)
            delay(120L)
            val relaxTs = System.currentTimeMillis()
            lastSimulatorInputMs = relaxTs
            processFrameInternal(base.copy(browToEyeRatio = browDetector.neutralBrowRatio, faceDetected = true, isSimulated = true, timestampMs = relaxTs), synthetic = true)
        }
    }

    /**
     * Section under the reticle reported by the Attention screen while its passage layout is attached.
     * null or [OFF_PASSAGE_SECTION_ID] means the gaze is on no section: no section dwell is credited.
     */
    fun updateActiveReadingSectionFromLayout(sectionId: String?) {
        readingLayoutAttached = true
        layoutHitSectionId = sectionId
    }

    /**
     * Reticle position normalized to the Attention passage Box (the frame the fixation heatmap is drawn in),
     * or null while the reticle is off the visible passage. Fixation samples are recorded only in this frame.
     */
    fun updatePassageGazePoint(point: Offset?) {
        layoutPassageGazePoint = point?.takeIf { it.x.isFinite() && it.y.isFinite() }
    }

    /** Called when the Attention passage leaves composition: stop crediting section dwell. */
    fun detachReadingSectionLayout() {
        readingLayoutAttached = false
        layoutHitSectionId = null
        layoutPassageGazePoint = null
    }

    fun updateAacTilePhrase(tileId: String, newTitle: String, newSpeechPhrase: String) {
        val cleanTitle = newTitle.trim().takeIf { it.isNotEmpty() } ?: return
        val cleanPhrase = newSpeechPhrase.trim().takeIf { it.isNotEmpty() } ?: return
        val updated = _aacTilesState.value.map { tile ->
            if (tile.id == tileId) tile.copy(title = cleanTitle, speechPhrase = cleanPhrase) else tile
        }
        _aacTilesState.value = updated
        localStore?.saveCustomAacTiles(currentUserId, updated)
        _statusBannerMessage.value = "Updated phrase tile \"$cleanTitle\"."
    }

    fun dismissRecalibrateNudge() {
        edgePinnedDurationMs = 0L
        _liveHudState.update { it.copy(showRecalibrateNudge = false) }
    }

    /**
     * Physical size of the frame gaze normX/normY is normalized to (MainActivity's root content Box), used
     * to convert calibration errors to mm. It is the measured frame on every model: substituting the full
     * panel size would overstate mm errors. Bounds are orientation-agnostic (short side / long side), so
     * a landscape frame keeps its real height.
     */
    fun setDisplayPhysicalSizeMm(widthMm: Double, heightMm: Double) {
        if (!widthMm.isFinite() || !heightMm.isFinite() || widthMm <= 0.0 || heightMm <= 0.0) return
        val landscape = widthMm > heightMm
        val shortMm = minOf(widthMm, heightMm).coerceIn(MIN_FRAME_SIDE_MM, MAX_FRAME_SHORT_SIDE_MM)
        val longMm = maxOf(widthMm, heightMm).coerceIn(MIN_FRAME_SIDE_MM, MAX_FRAME_LONG_SIDE_MM)
        displayWidthMm = if (landscape) longMm else shortMm
        displayHeightMm = if (landscape) shortMm else longMm
    }

    fun registerTileTriggerListener(listener: (AacActionTile) -> Unit) {
        onTileTriggeredCallback = listener
    }

    fun dismissStatusBanner() {
        _statusBannerMessage.value = null
    }

    fun toggleCloudSync(enabled: Boolean) {
        localStore?.setCloudSyncEnabled(currentUserId, enabled)
        _liveHudState.update { it.copy(cloudSyncEnabled = enabled) }
        _cloudSyncEnabledFlow.value = enabled
        _statusBannerMessage.value = if (enabled) {
            "Cloud Telemetry OPTED IN — calibrations & sessions sync to Firestore (/users/$currentUserId) and local storage."
        } else {
            "Cloud Telemetry OPTED OUT — Firestore sync halted; profiles & sessions stay strictly on-device."
        }
    }

    fun toggleSimulatorProxyMode(enabled: Boolean) {
        // An emulator has no camera, so the synthetic ticker stays on and the HUD keeps saying SIM PROXY
        val effective = enabled || onEmulator
        _liveHudState.update { it.copy(isSimulatorProxyEnabled = effective) }
        startOrUpdateSyntheticTicker(forceRun = effective)
    }

    private fun startOrUpdateSyntheticTicker(forceRun: Boolean) {
        simulatorTickerJob?.cancel()
        if (forceRun) {
            simulatorTickerJob = viewModelScope.launch {
                while (true) {
                    delay(66L) // ~15 FPS realistic cadence
                    val current = _liveHudState.value.rawFeature
                    val now = System.currentTimeMillis()
                    processFrameInternal(
                        current.copy(
                            faceDetected = true,
                            isSimulated = true,
                            timestampMs = now
                        ),
                        synthetic = true,
                        syntheticRepeat = true
                    )
                }
            }
        }
    }

    fun updateSimulatorGazeTarget(normX: Float, normY: Float, simulatedEyeOpen: Float = 0.92f) {
        // Never switch a real device into simulator mode implicitly (that silently drops camera frames
        // and calibrates on synthetic features); the simulator must be turned on explicitly.
        if (!_liveHudState.value.isSimulatorProxyEnabled && !onEmulator) return
        lastSimulatorInputMs = System.currentTimeMillis()
        val syntheticGx = ((normX - 0.5f) * 0.42f).toDouble()
        val syntheticGy = ((normY - 0.5f) * 0.36f).toDouble()
        val syntheticYaw = ((normX - 0.5f) * 14f).toDouble()
        val syntheticPitch = ((normY - 0.5f) * 12f).toDouble()
        val shiftX = (normX - 0.5f) * 0.07f
        val shiftY = (normY - 0.5f) * 0.05f
        val vec = GazeFeatureVector(
            gx = syntheticGx,
            gy = syntheticGy,
            yaw = syntheticYaw,
            pitch = syntheticPitch,
            roll = 0.0,
            eyeDistancePx = activeBaselineEyeDistPx,
            leftEyeOpenProb = simulatedEyeOpen,
            rightEyeOpenProb = simulatedEyeOpen,
            leftEyeConfidence = 0.94f,
            rightEyeConfidence = 0.94f,
            browToEyeRatio = browDetector.neutralBrowRatio,
            hasGlareWarning = false,
            faceDetected = true,
            leftEyeNormX = 0.38f,
            leftEyeNormY = 0.42f,
            rightEyeNormX = 0.62f,
            rightEyeNormY = 0.42f,
            leftPupilNormX = (0.38f + shiftX).coerceIn(0.32f, 0.44f),
            leftPupilNormY = (0.42f + shiftY).coerceIn(0.37f, 0.47f),
            rightPupilNormX = (0.62f + shiftX).coerceIn(0.56f, 0.68f),
            rightPupilNormY = (0.42f + shiftY).coerceIn(0.37f, 0.47f),
            timestampMs = System.currentTimeMillis(),
            isSimulated = true
        )
        processFrameInternal(vec, synthetic = true)
    }

    fun simulateIntentionalCommandBlink() {
        val now = System.currentTimeMillis()
        lastSimulatorInputMs = now
        val base = _liveHudState.value.rawFeature
        processFrameInternal(base.copy(leftEyeOpenProb = 0.10f, rightEyeOpenProb = 0.10f, faceDetected = true, isSimulated = true, timestampMs = now), synthetic = true)
        viewModelScope.launch {
            delay(460L)
            val reopenTs = System.currentTimeMillis()
            lastSimulatorInputMs = reopenTs
            processFrameInternal(base.copy(leftEyeOpenProb = 0.92f, rightEyeOpenProb = 0.92f, faceDetected = true, isSimulated = true, timestampMs = reopenTs), synthetic = true)
        }
    }

    /** Processes one gaze feature frame. Must be called on the main thread (see [submitCameraFrame]). */
    fun processIncomingFeatureFrame(feature: GazeFeatureVector) {
        processFrameInternal(feature, synthetic = false)
    }

    /**
     * @param synthetic the frame was produced by the simulator / emulator ticker, not measured.
     * @param syntheticRepeat a ticker re-injection of the last simulator vector; it only feeds the
     *   calibration wizard after a fresh simulator input during the current target.
     */
    private fun processFrameInternal(
        feature: GazeFeatureVector,
        synthetic: Boolean,
        syntheticRepeat: Boolean = false
    ) {
        val now = feature.timestampMs
        val simulatedFrame = synthetic || feature.isSimulated || onEmulator
        // While the enrollment scan runs, gaze may not activate tiles (the person is following the scan's
        // instructions, not choosing a tile); the blink and brow counters keep working.
        val enrollmentActive = isEnrollmentScanActive()
        lastFrameWallClockMs = System.currentTimeMillis()
        ensureFrameWatchdog()
        // Gap since the previous frame (-1 when unknown, e.g. first frame or after the stale-frame watchdog)
        val rawGapMs = if (lastFrameTimestampMs > 0L) now - lastFrameTimestampMs else -1L
        val dtMs = if (lastFrameTimestampMs > 0L) rawGapMs.coerceIn(16L, 500L) else 66L
        lastFrameTimestampMs = now
        smoothedFrameIntervalMs = 0.85 * smoothedFrameIntervalMs + 0.15 * dtMs.toDouble()
        val liveFps = (1000.0 / smoothedFrameIntervalMs.coerceAtLeast(25.0)).roundToInt().coerceIn(1, 60)

        // Adaptive Performance Mode & Thermal Guard: step down ROI workload if FPS < 12 for >3s,
        // or if session FPS drops >40% below first-minute baseline during a recording;
        // recover at >= 20 FPS, or after a sustained 5 s at >= 15 FPS (the normal camera cadence).
        val prev = _liveHudState.value
        lowFpsDurationMs = if (liveFps < 12) (lowFpsDurationMs + dtMs).coerceAtMost(15000L) else 0L
        fpsRecoveryDurationMs = if (liveFps >= 15) (fpsRecoveryDurationMs + dtMs).coerceAtMost(20000L) else 0L
        // The thermal flag only describes the recording it was detected in
        val thermalDrop = _attentionState.value.let { it.isRecording && it.thermalThrottleDetected }
        val perfMode = when {
            lowFpsDurationMs >= 3000L || thermalDrop -> true
            liveFps >= 20 -> false
            fpsRecoveryDurationMs >= PERF_MODE_RECOVERY_MS -> false
            else -> prev.isPerformanceModeActive
        }

        // Auto-step the analysis resolution down (1280x960 -> 960x720 -> 640x480) whenever the measured
        // analysis rate drops below 15 FPS for >2.5s or thermal throttling is flagged during a recording,
        // and back up one tier after a sustained 10 s at >= 20 FPS.
        below15FpsDurationMs = if (liveFps < 15 && !prev.isSimulatorProxyEnabled) {
            (below15FpsDurationMs + dtMs).coerceAtMost(20000L)
        } else {
            (below15FpsDurationMs - dtMs / 2).coerceAtLeast(0L)
        }
        above20FpsDurationMs = if (liveFps >= 20 && !thermalDrop && !prev.isSimulatorProxyEnabled) {
            (above20FpsDurationMs + dtMs).coerceAtMost(20000L)
        } else {
            0L
        }
        val canStepResolution = (now - lastResolutionStepChangeMs) >= 6000L
        val lowRateStep = below15FpsDurationMs >= 2500L
        // The step-down cause label is built only on the frame that steps (it was a per-frame string build)
        val nextTier = if (canStepResolution && (lowRateStep || thermalDrop)) {
            when (prev.resolutionTier) {
                AnalysisResolutionTier.TIER_HIGH_1280 -> {
                    lastResolutionStepChangeMs = now
                    below15FpsDurationMs = 0L
                    _statusBannerMessage.value =
                        "${resolutionStepCauseLabel(lowRateStep, liveFps)}: stepping camera analysis 1280×960 → 960×720."
                    AnalysisResolutionTier.TIER_MED_960
                }
                AnalysisResolutionTier.TIER_MED_960 -> {
                    lastResolutionStepChangeMs = now
                    below15FpsDurationMs = 0L
                    _statusBannerMessage.value =
                        "${resolutionStepCauseLabel(lowRateStep, liveFps)}: stepping camera analysis 960×720 → 640×480."
                    AnalysisResolutionTier.TIER_LOW_640
                }
                AnalysisResolutionTier.TIER_LOW_640 -> AnalysisResolutionTier.TIER_LOW_640
            }
        } else if (canStepResolution && above20FpsDurationMs >= RESOLUTION_STEP_UP_HOLD_MS) {
            when (prev.resolutionTier) {
                AnalysisResolutionTier.TIER_LOW_640 -> {
                    lastResolutionStepChangeMs = now
                    above20FpsDurationMs = 0L
                    _statusBannerMessage.value =
                        "Analysis rate recovered ($liveFps FPS for 10 s): stepping camera analysis 640×480 → 960×720."
                    AnalysisResolutionTier.TIER_MED_960
                }
                AnalysisResolutionTier.TIER_MED_960 -> {
                    lastResolutionStepChangeMs = now
                    above20FpsDurationMs = 0L
                    _statusBannerMessage.value =
                        "Analysis rate recovered ($liveFps FPS for 10 s): stepping camera analysis 960×720 → 1280×960."
                    AnalysisResolutionTier.TIER_HIGH_1280
                }
                AnalysisResolutionTier.TIER_HIGH_1280 -> AnalysisResolutionTier.TIER_HIGH_1280
            }
        } else {
            prev.resolutionTier
        }

        val projected = GazeCalibrationMath.projectGazeInto(
            feature = feature,
            matrixCoeffs = activeMatrix,
            baselineEyeDistPx = activeBaselineEyeDistPx,
            out = projectedGazeScratch
        )
        val rawPredX = projected[0]
        val rawPredY = projected[1]

        // Posture profile compensation (pitch & gravity shift for reclining/lying down). The fitted
        // intercept already absorbed the posture used during calibration, so only the difference applies.
        val posture = prev.activePosture
        val postureAdjustedX = (rawPredX + (posture.xBias - calibrationPosture.xBias)).coerceIn(0.02f, 0.98f)
        val postureAdjustedY = (rawPredY + (posture.yBias - calibrationPosture.yBias)).coerceIn(0.02f, 0.98f)

        // CRITICAL AUDIT FIX: Freeze gaze position updates while eyelids are closing/closed (< 0.50 open prob)
        // so upper-eyelid sweep never drags the reticle off the focused tile during an intentional blink!
        val eyeOpenProb = feature.combinedEyeOpenProb
        val isEyelidClosing = eyeOpenProb < 0.50f
        val gazeValid = feature.faceDetected && !isEyelidClosing

        if (recenterDeadlineMs > 0L) {
            updateRecenterCapture(postureAdjustedX, postureAdjustedY, gazeValid, now)
        }

        // Plain locals instead of a Pair<Float, Float> (a Pair and two boxed Floats per frame), and the
        // filters write into the scratch array instead of returning a Pair<Double, Double>
        var smoothedX = prev.normX
        var smoothedY = prev.normY
        if (gazeValid) {
            when (prev.filterMode) {
                com.example.data.model.GazeFilterMode.ONE_EURO -> {
                    val filtered = screenGazeFilter.filterInto(
                        postureAdjustedX.toDouble(), postureAdjustedY.toDouble(), now, filteredGazeScratch
                    )
                    smoothedX = filtered[0].toFloat().coerceIn(0.02f, 0.98f)
                    smoothedY = filtered[1].toFloat().coerceIn(0.02f, 0.98f)
                }
                com.example.data.model.GazeFilterMode.KALMAN -> {
                    val filtered = screenKalmanFilter.filterInto(
                        postureAdjustedX.toDouble(), postureAdjustedY.toDouble(), now, filteredGazeScratch
                    )
                    smoothedX = filtered[0].toFloat().coerceIn(0.02f, 0.98f)
                    smoothedY = filtered[1].toFloat().coerceIn(0.02f, 0.98f)
                }
                com.example.data.model.GazeFilterMode.RAW -> {
                    smoothedX = postureAdjustedX
                    smoothedY = postureAdjustedY
                }
            }
        }
        currentGazeX = smoothedX
        currentGazeY = smoothedY

        val mode = prev.triggerMode
        val blinkEnabled = mode == com.example.data.model.GazeTriggerMode.ALL_ACTIVE ||
            mode == com.example.data.model.GazeTriggerMode.COMMAND_BLINK
        val browEnabled = mode == com.example.data.model.GazeTriggerMode.ALL_ACTIVE ||
            mode == com.example.data.model.GazeTriggerMode.BROW_RAISE

        // No-face frames carry default (unmeasured) eye-open values: hold the blink state instead of
        // reading them as a reopen, so a dropout during a long rest cannot end it as a command blink.
        // (Split locals: the held / inactive branches no longer allocate a Pair per frame.)
        val blinkProgress: Float
        val blinkEvent: BlinkClassificationEvent?
        if (feature.faceDetected) {
            val blinkResult = blinkDetector.update(eyeOpenProb, now)
            blinkProgress = blinkResult.first
            blinkEvent = blinkResult.second
        } else {
            blinkProgress = prev.blinkHoldProgress
            blinkEvent = null
        }
        val browProgress: Float
        val browFired: Boolean
        if (browEnabled && gazeValid) {
            // Updates skipped for longer than the hold (eyelid closure) require a fresh full hold
            if (lastBrowEvalMs > 0L && now - lastBrowEvalMs > browDetector.holdTargetMs) {
                browDetector.cancelPendingRaise()
            }
            lastBrowEvalMs = now
            val browResult = browDetector.update(feature.browToEyeRatio, now)
            browProgress = browResult.first
            browFired = browResult.second
        } else {
            if (!browEnabled || !feature.faceDetected) {
                // A raise that started before the gap must not fire on resume without a new 400 ms hold
                browDetector.cancelPendingRaise()
            }
            browProgress = 0f
            browFired = false
        }

        var natBlinks = prev.naturalBlinkCount
        var cmdBlinks = prev.intentionalBlinkCount
        var browCount = prev.browRaiseTriggerCount
        var blinkLabel = prev.lastBlinkLabel

        if (browFired) {
            browCount++
            val holdMs = browDetector.holdTargetMs
            blinkLabel = if (enrollmentActive) {
                "Brow raise ($holdMs ms hold) — ignored during the enrollment scan"
            } else {
                when (fireFocusedTileByGesture("Brow Raise", now)) {
                    GestureTriggerOutcome.FIRED -> "Brow-Raise Trigger ($holdMs ms hold) — tile triggered!"
                    GestureTriggerOutcome.SUPPRESSED_REPEAT -> "Brow raise ($holdMs ms hold) — repeat on the same tile suppressed"
                    GestureTriggerOutcome.NO_TILE -> "Brow raise ($holdMs ms hold) — no tile focused"
                }
            }
        }

        if (blinkEvent != null) {
            when (blinkEvent.type) {
                BlinkType.NATURAL -> {
                    natBlinks++
                    blinkLabel = "Natural blink (${blinkEvent.durationMs} ms) — ignored for triggers"
                    if (_attentionState.value.isRecording) {
                        _attentionState.update { it.copy(sessionNaturalBlinks = it.sessionNaturalBlinks + 1) }
                    }
                }
                BlinkType.INTENTIONAL_COMMAND -> {
                    cmdBlinks++
                    if (blinkEnabled) {
                        if (_attentionState.value.isRecording) {
                            _attentionState.update { it.copy(sessionIntentionalBlinks = it.sessionIntentionalBlinks + 1) }
                        }
                        blinkLabel = if (enrollmentActive) {
                            "Command blink (${blinkEvent.durationMs} ms) — ignored during the enrollment scan"
                        } else {
                            when (triggerFocusedTileByCommandBlink(now)) {
                                GestureTriggerOutcome.FIRED -> "Command blink (${blinkEvent.durationMs} ms) — tile triggered!"
                                GestureTriggerOutcome.SUPPRESSED_REPEAT -> "Command blink (${blinkEvent.durationMs} ms) — repeat on the same tile suppressed"
                                GestureTriggerOutcome.NO_TILE -> "Command blink (${blinkEvent.durationMs} ms) — no tile focused"
                            }
                        }
                    } else {
                        blinkLabel = "Command blink (${blinkEvent.durationMs} ms) — disabled in ${mode.label}"
                    }
                }
                BlinkType.EXTENDED_REST -> {
                    blinkLabel = "Extended closure (${blinkEvent.durationMs} ms) — trigger cancelled"
                    // A long closure also restarts any pending dwell
                    hoverAccumulatedMs = 0L
                }
            }
        }

        // Head-distance drift warning at >15% deviation from calibration baseline (per audited spec)
        val distRatio = if (activeBaselineEyeDistPx > 20.0 && feature.faceDetected) {
            abs(feature.eyeDistancePx - activeBaselineEyeDistPx) / activeBaselineEyeDistPx
        } else {
            0.0
        }
        val hasDrift = distRatio > 0.15

        // Detect sustained edge/corner pinning (>4.0s) or severe distance drift to trigger Recalibration Nudge
        val isPinnedAtEdge = smoothedX <= 0.04f || smoothedX >= 0.96f || smoothedY <= 0.04f || smoothedY >= 0.96f
        edgePinnedDurationMs = if (feature.faceDetected && (isPinnedAtEdge || hasDrift)) {
            (edgePinnedDurationMs + dtMs).coerceAtMost(20000L)
        } else {
            (edgePinnedDurationMs - dtMs / 2).coerceAtLeast(0L)
        }

        // During the scan the hover counts as not looked at: dwell pauses, then resets, and cannot fire
        val dwellProgress = updateTileHoverDwell(now, dtMs, gazeValid && !enrollmentActive, rawGapMs)
        // Evaluated after dwell so a dwell-fired 'Recenter Gaze' reset is respected in the same frame
        val showNudge = edgePinnedDurationMs >= 4000L
        val updatedTrail = if (prev.overlayVisualMode == com.example.data.model.GazeOverlayVisualMode.RETICLE_ONLY) {
            if (prev.liveTrailPoints.isEmpty()) prev.liveTrailPoints else emptyList()
        } else {
            advanceLiveGazeTrail(
                prevTrail = prev.liveTrailPoints,
                normX = smoothedX,
                normY = smoothedY,
                faceDetected = feature.faceDetected,
                nowMs = now,
                dtMs = dtMs
            )
        }

        _liveHudState.update {
            it.copy(
                normX = smoothedX,
                normY = smoothedY,
                rawFeature = feature,
                liveTrailPoints = updatedTrail,
                blinkHoldProgress = if (blinkEnabled) blinkProgress else 0f,
                browHoldProgress = browProgress,
                lastBlinkLabel = blinkLabel,
                naturalBlinkCount = natBlinks,
                intentionalBlinkCount = cmdBlinks,
                browRaiseTriggerCount = browCount,
                hasDistanceDriftWarning = hasDrift,
                showRecalibrateNudge = showNudge,
                isPerformanceModeActive = perfMode,
                resolutionTier = nextTier,
                analyzerFps = liveFps,
                focusedTileDwellProgress = dwellProgress
            )
        }

        // The enrollment scan sees every frame (a no-face frame pauses its collection); a no-op while idle
        advanceEnrollmentIfActive(feature, now, simulatedFrame)

        if (feature.faceDetected) {
            // A ticker repeat of a stale simulator vector must not auto-fill the next calibration target
            if (!syntheticRepeat || lastSimulatorInputMs >= targetStartTimeMs) {
                advanceCalibrationIfActive(feature, now, simulatedFrame)
            }
            recordAttentionSampleIfActive(smoothedX, smoothedY, now, simulatedFrame, eyesOpen = !isEyelidClosing)
        }
    }

    /**
     * Zeroes the HUD frame rate (and forgets the frame cadence) once frames stop arriving, so a stale
     * FPS is never shown. Runs only while frames are flowing; the next frame restarts it.
     */
    private fun ensureFrameWatchdog() {
        if (frameWatchdogJob?.isActive == true) return
        frameWatchdogJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                if (System.currentTimeMillis() - lastFrameWallClockMs > FRAME_STALE_MS) {
                    lastFrameTimestampMs = 0L
                    smoothedFrameIntervalMs = 66.0
                    if (_liveHudState.value.analyzerFps != 0) {
                        _liveHudState.update { it.copy(analyzerFps = 0) }
                    }
                    break
                }
            }
        }
    }

    private fun updateRecenterCapture(x: Float, y: Float, gazeValid: Boolean, nowMs: Long) {
        if (nowMs > recenterDeadlineMs) {
            recenterDeadlineMs = 0L
            recenterSamples.clear()
            _statusBannerMessage.value = "Recenter cancelled — face not stable. Look at the centre of the screen and try again."
            return
        }
        if (nowMs < recenterCaptureFromMs || !gazeValid) return
        recenterSamples.add(Pair(x, y))
        if (recenterSamples.size < RECENTER_SAMPLE_COUNT) return

        val meanX = recenterSamples.sumOf { it.first.toDouble() } / recenterSamples.size
        val meanY = recenterSamples.sumOf { it.second.toDouble() } / recenterSamples.size
        recenterDeadlineMs = 0L
        recenterSamples.clear()
        if (activeMatrix.size != 12 || calibratedMatrix.size != 12) return
        val updated = activeMatrix.toMutableList()
        updated[0] = (updated[0] + (0.5 - meanX))
            .coerceIn(calibratedMatrix[0] - RECENTER_MAX_OFFSET, calibratedMatrix[0] + RECENTER_MAX_OFFSET)
        updated[6] = (updated[6] + (0.5 - meanY))
            .coerceIn(calibratedMatrix[6] - RECENTER_MAX_OFFSET, calibratedMatrix[6] + RECENTER_MAX_OFFSET)
        val appliedX = updated[0] - activeMatrix[0]
        val appliedY = updated[6] - activeMatrix[6]
        activeMatrix = updated
        // Later background drift updates stay near the recentered mapping
        driftAnchorMatrix = updated
        resetScreenFilters()
        edgePinnedDurationMs = 0L
        _statusBannerMessage.value = String.format(
            Locale.US,
            "Gaze recentered: offset %+.1f%% x, %+.1f%% y applied.",
            appliedX * 100.0,
            appliedY * 100.0
        )
    }

    /**
     * Sets the tile under the reticle (null when none, e.g. when the Control Deck leaves composition).
     * [tileCenterNorm] is the tile centre in the same normalized root frame as the gaze output; it is
     * the reference for background drift correction.
     */
    fun updateFocusedTile(tileId: String?, tileCenterNorm: Pair<Float, Float>? = null) {
        if (tileId != null && tileCenterNorm != null) {
            tileCentersNorm[tileId] = tileCenterNorm
        }
        if (tileId != currentHoveredTileId) {
            currentHoveredTileId = tileId
            hoverAccumulatedMs = 0L
            hoverInvalidRunMs = 0L
            dwellTriggeredForCurrentHover = false
            _liveHudState.update {
                it.copy(focusedTileId = tileId, focusedTileDwellProgress = 0f)
            }
        }
    }

    /** Replaces the normalized (root-overlay frame) centres of the tiles currently laid out. */
    fun updateTileCentersNorm(centers: Map<String, Pair<Float, Float>>) {
        tileCentersNorm.clear()
        tileCentersNorm.putAll(centers)
    }

    /**
     * Dwell time accumulates only over frames with valid gaze (face detected, eyes open). Invalid frames
     * pause it; an invalid run longer than an extended closure, or a gap in the frame stream (app in the
     * background, camera rebind), resets it, so a hover never completes without the user looking.
     */
    private fun updateTileHoverDwell(nowMs: Long, dtMs: Long, gazeValid: Boolean, frameGapMs: Long): Float {
        val tileId = currentHoveredTileId ?: return 0f
        val dwellTargetMs = _liveHudState.value.dwellTriggerMs.coerceAtLeast(400L)
        if (frameGapMs < 0L || frameGapMs > DWELL_FRAME_GAP_RESET_MS) {
            hoverAccumulatedMs = 0L
            hoverInvalidRunMs = 0L
        } else if (gazeValid) {
            hoverInvalidRunMs = 0L
            hoverAccumulatedMs = (hoverAccumulatedMs + dtMs).coerceAtMost(dwellTargetMs)
        } else {
            hoverInvalidRunMs += dtMs
            if (hoverInvalidRunMs > DWELL_INVALID_RESET_MS) {
                hoverAccumulatedMs = 0L
            }
        }
        val progress = (hoverAccumulatedMs.toFloat() / dwellTargetMs.toFloat()).coerceIn(0f, 1f)
        if (progress >= 1f && gazeValid && !dwellTriggeredForCurrentHover) {
            dwellTriggeredForCurrentHover = true
            fireGazeTrigger(tileId, "Dwell (${dwellTargetMs}ms)", nowMs)
        }
        return progress
    }

    private enum class GestureTriggerOutcome { FIRED, SUPPRESSED_REPEAT, NO_TILE }

    private fun fireFocusedTileByGesture(method: String, nowMs: Long): GestureTriggerOutcome {
        val tileId = currentHoveredTileId ?: return GestureTriggerOutcome.NO_TILE
        return if (fireGazeTrigger(tileId, method, nowMs)) {
            GestureTriggerOutcome.FIRED
        } else {
            GestureTriggerOutcome.SUPPRESSED_REPEAT
        }
    }

    private fun triggerFocusedTileByCommandBlink(nowMs: Long): GestureTriggerOutcome =
        fireFocusedTileByGesture("Intentional Blink", nowMs)

    /**
     * Gaze-driven activation (dwell, brow raise, command blink) with a short same-tile refractory period,
     * so one hover cannot speak the same tile twice through two modalities. Manual taps are not throttled.
     */
    private fun fireGazeTrigger(tileId: String, method: String, nowMs: Long): Boolean {
        val sinceLast = nowMs - lastGazeFireMs
        if (tileId == lastGazeFireTileId && sinceLast in 0L until GAZE_REFIRE_REFRACTORY_MS) {
            return false
        }
        lastGazeFireTileId = tileId
        lastGazeFireMs = nowMs
        fireTileAction(tileId, method)
        return true
    }

    fun fireTileAction(tileId: String, triggerMethod: String = "Tap") {
        val tile = _aacTilesState.value.firstOrNull { it.id == tileId } ?: return
        // Any activation consumes the current hover, so dwell cannot fire the same tile again
        if (tileId == currentHoveredTileId) dwellTriggeredForCurrentHover = true
        var spokenTile = tile
        when (tile.id) {
            "tile_recenter" -> {
                // Real recenter: capture where the user looks while fixating the screen centre and shift
                // the mapping intercepts so that gaze lands at the midpoint.
                startRecenterCapture()
                _liveHudState.update {
                    it.copy(
                        showRecalibrateNudge = false,
                        lastTriggeredSpeech = "[Recenter via $triggerMethod] ${tile.speechPhrase}"
                    )
                }
            }
            "tile_read_aloud" -> {
                if (tile.speechPhrase == defaultReadAloudPhrase) {
                    spokenTile = tile.copy(speechPhrase = buildReadingSummaryPhrase())
                }
                _liveHudState.update {
                    it.copy(lastTriggeredSpeech = "[${tile.title} • $triggerMethod] \"${spokenTile.speechPhrase}\"")
                }
            }
            else -> {
                _liveHudState.update {
                    it.copy(lastTriggeredSpeech = "[${tile.title} • $triggerMethod] \"${tile.speechPhrase}\"")
                }
            }
        }
        recordTileRecalibrationHint(tileId, triggerMethod)
        onTileTriggeredCallback?.invoke(spokenTile)
    }

    private fun startRecenterCapture() {
        val base = if (lastFrameTimestampMs > 0L) lastFrameTimestampMs else System.currentTimeMillis()
        recenterCaptureFromMs = base + RECENTER_SETTLE_MS
        recenterDeadlineMs = recenterCaptureFromMs + RECENTER_WINDOW_MS
        recenterSamples.clear()
        edgePinnedDurationMs = 0L
    }

    private fun buildReadingSummaryPhrase(): String {
        val summaries = computeSectionSummariesForDisplay()
        if (summaries.sumOf { it.dwellMs } <= 0L) return "No reading session recorded yet."
        return "Reading summary: " + summaries.joinToString("; ") { "${it.heading.take(48)}, ${it.percentage} percent" }
    }

    /**
     * Quiet background drift correction: after a gaze-triggered activation, nudge the intercepts (w0, w6)
     * a small step (eta) toward the activated tile's known centre, i.e. reduce the observed residual.
     * Taps carry no gaze information and are ignored, and the cumulative change stays within a small
     * band around the calibrated (or recentered) mapping.
     */
    private fun recordTileRecalibrationHint(tileId: String, triggerMethod: String) {
        if (triggerMethod == "Tap" || tileId == "tile_recenter") return
        val target = tileCentersNorm[tileId] ?: return
        if (activeMatrix.size != 12 || driftAnchorMatrix.size != 12) return
        val errX = (target.first - currentGazeX).toDouble()
        val errY = (target.second - currentGazeY).toDouble()
        val updated = activeMatrix.toMutableList()
        updated[0] = (updated[0] + DRIFT_ETA * errX)
            .coerceIn(driftAnchorMatrix[0] - DRIFT_MAX_OFFSET, driftAnchorMatrix[0] + DRIFT_MAX_OFFSET)
        updated[6] = (updated[6] + DRIFT_ETA * errY)
            .coerceIn(driftAnchorMatrix[6] - DRIFT_MAX_OFFSET, driftAnchorMatrix[6] + DRIFT_MAX_OFFSET)
        activeMatrix = updated
        _liveHudState.update { it.copy(backgroundRecalibrationCount = it.backgroundRecalibrationCount + 1) }
    }

    /** Current projection matrix (for regression tests). */
    internal fun currentMatrixForTest(): List<Double> = activeMatrix

    fun setPostureProfile(posture: com.example.data.model.GazePostureProfile) {
        _liveHudState.update { it.copy(activePosture = posture) }
        persistInteractionSettings()
        _statusBannerMessage.value = String.format(
            Locale.US,
            "Posture Profile: %s (Y-bias %+.3f applied relative to the calibration posture).",
            posture.label,
            posture.yBias - calibrationPosture.yBias
        )
    }

    fun setGazeFilterMode(mode: com.example.data.model.GazeFilterMode) {
        // The newly selected filter starts from the next sample, never from state left over from its last use
        if (mode != _liveHudState.value.filterMode) resetScreenFilters()
        _liveHudState.update { it.copy(filterMode = mode) }
        persistInteractionSettings()
        _statusBannerMessage.value = "Gaze Stabilization: ${mode.label}"
    }

    private fun resetScreenFilters() {
        screenGazeFilter.reset()
        screenKalmanFilter.reset()
    }

    fun runBenchmarkSuite() {
        if (_liveHudState.value.isBenchmarkRunning) return
        // Capture inputs on Main, then run the CPU-bound replay off the UI thread
        val matrix = activeMatrix
        val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Google Pixel 10 Pro XL"
        val widthMm = displayWidthMm
        val heightMm = displayHeightMm
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL?.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
        } else {
            null
        }
        _liveHudState.update { it.copy(isBenchmarkRunning = true) }
        viewModelScope.launch {
            try {
                val scorecard = withContext(Dispatchers.Default) {
                    com.example.domain.BenchmarkRunner.runCompleteBenchmark(
                        matrixCoeffs = matrix,
                        deviceModel = model,
                        screenWidthMm = widthMm,
                        screenHeightMm = heightMm,
                        socLabel = soc
                    )
                }
                _liveHudState.update { it.copy(lastBenchmarkReport = scorecard) }
                _statusBannerMessage.value = buildBenchmarkBanner(scorecard)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Benchmark run failed", e)
                _statusBannerMessage.value = "Benchmark failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _liveHudState.update { it.copy(isBenchmarkRunning = false) }
            }
        }
    }

    /** Banner derived from the measured scorecard; never a fixed pass claim. */
    private fun buildBenchmarkBanner(scorecard: BenchmarkScorecard): String {
        val results = scorecard.scenarioResults
        val gated = results.filter { it.scenario.category == "MIDAS_TOUCH" || it.scenario.category == "COMMAND" }
        val falseTriggers = results.sumOf { it.falseActivations }
        val verdict = if (scorecard.allScenariosPassed) "PASS" else "FAILURES DETECTED"
        return String.format(
            Locale.US,
            "Synthetic replay benchmark %s: %d/%d gated scenarios passed, %d replayed without pass criteria, " +
                "%d false triggers, %.3f ms median post-feature latency (JVM, excludes camera and face detection).",
            verdict,
            gated.count { it.passed },
            gated.size,
            results.size - gated.size,
            falseTriggers,
            scorecard.latencyMedianMs
        )
    }

    fun setDwellTriggerDuration(durationMs: Long) {
        val clamped = durationMs.coerceIn(400L, 2500L)
        val matchedPreset = com.example.data.model.DwellSpeedPreset.entries.minByOrNull {
            kotlin.math.abs(it.durationMs - clamped)
        } ?: _liveHudState.value.activeDwellPreset
        _liveHudState.update {
            it.copy(dwellTriggerMs = clamped, activeDwellPreset = matchedPreset)
        }
        persistInteractionSettings()
    }

    fun setDwellSpeedPreset(preset: com.example.data.model.DwellSpeedPreset) {
        _liveHudState.update {
            it.copy(
                dwellTriggerMs = preset.durationMs,
                dwellSensitivityRadiusMultiplier = preset.radiusToleranceMultiplier,
                activeDwellPreset = preset
            )
        }
        persistInteractionSettings()
        _statusBannerMessage.value = "Dwell Speed Preset: ${preset.title} (${preset.durationMs}ms • ${(preset.radiusToleranceMultiplier * 100).toInt()}% tolerance)"
    }

    fun setDwellSensitivityMultiplier(multiplier: Float) {
        val clamped = multiplier.coerceIn(0.6f, 2.0f)
        _liveHudState.update {
            it.copy(dwellSensitivityRadiusMultiplier = clamped)
        }
        persistInteractionSettings()
    }

    fun setBlinkTimingThreshold(minCommandMs: Long, closeThresh: Float) {
        val clampedMs = minCommandMs.coerceIn(280L, 700L)
        val clampedClose = closeThresh.coerceIn(0.15f, 0.40f)
        blinkDetector.intentionalMinMs = clampedMs
        blinkDetector.closeThreshold = clampedClose
    }

    // --- Face & Eye Enrollment Scan (~15 s, personalizes blink and brow detection) ---

    /**
     * Starts the enrollment scan on the live front camera. Refused (with a banner, nothing else changes)
     * while the simulator proxy or an emulator supplies the frames, since synthetic frames are not
     * measurements, and while the calibration wizard is collecting targets (both own the fixation overlay).
     */
    fun startEnrollmentScan() {
        if (_liveHudState.value.isSimulatorProxyEnabled || onEmulator) {
            _statusBannerMessage.value = if (onEmulator) {
                "Enrollment needs the live front camera — it is not available on an emulator (simulated frames are not measurements)."
            } else {
                "Enrollment needs the live front camera — turn the simulator proxy off and try again (simulated frames are not measurements)."
            }
            return
        }
        val step = _calibrationState.value.step
        if (step == CalibrationFlowStep.FIT_5_POINTS || step == CalibrationFlowStep.VALIDATE_4_POINTS) {
            _statusBannerMessage.value = "Finish or cancel the running calibration before starting the enrollment scan."
            return
        }
        // Same clock as the frames that will drive the scan (camera frames carry wall-clock timestamps)
        val now = if (lastFrameTimestampMs > 0L) lastFrameTimestampMs else System.currentTimeMillis()
        _enrollmentState.value = enrollmentEngine.start(now)
        _statusBannerMessage.value =
            "Enrollment scan started — face the front camera and follow the on-screen guidance (about 15 seconds)."
    }

    /** Cancels a running scan (FAILED / CANCELLED); the detectors keep their current settings. */
    fun cancelEnrollmentScan() {
        abortEnrollmentScan("Enrollment scan cancelled — blink and brow detection are unchanged.")
    }

    /** In the brow step only: completes the scan without a raised-brow measurement (BROW_SKIPPED). */
    fun skipEnrollmentBrowStep() {
        if (enrollmentEngine.state.phase != EnrollmentPhase.BROW_RAISE) return
        val next = enrollmentEngine.skipBrowStep()
        _enrollmentState.value = next
        handleEnrollmentTransition(next)
    }

    /**
     * Forgets this account's enrollment: the baseline leaves the vault, the brow detector goes back to the
     * app defaults, and the blink detector to the active profile's stored settings when a profile is
     * applied, else to the app defaults. A running scan is left alone (it saves a fresh baseline on completion).
     */
    fun clearEnrollmentBaseline() {
        localStore?.clearEnrollmentBaseline(currentUserId)
        _enrollmentBaseline.value = null
        if (!isEnrollmentScanActive()) {
            // The last scan's summary or failure must not stay on screen as if it still applied
            enrollmentEngine.reset()
            _enrollmentState.value = EnrollmentScanState()
        }
        val profile = appliedProfile
        if (profile != null) {
            applyProfileBlinkSettings(profile)
        } else {
            applyBlinkSettings(DEFAULT_CLOSE_BLINK_THRESHOLD, DEFAULT_OPEN_BLINK_THRESHOLD, DEFAULT_INTENTIONAL_BLINK_MIN_MS)
        }
        resetBrowDetectorToDefaults()
        _liveHudState.update { it.copy(enrolledAtMs = null) }
        persistInteractionSettings()
        _statusBannerMessage.value = if (profile != null) {
            "Enrollment cleared — brow detection back to the app defaults; blink settings of profile \"${profile.name}\" kept."
        } else {
            "Enrollment cleared — blink and brow detection back to the app defaults."
        }
    }

    private fun isEnrollmentScanActive(): Boolean {
        val phase = enrollmentEngine.state.phase
        return phase != EnrollmentPhase.IDLE && phase != EnrollmentPhase.COMPLETE && phase != EnrollmentPhase.FAILED
    }

    /** Cancels an active scan with [banner]; false (and no banner) when no scan is running. */
    private fun abortEnrollmentScan(banner: String): Boolean {
        if (!isEnrollmentScanActive()) return false
        _enrollmentState.value = enrollmentEngine.cancel()
        _statusBannerMessage.value = banner
        return true
    }

    /**
     * Feeds one frame (face or no-face) to the running scan. A no-op unless the engine is in an active
     * phase, so the idle per-frame pipeline is unchanged. Frames synthesized by the simulator / emulator
     * ticker reach the engine flagged as simulated even when the vector itself was not, so the scan fails
     * (SIMULATED_FRAMES) instead of enrolling made-up measurements.
     */
    private fun advanceEnrollmentIfActive(feature: GazeFeatureVector, nowMs: Long, simulatedFrame: Boolean) {
        if (!isEnrollmentScanActive()) return
        val frame = if (simulatedFrame && !feature.isSimulated) feature.copy(isSimulated = true) else feature
        val next = enrollmentEngine.onFrame(frame, nowMs)
        _enrollmentState.value = next
        handleEnrollmentTransition(next)
    }

    private fun handleEnrollmentTransition(next: EnrollmentScanState) {
        when (next.phase) {
            EnrollmentPhase.COMPLETE -> {
                val result = next.result
                if (result != null) {
                    onEnrollmentCompleted(result)
                } else {
                    _statusBannerMessage.value = "Enrollment ended without a measurable result — nothing was changed."
                }
            }
            EnrollmentPhase.FAILED -> onEnrollmentFailed(next.failure)
            else -> Unit
        }
    }

    /**
     * COMPLETE: the derived numbers are saved to the on-device vault (never synced), published, and applied
     * to the detectors through the same range-checked paths as a stored profile; the brow ratios are
     * mirrored into the HUD and the saved interaction settings like a manual brow calibration.
     */
    private fun onEnrollmentCompleted(result: EnrollmentBaseline) {
        val baseline = result.copy(
            capturedAtMs = if (result.capturedAtMs > 0L) result.capturedAtMs else System.currentTimeMillis(),
            deviceModel = result.deviceModel.ifBlank { Build.MODEL?.takeIf { it.isNotBlank() } ?: "Pixel 10 Pro XL" }
        )
        localStore?.saveEnrollmentBaseline(currentUserId, baseline)
        _enrollmentBaseline.value = baseline
        applyEnrollmentBaselineToDetectors(baseline)
        persistInteractionSettings()
        _statusBannerMessage.value = buildEnrollmentCompleteBanner(baseline)
    }

    /** FAILED: report why; the detectors keep whatever they had. */
    private fun onEnrollmentFailed(failure: EnrollmentFailure?) {
        _statusBannerMessage.value = when (failure) {
            EnrollmentFailure.POSITIONING_TIMEOUT ->
                "Enrollment failed: your face could not be positioned in time — centre it in the frame, in good light, and try again."
            EnrollmentFailure.FACE_LOST ->
                "Enrollment failed: the face was lost too often — keep facing the camera and hold still, then try again."
            EnrollmentFailure.EYES_DID_NOT_CLOSE ->
                "Enrollment failed: eye closure was not detected — close both eyes fully when asked and try again."
            EnrollmentFailure.EYES_DID_NOT_REOPEN ->
                "Enrollment failed: the eyes did not reopen in time — try again."
            EnrollmentFailure.SIMULATED_FRAMES ->
                "Enrollment refused: simulated frames are not measurements — it needs the live front camera."
            EnrollmentFailure.CANCELLED ->
                "Enrollment scan cancelled — blink and brow detection are unchanged."
            null -> "Enrollment failed — blink and brow detection are unchanged."
        }
    }

    /**
     * Applies [baseline] to the detectors: the blink thresholds and command minimum through the same
     * range-checked path as a stored profile ([applyBlinkSettings]); the brow ratios only when the measured
     * neutral ratio is one a face can produce (same window as [restoreInteractionSettings]), with the
     * measured raised ratio when it is a real raise above it, else max(current raised, neutral + separation).
     * Mirrors the brow ratios and the capture time into the HUD.
     */
    private fun applyEnrollmentBaselineToDetectors(baseline: EnrollmentBaseline) {
        applyBlinkSettings(baseline.closeBlinkThreshold, baseline.openBlinkThreshold, baseline.intentionalBlinkMinMs)
        val neutral = baseline.neutralBrowRatio
        val browValid = neutral.isFinite() && neutral in 0.20f..0.52f
        if (browValid) {
            val measuredRaised = baseline.raisedBrowRatio?.takeIf {
                it.isFinite() && it in 0.24f..0.62f && it >= neutral + MIN_BROW_RAISE_SEPARATION
            }
            browDetector.neutralBrowRatio = neutral
            browDetector.raisedBrowRatio = measuredRaised
                ?: maxOf(browDetector.raisedBrowRatio, neutral + MIN_BROW_RAISE_SEPARATION)
        }
        _liveHudState.update {
            it.copy(
                neutralBrowRatio = if (browValid) browDetector.neutralBrowRatio else it.neutralBrowRatio,
                raisedBrowRatio = if (browValid) browDetector.raisedBrowRatio else it.raisedBrowRatio,
                enrolledAtMs = baseline.capturedAtMs
            )
        }
    }

    /** Brow detector back to the app defaults (0.34 / 0.42), mirrored into the HUD. */
    private fun resetBrowDetectorToDefaults() {
        val defaults = LiveGazeHudState()
        browDetector.neutralBrowRatio = defaults.neutralBrowRatio
        browDetector.raisedBrowRatio = defaults.raisedBrowRatio
        browDetector.cancelPendingRaise()
        _liveHudState.update {
            it.copy(neutralBrowRatio = defaults.neutralBrowRatio, raisedBrowRatio = defaults.raisedBrowRatio)
        }
    }

    /** Drops the in-memory enrollment after the vault was wiped: brow defaults, no enrolled-at time. */
    private fun forgetEnrollmentBaselineInMemory() {
        _enrollmentBaseline.value = null
        if (!isEnrollmentScanActive()) {
            enrollmentEngine.reset()
            _enrollmentState.value = EnrollmentScanState()
        }
        resetBrowDetectorToDefaults()
        _liveHudState.update { it.copy(enrolledAtMs = null) }
    }

    /** Banner with the measured levels and the detector settings now in force (Locale.US numbers). */
    private fun buildEnrollmentCompleteBanner(baseline: EnrollmentBaseline): String {
        val raisedLabel = if (baseline.raisedBrowRatio != null) {
            String.format(Locale.US, "%.3f", browDetector.raisedBrowRatio)
        } else {
            "not measured (brow step skipped)"
        }
        val warningsSuffix = if (baseline.warnings.isEmpty()) {
            ""
        } else {
            " Warnings: " + baseline.warnings.joinToString(", ") { enrollmentWarningLabel(it) } + "."
        }
        return String.format(
            Locale.US,
            "Enrollment complete: eyes open %.2f / closed %.2f → blink close < %.2f, re-open > %.2f, command blink ≥ %d ms; " +
                "brow neutral %.3f → raised %s; %d natural blink(s), median %d ms, over %d frames.%s",
            baseline.openEyeLevel,
            baseline.closedEyeLevel,
            blinkDetector.closeThreshold,
            blinkDetector.openThreshold,
            blinkDetector.intentionalMinMs,
            browDetector.neutralBrowRatio,
            raisedLabel,
            baseline.naturalBlinkCount,
            baseline.naturalBlinkMedianMs,
            baseline.frameCount,
            warningsSuffix
        )
    }

    /** Plain words for a stored EnrollmentWarning name (unknown names are shown as saved). */
    private fun enrollmentWarningLabel(name: String): String =
        when (EnrollmentWarning.entries.firstOrNull { it.name == name }) {
            EnrollmentWarning.LOW_LIGHT -> "low light"
            EnrollmentWarning.GLARE -> "glare"
            EnrollmentWarning.SINGLE_EYE -> "single-eye tracking"
            EnrollmentWarning.FEW_NATURAL_BLINKS -> "fewer than 2 natural blinks seen (default command minimum)"
            EnrollmentWarning.EYE_RANGE_SMALL_DEFAULTS_USED -> "small open/closed range (default blink thresholds used)"
            EnrollmentWarning.BROW_SKIPPED -> "brow step skipped"
            null -> name.lowercase(Locale.US).replace('_', ' ')
        }

    // --- 5-Point Calibration + Optional 4-Point Held-Out Validation State Machine ---

    fun setIncludeHeldOutValidation(includeValidation: Boolean) {
        _calibrationState.update { it.copy(includeHeldOutValidation = includeValidation) }
    }

    fun startCalibrationAndValidationWizard(
        includeValidation: Boolean = _calibrationState.value.includeHeldOutValidation
    ) {
        // The scan and the wizard both own the full-screen fixation overlay; calibration takes over
        abortEnrollmentScan("Enrollment scan cancelled: calibration started. Blink and brow detection are unchanged.")
        // Retry over an unsaved candidate fit: put the pre-wizard engine back first
        val snapshot = preWizardSnapshot
        if (snapshot == null) {
            preWizardSnapshot = captureEngineSnapshot()
        } else {
            restoreEngineSnapshot(snapshot)
        }
        calibrationUsedSimulatedFrames = false
        pendingCalibrationPosture = _liveHudState.value.activePosture
        fitSamples.clear()
        validationSamples.clear()
        samplesForCurrentTarget = 0
        targetStartTimeMs = System.currentTimeMillis()
        val modelName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Pixel 10 Pro XL"
        _calibrationState.value = CalibrationWizardState(
            step = CalibrationFlowStep.FIT_5_POINTS,
            includeHeldOutValidation = includeValidation,
            currentTargetIndex = 0,
            currentTargetProgress = 0f,
            samplesCollectedForCurrentTarget = 0,
            requiredSamplesPerTarget = SAMPLES_PER_TARGET,
            pointSampleCounts = emptyMap(),
            totalCollectedSamples = 0,
            isSettlingDelay = true,
            draftProfileName = "$modelName Profile"
        )
    }

    fun cancelCalibrationWizard() {
        // Only a running collection can be cancelled. A late cancel (e.g. the screen disposing on the frame
        // the wizard reached COMPLETED_SUMMARY) must not erase the unsaved summary.
        val step = _calibrationState.value.step
        if (step != CalibrationFlowStep.FIT_5_POINTS && step != CalibrationFlowStep.VALIDATE_4_POINTS) return
        _calibrationState.update {
            CalibrationWizardState(
                step = CalibrationFlowStep.IDLE,
                includeHeldOutValidation = it.includeHeldOutValidation
            )
        }
        endWizardEngineOverride()
    }

    private fun captureEngineSnapshot() = EngineSnapshot(
        activeMatrix = activeMatrix,
        calibratedMatrix = calibratedMatrix,
        driftAnchorMatrix = driftAnchorMatrix,
        baselineEyeDistPx = activeBaselineEyeDistPx,
        calibrationPosture = calibrationPosture
    )

    private fun restoreEngineSnapshot(snapshot: EngineSnapshot) {
        activeMatrix = snapshot.activeMatrix
        calibratedMatrix = snapshot.calibratedMatrix
        driftAnchorMatrix = snapshot.driftAnchorMatrix
        activeBaselineEyeDistPx = snapshot.baselineEyeDistPx
        calibrationPosture = snapshot.calibrationPosture
        resetScreenFilters()
    }

    /**
     * Ends the wizard's live preview of an unsaved fit: the engine goes back to the pre-wizard profile
     * (Save applies the new profile explicitly), then any profile change that arrived meanwhile is applied.
     */
    private fun endWizardEngineOverride() {
        val snapshot = preWizardSnapshot ?: return
        restoreEngineSnapshot(snapshot)
        preWizardSnapshot = null
        reconcileActiveProfile(latestMergedProfiles)
    }

    fun updateDraftProfileName(name: String) {
        _calibrationState.update { it.copy(draftProfileName = name) }
    }

    /**
     * Allows user or accessibility/test flow to tap a specific calibration coordinate or "Capture Point"
     * button to collect gaze samples immediately for the active screen coordinate.
     */
    fun captureManualSamplesForCurrentTarget(sampleBatchCount: Int = SAMPLES_PER_TARGET) {
        val state = _calibrationState.value
        if (state.step != CalibrationFlowStep.FIT_5_POINTS && state.step != CalibrationFlowStep.VALIDATE_4_POINTS) {
            return
        }
        val allTargets = GazeCalibrationMath.CALIBRATION_AND_VALIDATION_TARGETS
        if (allTargets.getOrNull(state.currentTargetIndex) == null) return

        val currentFeature = _liveHudState.value.rawFeature
        if (!currentFeature.faceDetected) {
            // Never synthesize target-matched samples: without a face nothing was measured
            _statusBannerMessage.value = "No face detected — face the front camera, look at the target and try again."
            return
        }

        // Bypass the settling delay when the user explicitly triggers point capture
        val now = System.currentTimeMillis()
        targetStartTimeMs = now - SETTLING_DELAY_MS - 50L

        val simulatorActive = _liveHudState.value.isSimulatorProxyEnabled || onEmulator
        if (!simulatorActive) {
            // Live camera: the next real frames fill this target (one frame is never copied N times)
            _statusBannerMessage.value = "Capturing live gaze samples — keep looking at the target."
            return
        }

        // Simulator only: replicate the simulator's current (explicitly positioned) vector
        val effectiveFeature = currentFeature.copy(isSimulated = true, timestampMs = now)
        val countToCollect = sampleBatchCount.coerceIn(1, SAMPLES_PER_TARGET)
        repeat(countToCollect) { idx ->
            advanceCalibrationIfActive(effectiveFeature.copy(timestampMs = now + idx * 16L), now + idx * 16L, simulatedFrame = true)
        }
    }

    private fun advanceCalibrationIfActive(feature: GazeFeatureVector, nowMs: Long, simulatedFrame: Boolean) {
        val state = _calibrationState.value
        if (state.step != CalibrationFlowStep.FIT_5_POINTS && state.step != CalibrationFlowStep.VALIDATE_4_POINTS) {
            return
        }

        val allTargets = GazeCalibrationMath.CALIBRATION_AND_VALIDATION_TARGETS
        val target = allTargets.getOrNull(state.currentTargetIndex) ?: return

        val elapsedSinceTargetStart = nowMs - targetStartTimeMs
        if (elapsedSinceTargetStart < SETTLING_DELAY_MS) {
            _calibrationState.update {
                it.copy(
                    isSettlingDelay = true,
                    currentTargetProgress = 0f,
                    samplesCollectedForCurrentTarget = 0
                )
            }
            return
        }

        val samplePair = Pair(feature, Pair(target.normX, target.normY))
        if (!target.isValidationPoint) {
            fitSamples.add(samplePair)
        } else {
            validationSamples.add(samplePair)
        }
        if (simulatedFrame) calibrationUsedSimulatedFrames = true
        samplesForCurrentTarget++

        val updatedCounts = state.pointSampleCounts.toMutableMap()
        updatedCounts[target.index] = samplesForCurrentTarget
        val totalCollected = fitSamples.size + validationSamples.size

        val progress = (samplesForCurrentTarget.toFloat() / SAMPLES_PER_TARGET.toFloat()).coerceIn(0f, 1f)
        _calibrationState.update {
            it.copy(
                isSettlingDelay = false,
                currentTargetProgress = progress,
                samplesCollectedForCurrentTarget = samplesForCurrentTarget,
                pointSampleCounts = updatedCounts,
                totalCollectedSamples = totalCollected
            )
        }

        if (samplesForCurrentTarget >= SAMPLES_PER_TARGET) {
            samplesForCurrentTarget = 0
            targetStartTimeMs = nowMs
            val nextIndex = state.currentTargetIndex + 1

            when {
                nextIndex == 5 -> {
                    val avgEyeDist = fitSamples.map { it.first.eyeDistancePx }.average().takeIf { !it.isNaN() } ?: 148.0
                    val solvedMatrix = GazeCalibrationMath.fitRidgeAffineMatrix2x6(
                        samples = fitSamples,
                        baselineEyeDistPx = avgEyeDist
                    )
                    // The posture the user calibrated in is baked into the fitted intercepts
                    pendingCalibrationPosture = _liveHudState.value.activePosture

                    if (!state.includeHeldOutValidation) {
                        // In-sample residual on the 5 fit targets only: kept for display, never labelled
                        // or applied as validated accuracy (HUD metrics are set on Save, as not validated).
                        val fitMetrics = GazeCalibrationMath.evaluateHeldOutValidation(
                            matrixCoeffs = solvedMatrix,
                            validationSamples = fitSamples,
                            baselineEyeDistPx = avgEyeDist,
                            screenWidthMm = displayWidthMm,
                            screenHeightMm = displayHeightMm
                        )
                        _calibrationState.update {
                            it.copy(
                                step = CalibrationFlowStep.COMPLETED_SUMMARY,
                                currentTargetProgress = 1f,
                                samplesCollectedForCurrentTarget = SAMPLES_PER_TARGET,
                                pointSampleCounts = updatedCounts,
                                totalCollectedSamples = totalCollected,
                                isSettlingDelay = false,
                                fittedMatrix = solvedMatrix,
                                capturedBaselineEyeDistPx = avgEyeDist,
                                validationMeanErrorMm = fitMetrics.approxMeanErrorMm,
                                validationMeanErrorPct = fitMetrics.meanErrorPct,
                                validationMaxErrorPct = fitMetrics.maxErrorPct,
                                recommendedGridLabel = QUICK_FIT_GRID_LABEL,
                                metricsAreHeldOut = false
                            )
                        }
                        endWizardEngineOverride()
                    } else {
                        // Live preview of the candidate fit during validation; restored unless saved
                        activeMatrix = solvedMatrix
                        calibratedMatrix = solvedMatrix
                        driftAnchorMatrix = solvedMatrix
                        activeBaselineEyeDistPx = avgEyeDist
                        calibrationPosture = pendingCalibrationPosture
                        resetScreenFilters()
                        _calibrationState.update {
                            it.copy(
                                step = CalibrationFlowStep.VALIDATE_4_POINTS,
                                currentTargetIndex = 5,
                                currentTargetProgress = 0f,
                                samplesCollectedForCurrentTarget = 0,
                                pointSampleCounts = updatedCounts,
                                totalCollectedSamples = totalCollected,
                                isSettlingDelay = true,
                                fittedMatrix = solvedMatrix,
                                capturedBaselineEyeDistPx = avgEyeDist
                            )
                        }
                    }
                }
                nextIndex < allTargets.size -> {
                    _calibrationState.update {
                        it.copy(
                            currentTargetIndex = nextIndex,
                            currentTargetProgress = 0f,
                            samplesCollectedForCurrentTarget = 0,
                            pointSampleCounts = updatedCounts,
                            totalCollectedSamples = totalCollected,
                            isSettlingDelay = true
                        )
                    }
                }
                else -> {
                    val fitted = _calibrationState.value.fittedMatrix
                    val baseEyeDist = _calibrationState.value.capturedBaselineEyeDistPx
                    // Held-out targets only: never fall back to the in-sample fit residual
                    val valResult = GazeCalibrationMath.evaluateHeldOutValidation(
                        matrixCoeffs = fitted,
                        validationSamples = validationSamples,
                        baselineEyeDistPx = baseEyeDist,
                        screenWidthMm = displayWidthMm,
                        screenHeightMm = displayHeightMm
                    )
                    _calibrationState.update {
                        it.copy(
                            step = CalibrationFlowStep.COMPLETED_SUMMARY,
                            pointSampleCounts = updatedCounts,
                            totalCollectedSamples = totalCollected,
                            validationMeanErrorMm = valResult.approxMeanErrorMm,
                            validationMeanErrorPct = valResult.meanErrorPct,
                            validationMaxErrorPct = valResult.maxErrorPct,
                            recommendedGridLabel = valResult.recommendedGridDesc,
                            metricsAreHeldOut = validationSamples.isNotEmpty()
                        )
                    }
                    // HUD accuracy changes only when the user saves (applyProfileToEngine)
                    endWizardEngineOverride()
                }
            }
        }
    }

    fun saveCompletedCalibrationProfile() {
        val cal = _calibrationState.value
        // Only a finished wizard has a fit to save (a second Save tap would persist the defaults)
        if (cal.step != CalibrationFlowStep.COMPLETED_SUMMARY) return
        if (!cal.validationMeanErrorMm.isFinite() || !cal.validationMeanErrorPct.isFinite() ||
            !cal.validationMaxErrorPct.isFinite()
        ) {
            // Nothing was measured: never persist NaN (the local vault cannot store it) or a made-up value
            _statusBannerMessage.value = "Calibration error was not measured — run the calibration again before saving."
            return
        }
        val hud = _liveHudState.value
        val deviceName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Pixel 10 Pro XL"
        val simulated = calibrationUsedSimulatedFrames
        // Simulated fits stay on-device and are labelled as such, never synced as real telemetry
        val syncToCloud = hud.cloudSyncEnabled && !simulated
        // One id shared by the local and cloud copies, so the merged list de-duplicates them and a
        // delete reaches both. Sync-off saves keep the local_ prefix (local-only records).
        val profileId = if (syncToCloud) {
            "cal_" + UUID.randomUUID().toString().replace("-", "")
        } else {
            "local_cal_${System.currentTimeMillis()}"
        }
        val baseName = cal.draftProfileName.ifBlank { "$deviceName Profile" }
        val profile = CalibrationProfile(
            id = profileId,
            userId = currentUserId,
            name = (if (simulated) "$SIMULATED_PREFIX$baseName" else baseName).take(80),
            deviceModel = if (simulated) "Simulator" else deviceName,
            matrixCoeffs = cal.fittedMatrix,
            baselineEyeDistancePx = cal.capturedBaselineEyeDistPx,
            meanErrorMm = cal.validationMeanErrorMm,
            meanErrorPct = cal.validationMeanErrorPct,
            maxErrorPct = cal.validationMaxErrorPct,
            closeBlinkThreshold = blinkDetector.closeThreshold.toDouble(),
            openBlinkThreshold = blinkDetector.openThreshold.toDouble(),
            intentionalBlinkMinMs = blinkDetector.intentionalMinMs,
            isActive = true,
            // Persisted, so a quick fit's in-sample residual is never read back as validated accuracy
            heldOutValidated = cal.metricsAreHeldOut,
            calibrationPosture = pendingCalibrationPosture.name
        )
        sessionProfileMeta[profileId] = SessionProfileMeta(
            heldOutValidated = cal.metricsAreHeldOut,
            calibrationPosture = pendingCalibrationPosture
        )
        val localProfiles = _localProfilesFlow.value
        val evictionNote = if (
            localStore != null &&
            localProfiles.size >= LocalTelemetryStore.MAX_LOCAL_PROFILES &&
            localProfiles.none { it.id == profileId }
        ) {
            " The on-device vault keeps the ${LocalTelemetryStore.MAX_LOCAL_PROFILES} most recent profiles, " +
                "so the oldest on-device profile was removed."
        } else {
            ""
        }

        // Commit: the wizard override (if any) ends and the saved profile becomes the explicit choice
        preWizardSnapshot = null
        selectedProfileId = profileId
        applyProfileToEngine(profile)
        // Always persist to on-device LocalTelemetryStore so sync-off users retain calibration across restarts.
        // The cloud upload uses the entry exactly as persisted (same id), so the merged list de-duplicates them.
        val persisted = localStore?.saveLocalProfile(currentUserId, profile) ?: profile
        _localProfilesFlow.value = localStore?.loadLocalProfiles(currentUserId).orEmpty()

        _calibrationState.value = CalibrationWizardState(
            step = CalibrationFlowStep.IDLE,
            includeHeldOutValidation = cal.includeHeldOutValidation
        )

        if (simulated) {
            _statusBannerMessage.value =
                "Simulated calibration saved on this device only (not synced, labelled SIMULATED).$evictionNote"
            return
        }
        if (!hud.cloudSyncEnabled) {
            _statusBannerMessage.value = "Calibration saved to on-device storage (Cloud Sync is paused).$evictionNote"
            return
        }

        viewModelScope.launch {
            // An active profile's create also deactivates every other active cloud profile in the same batch
            val res = repository.createCalibrationProfile(persisted)
            if (res.isSuccess) {
                _statusBannerMessage.value = "Calibration profile synced to Cloud Firestore & local vault.$evictionNote"
            } else {
                _statusBannerMessage.value =
                    "Saved locally; Cloud sync error: ${res.exceptionOrNull()?.localizedMessage}$evictionNote"
            }
        }
    }

    fun activateSavedProfile(profile: CalibrationProfile) {
        selectedProfileId = profile.id.takeIf { it.isNotBlank() }
        deletedProfileIds.remove(profile.id)
        applyProfileToEngine(profile)
        // An explicit activation during the wizard survives a later cancel
        if (preWizardSnapshot != null) preWizardSnapshot = captureEngineSnapshot()
        _localProfilesFlow.value = localStore?.setActiveLocalProfile(currentUserId, profile.id).orEmpty()

        if (_liveHudState.value.cloudSyncEnabled && profile.id.isNotBlank() && !profile.id.startsWith("local_")) {
            viewModelScope.launch {
                // Exclusive: the same batch deactivates every other active cloud profile
                repository.updateCalibrationActiveState(profile.id, true)
            }
        }
        _statusBannerMessage.value = if (isHeldOutValidated(profile)) {
            String.format(
                Locale.US,
                "Activated profile: %s (%.1f%% mean • %.1f%% max)",
                profile.name,
                profile.meanErrorPct,
                profile.maxErrorPct
            )
        } else {
            "Activated profile: ${profile.name} (accuracy not validated)"
        }
    }

    private fun profileKey(profile: CalibrationProfile): String =
        profile.id.ifBlank { profile.name + profile.createdAt }

    /**
     * Whether the profile's error metrics are held-out validation results. Profiles saved in this session are
     * known exactly (their cloud copy may lack the field); otherwise the persisted flag decides, and records
     * saved before it existed read back as not validated.
     */
    private fun isHeldOutValidated(profile: CalibrationProfile): Boolean =
        sessionProfileMeta[profile.id]?.heldOutValidated ?: profile.heldOutValidated

    /**
     * Applies the profile that should be active (the user's explicit choice, else the flagged active one,
     * else the newest) only when it differs from the one already applied. Skipped while the calibration
     * wizard previews an unsaved fit; [endWizardEngineOverride] re-runs it.
     */
    private fun reconcileActiveProfile(merged: List<CalibrationProfile>) {
        latestMergedProfiles = merged
        if (preWizardSnapshot != null) return
        val candidates = if (deletedProfileIds.isEmpty()) merged else merged.filterNot { it.id in deletedProfileIds }
        val selected = selectedProfileId
        val active = (if (selected != null) candidates.firstOrNull { it.id == selected } else null)
            ?: candidates.firstOrNull { it.isActive }
            ?: candidates.firstOrNull()
            ?: return
        if (profileKey(active) != appliedProfileKey) {
            applyProfileToEngine(active)
        }
    }

    private fun applyProfileToEngine(profile: CalibrationProfile) {
        if (profile.matrixCoeffs.size == 12) {
            activeMatrix = profile.matrixCoeffs
            calibratedMatrix = profile.matrixCoeffs
            driftAnchorMatrix = profile.matrixCoeffs
        }
        activeBaselineEyeDistPx = profile.baselineEyeDistancePx
        applyProfileBlinkSettings(profile)
        val meta = sessionProfileMeta[profile.id]
        calibrationPosture = meta?.calibrationPosture ?: profile.calibrationPostureProfile()
        appliedProfileKey = profileKey(profile)
        activeProfileId = profile.id
        appliedProfile = profile
        recenterDeadlineMs = 0L
        recenterSamples.clear()
        // The previous mapping's smoothed state must not blend into the new one
        resetScreenFilters()

        // A 5-point fit without held-out validation has no validated accuracy (null), which keeps the
        // Control Deck on its protective grid; non-finite stored values are never shown as numbers.
        val validated = isHeldOutValidated(profile)
        _liveHudState.update {
            it.copy(
                activeProfileName = profile.name,
                activeProfileId = profile.id.takeIf { id -> id.isNotBlank() },
                activeProfileDeviceModel = profile.deviceModel,
                activeErrorRadiusPct = if (validated) profile.meanErrorPct.finiteOrNull()?.toFloat() else null,
                activeMaxQuadrantErrorPct = if (validated) profile.maxErrorPct.finiteOrNull()?.toFloat() else null,
                activeErrorMm = if (validated) profile.meanErrorMm.finiteOrNull() else null,
                hasActiveCalibration = true,
                backgroundRecalibrationCount = 0
            )
        }
    }

    /**
     * Profiles can come from the cloud or from older clients, so their blink settings go through the
     * range-checked [applyBlinkSettings] (the detector stores Float thresholds, so the stored Doubles are
     * converted first).
     */
    private fun applyProfileBlinkSettings(profile: CalibrationProfile) {
        applyBlinkSettings(
            close = profile.closeBlinkThreshold.toFloat(),
            open = profile.openBlinkThreshold.toFloat(),
            minMs = profile.intentionalBlinkMinMs
        )
    }

    /**
     * Range-checked application of stored blink settings (a calibration profile's or the enrollment
     * baseline's): the thresholds only as a consistent Schmitt pair (close within
     * [MIN_CLOSE_BLINK_THRESHOLD, MAX_CLOSE_BLINK_THRESHOLD], re-open at most [MAX_OPEN_BLINK_THRESHOLD] and
     * at least [MIN_BLINK_HYSTERESIS_GAP] above close), the command minimum only below the command maximum.
     * Otherwise the engine defaults apply.
     */
    private fun applyBlinkSettings(close: Float, open: Float, minMs: Long) {
        val closeD = close.toDouble()
        val openD = open.toDouble()
        val thresholdsValid = closeD.isFinite() && openD.isFinite() &&
            closeD in MIN_CLOSE_BLINK_THRESHOLD..MAX_CLOSE_BLINK_THRESHOLD &&
            openD <= MAX_OPEN_BLINK_THRESHOLD &&
            openD >= closeD + MIN_BLINK_HYSTERESIS_GAP
        if (thresholdsValid) {
            blinkDetector.closeThreshold = close
            blinkDetector.openThreshold = open
        } else {
            blinkDetector.closeThreshold = DEFAULT_CLOSE_BLINK_THRESHOLD
            blinkDetector.openThreshold = DEFAULT_OPEN_BLINK_THRESHOLD
        }
        blinkDetector.intentionalMinMs = if (minMs < blinkDetector.maxCommandMs) {
            minMs.coerceIn(MIN_INTENTIONAL_BLINK_MS, MAX_INTENTIONAL_BLINK_MIN_MS)
        } else {
            DEFAULT_INTENTIONAL_BLINK_MIN_MS
        }
    }

    /** Back to the state of a fresh launch with no profile (used when the active profile is deleted). */
    private fun resetEngineToUncalibratedDefaults() {
        activeProfileId = null
        appliedProfileKey = null
        appliedProfile = null
        selectedProfileId = null
        activeMatrix = DEFAULT_AFFINE_MATRIX_2X6
        calibratedMatrix = DEFAULT_AFFINE_MATRIX_2X6
        driftAnchorMatrix = DEFAULT_AFFINE_MATRIX_2X6
        activeBaselineEyeDistPx = 148.0
        calibrationPosture = GazePostureProfile.UPRIGHT
        blinkDetector.closeThreshold = DEFAULT_CLOSE_BLINK_THRESHOLD
        blinkDetector.openThreshold = DEFAULT_OPEN_BLINK_THRESHOLD
        blinkDetector.intentionalMinMs = DEFAULT_INTENTIONAL_BLINK_MIN_MS
        recenterDeadlineMs = 0L
        recenterSamples.clear()
        resetScreenFilters()
        val defaults = LiveGazeHudState()
        _liveHudState.update {
            it.copy(
                activeProfileName = defaults.activeProfileName,
                activeProfileId = null,
                activeProfileDeviceModel = defaults.activeProfileDeviceModel,
                activeErrorRadiusPct = null,
                activeMaxQuadrantErrorPct = null,
                activeErrorMm = null,
                hasActiveCalibration = false,
                backgroundRecalibrationCount = 0
            )
        }
    }

    fun deleteCalibrationProfile(profileId: String) {
        deletedProfileIds.add(profileId)
        if (selectedProfileId == profileId) selectedProfileId = null
        // Deleting the profile in use must not leave its matrix driving tracking
        if (profileId == activeProfileId) resetEngineToUncalibratedDefaults()
        _localProfilesFlow.value = localStore?.deleteLocalProfile(currentUserId, profileId).orEmpty()
        if (profileId.isNotBlank() && !profileId.startsWith("local_")) {
            // Cloud (or shared-id) record: delete the Firestore copy too and report the real outcome
            viewModelScope.launch {
                val res = repository.deleteCalibrationProfile(profileId)
                if (res.isSuccess) {
                    _statusBannerMessage.value = "Calibration profile deleted from this device and Cloud Firestore."
                } else {
                    deletedProfileIds.remove(profileId)
                    _statusBannerMessage.value = "Deleted on this device; cloud delete failed: ${res.exceptionOrNull()?.localizedMessage ?: "unknown error"}"
                }
            }
        } else {
            _statusBannerMessage.value = "Local calibration profile deleted."
        }
    }

    // --- Section Attention Heatmap & Reading Analytics Lab ---

    fun startAttentionRecording() {
        rawSessionGazeSamples.clear()
        archivedSessionClusters.clear()
        sessionUsedSimulatedFrames = false
        lastAttentionSampleMs = System.currentTimeMillis()
        sessionBaselineFps = _liveHudState.value.analyzerFps.toDouble().coerceAtLeast(10.0)
        _attentionState.value = AttentionSessionLiveState(
            isRecording = true,
            elapsedSeconds = 0,
            sectionDwellMsMap = readingPassageSections.associate { it.sectionId to 0L },
            fpsTimeline = listOf(_liveHudState.value.analyzerFps),
            thermalThrottleDetected = false,
            selectedReplaySession = null
        )
        sessionTimerJob?.cancel()
        sessionTimerJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                // Count only time with a running camera/simulator stream (not background time)
                if (System.currentTimeMillis() - lastFrameWallClockMs > SESSION_TIMER_FRAME_STALE_MS) continue
                val currentFps = _liveHudState.value.analyzerFps
                _attentionState.update { st ->
                    val nextElapsed = st.elapsedSeconds + 1
                    // One exact-size list per tick (plus + takeLast copied the timeline twice, takeLast(10) once more)
                    val nextTimeline = appendFpsSample(st.fpsTimeline, currentFps, FPS_TIMELINE_MAX_SAMPLES)
                    // Rolling 60-second baseline vs recent window: if FPS drops >40% from first-minute baseline,
                    // flag thermal throttling and auto-enable Performance Mode; clear it again (hysteresis)
                    // once the recent rate recovers to 80% of the baseline.
                    if (nextElapsed <= 60 && nextTimeline.isNotEmpty()) {
                        sessionBaselineFps = averageOfLastFpsSamples(nextTimeline, nextTimeline.size).coerceAtLeast(10.0)
                    }
                    val recentAvg = averageOfLastFpsSamples(nextTimeline, FPS_RECENT_WINDOW_SAMPLES)
                    val throttled = if (st.thermalThrottleDetected) {
                        recentAvg < sessionBaselineFps * 0.80
                    } else {
                        nextElapsed > 15 && recentAvg < sessionBaselineFps * 0.60
                    }
                    st.copy(
                        elapsedSeconds = nextElapsed,
                        fpsTimeline = nextTimeline,
                        thermalThrottleDetected = throttled
                    )
                }
            }
        }
    }

    /**
     * [normX]/[normY] are in the root overlay frame and feed the top/middle/bottom zone stats. Fixation samples
     * use the passage-frame point from [updatePassageGazePoint] (the frame the heatmap is drawn in) and are
     * recorded only while it is on the passage and the eyes are open, so frozen closed-eye positions never
     * extend a fixation.
     */
    private fun recordAttentionSampleIfActive(
        normX: Float,
        normY: Float,
        nowMs: Long,
        simulatedFrame: Boolean,
        eyesOpen: Boolean
    ) {
        val state = _attentionState.value
        if (!state.isRecording) return
        if (simulatedFrame) sessionUsedSimulatedFrames = true

        val dt = (nowMs - lastAttentionSampleMs).coerceIn(16L, 250L)
        lastAttentionSampleMs = nowMs
        val passagePoint = layoutPassageGazePoint?.takeIf { readingLayoutAttached && eyesOpen }
        if (passagePoint != null) {
            if (rawSessionGazeSamples.size >= MAX_SESSION_GAZE_SAMPLES) {
                // Fold the older half into archived clusters instead of discarding it, so the saved session
                // covers the whole recording (not just its last few minutes). clusterFixations only reads
                // its input, so the sub-list view is clustered directly (no 1,800-element copy).
                val olderHalf = rawSessionGazeSamples.subList(0, MAX_SESSION_GAZE_SAMPLES / 2)
                archivedSessionClusters.addAll(
                    GazeCalibrationMath.clusterFixations(olderHalf, maxClusters = Int.MAX_VALUE)
                )
                olderHalf.clear()
            }
            rawSessionGazeSamples.add(Triple(passagePoint.x, passagePoint.y, nowMs))
        }

        val topAdd = if (normY < 0.34f) dt else 0L
        val midAdd = if (normY in 0.34f..0.67f) dt else 0L
        val botAdd = if (normY > 0.67f) dt else 0L

        // Section dwell comes only from the layout-measured hit-test of the attached passage. Gaze on
        // no section (header, diagnostics bar, another tab) is not credited to any section.
        val hitSectionId: String? = layoutHitSectionId
            ?.takeIf { readingLayoutAttached && it != OFF_PASSAGE_SECTION_ID }

        val updatedMap = if (hitSectionId != null) {
            state.sectionDwellMsMap.toMutableMap().also { it[hitSectionId] = (it[hitSectionId] ?: 0L) + dt }
        } else {
            state.sectionDwellMsMap
        }

        // Recompute live fixation clusters periodically so the Reading Lab heatmap renders in real time during
        // recording, over a view of the newest window (takeLast copied up to 240 samples every 4th frame)
        val liveClusters = if (
            passagePoint != null && (rawSessionGazeSamples.size % 4 == 0 || state.computedClusters.isEmpty())
        ) {
            val sampleCount = rawSessionGazeSamples.size
            GazeCalibrationMath.clusterFixations(
                rawSessionGazeSamples.subList((sampleCount - LIVE_CLUSTER_WINDOW_SAMPLES).coerceAtLeast(0), sampleCount)
            )
        } else {
            state.computedClusters
        }

        _attentionState.update {
            it.copy(
                activeSectionId = hitSectionId,
                sectionDwellMsMap = updatedMap,
                topZoneMs = it.topZoneMs + topAdd,
                middleZoneMs = it.middleZoneMs + midAdd,
                bottomZoneMs = it.bottomZoneMs + botAdd,
                computedClusters = liveClusters
            )
        }
    }

    fun clearSessionReplay() {
        _attentionState.update {
            if (it.isRecording) {
                it.copy(selectedReplaySession = null, computedClusters = emptyList())
            } else {
                it.copy(selectedReplaySession = null, computedClusters = emptyList(), thermalThrottleDetected = false)
            }
        }
        if (!_attentionState.value.isRecording) sessionTimerJob?.cancel()
    }

    fun stopAndSaveAttentionSession(customTitle: String = "Section Reading Telemetry") {
        sessionTimerJob?.cancel()
        val state = _attentionState.value
        if (!state.isRecording) return

        // All fixations of the whole recording: clusters archived when the raw buffer filled, plus the rest
        val allClusters = archivedSessionClusters +
            GazeCalibrationMath.clusterFixations(rawSessionGazeSamples, maxClusters = Int.MAX_VALUE)
        val clusters = selectSessionDisplayClusters(allClusters)

        // No gaze samples means no zone data (0/0/0), not "100% bottom zone"
        val zonePcts = largestRemainderPercentages(listOf(state.topZoneMs, state.middleZoneMs, state.bottomZoneMs))
        val topPct = zonePcts[0]
        val midPct = zonePcts[1]
        val botPct = zonePcts[2]

        val durationSec = state.elapsedSeconds.coerceAtLeast(1)
        // Effective WPM from gaze: words of the sections actually dwelt on long enough to be read (at most
        // MAX_PLAUSIBLE_WPM), over the measured on-passage dwell. 0 when nothing was read.
        val sectionDwellTotalMs = state.sectionDwellMsMap.values.sum()
        val coveredWords = readingPassageSections
            .filter { sec -> (state.sectionDwellMsMap[sec.sectionId] ?: 0L) >= sec.wordCount * 60_000L / MAX_PLAUSIBLE_WPM }
            .sumOf { it.wordCount }
        val effectiveWpm = if (coveredWords == 0 || sectionDwellTotalMs <= 0L) {
            0
        } else {
            ((coveredWords * 60_000.0) / sectionDwellTotalMs.toDouble()).roundToInt().coerceIn(0, 2000)
        }
        val bpm = (state.sessionNaturalBlinks * 60.0) / durationSec.toDouble()

        val totalSecMs = sectionDwellTotalMs.coerceAtLeast(1L)
        val sectionLoadsEncoded = readingPassageSections.map { sec ->
            val ms = state.sectionDwellMsMap[sec.sectionId] ?: 0L
            val pct = ((ms * 100L) / totalSecMs).toInt()
            "${sec.sectionId}:${sec.heading.take(40)}:$pct"
        }

        // Tagged as passage-frame points; untagged legacy entries were recorded in the overlay frame
        val clusterPointsEncoded = clusters.map { c ->
            String.format(Locale.US, "%.3f,%.3f,%d,%s", c.normX, c.normY, c.dwellMs, PASSAGE_FRAME_CLUSTER_TAG)
        }

        val simulated = sessionUsedSimulatedFrames
        val syncToCloud = _liveHudState.value.cloudSyncEnabled && !simulated
        // One id shared by the local and cloud copies (see saveCompletedCalibrationProfile)
        val sessionId = if (syncToCloud) {
            "sess_" + UUID.randomUUID().toString().replace("-", "")
        } else {
            "local_sess_${System.currentTimeMillis()}"
        }
        val sessionModel = TrackingSession(
            id = sessionId,
            userId = currentUserId,
            title = (if (simulated) "$SIMULATED_PREFIX$customTitle" else customTitle).take(120),
            deviceModel = if (simulated) "Simulator" else (Build.MODEL?.takeIf { it.isNotBlank() } ?: "Pixel 10 Pro XL"),
            contentType = "READING_SECTIONS",
            durationSeconds = durationSec,
            naturalBlinkCount = state.sessionNaturalBlinks,
            intentionalBlinkCount = state.sessionIntentionalBlinks,
            blinksPerMinute = bpm,
            effectiveWordsPerMinute = effectiveWpm,
            fixationClusterCount = allClusters.size,
            topZonePercentage = topPct,
            middleZonePercentage = midPct,
            bottomZonePercentage = botPct,
            sectionLoads = sectionLoadsEncoded,
            clusterPoints = clusterPointsEncoded
        )

        _attentionState.update {
            it.copy(
                isRecording = false,
                computedClusters = clusters,
                selectedReplaySession = sessionModel,
                thermalThrottleDetected = false
            )
        }

        val localSessions = _localSessionsFlow.value
        val evictionNote = if (
            localStore != null &&
            localSessions.size >= LocalTelemetryStore.MAX_LOCAL_SESSIONS &&
            localSessions.none { it.id == sessionId }
        ) {
            " The on-device vault keeps the ${LocalTelemetryStore.MAX_LOCAL_SESSIONS} most recent sessions, " +
                "so the oldest on-device session was removed."
        } else {
            ""
        }
        // The cloud upload uses the entry exactly as persisted (same id), so the merged list de-duplicates them
        val persisted = localStore?.saveLocalSession(currentUserId, sessionModel) ?: sessionModel
        _localSessionsFlow.value = localStore?.loadLocalSessions(currentUserId).orEmpty()

        if (simulated) {
            _statusBannerMessage.value =
                "Simulated session saved on this device only (not synced, labelled SIMULATED).$evictionNote"
            return
        }
        if (!_liveHudState.value.cloudSyncEnabled) {
            _statusBannerMessage.value = "Session saved to on-device storage (Cloud Sync is paused).$evictionNote"
            return
        }

        viewModelScope.launch {
            val res = repository.createTrackingSession(persisted)
            if (res.isSuccess) {
                _statusBannerMessage.value = "Attention session synced to Cloud Firestore & local vault.$evictionNote"
            } else {
                _statusBannerMessage.value =
                    "Saved locally; Cloud sync error: ${res.exceptionOrNull()?.localizedMessage}$evictionNote"
            }
        }
    }

    /** At most [MAX_SAVED_CLUSTERS] clusters spread over the whole session: the longest dwells, in time order. */
    private fun selectSessionDisplayClusters(all: List<FixationClusterPoint>): List<FixationClusterPoint> {
        if (all.size <= MAX_SAVED_CLUSTERS) return all
        val keep = all.indices.sortedByDescending { all[it].dwellMs }.take(MAX_SAVED_CLUSTERS).sorted()
        return keep.map { all[it] }
    }

    fun selectSessionForReplay(session: TrackingSession) {
        if (_attentionState.value.isRecording) {
            // Opening a replay would silently discard the running recording
            _statusBannerMessage.value = "Stop & Save the current recording before opening a replay."
            return
        }
        sessionTimerJob?.cancel()
        // Only passage-frame points can be drawn on the passage heatmap. Legacy (untagged, 3-field) points
        // were recorded in the root overlay frame and would be drawn misaligned, so they are not replayed.
        var legacyPointCount = 0
        val parsedClusters = session.clusterPoints.mapNotNull { encoded ->
            val parts = encoded.split(",")
            when {
                parts.size == 4 && parts[3] == PASSAGE_FRAME_CLUSTER_TAG -> {
                    val x = parts[0].toFloatOrNull()
                    val y = parts[1].toFloatOrNull()
                    val ms = parts[2].toLongOrNull()
                    if (x != null && y != null && ms != null) FixationClusterPoint(x, y, ms) else null
                }
                parts.size == 3 -> {
                    legacyPointCount++
                    null
                }
                else -> null
            }
        }
        if (legacyPointCount > 0 && parsedClusters.isEmpty()) {
            _statusBannerMessage.value =
                "This session was recorded before passage-aligned fixations; its fixation heatmap is not shown."
        }
        _attentionState.update {
            it.copy(
                isRecording = false,
                computedClusters = parsedClusters,
                selectedReplaySession = session,
                thermalThrottleDetected = false
            )
        }
    }

    fun computeSectionSummariesForDisplay(): List<SectionDwellSummary> {
        val state = _attentionState.value
        val replay = state.selectedReplaySession
        if (!state.isRecording && replay != null && replay.sectionLoads.isNotEmpty()) {
            return replay.sectionLoads.mapIndexed { idx, encoded ->
                val parts = encoded.split(":")
                val secId = parts.getOrNull(0) ?: "sec_${idx + 1}"
                val title = parts.getOrNull(1) ?: "Section ${idx + 1}"
                val pct = parts.getOrNull(2)?.toIntOrNull() ?: 0
                val approxMs = (replay.durationSeconds * 1000L * pct) / 100L
                SectionDwellSummary(secId, title, approxMs, pct)
            }
        }

        val totalMs = state.sectionDwellMsMap.values.sum().coerceAtLeast(1L)
        return readingPassageSections.map { sec ->
            val ms = state.sectionDwellMsMap[sec.sectionId] ?: 0L
            val pct = ((ms * 100L) / totalMs).toInt().coerceIn(0, 100)
            SectionDwellSummary(sec.sectionId, sec.heading, ms, pct)
        }
    }

    fun deleteTrackingSession(sessionId: String) {
        _localSessionsFlow.value = localStore?.deleteLocalSession(currentUserId, sessionId).orEmpty()
        val attention = _attentionState.value
        if (!attention.isRecording && attention.selectedReplaySession?.id == sessionId) {
            clearSessionReplay()
        }
        if (sessionId.isNotBlank() && !sessionId.startsWith("local_")) {
            // Cloud (or shared-id) record: delete the Firestore copy too and report the real outcome
            viewModelScope.launch {
                val res = repository.deleteTrackingSession(sessionId)
                _statusBannerMessage.value = if (res.isSuccess) {
                    "Session deleted from this device and Cloud Firestore."
                } else {
                    "Deleted on this device; cloud delete failed: ${res.exceptionOrNull()?.localizedMessage ?: "unknown error"}"
                }
            }
        } else {
            _statusBannerMessage.value = "Local session deleted."
        }
    }

    /**
     * Triggers deletion of all user-scoped telemetry documents in Cloud Firestore
     * (`/users/{uid}/calibrations` and `/users/{uid}/sessions`) while preserving
     * on-device local calibration profiles so the user does not lose local calibration.
     */
    fun deleteFirestoreCloudTelemetry() {
        _liveHudState.update { it.copy(isDeletingCloudData = true) }
        viewModelScope.launch {
            val res = repository.eraseAllUserCloudTelemetry()
            if (res.isSuccess) {
                dropErasedCloudOnlyRecords()
                val count = res.getOrDefault(0)
                val summary = "Deleted $count Firestore cloud document(s) under /users/$currentUserId (on-device vault preserved)."
                _liveHudState.update {
                    it.copy(
                        isDeletingCloudData = false,
                        lastCloudDeletionSummary = summary
                    )
                }
                _statusBannerMessage.value = summary
            } else {
                val errMsg = res.exceptionOrNull()?.localizedMessage ?: "Permission or network error"
                val summary = "Firestore deletion error: $errMsg"
                Log.w(TAG, "Failed to delete Firestore cloud telemetry", res.exceptionOrNull())
                _liveHudState.update {
                    it.copy(
                        isDeletingCloudData = false,
                        lastCloudDeletionSummary = summary
                    )
                }
                _statusBannerMessage.value = summary
            }
        }
    }

    /**
     * After a confirmed cloud-only erase: records that existed only in the cloud are gone, so a cloud-only
     * active profile stops driving tracking (and is never re-applied from a stale listing), and a cloud-only
     * replay is closed. Records with an on-device copy (local or shared id) are kept.
     */
    private fun dropErasedCloudOnlyRecords() {
        val localProfileIds = _localProfilesFlow.value.map { it.id }.toSet()
        val cloudOnlyProfileIds = latestCloudProfiles.map { it.id }
            .filter { it.isNotBlank() && it !in localProfileIds }
        deletedProfileIds.addAll(cloudOnlyProfileIds)
        val activeId = activeProfileId
        if (activeId != null && activeId in cloudOnlyProfileIds) {
            resetEngineToUncalibratedDefaults()
            reconcileActiveProfile(latestMergedProfiles)
        }
        val attention = _attentionState.value
        val replayId = attention.selectedReplaySession?.id
        if (!attention.isRecording && replayId != null && _localSessionsFlow.value.none { it.id == replayId }) {
            clearSessionReplay()
        }
    }

    /**
     * Wipes both on-device local telemetry vault AND all user-scoped Cloud Firestore documents.
     */
    fun eraseAllCloudTelemetry() {
        // The erased calibration must not keep driving tracking or stay on screen; also never re-apply a
        // cloud copy that is still listed until the erase completes.
        deletedProfileIds.addAll(latestMergedProfiles.map { it.id }.filter { it.isNotBlank() })
        resetEngineToUncalibratedDefaults()
        _attentionState.update {
            if (it.isRecording) {
                it.copy(selectedReplaySession = null)
            } else {
                it.copy(selectedReplaySession = null, computedClusters = emptyList())
            }
        }
        localStore?.clearAllLocalData(currentUserId)
        // The vault wipe also removes the enrollment baseline and custom AAC phrases, so the brow detector
        // and the deck go back to the defaults
        forgetEnrollmentBaselineInMemory()
        _aacTilesState.value = defaultAacTiles
        _localProfilesFlow.value = emptyList()
        _localSessionsFlow.value = emptyList()
        _liveHudState.update { it.copy(isDeletingCloudData = true) }
        viewModelScope.launch {
            val res = repository.eraseAllUserCloudTelemetry()
            if (res.isSuccess) {
                val count = res.getOrDefault(0)
                val summary = "Erased all local & Firestore biometric records ($count cloud docs removed)."
                _liveHudState.update {
                    it.copy(
                        isDeletingCloudData = false,
                        lastCloudDeletionSummary = summary
                    )
                }
                _statusBannerMessage.value = summary
            } else {
                Log.w(TAG, "Failed to erase cloud telemetry", res.exceptionOrNull())
                val summary = "Cleared local vault; cloud erase error: ${res.exceptionOrNull()?.localizedMessage}"
                _liveHudState.update {
                    it.copy(
                        isDeletingCloudData = false,
                        lastCloudDeletionSummary = summary
                    )
                }
                _statusBannerMessage.value = summary
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        sessionTimerJob?.cancel()
        simulatorTickerJob?.cancel()
        frameWatchdogJob?.cancel()
    }

    companion object {
        /** Section id the Attention screen reports while its layout is attached but no section is hit. */
        const val OFF_PASSAGE_SECTION_ID = com.example.ui.screens.ATTENTION_OFF_PASSAGE_SECTION_ID

        /** Fourth field of a saved cluster point recorded in the passage frame (legacy points have 3 fields). */
        private const val PASSAGE_FRAME_CLUSTER_TAG = "p"

        // Bounds for the reported gaze-frame size, by short and long side so landscape frames keep their height
        private const val MIN_FRAME_SIDE_MM = 20.0
        private const val MAX_FRAME_SHORT_SIDE_MM = 400.0
        private const val MAX_FRAME_LONG_SIDE_MM = 600.0

        // Blink detector defaults and the ranges a stored profile's blink settings must fall in to be applied
        private const val DEFAULT_CLOSE_BLINK_THRESHOLD = 0.45f
        private const val DEFAULT_OPEN_BLINK_THRESHOLD = 0.65f
        private const val DEFAULT_INTENTIONAL_BLINK_MIN_MS = 350L
        private const val MIN_CLOSE_BLINK_THRESHOLD = 0.05
        private const val MAX_CLOSE_BLINK_THRESHOLD = 0.80
        private const val MAX_OPEN_BLINK_THRESHOLD = 0.98
        private const val MIN_BLINK_HYSTERESIS_GAP = 0.05
        private const val MIN_INTENTIONAL_BLINK_MS = 150L
        private const val MAX_INTENTIONAL_BLINK_MIN_MS = 700L

        private const val TAG = "EyeTrackingVM"
        private const val STOP_TIMEOUT_MILLIS = 5000L
        private const val SETTLING_DELAY_MS = 400L
        private const val SAMPLES_PER_TARGET = 18
        private const val MAX_SESSION_GAZE_SAMPLES = 3600
        // Newest raw samples the live Reading Lab heatmap clusters over while recording
        private const val LIVE_CLUSTER_WINDOW_SAMPLES = 240
        // Session FPS timeline: samples kept (one per second) and the recent window for the thermal guard
        private const val FPS_TIMELINE_MAX_SAMPLES = 120
        private const val FPS_RECENT_WINDOW_SAMPLES = 10
        private const val MAX_SAVED_CLUSTERS = 40
        private const val SIMULATED_PREFIX = "[SIMULATED] "
        // Same layout name the Control Deck uses for unvalidated metrics (GazeCalibrationMath.gridLayoutLabel)
        private val QUICK_FIT_GRID_LABEL = GazeCalibrationMath.gridLayoutLabel(use3x3 = false) +
            " — 5-point fit residual only, not validated; run 5-Point Fit + 4-Point Validation to unlock 3×3"
        private const val MIN_BROW_RAISE_SEPARATION = 0.035f
        // Dwell: frame gaps longer than this (background, camera rebind) reset a pending dwell;
        // invalid gaze longer than an extended closure resets it, shorter invalid runs only pause it.
        private const val DWELL_FRAME_GAP_RESET_MS = 500L
        private const val DWELL_INVALID_RESET_MS = 900L
        private const val GAZE_REFIRE_REFRACTORY_MS = 1500L
        private const val DRIFT_ETA = 0.025
        private const val DRIFT_MAX_OFFSET = 0.05
        private const val RECENTER_SETTLE_MS = 600L
        private const val RECENTER_WINDOW_MS = 3000L
        private const val RECENTER_SAMPLE_COUNT = 8
        private const val RECENTER_MAX_OFFSET = 0.2
        private const val PERF_MODE_RECOVERY_MS = 5000L
        private const val RESOLUTION_STEP_UP_HOLD_MS = 10_000L
        private const val FRAME_STALE_MS = 1500L
        private const val SESSION_TIMER_FRAME_STALE_MS = 2000L
        private const val MAX_PLAUSIBLE_WPM = 900L
    }
}

// Compiled once instead of once per passage section per ViewModel
private val WHITESPACE_RUN_REGEX = Regex("\\s+")

private fun passageSection(sectionId: String, heading: String, body: String) = SectionPassageItem(
    sectionId = sectionId,
    heading = heading,
    body = body,
    wordCount = body.trim().split(WHITESPACE_RUN_REGEX).size
)

/** Cause shown in the banner when the analysis resolution steps down. */
internal fun resolutionStepCauseLabel(lowRateStep: Boolean, liveFps: Int): String =
    if (lowRateStep) {
        "Low analysis rate ($liveFps FPS < 15)"
    } else {
        "Thermal guard ($liveFps FPS, >40% below session baseline)"
    }

// --- Live heatmap trail -------------------------------------------------------------------------------------

private const val LIVE_TRAIL_MAX_POINTS = 32
private const val LIVE_TRAIL_MAX_AGE_MS = 4800L
private const val LIVE_TRAIL_MIN_INTENSITY = 0.08f
private const val LIVE_TRAIL_MERGE_RADIUS = 0.065f
// Squared distances where the merge decision is certain without hypot: sqrt(0.0041) = 0.0640 and
// sqrt(0.0044) = 0.0663 lie far outside any rounding of the 0.065 radius. Only the band in between (and
// non-finite input) takes the exact hypot comparison, so the decision is the same as always using hypot.
private const val LIVE_TRAIL_MERGE_SURE_INSIDE_SQ = 0.0041
private const val LIVE_TRAIL_MERGE_SURE_OUTSIDE_SQ = 0.0044
private const val LIVE_TRAIL_DECAY_TAU_MS = 2400.0
// Frame intervals reach the trail clamped to 16..500 ms; the decay table covers 0..500 ms
private const val LIVE_TRAIL_DECAY_TABLE_MAX_DT_MS = 500

private fun computeLiveTrailDecayFactor(dtMs: Long): Float =
    exp(-dtMs.toDouble() / LIVE_TRAIL_DECAY_TAU_MS).toFloat().coerceIn(0.82f, 0.99f)

/** exp() per frame replaced by a lookup; built on first use from the same expression. */
private object LiveTrailDecayTable {
    val byDtMs = FloatArray(LIVE_TRAIL_DECAY_TABLE_MAX_DT_MS + 1) { dt -> computeLiveTrailDecayFactor(dt.toLong()) }
}

/** Per-frame intensity decay of the live trail for a frame interval of [dtMs]. */
internal fun liveGazeTrailDecayFactor(dtMs: Long): Float =
    if (dtMs in 0L..LIVE_TRAIL_DECAY_TABLE_MAX_DT_MS.toLong()) {
        LiveTrailDecayTable.byDtMs[dtMs.toInt()]
    } else {
        computeLiveTrailDecayFactor(dtMs)
    }

/** Same decision as `hypot(dx, dy).toFloat() <= 0.065f`, calling hypot only near the radius. */
private fun isWithinLiveTrailMergeRadius(dx: Double, dy: Double): Boolean {
    val distSq = dx * dx + dy * dy
    return when {
        distSq < LIVE_TRAIL_MERGE_SURE_INSIDE_SQ -> true
        distSq > LIVE_TRAIL_MERGE_SURE_OUTSIDE_SQ -> false
        else -> hypot(dx, dy).toFloat() <= LIVE_TRAIL_MERGE_RADIUS
    }
}

/**
 * Maintains a rolling real-time thermal gaze trail where dwelling near an existing node
 * accumulates thermal intensity (Cyan -> Emerald -> Amber -> Crimson) and older nodes
 * decay smoothly over a 4.8-second window.
 *
 * Every surviving node decays, so a new immutable list is published each frame, but it is allocated once at
 * its exact size: a first pass only counts the survivors, so the node that is about to be merged is never
 * copied first and a full trail never shifts its backing array to drop the oldest node.
 */
internal fun advanceLiveGazeTrail(
    prevTrail: List<LiveGazeTrailPoint>,
    normX: Float,
    normY: Float,
    faceDetected: Boolean,
    nowMs: Long,
    dtMs: Long
): List<LiveGazeTrailPoint> {
    val decayFactor = liveGazeTrailDecayFactor(dtMs)
    val prevSize = prevTrail.size
    val startIndex = (prevSize - LIVE_TRAIL_MAX_POINTS).coerceAtLeast(0)

    // Pass 1 (no allocation): how many nodes survive the decay, and which one is the newest survivor
    var survivorCount = 0
    var lastSurvivorIndex = -1
    for (i in startIndex until prevSize) {
        val pt = prevTrail[i]
        if (nowMs - pt.timestampMs <= LIVE_TRAIL_MAX_AGE_MS && pt.intensity * decayFactor >= LIVE_TRAIL_MIN_INTENSITY) {
            survivorCount++
            lastSurvivorIndex = i
        }
    }

    // A face frame near the newest survivor merges into it (dwell); any other face frame appends a node,
    // first dropping the oldest survivor when the trail is full
    val mergeIntoLast = faceDetected && lastSurvivorIndex >= 0 && prevTrail[lastSurvivorIndex].let { last ->
        isWithinLiveTrailMergeRadius((normX - last.normX).toDouble(), (normY - last.normY).toDouble())
    }
    val appendNew = faceDetected && !mergeIntoLast
    var survivorsToDrop = if (appendNew && survivorCount >= LIVE_TRAIL_MAX_POINTS) 1 else 0
    val outSize = survivorCount - survivorsToDrop + (if (appendNew) 1 else 0)
    if (outSize == 0) return emptyList()

    // Pass 2: decayed copies of the kept survivors (the merged survivor is replaced, not copied)
    val out = ArrayList<LiveGazeTrailPoint>(outSize)
    val copyEnd = if (mergeIntoLast) lastSurvivorIndex else prevSize
    for (i in startIndex until copyEnd) {
        val pt = prevTrail[i]
        val nextIntensity = pt.intensity * decayFactor
        if (nowMs - pt.timestampMs <= LIVE_TRAIL_MAX_AGE_MS && nextIntensity >= LIVE_TRAIL_MIN_INTENSITY) {
            if (survivorsToDrop > 0) {
                survivorsToDrop--
            } else {
                out.add(pt.copy(intensity = nextIntensity))
            }
        }
    }
    if (mergeIntoLast) {
        val last = prevTrail[lastSurvivorIndex]
        out.add(
            LiveGazeTrailPoint(
                normX = (last.normX * 0.72f + normX * 0.28f).coerceIn(0.02f, 0.98f),
                normY = (last.normY * 0.72f + normY * 0.28f).coerceIn(0.02f, 0.98f),
                intensity = (last.intensity * decayFactor + 0.18f).coerceAtMost(1.0f),
                timestampMs = nowMs
            )
        )
    } else if (appendNew) {
        out.add(LiveGazeTrailPoint(normX = normX, normY = normY, intensity = 0.34f, timestampMs = nowMs))
    }
    return out
}

// --- Session FPS timeline -----------------------------------------------------------------------------------

/** `(timeline + fps).takeLast(maxSize)`, built as a single list of the exact size. */
internal fun appendFpsSample(timeline: List<Int>, fps: Int, maxSize: Int): List<Int> {
    require(maxSize >= 0) { "Requested element count $maxSize is less than zero." }
    if (maxSize == 0) return emptyList()
    val from = (timeline.size + 1 - maxSize).coerceAtLeast(0)
    val next = ArrayList<Int>(timeline.size + 1 - from)
    for (i in from until timeline.size) next.add(timeline[i])
    next.add(fps)
    return next
}

/** `timeline.takeLast(count).average()` without the copy: same summation order, NaN when empty. */
internal fun averageOfLastFpsSamples(timeline: List<Int>, count: Int): Double {
    require(count >= 0) { "Requested element count $count is less than zero." }
    val from = (timeline.size - count).coerceAtLeast(0)
    var sum = 0.0
    for (i in from until timeline.size) sum += timeline[i]
    val n = timeline.size - from
    return if (n == 0) Double.NaN else sum / n
}

private fun Double.finiteOrNull(): Double? = takeIf { it.isFinite() }

/**
 * Integer percentages summing to 100 (largest-remainder rounding); all zero when nothing was measured.
 */
private fun largestRemainderPercentages(values: List<Long>): List<Int> {
    val total = values.sum()
    if (total <= 0L) return values.map { 0 }
    val result = values.map { ((it * 100L) / total).toInt() }.toMutableList()
    var leftover = 100 - result.sum()
    val byRemainder = values.indices.sortedByDescending { (values[it] * 100L) % total }
    for (i in byRemainder) {
        if (leftover <= 0) break
        result[i] = result[i] + 1
        leftover--
    }
    return result
}

fun isRunningOnEmulatorHardware(): Boolean {
    val fingerprint = (Build.FINGERPRINT ?: "").lowercase()
    val model = (Build.MODEL ?: "").lowercase()
    val hardware = (Build.HARDWARE ?: "").lowercase()
    val product = (Build.PRODUCT ?: "").lowercase()
    val brand = (Build.BRAND ?: "").lowercase()
    val device = (Build.DEVICE ?: "").lowercase()
    return fingerprint.startsWith("generic") ||
        fingerprint.contains("emulator") ||
        fingerprint.contains("sdk_gphone") ||
        model.contains("google_sdk") ||
        model.contains("emulator") ||
        model.contains("sdk_gphone") ||
        model.contains("android sdk built for") ||
        hardware.contains("goldfish") ||
        hardware.contains("ranchu") ||
        hardware.contains("cuttlefish") ||
        hardware.contains("vbox") ||
        product.contains("sdk_gphone") ||
        product.contains("emulator") ||
        product.contains("simulator") ||
        (brand.startsWith("generic") && device.startsWith("generic"))
}
