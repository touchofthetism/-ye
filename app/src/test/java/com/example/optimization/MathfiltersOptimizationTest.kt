package com.example.optimization

import com.example.data.model.BlinkClassificationEvent
import com.example.data.model.BlinkType
import com.example.data.model.DEFAULT_AFFINE_MATRIX_2X6
import com.example.data.model.FixationClusterPoint
import com.example.data.model.GazeFeatureVector
import com.example.data.model.ValidationMetricsResult
import com.example.domain.BlinkHysteresisDetector
import com.example.domain.BrowRaiseHysteresisDetector
import com.example.domain.GazeCalibrationMath
import com.example.domain.GazeKalmanFilter2D
import com.example.domain.OneEuroFilter
import com.example.domain.OneEuroFilter2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.LinkedList
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Equivalence tests for the allocation-free rewrite of GazeCalibrationMath.kt and OneEuroFilter.kt.
 *
 * The Frozen* declarations at the bottom are verbatim copies of the pre-optimization implementations.
 * Every optimized result must be bit-identical to them: Pair, List and data-class equality compare the
 * Float/Double bit patterns (so -0.0 vs 0.0 differ and NaN equals NaN), and scalar doubles are compared
 * through toBits().
 */
class MathfiltersOptimizationTest {

    // ---------------------------------------------------------------------------------------------
    // GazeCalibrationMath.projectGaze / projectGazeInto
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `projectGaze and projectGazeInto match the frozen reference bit for bit`() {
        val rnd = Random(101)
        val out = FloatArray(2)
        repeat(30_000) { iteration ->
            val feature = randomFeature(rnd)
            val matrix = randomMatrix(rnd)
            val baseline = randomBaseline(rnd)
            val expected = FrozenGazeCalibrationMath.projectGaze(feature, matrix, baseline)
            val actual = GazeCalibrationMath.projectGaze(feature, matrix, baseline)
            assertEquals("projectGaze #$iteration", expected, actual)

            assertSame(out, GazeCalibrationMath.projectGazeInto(feature, matrix, baseline, out))
            assertEquals("projectGazeInto x #$iteration", expected.first.toBits(), out[0].toBits())
            assertEquals("projectGazeInto y #$iteration", expected.second.toBits(), out[1].toBits())
        }
        // Default baseline argument
        val feature = GazeFeatureVector(gx = 0.08, gy = -0.04, yaw = 3.0, pitch = -2.0, roll = 0.5, eyeDistancePx = 151.0, timestampMs = 0L)
        assertEquals(
            FrozenGazeCalibrationMath.projectGaze(feature, DEFAULT_AFFINE_MATRIX_2X6),
            GazeCalibrationMath.projectGaze(feature, DEFAULT_AFFINE_MATRIX_2X6)
        )
    }

    // ---------------------------------------------------------------------------------------------
    // GazeCalibrationMath.fitRidgeAffineMatrix2x6
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `fitRidgeAffineMatrix2x6 matches the frozen reference on random calibration sets`() {
        val rnd = Random(202)
        val sizes = (0..14).toList() + List(140) { rnd.nextInt(15, 260) } + listOf(500, 1000, 1500)
        for ((caseIndex, n) in sizes.withIndex()) {
            val samples = randomFitSamples(rnd, n)
            val baseline = if (rnd.nextInt(6) == 0) randomBaseline(rnd) else 148.0 + rnd.nextDouble(-20.0, 20.0)
            val lambda = when (rnd.nextInt(10)) {
                0 -> 0.0
                1 -> -rnd.nextDouble(0.0, 0.05)
                2 -> rnd.nextDouble(0.0, 5.0)
                else -> 2.5e-2
            }
            val expected = FrozenGazeCalibrationMath.fitRidgeAffineMatrix2x6(samples, baseline, lambda)
            val actual = GazeCalibrationMath.fitRidgeAffineMatrix2x6(samples, baseline, lambda)
            assertEquals("fit case $caseIndex (n=$n, lambda=$lambda)", expected, actual)
            // A list without constant-time get takes the same path
            if (caseIndex % 10 == 0) {
                assertEquals("fit case $caseIndex as LinkedList", expected, GazeCalibrationMath.fitRidgeAffineMatrix2x6(LinkedList(samples), baseline, lambda))
            }
        }
        // Default arguments
        val samples = randomFitSamples(rnd, 125)
        assertEquals(FrozenGazeCalibrationMath.fitRidgeAffineMatrix2x6(samples), GazeCalibrationMath.fitRidgeAffineMatrix2x6(samples))
    }

    @Test
    fun `fitRidgeAffineMatrix2x6 matches the frozen reference on degenerate and edge-case sets`() {
        val rnd = Random(203)
        val target = Pair(0.5f, 0.5f)
        val cases = mutableListOf<List<Pair<GazeFeatureVector, Pair<Float, Float>>>>()
        // Identical features everywhere: MAD 0 is floored at 0.015; with lambda 0 the system is singular
        cases += List(40) { Pair(GazeFeatureVector(timestampMs = 0L), Pair(0.14f, 0.16f)) }
        // One target only, five samples (median of an odd and an even count)
        cases += List(5) { k -> Pair(GazeFeatureVector(gx = k * 0.01, gy = -k * 0.01, timestampMs = 0L), target) }
        cases += List(6) { k -> Pair(GazeFeatureVector(gx = k * 0.01, gy = -k * 0.01, timestampMs = 0L), target) }
        // Every frame closed: the open filter falls back to the whole group
        cases += List(30) { k -> Pair(GazeFeatureVector(gx = k * 0.001, leftEyeOpenProb = 0.1f, rightEyeOpenProb = 0.2f, timestampMs = 0L), Pair((k % 5) * 0.2f, 0.4f)) }
        // Signed zeros as distinct targets, and -0.0 / 0.0 / NaN feature values in the medians
        cases += List(60) { k ->
            val gx = when (k % 4) { 0 -> -0.0; 1 -> 0.0; 2 -> 0.02; else -> -0.02 }
            Pair(GazeFeatureVector(gx = gx, gy = if (k % 7 == 0) Double.NaN else 0.01 * (k % 3), timestampMs = 0L), Pair(if (k % 2 == 0) -0.0f else 0.0f, 0.5f))
        }
        // NaN target coordinates group together (Pair equality uses Float.equals)
        cases += List(25) { k -> Pair(GazeFeatureVector(gx = k * 0.003, timestampMs = 0L), Pair(Float.NaN, if (k % 2 == 0) Float.NaN else 0.3f)) }
        // Many distinct targets (more than the initial group capacity), interleaved
        cases += List(400) { k -> Pair(GazeFeatureVector(gx = rnd.nextDouble(-0.1, 0.1), gy = rnd.nextDouble(-0.1, 0.1), timestampMs = 0L), Pair((k % 37) / 37f, (k % 11) / 11f)) }
        // Infinite features
        cases += List(20) { k -> Pair(GazeFeatureVector(gx = if (k == 3) Double.POSITIVE_INFINITY else k * 0.01, yaw = if (k == 5) Double.NEGATIVE_INFINITY else 0.0, timestampMs = 0L), Pair((k % 5) * 0.2f, 0.5f)) }
        // Single-eye mode with asymmetric confidence picks one eye's openness
        cases += List(50) { k ->
            Pair(
                GazeFeatureVector(
                    gx = rnd.nextDouble(-0.05, 0.05), isSingleEyeMode = true,
                    leftEyeConfidence = if (k % 2 == 0) 0.9f else 0.1f, rightEyeConfidence = 0.5f,
                    leftEyeOpenProb = if (k % 3 == 0) 0.2f else 0.9f, rightEyeOpenProb = if (k % 5 == 0) 0.1f else 0.8f,
                    timestampMs = 0L
                ),
                Pair((k % 5) * 0.2f, (k % 2) * 0.5f)
            )
        }
        for ((index, samples) in cases.withIndex()) {
            for (lambda in doubleArrayOf(2.5e-2, 0.0, 1e-12)) {
                assertEquals(
                    "edge case $index lambda $lambda",
                    FrozenGazeCalibrationMath.fitRidgeAffineMatrix2x6(samples, 148.0, lambda),
                    GazeCalibrationMath.fitRidgeAffineMatrix2x6(samples, 148.0, lambda)
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // GazeCalibrationMath.evaluateHeldOutValidation
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `evaluateHeldOutValidation matches the frozen reference`() {
        val rnd = Random(303)
        val fitted = GazeCalibrationMath.fitRidgeAffineMatrix2x6(randomFitSamples(rnd, 125))
        repeat(600) { iteration ->
            val n = when (iteration % 6) {
                0 -> rnd.nextInt(0, 4)
                1 -> rnd.nextInt(4, 20)
                else -> rnd.nextInt(20, 300)
            }
            val samples = randomValidationSamples(rnd, n)
            val matrix = if (rnd.nextBoolean()) fitted else randomMatrix(rnd)
            val baseline = if (rnd.nextInt(5) == 0) randomBaseline(rnd) else 148.0
            val widthMm = randomScreenMm(rnd, 71.3)
            val heightMm = randomScreenMm(rnd, 158.4)
            val expected = FrozenGazeCalibrationMath.evaluateHeldOutValidation(matrix, samples, baseline, widthMm, heightMm)
            val actual = GazeCalibrationMath.evaluateHeldOutValidation(matrix, samples, baseline, widthMm, heightMm)
            assertEquals("validation #$iteration (n=$n)", expected, actual)
        }
        // Default screen size arguments, a NaN feature and an off-screen target
        val neutral = GazeFeatureVector(timestampMs = 0L)
        val special = listOf(
            Pair(neutral, Pair(0.5f, 0.5f)),
            Pair(neutral.copy(gx = Double.NaN), Pair(0.3f, 0.32f)),
            Pair(neutral, Pair(1.5f, 1.5f)),
            Pair(neutral, Pair(0.3f, 0.32f))
        )
        for (k in 1..special.size) {
            val subset = special.take(k)
            assertEquals(
                FrozenGazeCalibrationMath.evaluateHeldOutValidation(DEFAULT_AFFINE_MATRIX_2X6, subset, 148.0),
                GazeCalibrationMath.evaluateHeldOutValidation(DEFAULT_AFFINE_MATRIX_2X6, subset, 148.0)
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // GazeCalibrationMath.clusterFixations
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `clusterFixations matches the frozen reference on random gaze streams`() {
        val rnd = Random(404)
        repeat(1_500) { iteration ->
            val stream = randomGazeStream(rnd, if (iteration % 50 == 0) 3_000 else rnd.nextInt(0, 400))
            val threshold = when (rnd.nextInt(14)) {
                0 -> 0f
                1 -> -0.1f
                2 -> Float.NaN
                3 -> Float.POSITIVE_INFINITY
                4 -> Float.MIN_VALUE
                5 -> 1e-35f
                6 -> Float.MAX_VALUE
                7, 8 -> rnd.nextDouble(0.001, 0.5).toFloat()
                else -> 0.12f
            }
            val minDwell = when (rnd.nextInt(5)) { 0 -> 0L; 1 -> -5L; 2 -> rnd.nextLong(0L, 2_000L); else -> 180L }
            val maxGap = when (rnd.nextInt(5)) { 0 -> 0L; 1 -> rnd.nextLong(1L, 2_000L); else -> 300L }
            val maxClusters = when (rnd.nextInt(6)) { 0 -> 0; 1 -> -3; 2 -> Int.MAX_VALUE; 3 -> rnd.nextInt(1, 10); else -> 40 }
            val expected = FrozenGazeCalibrationMath.clusterFixations(stream, threshold, minDwell, maxGap, maxClusters)
            val actual = GazeCalibrationMath.clusterFixations(stream, threshold, minDwell, maxGap, maxClusters)
            assertEquals("clusters #$iteration (threshold=$threshold)", expected, actual)
        }
        // Default arguments
        val stream = randomGazeStream(rnd, 3_000)
        assertEquals(FrozenGazeCalibrationMath.clusterFixations(stream), GazeCalibrationMath.clusterFixations(stream))
    }

    @Test
    fun `clusterFixations decides distances at the threshold exactly like hypot`() {
        val rnd = Random(405)
        var joined = 0
        var split = 0
        repeat(4_000) { iteration ->
            val threshold = if (iteration % 3 == 0) 0.12f else rnd.nextDouble(0.01, 0.4).toFloat()
            // A displacement whose Float hypot lands within a few ulps of the threshold
            val theta = rnd.nextDouble(0.0, 2.0 * PI)
            val dx = (threshold * cos(theta)).toFloat()
            var dy = sqrt(maxOf(0.0, threshold.toDouble() * threshold - dx.toDouble() * dx)).toFloat() * (if (sin(theta) < 0) -1f else 1f)
            repeat(rnd.nextInt(-6, 7).let { abs(it) }) { dy = if (rnd.nextBoolean()) Math.nextUp(dy) else Math.nextDown(dy) }
            // Starting at the origin keeps x - centroid exact, so (dx, dy) is the measured displacement.
            // Joining yields one 200 ms cluster; splitting yields two zero-dwell (dropped) clusters.
            val stream = listOf(Triple(0f, 0f, 0L), Triple(dx, dy, 200L))
            val expected = FrozenGazeCalibrationMath.clusterFixations(stream, threshold)
            assertEquals("boundary #$iteration (dx=$dx, dy=$dy, t=$threshold)", expected, GazeCalibrationMath.clusterFixations(stream, threshold))
            if (expected.isEmpty()) split++ else joined++
        }
        assertTrue("boundary cases must land on both sides ($joined joined, $split split)", joined > 200 && split > 200)
    }

    // ---------------------------------------------------------------------------------------------
    // OneEuroFilter / OneEuroFilter2D / GazeKalmanFilter2D
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `OneEuroFilter2D filter and filterInto match the frozen reference`() {
        val rnd = Random(505)
        repeat(40) { run ->
            val params = doubleArrayOf(rnd.nextDouble(0.05, 3.0), rnd.nextDouble(0.0, 3.0), rnd.nextDouble(0.1, 3.0))
            val frozen = FrozenOneEuroFilter2D(params[0], params[1], params[2])
            val viaPair = OneEuroFilter2D(params[0], params[1], params[2])
            val viaInto = OneEuroFilter2D(params[0], params[1], params[2])
            val out = DoubleArray(2)
            var t = rnd.nextLong(0L, 10_000L)
            var x = 0.5
            var y = 0.5
            assertEquals(frozen.lastPosition, viaPair.lastPosition)
            repeat(1_500) { step ->
                t += randomDt(rnd)
                x = randomWalk(rnd, x)
                y = randomWalk(rnd, y)
                when (rnd.nextInt(200)) {
                    0 -> { frozen.reset(); viaPair.reset(); viaInto.reset() }
                    1 -> {
                        val c = rnd.nextDouble(0.05, 3.0)
                        frozen.minCutoff = c; viaPair.minCutoff = c; viaInto.minCutoff = c
                        val b = rnd.nextDouble(0.0, 3.0)
                        frozen.beta = b; viaPair.beta = b; viaInto.beta = b
                        val d = rnd.nextDouble(0.0, 3.0)
                        frozen.dCutoff = d; viaPair.dCutoff = d; viaInto.dCutoff = d
                    }
                }
                val expected = frozen.filter(x, y, t)
                assertEquals("2D run $run step $step", expected, viaPair.filter(x, y, t))
                assertSame(out, viaInto.filterInto(x, y, t, out))
                assertEquals("2D into x run $run step $step", expected.first.toBits(), out[0].toBits())
                assertEquals("2D into y run $run step $step", expected.second.toBits(), out[1].toBits())
                assertEquals(frozen.lastPosition, viaPair.lastPosition)
                assertEquals(frozen.lastPosition, viaInto.lastPosition)
            }
            frozen.reset(); viaPair.reset()
            assertEquals(frozen.lastPosition, viaPair.lastPosition)
        }
        // The live pupil filter configuration and the screen filter configuration
        for (params in listOf(doubleArrayOf(0.90, 0.040, 1.0), doubleArrayOf(0.90, 1.2, 1.0))) {
            val frozen = FrozenOneEuroFilter2D(params[0], params[1], params[2])
            val actual = OneEuroFilter2D(params[0], params[1], params[2])
            var t = 0L
            repeat(4_096) {
                t += 33L
                val x = 0.5 + rnd.nextDouble(-0.02, 0.02)
                val y = 0.5 + rnd.nextDouble(-0.02, 0.02)
                assertEquals(frozen.filter(x, y, t), actual.filter(x, y, t))
            }
        }
    }

    @Test
    fun `OneEuroFilter 1D matches the frozen reference`() {
        val rnd = Random(506)
        repeat(40) { run ->
            val frozen = FrozenOneEuroFilter(rnd.nextDouble(0.05, 3.0), rnd.nextDouble(0.0, 3.0), rnd.nextDouble(0.1, 3.0))
            val actual = OneEuroFilter(frozen.minCutoff, frozen.beta, frozen.dCutoff)
            var t = rnd.nextLong(0L, 10_000L)
            var x = 0.5
            assertEquals(frozen.lastValue, actual.lastValue)
            repeat(1_500) { step ->
                t += randomDt(rnd)
                x = randomWalk(rnd, x)
                if (rnd.nextInt(200) == 0) { frozen.reset(); actual.reset() }
                if (rnd.nextInt(300) == 0) {
                    frozen.beta = rnd.nextDouble(0.0, 3.0); actual.beta = frozen.beta
                }
                assertEquals("1D run $run step $step", frozen.filter(x, t).toBits(), actual.filter(x, t).toBits())
                assertEquals(frozen.lastValue, actual.lastValue)
            }
            frozen.reset(); actual.reset()
            assertEquals(frozen.lastValue, actual.lastValue)
        }
        assertEquals(FrozenOneEuroFilter.computeAlpha(0.033, 0.85).toBits(), OneEuroFilter.computeAlpha(0.033, 0.85).toBits())
    }

    @Test
    fun `GazeKalmanFilter2D filter and filterInto match the frozen reference`() {
        val rnd = Random(507)
        repeat(40) { run ->
            val q = rnd.nextDouble(1e-4, 5.0)
            val r = rnd.nextDouble(1e-4, 0.1)
            val frozen = FrozenGazeKalmanFilter2D(q, r)
            val viaPair = GazeKalmanFilter2D(q, r)
            val viaInto = GazeKalmanFilter2D(q, r)
            val out = DoubleArray(2)
            var t = rnd.nextLong(0L, 10_000L)
            var x = 0.5
            var y = 0.5
            repeat(1_500) { step ->
                t += randomDt(rnd)
                x = randomWalk(rnd, x)
                y = randomWalk(rnd, y)
                when (rnd.nextInt(200)) {
                    0 -> { frozen.reset(); viaPair.reset(); viaInto.reset() }
                    1 -> {
                        val nq = rnd.nextDouble(1e-4, 5.0)
                        frozen.processNoiseQ = nq; viaPair.processNoiseQ = nq; viaInto.processNoiseQ = nq
                    }
                }
                val expected = frozen.filter(x, y, t)
                assertEquals("kalman run $run step $step", expected, viaPair.filter(x, y, t))
                assertSame(out, viaInto.filterInto(x, y, t, out))
                assertEquals("kalman into x run $run step $step", expected.first.toBits(), out[0].toBits())
                assertEquals("kalman into y run $run step $step", expected.second.toBits(), out[1].toBits())
                assertEquals(frozen.state, viaPair.state)
                assertEquals(frozen.velocity, viaPair.velocity)
                assertEquals(frozen.state, viaInto.state)
                assertEquals(frozen.velocity, viaInto.velocity)
            }
            frozen.reset(); viaPair.reset()
            assertEquals(frozen.state, viaPair.state)
            assertEquals(frozen.velocity, viaPair.velocity)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // BlinkHysteresisDetector / BrowRaiseHysteresisDetector
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `BlinkHysteresisDetector matches the frozen reference`() {
        val rnd = Random(606)
        repeat(60) { run ->
            val frozen = FrozenBlinkHysteresisDetector()
            val actual = BlinkHysteresisDetector()
            var t = rnd.nextLong(0L, 5_000L)
            var closed = false
            repeat(2_000) { step ->
                t += when (rnd.nextInt(30)) { 0 -> 0L; 1 -> -rnd.nextLong(1L, 100L); 2 -> rnd.nextLong(250L, 3_000L); else -> rnd.nextLong(16L, 80L) }
                if (rnd.nextInt(14) == 0) closed = !closed
                val prob = when (rnd.nextInt(60)) {
                    0 -> Float.NaN
                    1 -> rnd.nextFloat()
                    else -> if (closed) rnd.nextFloat() * 0.4f else 0.7f + rnd.nextFloat() * 0.3f
                }
                when (rnd.nextInt(400)) {
                    0 -> { frozen.reset(); actual.reset() }
                    1 -> {
                        val minMs = if (rnd.nextInt(4) == 0) 0L else rnd.nextLong(100L, 600L)
                        frozen.intentionalMinMs = minMs; actual.intentionalMinMs = minMs
                        val maxMs = rnd.nextLong(300L, 1_500L)
                        frozen.maxCommandMs = maxMs; actual.maxCommandMs = maxMs
                        val nat = rnd.nextLong(0L, 200L)
                        frozen.naturalMinMs = nat; actual.naturalMinMs = nat
                        val close = rnd.nextFloat() * 0.6f
                        frozen.closeThreshold = close; actual.closeThreshold = close
                        val open = close + rnd.nextFloat() * 0.4f
                        frozen.openThreshold = open; actual.openThreshold = open
                    }
                }
                assertEquals("blink run $run step $step", frozen.update(prob, t), actual.update(prob, t))
            }
        }
    }

    @Test
    fun `BrowRaiseHysteresisDetector matches the frozen reference`() {
        val rnd = Random(607)
        repeat(60) { run ->
            val frozen = FrozenBrowRaiseHysteresisDetector()
            val actual = BrowRaiseHysteresisDetector()
            var t = rnd.nextLong(0L, 5_000L)
            var raised = false
            repeat(2_000) { step ->
                t += when (rnd.nextInt(40)) { 0 -> 0L; 1 -> -rnd.nextLong(1L, 100L); 2 -> rnd.nextLong(900L, 3_000L); else -> rnd.nextLong(16L, 80L) }
                if (rnd.nextInt(18) == 0) raised = !raised
                val ratio = when (rnd.nextInt(80)) {
                    0 -> Float.NaN
                    1 -> rnd.nextFloat()
                    else -> if (raised) 0.36f + rnd.nextFloat() * 0.1f else 0.30f + rnd.nextFloat() * 0.08f
                }
                when (rnd.nextInt(300)) {
                    0 -> { frozen.reset(); actual.reset() }
                    1 -> { frozen.cancelPendingRaise(); actual.cancelPendingRaise() }
                    2 -> {
                        val neutral = 0.25f + rnd.nextFloat() * 0.15f
                        frozen.neutralBrowRatio = neutral; actual.neutralBrowRatio = neutral
                        val raisedRatio = neutral + rnd.nextFloat() * 0.15f - 0.02f
                        frozen.raisedBrowRatio = raisedRatio; actual.raisedBrowRatio = raisedRatio
                        val hold = if (rnd.nextBoolean()) rnd.nextLong(0L, 250L) else rnd.nextLong(250L, 900L)
                        frozen.holdTargetMs = hold; actual.holdTargetMs = hold
                    }
                }
                assertEquals("brow run $run step $step", frozen.update(ratio, t), actual.update(ratio, t))
                assertEquals(frozen.raiseThreshold.toBits(), actual.raiseThreshold.toBits())
                assertEquals(frozen.releaseThreshold.toBits(), actual.releaseThreshold.toBits())
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Random inputs
    // ---------------------------------------------------------------------------------------------

    private fun special(rnd: Random): Double = when (rnd.nextInt(6)) {
        0 -> Double.NaN
        1 -> Double.POSITIVE_INFINITY
        2 -> Double.NEGATIVE_INFINITY
        3 -> -0.0
        4 -> 0.0
        else -> rnd.nextDouble(-1e6, 1e6)
    }

    /** Typical values, values beyond every clamp, and (rarely) NaN / infinities / signed zeros. */
    private fun randomFeature(rnd: Random): GazeFeatureVector {
        fun value(typical: Double): Double = when (rnd.nextInt(20)) {
            0 -> special(rnd)
            1, 2 -> rnd.nextDouble(-typical * 6, typical * 6)
            else -> rnd.nextDouble(-typical, typical)
        }
        return GazeFeatureVector(
            gx = value(0.25),
            gy = value(0.20),
            yaw = value(20.0),
            pitch = value(15.0),
            roll = value(15.0),
            eyeDistancePx = if (rnd.nextInt(25) == 0) special(rnd) else rnd.nextDouble(40.0, 320.0),
            timestampMs = 0L
        )
    }

    private fun randomMatrix(rnd: Random): List<Double> = when (rnd.nextInt(8)) {
        0 -> DEFAULT_AFFINE_MATRIX_2X6
        1 -> List(listOf(0, 6, 11, 13).random(rnd)) { rnd.nextDouble(-1.0, 1.0) } // wrong size: default matrix
        2 -> List(12) { if (rnd.nextInt(6) == 0) special(rnd) else rnd.nextDouble(-5.0, 5.0) }
        else -> List(12) { j -> DEFAULT_AFFINE_MATRIX_2X6[j] + rnd.nextDouble(-0.2, 0.2) }
    }

    private fun randomBaseline(rnd: Random): Double = when (rnd.nextInt(8)) {
        0 -> special(rnd)
        1 -> rnd.nextDouble(-50.0, 30.0) // below the 30 px floor
        2 -> 148.0
        else -> rnd.nextDouble(30.0, 320.0)
    }

    private fun randomScreenMm(rnd: Random, typical: Double): Double = when (rnd.nextInt(10)) {
        0 -> special(rnd)
        1 -> 0.0
        2 -> -typical
        else -> typical * rnd.nextDouble(0.5, 2.0)
    }

    /** A pool of targets; [freshInstances] hands out equal but distinct Pair objects. */
    private fun targetPool(rnd: Random): List<Pair<Float, Float>> {
        val count = when (rnd.nextInt(6)) { 0 -> 1; 1 -> rnd.nextInt(10, 20); else -> 5 }
        return List(count) { i ->
            if (count == 5) {
                val t = GazeCalibrationMath.FIVE_POINT_CALIBRATION_TARGETS[i]
                Pair(t.normX, t.normY)
            } else {
                Pair(rnd.nextFloat(), rnd.nextFloat())
            }
        }
    }

    private fun randomFitSamples(rnd: Random, n: Int): List<Pair<GazeFeatureVector, Pair<Float, Float>>> {
        val pool = targetPool(rnd)
        val interleave = rnd.nextInt(3) == 0
        val freshInstances = rnd.nextBoolean()
        val closedRate = rnd.nextInt(0, 4) // 0: none, else roughly 1 in (8 / rate) frames closed
        val outlierRate = rnd.nextInt(0, 4)
        val noise = rnd.nextDouble(0.0, 0.03)
        return List(n) { i ->
            val target = if (interleave) {
                val p = pool[rnd.nextInt(pool.size)]
                if (freshInstances) Pair(p.first, p.second) else p
            } else {
                val p = pool[(i * pool.size / n.coerceAtLeast(1)).coerceIn(0, pool.size - 1)]
                if (freshInstances) Pair(p.first, p.second) else p
            }
            val outlier = outlierRate > 0 && rnd.nextInt(24 / outlierRate) == 0
            val closed = closedRate > 0 && rnd.nextInt(8 / closedRate + 1) == 0
            val tx = target.first.toDouble()
            val ty = target.second.toDouble()
            val feature = GazeFeatureVector(
                gx = (tx - 0.5) * 0.40 + rnd.nextDouble(-noise, noise) + if (outlier) rnd.nextDouble(-0.3, 0.3) else 0.0,
                gy = (ty - 0.5) * 0.35 + rnd.nextDouble(-noise, noise) + if (outlier) rnd.nextDouble(-0.3, 0.3) else 0.0,
                yaw = (0.5 - tx) * 12.0 + rnd.nextDouble(-0.5, 0.5),
                pitch = (0.5 - ty) * 10.0 + rnd.nextDouble(-0.5, 0.5),
                roll = rnd.nextDouble(-1.0, 1.0),
                eyeDistancePx = 148.0 + rnd.nextDouble(-6.0, 6.0),
                leftEyeOpenProb = if (closed) rnd.nextFloat() * 0.4f else 0.6f + rnd.nextFloat() * 0.4f,
                rightEyeOpenProb = if (closed) rnd.nextFloat() * 0.4f else 0.6f + rnd.nextFloat() * 0.4f,
                isSingleEyeMode = rnd.nextInt(10) == 0,
                leftEyeConfidence = rnd.nextFloat(),
                rightEyeConfidence = rnd.nextFloat(),
                faceDetected = true,
                timestampMs = 1_000L + i * 33L
            )
            Pair(feature, target)
        }
    }

    private fun randomValidationSamples(rnd: Random, n: Int): List<Pair<GazeFeatureVector, Pair<Float, Float>>> {
        val pool = GazeCalibrationMath.HELD_OUT_VALIDATION_TARGETS.map { Pair(it.normX, it.normY) } +
            (if (rnd.nextInt(4) == 0) listOf(Pair(-0.0f, 0.5f), Pair(0.0f, 0.5f), Pair(1.4f, -0.2f)) else emptyList())
        val freshInstances = rnd.nextBoolean()
        val interleave = rnd.nextBoolean()
        return List(n) { i ->
            val p = if (interleave) pool[rnd.nextInt(pool.size)] else pool[(i * pool.size / n.coerceAtLeast(1)).coerceIn(0, pool.size - 1)]
            val target = if (freshInstances) Pair(p.first, p.second) else p
            Pair(if (rnd.nextInt(40) == 0) randomFeature(rnd) else featureLookingAt(rnd, target), target)
        }
    }

    private fun featureLookingAt(rnd: Random, target: Pair<Float, Float>): GazeFeatureVector = GazeFeatureVector(
        gx = (target.first - 0.5) * 0.40 + rnd.nextDouble(-0.02, 0.02),
        gy = (target.second - 0.5) * 0.35 + rnd.nextDouble(-0.02, 0.02),
        yaw = (0.5 - target.first) * 12.0 + rnd.nextDouble(-1.0, 1.0),
        pitch = (0.5 - target.second) * 10.0 + rnd.nextDouble(-1.0, 1.0),
        roll = rnd.nextDouble(-2.0, 2.0),
        eyeDistancePx = 148.0 + rnd.nextDouble(-5.0, 5.0),
        timestampMs = 0L
    )

    /** Fixations with jitter, saccades, face-loss gaps, backwards timestamps and rare non-finite points. */
    private fun randomGazeStream(rnd: Random, n: Int): List<Triple<Float, Float, Long>> {
        val samples = ArrayList<Triple<Float, Float, Long>>(n)
        var t = rnd.nextLong(0L, 100_000L)
        var cx = rnd.nextFloat()
        var cy = rnd.nextFloat()
        val jitter = rnd.nextDouble(0.0, 0.15).toFloat()
        for (i in 0 until n) {
            if (rnd.nextInt(25) == 0) {
                cx = rnd.nextFloat()
                cy = rnd.nextFloat()
            }
            t += when (rnd.nextInt(40)) {
                0 -> rnd.nextLong(300L, 3_000L)
                1 -> -rnd.nextLong(1L, 200L)
                2 -> 0L
                else -> rnd.nextLong(15L, 70L)
            }
            val x = when (rnd.nextInt(300)) { 0 -> Float.NaN; 1 -> Float.POSITIVE_INFINITY; else -> cx + (rnd.nextFloat() - 0.5f) * jitter }
            val y = when (rnd.nextInt(300)) { 0 -> Float.NaN; 1 -> Float.NEGATIVE_INFINITY; else -> cy + (rnd.nextFloat() - 0.5f) * jitter }
            samples.add(Triple(x, y, t))
        }
        return samples
    }

    private fun randomDt(rnd: Random): Long = when (rnd.nextInt(40)) {
        0 -> 0L
        1 -> -rnd.nextLong(1L, 500L) // backwards: re-initializes
        2 -> 1_001L + rnd.nextLong(0L, 5_000L) // stale gap: re-initializes
        3 -> 1_000L
        4 -> 1L
        else -> rnd.nextLong(10L, 120L)
    }

    private fun randomWalk(rnd: Random, value: Double): Double = when (rnd.nextInt(500)) {
        0 -> Double.NaN
        1 -> -0.0
        else -> if (rnd.nextInt(30) == 0) rnd.nextDouble(0.0, 1.0) else value + rnd.nextDouble(-0.01, 0.01)
    }
}

// =================================================================================================
// Frozen pre-optimization implementations (verbatim copies; do not edit)
// =================================================================================================

private object FrozenGazeCalibrationMath {
    const val PIXEL_10_PRO_XL_WIDTH_MM = 71.3
    const val PIXEL_10_PRO_XL_HEIGHT_MM = 158.4
    const val GRID_3X3_MAX_MEAN_ERROR_PCT = 12.0
    const val GRID_3X3_MAX_WORST_QUADRANT_PCT = 16.5
    const val RECALIBRATION_RECOMMENDED_WORST_QUADRANT_PCT = 24.0
    const val MAX_REPORTED_ERROR_PCT = 100.0

    fun qualifiesFor3x3Grid(meanErrorPct: Double, maxErrorPct: Double): Boolean =
        meanErrorPct.isFinite() && maxErrorPct.isFinite() &&
            meanErrorPct <= GRID_3X3_MAX_MEAN_ERROR_PCT &&
            maxErrorPct <= GRID_3X3_MAX_WORST_QUADRANT_PCT

    fun gridLayoutLabel(use3x3: Boolean): String =
        if (use3x3) "3×3 Validated Deck (9 tiles)" else "2×2 Protective Region Deck (4 tiles)"

    fun projectGaze(
        feature: GazeFeatureVector,
        matrixCoeffs: List<Double>,
        baselineEyeDistPx: Double = 148.0
    ): Pair<Float, Float> {
        val w = if (matrixCoeffs.size == 12) matrixCoeffs else DEFAULT_AFFINE_MATRIX_2X6
        val f = feature.toStandardizedFeatureArray(baselineEyeDistPx)
        var predX = 0.0
        var predY = 0.0
        for (j in 0 until 6) {
            predX += w[j] * f[j]
            predY += w[6 + j] * f[j]
        }
        return Pair(
            predX.toFloat().coerceIn(0.02f, 0.98f),
            predY.toFloat().coerceIn(0.02f, 0.98f)
        )
    }

    fun fitRidgeAffineMatrix2x6(
        samples: List<Pair<GazeFeatureVector, Pair<Float, Float>>>,
        baselineEyeDistPx: Double = 148.0,
        ridgeLambda: Double = 2.5e-2
    ): List<Double> {
        if (samples.size < 5) return DEFAULT_AFFINE_MATRIX_2X6

        val cleanedSamples = trimOutliersByTargetMad(samples)
        val activeSamples = if (cleanedSamples.size >= 5) cleanedSamples else samples

        val dim = 6
        val a = Array(dim) { DoubleArray(dim) }
        val bx = DoubleArray(dim)
        val by = DoubleArray(dim)

        for ((feat, target) in activeSamples) {
            val f = feat.toStandardizedFeatureArray(baselineEyeDistPx)
            val tx = target.first.toDouble()
            val ty = target.second.toDouble()
            for (r in 0 until dim) {
                bx[r] += f[r] * tx
                by[r] += f[r] * ty
                for (c in 0 until dim) {
                    a[r][c] += f[r] * f[c]
                }
            }
        }

        val prior = DEFAULT_AFFINE_MATRIX_2X6
        val sampleCount = activeSamples.size.coerceAtLeast(1)
        val effectiveLambda = ridgeLambda * sampleCount
        for (i in 0 until dim) {
            val reg = when (i) {
                0 -> effectiveLambda * 0.05
                1, 2 -> effectiveLambda * (a[i][i] / sampleCount).coerceIn(0.02, 1.0)
                else -> effectiveLambda
            }
            a[i][i] += reg
            bx[i] += reg * prior[i]
            by[i] += reg * prior[6 + i]
        }

        val wx = solveLinearSystem6x6(a, bx) ?: return DEFAULT_AFFINE_MATRIX_2X6
        val wy = solveLinearSystem6x6(a, by) ?: return DEFAULT_AFFINE_MATRIX_2X6

        val combined = ArrayList<Double>(12)
        for (v in wx) combined.add(v.coerceIn(-5.0, 5.0))
        for (v in wy) combined.add(v.coerceIn(-5.0, 5.0))
        return combined
    }

    private fun trimOutliersByTargetMad(
        samples: List<Pair<GazeFeatureVector, Pair<Float, Float>>>
    ): List<Pair<GazeFeatureVector, Pair<Float, Float>>> {
        val grouped = samples.groupBy { it.second }
        val result = ArrayList<Pair<GazeFeatureVector, Pair<Float, Float>>>(samples.size)

        for ((_, group) in grouped) {
            val openFiltered = group.filter { it.first.combinedEyeOpenProb >= 0.35f }
                .ifEmpty { group }
            if (openFiltered.size <= 3) {
                result.addAll(openFiltered)
                continue
            }
            val sortedGx = openFiltered.map { it.first.gx }.sorted()
            val sortedGy = openFiltered.map { it.first.gy }.sorted()
            val medGx = sortedGx[sortedGx.size / 2]
            val medGy = sortedGy[sortedGy.size / 2]

            val devs = openFiltered.map { hypot(it.first.gx - medGx, it.first.gy - medGy) }.sorted()
            val mad = devs[devs.size / 2].coerceAtLeast(0.015)
            val cutoff = mad * 2.8

            val inliers = openFiltered.filter {
                hypot(it.first.gx - medGx, it.first.gy - medGy) <= cutoff
            }
            result.addAll(if (inliers.size >= 2) inliers else openFiltered)
        }
        return result
    }

    private fun solveLinearSystem6x6(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
        val n = rhs.size
        val aug = Array(n) { r ->
            DoubleArray(n + 1) { c -> if (c < n) matrix[r][c] else rhs[r] }
        }

        for (col in 0 until n) {
            var pivotRow = col
            var maxVal = abs(aug[col][col])
            for (r in (col + 1) until n) {
                if (abs(aug[r][col]) > maxVal) {
                    maxVal = abs(aug[r][col])
                    pivotRow = r
                }
            }
            if (maxVal < 1e-10) return null

            if (pivotRow != col) {
                val tmp = aug[col]
                aug[col] = aug[pivotRow]
                aug[pivotRow] = tmp
            }

            val pivot = aug[col][col]
            for (c in col..n) {
                aug[col][c] /= pivot
            }

            for (r in 0 until n) {
                if (r != col) {
                    val factor = aug[r][col]
                    for (c in col..n) {
                        aug[r][c] -= factor * aug[col][c]
                    }
                }
            }
        }

        return DoubleArray(n) { r -> aug[r][n] }
    }

    fun evaluateHeldOutValidation(
        matrixCoeffs: List<Double>,
        validationSamples: List<Pair<GazeFeatureVector, Pair<Float, Float>>>,
        baselineEyeDistPx: Double,
        screenWidthMm: Double = PIXEL_10_PRO_XL_WIDTH_MM,
        screenHeightMm: Double = PIXEL_10_PRO_XL_HEIGHT_MM
    ): ValidationMetricsResult {
        if (validationSamples.isEmpty()) {
            return ValidationMetricsResult(
                meanErrorPct = Double.NaN,
                maxErrorPct = Double.NaN,
                approxMeanErrorMm = Double.NaN,
                recommendedGridDesc = "Not validated (no samples)"
            )
        }

        val safeWidthMm = if (screenWidthMm.isFinite() && screenWidthMm > 0.0) screenWidthMm else PIXEL_10_PRO_XL_WIDTH_MM
        val safeHeightMm = if (screenHeightMm.isFinite() && screenHeightMm > 0.0) screenHeightMm else PIXEL_10_PRO_XL_HEIGHT_MM
        var totalMmError = 0.0
        var totalNormError = 0.0

        val targetErrors = mutableMapOf<Pair<Float, Float>, MutableList<Double>>()

        for ((feat, actualTarget) in validationSamples) {
            val (predX, predY) = projectGaze(feat, matrixCoeffs, baselineEyeDistPx)
            val dxNorm = (predX - actualTarget.first).toDouble()
            val dyNorm = (predY - actualTarget.second).toDouble()
            val dxMm = dxNorm * safeWidthMm
            val dyMm = dyNorm * safeHeightMm
            val errMm = hypot(dxMm, dyMm)
            val errPct = hypot(dxNorm, dyNorm) * 100.0

            totalMmError += errMm
            totalNormError += errPct
            targetErrors.getOrPut(actualTarget) { mutableListOf() }.add(errPct)
        }

        val count = validationSamples.size.toDouble()
        val meanMm = totalMmError / count
        val meanPct = (totalNormError / count).coerceAtMost(MAX_REPORTED_ERROR_PCT)
        val maxQuadrantPct = targetErrors.values
            .mapNotNull { list -> if (list.isEmpty()) null else list.average() }
            .maxOrNull()
            ?.coerceIn(meanPct, MAX_REPORTED_ERROR_PCT)
            ?: meanPct

        val recommendation = gridLayoutLabel(qualifiesFor3x3Grid(meanPct, maxQuadrantPct)) +
            (if (maxQuadrantPct > RECALIBRATION_RECOMMENDED_WORST_QUADRANT_PCT) " — Recalibration Recommended" else "")

        return ValidationMetricsResult(
            meanErrorPct = meanPct,
            maxErrorPct = maxQuadrantPct,
            approxMeanErrorMm = meanMm,
            recommendedGridDesc = recommendation
        )
    }

    fun clusterFixations(
        rawTimedSamples: List<Triple<Float, Float, Long>>,
        dispersionThreshold: Float = 0.12f,
        minDwellMs: Long = 180L,
        maxGapMs: Long = 300L,
        maxClusters: Int = 40
    ): List<FixationClusterPoint> {
        if (rawTimedSamples.size < 2) return emptyList()
        val clusters = mutableListOf<FixationClusterPoint>()

        var sumX = rawTimedSamples[0].first
        var sumY = rawTimedSamples[0].second
        var count = 1
        var startTs = rawTimedSamples[0].third
        var lastTs = startTs

        for (i in 1 until rawTimedSamples.size) {
            val (x, y, ts) = rawTimedSamples[i]
            val centroidX = sumX / count
            val centroidY = sumY / count
            val dist = hypot(x - centroidX, y - centroidY)
            val gapBreak = ts - lastTs > maxGapMs

            if (!gapBreak && dist <= dispersionThreshold) {
                sumX += x
                sumY += y
                count++
                lastTs = ts
            } else {
                val dwell = lastTs - startTs
                if (dwell >= minDwellMs) {
                    clusters.add(
                        FixationClusterPoint(
                            normX = (sumX / count).coerceIn(0.05f, 0.95f),
                            normY = (sumY / count).coerceIn(0.05f, 0.95f),
                            dwellMs = dwell
                        )
                    )
                }
                sumX = x
                sumY = y
                count = 1
                startTs = ts
                lastTs = ts
            }
        }

        val finalDwell = lastTs - startTs
        if (finalDwell >= minDwellMs) {
            clusters.add(
                FixationClusterPoint(
                    normX = (sumX / count).coerceIn(0.05f, 0.95f),
                    normY = (sumY / count).coerceIn(0.05f, 0.95f),
                    dwellMs = finalDwell
                )
            )
        }

        val cap = maxClusters.coerceAtLeast(0)
        return if (clusters.size > cap) clusters.takeLast(cap) else clusters
    }
}

private class FrozenBlinkHysteresisDetector(
    var closeThreshold: Float = 0.45f,
    var openThreshold: Float = 0.65f,
    var intentionalMinMs: Long = 350L,
    var maxCommandMs: Long = 800L
) {
    var naturalMinMs: Long = 80L
    private var isEyesClosedState: Boolean = false
    private var closedStartTimestampMs: Long = 0L
    private var lastUpdateTimestampMs: Long = 0L
    private var frameIntervalMs: Double = 66.0

    fun update(eyeOpenProb: Float, timestampMs: Long): Pair<Float, BlinkClassificationEvent?> {
        if (lastUpdateTimestampMs > 0L) {
            val dt = (timestampMs - lastUpdateTimestampMs).coerceIn(16L, 250L).toDouble()
            frameIntervalMs = 0.8 * frameIntervalMs + 0.2 * dt
        }
        lastUpdateTimestampMs = timestampMs

        if (!isEyesClosedState) {
            if (eyeOpenProb < closeThreshold) {
                isEyesClosedState = true
                closedStartTimestampMs = timestampMs
                return Pair(0.05f, null)
            }
            return Pair(0f, null)
        } else {
            val elapsedMs = (timestampMs - closedStartTimestampMs).coerceAtLeast(0L)
            if (eyeOpenProb > openThreshold) {
                isEyesClosedState = false
                val estimatedClosureMs = elapsedMs + (frameIntervalMs / 2.0).toLong()
                val event = when {
                    elapsedMs < intentionalMinMs && estimatedClosureMs >= naturalMinMs ->
                        BlinkClassificationEvent(BlinkType.NATURAL, elapsedMs, timestampMs)
                    elapsedMs in intentionalMinMs..maxCommandMs ->
                        BlinkClassificationEvent(BlinkType.INTENTIONAL_COMMAND, elapsedMs, timestampMs)
                    elapsedMs > maxCommandMs ->
                        BlinkClassificationEvent(BlinkType.EXTENDED_REST, elapsedMs, timestampMs)
                    else -> null
                }
                return Pair(0f, event)
            } else {
                val progress = min(1f, elapsedMs.toFloat() / intentionalMinMs.toFloat())
                return Pair(progress, null)
            }
        }
    }

    fun reset() {
        isEyesClosedState = false
        closedStartTimestampMs = 0L
        lastUpdateTimestampMs = 0L
        frameIntervalMs = 66.0
    }
}

private class FrozenBrowRaiseHysteresisDetector(
    var neutralBrowRatio: Float = 0.34f,
    var raisedBrowRatio: Float = 0.42f,
    var holdTargetMs: Long = 400L
) {
    private var isBrowRaisedState: Boolean = false
    private var raiseStartTimestampMs: Long = 0L
    private var firedForCurrentRaise: Boolean = false
    private var lastUpdateTimestampMs: Long = 0L

    val raiseThreshold: Float
        get() = neutralBrowRatio + 0.60f * (raisedBrowRatio - neutralBrowRatio).coerceAtLeast(0.035f)

    val releaseThreshold: Float
        get() = neutralBrowRatio + 0.32f * (raisedBrowRatio - neutralBrowRatio).coerceAtLeast(0.035f)

    fun update(browToEyeRatio: Float, timestampMs: Long): Pair<Float, Boolean> {
        if (isBrowRaisedState && !firedForCurrentRaise && lastUpdateTimestampMs > 0L &&
            timestampMs - lastUpdateTimestampMs > 1000L
        ) {
            raiseStartTimestampMs = timestampMs
        }
        lastUpdateTimestampMs = timestampMs

        if (!isBrowRaisedState) {
            if (browToEyeRatio >= raiseThreshold) {
                isBrowRaisedState = true
                raiseStartTimestampMs = timestampMs
                firedForCurrentRaise = false
                return Pair(0.08f, false)
            }
            return Pair(0f, false)
        } else {
            if (browToEyeRatio < releaseThreshold) {
                isBrowRaisedState = false
                firedForCurrentRaise = false
                return Pair(0f, false)
            }
            val elapsed = (timestampMs - raiseStartTimestampMs).coerceAtLeast(0L)
            val progress = (elapsed.toFloat() / holdTargetMs.coerceAtLeast(250L).toFloat()).coerceIn(0f, 1f)
            if (progress >= 1f && !firedForCurrentRaise) {
                firedForCurrentRaise = true
                return Pair(1f, true)
            }
            return Pair(progress, false)
        }
    }

    fun cancelPendingRaise() {
        if (isBrowRaisedState && !firedForCurrentRaise) {
            isBrowRaisedState = false
        }
    }

    fun reset() {
        isBrowRaisedState = false
        firedForCurrentRaise = false
        raiseStartTimestampMs = 0L
        lastUpdateTimestampMs = 0L
    }
}

private fun frozenIsStaleGap(lastTimestampMs: Long, timestampMs: Long): Boolean {
    val gapMs = timestampMs - lastTimestampMs
    return gapMs < 0L || gapMs > 1000L
}

private class FrozenOneEuroFilter(
    var minCutoff: Double = 0.85,
    var beta: Double = 1.2,
    var dCutoff: Double = 1.0
) {
    private var xPrev: Double? = null
    private var dxPrev: Double = 0.0
    private var tPrev: Long? = null

    fun filter(x: Double, timestampMs: Long): Double {
        val lastT = tPrev
        val lastX = xPrev

        if (lastT == null || lastX == null || frozenIsStaleGap(lastT, timestampMs)) {
            xPrev = x
            dxPrev = 0.0
            tPrev = timestampMs
            return x
        }

        val dtSec = ((timestampMs - lastT).coerceIn(1L, 1000L)) / 1000.0
        tPrev = timestampMs

        val rawDx = (x - lastX) / dtSec

        val alphaD = computeAlpha(dtSec, dCutoff)
        val filteredDx = alphaD * rawDx + (1.0 - alphaD) * dxPrev
        dxPrev = filteredDx

        val cutoff = minCutoff + beta * abs(filteredDx)

        val alphaX = computeAlpha(dtSec, cutoff)
        val filteredX = alphaX * x + (1.0 - alphaX) * lastX
        xPrev = filteredX

        return filteredX
    }

    fun reset() {
        xPrev = null
        dxPrev = 0.0
        tPrev = null
    }

    val lastValue: Double?
        get() = xPrev

    companion object {
        fun computeAlpha(dtSec: Double, cutoffHz: Double): Double {
            val tau = 1.0 / (2.0 * PI * cutoffHz.coerceAtLeast(1e-4))
            return 1.0 / (1.0 + tau / dtSec.coerceAtLeast(1e-4))
        }
    }
}

private class FrozenOneEuroFilter2D(
    var minCutoff: Double = 0.85,
    var beta: Double = 1.2,
    var dCutoff: Double = 1.0
) {
    private var xPrev: Double? = null
    private var yPrev: Double? = null
    private var dxPrev: Double = 0.0
    private var dyPrev: Double = 0.0
    private var tPrev: Long? = null

    fun filter(x: Double, y: Double, timestampMs: Long): Pair<Double, Double> {
        val lastT = tPrev
        val lastX = xPrev
        val lastY = yPrev

        if (lastT == null || lastX == null || lastY == null || frozenIsStaleGap(lastT, timestampMs)) {
            xPrev = x
            yPrev = y
            dxPrev = 0.0
            dyPrev = 0.0
            tPrev = timestampMs
            return Pair(x, y)
        }

        val dtSec = ((timestampMs - lastT).coerceIn(1L, 1000L)) / 1000.0
        tPrev = timestampMs

        val rawDx = (x - lastX) / dtSec
        val rawDy = (y - lastY) / dtSec

        val alphaD = FrozenOneEuroFilter.computeAlpha(dtSec, dCutoff)
        val filteredDx = alphaD * rawDx + (1.0 - alphaD) * dxPrev
        val filteredDy = alphaD * rawDy + (1.0 - alphaD) * dyPrev
        dxPrev = filteredDx
        dyPrev = filteredDy

        val speed = sqrt(filteredDx * filteredDx + filteredDy * filteredDy)
        val dynamicCutoff = minCutoff + beta * speed

        val alpha = FrozenOneEuroFilter.computeAlpha(dtSec, dynamicCutoff)
        val filteredX = alpha * x + (1.0 - alpha) * lastX
        val filteredY = alpha * y + (1.0 - alpha) * lastY

        xPrev = filteredX
        yPrev = filteredY

        return Pair(filteredX, filteredY)
    }

    fun reset() {
        xPrev = null
        yPrev = null
        dxPrev = 0.0
        dyPrev = 0.0
        tPrev = null
    }

    val lastPosition: Pair<Double, Double>?
        get() {
            val x = xPrev
            val y = yPrev
            return if (x != null && y != null) Pair(x, y) else null
        }
}

private class FrozenGazeKalmanFilter2D(
    var processNoiseQ: Double = 1.0,
    var measurementNoiseR: Double = 1e-2
) {
    private var posX: Double = 0.0
    private var posY: Double = 0.0
    private var velX: Double = 0.0
    private var velY: Double = 0.0

    private var pX00: Double = 1.0
    private var pX01: Double = 0.0
    private var pX10: Double = 0.0
    private var pX11: Double = 1.0

    private var pY00: Double = 1.0
    private var pY01: Double = 0.0
    private var pY10: Double = 0.0
    private var pY11: Double = 1.0

    private var tPrev: Long? = null
    private var isInitialized: Boolean = false

    fun filter(measX: Double, measY: Double, timestampMs: Long): Pair<Double, Double> {
        val lastT = tPrev
        if (!isInitialized || lastT == null || frozenIsStaleGap(lastT, timestampMs)) {
            posX = measX
            posY = measY
            velX = 0.0
            velY = 0.0
            pX00 = 1.0; pX01 = 0.0; pX10 = 0.0; pX11 = 1.0
            pY00 = 1.0; pY01 = 0.0; pY10 = 0.0; pY11 = 1.0
            tPrev = timestampMs
            isInitialized = true
            return Pair(measX, measY)
        }

        val dt = ((timestampMs - lastT).coerceIn(1L, 1000L)) / 1000.0
        tPrev = timestampMs

        val predX = posX + velX * dt
        val predY = posY + velY * dt

        val dt2 = dt * dt
        val q00 = 0.25 * dt2 * dt2 * processNoiseQ
        val q01 = 0.5 * dt * dt2 * processNoiseQ
        val q11 = dt2 * processNoiseQ

        val predPX00 = pX00 + dt * (pX10 + pX01) + dt2 * pX11 + q00
        val predPX01 = pX01 + dt * pX11 + q01
        val predPX10 = pX10 + dt * pX11 + q01
        val predPX11 = pX11 + q11

        val predPY00 = pY00 + dt * (pY10 + pY01) + dt2 * pY11 + q00
        val predPY01 = pY01 + dt * pY11 + q01
        val predPY10 = pY10 + dt * pY11 + q01
        val predPY11 = pY11 + q11

        val resX = measX - predX
        val sX = predPX00 + measurementNoiseR
        val kX0 = predPX00 / sX
        val kX1 = predPX10 / sX

        posX = predX + kX0 * resX
        velX = velX + kX1 * resX

        pX00 = (1.0 - kX0) * predPX00
        pX01 = (1.0 - kX0) * predPX01
        pX10 = predPX10 - kX1 * predPX00
        pX11 = predPX11 - kX1 * predPX01

        val resY = measY - predY
        val sY = predPY00 + measurementNoiseR
        val kY0 = predPY00 / sY
        val kY1 = predPY10 / sY

        posY = predY + kY0 * resY
        velY = velY + kY1 * resY

        pY00 = (1.0 - kY0) * predPY00
        pY01 = (1.0 - kY0) * predPY01
        pY10 = predPY10 - kY1 * predPY00
        pY11 = predPY11 - kY1 * predPY01

        return Pair(posX, posY)
    }

    fun reset() {
        isInitialized = false
        tPrev = null
        posX = 0.0
        posY = 0.0
        velX = 0.0
        velY = 0.0
    }

    val state: Pair<Double, Double>
        get() = Pair(posX, posY)

    val velocity: Pair<Double, Double>
        get() = Pair(velX, velY)
}
