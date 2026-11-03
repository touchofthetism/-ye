package com.example.data.repository

import android.content.Context
import com.example.data.model.CalibrationProfile
import com.example.data.model.DEFAULT_AFFINE_MATRIX_2X6
import com.example.data.model.TrackingSession
import com.google.firebase.Timestamp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

/**
 * On-device persistent local store (SharedPreferences + JSON) ensuring that when a user disables
 * Cloud Sync for biometric privacy, their calibrated 2x6 regression profiles and session history
 * survive app restarts on-device without requiring recalibration every launch.
 */
class LocalTelemetryStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        "oculagaze_local_vault",
        Context.MODE_PRIVATE
    )

    fun isCloudSyncEnabled(): Boolean = prefs.getBoolean(KEY_CLOUD_SYNC_ENABLED, false)

    fun setCloudSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CLOUD_SYNC_ENABLED, enabled).apply()
    }

    fun loadLocalProfiles(userId: String): List<CalibrationProfile> {
        val raw = prefs.getString("${KEY_PROFILES_PREFIX}_$userId", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<CalibrationProfile>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val coeffsArr = obj.optJSONArray("matrixCoeffs")
                val coeffs = if (coeffsArr != null && coeffsArr.length() == 12) {
                    List(12) { idx -> coeffsArr.optDouble(idx, DEFAULT_AFFINE_MATRIX_2X6[idx]) }
                } else {
                    DEFAULT_AFFINE_MATRIX_2X6
                }
                val tsMillis = obj.optLong("createdAtMs", System.currentTimeMillis())
                val ts = Timestamp(Date(tsMillis))
                list.add(
                    CalibrationProfile(
                        id = obj.optString("id", "local_cal_$i"),
                        userId = userId,
                        name = obj.optString("name", "Local Calibration"),
                        deviceModel = obj.optString("deviceModel", "Pixel 10 Pro XL"),
                        matrixCoeffs = coeffs,
                        baselineEyeDistancePx = obj.optDouble("baselineEyeDistancePx", 148.0),
                        meanErrorMm = obj.optDouble("meanErrorMm", 13.4),
                        meanErrorPct = obj.optDouble("meanErrorPct", 10.9),
                        maxErrorPct = obj.optDouble("maxErrorPct", 14.2),
                        closeBlinkThreshold = obj.optDouble("closeBlinkThreshold", 0.25),
                        openBlinkThreshold = obj.optDouble("openBlinkThreshold", 0.55),
                        intentionalBlinkMinMs = obj.optLong("intentionalBlinkMinMs", 350L),
                        isActive = obj.optBoolean("isActive", i == 0),
                        createdAt = ts,
                        updatedAt = ts
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveLocalProfile(userId: String, profile: CalibrationProfile) {
        val existing = loadLocalProfiles(userId).toMutableList()
        val updated = existing.map { it.copy(isActive = false) }.toMutableList()
        val newEntry = profile.copy(
            id = profile.id.ifBlank { "local_cal_${System.currentTimeMillis()}" },
            userId = userId,
            isActive = true
        )
        updated.removeAll { it.id == newEntry.id }
        updated.add(0, newEntry)
        writeProfiles(userId, updated.take(20))
    }

    fun setActiveLocalProfile(userId: String, profileId: String): List<CalibrationProfile> {
        val updated = loadLocalProfiles(userId).map {
            it.copy(isActive = it.id == profileId)
        }
        writeProfiles(userId, updated)
        return updated
    }

    fun deleteLocalProfile(userId: String, profileId: String): List<CalibrationProfile> {
        val updated = loadLocalProfiles(userId).filterNot { it.id == profileId }
        writeProfiles(userId, updated)
        return updated
    }

    private fun writeProfiles(userId: String, profiles: List<CalibrationProfile>) {
        val arr = JSONArray()
        for (p in profiles) {
            val obj = JSONObject().apply {
                put("id", p.id)
                put("userId", userId)
                put("name", p.name)
                put("deviceModel", p.deviceModel)
                put("matrixCoeffs", JSONArray(p.matrixCoeffs))
                put("baselineEyeDistancePx", p.baselineEyeDistancePx)
                put("meanErrorMm", p.meanErrorMm)
                put("meanErrorPct", p.meanErrorPct)
                put("maxErrorPct", p.maxErrorPct)
                put("closeBlinkThreshold", p.closeBlinkThreshold)
                put("openBlinkThreshold", p.openBlinkThreshold)
                put("intentionalBlinkMinMs", p.intentionalBlinkMinMs)
                put("isActive", p.isActive)
                put("createdAtMs", p.createdAt?.toDate()?.time ?: System.currentTimeMillis())
            }
            arr.put(obj)
        }
        prefs.edit().putString("${KEY_PROFILES_PREFIX}_$userId", arr.toString()).apply()
    }

    fun loadLocalSessions(userId: String): List<TrackingSession> {
        val raw = prefs.getString("${KEY_SESSIONS_PREFIX}_$userId", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<TrackingSession>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val secArr = obj.optJSONArray("sectionLoads") ?: JSONArray()
                val cluArr = obj.optJSONArray("clusterPoints") ?: JSONArray()
                val tsMillis = obj.optLong("createdAtMs", System.currentTimeMillis())
                val ts = Timestamp(Date(tsMillis))
                list.add(
                    TrackingSession(
                        id = obj.optString("id", "local_sess_$i"),
                        userId = userId,
                        title = obj.optString("title", "Local Session"),
                        deviceModel = obj.optString("deviceModel", "Pixel 10 Pro XL"),
                        contentType = obj.optString("contentType", "READING_SECTIONS"),
                        durationSeconds = obj.optInt("durationSeconds", 15),
                        naturalBlinkCount = obj.optInt("naturalBlinkCount", 4),
                        intentionalBlinkCount = obj.optInt("intentionalBlinkCount", 1),
                        blinksPerMinute = obj.optDouble("blinksPerMinute", 16.0),
                        effectiveWordsPerMinute = obj.optInt("effectiveWordsPerMinute", 180),
                        fixationClusterCount = obj.optInt("fixationClusterCount", 5),
                        topZonePercentage = obj.optInt("topZonePercentage", 34),
                        middleZonePercentage = obj.optInt("middleZonePercentage", 33),
                        bottomZonePercentage = obj.optInt("bottomZonePercentage", 33),
                        sectionLoads = List(secArr.length()) { idx -> secArr.optString(idx) },
                        clusterPoints = List(cluArr.length()) { idx -> cluArr.optString(idx) },
                        createdAt = ts,
                        updatedAt = ts
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveLocalSession(userId: String, session: TrackingSession) {
        val existing = loadLocalSessions(userId).toMutableList()
        val entry = session.copy(
            id = session.id.ifBlank { "local_sess_${System.currentTimeMillis()}" },
            userId = userId
        )
        existing.removeAll { it.id == entry.id }
        existing.add(0, entry)
        writeSessions(userId, existing.take(30))
    }

    fun deleteLocalSession(userId: String, sessionId: String): List<TrackingSession> {
        val updated = loadLocalSessions(userId).filterNot { it.id == sessionId }
        writeSessions(userId, updated)
        return updated
    }

    fun clearAllLocalData(userId: String) {
        prefs.edit()
            .remove("${KEY_PROFILES_PREFIX}_$userId")
            .remove("${KEY_SESSIONS_PREFIX}_$userId")
            .apply()
    }

    private fun writeSessions(userId: String, sessions: List<TrackingSession>) {
        val arr = JSONArray()
        for (s in sessions) {
            val obj = JSONObject().apply {
                put("id", s.id)
                put("userId", userId)
                put("title", s.title)
                put("deviceModel", s.deviceModel)
                put("contentType", s.contentType)
                put("durationSeconds", s.durationSeconds)
                put("naturalBlinkCount", s.naturalBlinkCount)
                put("intentionalBlinkCount", s.intentionalBlinkCount)
                put("blinksPerMinute", s.blinksPerMinute)
                put("effectiveWordsPerMinute", s.effectiveWordsPerMinute)
                put("fixationClusterCount", s.fixationClusterCount)
                put("topZonePercentage", s.topZonePercentage)
                put("middleZonePercentage", s.middleZonePercentage)
                put("bottomZonePercentage", s.bottomZonePercentage)
                put("sectionLoads", JSONArray(s.sectionLoads))
                put("clusterPoints", JSONArray(s.clusterPoints))
                put("createdAtMs", s.createdAt?.toDate()?.time ?: System.currentTimeMillis())
            }
            arr.put(obj)
        }
        prefs.edit().putString("${KEY_SESSIONS_PREFIX}_$userId", arr.toString()).apply()
    }

    fun loadCustomAacTiles(
        userId: String,
        defaultTiles: List<com.example.data.model.AacActionTile>
    ): List<com.example.data.model.AacActionTile> {
        val raw = prefs.getString("${KEY_AAC_TILES_PREFIX}_$userId", null) ?: return defaultTiles
        return try {
            val arr = JSONArray(raw)
            val customById = mutableMapOf<String, Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id")
                val title = obj.optString("title")
                val phrase = obj.optString("speechPhrase")
                if (id.isNotBlank() && title.isNotBlank() && phrase.isNotBlank()) {
                    customById[id] = Pair(title, phrase)
                }
            }
            defaultTiles.map { tile ->
                val custom = customById[tile.id]
                if (custom != null) tile.copy(title = custom.first, speechPhrase = custom.second) else tile
            }
        } catch (_: Exception) {
            defaultTiles
        }
    }

    fun saveCustomAacTiles(
        userId: String,
        tiles: List<com.example.data.model.AacActionTile>
    ) {
        val arr = JSONArray()
        for (t in tiles) {
            val obj = JSONObject().apply {
                put("id", t.id)
                put("title", t.title)
                put("speechPhrase", t.speechPhrase)
            }
            arr.put(obj)
        }
        prefs.edit().putString("${KEY_AAC_TILES_PREFIX}_$userId", arr.toString()).apply()
    }

    private companion object {
        const val KEY_CLOUD_SYNC_ENABLED = "cloud_sync_enabled"
        const val KEY_PROFILES_PREFIX = "local_profiles"
        const val KEY_SESSIONS_PREFIX = "local_sessions"
        const val KEY_AAC_TILES_PREFIX = "local_aac_tiles"
    }
}
