package com.example.enrollment

import com.example.data.model.EnrollmentBaseline
import com.example.data.model.GazeFeatureVector
import com.example.domain.EnrollmentConfig
import com.example.domain.EnrollmentFailure
import com.example.domain.EnrollmentGuidance
import com.example.domain.EnrollmentPhase
import com.example.domain.EnrollmentScanEngine
import com.example.domain.EnrollmentScanState
import com.example.domain.EnrollmentWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests of the enrollment scan state machine (domain/EnrollmentScan.kt) driven by synthetic
 * GazeFeatureVector streams at a fixed 33 ms frame interval. No Robolectric: the engine has no Android types.
 */
class DomainEnrollmentTest {

    private val now0 = 1_000L
    private val dt = 33L

    /** Feeds frames to one engine while advancing a scripted clock. */
    private inner class Driver(
        val engine: EnrollmentScanEngine = EnrollmentScanEngine(deviceModel = "TestPhone"),
        var now: Long = now0
    ) {
        val state: EnrollmentScanState get() = engine.state

        fun start(): EnrollmentScanState = engine.start(now)

        fun frame(f: GazeFeatureVector): EnrollmentScanState {
            now += dt
            return engine.onFrame(f, now)
        }

        fun frames(count: Int, f: GazeFeatureVector): EnrollmentScanState {
            repeat(count) { frame(f) }
            return engine.state
        }

        /** Feeds [f] until at least [ms] of scripted time has passed. */
        fun framesForMs(ms: Long, f: GazeFeatureVector): EnrollmentScanState {
            val end = now + ms
            while (now < end) frame(f)
            return engine.state
        }

        fun untilPhase(target: EnrollmentPhase, f: GazeFeatureVector, maxFrames: Int = 2_000): EnrollmentScanState {
            var n = 0
            while (engine.state.phase != target) {
                check(engine.state.phase.isActive) { "scan left the active phases in ${engine.state.phase} (${engine.state.failure})" }
                check(n++ < maxFrames) { "phase $target not reached after $maxFrames frames (now in ${engine.state.phase})" }
                frame(f)
            }
            return engine.state
        }

        fun untilGuidance(target: EnrollmentGuidance, f: GazeFeatureVector, maxFrames: Int = 2_000): EnrollmentScanState {
            var n = 0
            while (engine.state.guidance != target) {
                check(engine.state.phase.isActive) { "scan left the active phases in ${engine.state.phase} (${engine.state.failure})" }
                check(n++ < maxFrames) { "guidance $target not reached after $maxFrames frames" }
                frame(f)
            }
            return engine.state
        }
    }

    // ------------------------------------------------------------------ synthetic frames

    private fun face(
        prob: Float = 0.92f,
        left: Float = prob,
        right: Float = prob,
        brow: Float = 0.34f,
        confidence: Float = 0.90f,
        boxLeft: Float = 0.25f,
        boxTop: Float = 0.18f,
        boxRight: Float = 0.75f,
        boxBottom: Float = 0.82f,
        lowLight: Boolean = false,
        glare: Boolean = false,
        singleEye: Boolean = false,
        eyeDistancePx: Double = 148.0,
        luma: Int = 142,
        simulated: Boolean = false
    ) = GazeFeatureVector(
        faceDetected = true,
        leftEyeOpenProb = left,
        rightEyeOpenProb = right,
        leftEyeConfidence = confidence,
        rightEyeConfidence = confidence,
        browToEyeRatio = brow,
        faceBoxLeft = boxLeft,
        faceBoxTop = boxTop,
        faceBoxRight = boxRight,
        faceBoxBottom = boxBottom,
        isLowLightDegraded = lowLight,
        hasGlareWarning = glare,
        isSingleEyeMode = singleEye,
        activeEyeLabel = if (singleEye) "LEFT EYE ONLY" else "BINOCULAR",
        eyeDistancePx = eyeDistancePx,
        ambientLuma = luma,
        isSimulated = simulated,
        timestampMs = 0L
    )

    private val noFace = GazeFeatureVector(
        faceDetected = false,
        leftEyeOpenProb = 0f,
        rightEyeOpenProb = 0f,
        leftEyeConfidence = 0f,
        rightEyeConfidence = 0f,
        timestampMs = 0L
    )

    private val openFrame = face()
    private val blinkFrame = face(prob = 0.08f, confidence = 0.04f)
    private val closedFrame = face(prob = 0.10f, confidence = 0.04f)
    private val raisedBrowFrame = face(brow = 0.42f)

    // ------------------------------------------------------------------ scripted scan segments

    private fun Driver.positionUntilEyesOpen(): EnrollmentScanState = untilPhase(EnrollmentPhase.EYES_OPEN, openFrame)

    /** EYES_OPEN with [blinkCount] closures of [blinkFrames] frames each (duration = blinkFrames * dt ms). */
    private fun Driver.eyesOpenWithBlinks(blinkCount: Int, blinkFrames: Int): EnrollmentScanState {
        assertEquals(EnrollmentPhase.EYES_OPEN, state.phase)
        repeat(blinkCount) {
            frames(10, openFrame)
            frames(blinkFrames, blinkFrame)
        }
        return untilPhase(EnrollmentPhase.EYES_CLOSED, openFrame)
    }

    /** EYES_CLOSED: hold [closed] until the engine asks to reopen, then reopen with [reopen]. */
    private fun Driver.eyesClosedThenReopen(closed: GazeFeatureVector = closedFrame, reopen: GazeFeatureVector = openFrame): EnrollmentScanState {
        assertEquals(EnrollmentPhase.EYES_CLOSED, state.phase)
        untilGuidance(EnrollmentGuidance.OPEN_EYES, closed)
        return untilPhase(EnrollmentPhase.BROW_RAISE, reopen)
    }

    private fun Driver.browRaise(raised: GazeFeatureVector = raisedBrowFrame): EnrollmentScanState {
        assertEquals(EnrollmentPhase.BROW_RAISE, state.phase)
        return untilPhase(EnrollmentPhase.COMPLETE, raised)
    }

    private fun Driver.happyPath(blinkCount: Int = 3, blinkFrames: Int = 12): EnrollmentBaseline {
        start()
        positionUntilEyesOpen()
        eyesOpenWithBlinks(blinkCount, blinkFrames)
        eyesClosedThenReopen()
        browRaise()
        return state.result ?: error("no result after COMPLETE")
    }

    // ------------------------------------------------------------------ idle / lifecycle

    @Test
    fun `idle engine ignores frames, cancel and skip, and start resets to positioning`() {
        val d = Driver()
        val idle = d.state
        assertEquals(EnrollmentScanState(), idle)
        assertSame(idle, d.frame(openFrame))
        assertSame(idle, d.engine.cancel())
        assertSame(idle, d.engine.skipBrowStep())
        assertEquals(EnrollmentPhase.IDLE, d.state.phase)

        val started = d.start()
        assertEquals(EnrollmentPhase.POSITIONING, started.phase)
        assertEquals(EnrollmentGuidance.NO_FACE, started.guidance)
        assertEquals(0L, started.elapsedMs)
        assertEquals(0, started.retries)
        assertTrue(started.warnings.isEmpty())
        assertNull(started.result)
        assertNull(started.failure)
        assertEquals(0f, started.overallProgress, 0f)
    }

    @Test
    fun `phase isActive covers exactly positioning through brow raise`() {
        val active = EnrollmentPhase.entries.filter { it.isActive }
        assertEquals(
            listOf(EnrollmentPhase.POSITIONING, EnrollmentPhase.EYES_OPEN, EnrollmentPhase.EYES_CLOSED, EnrollmentPhase.BROW_RAISE),
            active
        )
    }

    // ------------------------------------------------------------------ positioning

    @Test
    fun `positioning guidance names the first unmet condition`() {
        fun guidanceFor(f: GazeFeatureVector): EnrollmentGuidance {
            val d = Driver()
            d.start()
            return d.frame(f).guidance
        }
        assertEquals(EnrollmentGuidance.NO_FACE, guidanceFor(noFace))
        // No face wins over everything else, even a dark frame.
        assertEquals(EnrollmentGuidance.NO_FACE, guidanceFor(noFace.copy(isLowLightDegraded = true)))
        // Centre x = 0.725 > 0.65
        assertEquals(EnrollmentGuidance.CENTER_FACE, guidanceFor(face(boxLeft = 0.50f, boxRight = 0.95f)))
        // Centre y = 0.775 > 0.72
        assertEquals(EnrollmentGuidance.CENTER_FACE, guidanceFor(face(boxTop = 0.60f, boxBottom = 0.95f)))
        // Off-centre and too small: centring comes first.
        assertEquals(EnrollmentGuidance.CENTER_FACE, guidanceFor(face(boxLeft = 0.70f, boxRight = 0.86f)))
        // Width 0.16 < 0.22
        assertEquals(EnrollmentGuidance.MOVE_CLOSER, guidanceFor(face(boxLeft = 0.42f, boxRight = 0.58f)))
        // Too small beats lighting.
        assertEquals(EnrollmentGuidance.MOVE_CLOSER, guidanceFor(face(boxLeft = 0.42f, boxRight = 0.58f, lowLight = true)))
        // Width 0.84 > 0.78
        assertEquals(EnrollmentGuidance.MOVE_BACK, guidanceFor(face(boxLeft = 0.08f, boxRight = 0.92f)))
        assertEquals(EnrollmentGuidance.MORE_LIGHT, guidanceFor(face(lowLight = true)))
        // Low light beats glare.
        assertEquals(EnrollmentGuidance.MORE_LIGHT, guidanceFor(face(lowLight = true, glare = true)))
        assertEquals(EnrollmentGuidance.REDUCE_GLARE, guidanceFor(face(glare = true)))
        assertEquals(EnrollmentGuidance.HOLD_STILL, guidanceFor(openFrame))
        // Eyes not measured well enough (confidence / single eye) has no dedicated line: hold still.
        assertEquals(EnrollmentGuidance.HOLD_STILL, guidanceFor(face(confidence = 0.10f)))
        assertEquals(EnrollmentGuidance.HOLD_STILL, guidanceFor(face(singleEye = true)))
        // But low light is named when the eyes are also unmeasured.
        assertEquals(EnrollmentGuidance.MORE_LIGHT, guidanceFor(face(confidence = 0.10f, lowLight = true)))
    }

    @Test
    fun `positioning needs consecutive ready frames and an interruption restarts the count`() {
        val d = Driver()
        d.start()
        val required = d.engine.config.readyFramesRequired
        assertEquals(12, required)

        d.frames(required / 2, openFrame)
        assertEquals(EnrollmentPhase.POSITIONING, d.state.phase)
        assertEquals(0.5f, d.state.phaseProgress, 1e-6f)
        assertEquals(0.125f, d.state.overallProgress, 1e-6f)
        assertTrue(d.state.liveFaceCentered)
        assertEquals(0.92f, d.state.liveEyeOpenProb, 1e-6f)

        // One frame short, then a no-face frame: back to zero.
        d.frames(required / 2 - 1, openFrame)
        assertEquals(EnrollmentPhase.POSITIONING, d.state.phase)
        val interrupted = d.frame(noFace)
        assertEquals(EnrollmentPhase.POSITIONING, interrupted.phase)
        assertEquals(0f, interrupted.phaseProgress, 0f)
        assertEquals(0f, interrupted.liveEyeOpenProb, 0f)
        assertFalse(interrupted.liveFaceCentered)

        // Low-confidence and single-eye frames do not count either.
        d.frames(required, face(confidence = 0.10f))
        assertEquals(EnrollmentPhase.POSITIONING, d.state.phase)
        d.frames(required, face(singleEye = true))
        assertEquals(EnrollmentPhase.POSITIONING, d.state.phase)

        d.frames(required - 1, openFrame)
        assertEquals(EnrollmentPhase.POSITIONING, d.state.phase)
        val ready = d.frame(openFrame)
        assertEquals(EnrollmentPhase.EYES_OPEN, ready.phase)
        assertEquals(EnrollmentGuidance.LOOK_AT_DOT, ready.guidance)
        assertEquals(0f, ready.phaseProgress, 0f)
        assertEquals(0.25f, ready.overallProgress, 1e-6f)
        assertEquals(ready.elapsedMs, d.now - now0)
        assertTrue(ready.warnings.isEmpty())
    }

    @Test
    fun `low light blocks positioning for six seconds of otherwise-ready frames, then becomes a warning`() {
        val d = Driver()
        d.start()
        val dark = face(lowLight = true)
        d.framesForMs(5_900L, dark)
        assertEquals(EnrollmentPhase.POSITIONING, d.state.phase)
        assertEquals(EnrollmentGuidance.MORE_LIGHT, d.state.guidance)
        assertTrue(d.state.warnings.isEmpty())

        val ready = d.untilPhase(EnrollmentPhase.EYES_OPEN, dark, maxFrames = 60)
        assertTrue(ready.elapsedMs >= EnrollmentScanEngine.LIGHTING_OVERRIDE_MS)
        assertEquals(setOf(EnrollmentWarning.LOW_LIGHT), ready.warnings)
    }

    @Test
    fun `glare blocks positioning for six seconds of otherwise-ready frames, then becomes a warning`() {
        val d = Driver()
        d.start()
        val glary = face(glare = true)
        d.framesForMs(5_900L, glary)
        assertEquals(EnrollmentPhase.POSITIONING, d.state.phase)
        assertEquals(EnrollmentGuidance.REDUCE_GLARE, d.state.guidance)
        val ready = d.untilPhase(EnrollmentPhase.EYES_OPEN, glary, maxFrames = 60)
        assertEquals(setOf(EnrollmentWarning.GLARE), ready.warnings)
    }

    @Test
    fun `lighting override needs the geometry to be ready the whole time`() {
        val d = Driver()
        d.start()
        // Off-centre and dark for longer than the override window: never ready, no warning.
        d.framesForMs(7_000L, face(boxLeft = 0.50f, boxRight = 0.95f, lowLight = true))
        assertEquals(EnrollmentPhase.POSITIONING, d.state.phase)
        assertEquals(EnrollmentGuidance.CENTER_FACE, d.state.guidance)
        assertTrue(d.state.warnings.isEmpty())
        // The otherwise-ready run restarts after an interruption, so 4 s + gap + 4 s does not override.
        d.framesForMs(4_000L, face(lowLight = true))
        d.frame(noFace)
        d.framesForMs(4_000L, face(lowLight = true))
        assertEquals(EnrollmentPhase.POSITIONING, d.state.phase)
        assertEquals(EnrollmentGuidance.MORE_LIGHT, d.state.guidance)
    }

    @Test
    fun `positioning times out`() {
        val d = Driver(EnrollmentScanEngine(EnrollmentConfig(positioningTimeoutMs = 3_000L)))
        d.start()
        d.framesForMs(2_900L, noFace)
        assertEquals(EnrollmentPhase.POSITIONING, d.state.phase)
        d.framesForMs(200L, noFace)
        assertEquals(EnrollmentPhase.FAILED, d.state.phase)
        assertEquals(EnrollmentFailure.POSITIONING_TIMEOUT, d.state.failure)
        assertEquals(EnrollmentGuidance.FAILED, d.state.guidance)
        assertNull(d.state.result)
        // Terminal: further frames change nothing.
        val failed = d.state
        assertSame(failed, d.frame(openFrame))
    }

    @Test
    fun `a ready frame on the timeout boundary still passes positioning`() {
        val d = Driver(EnrollmentScanEngine(EnrollmentConfig(positioningTimeoutMs = 12 * dt)))
        d.start()
        // 12 ready frames; the 12th arrives exactly at the timeout and completes the phase.
        assertEquals(EnrollmentPhase.EYES_OPEN, d.frames(12, openFrame).phase)
    }

    // ------------------------------------------------------------------ happy path

    @Test
    fun `happy path with natural blinks yields ordered, clamped, personalized thresholds`() {
        val d = Driver()
        val baseline = d.happyPath(blinkCount = 3, blinkFrames = 12)
        val s = d.state
        assertEquals(EnrollmentPhase.COMPLETE, s.phase)
        assertEquals(EnrollmentGuidance.DONE, s.guidance)
        assertEquals(1f, s.phaseProgress, 0f)
        assertEquals(1f, s.overallProgress, 0f)
        assertNull(s.failure)
        assertTrue("no warnings expected but got ${s.warnings}", s.warnings.isEmpty())
        assertEquals(0, s.retries)
        assertSame(baseline, s.result)

        assertEquals(1, baseline.version)
        assertEquals(d.now, baseline.capturedAtMs)
        assertEquals("TestPhone", baseline.deviceModel)
        assertEquals(0.92f, baseline.openEyeLevel, 1e-6f)
        assertEquals(0.92f, baseline.leftOpenLevel, 1e-6f)
        assertEquals(0.92f, baseline.rightOpenLevel, 1e-6f)
        assertEquals(0.10f, baseline.closedEyeLevel, 1e-6f)
        assertEquals(148.0, baseline.baselineEyeDistancePx, 1e-9)
        assertEquals(0.34f, baseline.neutralBrowRatio, 1e-6f)
        assertEquals(0.42f, baseline.raisedBrowRatio ?: Float.NaN, 1e-6f)
        assertEquals(142, baseline.meanAmbientLuma)
        // Open frames carry 0.90, blink frames 0.04: the mean sits strictly between them.
        assertTrue(baseline.meanEyeConfidence > 0.04f && baseline.meanEyeConfidence < 0.90f)
        assertTrue(baseline.frameCount > 100)
        assertTrue(baseline.warnings.isEmpty())

        // Three 12-frame closures: 396 ms each.
        val blinkMs = 12 * dt
        assertEquals(3, baseline.naturalBlinkCount)
        assertEquals(blinkMs, baseline.naturalBlinkMedianMs)
        assertEquals(blinkMs, baseline.naturalBlinkP95Ms)
        assertEquals(blinkMs + 80L, baseline.intentionalBlinkMinMs)

        // range 0.82: close = 0.10 + 0.35 * 0.82, open = 0.10 + 0.65 * 0.82
        assertEquals(0.387f, baseline.closeBlinkThreshold, 1e-4f)
        assertEquals(0.633f, baseline.openBlinkThreshold, 1e-4f)
        assertTrue(baseline.closeBlinkThreshold < baseline.openBlinkThreshold)
        assertTrue(baseline.openBlinkThreshold - baseline.closeBlinkThreshold >= 0.05f - 1e-6f)
        assertTrue(baseline.closeBlinkThreshold in 0.05f..0.80f)
        assertTrue(baseline.openBlinkThreshold <= 0.98f)
    }

    @Test
    fun `happy path walks the guidance sequence and keeps overall progress monotonic`() {
        val d = Driver()
        d.start()
        val seen = linkedSetOf<EnrollmentGuidance>()
        var lastOverall = -1f
        fun observe(s: EnrollmentScanState) {
            seen += s.guidance
            assertTrue("overall progress went backwards: $lastOverall -> ${s.overallProgress} in ${s.phase}", s.overallProgress >= lastOverall - 1e-6f)
            lastOverall = s.overallProgress
        }
        observe(d.state)
        while (d.state.phase.isActive) {
            val f = when (d.state.phase) {
                EnrollmentPhase.POSITIONING, EnrollmentPhase.EYES_OPEN -> openFrame
                EnrollmentPhase.EYES_CLOSED -> if (d.state.guidance == EnrollmentGuidance.OPEN_EYES) openFrame else closedFrame
                else -> raisedBrowFrame
            }
            observe(d.frame(f))
        }
        assertEquals(EnrollmentPhase.COMPLETE, d.state.phase)
        assertEquals(
            listOf(
                EnrollmentGuidance.NO_FACE, EnrollmentGuidance.HOLD_STILL, EnrollmentGuidance.LOOK_AT_DOT,
                EnrollmentGuidance.CLOSE_EYES, EnrollmentGuidance.KEEP_EYES_CLOSED, EnrollmentGuidance.OPEN_EYES,
                EnrollmentGuidance.RAISE_BROWS, EnrollmentGuidance.DONE
            ),
            seen.toList()
        )
        // The whole scripted scan fits the ~15 s budget with the default windows.
        assertTrue("scan took ${d.state.elapsedMs} ms", d.state.elapsedMs in 5_000L..15_000L)
    }

    @Test
    fun `phase boundaries land on quarter marks of the overall progress`() {
        val d = Driver()
        d.start()
        assertEquals(0.25f, d.positionUntilEyesOpen().overallProgress, 1e-6f)
        assertEquals(0.50f, d.eyesOpenWithBlinks(0, 0).overallProgress, 1e-6f)
        assertEquals(0.75f, d.eyesClosedThenReopen().overallProgress, 1e-6f)
        assertEquals(1.00f, d.browRaise().overallProgress, 1e-6f)
    }

    @Test
    fun `eyes open phase excludes blink frames from the open level and pauses nothing on short blinks`() {
        val d = Driver()
        d.start()
        d.positionUntilEyesOpen()
        // Half the frames are blink frames: the open-level median must still be the open value.
        repeat(5) {
            d.frames(7, openFrame)
            d.frames(7, blinkFrame)
        }
        d.untilPhase(EnrollmentPhase.EYES_CLOSED, openFrame)
        d.eyesClosedThenReopen()
        val baseline = d.browRaise().result!!
        assertEquals(0.92f, baseline.openEyeLevel, 1e-6f)
        assertEquals(5, baseline.naturalBlinkCount)
        assertEquals(7 * dt, baseline.naturalBlinkMedianMs) // 231 ms: NATURAL class
        assertEquals(350L, baseline.intentionalBlinkMinMs) // max(350, 231 + 80)
        assertFalse(baseline.warnings.contains(EnrollmentWarning.FEW_NATURAL_BLINKS.name))
    }

    @Test
    fun `fewer than two natural blinks keeps the 350 ms default and warns`() {
        val d = Driver()
        val baseline = d.happyPath(blinkCount = 1, blinkFrames = 12)
        assertEquals(1, baseline.naturalBlinkCount)
        assertEquals(12 * dt, baseline.naturalBlinkMedianMs)
        assertEquals(350L, baseline.intentionalBlinkMinMs)
        assertEquals(setOf(EnrollmentWarning.FEW_NATURAL_BLINKS), d.state.warnings)
        assertEquals(listOf("FEW_NATURAL_BLINKS"), baseline.warnings)

        val none = Driver().happyPath(blinkCount = 0, blinkFrames = 0)
        assertEquals(0, none.naturalBlinkCount)
        assertEquals(0L, none.naturalBlinkMedianMs)
        assertEquals(0L, none.naturalBlinkP95Ms)
        assertEquals(350L, none.intentionalBlinkMinMs)
    }

    @Test
    fun `long natural blinks raise the command-blink minimum up to the 600 ms cap`() {
        // 24-frame closures = 792 ms (still a blink, under the 800 ms rest limit): p95 + 80 = 872 -> capped.
        val capped = Driver().happyPath(blinkCount = 2, blinkFrames = 24)
        assertEquals(2, capped.naturalBlinkCount)
        assertEquals(24 * dt, capped.naturalBlinkP95Ms)
        assertEquals(600L, capped.intentionalBlinkMinMs)

        // 15-frame closures = 495 ms -> 575 ms, inside the window.
        val inside = Driver().happyPath(blinkCount = 2, blinkFrames = 15)
        assertEquals(15 * dt + 80L, inside.intentionalBlinkMinMs)
    }

    @Test
    fun `an extended eye rest during the open phase is not counted as a natural blink`() {
        val d = Driver()
        d.start()
        d.positionUntilEyesOpen()
        d.frames(5, openFrame)
        d.frames(30, blinkFrame) // 990 ms closure: EXTENDED_REST
        d.frames(10, openFrame)
        d.frames(12, blinkFrame) // one real 396 ms blink
        d.untilPhase(EnrollmentPhase.EYES_CLOSED, openFrame)
        d.eyesClosedThenReopen()
        val baseline = d.browRaise().result!!
        assertEquals(1, baseline.naturalBlinkCount)
        assertEquals(12 * dt, baseline.naturalBlinkMedianMs)
    }

    // ------------------------------------------------------------------ thresholds

    @Test
    fun `small open-closed range falls back to the default thresholds with a warning`() {
        val d = Driver()
        d.start()
        d.positionUntilEyesOpen()
        d.eyesOpenWithBlinks(2, 12)
        // One frame below 0.5 * open starts the collection; the rest of the window sits at 0.80 (median 0.80).
        d.frame(face(prob = 0.40f))
        assertEquals(EnrollmentGuidance.KEEP_EYES_CLOSED, d.state.guidance)
        d.untilGuidance(EnrollmentGuidance.OPEN_EYES, face(prob = 0.80f))
        d.untilPhase(EnrollmentPhase.BROW_RAISE, openFrame) // 0.92 > 0.80 + 0.6 * 0.12
        val baseline = d.browRaise().result!!
        assertEquals(0.80f, baseline.closedEyeLevel, 1e-6f)
        assertEquals(0.45f, baseline.closeBlinkThreshold, 0f)
        assertEquals(0.65f, baseline.openBlinkThreshold, 0f)
        assertEquals(setOf(EnrollmentWarning.EYE_RANGE_SMALL_DEFAULTS_USED), d.state.warnings)
        assertEquals(listOf(EnrollmentWarning.EYE_RANGE_SMALL_DEFAULTS_USED.name), baseline.warnings)
    }

    @Test
    fun `thresholds are clamped to the close range and the minimum gap`() {
        // Upper close clamp: open 0.99, closed 0.70 -> close 0.8015 -> 0.80; open 0.8885.
        run {
            val d = Driver()
            d.start()
            d.untilPhase(EnrollmentPhase.EYES_OPEN, face(prob = 0.99f))
            d.untilPhase(EnrollmentPhase.EYES_CLOSED, face(prob = 0.99f))
            d.frame(face(prob = 0.40f)) // < 0.495 starts the closed collection
            d.untilGuidance(EnrollmentGuidance.OPEN_EYES, face(prob = 0.70f))
            d.untilPhase(EnrollmentPhase.BROW_RAISE, face(prob = 0.99f)) // > 0.70 + 0.6 * 0.29
            val b = d.browRaise().result!!
            assertEquals(0.70f, b.closedEyeLevel, 1e-6f)
            assertEquals(0.80f, b.closeBlinkThreshold, 1e-6f)
            assertEquals(0.70f + 0.65f * 0.29f, b.openBlinkThreshold, 1e-4f)
            assertTrue(b.warnings.isEmpty() || b.warnings == listOf("FEW_NATURAL_BLINKS"))
        }
        // Minimum gap: open 0.66, closed 0.50 -> range 0.16 -> close 0.556, open 0.604 -> raised to close + 0.05.
        run {
            val d = Driver()
            d.start()
            d.untilPhase(EnrollmentPhase.EYES_OPEN, face(prob = 0.66f))
            d.untilPhase(EnrollmentPhase.EYES_CLOSED, face(prob = 0.66f))
            d.frame(face(prob = 0.30f)) // < 0.33 starts the closed collection
            d.untilGuidance(EnrollmentGuidance.OPEN_EYES, face(prob = 0.50f))
            d.untilPhase(EnrollmentPhase.BROW_RAISE, face(prob = 0.66f)) // > 0.50 + 0.6 * 0.16
            val b = d.browRaise().result!!
            assertEquals(0.556f, b.closeBlinkThreshold, 1e-3f)
            assertEquals(b.closeBlinkThreshold + 0.05f, b.openBlinkThreshold, 1e-6f)
            assertFalse(b.warnings.contains(EnrollmentWarning.EYE_RANGE_SMALL_DEFAULTS_USED.name))
        }
    }

    @Test
    fun `per-eye open levels and eye distance come from the open frames`() {
        val d = Driver()
        d.start()
        val asym = face(left = 0.80f, right = 0.96f, eyeDistancePx = 160.0, luma = 90)
        d.untilPhase(EnrollmentPhase.EYES_OPEN, asym)
        d.untilPhase(EnrollmentPhase.EYES_CLOSED, asym)
        d.eyesClosedThenReopen(closed = closedFrame, reopen = asym)
        val b = d.browRaise().result!!
        assertEquals(0.88f, b.openEyeLevel, 1e-6f)
        assertEquals(0.80f, b.leftOpenLevel, 1e-6f)
        assertEquals(0.96f, b.rightOpenLevel, 1e-6f)
        assertEquals(160.0, b.baselineEyeDistancePx, 1e-9)
        assertEquals(90, b.meanAmbientLuma)
    }

    // ------------------------------------------------------------------ eyes closed failures

    @Test
    fun `eyes that never close fail the scan`() {
        val d = Driver()
        d.start()
        d.positionUntilEyesOpen()
        d.eyesOpenWithBlinks(2, 12)
        assertEquals(EnrollmentGuidance.CLOSE_EYES, d.frame(openFrame).guidance)
        d.framesForMs(3_900L, openFrame)
        assertEquals(EnrollmentPhase.EYES_CLOSED, d.state.phase)
        assertTrue(d.state.phaseProgress < 0.25f)
        d.framesForMs(200L, openFrame)
        assertEquals(EnrollmentPhase.FAILED, d.state.phase)
        assertEquals(EnrollmentFailure.EYES_DID_NOT_CLOSE, d.state.failure)
        assertNull(d.state.result)
        // Progress is frozen where the scan was.
        assertEquals(0.5f, d.state.overallProgress, 0.1f)
    }

    @Test
    fun `eyes that never reopen fail the scan`() {
        val d = Driver()
        d.start()
        d.positionUntilEyesOpen()
        d.eyesOpenWithBlinks(2, 12)
        d.untilGuidance(EnrollmentGuidance.OPEN_EYES, closedFrame)
        d.framesForMs(2_900L, closedFrame)
        assertEquals(EnrollmentPhase.EYES_CLOSED, d.state.phase)
        d.framesForMs(200L, closedFrame)
        assertEquals(EnrollmentPhase.FAILED, d.state.phase)
        assertEquals(EnrollmentFailure.EYES_DID_NOT_REOPEN, d.state.failure)
    }

    @Test
    fun `a closing frame on the wait boundary still starts the closed collection`() {
        val cfg = EnrollmentConfig(eyesClosedWaitMs = 10 * dt)
        val d = Driver(EnrollmentScanEngine(cfg))
        d.start()
        d.positionUntilEyesOpen()
        d.eyesOpenWithBlinks(0, 0)
        d.frames(9, openFrame)
        assertEquals(EnrollmentGuidance.CLOSE_EYES, d.state.guidance)
        val s = d.frame(closedFrame) // exactly at the wait limit
        assertEquals(EnrollmentPhase.EYES_CLOSED, s.phase)
        assertEquals(EnrollmentGuidance.KEEP_EYES_CLOSED, s.guidance)
    }

    // ------------------------------------------------------------------ brow step

    @Test
    fun `brow timeout completes the scan without a raised ratio and warns BROW_SKIPPED`() {
        val d = Driver()
        d.start()
        d.positionUntilEyesOpen()
        d.eyesOpenWithBlinks(2, 12)
        d.eyesClosedThenReopen()
        assertEquals(EnrollmentGuidance.RAISE_BROWS, d.state.guidance)
        d.framesForMs(4_900L, openFrame) // neutral brow the whole time
        assertEquals(EnrollmentPhase.BROW_RAISE, d.state.phase)
        d.framesForMs(200L, openFrame)
        val s = d.state
        assertEquals(EnrollmentPhase.COMPLETE, s.phase)
        assertNull(s.failure)
        assertNull(s.result!!.raisedBrowRatio)
        assertEquals(0.34f, s.result!!.neutralBrowRatio, 1e-6f)
        assertEquals(setOf(EnrollmentWarning.BROW_SKIPPED), s.warnings)
        assertEquals(listOf("BROW_SKIPPED"), s.result!!.warnings)
    }

    @Test
    fun `skipBrowStep completes immediately from BROW_RAISE and is a no-op elsewhere`() {
        val d = Driver()
        d.start()
        d.positionUntilEyesOpen()
        // Not in BROW_RAISE: nothing happens.
        val before = d.state
        assertSame(before, d.engine.skipBrowStep())
        assertEquals(EnrollmentPhase.EYES_OPEN, d.state.phase)

        d.eyesOpenWithBlinks(2, 12)
        d.eyesClosedThenReopen()
        d.frames(5, raisedBrowFrame) // collection has started but is far from done
        val s = d.engine.skipBrowStep()
        assertEquals(EnrollmentPhase.COMPLETE, s.phase)
        assertEquals(EnrollmentGuidance.DONE, s.guidance)
        assertEquals(1f, s.overallProgress, 0f)
        assertNull(s.result!!.raisedBrowRatio)
        assertTrue(s.warnings.contains(EnrollmentWarning.BROW_SKIPPED))
        assertEquals(d.now, s.result!!.capturedAtMs)
        // Terminal.
        assertSame(s, d.engine.skipBrowStep())
        assertSame(s, d.frame(openFrame))

        // The partial brow samples are discarded: the frame count equals that of the same script skipped
        // the moment BROW_RAISE began.
        val immediate = Driver()
        immediate.start()
        immediate.positionUntilEyesOpen()
        immediate.eyesOpenWithBlinks(2, 12)
        immediate.eyesClosedThenReopen()
        val skippedAtOnce = immediate.engine.skipBrowStep().result!!
        assertEquals(skippedAtOnce.frameCount, s.result!!.frameCount)
        assertEquals(skippedAtOnce.openEyeLevel, s.result!!.openEyeLevel, 0f)
        assertEquals(skippedAtOnce.closedEyeLevel, s.result!!.closedEyeLevel, 0f)
    }

    @Test
    fun `raised brow ratio is the 75th percentile of the collected frames`() {
        val d = Driver()
        d.start()
        d.positionUntilEyesOpen()
        d.eyesOpenWithBlinks(2, 12)
        d.eyesClosedThenReopen()
        // Waiting: a ratio within +0.02 of neutral does not start the collection.
        d.frames(10, face(brow = 0.355f))
        assertEquals(EnrollmentPhase.BROW_RAISE, d.state.phase)
        assertEquals(EnrollmentGuidance.RAISE_BROWS, d.state.guidance)
        assertTrue("still waiting: ${d.state.phaseProgress}", d.state.phaseProgress < 0.4f)
        // Mixed collection: first half 0.40, second half 0.46 -> p75 sits in the upper half.
        val start = d.now
        d.frame(face(brow = 0.40f))
        while (d.now - start < 750L) d.frame(face(brow = 0.40f))
        d.untilPhase(EnrollmentPhase.COMPLETE, face(brow = 0.46f))
        val raised = d.state.result!!.raisedBrowRatio!!
        assertTrue("p75 = $raised", raised > 0.43f && raised <= 0.46f + 1e-6f)
    }

    // ------------------------------------------------------------------ face loss

    @Test
    fun `face loss shorter than the grace pauses the collection without a retry`() {
        val d = Driver()
        d.start()
        d.positionUntilEyesOpen()
        d.framesForMs(1_000L, openFrame)
        val beforePause = d.state
        assertEquals(EnrollmentPhase.EYES_OPEN, beforePause.phase)

        d.framesForMs(1_000L, noFace) // < 1 500 ms grace
        val paused = d.state
        assertEquals(EnrollmentPhase.EYES_OPEN, paused.phase)
        assertEquals(EnrollmentGuidance.NO_FACE, paused.guidance)
        assertEquals(0, paused.retries)
        assertEquals(beforePause.phaseProgress, paused.phaseProgress, 0f) // the clock did not advance
        assertEquals(0f, paused.liveEyeOpenProb, 0f)

        // The pause does not count: about 1 500 ms of face frames are still needed.
        d.framesForMs(1_300L, openFrame)
        assertEquals(EnrollmentPhase.EYES_OPEN, d.state.phase)
        assertEquals(EnrollmentGuidance.LOOK_AT_DOT, d.state.guidance)
        d.framesForMs(300L, openFrame)
        assertEquals(EnrollmentPhase.EYES_CLOSED, d.state.phase)
        assertEquals(0, d.state.retries)
    }

    @Test
    fun `face loss longer than the grace restarts the phase and counts a retry`() {
        val d = Driver()
        d.start()
        d.positionUntilEyesOpen()
        d.framesForMs(2_000L, openFrame)
        assertTrue(d.state.phaseProgress > 0.7f)

        d.framesForMs(1_600L, noFace)
        val restarted = d.state
        assertEquals(EnrollmentPhase.EYES_OPEN, restarted.phase)
        assertEquals(1, restarted.retries)
        assertEquals(0f, restarted.phaseProgress, 0f)
        assertEquals(EnrollmentGuidance.NO_FACE, restarted.guidance)

        // A full window is needed again after the face returns.
        d.framesForMs(2_300L, openFrame)
        assertEquals(EnrollmentPhase.EYES_OPEN, d.state.phase)
        d.framesForMs(300L, openFrame)
        assertEquals(EnrollmentPhase.EYES_CLOSED, d.state.phase)
        assertEquals(1, d.state.retries)
    }

    @Test
    fun `face loss in the closed step restarts the whole closed phase`() {
        val d = Driver()
        d.start()
        d.positionUntilEyesOpen()
        d.eyesOpenWithBlinks(2, 12)
        d.frames(10, closedFrame)
        assertEquals(EnrollmentGuidance.KEEP_EYES_CLOSED, d.state.guidance)
        d.framesForMs(1_600L, noFace)
        assertEquals(EnrollmentPhase.EYES_CLOSED, d.state.phase)
        assertEquals(1, d.state.retries)
        // Back to waiting for the eyes to close; the earlier closed samples are gone.
        assertEquals(EnrollmentGuidance.CLOSE_EYES, d.frame(openFrame).guidance)
        d.eyesClosedThenReopen()
        val b = d.browRaise().result!!
        assertEquals(0.10f, b.closedEyeLevel, 1e-6f)
        assertEquals(1, d.state.retries)
    }

    @Test
    fun `face loss beyond the retry budget fails the scan`() {
        val d = Driver()
        d.start()
        d.positionUntilEyesOpen()
        d.framesForMs(500L, openFrame)
        // Each 1 500 ms of absence burns one retry; the fourth fails.
        d.framesForMs(1_600L, noFace)
        assertEquals(1, d.state.retries)
        d.framesForMs(1_500L, noFace)
        assertEquals(2, d.state.retries)
        d.framesForMs(1_500L, noFace)
        assertEquals(3, d.state.retries)
        assertEquals(EnrollmentPhase.EYES_OPEN, d.state.phase)
        d.framesForMs(1_600L, noFace)
        assertEquals(EnrollmentPhase.FAILED, d.state.phase)
        assertEquals(EnrollmentFailure.FACE_LOST, d.state.failure)
        assertEquals(3, d.state.retries)
    }

    @Test
    fun `face loss during positioning is only guidance, never a retry`() {
        val d = Driver()
        d.start()
        d.framesForMs(5_000L, noFace)
        assertEquals(EnrollmentPhase.POSITIONING, d.state.phase)
        assertEquals(EnrollmentGuidance.NO_FACE, d.state.guidance)
        assertEquals(0, d.state.retries)
    }

    // ------------------------------------------------------------------ warnings during collection

    @Test
    fun `single-eye, low-light and glare frames during collection add warnings without blocking`() {
        val d = Driver()
        d.start()
        d.positionUntilEyesOpen()
        d.frames(3, face(singleEye = true))
        d.frames(3, face(lowLight = true))
        d.frames(3, face(glare = true))
        d.eyesOpenWithBlinks(2, 12) // enough blinks that FEW_NATURAL_BLINKS is not raised
        d.eyesClosedThenReopen()
        val b = d.browRaise().result!!
        assertEquals(
            setOf(EnrollmentWarning.LOW_LIGHT, EnrollmentWarning.GLARE, EnrollmentWarning.SINGLE_EYE),
            d.state.warnings
        )
        // Stored in enum order.
        assertEquals(listOf("LOW_LIGHT", "GLARE", "SINGLE_EYE"), b.warnings)
    }

    // ------------------------------------------------------------------ simulated frames

    @Test
    fun `a simulated frame fails the scan in any active phase`() {
        val simulated = face(simulated = true)

        val positioning = Driver()
        positioning.start()
        positioning.frames(3, openFrame)
        assertEquals(EnrollmentFailure.SIMULATED_FRAMES, positioning.frame(simulated).failure)
        assertEquals(EnrollmentPhase.FAILED, positioning.state.phase)

        val open = Driver()
        open.start()
        open.positionUntilEyesOpen()
        assertEquals(EnrollmentFailure.SIMULATED_FRAMES, open.frame(simulated).failure)

        val closed = Driver()
        closed.start()
        closed.positionUntilEyesOpen()
        closed.eyesOpenWithBlinks(0, 0)
        assertEquals(EnrollmentFailure.SIMULATED_FRAMES, closed.frame(simulated).failure)

        val brow = Driver()
        brow.start()
        brow.positionUntilEyesOpen()
        brow.eyesOpenWithBlinks(0, 0)
        brow.eyesClosedThenReopen()
        assertEquals(EnrollmentFailure.SIMULATED_FRAMES, brow.frame(simulated).failure)
        assertNull(brow.state.result)
        assertEquals(EnrollmentGuidance.FAILED, brow.state.guidance)
    }

    // ------------------------------------------------------------------ cancel / reset

    @Test
    fun `cancel fails an active scan with CANCELLED and leaves terminal states alone`() {
        val d = Driver()
        d.start()
        d.positionUntilEyesOpen()
        d.framesForMs(500L, openFrame)
        val progressBefore = d.state.overallProgress
        val cancelled = d.engine.cancel()
        assertEquals(EnrollmentPhase.FAILED, cancelled.phase)
        assertEquals(EnrollmentFailure.CANCELLED, cancelled.failure)
        assertEquals(EnrollmentGuidance.FAILED, cancelled.guidance)
        assertNull(cancelled.result)
        assertEquals(progressBefore, cancelled.overallProgress, 1e-6f)
        // Already failed: cancel and frames are no-ops.
        assertSame(cancelled, d.engine.cancel())
        assertSame(cancelled, d.frame(openFrame))

        // A completed scan is not cancelled either.
        val done = Driver()
        done.happyPath()
        val complete = done.state
        assertSame(complete, done.engine.cancel())
        assertEquals(EnrollmentPhase.COMPLETE, done.state.phase)
    }

    @Test
    fun `reset returns to the default idle snapshot and a new start is clean`() {
        val d = Driver()
        d.start()
        d.positionUntilEyesOpen()
        d.frames(3, face(lowLight = true))
        d.framesForMs(1_600L, noFace)
        assertEquals(1, d.state.retries)
        assertTrue(d.state.warnings.isNotEmpty())

        d.engine.reset()
        assertEquals(EnrollmentScanState(), d.state)
        assertSame(d.state, d.frame(openFrame))

        d.now += 10_000L
        val restarted = d.start()
        assertEquals(EnrollmentPhase.POSITIONING, restarted.phase)
        assertEquals(0, restarted.retries)
        assertTrue(restarted.warnings.isEmpty())
        assertEquals(0L, restarted.elapsedMs)
        assertEquals(0f, restarted.overallProgress, 0f)
        // And it runs to completion again from scratch.
        d.positionUntilEyesOpen()
        d.eyesOpenWithBlinks(2, 12)
        d.eyesClosedThenReopen()
        val b = d.browRaise().result!!
        assertTrue(b.warnings.isEmpty())
        assertEquals(0, d.state.retries)
    }

    @Test
    fun `start restarts a completed or failed scan`() {
        val d = Driver()
        d.happyPath()
        assertEquals(EnrollmentPhase.COMPLETE, d.state.phase)
        val again = d.start()
        assertEquals(EnrollmentPhase.POSITIONING, again.phase)
        assertNull(again.result)
        assertNull(again.failure)

        d.engine.cancel()
        assertEquals(EnrollmentPhase.FAILED, d.state.phase)
        assertEquals(EnrollmentPhase.POSITIONING, d.start().phase)
    }
}
