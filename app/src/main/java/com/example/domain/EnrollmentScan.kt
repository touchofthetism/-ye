package com.example.domain

import com.example.data.model.BlinkType
import com.example.data.model.EnrollmentBaseline
import com.example.data.model.GazeFeatureVector
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Phases of the per-user face & eye enrollment scan. POSITIONING..BROW_RAISE are the active phases
 * ([isActive]); IDLE, COMPLETE and FAILED are terminal for [EnrollmentScanEngine.onFrame].
 */
enum class EnrollmentPhase {
    IDLE, POSITIONING, EYES_OPEN, EYES_CLOSED, BROW_RAISE, COMPLETE, FAILED;

    /** True while the scan is consuming frames (POSITIONING, EYES_OPEN, EYES_CLOSED or BROW_RAISE). */
    val isActive: Boolean
        get() = this == POSITIONING || this == EYES_OPEN || this == EYES_CLOSED || this == BROW_RAISE
}

/** What the person should do right now; the UI renders one plain-English line per value. */
enum class EnrollmentGuidance {
    NO_FACE, CENTER_FACE, MOVE_CLOSER, MOVE_BACK, MORE_LIGHT, REDUCE_GLARE, HOLD_STILL,
    LOOK_AT_DOT, CLOSE_EYES, KEEP_EYES_CLOSED, OPEN_EYES, RAISE_BROWS, DONE, FAILED
}

/** Non-fatal conditions observed during a scan; stored by name in [EnrollmentBaseline.warnings]. */
enum class EnrollmentWarning { LOW_LIGHT, GLARE, SINGLE_EYE, FEW_NATURAL_BLINKS, EYE_RANGE_SMALL_DEFAULTS_USED, BROW_SKIPPED }

/** Why a scan ended in [EnrollmentPhase.FAILED]. */
enum class EnrollmentFailure { POSITIONING_TIMEOUT, FACE_LOST, EYES_DID_NOT_CLOSE, EYES_DID_NOT_REOPEN, SIMULATED_FRAMES, CANCELLED }

/**
 * Immutable snapshot of the scan, published after every [EnrollmentScanEngine.onFrame].
 * - phaseProgress: 0..1 within the current phase; overallProgress: 0..1 across POSITIONING..BROW_RAISE
 *   (each active phase is one quarter), 1 in COMPLETE and frozen at its last value in FAILED.
 * - liveEyeOpenProb: the last [GazeFeatureVector.combinedEyeOpenProb] seen on a face frame (0 while no face
 *   is detected) for the live openness bar; liveFaceCentered: the face box is inside the positioning window.
 * - result is non-null only in COMPLETE; failure is non-null only in FAILED.
 */
data class EnrollmentScanState(
    val phase: EnrollmentPhase = EnrollmentPhase.IDLE,
    val guidance: EnrollmentGuidance = EnrollmentGuidance.NO_FACE,
    val phaseProgress: Float = 0f,
    val overallProgress: Float = 0f,
    val elapsedMs: Long = 0L,
    val retries: Int = 0,
    val warnings: Set<EnrollmentWarning> = emptySet(),
    val liveEyeOpenProb: Float = 0f,
    val liveFaceCentered: Boolean = false,
    val result: EnrollmentBaseline? = null,
    val failure: EnrollmentFailure? = null
)

/**
 * Timing and acceptance windows of the scan. Face-box coordinates are the normalized 0..1 values of
 * [GazeFeatureVector.faceBoxLeft] etc.; the centre window is symmetric, so the front camera's mirroring
 * does not matter.
 */
data class EnrollmentConfig(
    val readyFramesRequired: Int = 12,
    val positioningTimeoutMs: Long = 20_000L,
    val eyesOpenCollectMs: Long = 2_500L,
    val eyesClosedWaitMs: Long = 4_000L,
    val eyesClosedCollectMs: Long = 1_200L,
    val reopenWaitMs: Long = 3_000L,
    val browCollectMs: Long = 1_500L,
    val browWaitMs: Long = 5_000L,
    val faceLossGraceMs: Long = 1_500L,
    val maxRetries: Int = 3,
    val minEyeConfidence: Float = 0.25f,
    val minEyeRange: Float = 0.15f,
    val faceCenterMinX: Float = 0.35f,
    val faceCenterMaxX: Float = 0.65f,
    val faceCenterMinY: Float = 0.28f,
    val faceCenterMaxY: Float = 0.72f,
    val faceWidthMin: Float = 0.22f,
    val faceWidthMax: Float = 0.78f
)

/**
 * Pure-Kotlin state machine of the ~15 s face & eye enrollment scan. It consumes the live
 * [GazeFeatureVector] stream (one call per frame, no-face frames included) and derives the person's
 * eyelid-openness levels, natural blink timings and brow ratios, then the personalized blink/brow
 * detector thresholds. It never stores frames or landmarks: only the numbers in [EnrollmentBaseline].
 *
 * Phases:
 * - POSITIONING: needs [EnrollmentConfig.readyFramesRequired] consecutive frames with a detected face,
 *   both eye confidences >= minEyeConfidence, binocular tracking, the face-box centre inside the centre
 *   window and the face-box width inside [faceWidthMin, faceWidthMax]. Guidance names the first unmet
 *   condition. Low light / glare block only for the first [LIGHTING_OVERRIDE_MS] of otherwise-ready
 *   frames; after that they are recorded as warnings instead. Times out with POSITIONING_TIMEOUT.
 * - EYES_OPEN (LOOK_AT_DOT): collects eyesOpenCollectMs of face frames. openLevel = median
 *   combinedEyeOpenProb over frames with prob >= 0.5 (blink frames excluded), likewise the per-eye open
 *   medians; baselineEyeDistancePx, neutralBrowRatio = medians over all face frames; mean ambient luma and
 *   eye confidence. A provisional BlinkHysteresisDetector(0.45, 0.65) over the same frames yields the
 *   natural blink durations: every closure it classifies as NATURAL or INTENTIONAL_COMMAND (the person was
 *   not asked to blink, so these are spontaneous); EXTENDED_REST closures are not blinks.
 * - EYES_CLOSED: CLOSE_EYES until prob < 0.5 * openLevel (at most eyesClosedWaitMs, else
 *   EYES_DID_NOT_CLOSE), KEEP_EYES_CLOSED for eyesClosedCollectMs (closedLevel = median), then OPEN_EYES
 *   until prob > closed + 0.6 * (open - closed) (at most reopenWaitMs, else EYES_DID_NOT_REOPEN).
 * - BROW_RAISE (RAISE_BROWS): waits up to browWaitMs for browToEyeRatio > neutral + 0.02, collects
 *   browCollectMs (raisedBrowRatio = 75th percentile). A timeout or [skipBrowStep] completes the scan with
 *   raisedBrowRatio = null and the BROW_SKIPPED warning; it is not a failure.
 * - Face loss during EYES_OPEN..BROW_RAISE pauses the phase clock (guidance NO_FACE); once it lasts
 *   [EnrollmentConfig.faceLossGraceMs] the phase restarts from scratch (retries + 1), and the retry after
 *   [EnrollmentConfig.maxRetries] fails with FACE_LOST. Any frame with isSimulated fails with
 *   SIMULATED_FRAMES: the scan only ever measures the live camera.
 *
 * Thresholds at COMPLETE: range = open - closed. When range >= minEyeRange, close = closed + 0.35 * range
 * and open = closed + 0.65 * range; otherwise the app defaults 0.45 / 0.65 plus
 * EYE_RANGE_SMALL_DEFAULTS_USED. Clamped to close in 0.05..0.80 and open in (close + 0.05)..0.98.
 * intentionalBlinkMinMs = clamp(max(350, p95 + 80), 350, 600) when at least two natural blinks were seen,
 * else 350 plus FEW_NATURAL_BLINKS.
 *
 * All timing uses the nowMs passed to [onFrame], never the frame's own timestamp. Per-frame work does not
 * allocate beyond growing the primitive sample buffers (and the published snapshot); medians and
 * percentiles sort a copy of the buffer once, at the end of a phase.
 */
class EnrollmentScanEngine(
    val config: EnrollmentConfig = EnrollmentConfig(),
    private val deviceModel: String = ""
) {
    /** Current immutable snapshot; the same instance is returned until something changes it. */
    var state: EnrollmentScanState = EnrollmentScanState()
        private set

    private enum class Step { WAIT, COLLECT, WAIT_REOPEN }

    private var phase = EnrollmentPhase.IDLE
    private var guidance = EnrollmentGuidance.NO_FACE
    private var startMs = 0L
    private var lastNowMs = 0L
    private var phaseStartMs = 0L // pause-adjusted: shifted forward by every face-loss pause
    private var stepStartMs = 0L // pause-adjusted start of the current sub-step (wait / collect)
    private var step = Step.WAIT
    private var faceLostAtMs = -1L
    private var retries = 0
    private var warningBits = 0
    private var warningsSnapshot: Set<EnrollmentWarning> = emptySet()
    private var phaseProgress = 0f
    private var frozenOverallProgress = 0f
    private var liveEyeOpenProb = 0f
    private var liveFaceCentered = false
    private var result: EnrollmentBaseline? = null
    private var failure: EnrollmentFailure? = null

    // POSITIONING
    private var readyFrames = 0
    private var otherwiseReadySinceMs = -1L

    // EYES_OPEN samples (parallel buffers, one entry per face frame) and the derived levels
    private val openCombined = SampleBuffer()
    private val openLeft = SampleBuffer()
    private val openRight = SampleBuffer()
    private val eyeDistance = SampleBuffer()
    private val browRatio = SampleBuffer()
    private val ambientLuma = SampleBuffer()
    private val eyeConfidence = SampleBuffer()
    private val blinkDurations = SampleBuffer(16)
    private val provisionalBlinkDetector = BlinkHysteresisDetector(PROVISIONAL_CLOSE, PROVISIONAL_OPEN)
    private var openLevel = 0f
    private var leftOpenLevel = 0f
    private var rightOpenLevel = 0f
    private var baselineEyeDistancePx = 0.0
    private var neutralBrowRatio = 0f
    private var meanAmbientLuma = 0
    private var meanEyeConfidence = 0f
    private var naturalBlinkCount = 0
    private var naturalBlinkMedianMs = 0L
    private var naturalBlinkP95Ms = 0L

    // EYES_CLOSED
    private val closedCombined = SampleBuffer()
    private var closedLevel = 0f

    // BROW_RAISE
    private val raisedBrow = SampleBuffer()
    private var raisedBrowRatio: Float? = null

    /** Starts (or restarts) a scan at [nowMs]: everything is cleared and the phase becomes POSITIONING. */
    fun start(nowMs: Long): EnrollmentScanState {
        clearAll()
        startMs = nowMs
        lastNowMs = nowMs
        enterPhase(EnrollmentPhase.POSITIONING, nowMs)
        return publish()
    }

    /**
     * Feeds one frame (face or no-face). A no-op that returns the current snapshot unless the phase is
     * active. A simulated frame fails the scan with SIMULATED_FRAMES.
     */
    fun onFrame(feature: GazeFeatureVector, nowMs: Long): EnrollmentScanState {
        if (!phase.isActive) return state
        lastNowMs = nowMs
        if (feature.isSimulated) {
            fail(EnrollmentFailure.SIMULATED_FRAMES)
            return publish()
        }
        liveEyeOpenProb = if (feature.faceDetected) feature.combinedEyeOpenProb else 0f
        liveFaceCentered = feature.faceDetected && isCentered(feature) && isWidthOk(feature)
        when (phase) {
            EnrollmentPhase.POSITIONING -> onPositioningFrame(feature, nowMs)
            EnrollmentPhase.EYES_OPEN,
            EnrollmentPhase.EYES_CLOSED,
            EnrollmentPhase.BROW_RAISE -> onCollectionFrame(feature, nowMs)
            else -> Unit
        }
        return publish()
    }

    /** In BROW_RAISE: completes the scan without a raised-brow measurement (BROW_SKIPPED). Otherwise a no-op. */
    fun skipBrowStep(): EnrollmentScanState {
        if (phase != EnrollmentPhase.BROW_RAISE) return state
        raisedBrow.clear()
        raisedBrowRatio = null
        addWarning(EnrollmentWarning.BROW_SKIPPED)
        complete(lastNowMs)
        return publish()
    }

    /** From any active phase: FAILED(CANCELLED). IDLE, COMPLETE and FAILED are left unchanged. */
    fun cancel(): EnrollmentScanState {
        if (!phase.isActive) return state
        fail(EnrollmentFailure.CANCELLED)
        return publish()
    }

    /** Back to IDLE with the default snapshot; all samples are discarded. */
    fun reset() {
        clearAll()
        state = EnrollmentScanState()
    }

    // ---------------------------------------------------------------- POSITIONING

    private fun onPositioningFrame(f: GazeFeatureVector, nowMs: Long) {
        val faceOk = f.faceDetected
        val centered = faceOk && isCentered(f)
        val width = f.faceBoxRight - f.faceBoxLeft
        val widthOk = faceOk && isWidthOk(f)
        val eyesOk = faceOk && !f.isSingleEyeMode &&
            f.leftEyeConfidence >= config.minEyeConfidence && f.rightEyeConfidence >= config.minEyeConfidence
        val otherwiseReady = faceOk && centered && widthOk && eyesOk
        if (otherwiseReady) {
            if (otherwiseReadySinceMs < 0L) otherwiseReadySinceMs = nowMs
        } else {
            otherwiseReadySinceMs = -1L
        }
        val lightingBlocks = f.isLowLightDegraded || f.hasGlareWarning
        val lightingOverride = lightingBlocks && otherwiseReady && nowMs - otherwiseReadySinceMs >= LIGHTING_OVERRIDE_MS
        if (otherwiseReady && (!lightingBlocks || lightingOverride)) {
            readyFrames++
            if (lightingOverride) {
                if (f.isLowLightDegraded) addWarning(EnrollmentWarning.LOW_LIGHT)
                if (f.hasGlareWarning) addWarning(EnrollmentWarning.GLARE)
            }
            guidance = EnrollmentGuidance.HOLD_STILL
        } else {
            readyFrames = 0
            guidance = when {
                !faceOk -> EnrollmentGuidance.NO_FACE
                !centered -> EnrollmentGuidance.CENTER_FACE
                width < config.faceWidthMin -> EnrollmentGuidance.MOVE_CLOSER
                width > config.faceWidthMax -> EnrollmentGuidance.MOVE_BACK
                f.isLowLightDegraded -> EnrollmentGuidance.MORE_LIGHT
                f.hasGlareWarning -> EnrollmentGuidance.REDUCE_GLARE
                else -> EnrollmentGuidance.HOLD_STILL // eyes not measured well enough yet
            }
        }
        phaseProgress = if (config.readyFramesRequired <= 0) 1f
        else (readyFrames.toFloat() / config.readyFramesRequired.toFloat()).coerceIn(0f, 1f)
        if (readyFrames >= config.readyFramesRequired) {
            enterPhase(EnrollmentPhase.EYES_OPEN, nowMs)
        } else if (nowMs - phaseStartMs >= config.positioningTimeoutMs) {
            fail(EnrollmentFailure.POSITIONING_TIMEOUT)
        }
    }

    private fun isCentered(f: GazeFeatureVector): Boolean {
        val cx = (f.faceBoxLeft + f.faceBoxRight) * 0.5f
        val cy = (f.faceBoxTop + f.faceBoxBottom) * 0.5f
        return cx >= config.faceCenterMinX && cx <= config.faceCenterMaxX &&
            cy >= config.faceCenterMinY && cy <= config.faceCenterMaxY
    }

    private fun isWidthOk(f: GazeFeatureVector): Boolean {
        val width = f.faceBoxRight - f.faceBoxLeft
        return width >= config.faceWidthMin && width <= config.faceWidthMax
    }

    // ---------------------------------------------------------------- EYES_OPEN / EYES_CLOSED / BROW_RAISE

    private fun onCollectionFrame(f: GazeFeatureVector, nowMs: Long) {
        if (!f.faceDetected) {
            if (faceLostAtMs < 0L) {
                faceLostAtMs = nowMs
                // A closure spanning the outage is not a measured blink.
                provisionalBlinkDetector.reset()
            } else if (nowMs - faceLostAtMs >= config.faceLossGraceMs) {
                restartPhaseAfterFaceLoss(nowMs)
            }
            guidance = EnrollmentGuidance.NO_FACE
            return
        }
        if (faceLostAtMs >= 0L) {
            // Resume: the pause does not count towards any window.
            val pauseMs = (nowMs - faceLostAtMs).coerceAtLeast(0L)
            phaseStartMs += pauseMs
            stepStartMs += pauseMs
            faceLostAtMs = -1L
        }
        if (f.isSingleEyeMode) addWarning(EnrollmentWarning.SINGLE_EYE)
        if (f.isLowLightDegraded) addWarning(EnrollmentWarning.LOW_LIGHT)
        if (f.hasGlareWarning) addWarning(EnrollmentWarning.GLARE)
        when (phase) {
            EnrollmentPhase.EYES_OPEN -> onEyesOpenFrame(f, nowMs)
            EnrollmentPhase.EYES_CLOSED -> onEyesClosedFrame(f, nowMs)
            EnrollmentPhase.BROW_RAISE -> onBrowFrame(f, nowMs)
            else -> Unit
        }
    }

    private fun restartPhaseAfterFaceLoss(nowMs: Long) {
        if (retries >= config.maxRetries) {
            fail(EnrollmentFailure.FACE_LOST)
            return
        }
        retries++
        enterPhase(phase, nowMs)
        // The face is still missing: the next grace window starts now.
        faceLostAtMs = nowMs
    }

    private fun onEyesOpenFrame(f: GazeFeatureVector, nowMs: Long) {
        guidance = EnrollmentGuidance.LOOK_AT_DOT
        val prob = f.combinedEyeOpenProb
        openCombined.add(prob.toDouble())
        openLeft.add(f.leftEyeOpenProb.toDouble())
        openRight.add(f.rightEyeOpenProb.toDouble())
        eyeDistance.add(f.eyeDistancePx)
        browRatio.add(f.browToEyeRatio.toDouble())
        ambientLuma.add(f.ambientLuma.toDouble())
        eyeConfidence.add((f.leftEyeConfidence + f.rightEyeConfidence).toDouble() * 0.5)
        val event = provisionalBlinkDetector.update(prob, nowMs).second
        if (event != null && event.type != BlinkType.EXTENDED_REST) {
            blinkDurations.add(event.durationMs.toDouble())
        }
        val elapsed = nowMs - phaseStartMs
        phaseProgress = fraction(elapsed, config.eyesOpenCollectMs)
        if (elapsed >= config.eyesOpenCollectMs) {
            finishEyesOpen()
            enterPhase(EnrollmentPhase.EYES_CLOSED, nowMs)
        }
    }

    private fun finishEyesOpen() {
        openLevel = openCombined.medianWhereAtLeast(openCombined, OPEN_FRAME_MIN_PROB).toFloat()
        leftOpenLevel = openLeft.medianWhereAtLeast(openCombined, OPEN_FRAME_MIN_PROB).toFloat()
        rightOpenLevel = openRight.medianWhereAtLeast(openCombined, OPEN_FRAME_MIN_PROB).toFloat()
        baselineEyeDistancePx = eyeDistance.median()
        neutralBrowRatio = browRatio.median().toFloat()
        meanAmbientLuma = ambientLuma.mean().roundToInt()
        meanEyeConfidence = eyeConfidence.mean().toFloat()
        naturalBlinkCount = blinkDurations.size
        naturalBlinkMedianMs = blinkDurations.median().roundToLong()
        naturalBlinkP95Ms = blinkDurations.percentile(0.95).roundToLong()
    }

    private fun onEyesClosedFrame(f: GazeFeatureVector, nowMs: Long) {
        val prob = f.combinedEyeOpenProb
        when (step) {
            Step.WAIT -> {
                if (prob < 0.5f * openLevel) {
                    step = Step.COLLECT
                    stepStartMs = nowMs
                    closedCombined.add(prob.toDouble())
                    guidance = EnrollmentGuidance.KEEP_EYES_CLOSED
                    phaseProgress = CLOSED_WAIT_SHARE
                } else {
                    guidance = EnrollmentGuidance.CLOSE_EYES
                    val waited = nowMs - stepStartMs
                    phaseProgress = CLOSED_WAIT_SHARE * fraction(waited, config.eyesClosedWaitMs)
                    if (waited >= config.eyesClosedWaitMs) fail(EnrollmentFailure.EYES_DID_NOT_CLOSE)
                }
            }
            Step.COLLECT -> {
                guidance = EnrollmentGuidance.KEEP_EYES_CLOSED
                closedCombined.add(prob.toDouble())
                val collected = nowMs - stepStartMs
                phaseProgress = CLOSED_WAIT_SHARE + CLOSED_COLLECT_SHARE * fraction(collected, config.eyesClosedCollectMs)
                if (collected >= config.eyesClosedCollectMs) {
                    closedLevel = closedCombined.median().toFloat()
                    step = Step.WAIT_REOPEN
                    stepStartMs = nowMs
                    guidance = EnrollmentGuidance.OPEN_EYES
                }
            }
            Step.WAIT_REOPEN -> {
                guidance = EnrollmentGuidance.OPEN_EYES
                val reopenThreshold = closedLevel + 0.6f * (openLevel - closedLevel)
                if (prob > reopenThreshold) {
                    enterPhase(EnrollmentPhase.BROW_RAISE, nowMs)
                } else {
                    val waited = nowMs - stepStartMs
                    phaseProgress = CLOSED_WAIT_SHARE + CLOSED_COLLECT_SHARE +
                        (1f - CLOSED_WAIT_SHARE - CLOSED_COLLECT_SHARE) * fraction(waited, config.reopenWaitMs)
                    if (waited >= config.reopenWaitMs) fail(EnrollmentFailure.EYES_DID_NOT_REOPEN)
                }
            }
        }
    }

    private fun onBrowFrame(f: GazeFeatureVector, nowMs: Long) {
        guidance = EnrollmentGuidance.RAISE_BROWS
        val ratio = f.browToEyeRatio
        when (step) {
            Step.WAIT -> {
                if (ratio > neutralBrowRatio + BROW_RAISE_START_DELTA) {
                    step = Step.COLLECT
                    stepStartMs = nowMs
                    raisedBrow.add(ratio.toDouble())
                    phaseProgress = BROW_WAIT_SHARE
                } else {
                    val waited = nowMs - stepStartMs
                    phaseProgress = BROW_WAIT_SHARE * fraction(waited, config.browWaitMs)
                    if (waited >= config.browWaitMs) {
                        raisedBrow.clear()
                        raisedBrowRatio = null
                        addWarning(EnrollmentWarning.BROW_SKIPPED)
                        complete(nowMs)
                    }
                }
            }
            Step.COLLECT -> {
                raisedBrow.add(ratio.toDouble())
                val collected = nowMs - stepStartMs
                phaseProgress = BROW_WAIT_SHARE + (1f - BROW_WAIT_SHARE) * fraction(collected, config.browCollectMs)
                if (collected >= config.browCollectMs) {
                    raisedBrowRatio = raisedBrow.percentile(0.75).toFloat()
                    complete(nowMs)
                }
            }
            Step.WAIT_REOPEN -> Unit // not used by this phase
        }
    }

    // ---------------------------------------------------------------- transitions

    private fun enterPhase(next: EnrollmentPhase, nowMs: Long) {
        phase = next
        phaseStartMs = nowMs
        stepStartMs = nowMs
        step = Step.WAIT
        faceLostAtMs = -1L
        phaseProgress = 0f
        when (next) {
            EnrollmentPhase.POSITIONING -> {
                readyFrames = 0
                otherwiseReadySinceMs = -1L
                guidance = EnrollmentGuidance.NO_FACE
            }
            EnrollmentPhase.EYES_OPEN -> {
                openCombined.clear(); openLeft.clear(); openRight.clear()
                eyeDistance.clear(); browRatio.clear(); ambientLuma.clear(); eyeConfidence.clear()
                blinkDurations.clear()
                provisionalBlinkDetector.reset()
                guidance = EnrollmentGuidance.LOOK_AT_DOT
            }
            EnrollmentPhase.EYES_CLOSED -> {
                closedCombined.clear()
                guidance = EnrollmentGuidance.CLOSE_EYES
            }
            EnrollmentPhase.BROW_RAISE -> {
                raisedBrow.clear()
                raisedBrowRatio = null
                guidance = EnrollmentGuidance.RAISE_BROWS
            }
            else -> Unit
        }
    }

    private fun complete(nowMs: Long) {
        val range = openLevel - closedLevel
        var close: Float
        var open: Float
        if (range >= config.minEyeRange) {
            close = closedLevel + 0.35f * range
            open = closedLevel + 0.65f * range
        } else {
            close = DEFAULT_CLOSE_THRESHOLD
            open = DEFAULT_OPEN_THRESHOLD
            addWarning(EnrollmentWarning.EYE_RANGE_SMALL_DEFAULTS_USED)
        }
        close = close.coerceIn(MIN_CLOSE_THRESHOLD, MAX_CLOSE_THRESHOLD)
        open = open.coerceIn(close + MIN_THRESHOLD_GAP, MAX_OPEN_THRESHOLD)
        val intentionalMinMs = if (naturalBlinkCount >= 2) {
            max(DEFAULT_INTENTIONAL_MIN_MS, naturalBlinkP95Ms + 80L).coerceIn(DEFAULT_INTENTIONAL_MIN_MS, MAX_INTENTIONAL_MIN_MS)
        } else {
            addWarning(EnrollmentWarning.FEW_NATURAL_BLINKS)
            DEFAULT_INTENTIONAL_MIN_MS
        }
        result = EnrollmentBaseline(
            version = 1,
            capturedAtMs = nowMs,
            deviceModel = deviceModel,
            openEyeLevel = openLevel,
            closedEyeLevel = closedLevel,
            leftOpenLevel = leftOpenLevel,
            rightOpenLevel = rightOpenLevel,
            baselineEyeDistancePx = baselineEyeDistancePx,
            neutralBrowRatio = neutralBrowRatio,
            raisedBrowRatio = raisedBrowRatio,
            naturalBlinkCount = naturalBlinkCount,
            naturalBlinkMedianMs = naturalBlinkMedianMs,
            naturalBlinkP95Ms = naturalBlinkP95Ms,
            closeBlinkThreshold = close,
            openBlinkThreshold = open,
            intentionalBlinkMinMs = intentionalMinMs,
            meanAmbientLuma = meanAmbientLuma,
            meanEyeConfidence = meanEyeConfidence,
            frameCount = openCombined.size + closedCombined.size + raisedBrow.size,
            warnings = warningsSnapshot.map { it.name }
        )
        failure = null
        phase = EnrollmentPhase.COMPLETE
        guidance = EnrollmentGuidance.DONE
        phaseProgress = 1f
        faceLostAtMs = -1L
    }

    private fun fail(reason: EnrollmentFailure) {
        frozenOverallProgress = overallProgressFor(phase)
        phase = EnrollmentPhase.FAILED
        failure = reason
        result = null
        guidance = EnrollmentGuidance.FAILED
        faceLostAtMs = -1L
    }

    private fun clearAll() {
        phase = EnrollmentPhase.IDLE
        guidance = EnrollmentGuidance.NO_FACE
        startMs = 0L
        lastNowMs = 0L
        phaseStartMs = 0L
        stepStartMs = 0L
        step = Step.WAIT
        faceLostAtMs = -1L
        retries = 0
        warningBits = 0
        warningsSnapshot = emptySet()
        phaseProgress = 0f
        frozenOverallProgress = 0f
        liveEyeOpenProb = 0f
        liveFaceCentered = false
        result = null
        failure = null
        readyFrames = 0
        otherwiseReadySinceMs = -1L
        openCombined.clear(); openLeft.clear(); openRight.clear()
        eyeDistance.clear(); browRatio.clear(); ambientLuma.clear(); eyeConfidence.clear()
        blinkDurations.clear()
        provisionalBlinkDetector.reset()
        openLevel = 0f; leftOpenLevel = 0f; rightOpenLevel = 0f
        baselineEyeDistancePx = 0.0
        neutralBrowRatio = 0f
        meanAmbientLuma = 0
        meanEyeConfidence = 0f
        naturalBlinkCount = 0; naturalBlinkMedianMs = 0L; naturalBlinkP95Ms = 0L
        closedCombined.clear()
        closedLevel = 0f
        raisedBrow.clear()
        raisedBrowRatio = null
    }

    private fun addWarning(warning: EnrollmentWarning) {
        val bit = 1 shl warning.ordinal
        if (warningBits and bit != 0) return
        warningBits = warningBits or bit
        // Ordinal order, so the stored names and the UI chips are stable across runs.
        warningsSnapshot = EnrollmentWarning.entries.filterTo(LinkedHashSet()) { warningBits and (1 shl it.ordinal) != 0 }
    }

    private fun overallProgressFor(p: EnrollmentPhase): Float = when (p) {
        EnrollmentPhase.IDLE -> 0f
        EnrollmentPhase.POSITIONING -> phaseProgress * 0.25f
        EnrollmentPhase.EYES_OPEN -> 0.25f + phaseProgress * 0.25f
        EnrollmentPhase.EYES_CLOSED -> 0.50f + phaseProgress * 0.25f
        EnrollmentPhase.BROW_RAISE -> 0.75f + phaseProgress * 0.25f
        EnrollmentPhase.COMPLETE -> 1f
        EnrollmentPhase.FAILED -> frozenOverallProgress
    }

    private fun publish(): EnrollmentScanState {
        state = EnrollmentScanState(
            phase = phase,
            guidance = guidance,
            phaseProgress = phaseProgress,
            overallProgress = overallProgressFor(phase),
            elapsedMs = (lastNowMs - startMs).coerceAtLeast(0L),
            retries = retries,
            warnings = warningsSnapshot,
            liveEyeOpenProb = liveEyeOpenProb,
            liveFaceCentered = liveFaceCentered,
            result = result,
            failure = failure
        )
        return state
    }

    companion object {
        /** Low light / glare stop blocking POSITIONING after this much otherwise-ready time. */
        const val LIGHTING_OVERRIDE_MS = 6_000L

        /** Provisional blink thresholds used to time natural blinks during EYES_OPEN (the app defaults). */
        const val PROVISIONAL_CLOSE = 0.45f
        const val PROVISIONAL_OPEN = 0.65f

        /** Frames at or above this combinedEyeOpenProb count as open (blink frames are excluded from openLevel). */
        const val OPEN_FRAME_MIN_PROB = 0.5

        /** The brow step starts collecting once browToEyeRatio exceeds neutral by this much. */
        const val BROW_RAISE_START_DELTA = 0.02f

        const val DEFAULT_CLOSE_THRESHOLD = 0.45f
        const val DEFAULT_OPEN_THRESHOLD = 0.65f
        const val MIN_CLOSE_THRESHOLD = 0.05f
        const val MAX_CLOSE_THRESHOLD = 0.80f
        const val MIN_THRESHOLD_GAP = 0.05f
        const val MAX_OPEN_THRESHOLD = 0.98f
        const val DEFAULT_INTENTIONAL_MIN_MS = 350L
        const val MAX_INTENTIONAL_MIN_MS = 600L

        // Shares of phaseProgress given to each sub-step of EYES_CLOSED and BROW_RAISE.
        private const val CLOSED_WAIT_SHARE = 0.25f
        private const val CLOSED_COLLECT_SHARE = 0.50f
        private const val BROW_WAIT_SHARE = 0.40f

        private fun fraction(elapsedMs: Long, totalMs: Long): Float =
            if (totalMs <= 0L) 1f else (elapsedMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
    }
}

/**
 * Growable primitive sample buffer with order statistics. [add] only allocates when the buffer grows;
 * [median] / [percentile] sort a copy once per call, so they are meant for the end of a phase.
 */
private class SampleBuffer(initialCapacity: Int = 128) {
    private var values = DoubleArray(initialCapacity.coerceAtLeast(1))
    var size = 0
        private set

    fun add(value: Double) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    fun clear() {
        size = 0
    }

    fun mean(): Double {
        if (size == 0) return 0.0
        var sum = 0.0
        for (i in 0 until size) sum += values[i]
        return sum / size
    }

    fun median(): Double = percentile(0.5)

    /** Linear-interpolated percentile (p in 0..1) of the samples; 0 when empty. */
    fun percentile(p: Double): Double {
        if (size == 0) return 0.0
        val sorted = values.copyOf(size)
        sorted.sort()
        return percentileOfSorted(sorted, p)
    }

    /**
     * Median of the samples whose parallel [mask] entry is >= [threshold] (same index). Falls back to the
     * plain median when no sample qualifies, so a phase never ends without a level.
     */
    fun medianWhereAtLeast(mask: SampleBuffer, threshold: Double): Double {
        val n = minOf(size, mask.size)
        var count = 0
        for (i in 0 until n) if (mask.values[i] >= threshold) count++
        if (count == 0) return median()
        val selected = DoubleArray(count)
        var k = 0
        for (i in 0 until n) if (mask.values[i] >= threshold) selected[k++] = values[i]
        selected.sort()
        return percentileOfSorted(selected, 0.5)
    }

    private fun percentileOfSorted(sorted: DoubleArray, p: Double): Double {
        val n = sorted.size
        if (n == 1) return sorted[0]
        val pos = (p.coerceIn(0.0, 1.0)) * (n - 1)
        val lo = pos.toInt().coerceIn(0, n - 1)
        val hi = (lo + 1).coerceAtMost(n - 1)
        val frac = pos - lo
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
    }
}
