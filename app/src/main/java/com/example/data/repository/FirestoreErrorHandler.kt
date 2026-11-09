package com.example.data.repository

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestoreException
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

/**
 * Logs a Firestore failure and returns the logged JSON.
 *
 * The record holds no personal data: no email, provider data or uid. The `users/{uid}` path
 * segment is redacted in both the path and the error message. It keeps what is needed to
 * diagnose the failure: the operation, the Firestore status code found anywhere in the cause
 * chain (Query.snapshots() wraps listener errors in a CancellationException), and whether the
 * caller's own auth instance had a signed-in user.
 */
fun handleFirestoreError(
    exception: Exception,
    operationType: OperationType,
    path: String?,
    isSignedIn: Boolean? = null
): String {
    val firestoreException = exception.findFirestoreException()
    val reported: Throwable = firestoreException ?: exception
    val message = reported.message ?: reported.javaClass.simpleName

    val errorInfoJson = JSONObject().apply {
        put("error", redactUserIdSegments(message))
        put("code", firestoreException?.code?.name ?: JSONObject.NULL)
        put("operationType", operationType.value)
        put("path", path?.let(::redactUserIdSegments) ?: JSONObject.NULL)
        put("signedIn", isSignedIn ?: JSONObject.NULL)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}

/** First [FirebaseFirestoreException] in this throwable's cause chain, or null. */
fun Throwable.findFirestoreException(): FirebaseFirestoreException? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current is FirebaseFirestoreException) return current
        if (current.cause === current) return null
        current = current.cause
        depth++
    }
    return null
}

private const val MAX_CAUSE_DEPTH = 16
private val USER_ID_SEGMENT = Regex("users/[^/\\s]+")

internal fun redactUserIdSegments(text: String): String = text.replace(USER_ID_SEGMENT, "users/{uid}")
