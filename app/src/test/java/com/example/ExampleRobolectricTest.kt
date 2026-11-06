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
        // Ridge fit over 90 samples must complete under 15 ms so calibration handoff feels instantaneous on 120Hz display
        assertTrue("Ridge fit took $elapsedFitMs ms, expected < 15 ms", elapsedFitMs < 15L)

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
}



