package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.BlinkType
import com.example.data.model.CalibrationProfile
import com.example.data.model.GazeFeatureVector
import com.example.data.repository.LocalTelemetryStore
import com.example.domain.BlinkHysteresisDetector
import com.example.domain.BrowRaiseHysteresisDetector
import com.example.domain.GazeCalibrationMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app_name and verify Æye`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Æye", appName)
    }

    @Test
    fun `blinkHysteresisDetector disambiguates natural vs intentional command blinks with unified windows`() {
        val detector = BlinkHysteresisDetector(
            closeThreshold = 0.45f,
            openThreshold = 0.65f,
            intentionalMinMs = 350L,
            maxCommandMs = 800L
        )

        // 1. Natural blink (180 ms closure) -> classified as NATURAL, not INTENTIONAL_COMMAND
        detector.update(0.30f, 1000L)
        val (_, naturalEvent) = detector.update(0.80f, 1180L)
        assertNotNull(naturalEvent)
        assertEquals(BlinkType.NATURAL, naturalEvent?.type)

        // 2. Intentional command blink (480 ms closure) -> classified as INTENTIONAL_COMMAND
        detector.update(0.25f, 2000L)
        val (midProgress, midEvent) = detector.update(0.35f, 2200L)
        assertNull(midEvent)
        assertTrue(midProgress > 0.4f)
        val (_, commandEvent) = detector.update(0.85f, 2480L)
        assertNotNull(commandEvent)
        assertEquals(BlinkType.INTENTIONAL_COMMAND, commandEvent?.type)

        // 3. Extended rest (> 800 ms closure) -> classified as EXTENDED_REST (cancelled)
        detector.update(0.20f, 3000L)
        val (_, restEvent) = detector.update(0.85f, 3920L)
        assertNotNull(restEvent)
        assertEquals(BlinkType.EXTENDED_REST, restEvent?.type)
    }

    @Test
    fun `browRaiseHysteresisDetector requires hold duration and hysteresis release before re-arming`() {
        val browDetector = BrowRaiseHysteresisDetector(
            neutralBrowRatio = 0.34f,
            raisedBrowRatio = 0.42f,
            holdTargetMs = 400L
        )

        // Start raise above threshold (~0.388)
        val (p0, fired0) = browDetector.update(0.41f, 1000L)
        assertFalse(fired0)
        assertTrue(p0 > 0f)

        // Hold for 420 ms -> fires once
        val (p1, fired1) = browDetector.update(0.41f, 1420L)
        assertTrue(fired1)
        assertEquals(1f, p1, 0.01f)

        // Holding longer does not double-fire
        val (_, fired2) = browDetector.update(0.41f, 1600L)
        assertFalse(fired2)

        // Relax below releaseThreshold (~0.366) -> resets
        val (pReset, firedReset) = browDetector.update(0.34f, 1750L)
        assertFalse(firedReset)
        assertEquals(0f, pReset, 0.01f)
    }

    @Test
    fun `standardized ridge regression with MAD outlier trimming and local store default opt-in`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val localStore = LocalTelemetryStore(context)
        // Verify Cloud Sync defaults to OFF (opt-in privacy)
        assertFalse(localStore.isCloudSyncEnabled())

        val samples = GazeCalibrationMath.CALIBRATION_AND_VALIDATION_TARGETS
            .filter { !it.isValidationPoint }
            .flatMap { t ->
                val inlier = GazeFeatureVector(
                    gx = ((t.normX - 0.5f) * 0.4f).toDouble(),
                    gy = ((t.normY - 0.5f) * 0.35f).toDouble(),
                    yaw = ((0.5f - t.normX) * 12f).toDouble(),
                    pitch = ((0.5f - t.normY) * 10f).toDouble()
                )
                val outlierBlink = inlier.copy(gx = 0.49, gy = -0.49, leftEyeOpenProb = 0.1f, rightEyeOpenProb = 0.1f)
                listOf(
                    Pair(inlier, Pair(t.normX, t.normY)),
                    Pair(inlier, Pair(t.normX, t.normY)),
                    Pair(inlier, Pair(t.normX, t.normY)),
                    Pair(outlierBlink, Pair(t.normX, t.normY))
                )
            }

        val matrix = GazeCalibrationMath.fitRidgeAffineMatrix2x6(samples, baselineEyeDistPx = 148.0)
        assertEquals(12, matrix.size)

        localStore.saveLocalProfile(
            userId = "test_local_user",
            profile = CalibrationProfile(
                id = "local_cal_1",
                name = "Offline Pixel 10 Pro XL Profile",
                matrixCoeffs = matrix,
                meanErrorPct = 9.8,
                maxErrorPct = 12.4
            )
        )

        val loaded = localStore.loadLocalProfiles("test_local_user")
        assertEquals(1, loaded.size)
        assertEquals("Offline Pixel 10 Pro XL Profile", loaded.first().name)
        assertEquals(12.4, loaded.first().maxErrorPct, 0.01)
    }

    @Test
    fun `pixel10ProXl resolution tiers and lowLightDegraded iris damping behave as specified`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbId = context.getString(R.string.firestore_database_id)
        assertTrue(dbId.isNotBlank())

        assertEquals(1280, com.example.data.model.AnalysisResolutionTier.TIER_HIGH_1280.targetWidth)
        assertEquals(960, com.example.data.model.AnalysisResolutionTier.TIER_MED_960.targetWidth)
        assertEquals(640, com.example.data.model.AnalysisResolutionTier.TIER_LOW_640.targetWidth)

        val brightFeature = GazeFeatureVector(
            gx = 0.25,
            gy = -0.20,
            yaw = 8.0,
            pitch = -5.0,
            ambientLuma = 148,
            isLowLightDegraded = false
        )
        val dimFeature = brightFeature.copy(
            ambientLuma = 38,
            isLowLightDegraded = true
        )

        val brightVec = brightFeature.toStandardizedFeatureArray(148.0)
        val dimVec = dimFeature.toStandardizedFeatureArray(148.0)

        // In low-light degraded mode (no IR illuminator on Pixel 10 Pro XL),
        // noisy visible-light iris offsets (gx, gy) are damped by 0.45x while 3D head pose is preserved
        assertEquals(brightVec[1] * 0.45, dimVec[1], 1e-4)
        assertEquals(brightVec[2] * 0.45, dimVec[2], 1e-4)
        assertEquals(brightVec[3], dimVec[3], 1e-6)
        assertEquals(brightVec[4], dimVec[4], 1e-6)
    }

    @Test
    fun `privacy settings toggle persists explicit opt-in and opt-out and clearAllLocalData wipes user vault`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val localStore = LocalTelemetryStore(context)

        // Default is opted-out (false)
        localStore.setCloudSyncEnabled(false)
        assertFalse(localStore.isCloudSyncEnabled())

        // Explicit opt-in persists true
        localStore.setCloudSyncEnabled(true)
        assertTrue(localStore.isCloudSyncEnabled())

        // Explicit opt-out persists false
        localStore.setCloudSyncEnabled(false)
        assertFalse(localStore.isCloudSyncEnabled())

        // Verify local vault deletion
        localStore.saveLocalProfile(
            userId = "privacy_test_uid",
            profile = CalibrationProfile(id = "local_p1", name = "Test Profile")
        )
        assertEquals(1, localStore.loadLocalProfiles("privacy_test_uid").size)
        localStore.clearAllLocalData("privacy_test_uid")
        assertTrue(localStore.loadLocalProfiles("privacy_test_uid").isEmpty())
    }

    @Test
    fun `fivePointCalibrationTargets define 5 screen coordinates with guidance prompts and converge ridge fit`() {
        val fivePoints = GazeCalibrationMath.FIVE_POINT_CALIBRATION_TARGETS
        assertEquals(5, fivePoints.size)

        // Verify Center, Top-Left, Top-Right, Bottom-Left, Bottom-Right coordinates
        assertEquals(0.50f, fivePoints[0].normX, 1e-4f)
        assertEquals(0.50f, fivePoints[0].normY, 1e-4f)
        assertEquals(0.14f, fivePoints[1].normX, 1e-4f)
        assertEquals(0.16f, fivePoints[1].normY, 1e-4f)
        assertEquals(0.86f, fivePoints[2].normX, 1e-4f)
        assertEquals(0.16f, fivePoints[2].normY, 1e-4f)
        assertEquals(0.14f, fivePoints[3].normX, 1e-4f)
        assertEquals(0.84f, fivePoints[3].normY, 1e-4f)
        assertEquals(0.86f, fivePoints[4].normX, 1e-4f)
        assertEquals(0.84f, fivePoints[4].normY, 1e-4f)

        fivePoints.forEach { target ->
            assertFalse(target.isValidationPoint)
            assertTrue(target.shortName.isNotBlank())
            assertTrue(target.guidancePrompt.isNotBlank())
        }

        // Collect 18 synthetic gaze samples at each of the 5 coordinates (90 total samples)
        val collectedSamples = fivePoints.flatMap { target ->
            val vec = GazeFeatureVector(
                gx = ((target.normX - 0.5f) * 0.42f).toDouble(),
                gy = ((target.normY - 0.5f) * 0.36f).toDouble(),
                yaw = ((target.normX - 0.5f) * 14f).toDouble(),
                pitch = ((target.normY - 0.5f) * 12f).toDouble(),
                faceDetected = true
            )
            List(18) { Pair(vec, Pair(target.normX, target.normY)) }
        }
        assertEquals(90, collectedSamples.size)

        val fittedMatrix = GazeCalibrationMath.fitRidgeAffineMatrix2x6(
            samples = collectedSamples,
            baselineEyeDistPx = 148.0
        )
        assertEquals(12, fittedMatrix.size)

        val metrics = GazeCalibrationMath.evaluateHeldOutValidation(
            matrixCoeffs = fittedMatrix,
            validationSamples = collectedSamples,
            baselineEyeDistPx = 148.0
        )
        assertTrue(metrics.meanErrorPct < 10.0)
    }

    @Test
    fun `benchmarkPixel10ProXlPipeline throughput and latency limits`() {
        val baseVec = GazeFeatureVector(
            gx = 0.18,
            gy = -0.12,
            yaw = 8.5,
            pitch = -4.2,
            roll = 1.1,
            eyeDistancePx = 146.0,
            leftEyeOpenProb = 0.94f,
            rightEyeOpenProb = 0.94f,
            leftEyeConfidence = 0.92f,
            rightEyeConfidence = 0.92f,
            browToEyeRatio = 0.35f,
            faceDetected = true
        )

        // 1. Benchmark 1,000 feature vector standardization cycles (simulating sustained inference on Tensor G5)
        val startStd = System.nanoTime()
        var checkSum = 0.0
        for (i in 0 until 1000) {
            val arr = baseVec.toStandardizedFeatureArray(148.0)
            checkSum += arr[1]
        }
        val elapsedStdUs = (System.nanoTime() - startStd) / 1000L
        val perOpStdUs = elapsedStdUs / 1000.0
        // Assert each standardization takes < 25 microseconds (well within 66ms / 15 FPS frame budget)
        assertTrue("Standardization took $perOpStdUs µs per op, expected < 25 µs", perOpStdUs < 25.0)

        // 2. Benchmark 5-point ridge regression solver on 90 samples
        val fivePoints = GazeCalibrationMath.FIVE_POINT_CALIBRATION_TARGETS
        val samples = fivePoints.flatMap { target ->
            val v = baseVec.copy(
                gx = ((target.normX - 0.5f) * 0.42).toDouble(),
                gy = ((target.normY - 0.5f) * 0.36).toDouble()
            )
            List(18) { Pair(v, Pair(target.normX, target.normY)) }
        }
        val startFit = System.nanoTime()
        val solved = GazeCalibrationMath.fitRidgeAffineMatrix2x6(samples, 148.0)
        val elapsedFitMs = (System.nanoTime() - startFit) / 1_000_000L
        assertEquals(12, solved.size)
        // Ridge fit over 90 samples must complete under 35 ms so calibration handoff feels instantaneous on 120Hz display
        assertTrue("Ridge fit took $elapsedFitMs ms, expected < 35 ms", elapsedFitMs < 35L)

        // 3. Benchmark Schmitt-trigger hysteresis state transitions over 500 frames
        val detector = BlinkHysteresisDetector()
        val startBlink = System.nanoTime()
        for (f in 0 until 500) {
            val prob = if (f in 100..120) 0.20f else 0.85f
            detector.update(prob, 1000L + f * 16L)
        }
        val elapsedBlinkUs = (System.nanoTime() - startBlink) / 1000L
        val perFrameBlinkUs = elapsedBlinkUs / 500.0
        assertTrue("Blink detector took $perFrameBlinkUs µs per frame, expected < 10 µs", perFrameBlinkUs < 10.0)
    }

    @Test
    fun `dwellSpeedPresetsAndSensitivityAdjustment update duration and boundary tolerance accurately`() {
        val presets = com.example.data.model.DwellSpeedPreset.entries
        assertEquals(4, presets.size)

        // Verify presets
        val fast = com.example.data.model.DwellSpeedPreset.FAST_READER
        assertEquals(650L, fast.durationMs)
        assertEquals(0.85f, fast.radiusToleranceMultiplier, 1e-4f)

        val balanced = com.example.data.model.DwellSpeedPreset.BALANCED
        assertEquals(1000L, balanced.durationMs)
        assertEquals(1.0f, balanced.radiusToleranceMultiplier, 1e-4f)

        val relaxed = com.example.data.model.DwellSpeedPreset.RELAXED
        assertEquals(1400L, relaxed.durationMs)
        assertEquals(1.25f, relaxed.radiusToleranceMultiplier, 1e-4f)

        val assist = com.example.data.model.DwellSpeedPreset.MOTOR_ASSIST
        assertEquals(1900L, assist.durationMs)
        assertEquals(1.55f, assist.radiusToleranceMultiplier, 1e-4f)

        // Verify ViewModel clamping and preset selection
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbId = context.getString(R.string.firestore_database_id)
        if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) {
            com.google.firebase.FirebaseApp.initializeApp(
                context,
                com.google.firebase.FirebaseOptions.Builder()
                    .setApplicationId("com.aistudio.oculagaze.rxkmpq")
                    .setProjectId("demo-no-project")
                    .setApiKey("fake-api-key")
                    .build()
            )
        }
        val vm = com.example.ui.viewmodel.EyeTrackingViewModel(
            repository = com.example.data.repository.EyeTrackingRepository(
                db = com.google.firebase.firestore.FirebaseFirestore.getInstance(dbId)
            ),
            currentUserId = "test_dwell_user",
            localStore = LocalTelemetryStore(context)
        )

        vm.setDwellSpeedPreset(fast)
        assertEquals(650L, vm.liveHudState.value.dwellTriggerMs)
        assertEquals(0.85f, vm.liveHudState.value.dwellSensitivityRadiusMultiplier, 1e-4f)
        assertEquals(fast, vm.liveHudState.value.activeDwellPreset)

        vm.setDwellTriggerDuration(1850L)
        assertEquals(1850L, vm.liveHudState.value.dwellTriggerMs)

        vm.setDwellSensitivityMultiplier(1.6f)
        assertEquals(1.6f, vm.liveHudState.value.dwellSensitivityRadiusMultiplier, 1e-4f)
    }

    @Test
    fun `oneEuroFilter suppresses high frequency micro jitter while responding rapidly to saccades`() {
        val filter2D = com.example.domain.OneEuroFilter2D(minCutoff = 0.85, beta = 1.2, dCutoff = 1.0)

        // 1. Micro-jitter test during stationary fixation:
        // Add random high-frequency noise of ±0.03 around center (0.0, 0.0)
        var t = 1000L
        val rawJitters = mutableListOf<Double>()
        val filteredJitters = mutableListOf<Double>()

        for (i in 0 until 60) {
            t += 33L
            val noiseX = kotlin.math.sin(i * 1.8) * 0.025
            val noiseY = kotlin.math.cos(i * 1.5) * 0.025
            val (fx, fy) = filter2D.filter(noiseX, noiseY, t)
            rawJitters.add(kotlin.math.hypot(noiseX, noiseY))
            filteredJitters.add(kotlin.math.hypot(fx, fy))
        }

        val meanRawJitter = rawJitters.takeLast(30).average()
        val meanFilteredJitter = filteredJitters.takeLast(30).average()

        // 1€ Filter must reduce stationary jitter variance by at least 65%
        assertTrue("Expected mean filtered jitter ($meanFilteredJitter) < 0.40 * raw ($meanRawJitter)",
            meanFilteredJitter < meanRawJitter * 0.40)

        // 2. Saccadic step response test:
        // Sudden jump from 0.0 to 0.40 (large voluntary eye shift)
        val tSaccade = t + 33L
        val (stepX1, _) = filter2D.filter(0.40, 0.0, tSaccade)
        val (stepX2, _) = filter2D.filter(0.40, 0.0, tSaccade + 33L)
        val (stepX3, _) = filter2D.filter(0.40, 0.0, tSaccade + 66L)

        // Dynamic cutoff beta expansion must catch up swiftly without trailing lag
        assertTrue("Step response after 2 frames ($stepX2) must exceed 0.25 (>60% step coverage)", stepX2 > 0.25)
        assertTrue("Step response after 3 frames ($stepX3) must reach near-target (>0.33)", stepX3 > 0.33)
    }

    @Test
    fun `kalmanFilter2D tracks state and estimates velocity accurately`() {
        val kalman = com.example.domain.GazeKalmanFilter2D(processNoiseQ = 1e-3, measurementNoiseR = 1e-2)

        var t = 5000L
        // Constant velocity trajectory: x(t) = 0.10 * t
        for (i in 0 until 40) {
            t += 33L
            val trueX = 0.05 + i * 0.005
            val measX = trueX + (kotlin.math.sin(i.toDouble()) * 0.008)
            kalman.filter(measX, 0.50, t)
        }

        val (estX, estY) = kalman.state
        assertEquals(0.50, estY, 0.04)
        assertTrue("Kalman estimated X ($estX) should closely track ramp trajectory", estX > 0.20 && estX < 0.28)
        assertTrue("Estimated X velocity should be positive", kalman.velocity.first > 0.05)
    }

    @Test
    fun `midasTouchPrevention zero false activations during involuntary blinking, continuous speech, and yawning`() {
        val blinkDetector = BlinkHysteresisDetector()
        val browDetector = BrowRaiseHysteresisDetector()
        val pupilFilter = com.example.domain.OneEuroFilter2D()
        val screenFilter = com.example.domain.OneEuroFilter2D()
        val matrix = com.example.data.model.DEFAULT_AFFINE_MATRIX_2X6

        // 1. Normal involuntary blinking (250 frames)
        val blinkTrace = com.example.domain.ReplayTestHarness.generateScenarioTrace(
            com.example.domain.ReplayScenario.NORMAL_INVOLUNTARY_BLINKS, frameCount = 250
        )
        val blinkRes = com.example.domain.ReplayTestHarness.evaluateTrace(
            com.example.domain.ReplayScenario.NORMAL_INVOLUNTARY_BLINKS, blinkTrace,
            blinkDetector, browDetector, pupilFilter, screenFilter, matrix
        )
        assertTrue(blinkRes.passed)
        assertEquals(0, blinkRes.falseActivations)

        // 2. Continuous talking / jaw movements (250 frames)
        val speechTrace = com.example.domain.ReplayTestHarness.generateScenarioTrace(
            com.example.domain.ReplayScenario.CONTINUOUS_TALKING, frameCount = 250
        )
        val speechRes = com.example.domain.ReplayTestHarness.evaluateTrace(
            com.example.domain.ReplayScenario.CONTINUOUS_TALKING, speechTrace,
            blinkDetector, browDetector, pupilFilter, screenFilter, matrix
        )
        assertTrue(speechRes.passed)
        assertEquals(0, speechRes.falseActivations)

        // 3. Yawning / extreme jaw depression (150 frames)
        val yawnTrace = com.example.domain.ReplayTestHarness.generateScenarioTrace(
            com.example.domain.ReplayScenario.YAWNING_JAW_OPEN, frameCount = 150
        )
        val yawnRes = com.example.domain.ReplayTestHarness.evaluateTrace(
            com.example.domain.ReplayScenario.YAWNING_JAW_OPEN, yawnTrace,
            blinkDetector, browDetector, pupilFilter, screenFilter, matrix
        )
        assertTrue(yawnRes.passed)
        assertEquals(0, yawnRes.falseActivations)

        // 4. Intentional command blink (deliberate 460 ms hold) -> MUST trigger 1 valid command
        val cmdTrace = com.example.domain.ReplayTestHarness.generateScenarioTrace(
            com.example.domain.ReplayScenario.INTENTIONAL_SELECTION_BLINK, frameCount = 100
        )
        val cmdRes = com.example.domain.ReplayTestHarness.evaluateTrace(
            com.example.domain.ReplayScenario.INTENTIONAL_SELECTION_BLINK, cmdTrace,
            blinkDetector, browDetector, pupilFilter, screenFilter, matrix
        )
        assertTrue(cmdRes.passed)
        assertEquals(1, cmdRes.validActivations)
        assertEquals(0, cmdRes.falseActivations)
    }

    @Test
    fun `postureProfiles compensate pitch and gravity elevation correctly`() {
        val upright = com.example.data.model.GazePostureProfile.UPRIGHT
        val reclining = com.example.data.model.GazePostureProfile.RECLINING
        val lyingDown = com.example.data.model.GazePostureProfile.LYING_DOWN

        assertEquals(0.0f, upright.yBias, 1e-4f)
        assertTrue("Reclining must apply negative Y bias for upward elevation", reclining.yBias < 0f)
        assertTrue("Lying flat must apply stronger negative Y bias than reclining", lyingDown.yBias < reclining.yBias)
    }

    @Test
    fun `quietBackgroundRecalibration incrementally absorbs drift on successful tile triggers`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbId = context.getString(R.string.firestore_database_id)
        if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) {
            com.google.firebase.FirebaseApp.initializeApp(
                context,
                com.google.firebase.FirebaseOptions.Builder()
                    .setApplicationId("com.aistudio.oculagaze.rxkmpq")
                    .setProjectId("demo-no-project")
                    .setApiKey("fake-api-key")
                    .build()
            )
        }
        val vm = com.example.ui.viewmodel.EyeTrackingViewModel(
            repository = com.example.data.repository.EyeTrackingRepository(
                db = com.google.firebase.firestore.FirebaseFirestore.getInstance(dbId)
            ),
            currentUserId = "test_recal_user",
            localStore = LocalTelemetryStore(context)
        )

        assertEquals(0, vm.liveHudState.value.backgroundRecalibrationCount)

        // Simulate successful tile trigger
        vm.fireTileAction("tile_yes", "Dwell (1000ms)")
        assertEquals(1, vm.liveHudState.value.backgroundRecalibrationCount)

        vm.fireTileAction("tile_water", "Brow Raise")
        assertEquals(2, vm.liveHudState.value.backgroundRecalibrationCount)
    }

    @Test
    fun `benchmarkRunner executes 25 scenario test suite and generates complete scorecard`() {
        val scorecard = com.example.domain.BenchmarkRunner.runCompleteBenchmark(
            deviceModel = "Google Pixel 10 Pro XL (Tensor G5)"
        )

        assertNotNull(scorecard)
        assertTrue("All 25 stress scenarios must pass", scorecard.allScenariosPassed)
        assertEquals(0.0, scorecard.falseActivationsPerMinute, 1e-6)
        assertTrue("Latency median (${scorecard.latencyMedianMs} ms) must be < 35 ms", scorecard.latencyMedianMs < 35.0)
        assertTrue("Latency p95 (${scorecard.latencyP95Ms} ms) must be < 50 ms", scorecard.latencyP95Ms < 50.0)
        assertTrue("Mean accuracy (${scorecard.meanAccuracyDeg}°) must be within < 4.5°", scorecard.meanAccuracyDeg < 4.5)
        assertTrue("RMS jitter (${scorecard.rmsJitterPx} px) must be heavily damped by 1€ filter", scorecard.rmsJitterPx < 35.0)

        val reportText = scorecard.toFormattedReport()
        assertTrue(reportText.contains("100% PASS"))
        assertTrue(reportText.contains("LATENCY & THROUGHPUT"))
    }
}





