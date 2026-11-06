package com.example.regression

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.CalibrationProfile
import com.example.data.model.TrackingSession
import com.example.data.repository.EyeTrackingRepository
import com.example.data.repository.OperationType
import com.example.data.repository.handleFirestoreError
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Regression tests for the Firestore error logger. They need no emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CloudRegressionTest {

    @Test
    fun errorLog_containsNoIdentity_andRedactsUidInPath() {
        val json = JSONObject(
            handleFirestoreError(
                FirebaseFirestoreException("Missing or insufficient permissions.", FirebaseFirestoreException.Code.PERMISSION_DENIED),
                OperationType.DELETE,
                "users/uid_alice_42/calibrations/local_cal_1",
                isSignedIn = true
            )
        )
        assertFalse("auth/identity block must not be logged: $json", json.has("authInfo"))
        assertFalse("no email may be logged: $json", json.toString().contains("email"))
        assertFalse("uid must be redacted from the path: $json", json.toString().contains("uid_alice_42"))
        assertEquals("users/{uid}/calibrations/local_cal_1", json.getString("path"))
        assertEquals(true, json.getBoolean("signedIn"))
    }

    @Test
    fun errorLog_unwrapsSnapshotListenerCancellation_toFirestoreCode() {
        // Query.snapshots() reports listener failures as cancel("Error getting Query snapshot", cause).
        val wrapped = CancellationException(
            "Error getting Query snapshot",
            FirebaseFirestoreException("Missing or insufficient permissions.", FirebaseFirestoreException.Code.PERMISSION_DENIED)
        )
        val json = JSONObject(handleFirestoreError(wrapped, OperationType.LIST, "users/u1/sessions"))
        assertEquals("PERMISSION_DENIED", json.getString("code"))
        assertEquals("Missing or insufficient permissions.", json.getString("error"))
    }

    @Test
    fun errorLog_redactsUidInsideFirestoreMessage() {
        val ex = FirebaseFirestoreException(
            "No document to update: projects/p/databases/d/documents/users/uid_bob_7/calibrations/cal_9",
            FirebaseFirestoreException.Code.NOT_FOUND
        )
        val json = JSONObject(handleFirestoreError(ex, OperationType.UPDATE, null))
        assertFalse("uid must be redacted from the message: $json", json.getString("error").contains("uid_bob_7"))
        assertTrue(json.getString("error").contains("users/{uid}/calibrations/cal_9"))
        assertEquals("NOT_FOUND", json.getString("code"))
    }
}

/**
 * Repository and rules regressions against the local Firestore and Auth emulators (same setup as
 * EyeTrackingRepositoryRuleTest). Each test signs in a fresh user so leftover data cannot interfere.
 */
@Config(sdk = [36])
class CloudEmulatorRegressionTest : FirestoreEmulatorTestBase() {

    private fun freshEmail() = "cloudreg_${UUID.randomUUID().toString().take(8)}@oculagaze.test"
    private fun freshId(prefix: String) = "${prefix}_${UUID.randomUUID().toString().replace("-", "").take(12)}"

    private suspend fun rawIsActive(uid: String, profileId: String): Boolean? =
        firestore.collection("users").document(uid).collection("calibrations").document(profileId)
            .get().await().getBoolean("isActive")

    @Test
    fun activation_isExclusive_onCreateAndOnActivate() = runBlocking {
        val uid = signInTestUser(freshEmail())
        val repository = EyeTrackingRepository(firestore, auth)
        val oldId = freshId("cal_old")
        val newId = freshId("cal_new")

        withTimeout(TIMEOUT_MS) {
            repository.createCalibrationProfile(CalibrationProfile(id = oldId, userId = uid, name = "Monday", isActive = true)).getOrThrow()
            repository.createCalibrationProfile(CalibrationProfile(id = newId, userId = uid, name = "Tuesday", isActive = true)).getOrThrow()
        }
        assertEquals("creating an active profile must deactivate the previous one", false, withTimeout(TIMEOUT_MS) { rawIsActive(uid, oldId) })
        assertEquals(true, withTimeout(TIMEOUT_MS) { rawIsActive(uid, newId) })

        withTimeout(TIMEOUT_MS) { repository.updateCalibrationActiveState(oldId, true).getOrThrow() }
        assertEquals(true, withTimeout(TIMEOUT_MS) { rawIsActive(uid, oldId) })
        assertEquals("activating a profile must deactivate the others", false, withTimeout(TIMEOUT_MS) { rawIsActive(uid, newId) })
    }

    @Test
    fun deleteAndGet_ofMissingDocument_succeed() = runBlocking {
        val uid = signInTestUser(freshEmail())
        val repository = EyeTrackingRepository(firestore, auth)

        val deleteCal = withTimeout(TIMEOUT_MS) { repository.deleteCalibrationProfile(freshId("local_cal")) }
        assertTrue("delete of a missing profile must be idempotent: ${deleteCal.exceptionOrNull()}", deleteCal.isSuccess)
        val deleteSess = withTimeout(TIMEOUT_MS) { repository.deleteTrackingSession(freshId("sess_missing")) }
        assertTrue("delete of a missing session must be idempotent: ${deleteSess.exceptionOrNull()}", deleteSess.isSuccess)

        val missing = withTimeout(TIMEOUT_MS) { repository.getCalibrationProfileById(uid, freshId("cal_missing")) }
        assertTrue("get of a missing profile must succeed: ${missing.exceptionOrNull()}", missing.isSuccess)
        assertNull(missing.getOrNull())
    }

    @Test
    fun listenerError_reachesCaller_asFirebaseFirestoreException() = runBlocking {
        auth.signOut()
        val repository = EyeTrackingRepository(firestore, auth)

        var caught: Throwable? = null
        try {
            withTimeout(TIMEOUT_MS) { repository.observeCalibrationProfiles("unauth_user_cloudreg").first() }
        } catch (e: Throwable) {
            caught = e
        }
        assertTrue("expected the FirebaseFirestoreException itself, got $caught", caught is FirebaseFirestoreException)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, (caught as FirebaseFirestoreException).code)
    }

    @Test
    fun eraseAll_whileOffline_failsFastInsteadOfHangingOrFakingSuccess() = runBlocking {
        val uid = signInTestUser(freshEmail())
        val repository = EyeTrackingRepository(firestore, auth)
        withTimeout(TIMEOUT_MS) {
            repository.createTrackingSession(TrackingSession(id = freshId("sess"), userId = uid, title = "Offline erase", durationSeconds = 10)).getOrThrow()
        }

        firestore.disableNetwork().await()
        try {
            val result = withTimeout(TIMEOUT_MS) { repository.eraseAllUserCloudTelemetry() }
            assertTrue("offline erase must report failure, got $result", result.isFailure)
        } finally {
            firestore.enableNetwork().await()
        }
    }

    @Test
    fun blinkSettings_areStoredWithinTheUsableRange() = runBlocking {
        val uid = signInTestUser(freshEmail())
        val repository = EyeTrackingRepository(firestore, auth)
        val id = freshId("cal_blink")

        val created = withTimeout(TIMEOUT_MS) {
            repository.createCalibrationProfile(
                CalibrationProfile(
                    id = id,
                    userId = uid,
                    name = "Inverted blink",
                    closeBlinkThreshold = 0.7,
                    openBlinkThreshold = 0.5,
                    intentionalBlinkMinMs = 1000L,
                    isActive = false
                )
            )
        }
        assertTrue("create failed: ${created.exceptionOrNull()}", created.isSuccess)
        val doc = withTimeout(TIMEOUT_MS) {
            firestore.collection("users").document(uid).collection("calibrations").document(id).get().await()
        }
        val minMs = checkNotNull(doc.getLong("intentionalBlinkMinMs"))
        assertTrue("intentionalBlinkMinMs=$minMs leaves no command window below 800 ms", minMs <= 700L)
        val close = checkNotNull(doc.getDouble("closeBlinkThreshold"))
        val open = checkNotNull(doc.getDouble("openBlinkThreshold"))
        assertTrue("hysteresis inverted: close=$close open=$open", close + 0.05 <= open)
    }

    @Test
    fun rules_rejectMatrixWithNonNumberAfterIndexZero() = runBlocking {
        val uid = signInTestUser(freshEmail())
        val docRef = firestore.collection("users").document(uid).collection("calibrations").document(freshId("cal_mixed"))
        val coeffs: List<Any> = listOf(0.5, "evil", 0.0, 0.32, 0.0, 0.01, 0.5, 0.0, 0.44, 0.0, 0.36, -0.01)
        val payload = mapOf(
            "userId" to uid,
            "name" to "Mixed matrix",
            "deviceModel" to "Pixel 10 Pro XL",
            "matrixCoeffs" to coeffs,
            "baselineEyeDistancePx" to 148.0,
            "meanErrorMm" to 13.0,
            "meanErrorPct" to 10.0,
            "maxErrorPct" to 14.0,
            "closeBlinkThreshold" to 0.25,
            "openBlinkThreshold" to 0.55,
            "intentionalBlinkMinMs" to 350L,
            "isActive" to false,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        val failure = runCatching { withTimeout(TIMEOUT_MS) { docRef.set(payload).await() } }.exceptionOrNull()
        assertNotNull("a 12-element matrix with a string at index 1 must be rejected", failure)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, failure?.unwrapFirestoreException()?.code)
    }

    @Test
    fun unreadableCloudDocument_isSkipped_notFatalForTheWholeList() = runBlocking {
        val uid = signInTestUser(freshEmail())
        val repository = EyeTrackingRepository(firestore, auth)
        val goodId = freshId("cal_good")
        withTimeout(TIMEOUT_MS) {
            repository.createCalibrationProfile(CalibrationProfile(id = goodId, userId = uid, name = "Readable", isActive = true)).getOrThrow()
        }
        // A document an older or other client wrote (bypassing today's rules): matrixCoeffs[1] is a string.
        withContext(Dispatchers.IO) { writeMalformedProfileBypassingRules(uid, freshId("cal_bad")) }

        val result = withTimeout(TIMEOUT_MS) { repository.getCalibrationProfiles(uid) }
        assertTrue("one unreadable document must not fail the whole list: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals(listOf(goodId), result.getOrThrow().map { it.id })
    }

    /** Emulator REST write with the "owner" token, which bypasses security rules. */
    private fun writeMalformedProfileBypassingRules(uid: String, docId: String) {
        val url = URL(
            "http://$EMULATOR_HOST:$FIRESTORE_PORT/v1/projects/$PROJECT_ID/databases/$databaseId/documents/users/$uid/calibrations?documentId=$docId"
        )
        val body = """
            {"fields": {
              "userId": {"stringValue": "$uid"},
              "name": {"stringValue": "Legacy malformed"},
              "deviceModel": {"stringValue": "Pixel 10 Pro XL"},
              "matrixCoeffs": {"arrayValue": {"values": [{"doubleValue": 0.5}, {"stringValue": "evil"}]}},
              "isActive": {"booleanValue": false}
            }}
        """.trimIndent()
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer owner")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            check(code in 200..299) { "emulator REST write failed with HTTP $code" }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 8000L
        // Must match FirestoreEmulatorTestBase.
        const val EMULATOR_HOST = "127.0.0.1"
        const val FIRESTORE_PORT = 8085
        const val PROJECT_ID = "demo-no-project"
    }
}
