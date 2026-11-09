package com.example.regression

import com.example.data.model.GazeFeatureVector
import com.example.domain.BrowRaiseHysteresisDetector
import com.example.ui.components.MainThreadFrameConflator
import com.example.ui.components.OpticalWarningLatch
import com.example.ui.components.UNCALIBRATED_RETICLE_ERROR_PCT
import com.example.ui.components.analyzerStatusLabel
import com.example.ui.components.browRaiseTriggerThreshold
import com.example.ui.components.cameraPipelineLabel
import com.example.ui.components.eyeOpenBrowLine
import com.example.ui.components.gazeTelemetryLine
import com.example.ui.components.glareWarningSentence
import com.example.ui.components.lowLightWarningSentence
import com.example.ui.components.opticalWarningText
import com.example.ui.components.reticleUncertaintyRadiiPx
import com.example.ui.viewmodel.LiveGazeHudState
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for OpticalDiagnosticsBar.kt (HUD honesty, warning stability, reticle geometry,
 * brow trigger display and main-thread frame hand-off).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DiagRegressionTest {

    // --- A112: camera frames reach the ViewModel on the main thread, conflated ---

    @Test
    fun conflatorQueuesOneMainTaskAndDeliversOnlyNewestBackgroundFrame() {
        val mainQueue = java.util.ArrayDeque<Runnable>()
        val delivered = mutableListOf<Long>()
        val conflator = MainThreadFrameConflator(
            mainExecutor = Executor { mainQueue.add(it) },
            isOnMainThread = { false },
            deliver = { delivered.add(it.timestampMs) }
        )

        conflator.submit(GazeFeatureVector(timestampMs = 1L))
        conflator.submit(GazeFeatureVector(timestampMs = 2L))
        conflator.submit(GazeFeatureVector(timestampMs = 3L))

        // Nothing runs on the analysis thread, and the main queue holds a single drain task.
        assertTrue(delivered.isEmpty())
        assertEquals(1, mainQueue.size)

        mainQueue.poll()!!.run()
        assertEquals(listOf(3L), delivered)

        conflator.submit(GazeFeatureVector(timestampMs = 4L))
        assertEquals(1, mainQueue.size)
        mainQueue.poll()!!.run()
        assertEquals(listOf(3L, 4L), delivered)
    }

    @Test
    fun conflatorDeliversInlineOnMainThreadAndNothingAfterDispose() {
        val mainQueue = java.util.ArrayDeque<Runnable>()
        val delivered = mutableListOf<Long>()
        var onMain = true
        val conflator = MainThreadFrameConflator(
            mainExecutor = Executor { mainQueue.add(it) },
            isOnMainThread = { onMain },
            deliver = { delivered.add(it.timestampMs) }
        )

        conflator.submit(GazeFeatureVector(timestampMs = 10L))
        assertEquals(listOf(10L), delivered)
        assertTrue(mainQueue.isEmpty())

        onMain = false
        conflator.submit(GazeFeatureVector(timestampMs = 11L))
        assertEquals(1, mainQueue.size)

        conflator.dispose()
        mainQueue.poll()!!.run()
        conflator.submit(GazeFeatureVector(timestampMs = 12L))
        assertEquals(listOf(10L), delivered)
        assertTrue(mainQueue.isEmpty())
    }

    @Test
    fun conflatorRecoversWhenMainExecutorRejectsWork() {
        var reject = true
        val mainQueue = java.util.ArrayDeque<Runnable>()
        val delivered = mutableListOf<Long>()
        val conflator = MainThreadFrameConflator(
            mainExecutor = Executor {
                if (reject) throw RejectedExecutionException("shut down") else mainQueue.add(it)
            },
            isOnMainThread = { false },
            deliver = { delivered.add(it.timestampMs) }
        )

        conflator.submit(GazeFeatureVector(timestampMs = 1L))
        reject = false
        // The rejected frame must not leave the conflator stuck with a pending frame and no drain task.
        conflator.submit(GazeFeatureVector(timestampMs = 2L))
        assertEquals(1, mainQueue.size)
        mainQueue.poll()!!.run()
        assertEquals(listOf(2L), delivered)
    }

    // --- A117 / B64: displayed brow trigger and overlay colour use the detector's real threshold ---

    @Test
    fun browTriggerThresholdMatchesDetectorInsteadOfMidpoint() {
        val shown = browRaiseTriggerThreshold(0.34f, 0.42f)
        assertEquals(BrowRaiseHysteresisDetector(0.34f, 0.42f).raiseThreshold, shown, 1e-6f)
        // Old HUD printed the midpoint 0.38, which is not what triggers.
        assertNotEquals(0.38f, shown, 1e-4f)

        // A ratio between the old midpoint and the real threshold does not start a hold, and the
        // overlay comparison (ratio >= shown threshold) agrees with the detector.
        val ratio = 0.385f
        val detector = BrowRaiseHysteresisDetector(0.34f, 0.42f)
        val (progress, fired) = detector.update(ratio, 0L)
        assertEquals(0f, progress, 0f)
        assertFalse(fired)
        assertFalse(ratio >= shown)

        // Calibrated small delta (raised - neutral < 0.035) also follows the detector.
        assertEquals(
            BrowRaiseHysteresisDetector(0.30f, 0.32f).raiseThreshold,
            browRaiseTriggerThreshold(0.30f, 0.32f),
            1e-6f
        )
    }

    @Test
    fun browTriggerThresholdUsesDetectorCompanionFormula() {
        assertEquals(
            BrowRaiseHysteresisDetector.raiseThresholdFor(0.31f, 0.47f),
            browRaiseTriggerThreshold(0.31f, 0.47f),
            0f
        )
    }

    // --- B62: a stopped frame stream is shown as NO FRAMES, never as "0 FPS" or a stale optical lock ---

    @Test
    fun statusLabelSaysNoFramesInsteadOfZeroFps() {
        val live = analyzerStatusLabel(isSimulator = false, isEmulator = false, faceDetected = true, fps = 0)
        assertEquals("CAMERA • NO FRAMES", live)
        assertFalse(live, live.contains("0 FPS"))
        assertFalse(live, live.contains("OPTICAL LOCK"))
        assertEquals(
            "CAMERA • NO FRAMES",
            analyzerStatusLabel(isSimulator = false, isEmulator = false, faceDetected = false, fps = 0)
        )
        assertEquals("SIM PROXY • NO FRAMES", analyzerStatusLabel(isSimulator = true, isEmulator = false, faceDetected = true, fps = 0))
        assertEquals("EMULATOR SYNTHETIC • NO FRAMES", analyzerStatusLabel(isSimulator = false, isEmulator = true, faceDetected = true, fps = 0))
        // Default HUD state (no frame ever delivered) does not claim a frame rate.
        val initial = LiveGazeHudState()
        val label = analyzerStatusLabel(
            isSimulator = initial.isSimulatorProxyEnabled,
            isEmulator = false,
            faceDetected = initial.rawFeature.faceDetected,
            fps = initial.analyzerFps
        )
        assertFalse(label, label.contains("FPS"))
    }

    // --- Engine no-face frames carry 0 eye-open probability: show "--", not closed eyes ---

    @Test
    fun eyeOpenLineHidesNoFacePlaceholders() {
        val noFace = eyeOpenBrowLine(
            GazeFeatureVector(faceDetected = false, leftEyeOpenProb = 0f, rightEyeOpenProb = 0f),
            0.388f
        )
        assertEquals("Eye Open: L -- / R -- • Brow Ratio: -- (Trigger ≥ 0.39)", noFace)
        assertFalse(noFace, noFace.contains("0%"))
    }

    @Test
    fun eyeOpenLineShowsMeasuredValuesWithUsLocale() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val line = eyeOpenBrowLine(
                GazeFeatureVector(
                    faceDetected = true,
                    leftEyeOpenProb = 0.81f,
                    rightEyeOpenProb = 0.64f,
                    browToEyeRatio = 0.4123f
                ),
                0.388f
            )
            assertEquals("Eye Open: L 81% / R 64% • Brow Ratio: 0.41 (Trigger ≥ 0.39)", line)
        } finally {
            Locale.setDefault(previous)
        }
    }

    // --- C3 / A214 / A23 / A55: no fabricated accuracy or placeholder per-eye/luma values in the HUD ---

    @Test
    fun telemetryLineSaysUncalibratedAndHidesNoFacePlaceholders() {
        val line = gazeTelemetryLine(LiveGazeHudState())
        assertTrue(line, line.contains("uncalibrated"))
        assertFalse(line, line.contains("±10.9"))
        assertTrue(line, line.contains("L:-- R:--"))
        assertTrue(line, line.contains("Luma:--"))
        assertFalse(line, line.contains("L:90%"))
        assertFalse(line, line.contains("Luma:142"))
    }

    @Test
    fun telemetryLineShowsMeasuredValuesWithUsLocale() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val line = gazeTelemetryLine(
                LiveGazeHudState(
                    normX = 0.25f,
                    normY = 0.75f,
                    activeErrorRadiusPct = 7.4f,
                    rawFeature = GazeFeatureVector(
                        faceDetected = true,
                        leftEyeConfidence = 0.8f,
                        rightEyeConfidence = 0.6f,
                        ambientLuma = 77
                    )
                )
            )
            assertEquals("Gaze (25%, 75%) • L:80% R:60% • ±7.4% • Luma:77", line)
        } finally {
            Locale.setDefault(previous)
        }
    }

    // --- A119 / B42: no hard-coded device claims; synthetic frames are labelled as such ---

    @Test
    fun pipelineLabelsMakeNoFixedHardwareClaimsAndFlagSyntheticFrames() {
        assertEquals("EMULATOR SYNTHETIC • 15 FPS", analyzerStatusLabel(isSimulator = false, isEmulator = true, faceDetected = true, fps = 15))
        assertEquals("SIM PROXY • 15 FPS", analyzerStatusLabel(isSimulator = true, isEmulator = true, faceDetected = true, fps = 15))
        assertEquals("OPTICAL LOCK • 22 FPS", analyzerStatusLabel(isSimulator = false, isEmulator = false, faceDetected = true, fps = 22))
        assertEquals("SEARCHING FACE... • 9 FPS", analyzerStatusLabel(isSimulator = false, isEmulator = false, faceDetected = false, fps = 9))

        val live = cameraPipelineLabel(isSimulator = false, isEmulator = false, deviceModel = "Pixel 7")
        assertEquals("FRONT CAMERA PIPELINE • PIXEL 7", live)
        assertFalse(live.contains("42MP"))
        assertFalse(live.contains("PIXEL 10 PRO XL"))
        assertEquals("FRONT CAMERA PIPELINE • DEVICE", cameraPipelineLabel(isSimulator = false, isEmulator = false, deviceModel = " "))
        assertTrue(cameraPipelineLabel(isSimulator = false, isEmulator = true, deviceModel = "sdk_gphone64").contains("SYNTHETIC"))
        assertEquals("CAMERAX STANDBY (TAP PREVIEW FOR LIVE)", cameraPipelineLabel(isSimulator = true, isEmulator = false, deviceModel = "Pixel 7"))

        assertFalse(lowLightWarningSentence(40).contains("Pixel"))
        assertTrue(lowLightWarningSentence(40).contains("40/255"))
    }

    // --- A123: glare text reflects the engine's actual eye selection ---

    @Test
    fun glareSentenceFollowsEngineEyeSelection() {
        val fused = glareWarningSentence(
            GazeFeatureVector(faceDetected = true, hasGlareWarning = true, isSingleEyeMode = false)
        )
        assertFalse(fused, fused.contains("unoccluded"))
        assertTrue(fused, fused.contains("both eyes fused"))

        val single = glareWarningSentence(
            GazeFeatureVector(
                faceDetected = true,
                hasGlareWarning = true,
                isSingleEyeMode = true,
                activeEyeLabel = "RIGHT EYE ONLY"
            )
        )
        assertTrue(single, single.contains("tracking RIGHT EYE ONLY"))
    }

    // --- B24: warnings do not toggle on no-face placeholder frames; one slot carries all warnings ---

    @Test
    fun warningLatchIgnoresNoFaceFramesExceptRaisingLowLight() {
        val latch = OpticalWarningLatch()
        latch.update(
            GazeFeatureVector(faceDetected = true, hasGlareWarning = true, isLowLightDegraded = true, ambientLuma = 40)
        )
        // A single dropped detection carries defaults (no glare, not low light, luma 142).
        latch.update(GazeFeatureVector(faceDetected = false))
        assertTrue(latch.hasGlare)
        assertTrue(latch.isLowLight)
        assertEquals(40, latch.lowLightLuma)

        latch.update(
            GazeFeatureVector(faceDetected = true, hasGlareWarning = false, isLowLightDegraded = false, ambientLuma = 120)
        )
        assertFalse(latch.hasGlare)
        assertFalse(latch.isLowLight)
        // Details of the last raising frame are kept for the debounced fade-out text.
        assertEquals(40, latch.lowLightLuma)
        assertTrue(latch.glareSentence, latch.glareSentence.contains("both eyes fused"))

        // A no-face frame that reports measured low light may raise the warning.
        latch.update(GazeFeatureVector(faceDetected = false, isLowLightDegraded = true, ambientLuma = 25))
        assertTrue(latch.isLowLight)
        assertEquals(25, latch.lowLightLuma)
        assertFalse(latch.hasGlare)
    }

    @Test
    fun warningTextOrdersLowLightFirstAndIsNullWhenNoWarning() {
        assertNull(
            opticalWarningText(
                showLowLight = false,
                lowLightLuma = 0,
                showGlare = false,
                glareSentence = "",
                showDistanceDrift = false
            )
        )
        val glare = "Eyeglass specular glare detected (both eyes fused, glare-weighted)."
        val text = opticalWarningText(
            showLowLight = true,
            lowLightLuma = 31,
            showGlare = true,
            glareSentence = glare,
            showDistanceDrift = true
        )!!
        assertTrue(text, text.startsWith(lowLightWarningSentence(31)))
        assertTrue(text, text.contains(glare))
        assertTrue(text, text.contains("IOD"))
    }

    // --- B61 / A118 / A301 / C3: per-axis uncertainty radii, no 120 dp cap, finite when uncalibrated ---

    @Test
    fun reticleRadiiScalePerAxisWithoutFixedCap() {
        // 411 x 830 dp overlay at 2.625 px/dp.
        val w = 411f * 2.625f
        val h = 830f * 2.625f
        val minPx = 28f * 2.625f
        val cap120dpPx = 120f * 2.625f

        val r = reticleUncertaintyRadiiPx(10.9f, w, h, minPx)
        assertEquals(0.109f * w, r.width, 0.05f)
        assertEquals(0.109f * h, r.height, 0.05f)
        assertTrue("vertical radius must use height", r.height > r.width * 1.9f)

        val poor = reticleUncertaintyRadiiPx(30f, w, h, minPx)
        assertTrue("30% error must not be capped at 120 dp", poor.width > cap120dpPx)
        assertEquals(0.30f * h, poor.height, 0.05f)

        val huge = reticleUncertaintyRadiiPx(85f, w, h, minPx)
        assertEquals(w * 0.5f, huge.width, 0.05f)
        assertEquals(h * 0.5f, huge.height, 0.05f)
    }

    @Test
    fun reticleRadiiAreFiniteAndWideWhenNotValidated() {
        val w = 1080f
        val h = 2400f
        val minPx = 73.5f
        val unknown = reticleUncertaintyRadiiPx(null, w, h, minPx)
        assertEquals(UNCALIBRATED_RETICLE_ERROR_PCT / 100f * w, unknown.width, 0.05f)
        assertEquals(UNCALIBRATED_RETICLE_ERROR_PCT / 100f * h, unknown.height, 0.05f)

        val nan = reticleUncertaintyRadiiPx(Float.NaN, w, h, minPx)
        assertFalse(nan.width.isNaN())
        assertFalse(nan.height.isNaN())
        assertEquals(unknown.width, nan.width, 0f)

        // Tiny overlay where the minimum exceeds half the axis must not throw.
        val tiny = reticleUncertaintyRadiiPx(10f, 40f, 40f, minPx)
        assertEquals(minPx, tiny.width, 0f)
        assertEquals(minPx, tiny.height, 0f)
    }
}
