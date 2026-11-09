package com.example.regression

import com.example.data.model.BlinkType
import com.example.data.model.DEFAULT_AFFINE_MATRIX_2X6
import com.example.data.model.GazeFeatureVector
import com.example.domain.BenchmarkRunner
import com.example.domain.BlinkHysteresisDetector
import com.example.domain.BrowRaiseHysteresisDetector
import com.example.domain.GazeCalibrationMath
import com.example.domain.OneEuroFilter2D
import com.example.domain.ReplayScenario
import com.example.domain.ReplayTestHarness
import com.example.domain.ScenarioEvaluationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.atan
import kotlin.math.tan

/**
 * Regression tests for defects fixed in ReplayTestHarness.kt and BenchmarkRunner.kt.
 * Each test fails on the pre-fix code and passes on the fixed code.
 */
class ReplaybenchRegressionTest {

    private fun evaluate(
        scenario: ReplayScenario,
        trace: List<GazeFeatureVector>,
        screenWidthPx: Double = 1344.0,
        screenHeightPx: Double = 2992.0
    ): ScenarioEvaluationResult = ReplayTestHarness.evaluateTrace(
        scenario = scenario,
        trace = trace,
        blinkDetector = BlinkHysteresisDetector(),
        browDetector = BrowRaiseHysteresisDetector(),
        pupilFilter = OneEuroFilter2D(),
        screenFilter = OneEuroFilter2D(),
        matrixCoeffs = DEFAULT_AFFINE_MATRIX_2X6,
        screenWidthPx = screenWidthPx,
        screenHeightPx = screenHeightPx
    )

    private fun closeEyes(trace: List<GazeFeatureVector>, frames: IntRange): List<GazeFeatureVector> =
        trace.mapIndexed { i, f -> if (i in frames) f.copy(leftEyeOpenProb = 0.05f, rightEyeOpenProb = 0.05f) else f }

    // A34 / B53 / B1: the forward-model probe used yaw/pitch with the opposite sign to gx/gy.
    @Test
    fun `forward model check uses the engine yaw and pitch sign convention`() {
        val card = BenchmarkRunner.runCompleteBenchmark()
        // Inverted head-pose signs gave 466.3 px mean / 628.1 px worst (~4.04 deg).
        assertEquals(51.8, card.meanAccuracyPx, 1.0)
        assertEquals(74.1, card.worstEdgeAccuracyPx, 1.0)
        assertTrue("mean ${card.meanAccuracyDeg} deg", card.meanAccuracyDeg < 1.0)
        assertTrue("worst ${card.worstEdgeAccuracyDeg} deg", card.worstEdgeAccuracyDeg < 1.0)
    }

    // A46: the visual angle used Pixel 10 Pro XL millimetres whatever screen the caller had.
    @Test
    fun `visual angle uses the physical screen size the caller passes`() {
        val pixel = BenchmarkRunner.runCompleteBenchmark()
        val doubled = BenchmarkRunner.runCompleteBenchmark(
            screenWidthMm = 2.0 * GazeCalibrationMath.PIXEL_10_PRO_XL_WIDTH_MM,
            screenHeightMm = 2.0 * GazeCalibrationMath.PIXEL_10_PRO_XL_HEIGHT_MM
        )
        assertEquals(pixel.meanAccuracyPx, doubled.meanAccuracyPx, 1e-9)
        val expectedDeg = Math.toDegrees(atan(2.0 * tan(Math.toRadians(pixel.meanAccuracyDeg))))
        assertEquals(expectedDeg, doubled.meanAccuracyDeg, 1e-6)
    }

    // A35 / B52: latency was a mean of means floored at 18 ms and a max of p95s floored at 26 ms.
    @Test
    fun `latency is the pooled median and p95 of the measured frame times without floors`() {
        val card = BenchmarkRunner.runCompleteBenchmark()
        val pooled = card.scenarioResults.flatMap { it.latencySamplesMs }.sorted()
        assertEquals(card.scenarioResults.sumOf { it.totalFramesEvaluated }, pooled.size)
        assertEquals(ReplayTestHarness.medianOfSorted(pooled), card.latencyMedianMs, 0.0)
        assertEquals(ReplayTestHarness.percentileOfSorted(pooled, 0.95), card.latencyP95Ms, 0.0)
        // Post-feature JVM work takes microseconds per frame; the old floors were exactly 18.0 and 26.0 ms.
        assertTrue(card.latencyMedianMs > 0.0)
        assertTrue("median ${card.latencyMedianMs} ms", card.latencyMedianMs < 18.0)
        assertTrue(card.latencyP95Ms >= card.latencyMedianMs)
        assertTrue("p95 ${card.latencyP95Ms} ms", card.latencyP95Ms < 26.0)

        assertEquals(2.5, ReplayTestHarness.medianOfSorted(listOf(1.0, 2.0, 3.0, 4.0)), 0.0)
        assertEquals(2.0, ReplayTestHarness.medianOfSorted(listOf(1.0, 2.0, 3.0)), 0.0)
        assertEquals(95.0, ReplayTestHarness.percentileOfSorted((1..100).map { it.toDouble() }, 0.95), 0.0)
    }

    // A36 / A37 / B52: FPS, drops, calibration, drift, battery, cold start, time to control and Fitts
    // throughput were literals reported as measurements.
    @Test
    fun `metrics the replay cannot measure are null and reported as not measured`() {
        val card = BenchmarkRunner.runCompleteBenchmark()
        assertNull(card.measuredFps)
        assertNull(card.droppedFramesPct)
        assertNull(card.calibrationTimeSeconds)
        assertNull(card.driftStabilityDurationMin)
        assertNull(card.projectedBatteryDrainPctPerHour)
        assertNull(card.coldStartTimeMs)
        assertNull(card.timeToUsableControlMs)
        assertNull(card.fittsThroughputBitsPerSec)
        assertNull(card.fittsMeanMovementTimeMs)

        val report = card.toFormattedReport()
        assertTrue(report.contains("Effective Analysis FPS:      not measured"))
        assertFalse(report.contains("29.8 FPS"))
        assertFalse(report.contains("3.32 bits/sec"))
        assertFalse(report.contains("11.2% / hour"))
        assertFalse(report.contains("Cold Start to Preview:      420 ms"))

        // The pass criteria are now real, and the shipped traces still meet them.
        assertEquals(ReplayScenario.entries.size, card.scenarioResults.size)
        assertTrue(card.scenarioResults.filterNot { it.passed }.joinToString { it.notes }, card.allScenariosPassed)
        assertTrue(report.contains("100% PASS"))
        assertTrue(report.contains("LATENCY & THROUGHPUT"))
    }

    // A38: jitter was normalized permille scaled by the width on both axes, averaged with the saccade-heavy
    // endurance trace, and floored at 1.2.
    @Test
    fun `jitter is in screen pixels per axis and excludes saccade and no-gaze traces`() {
        // Gaze noise on y only: the jitter follows the screen height and ignores the width.
        val yOnly = (0 until 120).map { i ->
            GazeFeatureVector(
                gy = if (i % 2 == 0) 0.02 else -0.02,
                faceDetected = true,
                timestampMs = 100_000L + i * 33L
            )
        }
        val tall = evaluate(ReplayScenario.POSTURE_UPRIGHT, yOnly, screenWidthPx = 1000.0, screenHeightPx = 2000.0)
        val short = evaluate(ReplayScenario.POSTURE_UPRIGHT, yOnly, screenWidthPx = 4000.0, screenHeightPx = 1000.0)
        assertTrue(tall.rmsJitterPx > 0.0)
        assertEquals(2.0, tall.rmsJitterPx / short.rmsJitterPx, 1e-6)

        val card = BenchmarkRunner.runCompleteBenchmark()
        val endurance = card.scenarioResults.single { it.scenario == ReplayScenario.ENDURANCE_60_MIN }
        assertFalse(BenchmarkRunner.countsTowardStationaryJitter(endurance))
        val included = card.scenarioResults.filter { BenchmarkRunner.countsTowardStationaryJitter(it) }
        assertTrue(included.isNotEmpty())
        assertEquals(included.map { it.rmsJitterPx }.average(), card.rmsJitterPx, 1e-9)
        // The old aggregate was 28.04 px, 78% of it from the endurance saccades.
        assertTrue("jitter ${card.rmsJitterPx} px", card.rmsJitterPx < 20.0)
    }

    // A44: with no face the harness emitted raw, unfiltered projections and scored their jitter.
    @Test
    fun `no gaze is emitted while no face is detected`() {
        val scenario = ReplayScenario.NO_FACE_IN_FRAME
        val res = evaluate(scenario, ReplayTestHarness.generateScenarioTrace(scenario, frameCount = 120))
        assertEquals(0, res.gazeFramesEmitted)
        assertTrue("jitter ${res.rmsJitterPx}", res.rmsJitterPx.isNaN())
        assertTrue(res.notes, res.notes.contains("no gaze output"))
        assertFalse(BenchmarkRunner.countsTowardStationaryJitter(res))
    }

    // A45: an empty trace threw IllegalArgumentException from coerceIn(0, -1).
    @Test
    fun `empty trace evaluates without throwing`() {
        val res = evaluate(ReplayScenario.DIM_ROOM, emptyList())
        assertEquals(0, res.totalFramesEvaluated)
        assertEquals(0.0, res.meanLatencyMs, 0.0)
        assertEquals(0.0, res.p95LatencyMs, 0.0)
        assertTrue(res.passed)
    }

    // A43 / B55: a failed COMMAND scenario fell through to the 'PASSED: Stable tracking' note.
    @Test
    fun `failed command scenario is reported as failed`() {
        val scenario = ReplayScenario.INTENTIONAL_SELECTION_BLINK
        // Closure starts at frame 40 and never reopens within 50 frames, so no command is classified.
        val res = evaluate(scenario, ReplayTestHarness.generateScenarioTrace(scenario, frameCount = 50))
        assertFalse(res.passed)
        assertEquals(0, res.validActivations)
        assertTrue(res.notes, res.notes.startsWith("FAILED"))
        assertFalse(res.notes, res.notes.contains("PASSED"))
    }

    // A40 / B8 / B55: every trigger in a COMMAND scenario counted as valid, so repeats and wrong gestures passed.
    @Test
    fun `command scenarios count repeated and wrong-gesture triggers as false activations`() {
        val scenario = ReplayScenario.INTENTIONAL_SELECTION_BLINK
        val base = ReplayTestHarness.generateScenarioTrace(scenario, frameCount = 200)
        assertEquals(1, evaluate(scenario, base).validActivations)

        // A second 462 ms closure fires a second command blink.
        val repeated = evaluate(scenario, closeEyes(base, 120..133))
        assertEquals(1, repeated.validActivations)
        assertEquals(1, repeated.falseActivations)
        assertFalse(repeated.passed)

        // A held brow raise is the wrong gesture for the blink scenario.
        val withBrow = base.mapIndexed { i, f -> if (i in 120..140) f.copy(browToEyeRatio = 0.44f) else f }
        val wrongGesture = evaluate(scenario, withBrow)
        assertEquals(1, wrongGesture.validActivations)
        assertEquals(1, wrongGesture.falseActivations)
        assertFalse(wrongGesture.passed)
    }

    // A39 / B55 / B1: LIGHTING, USER, POSTURE, SCENE and ENDURANCE scenarios passed unconditionally and ignored triggers.
    @Test
    fun `an unintended trigger fails a tracking scenario`() {
        val scenario = ReplayScenario.POSTURE_UPRIGHT
        val trace = closeEyes(ReplayTestHarness.generateScenarioTrace(scenario, frameCount = 120), 40..53)
        val res = evaluate(scenario, trace)
        assertEquals(1, res.falseActivations)
        assertFalse(res.passed)
        assertTrue(res.notes, res.notes.startsWith("FAILED"))
    }

    // A42: EYES_CLOSED_REST_5S never reopened (so it could not fail) and the 5 s / 10 s scenarios replayed 3.96 s.
    @Test
    fun `eyes closed rest reopens after five seconds and labelled durations are replayed in full`() {
        val scenario = ReplayScenario.EYES_CLOSED_REST_5S
        val trace = ReplayTestHarness.generateScenarioTrace(scenario, frameCount = 180)
        val closedFrames = trace.takeWhile { it.combinedEyeOpenProb < 0.45f }.size
        assertTrue(closedFrames * 33L >= 5_000L)
        assertTrue(trace.last().combinedEyeOpenProb > 0.65f)
        val detector = BlinkHysteresisDetector()
        val events = trace.mapNotNull { detector.update(it.combinedEyeOpenProb, it.timestampMs).second }
        assertEquals(listOf(BlinkType.EXTENDED_REST), events.map { it.type })

        val card = BenchmarkRunner.runCompleteBenchmark()
        val rest = card.scenarioResults.single { it.scenario == scenario }
        assertTrue(rest.totalFramesEvaluated > ReplayTestHarness.EYES_CLOSED_REST_CLOSED_FRAMES)
        assertTrue(rest.passed)
        val away = card.scenarioResults.single { it.scenario == ReplayScenario.LOOKING_AWAY_10S }
        assertTrue(away.totalFramesEvaluated * 33L >= 10_000L)
    }

    // A46 / B54: the report always printed '(Blinks/Talking: 0 false triggers)' and '(Tensor G5)'.
    @Test
    fun `report derives the false-trigger count from the results and names no fixed SoC`() {
        val card = BenchmarkRunner.runCompleteBenchmark()
        assertEquals(0, card.midasFalseActivationCount)
        val midas = card.scenarioResults.first { it.scenario.category == "MIDAS_TOUCH" }
        val faulty = card.copy(
            scenarioResults = card.scenarioResults.map {
                if (it === midas) it.copy(falseActivations = 2, passed = false) else it
            },
            midasFalseActivationCount = 2,
            allScenariosPassed = false
        )
        val report = faulty.toFormattedReport()
        val falseLine = report.lines().single { it.contains("False Activations:") }
        assertTrue(falseLine, falseLine.contains("2 false triggers in Midas-touch scenarios"))
        assertFalse(falseLine, falseLine.contains("0 false triggers"))
        assertFalse(report.contains("Tensor G5"))
        assertTrue(report.contains("FAILURES DETECTED"))
        assertTrue(
            card.copy(socLabel = "Tensor G4").toFormattedReport().contains("SYSTEM HEALTH & RESOURCES (Tensor G4)")
        )
    }

    // A50: String.format without a locale printed '466,3 px' and '2,04' on comma-decimal devices.
    @Test
    fun `report and notes use US number formatting under a comma-decimal default locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val card = BenchmarkRunner.runCompleteBenchmark()
            val report = card.toFormattedReport()
            assertTrue(report.contains(String.format(Locale.US, "%.1f px", card.meanAccuracyPx)))
            assertFalse(report.contains(String.format(Locale.GERMANY, "%.1f px", card.meanAccuracyPx)))
            val dim = card.scenarioResults.single { it.scenario == ReplayScenario.DIM_ROOM }
            assertTrue(dim.notes, dim.notes.contains(String.format(Locale.US, "RMS jitter %.2f px", dim.rmsJitterPx)))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
