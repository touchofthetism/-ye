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
}
