package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.CalibrationProfile
import com.example.data.model.DEFAULT_AFFINE_MATRIX_2X6
import com.example.data.model.TrackingSession
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class EyeTrackingRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun createAndObserveCalibrationProfile_authenticatedOwner_succeeds() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = EyeTrackingRepository(firestore, auth)
        val profileId = "cal_${UUID.randomUUID().toString().replace("-", "").take(12)}"

        val createResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createCalibrationProfile(
                CalibrationProfile(
                    id = profileId,
                    userId = aliceUid,
                    name = "Pixel 10 Pro XL Desk Calibration",
                    deviceModel = "Pixel 10 Pro XL",
                    matrixCoeffs = DEFAULT_AFFINE_MATRIX_2X6,
                    baselineEyeDistancePx = 148.0,
                    meanErrorMm = 12.6,
                    meanErrorPct = 10.4,
                    maxErrorPct = 13.8,
                    closeBlinkThreshold = 0.25,
                    openBlinkThreshold = 0.55,
                    intentionalBlinkMinMs = 350L,
                    isActive = true
                )
            )
        }
        assertTrue("Expected calibration creation to succeed", createResult.isSuccess)

        val emitted = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeCalibrationProfiles(aliceUid).first { list ->
                list.any { it.id == profileId }
            }
        }
        assertTrue(emitted.any { it.id == profileId && it.name == "Pixel 10 Pro XL Desk Calibration" && it.maxErrorPct == 13.8 })
    }

    @Test
    fun createAndObserveTrackingSession_authenticatedOwner_succeeds() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = EyeTrackingRepository(firestore, auth)
        val sessionId = "sess_${UUID.randomUUID().toString().replace("-", "").take(12)}"

        val createResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createTrackingSession(
                TrackingSession(
                    id = sessionId,
                    userId = aliceUid,
                    title = "Optical Reading Pass",
                    deviceModel = "Pixel 10 Pro XL",
                    contentType = "READING_SECTIONS",
                    durationSeconds = 30,
                    naturalBlinkCount = 8,
                    intentionalBlinkCount = 2,
                    blinksPerMinute = 16.0,
                    effectiveWordsPerMinute = 195,
                    fixationClusterCount = 6,
                    topZonePercentage = 40,
                    middleZonePercentage = 35,
                    bottomZonePercentage = 25,
                    sectionLoads = listOf("sec_1:Introduction:40", "sec_2:Methods:35", "sec_3:Results:25"),
                    clusterPoints = listOf("0.45,0.25,1100", "0.52,0.55,1400")
                )
            )
        }
        assertTrue("Expected session creation to succeed", createResult.isSuccess)

        val emitted = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeTrackingSessions(aliceUid).first { list ->
                list.any { it.id == sessionId }
            }
        }
        assertTrue(emitted.any { it.id == sessionId && it.effectiveWordsPerMinute == 195 })
    }

    @Test
    fun getCalibrationProfileById_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = EyeTrackingRepository(firestore, auth)
        val profileId = "cal_${UUID.randomUUID().toString().replace("-", "").take(12)}"

        val createdId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.createCalibrationProfile(
                CalibrationProfile(
                    id = profileId,
                    userId = aliceUid,
                    name = "Alice Private Calibration"
                )
            ).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = EyeTrackingRepository(firestore, auth)
        val bobAttempt = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.getCalibrationProfileById(aliceUid, createdId)
        }
        assertTrue("Expected cross-user read to fail", bobAttempt.isFailure)
        val firestoreEx = bobAttempt.exceptionOrNull()?.unwrapFirestoreException()
        assertNotNull("Expected FirebaseFirestoreException", firestoreEx)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreEx?.code)
    }

    @Test
    fun observeCalibrationProfiles_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repository = EyeTrackingRepository(firestore, auth)

        var caughtException: Throwable? = null
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeCalibrationProfiles("unauth_user_123").first()
            }
        } catch (e: Throwable) {
            caughtException = e
        }
        assertNotNull("Expected exception for unauthenticated query", caughtException)
        val firestoreException = caughtException?.unwrapFirestoreException()
        assertNotNull("Expected nested FirebaseFirestoreException, got $caughtException", firestoreException)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException?.code)
    }

    @Test
    fun eraseAllUserCloudTelemetry_authenticatedOwner_deletesAllCalibrationsAndSessions() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = EyeTrackingRepository(firestore, auth)
        val profileId = "cal_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val sessionId = "sess_${UUID.randomUUID().toString().replace("-", "").take(12)}"

        withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.createCalibrationProfile(
                CalibrationProfile(
                    id = profileId,
                    userId = aliceUid,
                    name = "Profile To Delete"
                )
            ).getOrThrow()
            repository.createTrackingSession(
                TrackingSession(
                    id = sessionId,
                    userId = aliceUid,
                    title = "Session To Delete",
                    durationSeconds = 20
                )
            ).getOrThrow()
        }

        val eraseResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.eraseAllUserCloudTelemetry()
        }
        assertTrue("Expected eraseAllUserCloudTelemetry to succeed", eraseResult.isSuccess)
        assertEquals(2, eraseResult.getOrNull())

        val remainingProfiles = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.getCalibrationProfiles(aliceUid).getOrThrow()
        }
        assertTrue("Expected all Firestore calibration profiles to be deleted", remainingProfiles.isEmpty())
    }

    private companion object {
        const val ALICE_EMAIL = "alice@oculagaze.test"
        const val BOB_EMAIL = "bob@oculagaze.test"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
