package com.example.domain

import com.example.data.model.GazeFeatureVector
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Benchmark scorecard record capturing the Phase 3 metrics of a synthetic JVM replay.
 *
 * Only values the replay actually computes are non-null. Metrics that need the camera, a real pointing
 * study, the battery or process start-up (FPS, dropped frames, calibration time, drift hold, battery
 * drain, cold start, time to control, Fitts' throughput) are null and reported as "not measured".
 * Latency covers the post-feature stages only (filters, projection, gesture detectors on precomputed
 * feature vectors), and the accuracy fields are a forward-model self-check, not measured gaze accuracy.
 */
data class BenchmarkScorecard(
    val timestampMs: Long = System.currentTimeMillis(),
    val deviceModel: String = "Unspecified host",
    val latencyMedianMs: Double,
    val latencyP95Ms: Double,
    val measuredFps: Double? = null,
    val droppedFramesPct: Double? = null,
    val meanAccuracyPx: Double,
    val meanAccuracyDeg: Double,
    val worstEdgeAccuracyPx: Double,
    val worstEdgeAccuracyDeg: Double,
    val rmsJitterPx: Double,
    val fittsThroughputBitsPerSec: Double? = null,
    val fittsMeanMovementTimeMs: Double? = null,
    val falseActivationsPerMinute: Double,
    val calibrationTimeSeconds: Double? = null,
    val driftStabilityDurationMin: Double? = null,
    val projectedBatteryDrainPctPerHour: Double? = null,
    val memoryUsageMb: Double,
    val coldStartTimeMs: Long? = null,
    val timeToUsableControlMs: Long? = null,
    val allScenariosPassed: Boolean,
    val scenarioResults: List<ScenarioEvaluationResult>,
    /** Unintended blink/brow triggers summed over the MIDAS_TOUCH scenarios. */
    val midasFalseActivationCount: Int = 0,
    /** SoC name of the device the replay ran on (e.g. Build.SOC_MODEL), or null when unknown. */
    val socLabel: String? = null
) {
    fun toFormattedReport(): String {
        val sb = StringBuilder()
        val totalScenarios = scenarioResults.size
        val passedScenarios = scenarioResults.count { it.passed }
        val allFalseActivations = scenarioResults.sumOf { it.falseActivations }
        val jitterScenarios = scenarioResults.count { BenchmarkRunner.countsTowardStationaryJitter(it) }
        sb.appendLine("================================================================================")
        sb.appendLine("           OCULAGAZE BENCHMARK & PERFORMANCE SCORECARD REPORT                   ")
        sb.appendLine("================================================================================")
        sb.appendLine("Source:                   Synthetic JVM replay of precomputed gaze feature vectors")
        sb.appendLine("                          (no camera, no ML Kit face detection, no pupil extraction)")
        sb.appendLine("Device Model:             $deviceModel")
        sb.appendLine("Timestamp:                ${java.util.Date(timestampMs)}")
        val passSummary = if (allScenariosPassed) {
            "YES (100% PASS, $passedScenarios/$totalScenarios scenarios)"
        } else {
            "FAILURES DETECTED ($passedScenarios/$totalScenarios scenarios passed)"
        }
        sb.appendLine("All Scenarios Passed:     $passSummary")
        sb.appendLine("Pass Criteria:            COMMAND: exactly 1 expected gesture; all others: 0 unintended")
        sb.appendLine("                          blink/brow triggers. Dwell and per-scenario accuracy are not scored.")
        sb.appendLine("--------------------------------------------------------------------------------")
        sb.appendLine("1. LATENCY & THROUGHPUT (post-feature stages on this JVM; excl. camera + face detection):")
        sb.appendLine("   - Processing Latency (Median): ${fmt(latencyMedianMs, "%.4f", " ms")}")
        sb.appendLine("   - Processing Latency (p95):    ${fmt(latencyP95Ms, "%.4f", " ms")}")
        sb.appendLine("   - Effective Analysis FPS:      ${fmt(measuredFps, "%.1f", " FPS")}")
        sb.appendLine("   - Dropped Frame Rate:          ${fmt(droppedFramesPct, "%.2f", "%")}")
        sb.appendLine("--------------------------------------------------------------------------------")
        sb.appendLine("2. SYNTHETIC PROJECTION CHECK & JITTER (forward model, not measured gaze accuracy):")
        sb.appendLine(
            "   - Mean Projection Error:      ${fmt(meanAccuracyPx, "%.1f", " px")} " +
                "(~${fmt(meanAccuracyDeg, "%.2f", "°")} visual angle)"
        )
        sb.appendLine(
            "   - Worst Projection Error:     ${fmt(worstEdgeAccuracyPx, "%.1f", " px")} " +
                "(~${fmt(worstEdgeAccuracyDeg, "%.2f", "°")} visual angle)"
        )
        sb.appendLine(
            "   - Stationary RMS Jitter:      ${fmt(rmsJitterPx.takeUnless { it.isNaN() }, "%.2f", " px")} " +
                "(1€ filters; mean of $jitterScenarios fixation scenarios, excl. saccade, no-gaze and edge-clamped traces)"
        )
        val fittsText = if (fittsThroughputBitsPerSec != null && fittsMeanMovementTimeMs != null) {
            "${fmt(fittsThroughputBitsPerSec, "%.2f", " bits/sec")} (MT: ${fmt(fittsMeanMovementTimeMs, "%.0f", " ms")})"
        } else {
            "$NOT_MEASURED (no pointing trials in this replay)"
        }
        sb.appendLine("   - Fitts' Law Throughput:      $fittsText")
        sb.appendLine("--------------------------------------------------------------------------------")
        sb.appendLine("3. MIDAS TOUCH & INTENTIONAL CONTROL (blink/brow gesture triggers; dwell not modelled):")
        sb.appendLine(
            "   - False Activations:          ${fmt(falseActivationsPerMinute, "%.1f", " / min")} " +
                "($midasFalseActivationCount false triggers in Midas-touch scenarios; $allFalseActivations across all scenarios)"
        )
        sb.appendLine("   - 5-Point Calibration Time:   ${fmt(calibrationTimeSeconds, "%.1f", " s")}")
        sb.appendLine("   - Drift Recalibration Hold:   ${fmt(driftStabilityDurationMin, "%.1f", " min")}")
        sb.appendLine("--------------------------------------------------------------------------------")
        sb.appendLine("4. SYSTEM HEALTH & RESOURCES${socLabel?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}:")
        sb.appendLine("   - Projected Battery Drain:    ${fmt(projectedBatteryDrainPctPerHour, "%.1f", "% / hour")}")
        sb.appendLine("   - Process JVM Heap In Use:    ${fmt(memoryUsageMb, "%.1f", " MB")} (total - free after the run; GC-dependent)")
        sb.appendLine("   - Cold Start to Preview:      ${coldStartTimeMs?.let { "$it ms" } ?: NOT_MEASURED}")
        sb.appendLine("   - Time to Usable Control:     ${timeToUsableControlMs?.let { "$it ms" } ?: NOT_MEASURED}")
        sb.appendLine("--------------------------------------------------------------------------------")
        sb.appendLine("5. SCENARIO PASS/FAIL BREAKDOWN:")
        for (res in scenarioResults) {
            val status = if (res.passed) "[RAN-PASS]" else "[RAN-FAIL]"
            val replayedSec = res.totalFramesEvaluated * ReplayTestHarness.FRAME_INTERVAL_MS / 1000.0
            sb.appendLine(
                "   $status ${res.scenario.name.padEnd(28)} | Frames: ${res.totalFramesEvaluated.toString().padStart(4)} " +
                    "(${String.format(Locale.US, "%.1f", replayedSec)} s) | ${res.notes}"
            )
        }
        sb.appendLine("================================================================================")
        return sb.toString()
    }

    private fun fmt(value: Double?, pattern: String, unit: String): String =
        value?.let { String.format(Locale.US, pattern, it) + unit } ?: NOT_MEASURED

    companion object {
        const val NOT_MEASURED = "not measured"
    }
}

/**
 * Automates the benchmark execution and performance profiling suite.
 */
object BenchmarkRunner {

    // Mirrors the live screen gaze filter in EyeTrackingViewModel (beta sized for normalized coordinates).
    private const val SCREEN_FILTER_MIN_CUTOFF_HZ = 0.90
    private const val SCREEN_FILTER_BETA = 1.2
    private const val SCREEN_FILTER_D_CUTOFF_HZ = 1.0

    /** Replays above this share of edge-clamped gaze frames report clamp saturation, not filter jitter. */
    private const val MAX_EDGE_CLAMPED_PCT_FOR_JITTER = 50.0

    /**
     * Whether a scenario contributes to the scorecard's stationary jitter: it emitted enough gaze to
     * measure jitter, it is a fixation trace (ENDURANCE contains deliberate saccades), and its output is
     * not mostly pinned at the screen-edge clamp.
     */
    fun countsTowardStationaryJitter(result: ScenarioEvaluationResult): Boolean =
        result.scenario.category != "ENDURANCE" &&
            !result.rmsJitterPx.isNaN() &&
            result.edgeClampedFramePct <= MAX_EDGE_CLAMPED_PCT_FOR_JITTER

    fun runCompleteBenchmark(
        matrixCoeffs: List<Double> = com.example.data.model.DEFAULT_AFFINE_MATRIX_2X6,
        screenWidthPx: Double = 1344.0,
        screenHeightPx: Double = 2992.0,
        viewingDistanceMm: Double = 350.0,
        deviceModel: String = "Unspecified host",
        screenWidthMm: Double = GazeCalibrationMath.PIXEL_10_PRO_XL_WIDTH_MM,
        screenHeightMm: Double = GazeCalibrationMath.PIXEL_10_PRO_XL_HEIGHT_MM,
        socLabel: String? = null
    ): BenchmarkScorecard {
        val blinkDetector = BlinkHysteresisDetector()
        val browDetector = BrowRaiseHysteresisDetector()
        // Same tuning as the engine's pupil filter and the ViewModel's screen filter.
        val pupilFilter = OneEuroFilter2D(
            minCutoff = CameraGazeAnalyzerEngine.PUPIL_FILTER_MIN_CUTOFF_HZ,
            beta = CameraGazeAnalyzerEngine.PUPIL_FILTER_BETA,
            dCutoff = CameraGazeAnalyzerEngine.PUPIL_FILTER_D_CUTOFF_HZ
        )
        val screenFilter = OneEuroFilter2D(
            minCutoff = SCREEN_FILTER_MIN_CUTOFF_HZ,
            beta = SCREEN_FILTER_BETA,
            dCutoff = SCREEN_FILTER_D_CUTOFF_HZ
        )

        val scenarioResults = mutableListOf<ScenarioEvaluationResult>()

        // 1. Run all scenarios through the ReplayTestHarness
        for (scenario in ReplayScenario.entries) {
            val frameCount = when (scenario) {
                ReplayScenario.ENDURANCE_60_MIN -> 600 // ~20 s representative segment for unit test runtime
                ReplayScenario.CONTINUOUS_TALKING -> 250
                ReplayScenario.NORMAL_INVOLUNTARY_BLINKS -> 250
                // Full labelled durations: 10 s looking away; 5 s closed rest plus ~1 s after reopening.
                ReplayScenario.LOOKING_AWAY_10S -> ReplayTestHarness.framesForDurationMs(10_000L)
                ReplayScenario.EYES_CLOSED_REST_5S -> ReplayTestHarness.EYES_CLOSED_REST_CLOSED_FRAMES + 28
                else -> 120
            }
            val trace = ReplayTestHarness.generateScenarioTrace(scenario, frameCount = frameCount)
            val result = ReplayTestHarness.evaluateTrace(
                scenario = scenario,
                trace = trace,
                blinkDetector = blinkDetector,
                browDetector = browDetector,
                pupilFilter = pupilFilter,
                screenFilter = screenFilter,
                matrixCoeffs = matrixCoeffs,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx
            )
            scenarioResults.add(result)
        }

        // 2. Synthetic forward-model check across 9 grid targets (including corners and edges).
        // Feature vectors are synthesized from a fixed linear eye + head model (looking right/down gives
        // +gx/+yaw and +gy/+pitch, the engine's convention) and projected through matrixCoeffs. This
        // checks the matrix against that model; it is not a measurement of real gaze accuracy.
        val targets = listOf(
            Pair(0.10f, 0.10f), Pair(0.50f, 0.10f), Pair(0.90f, 0.10f),
            Pair(0.10f, 0.50f), Pair(0.50f, 0.50f), Pair(0.90f, 0.50f),
            Pair(0.10f, 0.90f), Pair(0.50f, 0.90f), Pair(0.90f, 0.90f)
        )
        val safeWidthMm = if (screenWidthMm.isFinite() && screenWidthMm > 0.0) {
            screenWidthMm
        } else {
            GazeCalibrationMath.PIXEL_10_PRO_XL_WIDTH_MM
        }
        val safeHeightMm = if (screenHeightMm.isFinite() && screenHeightMm > 0.0) {
            screenHeightMm
        } else {
            GazeCalibrationMath.PIXEL_10_PRO_XL_HEIGHT_MM
        }
        val targetErrorsPx = mutableListOf<Double>()
        val targetErrorsMm = mutableListOf<Double>()

        for (t in targets) {
            val feat = GazeFeatureVector(
                gx = ((t.first - 0.5f) * 0.40f).toDouble(),
                gy = ((t.second - 0.5f) * 0.35f).toDouble(),
                yaw = ((t.first - 0.5f) * 12.0).toDouble(),
                pitch = ((t.second - 0.5f) * 10.0).toDouble()
            )
            val (predX, predY) = GazeCalibrationMath.projectGaze(feat, matrixCoeffs)
            val dxNorm = (predX - t.first).toDouble()
            val dyNorm = (predY - t.second).toDouble()
            targetErrorsPx.add(hypot(dxNorm * screenWidthPx, dyNorm * screenHeightPx))
            targetErrorsMm.add(hypot(dxNorm * safeWidthMm, dyNorm * safeHeightMm))
        }

        val meanAccPx = targetErrorsPx.average()
        val worstEdgePx = targetErrorsPx.maxOrNull() ?: 0.0

        val meanErrMm = targetErrorsMm.average()
        val meanAccDeg = Math.toDegrees(atan2(meanErrMm, viewingDistanceMm))

        val worstErrMm = targetErrorsMm.maxOrNull() ?: 0.0
        val worstAccDeg = Math.toDegrees(atan2(worstErrMm, viewingDistanceMm))

        // 3. Fitts' law throughput needs real pointing trials, which this replay does not run (not measured).

        // 4. Involuntary blinks and talking false activations
        val midasResults = scenarioResults.filter { it.scenario.category == "MIDAS_TOUCH" }
        val totalFalseActivations = midasResults.sumOf { it.falseActivations }
        val totalMidasFrames = midasResults.sumOf { it.totalFramesEvaluated }
        val falseActPerMin = if (totalMidasFrames > 0) {
            val midasMinutes = totalMidasFrames * ReplayTestHarness.FRAME_INTERVAL_MS / 60_000.0
            totalFalseActivations.toDouble() / midasMinutes
        } else {
            0.0
        }

        // 5. Memory usage
        val runtime = Runtime.getRuntime()
        val memMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0)

        // Median and p95 of every replayed frame's processing time, pooled across scenarios (no floors).
        val pooledLatencies = scenarioResults.flatMap { it.latencySamplesMs }.sorted()
        val midLatency = ReplayTestHarness.medianOfSorted(pooledLatencies)
        val p95Lat = ReplayTestHarness.percentileOfSorted(pooledLatencies, 0.95)

        val jitterResults = scenarioResults.filter { countsTowardStationaryJitter(it) }
        val rmsJitter = if (jitterResults.isEmpty()) Double.NaN else jitterResults.map { it.rmsJitterPx }.average()

        val allPassed = scenarioResults.all { it.passed }

        return BenchmarkScorecard(
            deviceModel = deviceModel,
            latencyMedianMs = midLatency,
            latencyP95Ms = p95Lat,
            meanAccuracyPx = meanAccPx,
            meanAccuracyDeg = meanAccDeg,
            worstEdgeAccuracyPx = worstEdgePx,
            worstEdgeAccuracyDeg = worstAccDeg,
            rmsJitterPx = rmsJitter,
            falseActivationsPerMinute = falseActPerMin,
            memoryUsageMb = memMb,
            allScenariosPassed = allPassed,
            scenarioResults = scenarioResults,
            midasFalseActivationCount = totalFalseActivations,
            socLabel = socLabel
        )
    }
}
