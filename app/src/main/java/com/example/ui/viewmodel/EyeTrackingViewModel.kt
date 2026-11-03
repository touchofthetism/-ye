package com.example.ui.viewmodel

import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AacActionTile
import com.example.data.model.BlinkType
import com.example.data.model.CalibrationProfile
import com.example.data.model.DEFAULT_AFFINE_MATRIX_2X6
import com.example.data.model.FixationClusterPoint
import com.example.data.model.GazeFeatureVector
import com.example.data.model.SectionDwellSummary
import com.example.data.model.SectionPassageItem
import com.example.data.model.TrackingSession
import com.example.data.repository.EyeTrackingRepository
import com.example.data.repository.LocalTelemetryStore
import com.example.domain.BlinkHysteresisDetector
import com.example.domain.GazeCalibrationMath
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

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
    val currentTargetIndex: Int = 0,
    val currentTargetProgress: Float = 0f,
    val isSettlingDelay: Boolean = false,
    val fittedMatrix: List<Double> = DEFAULT_AFFINE_MATRIX_2X6,
    val capturedBaselineEyeDistPx: Double = 148.0,
    val validationMeanErrorMm: Double = 13.4,
    val validationMeanErrorPct: Double = 10.9,
    val validationMaxErrorPct: Double = 14.2,
    val recommendedGridLabel: String = "3×2 Standard Deck (All Quadrants Verified)",
    val draftProfileName: String = "Pixel 10 Pro XL Desk"
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
    val isSimulatorProxyEnabled: Boolean = false,
    val cloudSyncEnabled: Boolean = false,
    val activeProfileName: String = "Pixel 10 Pro XL Default",
    val activeProfileDeviceModel: String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Pixel 10 Pro XL",
    val activeErrorRadiusPct: Float = 10.9f,
    val activeMaxQuadrantErrorPct: Float = 14.2f,
    val activeErrorMm: Double = 13.4,
    val analyzerFps: Int = 14,
    val dwellTriggerMs: Long = 1100L,
    val focusedTileId: String? = null,
    val focusedTileDwellProgress: Float = 0f,
    val lastTriggeredSpeech: String = "Fixate on any tile and raise brows (400ms), command-blink (350–800ms), or dwell to speak."
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

    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

    private val _localProfilesFlow = MutableStateFlow<List<CalibrationProfile>>(
        localStore?.loadLocalProfiles(currentUserId).orEmpty()
    )
    private val _localSessionsFlow = MutableStateFlow<List<TrackingSession>>(
        localStore?.loadLocalSessions(currentUserId).orEmpty()
    )

    private val _liveHudState = MutableStateFlow(
        LiveGazeHudState(
            cloudSyncEnabled = localStore?.isCloudSyncEnabled() ?: false
        )
    )
    val liveHudState: StateFlow<LiveGazeHudState> = _liveHudState.asStateFlow()

    private val _calibrationState = MutableStateFlow(CalibrationWizardState())
    val calibrationState: StateFlow<CalibrationWizardState> = _calibrationState.asStateFlow()

    private val _attentionState = MutableStateFlow(AttentionSessionLiveState())
    val attentionState: StateFlow<AttentionSessionLiveState> = _attentionState.asStateFlow()

    // Merge Cloud Firestore profiles with on-device LocalTelemetryStore so sync-off users never lose calibrations
    val calibrationProfilesState: StateFlow<UiDataState<List<CalibrationProfile>>> =
        combine(
            repository.observeCalibrationProfiles(currentUserId)
                .map<List<CalibrationProfile>, Result<List<CalibrationProfile>>> { Result.success(it) }
                .catch { emit(Result.failure(it)) },
            _localProfilesFlow
        ) { cloudResult, localList ->
            val cloudList = cloudResult.getOrNull().orEmpty()
            val merged = (cloudList + localList)
                .distinctBy { it.id.ifBlank { it.name + it.createdAt } }
                .sortedByDescending { it.createdAt?.seconds ?: 0L }

            val active = merged.firstOrNull { it.isActive } ?: merged.firstOrNull()
            if (active != null) {
                applyProfileToEngine(active)
            }
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
            repository.observeTrackingSessions(currentUserId)
                .map<List<TrackingSession>, Result<List<TrackingSession>>> { Result.success(it) }
                .catch { emit(Result.failure(it)) },
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
    private var activeBaselineEyeDistPx: Double = 148.0
    private val blinkDetector = BlinkHysteresisDetector(
        closeThreshold = 0.45f,
        openThreshold = 0.65f,
        intentionalMinMs = 350L,
        maxCommandMs = 800L
    )
    private val browDetector = com.example.domain.BrowRaiseHysteresisDetector(
        neutralBrowRatio = 0.34f,
        raisedBrowRatio = 0.42f,
        holdTargetMs = 400L
    )

    // Calibration sample buffers
    private val fitSamples = mutableListOf<Pair<GazeFeatureVector, Pair<Float, Float>>>()
    private val validationSamples = mutableListOf<Pair<GazeFeatureVector, Pair<Float, Float>>>()
    private var samplesForCurrentTarget = 0
    private var targetStartTimeMs = 0L

    // Screen dimensions in mm (defaults to Google Pixel 10 Pro XL 6.8" LTPO OLED active area: 71.3mm x 158.4mm)
    private var displayWidthMm: Double = GazeCalibrationMath.PIXEL_10_PRO_XL_WIDTH_MM
    private var displayHeightMm: Double = GazeCalibrationMath.PIXEL_10_PRO_XL_HEIGHT_MM

    // Frame cadence, thermal FPS guard & edge-pinning drift tracker
    private var lastFrameTimestampMs: Long = 0L
    private var smoothedFrameIntervalMs: Double = 70.0 // ~14 FPS initial estimate
    private var lowFpsDurationMs: Long = 0L
    private var sessionBaselineFps: Double = 15.0
    private var edgePinnedDurationMs: Long = 0L

    // Dwell tracking & layout-measured section ID
    private var currentHoveredTileId: String? = null
    private var currentHoverStartMs: Long = 0L
    private var dwellTriggeredForCurrentHover: Boolean = false
    private var onTileTriggeredCallback: ((AacActionTile) -> Unit)? = null
    private var layoutHitSectionId: String? = null

    // Attention session raw trajectory
    private val rawSessionGazeSamples = mutableListOf<Triple<Float, Float, Long>>()
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
        AacActionTile("tile_recenter", "Recenter Gaze", "Zero-point offset snap", "Gaze cursor recentered to screen midpoint.", "SYSTEM"),
        AacActionTile("tile_thanks", "Thank You", "Express gratitude", "Thank you very much for your help.", "COMMUNICATION"),
        AacActionTile("tile_question", "Ask Question", "Request clarification", "I have a question I would like to ask.", "COMMUNICATION"),
        AacActionTile("tile_rest", "Eyes Tired", "Pause session briefly", "My eyes need a short rest break.", "NEEDS")
    )

    private val _aacTilesState = MutableStateFlow<List<AacActionTile>>(
        localStore?.loadCustomAacTiles(currentUserId, defaultAacTiles) ?: defaultAacTiles
    )
    val aacTilesState: StateFlow<List<AacActionTile>> = _aacTilesState.asStateFlow()
    val aacTiles: List<AacActionTile>
        get() = _aacTilesState.value

    val readingPassageSections: List<SectionPassageItem> = listOf(
        SectionPassageItem(
            sectionId = "sec_1",
            heading = "1. Optical Corneal Reflection & Visible-Light Constraints",
            body = "Unlike dedicated infrared eye trackers that illuminate the retina to produce a high-contrast bright-pupil reflex, mobile front-facing RGB cameras rely on ambient visible light. Consequently, OculaGaze crops out the upper 25% eyelash shadow band, stretches local ROI contrast, and weights each eye by its glare-free confidence to achieve reliable region-level gaze estimation.",
            wordCount = 52
        ),
        SectionPassageItem(
            sectionId = "sec_2",
            heading = "2. Standardized 2×6 Ridge Regression & Head-Pose Coupling",
            body = "When viewing a handheld smartphone, subtle head rotations cross-couple with eye-in-orbit rotations. Mapping a unit-scaled 6D feature vector—combining confidence-fused iris centroids, 3D head Euler angles, and inter-ocular scale—through a regularized 2×6 affine matrix compensates for head-pose drift across the screen.",
            wordCount = 44
        ),
        SectionPassageItem(
            sectionId = "sec_3",
            heading = "3. Expression & Hysteresis Gating vs. Dwell Ergonomics",
            body = "Humans blink involuntarily 12 to 18 times per minute with typical closures lasting 80 to 349 milliseconds. By combining pitch-compensated brow-raise triggers (400 ms hold) with Schmitt-trigger command blinks (350 to 800 ms) and region dwell, hands-free UI tiles avoid false activations during natural reading.",
            wordCount = 46
        )
    )

    init {
        val localInitial = _localProfilesFlow.value.firstOrNull { it.isActive } ?: _localProfilesFlow.value.firstOrNull()
        if (localInitial != null) {
            applyProfileToEngine(localInitial)
        }
    }

    fun setTriggerMode(mode: com.example.data.model.GazeTriggerMode) {
        _liveHudState.update { it.copy(triggerMode = mode) }
    }

    fun cycleOverlayVisualMode() {
        val modes = com.example.data.model.GazeOverlayVisualMode.entries
        val current = _liveHudState.value.overlayVisualMode
        val next = modes[(modes.indexOf(current) + 1) % modes.size]
        _liveHudState.update { it.copy(overlayVisualMode = next) }
    }

    fun calibrateNeutralBrowBaseline() {
        val currentRatio = _liveHudState.value.rawFeature.browToEyeRatio.coerceIn(0.20f, 0.52f)
        val raised = maxOf(currentRatio + 0.055f, browDetector.raisedBrowRatio)
        browDetector.neutralBrowRatio = currentRatio
        browDetector.raisedBrowRatio = raised
        _liveHudState.update {
            it.copy(neutralBrowRatio = currentRatio, raisedBrowRatio = raised)
        }
        _statusBannerMessage.value = "Captured Neutral Brow baseline (${String.format("%.3f", currentRatio)}). Now raise eyebrows and tap 'Sample Raised Brow'."
    }

    fun calibrateRaisedBrowPeak() {
        val currentRatio = _liveHudState.value.rawFeature.browToEyeRatio.coerceIn(0.24f, 0.62f)
        val neutral = browDetector.neutralBrowRatio
        val validRaised = maxOf(currentRatio, neutral + 0.045f)
        browDetector.raisedBrowRatio = validRaised
        _liveHudState.update {
            it.copy(raisedBrowRatio = validRaised)
        }
        _statusBannerMessage.value = "Expression calibrated: Neutral ${String.format("%.3f", neutral)} → Raised ${String.format("%.3f", validRaised)} (Trigger @ ${String.format("%.3f", browDetector.raiseThreshold)})."
    }

    fun simulateBrowRaiseCommand() {
        val now = System.currentTimeMillis()
        val base = _liveHudState.value.rawFeature
        val peakRatio = (browDetector.raisedBrowRatio + 0.03f).coerceAtMost(0.60f)
        processIncomingFeatureFrame(base.copy(browToEyeRatio = peakRatio, faceDetected = true, timestampMs = now))
        viewModelScope.launch {
            delay(430L)
            val holdTs = System.currentTimeMillis()
            processIncomingFeatureFrame(base.copy(browToEyeRatio = peakRatio, faceDetected = true, timestampMs = holdTs))
            delay(120L)
            val relaxTs = System.currentTimeMillis()
            processIncomingFeatureFrame(base.copy(browToEyeRatio = browDetector.neutralBrowRatio, faceDetected = true, timestampMs = relaxTs))
        }
    }

    fun updateActiveReadingSectionFromLayout(sectionId: String?) {
        layoutHitSectionId = sectionId
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

    fun setDisplayPhysicalSizeMm(widthMm: Double, heightMm: Double) {
        val isPixel10ProXl = (Build.MODEL ?: "").contains("Pixel 10", ignoreCase = true)
        if (isPixel10ProXl) {
            displayWidthMm = GazeCalibrationMath.PIXEL_10_PRO_XL_WIDTH_MM
            displayHeightMm = GazeCalibrationMath.PIXEL_10_PRO_XL_HEIGHT_MM
        } else {
            displayWidthMm = widthMm.coerceIn(50.0, 400.0)
            displayHeightMm = heightMm.coerceIn(90.0, 600.0)
        }
    }

    fun registerTileTriggerListener(listener: (AacActionTile) -> Unit) {
        onTileTriggeredCallback = listener
    }

    fun dismissStatusBanner() {
        _statusBannerMessage.value = null
    }

    fun toggleCloudSync(enabled: Boolean) {
        localStore?.setCloudSyncEnabled(enabled)
        _liveHudState.update { it.copy(cloudSyncEnabled = enabled) }
        _statusBannerMessage.value = if (enabled) {
            "Cloud Sync enabled — calibrations & sessions back up to Firestore and local storage."
        } else {
            "Cloud Sync paused — profiles & sessions persist privately on-device across restarts."
        }
    }

    fun toggleSimulatorProxyMode(enabled: Boolean) {
        _liveHudState.update { it.copy(isSimulatorProxyEnabled = enabled) }
        simulatorTickerJob?.cancel()
        if (enabled) {
            simulatorTickerJob = viewModelScope.launch {
                while (true) {
                    delay(66L) // ~15 FPS realistic cadence
                    val current = _liveHudState.value.rawFeature
                    val now = System.currentTimeMillis()
                    processIncomingFeatureFrame(
                        current.copy(
                            faceDetected = true,
                            timestampMs = now
                        )
                    )
                }
            }
        }
    }

    fun updateSimulatorGazeTarget(normX: Float, normY: Float, simulatedEyeOpen: Float = 0.92f) {
        if (!_liveHudState.value.isSimulatorProxyEnabled) {
            toggleSimulatorProxyMode(true)
        }
        val syntheticGx = ((normX - 0.5f) * 0.42f).toDouble()
        val syntheticGy = ((normY - 0.5f) * 0.36f).toDouble()
        val syntheticYaw = ((0.5f - normX) * 14f).toDouble()
        val syntheticPitch = ((0.5f - normY) * 12f).toDouble()
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
            timestampMs = System.currentTimeMillis()
        )
        processIncomingFeatureFrame(vec)
    }

    fun simulateIntentionalCommandBlink() {
        val now = System.currentTimeMillis()
        val base = _liveHudState.value.rawFeature
        processIncomingFeatureFrame(base.copy(leftEyeOpenProb = 0.10f, rightEyeOpenProb = 0.10f, faceDetected = true, timestampMs = now))
        viewModelScope.launch {
            delay(460L)
            val reopenTs = System.currentTimeMillis()
            processIncomingFeatureFrame(base.copy(leftEyeOpenProb = 0.92f, rightEyeOpenProb = 0.92f, faceDetected = true, timestampMs = reopenTs))
        }
    }

    fun processIncomingFeatureFrame(feature: GazeFeatureVector) {
        val now = feature.timestampMs
        val dtMs = if (lastFrameTimestampMs > 0L) (now - lastFrameTimestampMs).coerceIn(16L, 500L) else 66L
        lastFrameTimestampMs = now
        smoothedFrameIntervalMs = 0.85 * smoothedFrameIntervalMs + 0.15 * dtMs.toDouble()
        val liveFps = (1000.0 / smoothedFrameIntervalMs.coerceAtLeast(25.0)).roundToInt().coerceIn(1, 60)

        // Adaptive Performance Mode & Thermal Guard: step down ROI workload if FPS < 12 for >3s,
        // or if session FPS drops >40% below first-minute baseline; recover if FPS >= 20
        val prev = _liveHudState.value
        lowFpsDurationMs = if (liveFps < 12) (lowFpsDurationMs + dtMs).coerceAtMost(15000L) else 0L
        val thermalDrop = _attentionState.value.thermalThrottleDetected
        val perfMode = when {
            lowFpsDurationMs >= 3000L || thermalDrop -> true
            liveFps >= 20 -> false
            else -> prev.isPerformanceModeActive
        }

        val (rawPredX, rawPredY) = GazeCalibrationMath.projectGaze(
            feature = feature,
            matrixCoeffs = activeMatrix,
            baselineEyeDistPx = activeBaselineEyeDistPx
        )

        // CRITICAL AUDIT FIX: Freeze gaze position updates while eyelids are closing/closed (< 0.50 open prob)
        // so upper-eyelid sweep never drags the reticle off the focused tile during an intentional blink!
        val isEyelidClosing = feature.combinedEyeOpenProb < 0.50f
        val alpha = GazeCalibrationMath.computeAdaptiveSmoothingAlpha(dtMs)
        val smoothedX = if (feature.faceDetected && !isEyelidClosing) {
            (prev.normX * (1f - alpha) + rawPredX * alpha).coerceIn(0.02f, 0.98f)
        } else {
            prev.normX
        }
        val smoothedY = if (feature.faceDetected && !isEyelidClosing) {
            (prev.normY * (1f - alpha) + rawPredY * alpha).coerceIn(0.02f, 0.98f)
        } else {
            prev.normY
        }

        val mode = prev.triggerMode
        val blinkEnabled = mode == com.example.data.model.GazeTriggerMode.ALL_ACTIVE ||
            mode == com.example.data.model.GazeTriggerMode.COMMAND_BLINK
        val browEnabled = mode == com.example.data.model.GazeTriggerMode.ALL_ACTIVE ||
            mode == com.example.data.model.GazeTriggerMode.BROW_RAISE

        val (blinkProgress, blinkEvent) = blinkDetector.update(feature.combinedEyeOpenProb, now)
        val (browProgress, browFired) = if (browEnabled && feature.faceDetected && !isEyelidClosing) {
            browDetector.update(feature.browToEyeRatio, now)
        } else {
            Pair(0f, false)
        }

        var natBlinks = prev.naturalBlinkCount
        var cmdBlinks = prev.intentionalBlinkCount
        var browCount = prev.browRaiseTriggerCount
        var blinkLabel = prev.lastBlinkLabel

        if (browFired) {
            browCount++
            blinkLabel = "Brow-Raise Trigger (400 ms hold) — tile triggered!"
            val tileId = currentHoveredTileId
            if (tileId != null) {
                fireTileAction(tileId, "Brow Raise")
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
                        blinkLabel = "Command blink (${blinkEvent.durationMs} ms) — tile triggered!"
                        if (_attentionState.value.isRecording) {
                            _attentionState.update { it.copy(sessionIntentionalBlinks = it.sessionIntentionalBlinks + 1) }
                        }
                        triggerFocusedTileByCommandBlink()
                    } else {
                        blinkLabel = "Command blink (${blinkEvent.durationMs} ms) — disabled in ${mode.label}"
                    }
                }
                BlinkType.EXTENDED_REST -> {
                    blinkLabel = "Extended closure (${blinkEvent.durationMs} ms) — trigger cancelled"
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
        val showNudge = edgePinnedDurationMs >= 4000L

        val dwellProgress = updateTileHoverDwell(now)
        val updatedTrail = updateLiveHeatmapTrail(
            prevTrail = prev.liveTrailPoints,
            normX = smoothedX,
            normY = smoothedY,
            faceDetected = feature.faceDetected,
            nowMs = now,
            dtMs = dtMs
        )

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
                analyzerFps = liveFps,
                focusedTileDwellProgress = dwellProgress
            )
        }

        if (feature.faceDetected) {
            advanceCalibrationIfActive(feature, now)
            recordAttentionSampleIfActive(smoothedX, smoothedY, now)
        }
    }

    /**
     * Maintains a rolling real-time thermal gaze trail where dwelling near an existing node
     * accumulates thermal intensity (Cyan -> Emerald -> Amber -> Crimson) and older nodes
     * decay smoothly over a 4.5-second window.
     */
    private fun updateLiveHeatmapTrail(
        prevTrail: List<com.example.data.model.LiveGazeTrailPoint>,
        normX: Float,
        normY: Float,
        faceDetected: Boolean,
        nowMs: Long,
        dtMs: Long
    ): List<com.example.data.model.LiveGazeTrailPoint> {
        val decayFactor = kotlin.math.exp(-dtMs.toDouble() / 2400.0).toFloat().coerceIn(0.82f, 0.99f)
        val decayed = prevTrail.mapNotNull { pt ->
            val age = nowMs - pt.timestampMs
            val nextIntensity = pt.intensity * decayFactor
            if (age <= 4800L && nextIntensity >= 0.08f) {
                pt.copy(intensity = nextIntensity)
            } else {
                null
            }
        }.toMutableList()

        if (!faceDetected) return decayed

        val last = decayed.lastOrNull()
        if (last != null) {
            val dist = kotlin.math.hypot((normX - last.normX).toDouble(), (normY - last.normY).toDouble()).toFloat()
            if (dist <= 0.065f) {
                val boosted = (last.intensity + 0.18f).coerceAtMost(1.0f)
                val mergedX = (last.normX * 0.72f + normX * 0.28f).coerceIn(0.02f, 0.98f)
                val mergedY = (last.normY * 0.72f + normY * 0.28f).coerceIn(0.02f, 0.98f)
                decayed[decayed.lastIndex] = com.example.data.model.LiveGazeTrailPoint(
                    normX = mergedX,
                    normY = mergedY,
                    intensity = boosted,
                    timestampMs = nowMs
                )
                return decayed.takeLast(32)
            }
        }

        decayed.add(
            com.example.data.model.LiveGazeTrailPoint(
                normX = normX,
                normY = normY,
                intensity = 0.34f,
                timestampMs = nowMs
            )
        )
        return decayed.takeLast(32)
    }

    fun updateFocusedTile(tileId: String?) {
        val now = System.currentTimeMillis()
        if (tileId != currentHoveredTileId) {
            currentHoveredTileId = tileId
            currentHoverStartMs = now
            dwellTriggeredForCurrentHover = false
            _liveHudState.update {
                it.copy(focusedTileId = tileId, focusedTileDwellProgress = 0f)
            }
        }
    }

    private fun updateTileHoverDwell(nowMs: Long): Float {
        val tileId = currentHoveredTileId ?: return 0f
        val dwellTargetMs = _liveHudState.value.dwellTriggerMs.coerceAtLeast(400L)
        val elapsed = (nowMs - currentHoverStartMs).coerceAtLeast(0L)
        val progress = (elapsed.toFloat() / dwellTargetMs.toFloat()).coerceIn(0f, 1f)
        if (progress >= 1f && !dwellTriggeredForCurrentHover) {
            dwellTriggeredForCurrentHover = true
            fireTileAction(tileId, "Dwell (${dwellTargetMs}ms)")
        }
        return progress
    }

    private fun triggerFocusedTileByCommandBlink() {
        val tileId = currentHoveredTileId ?: return
        fireTileAction(tileId, "Intentional Blink")
    }

    fun fireTileAction(tileId: String, triggerMethod: String = "Tap") {
        val tile = _aacTilesState.value.firstOrNull { it.id == tileId } ?: return
        if (tile.id == "tile_recenter") {
            edgePinnedDurationMs = 0L
            _liveHudState.update {
                it.copy(
                    normX = 0.50f,
                    normY = 0.50f,
                    showRecalibrateNudge = false,
                    lastTriggeredSpeech = "[Recenter via $triggerMethod] ${tile.speechPhrase}"
                )
            }
        } else {
            _liveHudState.update {
                it.copy(lastTriggeredSpeech = "[${tile.title} • $triggerMethod] \"${tile.speechPhrase}\"")
            }
        }
        onTileTriggeredCallback?.invoke(tile)
    }

    fun setDwellTriggerDuration(durationMs: Long) {
        _liveHudState.update { it.copy(dwellTriggerMs = durationMs.coerceIn(600L, 2500L)) }
    }

    fun setBlinkTimingThreshold(minCommandMs: Long, closeThresh: Float) {
        val clampedMs = minCommandMs.coerceIn(280L, 700L)
        val clampedClose = closeThresh.coerceIn(0.15f, 0.40f)
        blinkDetector.intentionalMinMs = clampedMs
        blinkDetector.closeThreshold = clampedClose
    }

    // --- 5-Point Calibration + 4-Point Held-Out Validation State Machine ---

    fun startCalibrationAndValidationWizard() {
        fitSamples.clear()
        validationSamples.clear()
        samplesForCurrentTarget = 0
        targetStartTimeMs = System.currentTimeMillis()
        val modelName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Pixel 10 Pro XL"
        _calibrationState.value = CalibrationWizardState(
            step = CalibrationFlowStep.FIT_5_POINTS,
            currentTargetIndex = 0,
            currentTargetProgress = 0f,
            isSettlingDelay = true,
            draftProfileName = "$modelName Profile"
        )
    }

    fun cancelCalibrationWizard() {
        _calibrationState.value = CalibrationWizardState(step = CalibrationFlowStep.IDLE)
    }

    fun updateDraftProfileName(name: String) {
        _calibrationState.update { it.copy(draftProfileName = name) }
    }

    private fun advanceCalibrationIfActive(feature: GazeFeatureVector, nowMs: Long) {
        val state = _calibrationState.value
        if (state.step != CalibrationFlowStep.FIT_5_POINTS && state.step != CalibrationFlowStep.VALIDATE_4_POINTS) {
            return
        }

        val allTargets = GazeCalibrationMath.CALIBRATION_AND_VALIDATION_TARGETS
        val target = allTargets.getOrNull(state.currentTargetIndex) ?: return

        val elapsedSinceTargetStart = nowMs - targetStartTimeMs
        if (elapsedSinceTargetStart < SETTLING_DELAY_MS) {
            _calibrationState.update { it.copy(isSettlingDelay = true, currentTargetProgress = 0f) }
            return
        }

        val samplePair = Pair(feature, Pair(target.normX, target.normY))
        if (!target.isValidationPoint) {
            fitSamples.add(samplePair)
        } else {
            validationSamples.add(samplePair)
        }
        samplesForCurrentTarget++

        val progress = (samplesForCurrentTarget.toFloat() / SAMPLES_PER_TARGET.toFloat()).coerceIn(0f, 1f)
        _calibrationState.update {
            it.copy(isSettlingDelay = false, currentTargetProgress = progress)
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
                    activeMatrix = solvedMatrix
                    activeBaselineEyeDistPx = avgEyeDist
                    _calibrationState.update {
                        it.copy(
                            step = CalibrationFlowStep.VALIDATE_4_POINTS,
                            currentTargetIndex = 5,
                            currentTargetProgress = 0f,
                            isSettlingDelay = true,
                            fittedMatrix = solvedMatrix,
                            capturedBaselineEyeDistPx = avgEyeDist
                        )
                    }
                }
                nextIndex < allTargets.size -> {
                    _calibrationState.update {
                        it.copy(
                            currentTargetIndex = nextIndex,
                            currentTargetProgress = 0f,
                            isSettlingDelay = true
                        )
                    }
                }
                else -> {
                    val fitted = _calibrationState.value.fittedMatrix
                    val baseEyeDist = _calibrationState.value.capturedBaselineEyeDistPx
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
                            validationMeanErrorMm = valResult.approxMeanErrorMm,
                            validationMeanErrorPct = valResult.meanErrorPct,
                            validationMaxErrorPct = valResult.maxErrorPct,
                            recommendedGridLabel = valResult.recommendedGridDesc
                        )
                    }
                    _liveHudState.update {
                        it.copy(
                            activeErrorRadiusPct = valResult.meanErrorPct.toFloat(),
                            activeMaxQuadrantErrorPct = valResult.maxErrorPct.toFloat(),
                            activeErrorMm = valResult.approxMeanErrorMm
                        )
                    }
                }
            }
        }
    }

    fun saveCompletedCalibrationProfile() {
        val cal = _calibrationState.value
        val hud = _liveHudState.value
        val deviceName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Pixel 10 Pro XL"
        val profile = CalibrationProfile(
            userId = currentUserId,
            name = cal.draftProfileName.ifBlank { "$deviceName Profile" },
            deviceModel = deviceName,
            matrixCoeffs = cal.fittedMatrix,
            baselineEyeDistancePx = cal.capturedBaselineEyeDistPx,
            meanErrorMm = cal.validationMeanErrorMm,
            meanErrorPct = cal.validationMeanErrorPct,
            maxErrorPct = cal.validationMaxErrorPct,
            closeBlinkThreshold = blinkDetector.closeThreshold.toDouble(),
            openBlinkThreshold = blinkDetector.openThreshold.toDouble(),
            intentionalBlinkMinMs = blinkDetector.intentionalMinMs,
            isActive = true
        )

        applyProfileToEngine(profile)
        // Always persist to on-device LocalTelemetryStore so sync-off users retain calibration across restarts
        localStore?.saveLocalProfile(currentUserId, profile)
        _localProfilesFlow.value = localStore?.loadLocalProfiles(currentUserId).orEmpty()

        _calibrationState.value = CalibrationWizardState(step = CalibrationFlowStep.IDLE)

        if (!hud.cloudSyncEnabled) {
            _statusBannerMessage.value = "Calibration saved to on-device storage (Cloud Sync is paused)."
            return
        }

        viewModelScope.launch {
            val res = repository.createCalibrationProfile(profile)
            if (res.isSuccess) {
                _statusBannerMessage.value = "Calibration profile synced to Cloud Firestore & local vault."
            } else {
                _statusBannerMessage.value = "Saved locally; Cloud sync error: ${res.exceptionOrNull()?.localizedMessage}"
            }
        }
    }

    fun activateSavedProfile(profile: CalibrationProfile) {
        applyProfileToEngine(profile)
        localStore?.setActiveLocalProfile(currentUserId, profile.id)
        _localProfilesFlow.value = localStore?.loadLocalProfiles(currentUserId).orEmpty()

        if (_liveHudState.value.cloudSyncEnabled && profile.id.isNotBlank() && !profile.id.startsWith("local_")) {
            viewModelScope.launch {
                repository.updateCalibrationActiveState(profile.id, true)
            }
        }
        _statusBannerMessage.value = "Activated profile: ${profile.name} (${String.format("%.1f", profile.meanErrorPct)}% mean • ${String.format("%.1f", profile.maxErrorPct)}% max)"
    }

    private fun applyProfileToEngine(profile: CalibrationProfile) {
        if (profile.matrixCoeffs.size == 12) {
            activeMatrix = profile.matrixCoeffs
        }
        activeBaselineEyeDistPx = profile.baselineEyeDistancePx
        blinkDetector.closeThreshold = profile.closeBlinkThreshold.toFloat()
        blinkDetector.openThreshold = profile.openBlinkThreshold.toFloat()
        blinkDetector.intentionalMinMs = profile.intentionalBlinkMinMs

        _liveHudState.update {
            it.copy(
                activeProfileName = profile.name,
                activeProfileDeviceModel = profile.deviceModel,
                activeErrorRadiusPct = profile.meanErrorPct.toFloat(),
                activeMaxQuadrantErrorPct = profile.maxErrorPct.toFloat(),
                activeErrorMm = profile.meanErrorMm
            )
        }
    }

    fun deleteCalibrationProfile(profileId: String) {
        localStore?.deleteLocalProfile(currentUserId, profileId)
        _localProfilesFlow.value = localStore?.loadLocalProfiles(currentUserId).orEmpty()
        if (!profileId.startsWith("local_")) {
            viewModelScope.launch {
                val res = repository.deleteCalibrationProfile(profileId)
                if (res.isSuccess) {
                    _statusBannerMessage.value = "Calibration profile deleted."
                }
            }
        } else {
            _statusBannerMessage.value = "Local calibration profile deleted."
        }
    }

    // --- Section Attention Heatmap & Reading Analytics Lab ---

    fun startAttentionRecording() {
        rawSessionGazeSamples.clear()
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
                val currentFps = _liveHudState.value.analyzerFps
                _attentionState.update { st ->
                    val nextElapsed = st.elapsedSeconds + 1
                    val nextTimeline = (st.fpsTimeline + currentFps).takeLast(120)
                    // Rolling 60-second baseline vs recent window: if FPS drops >40% from first-minute baseline,
                    // flag thermal throttling and auto-enable Performance Mode
                    if (nextElapsed <= 60 && nextTimeline.isNotEmpty()) {
                        sessionBaselineFps = nextTimeline.average().coerceAtLeast(10.0)
                    }
                    val recentAvg = nextTimeline.takeLast(10).average()
                    val throttled = st.thermalThrottleDetected || (nextElapsed > 15 && recentAvg < sessionBaselineFps * 0.60)
                    st.copy(
                        elapsedSeconds = nextElapsed,
                        fpsTimeline = nextTimeline,
                        thermalThrottleDetected = throttled
                    )
                }
            }
        }
    }

    private fun recordAttentionSampleIfActive(normX: Float, normY: Float, nowMs: Long) {
        val state = _attentionState.value
        if (!state.isRecording) return

        val dt = (nowMs - lastAttentionSampleMs).coerceIn(16L, 250L)
        lastAttentionSampleMs = nowMs
        rawSessionGazeSamples.add(Triple(normX, normY, nowMs))

        val topAdd = if (normY < 0.34f) dt else 0L
        val midAdd = if (normY in 0.34f..0.67f) dt else 0L
        val botAdd = if (normY > 0.67f) dt else 0L

        // Prefer layout-measured section bounding box hit-test (refreshed on scroll/layout),
        // with fallback to normalized vertical bands when layout bounds are not yet attached
        val hitSectionId = layoutHitSectionId ?: when {
            normY < 0.36f -> "sec_1"
            normY < 0.68f -> "sec_2"
            else -> "sec_3"
        }

        val updatedMap = state.sectionDwellMsMap.toMutableMap()
        updatedMap[hitSectionId] = (updatedMap[hitSectionId] ?: 0L) + dt

        // Recompute live fixation clusters periodically so the Reading Lab heatmap renders in real time during recording
        val liveClusters = if (rawSessionGazeSamples.size % 4 == 0 || state.computedClusters.isEmpty()) {
            GazeCalibrationMath.clusterFixations(rawSessionGazeSamples.takeLast(240))
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
        _attentionState.update { it.copy(selectedReplaySession = null, computedClusters = emptyList()) }
    }

    fun stopAndSaveAttentionSession(customTitle: String = "Section Reading Telemetry") {
        sessionTimerJob?.cancel()
        val state = _attentionState.value
        if (!state.isRecording) return

        val clusters = GazeCalibrationMath.clusterFixations(rawSessionGazeSamples)
        val totalZoneMs = (state.topZoneMs + state.middleZoneMs + state.bottomZoneMs).coerceAtLeast(1L)
        val topPct = ((state.topZoneMs * 100L) / totalZoneMs).toInt().coerceIn(0, 100)
        val midPct = ((state.middleZoneMs * 100L) / totalZoneMs).toInt().coerceIn(0, 100)
        val botPct = (100 - topPct - midPct).coerceIn(0, 100)

        val durationSec = state.elapsedSeconds.coerceAtLeast(1)
        val totalWords = readingPassageSections.sumOf { it.wordCount }
        val effectiveWpm = ((totalWords * 60.0) / durationSec.toDouble()).roundToInt().coerceIn(10, 900)
        val bpm = (state.sessionNaturalBlinks * 60.0) / durationSec.toDouble()

        val totalSecMs = state.sectionDwellMsMap.values.sum().coerceAtLeast(1L)
        val sectionLoadsEncoded = readingPassageSections.map { sec ->
            val ms = state.sectionDwellMsMap[sec.sectionId] ?: 0L
            val pct = ((ms * 100L) / totalSecMs).toInt()
            "${sec.sectionId}:${sec.heading.take(40)}:$pct"
        }

        val clusterPointsEncoded = clusters.take(40).map { c ->
            String.format(java.util.Locale.US, "%.3f,%.3f,%d", c.normX, c.normY, c.dwellMs)
        }

        val sessionModel = TrackingSession(
            userId = currentUserId,
            title = customTitle,
            deviceModel = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Pixel 10 Pro XL",
            contentType = "READING_SECTIONS",
            durationSeconds = durationSec,
            naturalBlinkCount = state.sessionNaturalBlinks,
            intentionalBlinkCount = state.sessionIntentionalBlinks,
            blinksPerMinute = bpm,
            effectiveWordsPerMinute = effectiveWpm,
            fixationClusterCount = clusters.size,
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
                selectedReplaySession = sessionModel
            )
        }

        localStore?.saveLocalSession(currentUserId, sessionModel)
        _localSessionsFlow.value = localStore?.loadLocalSessions(currentUserId).orEmpty()

        if (!_liveHudState.value.cloudSyncEnabled) {
            _statusBannerMessage.value = "Session saved to on-device storage (Cloud Sync is paused)."
            return
        }

        viewModelScope.launch {
            val res = repository.createTrackingSession(sessionModel)
            if (res.isSuccess) {
                _statusBannerMessage.value = "Attention session synced to Cloud Firestore & local vault."
            } else {
                _statusBannerMessage.value = "Saved locally; Cloud sync error: ${res.exceptionOrNull()?.localizedMessage}"
            }
        }
    }

    fun selectSessionForReplay(session: TrackingSession) {
        val parsedClusters = session.clusterPoints.mapNotNull { encoded ->
            val parts = encoded.split(",")
            if (parts.size == 3) {
                val x = parts[0].toFloatOrNull()
                val y = parts[1].toFloatOrNull()
                val ms = parts[2].toLongOrNull()
                if (x != null && y != null && ms != null) FixationClusterPoint(x, y, ms) else null
            } else null
        }
        _attentionState.update {
            it.copy(
                isRecording = false,
                computedClusters = parsedClusters,
                selectedReplaySession = session
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
                val pct = parts.getOrNull(2)?.toIntOrNull() ?: 33
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
        localStore?.deleteLocalSession(currentUserId, sessionId)
        _localSessionsFlow.value = localStore?.loadLocalSessions(currentUserId).orEmpty()
        if (!sessionId.startsWith("local_")) {
            viewModelScope.launch {
                val res = repository.deleteTrackingSession(sessionId)
                if (res.isSuccess) {
                    _statusBannerMessage.value = "Session deleted."
                }
            }
        } else {
            _statusBannerMessage.value = "Local session deleted."
        }
    }

    fun eraseAllCloudTelemetry() {
        localStore?.clearAllLocalData(currentUserId)
        _localProfilesFlow.value = emptyList()
        _localSessionsFlow.value = emptyList()
        viewModelScope.launch {
            val res = repository.eraseAllUserCloudTelemetry()
            if (res.isSuccess) {
                val count = res.getOrDefault(0)
                _statusBannerMessage.value = "Erased all local & cloud biometric records ($count cloud docs removed)."
            } else {
                Log.w(TAG, "Failed to erase cloud telemetry", res.exceptionOrNull())
                _statusBannerMessage.value = "Cleared local vault; cloud erase error: ${res.exceptionOrNull()?.localizedMessage}"
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        sessionTimerJob?.cancel()
        simulatorTickerJob?.cancel()
    }

    private companion object {
        const val TAG = "EyeTrackingVM"
        const val STOP_TIMEOUT_MILLIS = 5000L
        const val SETTLING_DELAY_MS = 400L
        const val SAMPLES_PER_TARGET = 18
    }
}
