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
 *
 * Everything lives in the SharedPreferences file [VAULT_PREFS_NAME] (shared_prefs/oculagaze_local_vault.xml).
 * That file must stay excluded from Android Auto Backup and device-to-device transfer
 * (res/xml/backup_rules.xml and res/xml/data_extraction_rules.xml); keep the name in sync with those rules.
 *
 * The local vault is bounded: it keeps the [MAX_LOCAL_PROFILES] most recent calibration profiles and the
 * [MAX_LOCAL_SESSIONS] most recent sessions, and saving beyond that evicts the oldest entry.
 */
class LocalTelemetryStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        VAULT_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    /**
     * Cloud-sync consent of [userId]. Consent is per account: a device-wide flag would let the next account
     * that signs in inherit the previous account's opt-in. Defaults to opted out.
     */
    fun isCloudSyncEnabled(userId: String): Boolean =
        prefs.getBoolean("${KEY_CLOUD_SYNC_ENABLED}_$userId", false)

    /**
     * Stores [userId]'s cloud-sync consent. Also drops the legacy device-wide flag written by older versions:
     * it cannot be attributed to an account, so it is not migrated and every account starts opted out.
     */
    fun setCloudSyncEnabled(userId: String, enabled: Boolean) {
        prefs.edit()
            .putBoolean("${KEY_CLOUD_SYNC_ENABLED}_$userId", enabled)
            .remove(KEY_CLOUD_SYNC_ENABLED)
            .apply()
    }

    @Deprecated(
        "Device-wide consent leaks one account's opt-in to the next account; use isCloudSyncEnabled(userId).",
        ReplaceWith("isCloudSyncEnabled(userId)")
    )
    fun isCloudSyncEnabled(): Boolean = prefs.getBoolean(KEY_CLOUD_SYNC_ENABLED, false)

    @Deprecated(
        "Device-wide consent leaks one account's opt-in to the next account; use setCloudSyncEnabled(userId, enabled).",
        ReplaceWith("setCloudSyncEnabled(userId, enabled)")
    )
    fun setCloudSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CLOUD_SYNC_ENABLED, enabled).apply()
    }

    /**
     * Id of the profile [userId] last activated or saved (local or cloud id), or null when none is recorded.
     * Written by [saveLocalProfile] and [setActiveLocalProfile], cleared when that profile is deleted.
     */
    fun getActiveProfileId(userId: String): String? =
        prefs.getString("${KEY_ACTIVE_PROFILE_ID_PREFIX}_$userId", null)?.takeIf { it.isNotBlank() }

    /** Records [profileId] as [userId]'s chosen active profile; null or blank clears it. */
    fun setActiveProfileId(userId: String, profileId: String?) {
        val key = "${KEY_ACTIVE_PROFILE_ID_PREFIX}_$userId"
        val editor = prefs.edit()
        if (profileId.isNullOrBlank()) editor.remove(key) else editor.putString(key, profileId)
        editor.apply()
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

    /**
     * Saves [profile] as [userId]'s active local profile and returns the entry exactly as persisted,
     * including the `local_cal_<ms>` id assigned when [profile] has a blank id. Callers that also upload the
     * profile should upload the returned entry so the local and cloud copies share one id.
     * Keeps at most [MAX_LOCAL_PROFILES] entries; the oldest is evicted beyond that.
     */
    fun saveLocalProfile(userId: String, profile: CalibrationProfile): CalibrationProfile {
        val existing = loadLocalProfiles(userId).toMutableList()
        val updated = existing.map { it.copy(isActive = false) }.toMutableList()
        val newEntry = profile.copy(
            id = profile.id.ifBlank { "local_cal_${System.currentTimeMillis()}" },
            userId = userId,
            isActive = true
        )
        updated.removeAll { it.id == newEntry.id }
        updated.add(0, newEntry)
        writeProfiles(userId, updated.take(MAX_LOCAL_PROFILES))
        setActiveProfileId(userId, newEntry.id)
        return newEntry
    }

    /**
     * Marks [profileId] active among the local entries and records it as the chosen active profile, even when
     * it is a cloud-only id with no local entry.
     */
    fun setActiveLocalProfile(userId: String, profileId: String): List<CalibrationProfile> {
        val updated = loadLocalProfiles(userId).map {
            it.copy(isActive = it.id == profileId)
        }
        writeProfiles(userId, updated)
        setActiveProfileId(userId, profileId)
        return updated
    }

    fun deleteLocalProfile(userId: String, profileId: String): List<CalibrationProfile> {
        val updated = loadLocalProfiles(userId).filterNot { it.id == profileId }
        writeProfiles(userId, updated)
        if (getActiveProfileId(userId) == profileId) setActiveProfileId(userId, null)
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

    /**
     * Saves [session] for [userId] and returns the entry exactly as persisted, including the
     * `local_sess_<ms>` id assigned when [session] has a blank id. Callers that also upload the session should
     * upload the returned entry so the local and cloud copies share one id.
     * Keeps at most [MAX_LOCAL_SESSIONS] entries; the oldest is evicted beyond that.
     */
    fun saveLocalSession(userId: String, session: TrackingSession): TrackingSession {
        val existing = loadLocalSessions(userId).toMutableList()
        val entry = session.copy(
            id = session.id.ifBlank { "local_sess_${System.currentTimeMillis()}" },
            userId = userId
        )
        existing.removeAll { it.id == entry.id }
        existing.add(0, entry)
        writeSessions(userId, existing.take(MAX_LOCAL_SESSIONS))
        return entry
    }

    fun deleteLocalSession(userId: String, sessionId: String): List<TrackingSession> {
        val updated = loadLocalSessions(userId).filterNot { it.id == sessionId }
        writeSessions(userId, updated)
        return updated
    }

    /**
     * Wipes [userId]'s vault: calibration profiles, sessions, the recorded active profile id and the
     * user-authored AAC phrase tiles (which can hold personal or medical text).
     */
    fun clearAllLocalData(userId: String) {
        prefs.edit()
            .remove("${KEY_PROFILES_PREFIX}_$userId")
            .remove("${KEY_SESSIONS_PREFIX}_$userId")
            .remove("${KEY_AAC_TILES_PREFIX}_$userId")
            .remove("${KEY_ACTIVE_PROFILE_ID_PREFIX}_$userId")
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

    companion object {
        /** SharedPreferences file holding the vault; excluded from backup by name in res/xml. */
        const val VAULT_PREFS_NAME = "oculagaze_local_vault"

        /** The vault keeps this many most-recent calibration profiles; saving more evicts the oldest. */
        const val MAX_LOCAL_PROFILES = 20

        /** The vault keeps this many most-recent sessions; saving more evicts the oldest. */
        const val MAX_LOCAL_SESSIONS = 30

        private const val KEY_CLOUD_SYNC_ENABLED = "cloud_sync_enabled"
        private const val KEY_PROFILES_PREFIX = "local_profiles"
        private const val KEY_SESSIONS_PREFIX = "local_sessions"
        private const val KEY_AAC_TILES_PREFIX = "local_aac_tiles"
        private const val KEY_ACTIVE_PROFILE_ID_PREFIX = "local_active_profile_id"
    }
}
