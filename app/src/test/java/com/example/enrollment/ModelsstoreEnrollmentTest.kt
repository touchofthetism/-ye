package com.example.enrollment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.EnrollmentBaseline
import com.example.data.model.InteractionSettings
import com.example.data.repository.LocalTelemetryStore
import org.json.JSONArray
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

/**
 * Models-store side of the per-user face & eye enrollment scan (contract C2):
 * - [EnrollmentBaseline] round-trips through the on-device vault under `local_enrollment_baseline_<uid>`,
 * - an absent or corrupt entry, or one with a missing / non-finite required number, reads back null,
 * - fields with defaults (version, capturedAtMs, deviceModel, warnings) and the optional raisedBrowRatio
 *   read back as their defaults when missing,
 * - baselines are isolated per account, cleared on their own and wiped with the rest of the vault.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ModelsstoreEnrollmentTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun vaultPrefs() =
        context.getSharedPreferences(LocalTelemetryStore.VAULT_PREFS_NAME, Context.MODE_PRIVATE)

    private fun rawEntry(uid: String): String? = vaultPrefs().getString("local_enrollment_baseline_$uid", null)

    private fun fullBaseline() = EnrollmentBaseline(
        version = 1,
        capturedAtMs = 1_762_700_000_123L,
        deviceModel = "Pixel 10 Pro XL",
        openEyeLevel = 0.91f,
        closedEyeLevel = 0.12f,
        leftOpenLevel = 0.93f,
        rightOpenLevel = 0.89f,
        baselineEyeDistancePx = 151.25,
        neutralBrowRatio = 0.335f,
        raisedBrowRatio = 0.418f,
        naturalBlinkCount = 3,
        naturalBlinkMedianMs = 190L,
        naturalBlinkP95Ms = 260L,
        closeBlinkThreshold = 0.3965f,
        openBlinkThreshold = 0.6335f,
        intentionalBlinkMinMs = 350L,
        meanAmbientLuma = 138,
        meanEyeConfidence = 0.87f,
        frameCount = 412,
        warnings = listOf("LOW_LIGHT", "FEW_NATURAL_BLINKS")
    )

    // ---- Round trip -----------------------------------------------------------------------------------

    @Test
    fun saveAndLoad_roundTripEveryFieldThroughANewStoreInstance() {
        val store = LocalTelemetryStore(context)
        val uid = "enroll_uid"
        assertNull(store.loadEnrollmentBaseline(uid))

        val baseline = fullBaseline()
        store.saveEnrollmentBaseline(uid, baseline)

        assertEquals(baseline, store.loadEnrollmentBaseline(uid))
        assertEquals("A fresh store must read the persisted entry", baseline, LocalTelemetryStore(context).loadEnrollmentBaseline(uid))
    }

    @Test
    fun saveAndLoad_roundTripWithSkippedBrowStepAndNoWarnings() {
        val store = LocalTelemetryStore(context)
        val uid = "brow_skipped_uid"
        val baseline = fullBaseline().copy(raisedBrowRatio = null, warnings = emptyList())

        store.saveEnrollmentBaseline(uid, baseline)

        val loaded = store.loadEnrollmentBaseline(uid)
        assertEquals(baseline, loaded)
        assertNull(loaded?.raisedBrowRatio)
        assertTrue(loaded?.warnings?.isEmpty() == true)
        val raw = rawEntry(uid)
        assertNotNull(raw)
        assertFalse("A null raised ratio is left out rather than written as JSON null: $raw", JSONObject(raw!!).has("raisedBrowRatio"))
    }

    @Test
    fun save_replacesTheEarlierBaselineOfTheSameUser() {
        val store = LocalTelemetryStore(context)
        val uid = "rescan_uid"
        store.saveEnrollmentBaseline(uid, fullBaseline())

        val rescan = fullBaseline().copy(capturedAtMs = 1_762_800_000_000L, openEyeLevel = 0.88f, frameCount = 399)
        store.saveEnrollmentBaseline(uid, rescan)

        assertEquals(rescan, store.loadEnrollmentBaseline(uid))
    }

    // ---- Storage location and contents ----------------------------------------------------------------

    @Test
    fun baseline_isStoredUnderThePerUserKeyInTheBackupExcludedVaultFile_asDerivedNumbersOnly() {
        val store = LocalTelemetryStore(context)
        val uid = "key_uid"
        store.saveEnrollmentBaseline(uid, fullBaseline())

        val raw = rawEntry(uid)
        assertNotNull("The entry must live under local_enrollment_baseline_<uid> in ${LocalTelemetryStore.VAULT_PREFS_NAME}", raw)
        val obj = JSONObject(raw!!)
        assertEquals(1, obj.getInt("version"))
        assertEquals(0.91, obj.getDouble("openEyeLevel"), 1e-6)
        assertEquals(0.12, obj.getDouble("closedEyeLevel"), 1e-6)
        assertEquals(151.25, obj.getDouble("baselineEyeDistancePx"), 0.0)
        assertEquals(350L, obj.getLong("intentionalBlinkMinMs"))
        assertEquals(412, obj.getInt("frameCount"))
        assertEquals("LOW_LIGHT", obj.getJSONArray("warnings").getString(0))
        assertEquals("FEW_NATURAL_BLINKS", obj.getJSONArray("warnings").getString(1))

        // Only the contract's derived fields are written: nothing image-, frame- or landmark-like.
        val expectedKeys = setOf(
            "version", "capturedAtMs", "deviceModel", "openEyeLevel", "closedEyeLevel", "leftOpenLevel",
            "rightOpenLevel", "baselineEyeDistancePx", "neutralBrowRatio", "raisedBrowRatio", "naturalBlinkCount",
            "naturalBlinkMedianMs", "naturalBlinkP95Ms", "closeBlinkThreshold", "openBlinkThreshold",
            "intentionalBlinkMinMs", "meanAmbientLuma", "meanEyeConfidence", "frameCount", "warnings"
        )
        val storedKeys = obj.keys().asSequence().toSet()
        assertEquals(expectedKeys, storedKeys)
    }

    // ---- Absent / corrupt / non-finite -> null ---------------------------------------------------------

    @Test
    fun load_returnsNullWhenAbsentOrNotJson() {
        val store = LocalTelemetryStore(context)
        assertNull(store.loadEnrollmentBaseline("never_enrolled_uid"))

        vaultPrefs().edit().putString("local_enrollment_baseline_corrupt_uid", "not json").commit()
        assertNull(store.loadEnrollmentBaseline("corrupt_uid"))

        vaultPrefs().edit().putString("local_enrollment_baseline_array_uid", "[1,2,3]").commit()
        assertNull(store.loadEnrollmentBaseline("array_uid"))

        vaultPrefs().edit().putString("local_enrollment_baseline_empty_uid", "{}").commit()
        assertNull("An entry with no measurements is not a baseline", store.loadEnrollmentBaseline("empty_uid"))
    }

    @Test
    fun load_returnsNullWhenARequiredFieldIsMissingOrNotANumber() {
        val store = LocalTelemetryStore(context)
        val uid = "required_uid"
        store.saveEnrollmentBaseline(uid, fullBaseline())
        val complete = JSONObject(rawEntry(uid)!!)

        val requiredNumbers = listOf(
            "openEyeLevel", "closedEyeLevel", "leftOpenLevel", "rightOpenLevel", "baselineEyeDistancePx",
            "neutralBrowRatio", "naturalBlinkCount", "naturalBlinkMedianMs", "naturalBlinkP95Ms",
            "closeBlinkThreshold", "openBlinkThreshold", "intentionalBlinkMinMs", "meanAmbientLuma",
            "meanEyeConfidence", "frameCount"
        )
        for (key in requiredNumbers) {
            val missing = JSONObject(complete.toString()).apply { remove(key) }
            vaultPrefs().edit().putString("local_enrollment_baseline_missing_uid", missing.toString()).commit()
            assertNull("Missing $key must make the entry unreadable", store.loadEnrollmentBaseline("missing_uid"))

            val text = JSONObject(complete.toString()).apply { put(key, "abc") }
            vaultPrefs().edit().putString("local_enrollment_baseline_text_uid", text.toString()).commit()
            assertNull("Non-numeric $key must make the entry unreadable", store.loadEnrollmentBaseline("text_uid"))
        }
    }

    @Test
    fun load_returnsNullWhenARequiredFloatIsNonFinite() {
        val store = LocalTelemetryStore(context)

        store.saveEnrollmentBaseline("nan_open_uid", fullBaseline().copy(openEyeLevel = Float.NaN))
        assertNull("A NaN open level is not a measurement", store.loadEnrollmentBaseline("nan_open_uid"))

        store.saveEnrollmentBaseline("inf_dist_uid", fullBaseline().copy(baselineEyeDistancePx = Double.POSITIVE_INFINITY))
        assertNull(store.loadEnrollmentBaseline("inf_dist_uid"))

        store.saveEnrollmentBaseline("inf_close_uid", fullBaseline().copy(closeBlinkThreshold = Float.NEGATIVE_INFINITY))
        assertNull(store.loadEnrollmentBaseline("inf_close_uid"))

        store.saveEnrollmentBaseline("nan_conf_uid", fullBaseline().copy(meanEyeConfidence = Float.NaN))
        assertNull(store.loadEnrollmentBaseline("nan_conf_uid"))

        // A hand-edited entry carrying a textual NaN / Infinity is rejected the same way.
        store.saveEnrollmentBaseline("textual_uid", fullBaseline())
        val obj = JSONObject(rawEntry("textual_uid")!!)
        vaultPrefs().edit().putString(
            "local_enrollment_baseline_textual_nan_uid",
            JSONObject(obj.toString()).apply { put("closedEyeLevel", "NaN") }.toString()
        ).commit()
        assertNull(store.loadEnrollmentBaseline("textual_nan_uid"))
        vaultPrefs().edit().putString(
            "local_enrollment_baseline_textual_inf_uid",
            JSONObject(obj.toString()).apply { put("neutralBrowRatio", "Infinity") }.toString()
        ).commit()
        assertNull(store.loadEnrollmentBaseline("textual_inf_uid"))
    }

    @Test
    fun load_nonFiniteRaisedBrowRatioReadsBackNullWithoutDroppingTheBaseline() {
        val store = LocalTelemetryStore(context)
        val uid = "nan_raised_uid"
        store.saveEnrollmentBaseline(uid, fullBaseline().copy(raisedBrowRatio = Float.NaN))

        val loaded = store.loadEnrollmentBaseline(uid)
        assertNotNull(loaded)
        assertNull(loaded?.raisedBrowRatio)
        assertEquals(fullBaseline().copy(raisedBrowRatio = null), loaded)
    }

    // ---- Defaults for the optional fields --------------------------------------------------------------

    @Test
    fun load_fieldsWithDefaultsReadBackAsDefaultsWhenMissing() {
        val store = LocalTelemetryStore(context)
        val uid = "defaults_uid"
        store.saveEnrollmentBaseline(uid, fullBaseline())
        val stripped = JSONObject(rawEntry(uid)!!).apply {
            remove("version")
            remove("capturedAtMs")
            remove("deviceModel")
            remove("warnings")
            remove("raisedBrowRatio")
        }
        vaultPrefs().edit().putString("local_enrollment_baseline_stripped_uid", stripped.toString()).commit()

        val loaded = store.loadEnrollmentBaseline("stripped_uid")
        assertNotNull(loaded)
        assertEquals(1, loaded!!.version)
        assertEquals(0L, loaded.capturedAtMs)
        assertEquals("", loaded.deviceModel)
        assertEquals(emptyList<String>(), loaded.warnings)
        assertNull(loaded.raisedBrowRatio)
        assertEquals(0.91f, loaded.openEyeLevel, 0f)
        assertEquals(412, loaded.frameCount)
    }

    @Test
    fun load_dropsBlankWarningNamesAndKeepsTheOthersAsStored() {
        val store = LocalTelemetryStore(context)
        store.saveEnrollmentBaseline("warn_uid", fullBaseline())
        val obj = JSONObject(rawEntry("warn_uid")!!).apply {
            put("warnings", JSONArray(listOf("GLARE", "", "   ", "SINGLE_EYE")))
        }
        vaultPrefs().edit().putString("local_enrollment_baseline_warn2_uid", obj.toString()).commit()

        assertEquals(listOf("GLARE", "SINGLE_EYE"), store.loadEnrollmentBaseline("warn2_uid")?.warnings)
    }

    // ---- Per-user isolation, clear and wipe ------------------------------------------------------------

    @Test
    fun baselines_areIsolatedPerAccount() {
        val store = LocalTelemetryStore(context)
        val caregiver = fullBaseline()
        val patient = fullBaseline().copy(openEyeLevel = 0.78f, closedEyeLevel = 0.2f, raisedBrowRatio = null)

        store.saveEnrollmentBaseline("caregiver_a", caregiver)
        assertNull("A second account must not inherit the first account's face measurements", store.loadEnrollmentBaseline("patient_b"))

        store.saveEnrollmentBaseline("patient_b", patient)
        assertEquals(caregiver, store.loadEnrollmentBaseline("caregiver_a"))
        assertEquals(patient, store.loadEnrollmentBaseline("patient_b"))
    }

    @Test
    fun clearEnrollmentBaseline_removesOnlyThatUsersBaseline() {
        val store = LocalTelemetryStore(context)
        store.saveEnrollmentBaseline("clear_a", fullBaseline())
        store.saveEnrollmentBaseline("clear_b", fullBaseline())
        store.saveInteractionSettings("clear_a", InteractionSettings(neutralBrowRatio = 0.31f))

        store.clearEnrollmentBaseline("clear_a")

        assertNull(store.loadEnrollmentBaseline("clear_a"))
        assertNull(rawEntry("clear_a"))
        assertNull(LocalTelemetryStore(context).loadEnrollmentBaseline("clear_a"))
        assertEquals("Another account's baseline stays", fullBaseline(), store.loadEnrollmentBaseline("clear_b"))
        assertEquals(
            "Clearing the baseline leaves the same account's interaction settings alone",
            InteractionSettings(neutralBrowRatio = 0.31f),
            store.loadInteractionSettings("clear_a")
        )

        // Clearing an account that has no baseline is a no-op.
        store.clearEnrollmentBaseline("never_enrolled_uid")
        assertNull(store.loadEnrollmentBaseline("never_enrolled_uid"))
    }

    @Test
    fun clearAllLocalData_wipesTheBaselineWithTheRestOfTheVault() {
        val store = LocalTelemetryStore(context)
        val uid = "wipe_uid"
        store.saveEnrollmentBaseline(uid, fullBaseline())
        store.saveEnrollmentBaseline("other_uid", fullBaseline())
        assertNotNull(store.loadEnrollmentBaseline(uid))

        store.clearAllLocalData(uid)

        assertNull(store.loadEnrollmentBaseline(uid))
        assertNull(rawEntry(uid))
        assertNull(LocalTelemetryStore(context).loadEnrollmentBaseline(uid))
        assertEquals("Wiping one account leaves the other's baseline", fullBaseline(), store.loadEnrollmentBaseline("other_uid"))
    }

    // ---- Model -----------------------------------------------------------------------------------------

    @Test
    fun enrollmentBaseline_defaultsAndNullableRaisedRatio() {
        val minimal = EnrollmentBaseline(
            openEyeLevel = 0.9f,
            closedEyeLevel = 0.1f,
            leftOpenLevel = 0.9f,
            rightOpenLevel = 0.9f,
            baselineEyeDistancePx = 148.0,
            neutralBrowRatio = 0.34f,
            raisedBrowRatio = null,
            naturalBlinkCount = 0,
            naturalBlinkMedianMs = 0L,
            naturalBlinkP95Ms = 0L,
            closeBlinkThreshold = 0.45f,
            openBlinkThreshold = 0.65f,
            intentionalBlinkMinMs = 350L,
            meanAmbientLuma = 140,
            meanEyeConfidence = 0.8f,
            frameCount = 60
        )
        assertEquals(1, minimal.version)
        assertEquals(0L, minimal.capturedAtMs)
        assertEquals("", minimal.deviceModel)
        assertEquals(emptyList<String>(), minimal.warnings)
        assertNull(minimal.raisedBrowRatio)

        // The model is a plain on-device record: no Firestore mapping annotations, so nothing can sync it by accident.
        val annotationNames = EnrollmentBaseline::class.java.annotations.map { it.annotationClass.java.name }
        assertFalse(annotationNames.toString(), annotationNames.any { it.startsWith("com.google.firebase") })
    }
}
