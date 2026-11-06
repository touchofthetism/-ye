package com.example.regression

import android.content.Context
import android.content.res.XmlResourceParser
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.data.model.AacActionTile
import com.example.data.model.AnalysisResolutionTier
import com.example.data.model.CalibrationProfile
import com.example.data.model.GazeFeatureVector
import com.example.data.model.GazeTriggerMode
import com.example.data.model.TrackingSession
import com.example.data.repository.LocalTelemetryStore
import com.example.domain.BrowRaiseHysteresisDetector
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.model.ResourcePath
import com.google.firebase.firestore.util.CustomClassMapper
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * Regression tests for GazeModels.kt and LocalTelemetryStore.kt:
 * - CalibrationProfile.isActive round-trips through Firestore's class mapper under the stored "isActive" key,
 * - the standardized feature mapping no longer changes gain with the low-light flag,
 * - labels match the real detector timing and analysis resolution,
 * - cloud-sync consent is per account, the local vault returns persisted ids, wipes AAC phrases,
 *   names its caps, records the chosen active profile, and is excluded from backup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ModelsstoreRegressionTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun vaultPrefs() =
        context.getSharedPreferences(LocalTelemetryStore.VAULT_PREFS_NAME, Context.MODE_PRIVATE)

    // ---- A51 / A66 / C2: isActive must survive Firestore's reflection mapper -------------------------

    @Test
    fun calibrationProfile_storedIsActiveFalse_readsBackInactiveThroughFirestoreMapper() {
        val docRef = DocumentReference.forPath(
            ResourcePath.fromString("users/u_roundtrip/calibrations/cal_roundtrip_1"),
            firestore()
        )
        val stored = mapOf<String, Any>(
            "userId" to "u_roundtrip",
            "name" to "Older desk profile",
            "isActive" to false
        )

        val inactive = CustomClassMapper.convertToCustomClass(stored, CalibrationProfile::class.java, docRef)
        assertEquals("cal_roundtrip_1", inactive.id)
        assertEquals("Older desk profile", inactive.name)
        assertFalse("A stored isActive=false must not read back as the default true", inactive.isActive)

        val active = CustomClassMapper.convertToCustomClass(
            stored + ("isActive" to true),
            CalibrationProfile::class.java,
            docRef
        )
        assertTrue(active.isActive)
    }

    @Test
    fun calibrationProfile_serializesActiveFlagUnderTheStoredKey() {
        @Suppress("UNCHECKED_CAST")
        val plain = CustomClassMapper.convertToPlainJavaTypes(CalibrationProfile(isActive = false)) as Map<String, Any?>
        assertEquals(false, plain["isActive"])
        assertFalse("The mapper must not invent an 'active' key: ${plain.keys}", plain.containsKey("active"))
    }

    // ---- A56: no per-frame low-light gain switch on the regressor inputs -----------------------------

    @Test
    fun standardizedFeatures_areIdenticalWhetherOrNotLowLightIsFlagged() {
        val bright = GazeFeatureVector(
            gx = 0.12,
            gy = -0.08,
            yaw = 6.0,
            pitch = -4.0,
            roll = 1.5,
            eyeDistancePx = 152.0,
            faceDetected = true,
            ambientLuma = 150,
            isLowLightDegraded = false,
            timestampMs = 0L
        )
        val dim = bright.copy(ambientLuma = 40, isLowLightDegraded = true)

        assertArrayEquals(
            bright.toStandardizedFeatureArray(148.0),
            dim.toStandardizedFeatureArray(148.0),
            0.0
        )
    }

    // ---- B47 (model side): frames can be marked as simulated -----------------------------------------

    @Test
    fun gazeFeatureVector_isMeasuredByDefaultAndCanBeMarkedSimulated() {
        val measured = GazeFeatureVector(faceDetected = true, timestampMs = 0L)
        assertFalse(measured.isSimulated)

        val simulated = measured.copy(isSimulated = true)
        assertTrue(simulated.isSimulated)
        assertArrayEquals(measured.toStandardizedFeatureArray(), simulated.toStandardizedFeatureArray(), 0.0)
    }

    // ---- A62 / A63: labels shown to the user match the real behaviour --------------------------------

    @Test
    fun browRaiseModeLabel_statesTheDetectorHoldTime() {
        val holdMs = BrowRaiseHysteresisDetector().holdTargetMs
        val label = GazeTriggerMode.BROW_RAISE.label
        assertTrue("'$label' must state the ${holdMs} ms hold", label.contains("(${holdMs}ms Hold)"))
    }

    @Test
    fun highResolutionTierLabel_doesNotClaimSensorMegapixels() {
        val tier = AnalysisResolutionTier.TIER_HIGH_1280
        assertFalse("'${tier.shortLabel}' must not claim a sensor resolution", tier.shortLabel.contains("MP"))
        assertTrue(tier.shortLabel.startsWith("${tier.targetWidth}w"))
    }

    // ---- A238: cloud-sync consent is per account ------------------------------------------------------

    @Test
    fun cloudSyncConsent_isScopedPerAccount() {
        val store = LocalTelemetryStore(context)
        assertFalse(store.isCloudSyncEnabled("caregiver_a"))

        store.setCloudSyncEnabled("caregiver_a", true)
        assertTrue(store.isCloudSyncEnabled("caregiver_a"))
        assertFalse("A second account must not inherit the first account's opt-in", store.isCloudSyncEnabled("patient_b"))
        assertTrue(LocalTelemetryStore(context).isCloudSyncEnabled("caregiver_a"))

        store.setCloudSyncEnabled("caregiver_a", false)
        assertFalse(store.isCloudSyncEnabled("caregiver_a"))
    }

    @Test
    fun legacyDeviceWideOptIn_isNotInheritedAndIsDroppedOnFirstPerAccountWrite() {
        vaultPrefs().edit().putBoolean("cloud_sync_enabled", true).commit()
        val store = LocalTelemetryStore(context)

        assertFalse(store.isCloudSyncEnabled("new_account"))
        store.setCloudSyncEnabled("new_account", false)
        assertFalse(vaultPrefs().contains("cloud_sync_enabled"))
        assertFalse(store.isCloudSyncEnabled("new_account"))
    }

    // ---- A52: saves return the entry exactly as persisted --------------------------------------------

    @Test
    fun saveLocalProfileAndSession_returnTheEntryAsPersisted() {
        val store = LocalTelemetryStore(context)
        val uid = "ids_uid"

        val savedProfile = store.saveLocalProfile(uid, CalibrationProfile(name = "Desk", isActive = false))
        assertTrue(savedProfile.id, savedProfile.id.startsWith("local_cal_"))
        assertTrue(savedProfile.isActive)
        assertEquals(uid, savedProfile.userId)
        assertEquals(listOf(savedProfile.id), store.loadLocalProfiles(uid).map { it.id })

        val savedSession = store.saveLocalSession(uid, TrackingSession(title = "Reading"))
        assertTrue(savedSession.id, savedSession.id.startsWith("local_sess_"))
        assertEquals(uid, savedSession.userId)
        assertEquals(listOf(savedSession.id), store.loadLocalSessions(uid).map { it.id })

        // A caller-supplied id is kept, so one id can be shared by the local and cloud copies.
        assertEquals("cal_shared_1", store.saveLocalProfile(uid, CalibrationProfile(id = "cal_shared_1")).id)
        assertEquals("sess_shared_1", store.saveLocalSession(uid, TrackingSession(id = "sess_shared_1")).id)
    }

    // ---- A61: wiping the vault also wipes user-authored AAC phrases ----------------------------------

    @Test
    fun clearAllLocalData_wipesCustomAacPhrases() {
        val store = LocalTelemetryStore(context)
        val uid = "wipe_uid"
        val defaults = listOf(
            AacActionTile("tile_water", "Request Water", "Hydration assistance", "Could I please have some water?", "NEEDS")
        )
        store.saveCustomAacTiles(
            uid,
            listOf(defaults.single().copy(title = "Private", speechPhrase = "Please call my doctor about my insulin."))
        )
        assertEquals(
            "Please call my doctor about my insulin.",
            store.loadCustomAacTiles(uid, defaults).single().speechPhrase
        )

        store.clearAllLocalData(uid)

        assertEquals(defaults, store.loadCustomAacTiles(uid, defaults))
        assertEquals(defaults, LocalTelemetryStore(context).loadCustomAacTiles(uid, defaults))
    }

    // ---- A251: the vault caps are named and enforced --------------------------------------------------

    @Test
    fun localVaultCaps_areNamedAndEvictTheOldestEntry() {
        assertEquals(20, LocalTelemetryStore.MAX_LOCAL_PROFILES)
        assertEquals(30, LocalTelemetryStore.MAX_LOCAL_SESSIONS)

        val store = LocalTelemetryStore(context)
        val uid = "cap_uid"
        for (i in 0..LocalTelemetryStore.MAX_LOCAL_SESSIONS) {
            store.saveLocalSession(uid, TrackingSession(id = "s$i", title = "Session $i"))
        }
        val keptSessions = store.loadLocalSessions(uid).map { it.id }
        assertEquals(LocalTelemetryStore.MAX_LOCAL_SESSIONS, keptSessions.size)
        assertEquals("s${LocalTelemetryStore.MAX_LOCAL_SESSIONS}", keptSessions.first())
        assertFalse(keptSessions.contains("s0"))

        for (i in 0..LocalTelemetryStore.MAX_LOCAL_PROFILES) {
            store.saveLocalProfile(uid, CalibrationProfile(id = "p$i", name = "Profile $i"))
        }
        val keptProfiles = store.loadLocalProfiles(uid).map { it.id }
        assertEquals(LocalTelemetryStore.MAX_LOCAL_PROFILES, keptProfiles.size)
        assertFalse(keptProfiles.contains("p0"))
    }

    // ---- A244 (store side): the chosen active profile id is recorded per user ------------------------

    @Test
    fun activeProfileId_followsSaveActivateDeleteAndWipe() {
        val store = LocalTelemetryStore(context)
        val uid = "active_uid"
        assertNull(store.getActiveProfileId(uid))

        store.saveLocalProfile(uid, CalibrationProfile(id = "cal_a", name = "A"))
        assertEquals("cal_a", store.getActiveProfileId(uid))
        store.saveLocalProfile(uid, CalibrationProfile(id = "cal_b", name = "B"))
        assertEquals("cal_b", store.getActiveProfileId(uid))

        store.setActiveLocalProfile(uid, "cal_a")
        assertEquals("cal_a", store.getActiveProfileId(uid))
        assertEquals(listOf("cal_a"), store.loadLocalProfiles(uid).filter { it.isActive }.map { it.id })

        // A cloud-only profile (no local entry) can also be the user's choice.
        store.setActiveLocalProfile(uid, "cal_cloud_only")
        assertEquals("cal_cloud_only", store.getActiveProfileId(uid))
        assertNull(store.getActiveProfileId("other_uid"))

        store.setActiveLocalProfile(uid, "cal_a")
        store.deleteLocalProfile(uid, "cal_a")
        assertNull(store.getActiveProfileId(uid))

        store.setActiveProfileId(uid, "cal_b")
        assertEquals("cal_b", store.getActiveProfileId(uid))
        store.clearAllLocalData(uid)
        assertNull(store.getActiveProfileId(uid))
    }

    // ---- A54: the biometric vault never leaves the device through backup or transfer ------------------

    @Test
    fun localVault_isExcludedFromCloudBackupAndDeviceTransfer() {
        val vaultFile = "${LocalTelemetryStore.VAULT_PREFS_NAME}.xml"

        val fullBackup = sharedPrefExcludesBySection(R.xml.backup_rules)
        assertTrue(
            "backup_rules.xml must exclude $vaultFile, found $fullBackup",
            fullBackup["full-backup-content"].orEmpty().contains(vaultFile)
        )

        val extraction = sharedPrefExcludesBySection(R.xml.data_extraction_rules)
        assertTrue(
            "data_extraction_rules.xml <cloud-backup> must exclude $vaultFile, found $extraction",
            extraction["cloud-backup"].orEmpty().contains(vaultFile)
        )
        assertTrue(
            "data_extraction_rules.xml <device-transfer> must exclude $vaultFile, found $extraction",
            extraction["device-transfer"].orEmpty().contains(vaultFile)
        )
    }

    private fun sharedPrefExcludesBySection(xmlResId: Int): Map<String, Set<String>> {
        val sections = setOf("full-backup-content", "cloud-backup", "device-transfer")
        val result = mutableMapOf<String, MutableSet<String>>()
        val parser: XmlResourceParser = context.resources.getXml(xmlResId)
        try {
            var currentSection: String? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val tag = parser.name
                    val section = currentSection
                    if (tag in sections) {
                        currentSection = tag
                    } else if (tag == "exclude" && section != null && parser.attribute("domain") == "sharedpref") {
                        parser.attribute("path")?.let { path -> result.getOrPut(section) { mutableSetOf() }.add(path) }
                    }
                } else if (event == XmlPullParser.END_TAG && parser.name == currentSection) {
                    currentSection = null
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return result
    }

    private fun XmlPullParser.attribute(name: String): String? =
        (0 until attributeCount).firstOrNull { getAttributeName(it) == name }?.let { getAttributeValue(it) }

    private fun firestore(): FirebaseFirestore {
        val app = FirebaseApp.getApps(context).firstOrNull { it.name == FIREBASE_APP_NAME }
            ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("com.aistudio.oculagaze.rxkmpq")
                    .setProjectId("demo-models-store-regression")
                    .setApiKey("fake-api-key-for-unit-tests")
                    .build(),
                FIREBASE_APP_NAME
            )
        return FirebaseFirestore.getInstance(app)
    }

    private companion object {
        const val FIREBASE_APP_NAME = "models-store-regression"
    }
}
