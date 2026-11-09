package com.example.regression

import android.graphics.PointF
import com.example.domain.CameraGazeAnalyzerEngine
import com.example.domain.OneEuroFilter2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Regression tests for CameraGazeAnalyzerEngine fixes. Robolectric is needed for android.graphics.PointF.
 * The engine itself cannot be constructed off-device (ML Kit), so the ROI analysis is reached through
 * reflection on an instance allocated without its constructor, as PipelineBenchmarkTest does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EngineRegressionTest {

    // ---------------------------------------------------------------------------------------------
    // A21: ML Kit listener dispatch after close() must not throw and must still run the listener.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `task listener executor runs on the analysis thread and inline once that executor is shut down`() {
        val analysis = Executors.newSingleThreadExecutor { r -> Thread(r, "engine-analysis-test") }
        val safe = CameraGazeAnalyzerEngine.rejectionSafeExecutor(analysis)

        val threadName = AtomicReference<String>()
        val done = CountDownLatch(1)
        safe.execute {
            threadName.set(Thread.currentThread().name)
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals("engine-analysis-test", threadName.get())

        analysis.shutdown()
        assertTrue(analysis.awaitTermination(5, TimeUnit.SECONDS))
        val ranInline = AtomicBoolean(false)
        // Old code handed the listener straight to the shut-down executor: RejectedExecutionException.
        safe.execute { ranInline.set(true) }
        assertTrue("listener (which closes the ImageProxy) must still run", ranInline.get())
    }

    // ---------------------------------------------------------------------------------------------
    // A24 / A25 / A29: ROI results mark what was measured; pixels are read in place from the plane.
    // ---------------------------------------------------------------------------------------------

    private class Frame(val buf: ByteBuffer, val rawWidth: Int, val rawHeight: Int, val rowStride: Int)

    /** Rotation-0 Y plane with one eye (sclera 205, iris 40, pupil 20) on a 150 background; padding is 255. */
    private fun paintEyeFrame(cx: Float, cy: Float, eyeWidthPx: Float): Frame {
        val rawWidth = 320
        val rawHeight = 240
        val rowStride = rawWidth + 16
        val bytes = ByteArray(rowStride * rawHeight) { 0xFF.toByte() }
        val a = eyeWidthPx / 2f
        val b = eyeWidthPx * 0.19f
        val irisR = eyeWidthPx * 0.17f
        val irisX = cx + a * 0.18f
        val irisY = cy + b * 0.15f
        for (y in 0 until rawHeight) {
            for (x in 0 until rawWidth) {
                val ex = (x - cx) / a
                val ey = (y - cy) / b
                var luma = 150
                if (ex * ex + ey * ey <= 1f) {
                    luma = 205
                    val d = hypot((x - irisX).toDouble(), (y - irisY).toDouble())
                    if (d <= irisR) luma = if (d <= irisR * 0.45) 20 else 40
                }
                bytes[y * rowStride + x] = luma.toByte()
            }
        }
        val buf = ByteBuffer.allocateDirect(bytes.size)
        buf.put(bytes)
        buf.position(0)
        return Frame(buf, rawWidth, rawHeight, rowStride)
    }

    /** 16-point eye contour: index 0 = medial corner, index 8 = lateral corner, like ML Kit. */
    private fun eyeContour(cx: Float, cy: Float, halfWidth: Float, halfHeight: Float): List<PointF> =
        (0 until 16).map { i ->
            val theta = PI * i / 8.0
            PointF((cx + halfWidth * cos(theta)).toFloat(), (cy - halfHeight * sin(theta)).toFloat())
        }

    private fun allocateEngineWithoutConstructor(): Any {
        val unsafeCls = Class.forName("sun.misc.Unsafe")
        val field = unsafeCls.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        return checkNotNull(
            unsafeCls.getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, CameraGazeAnalyzerEngine::class.java)
        )
    }

    private val roiMethod: Method by lazy {
        CameraGazeAnalyzerEngine::class.java.declaredMethods
            .first { it.name == "analyzeEyeContourRoi" && it.parameterCount == 9 }
            .apply { isAccessible = true }
    }

    private fun analyzeRoi(engine: Any, points: List<PointF>, frame: Frame): Any =
        checkNotNull(
            roiMethod.invoke(
                engine, points, 0.92f, frame.buf, frame.rawWidth, frame.rawHeight, 0, frame.rowStride, 1, 1
            )
        )

    private fun field(obj: Any, name: String): Any? =
        obj.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(obj)

    @Test
    fun `eye ROI marks fallbacks as unmeasured instead of reporting luma 140 and a zero gaze`() {
        val engine = allocateEngineWithoutConstructor()
        val frame = paintEyeFrame(cx = 100f, cy = 120f, eyeWidthPx = 60f)

        // Real pupil: measured luma and range, pupil measurement flagged.
        val measured = analyzeRoi(engine, eyeContour(100f, 120f, 30f, 60f * 0.19f), frame)
        assertEquals(true, field(measured, "hasPupilMeasurement"))
        val luma = field(measured, "meanLuma") as Int?
        assertNotNull(luma)
        assertTrue("measured luma $luma", luma!! in 21..204)
        assertEquals(185, field(measured, "dynamicRange"))

        // Nearly closed eye: the trimmed ROI collapses. Old code returned meanLuma=140 / dynamicRange=65
        // and a 0.0 gaze that was fused like a measurement.
        val collapsed = analyzeRoi(engine, eyeContour(100f, 201f, 30f, 1f), frame)
        assertEquals(false, field(collapsed, "hasPupilMeasurement"))
        assertNull(field(collapsed, "meanLuma"))
        assertNull(field(collapsed, "dynamicRange"))

        // Missing contour.
        val noContour = analyzeRoi(engine, emptyList(), frame)
        assertEquals(false, field(noContour, "hasPupilMeasurement"))
        assertNull(field(noContour, "meanLuma"))

        // Pixels are read with absolute gets from the plane buffer itself; its position never moves.
        assertEquals(0, frame.buf.position())
    }

    // ---------------------------------------------------------------------------------------------
    // A23: no-face frames measure luma from the Y plane.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `coarse frame luma measures the plane, skips row padding and leaves the buffer position alone`() {
        val rawWidth = 64
        val rawHeight = 48
        val rowStride = 80
        val bytes = ByteArray(rowStride * rawHeight) { 0xFF.toByte() }
        for (y in 0 until rawHeight) {
            for (x in 0 until rawWidth) {
                bytes[y * rowStride + x] = 25
            }
        }
        val buf = ByteBuffer.allocateDirect(bytes.size)
        buf.put(bytes)
        buf.position(0)

        assertEquals(25, CameraGazeAnalyzerEngine.coarseFrameMeanLuma(buf, rawWidth, rawHeight, rowStride, 1))
        assertEquals(0, buf.position())
        assertNull(CameraGazeAnalyzerEngine.coarseFrameMeanLuma(buf, 0, 0, rowStride, 1))
    }

    // ---------------------------------------------------------------------------------------------
    // A26: low-light state has hysteresis and ignores unmeasured frames.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `low-light state does not flicker when luma hovers around the old 58 threshold`() {
        val latch = CameraGazeAnalyzerEngine.LowLightHysteresisLatch()
        var t = 1_000_000L
        var changes = 0
        var last: Boolean? = null
        for (i in 0 until 90) {
            // Old rule `luma < 58` flipped on every one of these frames.
            val state = latch.update(if (i % 2 == 0) 56 else 60, 40, t)
            if (last != null && state != last) changes++
            last = state
            t += 33L
        }
        assertEquals(0, changes)
        assertFalse(latch.isLowLight)
    }

    @Test
    fun `low-light state enters on sustained dim light and exits only after the dwell time`() {
        val latch = CameraGazeAnalyzerEngine.LowLightHysteresisLatch()
        // Before anything was measured there is no luma to report (old code reported a constant 140).
        assertFalse(latch.update(null, null, 999_000L))
        assertNull(latch.reportedLuma())

        val t0 = 1_000_000L
        assertTrue(latch.update(30, 10, t0))

        // Unmeasured frames (ROI fallbacks) neither exit nor change the smoothed luma.
        var t = t0
        repeat(10) {
            t += 33L
            assertTrue(latch.update(null, null, t))
        }
        assertEquals(30, latch.reportedLuma())

        // Bright light: the EMA crosses the exit threshold within a few frames, but the state is held
        // until a full second has passed since entering.
        while (t < t0 + 900L) {
            t += 33L
            assertTrue("still held at ${t - t0} ms", latch.update(150, 60, t))
        }
        while (t < t0 + 1100L) {
            t += 33L
            latch.update(150, 60, t)
        }
        assertFalse(latch.isLowLight)
    }

    // ---------------------------------------------------------------------------------------------
    // A25: an eye without a pupil measurement never enters fusion.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `eye selection drops an unmeasured eye even when its confidence is not tiny`() {
        val sel = CameraGazeAnalyzerEngine.EyeSelection.RIGHT_ONLY
        // Collapsed left ROI (conf 0.2) + dim right eye (conf 0.33): the old rule fused 38% of a fake zero.
        assertEquals(sel, CameraGazeAnalyzerEngine.selectActiveEye(0.2f, 0.33f, leftMeasured = false, rightMeasured = true))
        // Missing left contour (conf 0.352) + measured right (0.62): the old rule shrank gaze 36% to center.
        assertEquals(sel, CameraGazeAnalyzerEngine.selectActiveEye(0.352f, 0.62f, leftMeasured = false, rightMeasured = true))
        assertEquals(
            CameraGazeAnalyzerEngine.EyeSelection.LEFT_ONLY,
            CameraGazeAnalyzerEngine.selectActiveEye(0.62f, 0.352f, leftMeasured = true, rightMeasured = false)
        )
        // Between two measured eyes the confidence rules are unchanged.
        assertEquals(
            CameraGazeAnalyzerEngine.EyeSelection.BINOCULAR,
            CameraGazeAnalyzerEngine.selectActiveEye(0.62f, 0.352f, leftMeasured = true, rightMeasured = true)
        )
        assertEquals(
            CameraGazeAnalyzerEngine.EyeSelection.LEFT_ONLY,
            CameraGazeAnalyzerEngine.selectActiveEye(0.9f, 0.1f, leftMeasured = true, rightMeasured = true)
        )
    }

    // ---------------------------------------------------------------------------------------------
    // A32: eye-closure freeze follows the active eye and the ViewModel's 0.50 threshold.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `eye closure is judged on the active eye with the same threshold as the ViewModel`() {
        val threshold = CameraGazeAnalyzerEngine.EYE_CLOSING_OPEN_PROB_THRESHOLD
        assertEquals(0.50f, threshold, 0f)
        // Left-eye-only mode, left eye blinking, discarded right eye reads 0.6: old rule (both < 0.40) kept filtering.
        assertTrue(
            CameraGazeAnalyzerEngine.activeEyeOpenness(CameraGazeAnalyzerEngine.EyeSelection.LEFT_ONLY, 0.10f, 0.60f) < threshold
        )
        // Both eyes at 0.45: the ViewModel freezes, the old engine rule did not.
        assertTrue(
            CameraGazeAnalyzerEngine.activeEyeOpenness(CameraGazeAnalyzerEngine.EyeSelection.BINOCULAR, 0.45f, 0.45f) < threshold
        )
        assertFalse(
            CameraGazeAnalyzerEngine.activeEyeOpenness(CameraGazeAnalyzerEngine.EyeSelection.BINOCULAR, 0.90f, 0.88f) < threshold
        )
    }

    // ---------------------------------------------------------------------------------------------
    // A290: inter-ocular distance does not change when the analysis resolution tier changes.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `inter-ocular distance is independent of the analysis resolution tier`() {
        // Same head distance: 130 px at 1280x960, 97.5 px at 960x720, 65 px at 640x480 (upright portrait).
        assertEquals(130.0, CameraGazeAnalyzerEngine.normalizeEyeDistancePx(130.0, 960, 1280), 1e-9)
        assertEquals(130.0, CameraGazeAnalyzerEngine.normalizeEyeDistancePx(97.5, 720, 960), 1e-9)
        assertEquals(130.0, CameraGazeAnalyzerEngine.normalizeEyeDistancePx(65.0, 480, 640), 1e-9)
    }

    // ---------------------------------------------------------------------------------------------
    // A27: the pupil One Euro filter actually adapts to saccade speed.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `pupil gaze filter reaches 90 percent of a saccade-sized gx step within 250 ms at 30 fps`() {
        val filter = CameraGazeAnalyzerEngine.newPupilGazeFilter()
        var t = 1_000_000L
        repeat(30) {
            filter.filter(0.0, 0.0, t)
            t += 33L
        }
        val stepStart = t
        var riseMs: Long? = null
        for (i in 0 until 60) {
            val (x, _) = filter.filter(0.2, 0.0, t)
            if (x >= 0.18) {
                riseMs = t - stepStart
                break
            }
            t += 33L
        }
        // beta = 0.035 needed about 430 ms here.
        assertNotNull(riseMs)
        assertTrue("90% rise took $riseMs ms", riseMs!! < 250L)
    }

    // ---------------------------------------------------------------------------------------------
    // A28: overlay pupil dot does not trail the eye center during head motion.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `smoothed overlay pupil stays with the eye center while the head moves`() {
        val filter = OneEuroFilter2D(minCutoff = 0.90, beta = 0.040, dCutoff = 1.0)
        val offset = 0.01f
        var eyeX = 0.40f
        var t = 1_000_000L
        var maxError = 0f
        for (i in 0 until 40) {
            // Head shifts the eye by 0.10 of the frame width over ~200 ms.
            if (i in 10 until 16) eyeX += 0.10f / 6f
            val (px, _) = CameraGazeAnalyzerEngine.smoothPupilAroundEyeCenter(filter, eyeX, 0.42f, eyeX + offset, 0.42f, t)
            maxError = max(maxError, abs(px - (eyeX + offset)))
            t += 33L
        }
        // Filtering the absolute position lagged by about 0.05 here.
        assertTrue("max pupil lag $maxError", maxError < 0.003f)
    }
}
