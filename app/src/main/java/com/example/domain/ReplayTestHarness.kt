package com.example.domain

import com.example.data.model.BlinkType
import com.example.data.model.GazeFeatureVector
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Replay scenario classifications covering lighting, posture, facial expressions,
 * and system interruptions for repeatable QA validation without requiring live human presence.
 */
enum class ReplayScenario(val label: String, val category: String) {
    DIM_ROOM("Dim Room (< 40 Luma)", "LIGHTING"),
    WINDOW_BACKLIT("Window Backlit / High Contrast", "LIGHTING"),
    DIRECT_SUN_SPECULAR("Direct Sun / Specular Glare", "LIGHTING"),
    FLICKERING_LIGHT("Flickering Room Light (50/60 Hz)", "LIGHTING"),
    NEAR_DARK("Near Dark / Screen Glow Only", "LIGHTING"),

    GLASSES_SPECULAR_GLARE("Glasses with Lens Reflections", "USER"),
    SUNGLASSES_OCCLUSION("Sunglasses / High Eye Occlusion", "USER"),
    HEAD_TURNED_30_DEG("Head Turned > 30° Yaw/Pitch", "USER"),
    PARTIAL_FACE_OUT_OF_FRAME("Face Partial Crop / Border Truncation", "USER"),

    POSTURE_UPRIGHT("Posture: Sitting Upright (Desk / Stand)", "POSTURE"),
    POSTURE_RECLINING("Posture: Reclining (45° Tilt)", "POSTURE"),
    POSTURE_LYING_DOWN("Posture: Lying Down Flat (15° Tilt)", "POSTURE"),
    HANDHELD_TREMOR("Handheld Micro-Tremor & Walk Sway", "POSTURE"),

    NORMAL_INVOLUNTARY_BLINKS("Normal Involuntary Blinking (120-220 ms)", "MIDAS_TOUCH"),
    RAPID_FATIGUE_BLINKS("Rapid Fatigue Flurry (80-140 ms)", "MIDAS_TOUCH"),
    CONTINUOUS_TALKING("Continuous Talking / Speech Articulation", "MIDAS_TOUCH"),
    YAWNING_JAW_OPEN("Yawning / Extreme Jaw Open", "MIDAS_TOUCH"),
    LAUGHING_SMILE("Laughing / Extreme Cheek Squint", "MIDAS_TOUCH"),
    LOOKING_AWAY_10S("Looking Completely Away for 10s", "MIDAS_TOUCH"),
    EYES_CLOSED_REST_5S("Eyes Closed Prolonged Rest (5s)", "MIDAS_TOUCH"),

    INTENTIONAL_SELECTION_BLINK("Intentional Command Blink (450 ms)", "COMMAND"),
    INTENTIONAL_BROW_RAISE("Intentional Brow Raise Hold (420 ms)", "COMMAND"),

    NO_FACE_IN_FRAME("Empty Room / No Face Detected", "SCENE"),
    MULTIPLE_FACES_PRIMARY_LOCK("Two Faces / Primary User Area Lock", "SCENE"),

    // The enum name is kept for compatibility; the replayed segment is short (see BenchmarkRunner), not 60 min.
    ENDURANCE_60_MIN("Long-Session Mix: Saccades, Blinks & Holds (short replay segment)", "ENDURANCE")
}

/**
 * Result record from a replay scenario evaluation.
 *
 * @property falseActivations Blink-command or brow-raise triggers the scenario did not ask for: every
 *   trigger outside COMMAND scenarios, and any wrong-gesture or repeated trigger inside them.
 * @property validActivations Expected deliberate gesture captured in a COMMAND scenario (at most 1).
 * @property rmsJitterPx RMS displacement of the filtered gaze around its mean over the middle half of
 *   the emitted gaze points, in screen pixels per axis (x scaled by width, y by height). NaN when the
 *   pipeline emitted fewer than 10 gaze points, so no jitter could be measured.
 * @property latencySamplesMs Per-frame post-feature processing times (ascending), for pooled statistics.
 * @property gazeFramesEmitted Frames on which the pipeline produced a fresh gaze sample (face present
 *   and eyelids open); on the other frames the reticle holds its previous position, as the live app does.
 * @property edgeClampedFramePct Percentage of [gazeFramesEmitted] whose projection hit the screen-edge
 *   clamp on either axis, i.e. frames whose output position is a clamp value rather than a mapping result.
 */
data class ScenarioEvaluationResult(
    val scenario: ReplayScenario,
    val totalFramesEvaluated: Int,
    val passed: Boolean,
    val falseActivations: Int,
    val validActivations: Int,
    val meanLatencyMs: Double,
    val p95LatencyMs: Double,
    val rmsJitterPx: Double,
    val notes: String,
    val latencySamplesMs: List<Double> = emptyList(),
    val gazeFramesEmitted: Int = 0,
    val edgeClampedFramePct: Double = 0.0
)

/**
 * Deterministic Replay Test Harness:
 * Generates synthetic or prerecorded frame sequences mimicking real-world optical variations,
 * facial contractions, and system disturbances, feeding them into the tracking pipeline.
 */
object ReplayTestHarness {

    /** Cadence of the synthetic traces (~30 FPS). */
    const val FRAME_INTERVAL_MS = 33L

    /**
     * EYES_CLOSED_REST_5S keeps the eyes closed for this many frames (152 x 33 ms = 5.016 s) and then
     * reopens them, so the detector has to classify a real 5 s closure. Shorter traces never reopen.
     */
    const val EYES_CLOSED_REST_CLOSED_FRAMES = 152

    // Same bounds GazeCalibrationMath.projectGaze clamps its output to.
    private const val PROJECTION_CLAMP_MIN = 0.02f
    private const val PROJECTION_CLAMP_MAX = 0.98f

    /** Number of frames that cover [durationMs] at [FRAME_INTERVAL_MS]. */
    fun framesForDurationMs(durationMs: Long): Int =
        ceil(durationMs.toDouble() / FRAME_INTERVAL_MS.toDouble()).toInt()

    /** Median of an ascending list (mean of the two middle values for an even size); 0.0 when empty. */
    fun medianOfSorted(sorted: List<Double>): Double {
        if (sorted.isEmpty()) return 0.0
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /** Nearest-rank percentile ([quantile] in 0..1) of an ascending list; 0.0 when empty. */
    fun percentileOfSorted(sorted: List<Double>, quantile: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val rank = ceil(quantile.coerceIn(0.0, 1.0) * sorted.size).toInt()
        return sorted[(rank - 1).coerceIn(0, sorted.size - 1)]
    }

    /**
     * Synthesizes a deterministic frame sequence for a given scenario.
     * @param scenario The target stress scenario.
     * @param frameCount Number of sequential frames to generate (cadence typically 30 FPS / ~33 ms).
     */
    fun generateScenarioTrace(
        scenario: ReplayScenario,
        frameCount: Int = 180,
        startTs: Long = 100000L
    ): List<GazeFeatureVector> {
        val frames = mutableListOf<GazeFeatureVector>()
        val random = Random(42 + scenario.ordinal)
        val dtMs = FRAME_INTERVAL_MS // ~30 FPS default cadence

        for (i in 0 until frameCount) {
            val ts = startTs + i * dtMs
            val timeSec = i * 0.033

            // Baseline stationary gaze on center tile
            var gx = 0.0 + random.nextDouble(-0.012, 0.012)
            var gy = 0.0 + random.nextDouble(-0.010, 0.010)
            var yaw = 0.0
            var pitch = 0.0
            var roll = 0.0
            var eyeDist = 148.0
            var leftOpen = 0.90f
            var rightOpen = 0.90f
            var leftConf = 0.92f
            var rightConf = 0.92f
            var browRatio = 0.34f
            var faceDetected = true
            var isLowLight = false
            var ambientLuma = 140
            var hasGlare = false
            var isSingleEye = false
            var activeLabel = "BINOCULAR"

            when (scenario) {
                ReplayScenario.DIM_ROOM -> {
                    ambientLuma = 34
                    isLowLight = true
                    leftConf = 0.42f; rightConf = 0.40f
                    gx += random.nextDouble(-0.025, 0.025)
                    gy += random.nextDouble(-0.020, 0.020)
                }
                ReplayScenario.WINDOW_BACKLIT -> {
                    ambientLuma = 220
                    hasGlare = true
                    leftConf = 0.35f; rightConf = 0.88f // One eye washed out
                    isSingleEye = true
                    activeLabel = "RIGHT EYE ONLY"
                }
                ReplayScenario.DIRECT_SUN_SPECULAR -> {
                    hasGlare = true
                    leftConf = 0.22f; rightConf = 0.24f
                    gx += random.nextDouble(-0.03, 0.03)
                }
                ReplayScenario.FLICKERING_LIGHT -> {
                    val flicker = (sin(timeSec * 2.0 * PI * 60.0) * 45).toInt()
                    ambientLuma = (120 + flicker).coerceIn(20, 250)
                }
                ReplayScenario.NEAR_DARK -> {
                    ambientLuma = 18
                    isLowLight = true
                    leftConf = 0.28f; rightConf = 0.26f
                }
                ReplayScenario.GLASSES_SPECULAR_GLARE -> {
                    hasGlare = true
                    // Glare intermittently drops confidence on left eye
                    if ((i / 15) % 2 == 0) {
                        leftConf = 0.20f
                        isSingleEye = true
                        activeLabel = "RIGHT EYE ONLY"
                    }
                }
                ReplayScenario.SUNGLASSES_OCCLUSION -> {
                    leftOpen = 0.35f; rightOpen = 0.35f
                    leftConf = 0.25f; rightConf = 0.25f
                }
                ReplayScenario.HEAD_TURNED_30_DEG -> {
                    yaw = 32.5
                    pitch = 14.0
                    rightConf = 0.38f // Occlusion of far eye
                }
                ReplayScenario.PARTIAL_FACE_OUT_OF_FRAME -> {
                    faceDetected = i < frameCount * 0.75
                    yaw = 28.0
                }
                ReplayScenario.POSTURE_UPRIGHT -> {
                    pitch = 2.0; roll = 0.0
                }
                ReplayScenario.POSTURE_RECLINING -> {
                    pitch = -18.0; roll = 1.5
                    gy -= 0.04 // Eye elevation compensation
                }
                ReplayScenario.POSTURE_LYING_DOWN -> {
                    pitch = -34.0; roll = 3.0
                    gy -= 0.08
                }
                ReplayScenario.HANDHELD_TREMOR -> {
                    // 6-8 Hz hand micro-tremor
                    val tremorX = sin(timeSec * 2.0 * PI * 7.2) * 0.022
                    val tremorY = cos(timeSec * 2.0 * PI * 6.5) * 0.018
                    gx += tremorX
                    gy += tremorY
                    yaw += tremorX * 20.0
                }
                ReplayScenario.NORMAL_INVOLUNTARY_BLINKS -> {
                    // Periodic normal blinks every 45-60 frames (150 ms duration = ~5 frames)
                    val blinkPhase = i % 55
                    if (blinkPhase in 20..24) {
                        leftOpen = 0.08f; rightOpen = 0.08f
                    }
                }
                ReplayScenario.RAPID_FATIGUE_BLINKS -> {
                    // Rapid short flutter (3 frames closed, 4 open, repeated)
                    val flutter = i % 8
                    if (flutter in 0..2) {
                        leftOpen = 0.12f; rightOpen = 0.12f
                    }
                }
                ReplayScenario.CONTINUOUS_TALKING -> {
                    // Jaw movement modulates lower face; eyes stay open; brow stays neutral
                    val mouthCycle = sin(timeSec * 2.0 * PI * 2.8) * 0.015
                    pitch += mouthCycle * 8.0
                    browRatio = (0.34f + mouthCycle * 0.5f).toFloat()
                }
                ReplayScenario.YAWNING_JAW_OPEN -> {
                    pitch += 14.0
                    leftOpen = 0.48f; rightOpen = 0.48f // Squinting eyes during yawn
                    browRatio = 0.32f
                }
                ReplayScenario.LAUGHING_SMILE -> {
                    leftOpen = 0.52f; rightOpen = 0.52f
                    browRatio = 0.33f
                }
                ReplayScenario.LOOKING_AWAY_10S -> {
                    // Saccade completely off screen (gx = 0.48, yaw = 25°)
                    gx = 0.48
                    gy = -0.38
                    yaw = 28.0
                }
                ReplayScenario.EYES_CLOSED_REST_5S -> {
                    // Eyes closed for 5 s, then reopened: the reopening is what the blink detector classifies.
                    if (i < EYES_CLOSED_REST_CLOSED_FRAMES) {
                        leftOpen = 0.02f; rightOpen = 0.02f
                    }
                }
                ReplayScenario.INTENTIONAL_SELECTION_BLINK -> {
                    // Deliberate command blink: frames 40 to 54 closed (14 frames * 33ms = 462 ms)
                    if (i in 40..53) {
                        leftOpen = 0.05f; rightOpen = 0.05f
                    }
                }
                ReplayScenario.INTENTIONAL_BROW_RAISE -> {
                    // Deliberate brow raise: frames 40 to 55 held high (15 frames * 33ms = 495 ms)
                    if (i in 40..54) {
                        browRatio = 0.44f // Well above 0.388 threshold
                    }
                }
                ReplayScenario.NO_FACE_IN_FRAME -> {
                    faceDetected = false
                }
                ReplayScenario.MULTIPLE_FACES_PRIMARY_LOCK -> {
                    // Primary face remains tracked and locked
                    faceDetected = true
                }
                ReplayScenario.ENDURANCE_60_MIN -> {
                    // Realistic cyclic sequence combining periodic reading saccades, blinks, and holds
                    val saccadeCycle = (i / 90) % 4
                    val targetGx = when (saccadeCycle) {
                        0 -> -0.18
                        1 -> 0.18
                        2 -> -0.18
                        else -> 0.18
                    }
                    val targetGy = if (saccadeCycle < 2) -0.15 else 0.15
                    gx = targetGx + random.nextDouble(-0.008, 0.008)
                    gy = targetGy + random.nextDouble(-0.008, 0.008)
                    if (i % 60 in 10..14) {
                        leftOpen = 0.05f; rightOpen = 0.05f
                    }
                }
            }

            frames.add(
                GazeFeatureVector(
                    gx = gx.coerceIn(-0.5, 0.5),
                    gy = gy.coerceIn(-0.5, 0.5),
                    yaw = yaw,
                    pitch = pitch,
                    roll = roll,
                    eyeDistancePx = eyeDist,
                    leftEyeOpenProb = leftOpen,
                    rightEyeOpenProb = rightOpen,
                    leftEyeConfidence = leftConf,
                    rightEyeConfidence = rightConf,
                    browToEyeRatio = browRatio,
                    isSingleEyeMode = isSingleEye,
                    activeEyeLabel = activeLabel,
                    hasGlareWarning = hasGlare,
                    faceDetected = faceDetected,
                    ambientLuma = ambientLuma,
                    isLowLightDegraded = isLowLight,
                    timestampMs = ts
                )
            )
        }
        return frames
    }

    /**
     * Executes a trace through the post-feature gaze pipeline (pupil filter, projection, screen filter,
     * blink and brow detectors; no camera or face detection), evaluating:
     * - Unintended blink-command / brow-raise triggers (MUST be 0 in every non-COMMAND scenario)
     * - Exactly one trigger of the expected gesture in COMMAND scenarios (wrong-gesture or repeated
     *   triggers count as false activations). Dwell selection is not modelled.
     * - Filtered jitter (RMS displacement in screen pixels) and how often the projection is edge-clamped
     * - Per-frame processing time of these stages on the current JVM
     *
     * Like the live ViewModel, no gaze is emitted while the face is absent or the eyelids are closing:
     * the last emitted position is held, and nothing is emitted before the first valid frame.
     */
    fun evaluateTrace(
        scenario: ReplayScenario,
        trace: List<GazeFeatureVector>,
        blinkDetector: BlinkHysteresisDetector,
        browDetector: BrowRaiseHysteresisDetector,
        pupilFilter: OneEuroFilter2D,
        screenFilter: OneEuroFilter2D,
        matrixCoeffs: List<Double>,
        screenWidthPx: Double = 1344.0,
        screenHeightPx: Double = 2992.0
    ): ScenarioEvaluationResult {
        blinkDetector.reset()
        browDetector.reset()
        pupilFilter.reset()
        screenFilter.reset()

        var falseActivations = 0
        var validActivations = 0
        var sumLatency = 0.0
        var gazeFramesEmitted = 0
        var edgeClampedFrames = 0
        val latencies = mutableListOf<Double>()
        val filteredPoints = mutableListOf<Pair<Double, Double>>()

        val isMidasTouchScenario = scenario.category == "MIDAS_TOUCH"
        val isCommandScenario = scenario.category == "COMMAND"
        val expectsCommandBlink = scenario == ReplayScenario.INTENTIONAL_SELECTION_BLINK
        val expectsBrowRaise = scenario == ReplayScenario.INTENTIONAL_BROW_RAISE

        for (frame in trace) {
            val startNs = System.nanoTime()

            // 1. Pupil 1€ Filter (held while the face is absent or the eyes are nearly closed)
            val pupil = if (frame.faceDetected && frame.combinedEyeOpenProb >= 0.40f) {
                pupilFilter.filter(frame.gx, frame.gy, frame.timestampMs)
            } else {
                pupilFilter.lastPosition
            }

            // 2-3. Gaze projection with filtered pupil offsets, then the screen gaze 1€ filter
            val isEyelidClosing = frame.combinedEyeOpenProb < 0.50f
            val screenPoint = if (frame.faceDetected && !isEyelidClosing && pupil != null) {
                val cleanFeature = frame.copy(gx = pupil.first, gy = pupil.second)
                val (rawX, rawY) = GazeCalibrationMath.projectGaze(cleanFeature, matrixCoeffs)
                gazeFramesEmitted++
                if (rawX <= PROJECTION_CLAMP_MIN || rawX >= PROJECTION_CLAMP_MAX ||
                    rawY <= PROJECTION_CLAMP_MIN || rawY >= PROJECTION_CLAMP_MAX
                ) {
                    edgeClampedFrames++
                }
                screenFilter.filter(rawX.toDouble(), rawY.toDouble(), frame.timestampMs)
            } else {
                // No raw fallback: the live app holds the previous reticle position here.
                screenFilter.lastPosition
            }
            if (screenPoint != null) filteredPoints.add(screenPoint)

            // 4. Expression & Gesture classification
            val (_, blinkEvent) = blinkDetector.update(frame.combinedEyeOpenProb, frame.timestampMs)
            val (_, browFired) = browDetector.update(frame.browToEyeRatio, frame.timestampMs)

            if (blinkEvent?.type == BlinkType.INTENTIONAL_COMMAND) {
                if (expectsCommandBlink && validActivations == 0) validActivations++ else falseActivations++
            }
            if (browFired) {
                if (expectsBrowRaise && validActivations == 0) validActivations++ else falseActivations++
            }

            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
            sumLatency += elapsedMs
            latencies.add(elapsedMs)
        }

        latencies.sort()
        val meanLatency = if (latencies.isNotEmpty()) sumLatency / latencies.size else 0.0
        val p95Latency = percentileOfSorted(latencies, 0.95)

        // RMS jitter across the middle steady segment, in screen pixels per axis
        var rmsJitter = Double.NaN
        if (filteredPoints.size >= 10) {
            val steady = filteredPoints.subList(filteredPoints.size / 4, (filteredPoints.size * 3) / 4)
            val meanX = steady.map { it.first }.average()
            val meanY = steady.map { it.second }.average()
            var sumSq = 0.0
            for (p in steady) {
                val dxPx = (p.first - meanX) * screenWidthPx
                val dyPx = (p.second - meanY) * screenHeightPx
                sumSq += dxPx * dxPx + dyPx * dyPx
            }
            rmsJitter = sqrt(sumSq / steady.size)
        }
        val edgeClampedPct = if (gazeFramesEmitted > 0) 100.0 * edgeClampedFrames / gazeFramesEmitted else 0.0

        val passed = if (isCommandScenario) {
            validActivations == 1 && falseActivations == 0
        } else {
            falseActivations == 0
        }

        val trackingSummary = when {
            !rmsJitter.isNaN() -> String.format(
                Locale.US,
                if (scenario.category == "ENDURANCE") {
                    "RMS gaze spread %.2f px incl. deliberate saccades; %.0f%% of gaze frames clamped at the screen edge"
                } else {
                    "RMS jitter %.2f px; %.0f%% of gaze frames clamped at the screen edge"
                },
                rmsJitter,
                edgeClampedPct
            )
            gazeFramesEmitted == 0 -> "no gaze output: face absent or eyelids below the open threshold on every frame"
            else -> "too few gaze frames ($gazeFramesEmitted) to estimate jitter"
        }
        val expectedGesture = if (expectsBrowRaise) "brow raise" else "command blink"

        val notes = when {
            isCommandScenario && passed ->
                "PASSED: Captured 1 deliberate $expectedGesture with 0 false triggers ($trackingSummary)."
            isCommandScenario ->
                "FAILED: Expected exactly 1 deliberate $expectedGesture; captured $validActivations, plus $falseActivations unintended trigger(s)."
            isMidasTouchScenario && passed ->
                "PASSED: Zero false blink/brow activations across ${trace.size} frames of involuntary gestures (dwell not modelled; $trackingSummary)."
            isMidasTouchScenario ->
                "FAILED: Triggered $falseActivations accidental activation(s) during involuntary facial motion."
            passed ->
                "PASSED: No unintended blink/brow triggers under ${scenario.label} (no gaze ground truth; $trackingSummary)."
            else ->
                "FAILED: $falseActivations unintended blink/brow trigger(s) under ${scenario.label} ($trackingSummary)."
        }

        return ScenarioEvaluationResult(
            scenario = scenario,
            totalFramesEvaluated = trace.size,
            passed = passed,
            falseActivations = falseActivations,
            validActivations = validActivations,
            meanLatencyMs = meanLatency,
            p95LatencyMs = p95Latency,
            rmsJitterPx = rmsJitter,
            notes = notes,
            latencySamplesMs = latencies.toList(),
            gazeFramesEmitted = gazeFramesEmitted,
            edgeClampedFramePct = edgeClampedPct
        )
    }
}
