package com.example.regression

import android.view.HapticFeedbackConstants
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.example.InlineCloudPrivacyBar
import com.example.shouldClearUserScopedViewModels
import com.example.tileTriggerHapticFeedbackConstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Regression tests for MainActivity.kt, AndroidManifest.xml and the backup rule resources.
 * Plain JVM tests: the source XML files are parsed directly from the module directory.
 */
class ActivityRegressionTest {

    @Test
    fun `manifest declares the TTS service query so TextToSpeech can bind its engine on API 30+`() {
        val manifest = parseXml(sourceFile("src/main/AndroidManifest.xml")).documentElement
        val queriedActions = manifest.childElements("queries")
            .flatMap { it.childElements("intent") }
            .flatMap { it.childElements("action") }
            .map { it.getAttributeNS(ANDROID_NS, "name") }
        assertTrue(
            "AndroidManifest.xml must declare <queries><intent><action android:name=" +
                "\"$TTS_SERVICE_ACTION\"/></intent></queries>, found $queriedActions",
            TTS_SERVICE_ACTION in queriedActions
        )
    }

    @Test
    fun `legacy full backup rules exclude the on-device telemetry vault`() {
        val application = parseXml(sourceFile("src/main/AndroidManifest.xml"))
            .documentElement.childElements("application").single()
        assertEquals("@xml/backup_rules", application.getAttributeNS(ANDROID_NS, "fullBackupContent"))

        val rules = parseXml(sourceFile("src/main/res/xml/backup_rules.xml")).documentElement
        assertEquals("full-backup-content", rules.tagName)
        assertTrue(
            "backup_rules.xml must exclude sharedpref/$VAULT_PREFS_FILE (API 24-30 Auto Backup)",
            rules.excludes("sharedpref", VAULT_PREFS_FILE)
        )
        assertTrue(
            "backup_rules.xml must exclude the Firestore cache databases",
            rules.excludes("database", ".")
        )
    }

    @Test
    fun `data extraction rules exclude the vault from cloud backup and device transfer`() {
        val application = parseXml(sourceFile("src/main/AndroidManifest.xml"))
            .documentElement.childElements("application").single()
        assertEquals("@xml/data_extraction_rules", application.getAttributeNS(ANDROID_NS, "dataExtractionRules"))

        val rules = parseXml(sourceFile("src/main/res/xml/data_extraction_rules.xml")).documentElement
        assertEquals("data-extraction-rules", rules.tagName)
        for (section in listOf("cloud-backup", "device-transfer")) {
            val sectionElement = rules.childElements(section).singleOrNull()
            assertNotNull("data_extraction_rules.xml needs a live <$section> section", sectionElement)
            assertTrue(
                "<$section> must exclude sharedpref/$VAULT_PREFS_FILE",
                sectionElement!!.excludes("sharedpref", VAULT_PREFS_FILE)
            )
            assertTrue(
                "<$section> must exclude the Firestore cache databases",
                sectionElement.excludes("database", ".")
            )
        }
    }

    @Test
    fun `tile trigger haptic falls back to VIRTUAL_KEY below API 30`() {
        // CONFIRM (API 30) is silently ignored on API 24-29, so tile activations had no haptic at all.
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, tileTriggerHapticFeedbackConstant(24))
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, tileTriggerHapticFeedbackConstant(29))
        assertEquals(HapticFeedbackConstants.CONFIRM, tileTriggerHapticFeedbackConstant(30))
        assertEquals(HapticFeedbackConstants.CONFIRM, tileTriggerHapticFeedbackConstant(36))
    }

    @Test
    fun `user-scoped ViewModels are cleared only on the signed-in to signed-out transition`() {
        assertTrue(shouldClearUserScopedViewModels(previousUid = "uid_a", newUid = null))
        // Startup while signed out, first sign-in, re-emission of the same user: nothing to clear.
        assertFalse(shouldClearUserScopedViewModels(previousUid = null, newUid = null))
        assertFalse(shouldClearUserScopedViewModels(previousUid = null, newUid = "uid_a"))
        assertFalse(shouldClearUserScopedViewModels(previousUid = "uid_a", newUid = "uid_a"))
        // A direct uid switch must not clear the store: the new user's ViewModel already lives in it.
        assertFalse(shouldClearUserScopedViewModels(previousUid = "uid_a", newUid = "uid_b"))
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        /** TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE */
        const val TTS_SERVICE_ACTION = "android.intent.action.TTS_SERVICE"

        /** SharedPreferences file written by LocalTelemetryStore ("oculagaze_local_vault"). */
        const val VAULT_PREFS_FILE = "oculagaze_local_vault.xml"

        /** Gradle runs unit tests from the module directory; also accept the repository root. */
        fun sourceFile(modulePath: String): File {
            val candidates = listOf(File(modulePath), File("app", modulePath))
            return candidates.firstOrNull { it.isFile }
                ?: throw AssertionError(
                    "Cannot find $modulePath from ${File(".").absolutePath} (tried $candidates)"
                )
        }

        fun parseXml(file: File) = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)

        fun Element.childElements(tag: String): List<Element> {
            val nodes = childNodes
            return (0 until nodes.length)
                .map { nodes.item(it) }
                .filterIsInstance<Element>()
                .filter { it.tagName == tag }
        }

        fun Element.excludes(domain: String, path: String): Boolean =
            childElements("exclude").any {
                it.getAttribute("domain") == domain && it.getAttribute("path") == path
            }
    }
}

/**
 * The always-visible "Delete Cloud" button permanently deletes every Firestore calibration and
 * session; it used to do so on a single tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActivityRegressionComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var deleteRequests = 0

    private fun showInlineBar() {
        composeRule.setContent {
            MaterialTheme {
                InlineCloudPrivacyBar(
                    cloudSyncEnabled = true,
                    isDeletingCloudData = false,
                    currentUserId = "uid_regression_123",
                    onToggleCloudSync = {},
                    onConfirmDeleteCloud = { deleteRequests++ }
                )
            }
        }
    }

    @Test
    fun `inline Delete Cloud deletes only after the confirmation dialog is accepted`() {
        showInlineBar()

        composeRule.onNodeWithTag("delete_firestore_data_button").performClick()
        composeRule.waitForIdle()
        assertEquals("A single tap must not delete cloud data", 0, deleteRequests)

        composeRule.onNodeWithTag("confirm_delete_cloud_button")
            .assertExists()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        assertEquals(1, deleteRequests)
        composeRule.onNodeWithTag("confirm_delete_cloud_button").assertDoesNotExist()
    }

    @Test
    fun `cancelling the inline Delete Cloud confirmation deletes nothing`() {
        showInlineBar()

        composeRule.onNodeWithTag("delete_firestore_data_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("cancel_delete_cloud_button")
            .assertExists()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assertEquals(0, deleteRequests)
        composeRule.onNodeWithTag("confirm_delete_cloud_button").assertDoesNotExist()
    }
}
