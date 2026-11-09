package com.example.optimization

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.CalibrationProfile
import com.example.data.model.DEFAULT_AFFINE_MATRIX_2X6
import com.example.data.model.TrackingSession
import com.example.data.model.calibrationPostureProfile
import com.example.data.repository.CloudEraseIncompleteException
import com.example.data.repository.EyeTrackingRepository
import com.example.data.repository.OperationType
import com.example.data.repository.findFirestoreException
import com.example.data.repository.handleFirestoreError
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.UUID
import kotlin.random.Random

private const val SEED = 20_261_106L
private const val FIRESTORE_ERROR_TAG = "FirestoreError"

/** One repository call and the error-log path the pre-optimization inline template produced for it. */
private class LoggedCase(
    val label: String,
    val operation: OperationType,
    val frozenPath: String,
    val call: suspend () -> Result<*>
)

/**
 * Runs each case and asserts the repository logged exactly what the frozen inline template would
 * have: reportError(e, op, path) == handleFirestoreError(e, op, path, signedInOrNull()).
 */
private suspend fun assertLogsMatchFrozenTemplates(cases: List<LoggedCase>, signedIn: Boolean) {
    for (case in cases) {
        val before = ShadowLog.getLogsForTag(FIRESTORE_ERROR_TAG).size
        val result = case.call()
        val failure = result.exceptionOrNull()
        assertNotNull("${case.label}: the call must fail on the client, got $result", failure)
        val logged = ShadowLog.getLogsForTag(FIRESTORE_ERROR_TAG).drop(before).map { it.msg }
        val expected = "Firestore Error: " + handleFirestoreError(failure as Exception, case.operation, case.frozenPath, signedIn)
        assertEquals(case.label, listOf(expected), logged)
    }
}

private fun randomDocId(rng: Random): String {
    val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-"
    return "p_" + String(CharArray(rng.nextInt(1, 24)) { alphabet[rng.nextInt(alphabet.length)] })
}

/**
 * Equivalence tests for the EyeTrackingRepository optimization pass that need no emulator: the
 * error-path helper (currentUserPath) must build the same logged path as the six inline
 * "users/${uid ?: "unauthenticated"}..." templates it replaced, and the shared activation field
 * maps must equal the per-call maps they replaced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CloudOptimizationTest {

    /** A named app, so the default app other tests configure is left alone. Nothing here reaches the network. */
    private fun signedOutAuthAndDb(): Pair<FirebaseAuth, FirebaseFirestore> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME }
            ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("com.aistudio.oculagaze.rxkmpq")
                    .setProjectId("demo-cloud-optimization")
                    .setApiKey("fake-api-key-for-unit-tests")
                    .build(),
                APP_NAME
            )
        val auth = FirebaseAuth.getInstance(app)
        auth.signOut()
        return auth to FirebaseFirestore.getInstance(app)
    }

    @Test
    fun signedOutFailures_logTheSamePathAsTheFrozenInlineTemplates() = runBlocking<Unit> {
        val (auth, db) = signedOutAuthAndDb()
        val repository = EyeTrackingRepository(db, auth)
        val rng = Random(SEED)
        // Fixed edge ids first (only the path text is under test: every call fails at requireUserId).
        val ids = listOf("", "a b", "x/y", "users/nested") + List(40) { randomDocId(rng) }
        for (id in ids) {
            val others = List(rng.nextInt(0, 4)) { randomDocId(rng) }
            val active = rng.nextBoolean()
            // Frozen template, exactly as the six catch blocks wrote it before the pass.
            val frozenPrefix = "users/${auth.currentUser?.uid ?: "unauthenticated"}"
            val cases = listOf(
                LoggedCase("create calibration '$id'", OperationType.CREATE, "$frozenPrefix/calibrations") {
                    repository.createCalibrationProfile(CalibrationProfile(id = id, isActive = active))
                },
                LoggedCase("update active '$id'", OperationType.UPDATE, "$frozenPrefix/calibrations/$id") {
                    repository.updateCalibrationActiveState(id, active)
                },
                LoggedCase("set active '$id'", OperationType.UPDATE, "$frozenPrefix/calibrations/$id") {
                    repository.setActiveCalibrationProfile(id, others)
                },
                LoggedCase("delete calibration '$id'", OperationType.DELETE, "$frozenPrefix/calibrations/$id") {
                    repository.deleteCalibrationProfile(id)
                },
                LoggedCase("create session '$id'", OperationType.CREATE, "$frozenPrefix/sessions") {
                    repository.createTrackingSession(TrackingSession(id = id, title = "Session $id"))
                },
                LoggedCase("delete session '$id'", OperationType.DELETE, "$frozenPrefix/sessions/$id") {
                    repository.deleteTrackingSession(id)
                },
                LoggedCase("erase all", OperationType.DELETE, frozenPrefix) {
                    repository.eraseAllUserCloudTelemetry()
                }
            )
            assertLogsMatchFrozenTemplates(cases, signedIn = false)
        }
    }

    /**
     * activeStateFields now returns one of two shared maps instead of building a map per call. The
     * shared maps must hold exactly what the frozen per-call mapOf built (same keys in the same
     * order, same values, the server-timestamp sentinel itself), and stay unchanged across calls.
     */
    @Test
    fun activeStateFields_sharedMaps_matchTheFrozenPerCallMaps() {
        val (auth, db) = signedOutAuthAndDb()
        val repository = EyeTrackingRepository(db, auth)
        val activeStateFields = EyeTrackingRepository::class.java
            .getDeclaredMethod("activeStateFields", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val rng = Random(SEED + 2)
        repeat(64) {
            val isActive = rng.nextBoolean()
            // Frozen reference, exactly as the pre-optimization activeStateFields built it.
            val frozen: Map<String, Any> = mapOf(
                "isActive" to isActive,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            @Suppress("UNCHECKED_CAST")
            val actual = activeStateFields.invoke(repository, isActive) as Map<String, Any>
            assertEquals(frozen, actual)
            assertEquals(frozen.keys.toList(), actual.keys.toList())
            assertEquals(isActive, actual["isActive"])
            assertSame(FieldValue.serverTimestamp(), actual["updatedAt"])
        }
    }

    @Test
    fun signedOutFailures_returnTheSameResultAsBefore() = runBlocking<Unit> {
        val (auth, db) = signedOutAuthAndDb()
        val repository = EyeTrackingRepository(db, auth)
        val results = listOf(
            repository.eraseAllUserCloudTelemetry(),
            repository.updateCalibrationActiveState("p1", true),
            repository.setActiveCalibrationProfile("p1", listOf("p2")),
            repository.createCalibrationProfile(CalibrationProfile(id = "p1", isActive = true))
        )
        for (result in results) {
            val failure = result.exceptionOrNull()
            assertTrue("expected the requireUserId failure, got $result", failure is IllegalStateException)
            assertEquals("User must be signed in with Google before writing to Firestore.", failure?.message)
        }
    }

    private companion object {
        const val APP_NAME = "cloud-optimization-test"
    }
}

/**
 * Emulator equivalence tests (skipped locally without `firebase emulators:start`, required in CI,
 * like CloudEmulatorRegressionTest). Each round seeds two fresh users with the same data, runs the
 * FROZEN pre-optimization implementation for one and the optimized repository for the other, and
 * compares results and the final server state.
 */
@Config(sdk = [36])
class CloudOptimizationEmulatorTest : FirestoreEmulatorTestBase() {

    private fun freshEmail() = "cloudopt_${UUID.randomUUID().toString().take(8)}@oculagaze.test"

    private fun calibrations(uid: String): CollectionReference =
        firestore.collection("users").document(uid).collection("calibrations")

    private fun sessions(uid: String): CollectionReference =
        firestore.collection("users").document(uid).collection("sessions")

    // ---------------------------------------------------------------------------------------------
    // Frozen reference: the pre-optimization implementation, copied verbatim minus error logging.
    // ---------------------------------------------------------------------------------------------

    private fun frozenActiveStateFields(isActive: Boolean): Map<String, Any> = mapOf(
        "isActive" to isActive,
        "updatedAt" to FieldValue.serverTimestamp()
    )

    private suspend fun frozenOtherActiveProfileRefs(
        uid: String,
        keepId: String,
        alsoDeactivateIds: Set<String> = emptySet()
    ): List<DocumentReference> =
        calibrations(uid)
            .whereEqualTo("userId", uid)
            .get()
            .await()
            .documents
            .filter { it.id != keepId && (it.get("isActive") == true || it.id in alsoDeactivateIds) }
            .map { it.reference }

    private suspend fun frozenCommitActiveState(
        uid: String,
        profileId: String,
        isActive: Boolean,
        alsoDeactivateIds: Set<String>
    ): Result<Unit> = try {
        val docRef = calibrations(uid).document(profileId)
        val othersToDeactivate = if (isActive) frozenOtherActiveProfileRefs(uid, keepId = profileId, alsoDeactivateIds) else emptyList()
        val batch = firestore.batch()
        batch.update(docRef, frozenActiveStateFields(isActive))
        othersToDeactivate.forEach { batch.update(it, frozenActiveStateFields(false)) }
        batch.commit().await()
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun frozenCreateCalibrationProfile(uid: String, profile: CalibrationProfile): Result<String> = try {
        val colRef = calibrations(uid)
        val docRef = if (profile.id.isNotBlank()) colRef.document(profile.id) else colRef.document()
        val closeBlinkThreshold = profile.closeBlinkThreshold.coerceIn(0.05, 0.80)
        val openBlinkThreshold = profile.openBlinkThreshold.coerceIn(0.15, 0.98)
            .coerceAtLeast(closeBlinkThreshold + 0.05)
        val payload = mapOf(
            "userId" to uid,
            "name" to profile.name.take(80).ifBlank { "Calibration Profile" },
            "deviceModel" to profile.deviceModel.take(80).ifBlank { "Pixel 10 Pro XL" },
            "matrixCoeffs" to if (profile.matrixCoeffs.size == 12) profile.matrixCoeffs else DEFAULT_AFFINE_MATRIX_2X6,
            "baselineEyeDistancePx" to profile.baselineEyeDistancePx.coerceIn(1.0, 5000.0),
            "meanErrorMm" to profile.meanErrorMm.coerceIn(0.0, 500.0),
            "meanErrorPct" to profile.meanErrorPct.coerceIn(0.0, 100.0),
            "maxErrorPct" to profile.maxErrorPct.coerceIn(0.0, 100.0),
            "closeBlinkThreshold" to closeBlinkThreshold,
            "openBlinkThreshold" to openBlinkThreshold,
            "intentionalBlinkMinMs" to profile.intentionalBlinkMinMs.coerceIn(150L, 700L),
            "isActive" to profile.isActive,
            "heldOutValidated" to profile.heldOutValidated,
            "calibrationPosture" to profile.calibrationPostureProfile().name,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        val othersToDeactivate = if (profile.isActive) frozenOtherActiveProfileRefs(uid, keepId = docRef.id) else emptyList()
        val batch = firestore.batch()
        batch.set(docRef, payload)
        othersToDeactivate.forEach { batch.update(it, frozenActiveStateFields(false)) }
        batch.commit().await()
        Result.success(docRef.id)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun frozenOwnedDocumentRefsFromServer(collection: CollectionReference, uid: String): List<DocumentReference> =
        collection
            .whereEqualTo("userId", uid)
            .get(Source.SERVER)
            .await()
            .documents
            .map { it.reference }

    /** Sequential listings: calibrations are listed and awaited before sessions are requested. */
    private suspend fun frozenEraseAll(uid: String): Result<Int> {
        var deletedCount = 0
        var totalCount = 0
        return try {
            val refs = frozenOwnedDocumentRefsFromServer(calibrations(uid), uid) +
                frozenOwnedDocumentRefsFromServer(sessions(uid), uid)
            totalCount = refs.size
            for (chunk in refs.chunked(500)) {
                val batch = firestore.batch()
                chunk.forEach { batch.delete(it) }
                val confirmed = withTimeoutOrNull(20_000L) {
                    batch.commit().await()
                    true
                }
                if (confirmed == null) {
                    throw CloudEraseIncompleteException(
                        deletedCount,
                        totalCount,
                        "Firestore did not confirm the deletion in time ($deletedCount of $totalCount document(s) " +
                            "confirmed). Queued deletes may still complete when the connection returns."
                    )
                }
                deletedCount += chunk.size
            }
            Result.success(deletedCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = if (e !is CloudEraseIncompleteException && deletedCount > 0) {
                CloudEraseIncompleteException(
                    deletedCount,
                    totalCount,
                    "Deleted $deletedCount of $totalCount Firestore document(s) before an error: ${e.localizedMessage}",
                    e
                )
            } else {
                e
            }
            Result.failure(failure)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Seeding and inspection helpers.
    // ---------------------------------------------------------------------------------------------

    private suspend fun seedTelemetry(uid: String, calibrationCount: Int, sessionCount: Int) {
        val repository = EyeTrackingRepository(firestore, auth)
        withTimeout(TIMEOUT_MS) {
            repeat(calibrationCount) { i ->
                repository.createCalibrationProfile(
                    CalibrationProfile(id = "cal_$i", userId = uid, name = "Seed $i", isActive = false)
                ).getOrThrow()
            }
            repeat(sessionCount) { i ->
                repository.createTrackingSession(
                    TrackingSession(id = "sess_$i", userId = uid, title = "Seed $i", durationSeconds = 10)
                ).getOrThrow()
            }
        }
    }

    /** Valid per firestore.rules; written raw so several profiles can be active at once. */
    private fun rawProfilePayload(uid: String, name: String, isActive: Boolean): Map<String, Any> = mapOf(
        "userId" to uid,
        "name" to name,
        "deviceModel" to "Pixel 10 Pro XL",
        "matrixCoeffs" to DEFAULT_AFFINE_MATRIX_2X6,
        "baselineEyeDistancePx" to 148.0,
        "meanErrorMm" to 13.0,
        "meanErrorPct" to 10.0,
        "maxErrorPct" to 14.0,
        "closeBlinkThreshold" to 0.25,
        "openBlinkThreshold" to 0.55,
        "intentionalBlinkMinMs" to 350L,
        "isActive" to isActive,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
    )

    private suspend fun seedProfiles(uid: String, activeFlags: List<Boolean>) {
        val batch = firestore.batch()
        activeFlags.forEachIndexed { i, active ->
            batch.set(calibrations(uid).document("cal_$i"), rawProfilePayload(uid, "Seed $i", active))
        }
        withTimeout(TIMEOUT_MS) { batch.commit().await() }
    }

    private suspend fun ownedCountOnServer(uid: String): Int =
        calibrations(uid).whereEqualTo("userId", uid).get(Source.SERVER).await().size() +
            sessions(uid).whereEqualTo("userId", uid).get(Source.SERVER).await().size()

    private suspend fun activeStates(uid: String): Map<String, Boolean?> =
        calibrations(uid).whereEqualTo("userId", uid).get(Source.SERVER).await()
            .documents.associate { it.id to it.getBoolean("isActive") }

    /** Stored fields minus the owner and the server timestamps, which differ between the two users. */
    private suspend fun storedFields(uid: String, id: String): Map<String, Any> =
        calibrations(uid).document(id).get(Source.SERVER).await().data.orEmpty() -
            setOf("userId", "createdAt", "updatedAt")

    private fun randomProfile(rng: Random, id: String): CalibrationProfile = CalibrationProfile(
        id = id,
        name = if (rng.nextInt(4) == 0) "  " else "Profile ${rng.nextInt(1000)} " + "x".repeat(rng.nextInt(0, 100)),
        deviceModel = if (rng.nextInt(4) == 0) "" else "Model-" + "m".repeat(rng.nextInt(0, 100)),
        matrixCoeffs = if (rng.nextInt(4) == 0) {
            List(rng.nextInt(0, 12)) { rng.nextDouble(-1.0, 1.0) }
        } else {
            List(12) { rng.nextDouble(-1.0, 1.0) }
        },
        baselineEyeDistancePx = rng.nextDouble(-100.0, 6000.0),
        meanErrorMm = rng.nextDouble(-10.0, 600.0),
        meanErrorPct = rng.nextDouble(-10.0, 120.0),
        maxErrorPct = rng.nextDouble(-10.0, 120.0),
        closeBlinkThreshold = rng.nextDouble(0.0, 1.0),
        openBlinkThreshold = rng.nextDouble(0.0, 1.0),
        intentionalBlinkMinMs = rng.nextLong(0L, 1500L),
        isActive = rng.nextBoolean(),
        heldOutValidated = rng.nextBoolean(),
        calibrationPosture = listOf("UPRIGHT", "RECLINING", "LYING_DOWN", "HANDSTAND", "")[rng.nextInt(5)]
    )

    // ---------------------------------------------------------------------------------------------
    // Tests.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun eraseAll_concurrentListings_matchFrozenSequentialListings_onSeededCounts() = runBlocking<Unit> {
        val rng = Random(SEED)
        // Fixed edge counts first (nothing, calibrations only, sessions only), then seeded counts.
        val counts = listOf(0 to 0, 3 to 0, 0 to 3) + List(4) { rng.nextInt(0, 5) to rng.nextInt(0, 5) }
        for ((calibrationCount, sessionCount) in counts) {
            val label = "$calibrationCount calibration(s), $sessionCount session(s)"

            val referenceUid = signInTestUser(freshEmail())
            seedTelemetry(referenceUid, calibrationCount, sessionCount)
            val expected = withTimeout(TIMEOUT_MS) { frozenEraseAll(referenceUid) }
            val referenceLeft = withTimeout(TIMEOUT_MS) { ownedCountOnServer(referenceUid) }

            val uid = signInTestUser(freshEmail())
            seedTelemetry(uid, calibrationCount, sessionCount)
            val actual = withTimeout(TIMEOUT_MS) { EyeTrackingRepository(firestore, auth).eraseAllUserCloudTelemetry() }
            val left = withTimeout(TIMEOUT_MS) { ownedCountOnServer(uid) }

            assertTrue("$label: frozen reference failed: $expected", expected.isSuccess)
            assertTrue("$label: optimized erase failed: $actual", actual.isSuccess)
            assertEquals(label, expected.getOrNull(), actual.getOrNull())
            assertEquals(label, calibrationCount + sessionCount, actual.getOrNull())
            assertEquals(label, referenceLeft, left)
            assertEquals(label, 0, left)
        }
    }

    @Test
    fun eraseAll_offline_failsExactlyLikeFrozenSequentialListings() = runBlocking<Unit> {
        val uid = signInTestUser(freshEmail())
        seedTelemetry(uid, calibrationCount = 1, sessionCount = 1)
        val repository = EyeTrackingRepository(firestore, auth)

        firestore.disableNetwork().await()
        try {
            val expected = withTimeout(TIMEOUT_MS) { frozenEraseAll(uid) }
            val before = ShadowLog.getLogsForTag(FIRESTORE_ERROR_TAG).size
            val actual = withTimeout(TIMEOUT_MS) { repository.eraseAllUserCloudTelemetry() }
            val logged = ShadowLog.getLogsForTag(FIRESTORE_ERROR_TAG).drop(before).map { it.msg }

            val expectedError = checkNotNull(expected.exceptionOrNull()) { "offline frozen erase must fail" }
            val actualError = checkNotNull(actual.exceptionOrNull()) { "offline erase must fail, got $actual" }
            assertEquals(expectedError.javaClass, actualError.javaClass)
            assertEquals(expectedError.message, actualError.message)
            assertEquals(expectedError.findFirestoreException()?.code, actualError.findFirestoreException()?.code)
            // Frozen error-path template for the erase: "users/${uid ?: "unauthenticated"}".
            val frozenPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}"
            assertEquals(
                listOf("Firestore Error: " + handleFirestoreError(actualError as Exception, OperationType.DELETE, frozenPath, true)),
                logged
            )
        } finally {
            firestore.enableNetwork().await()
        }
        assertEquals("neither failed erase may delete anything", 2, withTimeout(TIMEOUT_MS) { ownedCountOnServer(uid) })
    }

    @Test
    fun activationAndCreate_sharedFieldMaps_matchFrozenBatches_onSeededStates() = runBlocking<Unit> {
        val rng = Random(SEED + 1)
        repeat(ACTIVATION_ROUNDS) { round ->
            val count = rng.nextInt(1, 6)
            val initiallyActive = List(count) { rng.nextBoolean() }
            val chosenId = "cal_${rng.nextInt(count)}"
            // A random subset of the listing plus a stale id that no longer exists.
            val otherIds = (0 until count).map { "cal_$it" }.filter { it != chosenId && rng.nextBoolean() } + "cal_gone"
            val newProfile = randomProfile(rng, id = "cal_new")
            val mode = round % 4
            val label = "round $round mode $mode active=$initiallyActive chosen=$chosenId others=$otherIds"

            val referenceUid = signInTestUser(freshEmail())
            seedProfiles(referenceUid, initiallyActive)
            val expected = withTimeout(TIMEOUT_MS) {
                when (mode) {
                    0 -> frozenCommitActiveState(referenceUid, chosenId, isActive = true, alsoDeactivateIds = otherIds.toSet())
                    1 -> frozenCommitActiveState(referenceUid, chosenId, isActive = true, alsoDeactivateIds = emptySet())
                    2 -> frozenCommitActiveState(referenceUid, chosenId, isActive = false, alsoDeactivateIds = emptySet())
                    else -> frozenCreateCalibrationProfile(referenceUid, newProfile)
                }
            }
            val expectedStates = withTimeout(TIMEOUT_MS) { activeStates(referenceUid) }
            val expectedNew = if (mode == 3) withTimeout(TIMEOUT_MS) { storedFields(referenceUid, "cal_new") } else emptyMap()

            val uid = signInTestUser(freshEmail())
            seedProfiles(uid, initiallyActive)
            val repository = EyeTrackingRepository(firestore, auth)
            val actual = withTimeout(TIMEOUT_MS) {
                when (mode) {
                    0 -> repository.setActiveCalibrationProfile(chosenId, otherIds)
                    1 -> repository.updateCalibrationActiveState(chosenId, true)
                    2 -> repository.updateCalibrationActiveState(chosenId, false)
                    else -> repository.createCalibrationProfile(newProfile)
                }
            }
            val actualStates = withTimeout(TIMEOUT_MS) { activeStates(uid) }
            val actualNew = if (mode == 3) withTimeout(TIMEOUT_MS) { storedFields(uid, "cal_new") } else emptyMap()

            assertTrue("$label: frozen reference failed: $expected", expected.isSuccess)
            assertTrue("$label: optimized call failed: $actual", actual.isSuccess)
            assertEquals(label, expected.getOrNull(), actual.getOrNull())
            assertEquals(label, expectedStates, actualStates)
            assertEquals(label, expectedNew, actualNew)
        }
    }

    @Test
    fun signedInClientSideFailures_logTheSamePathAsTheFrozenInlineTemplates() = runBlocking<Unit> {
        signInTestUser(freshEmail())
        val repository = EyeTrackingRepository(firestore, auth)
        val frozenPrefix = "users/${auth.currentUser?.uid ?: "unauthenticated"}"
        // Ids that give a document path with an odd segment count, so Firestore throws on the client
        // (IllegalArgumentException) and nothing is sent. "" is left out of the creates, where it
        // means "generate an id".
        for (badId in listOf("a/b", "x/y/z/w", "p_1/q")) {
            val cases = listOf(
                LoggedCase("create calibration '$badId'", OperationType.CREATE, "$frozenPrefix/calibrations") {
                    repository.createCalibrationProfile(CalibrationProfile(id = badId, isActive = true))
                },
                LoggedCase("create session '$badId'", OperationType.CREATE, "$frozenPrefix/sessions") {
                    repository.createTrackingSession(TrackingSession(id = badId, title = "Bad id"))
                }
            )
            assertLogsMatchFrozenTemplates(cases, signedIn = true)
        }
        for (badId in listOf("", "a/b", "x/y/z/w")) {
            val cases = listOf(
                LoggedCase("update active '$badId'", OperationType.UPDATE, "$frozenPrefix/calibrations/$badId") {
                    repository.updateCalibrationActiveState(badId, true)
                },
                LoggedCase("set active '$badId'", OperationType.UPDATE, "$frozenPrefix/calibrations/$badId") {
                    repository.setActiveCalibrationProfile(badId, listOf("cal_other"))
                },
                LoggedCase("delete calibration '$badId'", OperationType.DELETE, "$frozenPrefix/calibrations/$badId") {
                    repository.deleteCalibrationProfile(badId)
                },
                LoggedCase("delete session '$badId'", OperationType.DELETE, "$frozenPrefix/sessions/$badId") {
                    repository.deleteTrackingSession(badId)
                }
            )
            assertLogsMatchFrozenTemplates(cases, signedIn = true)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 8000L
        const val ACTIVATION_ROUNDS = 8
    }
}
