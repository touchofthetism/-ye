package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.BlinkClassificationEvent
import com.example.data.model.BlinkType
import com.example.data.model.CalibrationProfile
import com.example.data.model.DEFAULT_AFFINE_MATRIX_2X6
import com.example.data.model.GazeFeatureVector
import com.example.data.repository.LocalTelemetryStore
import com.example.domain.BlinkHysteresisDetector
import com.example.domain.BrowRaiseHysteresisDetector
import com.example.domain.GazeCalibrationMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

        // 4. Window edges (B9). Observed closure = first closed frame to first reopened frame at 30 FPS.
        // A single-frame dip at 30 FPS (33 ms, estimated ~49 ms) is no blink at all...
        assertNull(classifyClosure(closureMs = 33L, cadenceMs = 33L))
        // ...while a blink caught in a single frame at 15 FPS (66 ms + half a frame) is a natural blink.
        assertEquals(BlinkType.NATURAL, classifyClosure(closureMs = 66L, cadenceMs = 66L)?.type)
        assertEquals(BlinkType.NATURAL, classifyClosure(closureMs = 80L)?.type)
        assertEquals(BlinkType.NATURAL, classifyClosure(closureMs = 349L)?.type)
        assertEquals(BlinkType.INTENTIONAL_COMMAND, classifyClosure(closureMs = 350L)?.type)
        assertEquals(BlinkType.INTENTIONAL_COMMAND, classifyClosure(closureMs = 800L)?.type)
        assertEquals(BlinkType.EXTENDED_REST, classifyClosure(closureMs = 801L)?.type)
        assertEquals(BlinkType.EXTENDED_REST, classifyClosure(closureMs = 850L)?.type)
    }

    /** Fresh detector, open frames at [cadenceMs], then one closure of exactly [closureMs] (closed frames at cadence). */
    private fun classifyClosure(closureMs: Long, cadenceMs: Long = 33L): BlinkClassificationEvent? {
        val detector = BlinkHysteresisDetector(
            closeThreshold = 0.45f,
            openThreshold = 0.65f,
            intentionalMinMs = 350L,
            maxCommandMs = 800L
        )
        var t = 10_000L
        repeat(12) { detector.update(0.90f, t); t += cadenceMs }
        val closeAt = t
        assertNull(detector.update(0.20f, closeAt).second)
        var ts = closeAt + cadenceMs
        while (ts < closeAt + closureMs) {
            assertNull(detector.update(0.20f, ts).second)
            ts += cadenceMs
        }
        return detector.update(0.85f, closeAt + closureMs).second
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

        // The hysteresis band lies between release (0.3656) and raise (0.388) (B9)
        assertEquals(BrowRaiseHysteresisDetector.raiseThresholdFor(0.34f, 0.42f), browDetector.raiseThreshold, 1e-6f)
        assertEquals(0.388f, browDetector.raiseThreshold, 1e-4f)
        assertEquals(0.3656f, browDetector.releaseThreshold, 1e-4f)
        assertTrue(browDetector.releaseThreshold < browDetector.raiseThreshold)

        // Disarmed: a value inside the band does not start a raise
        assertEquals(Pair(0f, false), browDetector.update(0.37f, 1800L))
        // Re-arm: a new raise starts...
        assertEquals(Pair(0.08f, false), browDetector.update(0.41f, 1850L))
        // ...and a dip into the band keeps it armed (no release, no fire)
        val (pBand, firedBand) = browDetector.update(0.37f, 2000L)
        assertFalse(firedBand)
        assertEquals(150f / 400f, pBand, 0.01f)
        // Held 410 ms since the re-arm -> fires again exactly once
        assertTrue(browDetector.update(0.41f, 2260L).second)
        assertFalse(browDetector.update(0.41f, 2400L).second)
    }

    @Test
    fun `standardized ridge regression with MAD outlier trimming and local store default opt-in`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val localStore = LocalTelemetryStore(context)
        // Verify Cloud Sync defaults to OFF (opt-in privacy), per account
        assertFalse(localStore.isCloudSyncEnabled("test_local_user"))

        // Convention: +gx / +yaw = looking right, +gy / +pitch = looking down (GazeModels.kt).
        fun featureFor(normX: Float, normY: Float) = GazeFeatureVector(
            gx = ((normX - 0.5f) * 0.4f).toDouble(),
            gy = ((normY - 0.5f) * 0.35f).toDouble(),
            yaw = ((normX - 0.5f) * 12f).toDouble(),
            pitch = ((normY - 0.5f) * 10f).toDouble()
        )
        val jitter = listOf(0.0 to 0.0, 0.004 to -0.003, -0.004 to 0.003, 0.003 to 0.004, -0.003 to -0.004)
        val fitTargets = GazeCalibrationMath.CALIBRATION_AND_VALIDATION_TARGETS.filter { !it.isValidationPoint }
        val inlierSamples = mutableListOf<Pair<GazeFeatureVector, Pair<Float, Float>>>()
        val samples = fitTargets.flatMap { t ->
            val base = featureFor(t.normX, t.normY)
            val target = Pair(t.normX, t.normY)
            // Five open-eye inliers per target force the MAD branch (groups of <= 3 skip it)
            val inliers = jitter.map { (dx, dy) -> Pair(base.copy(gx = base.gx + dx, gy = base.gy + dy), target) }
            inlierSamples += inliers
            // An open-eye micro-saccade outlier (only MAD can remove it) and a blink frame (eye-open filter)
            val saccadeOutlier = base.copy(gx = base.gx + 0.2, gy = base.gy - 0.15)
            val outlierBlink = base.copy(gx = 0.49, gy = -0.49, leftEyeOpenProb = 0.1f, rightEyeOpenProb = 0.1f)
            inliers + listOf(Pair(saccadeOutlier, target), Pair(outlierBlink, target))
        }
        assertEquals(35, samples.size)

        val matrix = GazeCalibrationMath.fitRidgeAffineMatrix2x6(samples, baselineEyeDistPx = 148.0)
        assertEquals(12, matrix.size)
        assertNotEquals("the fit must not fall back to the default matrix", DEFAULT_AFFINE_MATRIX_2X6, matrix)

        // Both outliers are trimmed: the fit equals the fit on the inliers alone
        val inlierOnly = GazeCalibrationMath.fitRidgeAffineMatrix2x6(inlierSamples, baselineEyeDistPx = 148.0)
        for (i in 0 until 12) assertEquals("coefficient $i", inlierOnly[i], matrix[i], 1e-9)

        // Held-out accuracy at the 4 validation targets (raw projection, no metric floors)
        fun heldOutMeanPct(m: List<Double>) = GazeCalibrationMath.HELD_OUT_VALIDATION_TARGETS.map { t ->
            val (px, py) = GazeCalibrationMath.projectGaze(featureFor(t.normX, t.normY), m, 148.0)
            kotlin.math.hypot((px - t.normX).toDouble(), (py - t.normY).toDouble()) * 100.0
        }.average()
        val fittedPct = heldOutMeanPct(matrix)
        assertTrue("held-out error of the trimmed fit is $fittedPct%", fittedPct < 1.0)
        assertTrue("the default matrix must be measurably worse", heldOutMeanPct(DEFAULT_AFFINE_MATRIX_2X6) > 2.0 * fittedPct)

        localStore.saveLocalProfile(
            userId = "test_local_user",
            profile = CalibrationProfile(
                id = "local_cal_1",
                name = "Offline Pixel 10 Pro XL Profile",
                matrixCoeffs = matrix,
                meanErrorPct = 9.8,
                maxErrorPct = 12.4,
                closeBlinkThreshold = 0.30,
                openBlinkThreshold = 0.60,
                isActive = true
            )
        )

        val loaded = localStore.loadLocalProfiles("test_local_user")
        assertEquals(1, loaded.size)
        assertEquals("Offline Pixel 10 Pro XL Profile", loaded.first().name)
        assertEquals(12.4, loaded.first().maxErrorPct, 0.01)
        assertEquals(9.8, loaded.first().meanErrorPct, 0.01)
        // The fitted matrix and settings survive the round trip (not replaced by defaults)
        assertEquals(12, loaded.first().matrixCoeffs.size)
        for (i in 0 until 12) assertEquals("stored coefficient $i", matrix[i], loaded.first().matrixCoeffs[i], 1e-9)
        assertEquals(0.30, loaded.first().closeBlinkThreshold, 1e-9)
        assertEquals(0.60, loaded.first().openBlinkThreshold, 1e-9)
        assertTrue(loaded.first().isActive)
    }

    @Test
    fun `pixel10ProXl resolution tiers and lowLightDegraded flag leaves the fitted feature scale unchanged`() {
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

        // The same standardization feeds the ridge fit and runtime projection, so a per-frame low-light
        // flag must not rescale the iris regressors (a 0.45x gain switch mis-scaled the fitted matrix, A56)
        assertEquals(1.0, brightVec[1], 1e-9) // gx 0.25 / 0.25
        assertEquals(-1.0, brightVec[2], 1e-9) // gy -0.20 / 0.20
        assertEquals(brightVec[1], dimVec[1], 1e-9)
        assertEquals(brightVec[2], dimVec[2], 1e-9)
        assertEquals(brightVec[3], dimVec[3], 1e-6)
        assertEquals(brightVec[4], dimVec[4], 1e-6)
        assertEquals(
            GazeCalibrationMath.projectGaze(brightFeature, DEFAULT_AFFINE_MATRIX_2X6),
            GazeCalibrationMath.projectGaze(dimFeature, DEFAULT_AFFINE_MATRIX_2X6)
        )
    }

    @Test
    fun `privacy settings toggle persists explicit opt-in and opt-out and clearAllLocalData wipes user vault`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val localStore = LocalTelemetryStore(context)

        // Default is opted-out (false)
        assertFalse(localStore.isCloudSyncEnabled("privacy_test_uid"))

        // Explicit opt-in persists true, for this account only (A238: a shared device must not hand the
        // previous account's consent to the next one)
        localStore.setCloudSyncEnabled("privacy_test_uid", true)
        assertTrue(localStore.isCloudSyncEnabled("privacy_test_uid"))
        assertTrue(LocalTelemetryStore(context).isCloudSyncEnabled("privacy_test_uid"))
        assertFalse(localStore.isCloudSyncEnabled("second_account_uid"))

        // Explicit opt-out persists false
        localStore.setCloudSyncEnabled("privacy_test_uid", false)
        assertFalse(localStore.isCloudSyncEnabled("privacy_test_uid"))

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
        assertNotEquals("the solver must not fall back to the default matrix", DEFAULT_AFFINE_MATRIX_2X6, fittedMatrix)

        // Validate on the 4 held-out targets (never on the fit samples), same synthetic eye + head model
        val heldOut = GazeCalibrationMath.HELD_OUT_VALIDATION_TARGETS.map { target ->
            Pair(
                GazeFeatureVector(
                    gx = ((target.normX - 0.5f) * 0.42f).toDouble(),
                    gy = ((target.normY - 0.5f) * 0.36f).toDouble(),
                    yaw = ((target.normX - 0.5f) * 14f).toDouble(),
                    pitch = ((target.normY - 0.5f) * 12f).toDouble(),
                    faceDetected = true
                ),
                Pair(target.normX, target.normY)
            )
        }
        fun rawMeanPct(m: List<Double>) = heldOut.map { (f, t) ->
            val (px, py) = GazeCalibrationMath.projectGaze(f, m, 148.0)
            kotlin.math.hypot((px - t.first).toDouble(), (py - t.second).toDouble()) * 100.0
        }.average()
        val fittedPct = rawMeanPct(fittedMatrix)
        assertTrue("held-out error of the fit is $fittedPct%", fittedPct < 1.0)
        assertTrue("the fit must improve on the default matrix", rawMeanPct(DEFAULT_AFFINE_MATRIX_2X6) > 1.5)

        // The reported metrics are the measured values (no 1.5 % / 2 mm floors) and pick the 3x3 deck
        val metrics = GazeCalibrationMath.evaluateHeldOutValidation(
            matrixCoeffs = fittedMatrix,
            validationSamples = heldOut,
            baselineEyeDistPx = 148.0
        )
        assertEquals(fittedPct, metrics.meanErrorPct, 1e-9)
        val rawMeanMm = heldOut.map { (f, t) ->
            val (px, py) = GazeCalibrationMath.projectGaze(f, fittedMatrix, 148.0)
            kotlin.math.hypot(
                (px - t.first) * GazeCalibrationMath.PIXEL_10_PRO_XL_WIDTH_MM,
                (py - t.second) * GazeCalibrationMath.PIXEL_10_PRO_XL_HEIGHT_MM
            )
        }.average()
        assertEquals(rawMeanMm, metrics.approxMeanErrorMm, 1e-9)
        assertTrue(metrics.approxMeanErrorMm < 2.0)
        assertTrue(metrics.maxErrorPct >= metrics.meanErrorPct)
        assertEquals(GazeCalibrationMath.gridLayoutLabel(true), metrics.recommendedGridDesc)
    }

    @Test
    fun `benchmarkPixel10ProXlPipeline throughput and latency limits`() {
        // Functional checks always run. The wall-clock bounds are host-JVM relative timings (not Tensor G5 or
        // 120 Hz measurements) and are asserted only in the opt-in benchmark run (-Pbenchmark), where they
        // cannot fail a normal build on a loaded CI runner (B7).
        val timingAsserted = System.getProperty("oculagaze.benchmark") == "true"
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

        // 1. 1,000 feature vector standardization cycles (host JVM)
        val startStd = System.nanoTime()
        var checkSum = 0.0
        for (i in 0 until 1000) {
            val arr = baseVec.toStandardizedFeatureArray(148.0)
            checkSum += arr[1]
        }
        val elapsedStdUs = (System.nanoTime() - startStd) / 1000L
        val perOpStdUs = elapsedStdUs / 1000.0
        assertTrue(checkSum.isFinite())
        assertEquals(1000 * (0.18 / 0.25), checkSum, 1e-6)
        if (timingAsserted) {
            assertTrue("Standardization took $perOpStdUs µs per op, expected < 25 µs", perOpStdUs < 25.0)
        }

        // 2. 5-point ridge regression solver on 90 samples
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
        assertNotEquals(DEFAULT_AFFINE_MATRIX_2X6, solved)
        if (timingAsserted) {
            assertTrue("Ridge fit took $elapsedFitMs ms, expected < 35 ms", elapsedFitMs < 35L)
        }

        // 3. Schmitt-trigger hysteresis state transitions over 500 frames (one 336 ms closure)
        val detector = BlinkHysteresisDetector()
        val events = mutableListOf<BlinkClassificationEvent>()
        val startBlink = System.nanoTime()
        for (f in 0 until 500) {
            val prob = if (f in 100..120) 0.20f else 0.85f
            detector.update(prob, 1000L + f * 16L).second?.let { events += it }
        }
        val elapsedBlinkUs = (System.nanoTime() - startBlink) / 1000L
        val perFrameBlinkUs = elapsedBlinkUs / 500.0
        assertEquals(1, events.size)
        assertEquals(BlinkType.NATURAL, events.single().type)
        assertEquals(336L, events.single().durationMs)
        if (timingAsserted) {
            assertTrue("Blink detector took $perFrameBlinkUs µs per frame, expected < 10 µs", perFrameBlinkUs < 10.0)
        }
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
        // A custom duration selects the nearest preset (1900 ms is 50 ms away)
        assertEquals(assist, vm.liveHudState.value.activeDwellPreset)

        vm.setDwellSensitivityMultiplier(1.6f)
        assertEquals(1.6f, vm.liveHudState.value.dwellSensitivityRadiusMultiplier, 1e-4f)

        // Out-of-range values are clamped (B10): a 50 ms dwell would make every glance a selection
        vm.setDwellTriggerDuration(50L)
        assertEquals(400L, vm.liveHudState.value.dwellTriggerMs)
        assertEquals(fast, vm.liveHudState.value.activeDwellPreset)
        vm.setDwellTriggerDuration(10_000L)
        assertEquals(2500L, vm.liveHudState.value.dwellTriggerMs)
        assertEquals(assist, vm.liveHudState.value.activeDwellPreset)
        vm.setDwellSensitivityMultiplier(0.1f)
        assertEquals(0.6f, vm.liveHudState.value.dwellSensitivityRadiusMultiplier, 1e-4f)
        vm.setDwellSensitivityMultiplier(5f)
        assertEquals(2.0f, vm.liveHudState.value.dwellSensitivityRadiusMultiplier, 1e-4f)
    }

    @Test
    fun `oneEuroFilter suppresses high frequency micro jitter while responding rapidly to saccades`() {
        // The live screen gaze filter (EyeTrackingViewModel / BenchmarkRunner SCREEN_FILTER_*)
        val filter2D = com.example.domain.OneEuroFilter2D(minCutoff = 0.90, beta = 1.2, dCutoff = 1.0)

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
        // Laid-out tile centres in the gaze frame (what the Control Deck reports)
        vm.updateTileCentersNorm(mapOf("tile_yes" to (0.2f to 0.6f), "tile_water" to (0.8f to 0.6f)))
        val start = vm.currentMatrixForTest()
        assertEquals(DEFAULT_AFFINE_MATRIX_2X6, start)

        // A tap carries no gaze information: no nudge, no count
        vm.fireTileAction("tile_yes", "Tap")
        assertEquals(0, vm.liveHudState.value.backgroundRecalibrationCount)
        assertEquals(start, vm.currentMatrixForTest())

        // Gaze-triggered activation: the intercepts move toward the activated tile's centre. The gaze is
        // still at the centre (0.5, 0.5), so the residual is (-0.3, +0.1) for tile_yes.
        vm.fireTileAction("tile_yes", "Dwell (1000ms)")
        assertEquals(1, vm.liveHudState.value.backgroundRecalibrationCount)
        val afterYes = vm.currentMatrixForTest()
        assertTrue("w0 must decrease toward x=0.2 (got ${afterYes[0]})", afterYes[0] < start[0])
        assertTrue("w6 must increase toward y=0.6 (got ${afterYes[6]})", afterYes[6] > start[6])
        val neutral = GazeFeatureVector()
        val (x0, y0) = GazeCalibrationMath.projectGaze(neutral, start)
        val (x1, y1) = GazeCalibrationMath.projectGaze(neutral, afterYes)
        assertTrue(
            "the residual to the tile centre must shrink",
            kotlin.math.hypot((x1 - 0.2f).toDouble(), (y1 - 0.6f).toDouble()) <
                kotlin.math.hypot((x0 - 0.2f).toDouble(), (y0 - 0.6f).toDouble())
        )
        // Only the intercepts change
        for (i in listOf(1, 2, 3, 4, 5, 7, 8, 9, 10, 11)) assertEquals(start[i], afterYes[i], 0.0)

        vm.fireTileAction("tile_water", "Brow Raise")
        assertEquals(2, vm.liveHudState.value.backgroundRecalibrationCount)
        assertTrue("w0 must move back toward x=0.8", vm.currentMatrixForTest()[0] > afterYes[0])

        // No known centre (tile not laid out) or the recenter tile: nothing is absorbed
        val beforeUnknown = vm.currentMatrixForTest()
        vm.fireTileAction("tile_no", "Dwell (1000ms)")
        vm.fireTileAction("tile_recenter", "Dwell (1000ms)")
        assertEquals(2, vm.liveHudState.value.backgroundRecalibrationCount)
        assertEquals(beforeUnknown, vm.currentMatrixForTest())
    }

    @Test
    fun `benchmarkRunner executes 25 scenario test suite and generates complete scorecard`() {
        val scorecard = com.example.domain.BenchmarkRunner.runCompleteBenchmark(
            deviceModel = "Google Pixel 10 Pro XL (Tensor G5)"
        )

        assertNotNull(scorecard)
        assertEquals(25, scorecard.scenarioResults.size)
        assertEquals(com.example.domain.ReplayScenario.entries.size, scorecard.scenarioResults.size)
        assertEquals(
            com.example.domain.ReplayScenario.entries.toSet(),
            scorecard.scenarioResults.map { it.scenario }.toSet()
        )
        assertTrue("All 25 stress scenarios must pass", scorecard.allScenariosPassed)
        // The gated scenarios can fail: exactly one deliberate gesture per command trace, none elsewhere
        scorecard.scenarioResults.forEach { r ->
            if (r.scenario.category == "COMMAND") {
                assertEquals("${r.scenario}", 1, r.validActivations)
            } else {
                assertEquals("${r.scenario}", 0, r.validActivations)
            }
            assertEquals("${r.scenario}", 0, r.falseActivations)
        }
        assertEquals(0.0, scorecard.falseActivationsPerMinute, 1e-6)
        // Measured (unfloored) per-frame times: positive, and far below the old 18 / 26 ms floors
        assertTrue("Latency median (${scorecard.latencyMedianMs} ms) must be measured", scorecard.latencyMedianMs > 0.0)
        assertTrue("Latency median (${scorecard.latencyMedianMs} ms) must be < 35 ms", scorecard.latencyMedianMs < 35.0)
        assertTrue("Latency p95 (${scorecard.latencyP95Ms} ms) must be < 50 ms", scorecard.latencyP95Ms < 50.0)
        assertTrue(scorecard.latencyP95Ms >= scorecard.latencyMedianMs)
        // Convention-consistent synthesis gives ~52 px / 0.45 deg; the inverted yaw/pitch sign gave 466 px / 4.0 deg
        assertTrue("Mean accuracy (${scorecard.meanAccuracyPx} px) must be < 80 px", scorecard.meanAccuracyPx < 80.0)
        assertTrue("Mean accuracy (${scorecard.meanAccuracyDeg}°) must be < 1.0°", scorecard.meanAccuracyDeg < 1.0)
        assertTrue("RMS jitter (${scorecard.rmsJitterPx} px) must be heavily damped by 1€ filter", scorecard.rmsJitterPx < 35.0)

        val reportText = scorecard.toFormattedReport()
        assertTrue(reportText.contains("100% PASS"))
        assertTrue(reportText.contains("LATENCY & THROUGHPUT"))
    }
}
