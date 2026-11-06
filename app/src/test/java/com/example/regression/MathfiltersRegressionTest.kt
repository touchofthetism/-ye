package com.example.regression

import com.example.data.model.BlinkType
import com.example.data.model.DEFAULT_AFFINE_MATRIX_2X6
import com.example.data.model.GazeFeatureVector
import com.example.domain.BlinkHysteresisDetector
import com.example.domain.BrowRaiseHysteresisDetector
import com.example.domain.GazeCalibrationMath
import com.example.domain.GazeKalmanFilter2D
import com.example.domain.OneEuroFilter
import com.example.domain.OneEuroFilter2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

/**
 * Regression tests for defects fixed in GazeCalibrationMath.kt and OneEuroFilter.kt.
 * Each test fails on the pre-fix code and passes on the fixed code.
 */
class MathfiltersRegressionTest {

    // The default matrix maps the neutral feature vector (all offsets 0, baseline eye distance) to (0.5, 0.5).
    private val neutralFeature = GazeFeatureVector(timestampMs = 0L)

    // A2 / B51: the recommendation names the layout the Control Deck renders, using the same thresholds.
    @Test
    fun `validation recommendation names the deck the control deck actually renders`() {
        // Per-target errors 4 percent and 16 percent: mean 10, worst quadrant 16 -> 3x3 deck.
        val samples = listOf(
            Pair(neutralFeature, Pair(0.54f, 0.50f)),
            Pair(neutralFeature, Pair(0.66f, 0.50f))
        )
        val metrics = GazeCalibrationMath.evaluateHeldOutValidation(DEFAULT_AFFINE_MATRIX_2X6, samples, 148.0)
        assertEquals(10.0, metrics.meanErrorPct, 0.01)
        assertEquals(16.0, metrics.maxErrorPct, 0.01)
        assertTrue(GazeCalibrationMath.qualifiesFor3x3Grid(metrics.meanErrorPct, metrics.maxErrorPct))
        assertEquals(GazeCalibrationMath.gridLayoutLabel(true), metrics.recommendedGridDesc)

        // 30 percent everywhere -> protective 2x2 deck plus a recalibration hint (no 2x1 deck exists).
        val bad = listOf(
            Pair(neutralFeature, Pair(0.80f, 0.50f)),
            Pair(neutralFeature, Pair(0.50f, 0.80f))
        )
        val badMetrics = GazeCalibrationMath.evaluateHeldOutValidation(DEFAULT_AFFINE_MATRIX_2X6, bad, 148.0)
        assertFalse(GazeCalibrationMath.qualifiesFor3x3Grid(badMetrics.meanErrorPct, badMetrics.maxErrorPct))
        assertEquals(
            GazeCalibrationMath.gridLayoutLabel(false) + " — Recalibration Recommended",
            badMetrics.recommendedGridDesc
        )

        // Shared thresholds: mean <= 12 and worst quadrant <= 16.5, both finite.
        assertTrue(GazeCalibrationMath.qualifiesFor3x3Grid(12.0, 16.5))
        assertFalse(GazeCalibrationMath.qualifiesFor3x3Grid(12.01, 10.0))
        assertFalse(GazeCalibrationMath.qualifiesFor3x3Grid(10.0, 16.51))
        assertFalse(GazeCalibrationMath.qualifiesFor3x3Grid(Double.NaN, 10.0))
        assertEquals("3×3 Validated Deck (9 tiles)", GazeCalibrationMath.gridLayoutLabel(true))
        assertEquals("2×2 Protective Region Deck (4 tiles)", GazeCalibrationMath.gridLayoutLabel(false))
    }

    // A9: an empty validation set reports "not measured" instead of fabricated 10.9 / 14.2 / 13.4 values.
    @Test
    fun `empty validation set returns not validated instead of fabricated metrics`() {
        val metrics = GazeCalibrationMath.evaluateHeldOutValidation(DEFAULT_AFFINE_MATRIX_2X6, emptyList(), 148.0)
        assertTrue(metrics.meanErrorPct.isNaN())
        assertTrue(metrics.maxErrorPct.isNaN())
        assertTrue(metrics.approxMeanErrorMm.isNaN())
        assertEquals("Not validated (no samples)", metrics.recommendedGridDesc)
        assertFalse(GazeCalibrationMath.qualifiesFor3x3Grid(metrics.meanErrorPct, metrics.maxErrorPct))
    }

    // A11: real (landscape) screen dimensions are used as given, without the 55 x 110 mm floors.
    @Test
    fun `approximate mm error uses the supplied screen size without arbitrary floors`() {
        // Pure vertical 10 percent error on a 158 x 71 mm landscape screen is about 7.1 mm, not 11 mm.
        val samples = listOf(Pair(neutralFeature, Pair(0.50f, 0.60f)))
        val landscape = GazeCalibrationMath.evaluateHeldOutValidation(
            DEFAULT_AFFINE_MATRIX_2X6, samples, 148.0,
            screenWidthMm = 158.0,
            screenHeightMm = 71.0
        )
        assertEquals(7.1, landscape.approxMeanErrorMm, 0.05)

        // A non-finite dimension falls back to the Pixel 10 Pro XL height instead of producing NaN.
        val fallback = GazeCalibrationMath.evaluateHeldOutValidation(
            DEFAULT_AFFINE_MATRIX_2X6, samples, 148.0,
            screenWidthMm = 71.3,
            screenHeightMm = Double.NaN
        )
        assertEquals(0.10 * GazeCalibrationMath.PIXEL_10_PRO_XL_HEIGHT_MM, fallback.approxMeanErrorMm, 0.05)
    }

    // A8: tau is (baseTau * factor) clamped at 35 ms, so alpha adapts to dt and displacement.
    @Test
    fun `adaptive smoothing alpha adapts instead of being pinned at the 0_20 floor`() {
        val rest = GazeCalibrationMath.computeAdaptiveSmoothingAlpha(66L, 0f)
        assertEquals(1.0 - exp(-66.0 / 95.0), rest.toDouble(), 1e-3)
        val saccade = GazeCalibrationMath.computeAdaptiveSmoothingAlpha(66L, 0.2f)
        assertEquals(1.0 - exp(-66.0 / (95.0 * 0.42)), saccade.toDouble(), 1e-3)
        assertTrue("saccade alpha ($saccade) must exceed rest alpha ($rest)", saccade > rest + 0.2f)
    }

    // A7: an unfired raise is not completed by a long update gap.
    @Test
    fun `brow raise hold restarts after a long update gap instead of firing instantly`() {
        val detector = BrowRaiseHysteresisDetector(neutralBrowRatio = 0.34f, raisedBrowRatio = 0.42f, holdTargetMs = 400L)
        val (_, enteredFired) = detector.update(0.41f, 1_000L)
        assertFalse(enteredFired)

        // 2 s without updates, then a ratio inside the hysteresis band (>= release 0.3656, < raise 0.388).
        val (resumeProgress, resumeFired) = detector.update(0.37f, 3_000L)
        assertFalse("A stale raise must not fire on the first resumed frame", resumeFired)
        assertEquals(0f, resumeProgress, 1e-4f)

        val (_, firedEarly) = detector.update(0.41f, 3_200L)
        assertFalse(firedEarly)
        val (_, firedAfterFullHold) = detector.update(0.41f, 3_420L)
        assertTrue("A fresh 400 ms hold after the gap must fire", firedAfterFullHold)
    }

    // A7: callers that skip updates can cancel an unfired raise; a fired raise stays disarmed.
    @Test
    fun `cancelPendingRaise forces a fresh full hold and keeps a fired raise disarmed`() {
        val detector = BrowRaiseHysteresisDetector()
        detector.update(0.41f, 1_000L)
        detector.cancelPendingRaise()

        val (reEnterProgress, reEnterFired) = detector.update(0.41f, 1_450L)
        assertFalse(reEnterFired)
        assertEquals(0.08f, reEnterProgress, 1e-4f)
        val (_, firedAt400) = detector.update(0.41f, 1_860L)
        assertTrue(firedAt400)

        detector.cancelPendingRaise()
        val (_, refired) = detector.update(0.41f, 1_950L)
        assertFalse("A raise that already fired must not fire again before release", refired)
    }

    // A117 / B64: one shared formula for the brow thresholds the HUD displays and the detector uses.
    @Test
    fun `brow threshold helpers match the detector thresholds`() {
        val detector = BrowRaiseHysteresisDetector(neutralBrowRatio = 0.34f, raisedBrowRatio = 0.42f)
        assertEquals(0.388f, BrowRaiseHysteresisDetector.raiseThresholdFor(0.34f, 0.42f), 1e-4f)
        assertEquals(0.3656f, BrowRaiseHysteresisDetector.releaseThresholdFor(0.34f, 0.42f), 1e-4f)
        assertEquals(detector.raiseThreshold, BrowRaiseHysteresisDetector.raiseThresholdFor(0.34f, 0.42f), 0f)
        assertEquals(detector.releaseThreshold, BrowRaiseHysteresisDetector.releaseThresholdFor(0.34f, 0.42f), 0f)
    }

    // A12: a blink seen in a single closed frame at 15 FPS is a natural blink, not dropped.
    @Test
    fun `single closed frame at 15 fps is counted as a natural blink`() {
        val detector = BlinkHysteresisDetector()
        var t = 10_000L
        repeat(10) { t += 66L; detector.update(0.9f, t) }
        t += 66L
        detector.update(0.2f, t)
        t += 66L
        val (_, event) = detector.update(0.9f, t)
        assertNotNull("A 66 ms observed closure at 15 FPS must be counted", event)
        assertEquals(BlinkType.NATURAL, event?.type)
    }

    // A12: at 30 FPS a single-frame dip is still noise, while a two-frame closure counts.
    @Test
    fun `at 30 fps a single closed frame is rejected and two closed frames count`() {
        val detector = BlinkHysteresisDetector()
        var t = 10_000L
        repeat(30) { t += 33L; detector.update(0.9f, t) }
        t += 33L
        detector.update(0.2f, t)
        t += 33L
        val (_, singleFrame) = detector.update(0.9f, t)
        assertNull(singleFrame)

        repeat(5) { t += 33L; detector.update(0.9f, t) }
        t += 33L
        detector.update(0.2f, t)
        t += 33L
        detector.update(0.2f, t)
        t += 33L
        val (_, twoFrame) = detector.update(0.9f, t)
        assertEquals(BlinkType.NATURAL, twoFrame?.type)
    }

    // A297: a face-loss gap ends the fixation instead of being counted as dwell.
    @Test
    fun `fixation clusters break across sample gaps`() {
        val samples = mutableListOf<Triple<Float, Float, Long>>()
        for (i in 0 until 16) samples.add(Triple(0.5f, 0.5f, i * 33L))
        val resumeTs = 20_495L
        for (i in 0 until 16) samples.add(Triple(0.5f, 0.5f, resumeTs + i * 33L))

        val clusters = GazeCalibrationMath.clusterFixations(samples)
        assertEquals(2, clusters.size)
        clusters.forEach { assertEquals(15 * 33L, it.dwellMs) }
    }

    // A297: callers can get every cluster; the default still caps at 40 for display.
    @Test
    fun `clusterFixations can return more than 40 clusters when asked`() {
        val samples = mutableListOf<Triple<Float, Float, Long>>()
        var t = 0L
        for (c in 0 until 60) {
            val p = if (c % 2 == 0) 0.2f else 0.8f
            repeat(8) {
                samples.add(Triple(p, p, t))
                t += 33L
            }
        }
        assertEquals(60, GazeCalibrationMath.clusterFixations(samples, maxClusters = Int.MAX_VALUE).size)
        assertEquals(40, GazeCalibrationMath.clusterFixations(samples).size)
    }

    // A18: a long gap re-initializes the filters from the current sample instead of a stale state.
    @Test
    fun `filters re-initialize after a stale gap`() {
        var t = 1_000L
        val oneEuro2D = OneEuroFilter2D()
        val oneEuro1D = OneEuroFilter()
        val kalman = GazeKalmanFilter2D()
        repeat(30) {
            t += 33L
            oneEuro2D.filter(0.3, 0.3, t)
            oneEuro1D.filter(0.3, t)
            kalman.filter(0.3, 0.3, t)
        }
        val resume = t + 60_000L
        val (ex, ey) = oneEuro2D.filter(0.7, 0.7, resume)
        assertEquals(0.7, ex, 1e-12)
        assertEquals(0.7, ey, 1e-12)
        assertEquals(0.7, oneEuro1D.filter(0.7, resume), 1e-12)
        val (kx, ky) = kalman.filter(0.7, 0.7, resume)
        assertEquals(0.7, kx, 1e-12)
        assertEquals(0.7, ky, 1e-12)
    }

    // A18: timestamps that go backwards re-initialize instead of producing a 1 ms derivative spike.
    @Test
    fun `filters re-initialize when timestamps go backwards`() {
        val oneEuro2D = OneEuroFilter2D()
        oneEuro2D.filter(0.3, 0.3, 5_000L)
        oneEuro2D.filter(0.3, 0.3, 5_033L)
        val (x, y) = oneEuro2D.filter(0.7, 0.7, 4_000L)
        assertEquals(0.7, x, 1e-12)
        assertEquals(0.7, y, 1e-12)

        val kalman = GazeKalmanFilter2D()
        kalman.filter(0.3, 0.3, 5_000L)
        kalman.filter(0.3, 0.3, 5_033L)
        assertEquals(0.7, kalman.filter(0.7, 0.7, 4_000L).first, 1e-12)
    }

    // A16 / A293: the default Kalman tuning no longer needs ~1.65 s to reach 90% of a step.
    @Test
    fun `kalman default tuning reaches 90 percent of a step within 400 ms after warm up`() {
        val kalman = GazeKalmanFilter2D()
        var t = 1_000L
        repeat(600) { t += 33L; kalman.filter(0.3, 0.3, t) }

        var elapsedMs = 0L
        var x = 0.3
        while (x < 0.66 && elapsedMs < 5_000L) {
            t += 33L
            elapsedMs += 33L
            x = kalman.filter(0.7, 0.7, t).first
        }
        assertTrue("90% rise took $elapsedMs ms, expected <= 400 ms", elapsedMs <= 400L)
    }
}
