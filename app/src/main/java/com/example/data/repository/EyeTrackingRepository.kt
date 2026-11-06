package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.model.CalibrationProfile
import com.example.data.model.DEFAULT_AFFINE_MATRIX_2X6
import com.example.data.model.TrackingSession
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Thrown by [EyeTrackingRepository.eraseAllUserCloudTelemetry] when the erase stopped part way or
 * the server did not confirm it in time. [deletedCount] documents were confirmed deleted out of
 * [totalCount] found on the server.
 */
class CloudEraseIncompleteException(
    val deletedCount: Int,
    val totalCount: Int,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

class EyeTrackingRepository(
    private val db: FirebaseFirestore,
    private val customAuth: com.google.firebase.auth.FirebaseAuth? = null
) {
    // Convenience constructor resolving the custom named database ID from string resources
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        null
    )

    private val auth
        get() = customAuth ?: Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before writing to Firestore.")
    }

    /** Signed-in state of this repository's own auth instance, for error logs (never the identity). */
    private fun signedInOrNull(): Boolean? = try {
        auth.currentUser != null
    } catch (e: Exception) {
        null
    }

    private fun reportError(e: Exception, operationType: OperationType, path: String) {
        handleFirestoreError(e, operationType, path, signedInOrNull())
    }

    private fun calibrationsOf(uid: String): CollectionReference =
        db.collection("users").document(uid).collection("calibrations")

    private fun sessionsOf(uid: String): CollectionReference =
        db.collection("users").document(uid).collection("sessions")

    fun observeCalibrationProfiles(userId: String): Flow<List<CalibrationProfile>> {
        val path = "users/$userId/calibrations"
        return calibrationsOf(userId)
            .whereEqualTo("userId", userId)
            .snapshots()
            .map { snapshot -> snapshot.toObjectsSkippingUnreadable(CalibrationProfile::class.java, path) }
            .catch { error -> throw surfaceListenerError(error, path) }
            // Reflection mapping of the whole collection runs on every change; keep it off the main thread.
            .flowOn(Dispatchers.Default)
    }

    suspend fun getCalibrationProfiles(userId: String): Result<List<CalibrationProfile>> {
        val path = "users/$userId/calibrations"
        return try {
            val snapshot = calibrationsOf(userId)
                .whereEqualTo("userId", userId)
                .get()
                .await()
            Result.success(snapshot.toObjectsSkippingUnreadable(CalibrationProfile::class.java, path))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    suspend fun getCalibrationProfileById(targetUserId: String, profileId: String): Result<CalibrationProfile?> {
        val path = "users/$targetUserId/calibrations/$profileId"
        return try {
            val doc = calibrationsOf(targetUserId).document(profileId)
                .get()
                .await()
            Result.success(
                doc.toObject(
                    CalibrationProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    /**
     * Creates the profile. When it is active, the same atomic batch deactivates every other active
     * cloud profile, so exactly one cloud profile is active afterwards.
     */
    suspend fun createCalibrationProfile(profile: CalibrationProfile): Result<String> {
        return try {
            val uid = requireUserId()
            val colRef = calibrationsOf(uid)
            val docRef = if (profile.id.isNotBlank()) colRef.document(profile.id) else colRef.document()
            val closeBlinkThreshold = profile.closeBlinkThreshold.coerceIn(0.05, 0.80)
            // The re-open threshold must sit above the close threshold (Schmitt-trigger hysteresis).
            val openBlinkThreshold = profile.openBlinkThreshold.coerceIn(0.15, 0.98)
                .coerceAtLeast(closeBlinkThreshold + MIN_BLINK_HYSTERESIS_GAP)
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
                "intentionalBlinkMinMs" to profile.intentionalBlinkMinMs.coerceIn(150L, MAX_INTENTIONAL_BLINK_MIN_MS),
                "isActive" to profile.isActive,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            val othersToDeactivate = if (profile.isActive) otherActiveProfileRefs(uid, keepId = docRef.id) else emptyList()
            val batch = db.batch()
            batch.set(docRef, payload)
            othersToDeactivate.forEach { batch.update(it, activeStateFields(false)) }
            batch.commit().await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportError(e, OperationType.CREATE, "users/${auth.currentUser?.uid ?: "unauthenticated"}/calibrations")
            Result.failure(e)
        }
    }

    /**
     * Sets the profile's active flag. Activation is exclusive: the same atomic batch deactivates
     * every other active cloud profile, so the user's choice is not overridden by a newer profile
     * that is still marked active.
     */
    suspend fun updateCalibrationActiveState(profileId: String, isActive: Boolean): Result<Unit> {
        return try {
            val uid = requireUserId()
            val docRef = calibrationsOf(uid).document(profileId)
            val othersToDeactivate = if (isActive) otherActiveProfileRefs(uid, keepId = profileId) else emptyList()
            val batch = db.batch()
            batch.update(docRef, activeStateFields(isActive))
            othersToDeactivate.forEach { batch.update(it, activeStateFields(false)) }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportError(e, OperationType.UPDATE, "users/${auth.currentUser?.uid ?: "unauthenticated"}/calibrations/$profileId")
            Result.failure(e)
        }
    }

    private suspend fun otherActiveProfileRefs(uid: String, keepId: String): List<DocumentReference> =
        calibrationsOf(uid)
            .whereEqualTo("userId", uid)
            .get()
            .await()
            .documents
            .filter { it.id != keepId && it.get("isActive") == true }
            .map { it.reference }

    private fun activeStateFields(isActive: Boolean): Map<String, Any> = mapOf(
        "isActive" to isActive,
        "updatedAt" to FieldValue.serverTimestamp()
    )

    suspend fun deleteCalibrationProfile(profileId: String): Result<Unit> {
        return try {
            val uid = requireUserId()
            val docRef = calibrationsOf(uid).document(profileId)
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportError(e, OperationType.DELETE, "users/${auth.currentUser?.uid ?: "unauthenticated"}/calibrations/$profileId")
            Result.failure(e)
        }
    }

    fun observeTrackingSessions(userId: String): Flow<List<TrackingSession>> {
        val path = "users/$userId/sessions"
        return sessionsOf(userId)
            .whereEqualTo("userId", userId)
            .snapshots()
            .map { snapshot -> snapshot.toObjectsSkippingUnreadable(TrackingSession::class.java, path) }
            .catch { error -> throw surfaceListenerError(error, path) }
            // Reflection mapping of the whole collection runs on every change; keep it off the main thread.
            .flowOn(Dispatchers.Default)
    }

    suspend fun createTrackingSession(session: TrackingSession): Result<String> {
        return try {
            val uid = requireUserId()
            val colRef = sessionsOf(uid)
            val docRef = if (session.id.isNotBlank()) colRef.document(session.id) else colRef.document()
            val payload = mapOf(
                "userId" to uid,
                "title" to session.title.take(120).ifBlank { "Tracking Session" },
                "deviceModel" to session.deviceModel.take(80).ifBlank { "Pixel 10 Pro XL" },
                "contentType" to if (session.contentType in listOf("READING_SECTIONS", "AAC_CONTROL_DECK")) session.contentType else "READING_SECTIONS",
                "durationSeconds" to session.durationSeconds.coerceIn(1, 86400),
                "naturalBlinkCount" to session.naturalBlinkCount.coerceIn(0, 100000),
                "intentionalBlinkCount" to session.intentionalBlinkCount.coerceIn(0, 100000),
                "blinksPerMinute" to session.blinksPerMinute.coerceIn(0.0, 300.0),
                "effectiveWordsPerMinute" to session.effectiveWordsPerMinute.coerceIn(0, 2000),
                "fixationClusterCount" to session.fixationClusterCount.coerceIn(0, 5000),
                "topZonePercentage" to session.topZonePercentage.coerceIn(0, 100),
                "middleZonePercentage" to session.middleZonePercentage.coerceIn(0, 100),
                "bottomZonePercentage" to session.bottomZonePercentage.coerceIn(0, 100),
                "sectionLoads" to session.sectionLoads.take(12).map { it.take(120) },
                "clusterPoints" to session.clusterPoints.take(40).map { it.take(80) },
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportError(e, OperationType.CREATE, "users/${auth.currentUser?.uid ?: "unauthenticated"}/sessions")
            Result.failure(e)
        }
    }

    suspend fun deleteTrackingSession(sessionId: String): Result<Unit> {
        return try {
            val uid = requireUserId()
            val docRef = sessionsOf(uid).document(sessionId)
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportError(e, OperationType.DELETE, "users/${auth.currentUser?.uid ?: "unauthenticated"}/sessions/$sessionId")
            Result.failure(e)
        }
    }

    /**
     * Deletes every calibration and session document of the signed-in user and returns how many
     * deletions the server confirmed.
     *
     * The documents are listed from the server, never the offline cache, so an offline erase fails
     * fast (UNAVAILABLE) instead of deleting only cached documents and reporting success, or waiting
     * forever for acknowledgements. Deletes go out in batches of at most [MAX_BATCH_WRITES]. A batch
     * the server does not confirm within [ERASE_BATCH_TIMEOUT_MS], or a failure after some batches
     * were committed, is reported as [CloudEraseIncompleteException] with the confirmed count.
     */
    suspend fun eraseAllUserCloudTelemetry(): Result<Int> {
        var deletedCount = 0
        var totalCount = 0
        return try {
            val uid = requireUserId()
            val refs = ownedDocumentRefsFromServer(calibrationsOf(uid), uid) +
                ownedDocumentRefsFromServer(sessionsOf(uid), uid)
            totalCount = refs.size
            for (chunk in refs.chunked(MAX_BATCH_WRITES)) {
                val batch = db.batch()
                chunk.forEach { batch.delete(it) }
                val confirmed = withTimeoutOrNull(ERASE_BATCH_TIMEOUT_MS) {
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
            reportError(e, OperationType.DELETE, "users/${auth.currentUser?.uid ?: "unauthenticated"}")
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

    private suspend fun ownedDocumentRefsFromServer(collection: CollectionReference, uid: String): List<DocumentReference> =
        collection
            .whereEqualTo("userId", uid)
            .get(Source.SERVER)
            .await()
            .documents
            .map { it.reference }

    /**
     * Maps each document on its own, so one document the mapper cannot read (for example a list
     * element of the wrong type written by another client) is skipped instead of failing the
     * whole list or listener.
     */
    private fun <T : Any> QuerySnapshot.toObjectsSkippingUnreadable(type: Class<T>, path: String): List<T> {
        val mapped = ArrayList<T>(size())
        for (doc in documents) {
            try {
                doc.toObject(type, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.let { mapped.add(it) }
            } catch (e: RuntimeException) {
                Log.w(TAG, "Skipping unreadable document ${doc.id} in ${redactUserIdSegments(path)}: ${e.message}")
            }
        }
        return mapped
    }

    /**
     * Query.snapshots() reports a listener failure by cancelling its producer with the
     * FirebaseFirestoreException as the cause. Log it and hand callers that exception, so they see
     * its code (PERMISSION_DENIED, UNAVAILABLE, ...) instead of "Error getting Query snapshot".
     */
    private fun surfaceListenerError(error: Throwable, path: String): Throwable {
        val firestoreError = error.findFirestoreException()
        if (firestoreError == null && error is CancellationException) return error
        val surfaced = firestoreError ?: error
        if (surfaced is Exception) {
            reportError(surfaced, OperationType.LIST, path)
        }
        return surfaced
    }

    private companion object {
        const val TAG = "EyeTrackingRepository"
        /** Firestore's limit on writes per batch. */
        const val MAX_BATCH_WRITES = 500
        const val ERASE_BATCH_TIMEOUT_MS = 20_000L
        /** Must match firestore.rules: closeBlinkThreshold + 0.05 <= openBlinkThreshold. */
        const val MIN_BLINK_HYSTERESIS_GAP = 0.05
        /**
         * BlinkHysteresisDetector classifies command blinks as intentionalBlinkMinMs..800 ms; 700 ms keeps
         * that window non-empty and matches the ViewModel's blink-timing setter and firestore.rules.
         */
        const val MAX_INTENTIONAL_BLINK_MIN_MS = 700L
    }
}
