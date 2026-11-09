package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.data.model.AacActionTile
import com.example.data.model.CalibrationProfile
import com.example.data.model.DEFAULT_AFFINE_MATRIX_2X6
import com.example.data.model.DwellSpeedPreset
import com.example.data.model.GazeFilterMode
import com.example.data.model.GazePostureProfile
import com.example.data.model.GazeTriggerMode
import com.example.data.model.InteractionSettings
import com.example.data.model.TrackingSession
import com.google.firebase.Timestamp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

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
 *
 * The profile and session lists are kept parsed in memory next to the exact string they were parsed from
 * (or written as), and each write encodes only the entries that changed. The stored JSON is the same as
 * re-encoding the whole list would produce; a cached list is used only while the stored string still
 * equals the one it came from, so writes made anywhere else just cause a fresh parse.
 */
class LocalTelemetryStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        VAULT_PREFS_NAME,
        Context.MODE_PRIVATE
    )

    /** Parsed profile lists by preference key; see [VaultListSnapshot]. */
    private val profileSnapshots = ConcurrentHashMap<String, VaultListSnapshot<CalibrationProfile>>()

    /** Parsed session lists by preference key; see [VaultListSnapshot]. */
    private val sessionSnapshots = ConcurrentHashMap<String, VaultListSnapshot<TrackingSession>>()

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
        val editor = prefs.edit()
        putActiveProfileId(editor, userId, profileId)
        editor.apply()
    }

    /** [setActiveProfileId]'s change, added to [editor] so it lands in the same commit as a list write. */
    private fun putActiveProfileId(editor: SharedPreferences.Editor, userId: String, profileId: String?) {
        val key = "${KEY_ACTIVE_PROFILE_ID_PREFIX}_$userId"
        if (profileId.isNullOrBlank()) editor.remove(key) else editor.putString(key, profileId)
    }

    fun loadLocalProfiles(userId: String): List<CalibrationProfile> {
        val snapshot = readProfiles(userId) ?: return emptyList()
        return ArrayList(snapshot.items)
    }

    private fun readProfiles(userId: String): VaultListSnapshot<CalibrationProfile>? =
        readSnapshot("${KEY_PROFILES_PREFIX}_$userId", profileSnapshots) { raw -> parseProfiles(raw, userId) }

    /** Reads a stored profile list the way [loadLocalProfiles] always has. */
    private fun parseProfiles(raw: String, userId: String): VaultListSnapshot<CalibrationProfile> {
        var clockDependent = false
        val items: List<CalibrationProfile> = try {
            val arr = JSONArray(raw)
            val list = ArrayList<CalibrationProfile>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val coeffsArr = obj.optJSONArray("matrixCoeffs")
                val coeffs = if (coeffsArr != null && coeffsArr.length() == 12) {
                    List(12) { idx -> coeffsArr.optDouble(idx, DEFAULT_AFFINE_MATRIX_2X6[idx]) }
                } else {
                    DEFAULT_AFFINE_MATRIX_2X6
                }
                val ts = Timestamp(Date(createdAtMsOrNow(obj) { clockDependent = true }))
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
                        // Entries written before these keys existed read back unvalidated and upright.
                        heldOutValidated = obj.optBoolean("heldOutValidated", false),
                        calibrationPosture = obj.optString("calibrationPosture", GazePostureProfile.UPRIGHT.name)
                            .takeIf { isKnownPosture(it) }
                            ?: GazePostureProfile.UPRIGHT.name,
                        createdAt = ts,
                        updatedAt = ts
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
        return VaultListSnapshot(raw, items, arrayOfNulls(items.size), reusable = !clockDependent)
    }

    /**
     * Saves [profile] as [userId]'s active local profile and returns the entry exactly as persisted,
     * including the `local_cal_<ms>` id assigned when [profile] has a blank id. Callers that also upload the
     * profile should upload the returned entry so the local and cloud copies share one id.
     * Keeps at most [MAX_LOCAL_PROFILES] entries; the oldest is evicted beyond that.
     */
    fun saveLocalProfile(userId: String, profile: CalibrationProfile): CalibrationProfile {
        val snapshot = readProfiles(userId)
        val newEntry = profile.copy(
            id = profile.id.ifBlank { "local_cal_${System.currentTimeMillis()}" },
            userId = userId,
            isActive = true
        )
        // newEntry first, then the earlier entries (all inactive) without newEntry's id, capped.
        val draft = ListDraft<CalibrationProfile>(MAX_LOCAL_PROFILES)
        draft.add(newEntry, null)
        if (snapshot != null) {
            val items = snapshot.items
            for (i in items.indices) {
                if (draft.size >= MAX_LOCAL_PROFILES) break
                val p = items[i]
                if (p.id == newEntry.id) continue
                if (p.isActive) draft.add(p.copy(isActive = false), null) else draft.add(p, snapshot.itemJson[i])
            }
        }
        commitProfiles(userId, draft) { putActiveProfileId(it, userId, newEntry.id) }
        return newEntry
    }

    /**
     * Marks [profileId] active among the local entries and records it as the chosen active profile, even when
     * it is a cloud-only id with no local entry.
     */
    fun setActiveLocalProfile(userId: String, profileId: String): List<CalibrationProfile> {
        val snapshot = readProfiles(userId)
        val draft = ListDraft<CalibrationProfile>(snapshot?.items?.size ?: 0)
        if (snapshot != null) {
            val items = snapshot.items
            for (i in items.indices) {
                val p = items[i]
                val active = p.id == profileId
                if (p.isActive == active) draft.add(p, snapshot.itemJson[i]) else draft.add(p.copy(isActive = active), null)
            }
        }
        commitProfiles(userId, draft) { putActiveProfileId(it, userId, profileId) }
        return draft.items
    }

    fun deleteLocalProfile(userId: String, profileId: String): List<CalibrationProfile> {
        val snapshot = readProfiles(userId)
        val draft = ListDraft<CalibrationProfile>(snapshot?.items?.size ?: 0)
        if (snapshot != null) {
            val items = snapshot.items
            for (i in items.indices) if (items[i].id != profileId) draft.add(items[i], snapshot.itemJson[i])
        }
        val clearActive = getActiveProfileId(userId) == profileId
        commitProfiles(userId, draft) { if (clearActive) putActiveProfileId(it, userId, null) }
        return draft.items
    }

    private fun commitProfiles(
        userId: String,
        draft: ListDraft<CalibrationProfile>,
        alsoEdit: (SharedPreferences.Editor) -> Unit
    ) = commitList("${KEY_PROFILES_PREFIX}_$userId", profileSnapshots, draft, { encodeProfile(it, userId) }, alsoEdit)

    /** One stored profile entry, with the keys in their stored order. */
    private fun encodeProfile(p: CalibrationProfile, userId: String): EncodedEntry<CalibrationProfile> {
        val createdAtMs = p.createdAt?.toDate()?.time ?: System.currentTimeMillis()
        val json: String? = JSONObject().apply {
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
            put("heldOutValidated", p.heldOutValidated)
            put("calibrationPosture", p.calibrationPosture)
            put("createdAtMs", createdAtMs)
        }.toString()
        val readBack = profileReadBack(p, userId, createdAtMs)
        // Reading back swaps a coefficient list of another length for the default matrix and an unknown posture
        // for UPRIGHT; then the read-back value encodes differently and is re-encoded on the next write.
        val readBackJson = if (
            readBack != null && p.matrixCoeffs.size == 12 && readBack.calibrationPosture == p.calibrationPosture
        ) json else null
        return EncodedEntry(json, readBack, readBackJson)
    }

    /**
     * What [parseProfiles] returns for [p] encoded with [createdAtMs], or null when that is not certain
     * without parsing (a null where Kotlin types promise none, -0.0 or a non-finite number).
     */
    private fun profileReadBack(p: CalibrationProfile, userId: String, createdAtMs: Long): CalibrationProfile? {
        if (isNull(p.id) || isNull(p.name) || isNull(p.deviceModel) || isNull(p.calibrationPosture)) return null
        val coeffs: Any? = p.matrixCoeffs
        if (coeffs !is List<*>) return null
        for (c in coeffs) if (c !is Double || !c.isExactInJson()) return null
        if (!p.baselineEyeDistancePx.isExactInJson() || !p.meanErrorMm.isExactInJson() ||
            !p.meanErrorPct.isExactInJson() || !p.maxErrorPct.isExactInJson() ||
            !p.closeBlinkThreshold.isExactInJson() || !p.openBlinkThreshold.isExactInJson()
        ) return null
        return try {
            val ts = Timestamp(Date(createdAtMs))
            p.copy(
                userId = userId,
                matrixCoeffs = if (coeffs.size == 12) ArrayList(p.matrixCoeffs) else DEFAULT_AFFINE_MATRIX_2X6,
                calibrationPosture = if (isKnownPosture(p.calibrationPosture)) {
                    p.calibrationPosture
                } else {
                    GazePostureProfile.UPRIGHT.name
                },
                createdAt = ts,
                updatedAt = ts
            )
        } catch (_: IllegalArgumentException) {
            null // A time Timestamp rejects: parsing it fails as well, so leave that to the next read.
        }
    }

    fun loadLocalSessions(userId: String): List<TrackingSession> {
        val snapshot = readSessions(userId) ?: return emptyList()
        return ArrayList(snapshot.items)
    }

    private fun readSessions(userId: String): VaultListSnapshot<TrackingSession>? =
        readSnapshot("${KEY_SESSIONS_PREFIX}_$userId", sessionSnapshots) { raw -> parseSessions(raw, userId) }

    /** Reads a stored session list the way [loadLocalSessions] always has. */
    private fun parseSessions(raw: String, userId: String): VaultListSnapshot<TrackingSession> {
        var clockDependent = false
        val items: List<TrackingSession> = try {
            val arr = JSONArray(raw)
            val list = ArrayList<TrackingSession>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val secArr = obj.optJSONArray("sectionLoads") ?: JSONArray()
                val cluArr = obj.optJSONArray("clusterPoints") ?: JSONArray()
                val ts = Timestamp(Date(createdAtMsOrNow(obj) { clockDependent = true }))
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
        return VaultListSnapshot(raw, items, arrayOfNulls(items.size), reusable = !clockDependent)
    }

    /**
     * Saves [session] for [userId] and returns the entry exactly as persisted, including the
     * `local_sess_<ms>` id assigned when [session] has a blank id. Callers that also upload the session should
     * upload the returned entry so the local and cloud copies share one id.
     * Keeps at most [MAX_LOCAL_SESSIONS] entries; the oldest is evicted beyond that.
     */
    fun saveLocalSession(userId: String, session: TrackingSession): TrackingSession {
        val snapshot = readSessions(userId)
        val entry = session.copy(
            id = session.id.ifBlank { "local_sess_${System.currentTimeMillis()}" },
            userId = userId
        )
        // entry first, then the earlier entries without entry's id, capped.
        val draft = ListDraft<TrackingSession>(MAX_LOCAL_SESSIONS)
        draft.add(entry, null)
        if (snapshot != null) {
            val items = snapshot.items
            for (i in items.indices) {
                if (draft.size >= MAX_LOCAL_SESSIONS) break
                if (items[i].id != entry.id) draft.add(items[i], snapshot.itemJson[i])
            }
        }
        commitSessions(userId, draft)
        return entry
    }

    fun deleteLocalSession(userId: String, sessionId: String): List<TrackingSession> {
        val snapshot = readSessions(userId)
        val draft = ListDraft<TrackingSession>(snapshot?.items?.size ?: 0)
        if (snapshot != null) {
            val items = snapshot.items
            for (i in items.indices) if (items[i].id != sessionId) draft.add(items[i], snapshot.itemJson[i])
        }
        commitSessions(userId, draft)
        return draft.items
    }

    /**
     * Wipes [userId]'s vault: calibration profiles, sessions, the recorded active profile id, the
     * user-authored AAC phrase tiles (which can hold personal or medical text) and the interaction settings
     * (which include brow ratios measured from the user's face).
     */
    fun clearAllLocalData(userId: String) {
        val profilesKey = "${KEY_PROFILES_PREFIX}_$userId"
        val sessionsKey = "${KEY_SESSIONS_PREFIX}_$userId"
        prefs.edit()
            .remove(profilesKey)
            .remove(sessionsKey)
            .remove("${KEY_AAC_TILES_PREFIX}_$userId")
            .remove("${KEY_ACTIVE_PROFILE_ID_PREFIX}_$userId")
            .remove("${KEY_INTERACTION_SETTINGS_PREFIX}_$userId")
            .apply()
        profileSnapshots.remove(profilesKey)
        sessionSnapshots.remove(sessionsKey)
    }

    /**
     * Stores [userId]'s dwell, trigger, filter, posture and brow-calibration settings as one JSON entry,
     * replacing any earlier one. Non-finite numbers are left out and read back as defaults.
     */
    fun saveInteractionSettings(userId: String, settings: InteractionSettings) {
        val obj = JSONObject().apply {
            put("dwellTriggerMs", settings.dwellTriggerMs)
            putIfFinite("dwellSensitivityRadiusMultiplier", settings.dwellSensitivityRadiusMultiplier)
            put("activeDwellPreset", settings.activeDwellPreset.name)
            put("triggerMode", settings.triggerMode.name)
            put("filterMode", settings.filterMode.name)
            put("activePosture", settings.activePosture.name)
            putIfFinite("neutralBrowRatio", settings.neutralBrowRatio)
            putIfFinite("raisedBrowRatio", settings.raisedBrowRatio)
        }
        prefs.edit().putString("${KEY_INTERACTION_SETTINGS_PREFIX}_$userId", obj.toString()).apply()
    }

    /**
     * [userId]'s saved interaction settings, or null when none were saved (or the entry is unreadable), so the
     * caller keeps its own defaults. A missing, non-finite or unknown field reads back as the
     * [InteractionSettings] default; values are otherwise returned as saved, without range clamping.
     */
    fun loadInteractionSettings(userId: String): InteractionSettings? {
        val raw = prefs.getString("${KEY_INTERACTION_SETTINGS_PREFIX}_$userId", null) ?: return null
        return try {
            val obj = JSONObject(raw)
            val defaults = InteractionSettings()
            InteractionSettings(
                dwellTriggerMs = obj.optLong("dwellTriggerMs", defaults.dwellTriggerMs),
                dwellSensitivityRadiusMultiplier = obj.finiteFloat(
                    "dwellSensitivityRadiusMultiplier",
                    defaults.dwellSensitivityRadiusMultiplier
                ),
                activeDwellPreset = obj.optEnum("activeDwellPreset", DwellSpeedPreset.entries, defaults.activeDwellPreset),
                triggerMode = obj.optEnum("triggerMode", GazeTriggerMode.entries, defaults.triggerMode),
                filterMode = obj.optEnum("filterMode", GazeFilterMode.entries, defaults.filterMode),
                activePosture = obj.optEnum("activePosture", GazePostureProfile.entries, defaults.activePosture),
                neutralBrowRatio = obj.finiteFloat("neutralBrowRatio", defaults.neutralBrowRatio),
                raisedBrowRatio = obj.finiteFloat("raisedBrowRatio", defaults.raisedBrowRatio)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun JSONObject.putIfFinite(key: String, value: Float) {
        if (value.isFinite()) put(key, value.toDouble())
    }

    private fun JSONObject.finiteFloat(key: String, fallback: Float): Float =
        optDouble(key, Double.NaN).takeIf { it.isFinite() }?.toFloat() ?: fallback

    /** The entry of [entries] named by [key]'s string value (looked up once), else [fallback]. */
    private fun <E : Enum<E>> JSONObject.optEnum(key: String, entries: List<E>, fallback: E): E {
        val name = optString(key)
        return entries.firstOrNull { it.name == name } ?: fallback
    }

    private fun commitSessions(userId: String, draft: ListDraft<TrackingSession>) =
        commitList("${KEY_SESSIONS_PREFIX}_$userId", sessionSnapshots, draft, { encodeSession(it, userId) }, {})

    /** One stored session entry, with the keys in their stored order. */
    private fun encodeSession(s: TrackingSession, userId: String): EncodedEntry<TrackingSession> {
        val createdAtMs = s.createdAt?.toDate()?.time ?: System.currentTimeMillis()
        val json: String? = JSONObject().apply {
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
            put("createdAtMs", createdAtMs)
        }.toString()
        val readBack = sessionReadBack(s, userId, createdAtMs)
        return EncodedEntry(json, readBack, if (readBack != null) json else null)
    }

    /** What [parseSessions] returns for [s] encoded with [createdAtMs], or null when that is not certain. */
    private fun sessionReadBack(s: TrackingSession, userId: String, createdAtMs: Long): TrackingSession? {
        if (isNull(s.id) || isNull(s.title) || isNull(s.deviceModel) || isNull(s.contentType)) return null
        if (!isStringList(s.sectionLoads) || !isStringList(s.clusterPoints)) return null
        if (!s.blinksPerMinute.isExactInJson()) return null
        return try {
            val ts = Timestamp(Date(createdAtMs))
            s.copy(
                userId = userId,
                sectionLoads = ArrayList(s.sectionLoads),
                clusterPoints = ArrayList(s.clusterPoints),
                createdAt = ts,
                updatedAt = ts
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun loadCustomAacTiles(
        userId: String,
        defaultTiles: List<AacActionTile>
    ): List<AacActionTile> {
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
        tiles: List<AacActionTile>
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

    /**
     * The snapshot of the list stored under [key], parsing the stored string only when it is not the one the
     * cached snapshot came from. Null when nothing is stored under [key].
     */
    private inline fun <T> readSnapshot(
        key: String,
        snapshots: ConcurrentHashMap<String, VaultListSnapshot<T>>,
        parse: (raw: String) -> VaultListSnapshot<T>
    ): VaultListSnapshot<T>? {
        val raw = prefs.getString(key, null) ?: return null
        val cached = snapshots[key]
        if (cached != null && cached.raw == raw) return cached
        val parsed = parse(raw)
        if (parsed.reusable) snapshots[key] = parsed else snapshots.remove(key)
        return parsed
    }

    /**
     * Stores [draft] under [key] as one JSON array, together with [alsoEdit]'s changes in the same commit.
     * Entries with a known text are reused as is; the others are encoded with [encode]. The array text is
     * what JSONArray.toString() gives for those objects: null when one of them cannot be encoded (a
     * non-finite coefficient), which removes [key] as it always has. An exception from [encode] (a
     * non-finite top-level number) propagates before anything is written.
     */
    private fun <T> commitList(
        key: String,
        snapshots: ConcurrentHashMap<String, VaultListSnapshot<T>>,
        draft: ListDraft<T>,
        encode: (T) -> EncodedEntry<T>,
        alsoEdit: (SharedPreferences.Editor) -> Unit
    ) {
        val n = draft.size
        val json = arrayOfNulls<String>(n)
        val readBack = ArrayList<T>(n)
        val readBackJson = arrayOfNulls<String>(n)
        var complete = true
        var predictable = true
        for (i in 0 until n) {
            val item = draft.items[i]
            val known = draft.json[i]
            if (known != null) {
                // Unchanged since it was read: it encodes to [known] and reads back as itself.
                json[i] = known
                readBack.add(item)
                readBackJson[i] = known
                continue
            }
            val entry = encode(item)
            val text = entry.json
            if (text == null) complete = false else json[i] = text
            val back = entry.readBack
            if (back == null) {
                predictable = false
            } else {
                readBack.add(back)
                readBackJson[i] = entry.readBackJson
            }
        }
        val raw = if (complete) joinJsonArray(json) else null
        val editor = prefs.edit().putString(key, raw)
        alsoEdit(editor)
        editor.apply()
        if (raw != null && predictable) snapshots[key] = VaultListSnapshot(raw, readBack, readBackJson) else snapshots.remove(key)
    }

    /**
     * A list key's content as last read or written: [raw] is the exact stored string and [items] what
     * parsing it returns. [itemJson] holds, per item, the JSON text encoding that item produces (null where
     * not known, so that item is encoded again). [reusable] is false when parsing used the clock (an entry
     * without "createdAtMs" reads back with the current time), so such a list is parsed on every read.
     */
    private class VaultListSnapshot<T>(
        val raw: String,
        val items: List<T>,
        val itemJson: Array<String?>,
        val reusable: Boolean = true
    )

    /** A list being rebuilt for a write; entries kept unchanged from a snapshot carry their known JSON text. */
    private class ListDraft<T>(capacity: Int) {
        val items = ArrayList<T>(capacity)
        val json = ArrayList<String?>(capacity)
        val size: Int get() = items.size

        fun add(item: T, knownJson: String?) {
            items.add(item)
            json.add(knownJson)
        }
    }

    /**
     * One encoded entry: its JSON text ([json], null when JSONObject.toString() fails), the value parsing it
     * yields ([readBack], null when not certain) and the JSON text encoding [readBack] produces.
     */
    private class EncodedEntry<T>(val json: String?, val readBack: T?, val readBackJson: String?)

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
        private const val KEY_INTERACTION_SETTINGS_PREFIX = "local_interaction_settings"
    }
}

/** (-0.0).toRawBits(): org.json writes -0.0 as "-0", which reads back as +0.0. */
private const val NEGATIVE_ZERO_BITS = Long.MIN_VALUE

/** The text JSONArray.toString() produces for objects whose own toString() texts are [entries]. */
private fun joinJsonArray(entries: Array<String?>): String {
    var length = entries.size + 2
    for (e in entries) length += e?.length ?: 0
    val sb = StringBuilder(length).append('[')
    for (i in entries.indices) {
        if (i > 0) sb.append(',')
        sb.append(entries[i])
    }
    return sb.append(']').toString()
}

/**
 * The entry's "createdAtMs" as optLong("createdAtMs", now) reads it. Reading with two different
 * fallbacks tells a stored value from a substituted one; [onClock] runs when the clock is used.
 */
private inline fun createdAtMsOrNow(obj: JSONObject, onClock: () -> Unit): Long {
    val stored = obj.optLong("createdAtMs", 0L)
    if (stored == obj.optLong("createdAtMs", 1L)) return stored
    onClock()
    return System.currentTimeMillis()
}

private fun isKnownPosture(name: String): Boolean = GazePostureProfile.entries.any { it.name == name }

/** True for a null that reached a non-null Kotlin type through reflection (Firestore's mapper). */
private fun isNull(value: Any?): Boolean = value == null

private fun isStringList(value: Any?): Boolean = value is List<*> && value.all { it is String }

/** Whether org.json writes this number so that it reads back with the same bits. */
private fun Double.isExactInJson(): Boolean = isFinite() && toRawBits() != NEGATIVE_ZERO_BITS
