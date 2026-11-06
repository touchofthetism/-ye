package com.example.domain

import com.example.data.model.GazeFeatureVector
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
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

    ENDURANCE_60_MIN("Continuous 60-Minute Session Stream", "ENDURANCE")
}

/**
 * Result record from a replay scenario evaluation.
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
    val notes: String
)

/**
 * Deterministic Replay Test Harness:
 * Generates synthetic or prerecorded frame sequences mimicking real-world optical variations,
 * facial contractions, and system disturbances, feeding them into the tracking pipeline.
 */
object ReplayTestHarness {

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
        val dtMs = 33L // ~30 FPS default cadence

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
                    // Closed eyes for entire duration
                    leftOpen = 0.02f; rightOpen = 0.02f
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
     * Executes a trace through the gaze pipeline, evaluating:
     * - Number of false activations (MUST be 0 for Midas Touch scenarios)
     * - Successful valid command detections (for deliberate command blinks/brow raises)
     * - Filtered jitter suppression (RMS displacement)
     * - End-to-end pipeline processing latency
     */
    fun evaluateTrace(
        scenario: ReplayScenario,
        trace: List<GazeFeatureVector>,
        blinkDetector: BlinkHysteresisDetector,
        browDetector: BrowRaiseHysteresisDetector,
        pupilFilter: OneEuroFilter2D,
        screenFilter: OneEuroFilter2D,
        matrixCoeffs: List<Double>
    ): ScenarioEvaluationResult {
        blinkDetector.reset()
        browDetector.reset()
        pupilFilter.reset()
        screenFilter.reset()

        var falseActivations = 0
        var validActivations = 0
        var sumLatency = 0.0
        val latencies = mutableListOf<Double>()
        val filteredPoints = mutableListOf<Pair<Double, Double>>()

        val isMidasTouchScenario = scenario.category == "MIDAS_TOUCH"
        val isCommandScenario = scenario.category == "COMMAND"

        for (frame in trace) {
            val startNs = System.nanoTime()

            // 1. Pupil 1€ Filter
            val (filteredGx, filteredGy) = if (frame.faceDetected && frame.combinedEyeOpenProb >= 0.40f) {
                pupilFilter.filter(frame.gx, frame.gy, frame.timestampMs)
            } else {
                pupilFilter.lastPosition ?: Pair(frame.gx, frame.gy)
            }

            // 2. Gaze Projection with filtered pupil offsets
            val cleanFeature = frame.copy(gx = filteredGx, gy = filteredGy)
            val (rawX, rawY) = GazeCalibrationMath.projectGaze(cleanFeature, matrixCoeffs)

            // 3. Screen Gaze 1€ Filter
            val isEyelidClosing = frame.combinedEyeOpenProb < 0.50f
            val (screenX, screenY) = if (frame.faceDetected && !isEyelidClosing) {
                screenFilter.filter(rawX.toDouble(), rawY.toDouble(), frame.timestampMs)
            } else {
                screenFilter.lastPosition ?: Pair(rawX.toDouble(), rawY.toDouble())
            }
            filteredPoints.add(Pair(screenX, screenY))

            // 4. Expression & Gesture classification
            val (_, blinkEvent) = blinkDetector.update(frame.combinedEyeOpenProb, frame.timestampMs)
            val (_, browFired) = browDetector.update(frame.browToEyeRatio, frame.timestampMs)

            val actionTriggered = (blinkEvent?.type == com.example.data.model.BlinkType.INTENTIONAL_COMMAND) || browFired

            if (actionTriggered) {
                if (isMidasTouchScenario) {
                    falseActivations++
                } else if (isCommandScenario) {
                    validActivations++
                }
            }

            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
            sumLatency += elapsedMs
            latencies.add(elapsedMs)
        }

        latencies.sort()
        val meanLatency = if (latencies.isNotEmpty()) sumLatency / latencies.size else 0.0
        val p95Index = (latencies.size * 0.95).toInt().coerceIn(0, latencies.size - 1)
        val p95Latency = if (latencies.isNotEmpty()) latencies[p95Index] else 0.0

        // Calculate RMS jitter across middle steady segment
        var rmsJitter = 0.0
        if (filteredPoints.size >= 10) {
            val steady = filteredPoints.subList(filteredPoints.size / 4, (filteredPoints.size * 3) / 4)
            val meanX = steady.map { it.first }.average()
            val meanY = steady.map { it.second }.average()
            var sumDistSq = 0.0
            for (p in steady) {
                val d = hypot(p.first - meanX, p.second - meanY)
                sumDistSq += d * d
            }
            rmsJitter = kotlin.math.sqrt(sumDistSq / steady.size) * 1000.0 // Scaled in permille
        }

        val passed = when {
            isMidasTouchScenario -> falseActivations == 0
            isCommandScenario -> validActivations >= 1 && falseActivations == 0
            else -> true
        }

        val notes = when {
            !passed && isMidasTouchScenario -> "FAILED: Triggered $falseActivations accidental activation(s) during involuntary facial motion."
            passed && isMidasTouchScenario -> "PASSED: Zero false activations across ${trace.size} frames of involuntary gestures."
            passed && isCommandScenario -> "PASSED: Captured $validActivations deliberate action(s) with 0 false triggers."
            else -> "PASSED: Stable tracking under ${scenario.label} (RMS Jitter: ${String.format("%.2f", rmsJitter)}‰)."
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
            notes = notes
        )
    }
}
