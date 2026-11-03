package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.model.CalibrationProfile
import com.example.data.model.DEFAULT_AFFINE_MATRIX_2X6
import com.example.data.model.TrackingSession
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class EyeTrackingRepository(
    private val db: FirebaseFirestore
) {
    // Convenience constructor resolving the custom named database ID from string resources
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth
        get() = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before writing to Firestore.")
    }

    fun observeCalibrationProfiles(userId: String): Flow<List<CalibrationProfile>> {
        val path = "users/$userId/calibrations"
        return db.collection("users").document(userId).collection("calibrations")
            .whereEqualTo("userId", userId)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    CalibrationProfile::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
            .catch { error ->
                if (error is Exception) {
                    handleFirestoreError(error, OperationType.LIST, path)
                }
                throw error
            }
    }

    suspend fun getCalibrationProfiles(userId: String): Result<List<CalibrationProfile>> {
        val path = "users/$userId/calibrations"
        return try {
            val snapshot = db.collection("users").document(userId).collection("calibrations")
                .whereEqualTo("userId", userId)
                .get()
                .await()
            val profiles = snapshot.toObjects(
                CalibrationProfile::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            Result.success(profiles)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    suspend fun getCalibrationProfileById(targetUserId: String, profileId: String): Result<CalibrationProfile?> {
        val path = "users/$targetUserId/calibrations/$profileId"
        return try {
            val doc = db.collection("users").document(targetUserId)
                .collection("calibrations").document(profileId)
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
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    suspend fun createCalibrationProfile(profile: CalibrationProfile): Result<String> {
        val uid = requireUserId()
        val colRef = db.collection("users").document(uid).collection("calibrations")
        val docRef = if (profile.id.isNotBlank()) colRef.document(profile.id) else colRef.document()
        val payload = mapOf(
            "userId" to uid,
            "name" to profile.name.take(80).ifBlank { "Calibration Profile" },
            "deviceModel" to profile.deviceModel.take(80).ifBlank { "Pixel 10 Pro XL" },
            "matrixCoeffs" to if (profile.matrixCoeffs.size == 12) profile.matrixCoeffs else DEFAULT_AFFINE_MATRIX_2X6,
            "baselineEyeDistancePx" to profile.baselineEyeDistancePx.coerceIn(1.0, 5000.0),
            "meanErrorMm" to profile.meanErrorMm.coerceIn(0.0, 500.0),
            "meanErrorPct" to profile.meanErrorPct.coerceIn(0.0, 100.0),
            "maxErrorPct" to profile.maxErrorPct.coerceIn(0.0, 100.0),
            "closeBlinkThreshold" to profile.closeBlinkThreshold.coerceIn(0.05, 0.80),
            "openBlinkThreshold" to profile.openBlinkThreshold.coerceIn(0.15, 0.98),
            "intentionalBlinkMinMs" to profile.intentionalBlinkMinMs.coerceIn(150L, 2000L),
            "isActive" to profile.isActive,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateCalibrationActiveState(profileId: String, isActive: Boolean): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("calibrations").document(profileId)
        return try {
            docRef.update(
                mapOf(
                    "isActive" to isActive,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteCalibrationProfile(profileId: String): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("calibrations").document(profileId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    fun observeTrackingSessions(userId: String): Flow<List<TrackingSession>> {
        val path = "users/$userId/sessions"
        return db.collection("users").document(userId).collection("sessions")
            .whereEqualTo("userId", userId)
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    TrackingSession::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
            .catch { error ->
                if (error is Exception) {
                    handleFirestoreError(error, OperationType.LIST, path)
                }
                throw error
            }
    }

    suspend fun createTrackingSession(session: TrackingSession): Result<String> {
        val uid = requireUserId()
        val colRef = db.collection("users").document(uid).collection("sessions")
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
        return try {
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteTrackingSession(sessionId: String): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("sessions").document(sessionId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun eraseAllUserCloudTelemetry(): Result<Int> {
        val uid = requireUserId()
        var deletedCount = 0
        return try {
            val calSnap = db.collection("users").document(uid).collection("calibrations")
                .whereEqualTo("userId", uid)
                .get()
                .await()
            for (doc in calSnap.documents) {
                doc.reference.delete().await()
                deletedCount++
            }
            val sessSnap = db.collection("users").document(uid).collection("sessions")
                .whereEqualTo("userId", uid)
                .get()
                .await()
            for (doc in sessSnap.documents) {
                doc.reference.delete().await()
                deletedCount++
            }
            Result.success(deletedCount)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, "users/$uid")
            Result.failure(e)
        }
    }
}
