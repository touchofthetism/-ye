package com.example.regression

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.data.model.CalibrationProfile
import com.example.data.model.SectionPassageItem
import com.example.data.model.TrackingSession
import com.example.ui.screens.ATTENTION_OFF_PASSAGE_SECTION_ID
import com.example.ui.screens.AttentionAnalyticsScreen
import com.example.ui.screens.CloudPrivacyScreen
import com.example.ui.screens.PassageLayoutSnapshot
import com.example.ui.screens.formatEstimatedWpm
import com.example.ui.screens.formatSectionDwellLabel
import com.example.ui.screens.formatSensitivityLabel
import com.example.ui.screens.formatSessionSummaryLine
import com.example.ui.screens.formatZoneSummary
import com.example.ui.screens.indexOfActiveProfile
import com.example.ui.screens.passageLocalGazePoint
import com.example.ui.screens.resolveLayoutHitReport
import com.example.ui.screens.shouldShowThermalGuard
import com.example.ui.screens.sliderValueToDwellMs
import com.example.ui.viewmodel.AttentionSessionLiveState
import com.example.ui.viewmodel.LiveGazeHudState
import com.example.ui.viewmodel.UiDataState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pure helper regressions for AttentionAnalyticsScreen / CloudPrivacyScreen (models need Robolectric for Build.MODEL). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AnalyticsprivacyRegressionTest {

    // A171 / B22: "bounds attached, nothing hit" must not be reported as null (null = band fallback in the VM).
    @Test
    fun `layout hit report separates unattached bounds from off-passage gaze`() {
        val bounds = mapOf(
            "sec_1" to Rect(12f, 100f, 348f, 200f),
            "sec_2" to Rect(12f, 212f, 348f, 300f)
        )
        assertNull(resolveLayoutHitReport(Offset(100f, 150f), emptyMap()))
        assertEquals("sec_1", resolveLayoutHitReport(Offset(100f, 150f), bounds))
        assertEquals("sec_2", resolveLayoutHitReport(Offset(100f, 250f), bounds))
        // 12 dp gap between sections, and the header card above the passage
        assertEquals(ATTENTION_OFF_PASSAGE_SECTION_ID, resolveLayoutHitReport(Offset(100f, 205f), bounds))
        assertEquals(ATTENTION_OFF_PASSAGE_SECTION_ID, resolveLayoutHitReport(Offset(100f, 40f), bounds))
    }

    // A168 / B21: cluster samples must be normalized to the passage Box the heatmap Canvas draws in.
    @Test
    fun `passage local gaze point is normalized to the passage box including scroll`() {
        // Passage Box starts 250 px down the root and is 900 px tall; only 250..700 is on screen.
        val passage = PassageLayoutSnapshot(
            originInRoot = Offset(12f, 250f),
            size = Size(336f, 900f),
            visibleBoundsInRoot = Rect(12f, 250f, 348f, 700f)
        )
        val firstLine = passageLocalGazePoint(Offset(180f, 264f), passage)
        assertNotNull(firstLine)
        assertEquals(0.5f, firstLine!!.x, 1e-4f)
        assertEquals(14f / 900f, firstLine.y, 1e-4f)

        // Scrolled by 450 px: the Box origin moved above the viewport.
        val scrolled = PassageLayoutSnapshot(
            originInRoot = Offset(12f, -200f),
            size = Size(336f, 900f),
            visibleBoundsInRoot = Rect(12f, 120f, 348f, 700f)
        )
        val visible = passageLocalGazePoint(Offset(180f, 250f), scrolled)
        assertNotNull(visible)
        assertEquals(450f / 900f, visible!!.y, 1e-4f)
        // Over the HUD above the viewport: inside the Box's unclipped extent but not visible -> no sample.
        assertNull(passageLocalGazePoint(Offset(180f, 60f), scrolled))
        // Layout not measured yet.
        assertNull(passageLocalGazePoint(Offset(180f, 260f), passage.copy(size = Size(0f, 0f))))
    }

    // A187: the latched thermal flag must not keep showing the banner after recording ends / in replays.
    @Test
    fun `thermal guard banner only shows during a live recording`() {
        assertFalse(shouldShowThermalGuard(AttentionSessionLiveState(isRecording = false, thermalThrottleDetected = true)))
        assertTrue(shouldShowThermalGuard(AttentionSessionLiveState(isRecording = true, thermalThrottleDetected = true)))
        assertFalse(shouldShowThermalGuard(AttentionSessionLiveState(isRecording = true, thermalThrottleDetected = false)))
    }

    // A179 / A201 / B57 / B58: WPM is labelled as an estimate; a 0 WPM or 0/0/0 zones (no gaze data) is shown as n/a.
    @Test
    fun `session summary labels wpm as an estimate and shows n-a for sessions without data`() {
        assertEquals("est. WPM n/a", formatEstimatedWpm(0))
        assertTrue(formatEstimatedWpm(184).startsWith("184 est. WPM"))
        val line = formatSessionSummaryLine(
            TrackingSession(
                id = "s1",
                title = "t",
                deviceModel = "Test",
                durationSeconds = 30,
                blinksPerMinute = 12.5,
                effectiveWordsPerMinute = 0,
                topZonePercentage = 0,
                middleZonePercentage = 0,
                bottomZonePercentage = 0
            )
        )
        assertFalse(line, line.contains("eff. WPM"))
        assertTrue(line, line.contains("est. WPM n/a"))
        assertTrue(line, line.startsWith("30s • "))
        assertTrue(line, line.contains("12.5 blinks/min"))
        assertTrue(line, line.endsWith("Zones n/a"))
        assertFalse(line, line.contains("0%"))

        assertEquals("Zones T:50% M:30% B:20%", formatZoneSummary(50, 30, 20))
        assertEquals("Zones T:0% M:0% B:100%", formatZoneSummary(0, 0, 100))
    }

    // B57: section dwell shares that sum to 0 are "no data", not "0% dwell" on every section.
    @Test
    fun `section dwell label shows n-a when no section has dwell`() {
        assertEquals("dwell n/a", formatSectionDwellLabel(0, anySectionDwell = false))
        assertEquals("0% dwell", formatSectionDwellLabel(0, anySectionDwell = true))
        assertEquals("64% dwell", formatSectionDwellLabel(64, anySectionDwell = true))
    }

    // A181: M3 slider ticks come back as e.g. 1899.9999f; truncation turned the MOTOR_ASSIST 1900 ms tick into 1899.
    @Test
    fun `dwell slider ticks round to exact 100 ms values`() {
        assertEquals(1900L, sliderValueToDwellMs(1899.9999f))
        for (k in 0..18) {
            val t = k / 18f
            val tick = (1f - t) * 400f + t * 2200f
            assertEquals("tick $k ($tick)", 400L + 100L * k, sliderValueToDwellMs(tick))
        }
    }

    // A182: same rounding as DwellTriggerSettingsDialog (14 * 0.85 = 11.9 -> 12 dp, not 11 dp).
    @Test
    fun `sensitivity label rounds the dp margin`() {
        assertEquals("0.85× (12 dp)", formatSensitivityLabel(0.85f))
        assertEquals("1.55× (22 dp)", formatSensitivityLabel(1.55f))
    }

    // A175 / B34: same-named profiles must not all be marked as the loaded calibration.
    @Test
    fun `only the loaded profile among same-named profiles is marked current`() {
        val older = CalibrationProfile(id = "a", name = "Pixel Profile", deviceModel = "Pixel", meanErrorPct = 9.0, maxErrorPct = 12.0, meanErrorMm = 11.0)
        val loaded = CalibrationProfile(id = "b", name = "Pixel Profile", deviceModel = "Pixel", meanErrorPct = 6.5, maxErrorPct = 8.0, meanErrorMm = 7.9)
        val other = CalibrationProfile(id = "c", name = "Desk", deviceModel = "Pixel", meanErrorPct = 5.0, maxErrorPct = 6.0, meanErrorMm = 6.1)
        val profiles = listOf(older, loaded, other)

        assertEquals(1, indexOfActiveProfile(profiles, "Pixel Profile", "Pixel", 6.5f, 8.0f, 7.9))
        assertEquals(2, indexOfActiveProfile(profiles, "Desk", "Pixel", 5.0f, 6.0f, 6.1))
        assertEquals(-1, indexOfActiveProfile(profiles, "Uncalibrated (default matrix)", "Pixel", null, null, null))
    }
}

/** Compose behaviour regressions for the two screens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AnalyticsprivacyComposeRegressionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setPrivacyScreen(
        hudState: LiveGazeHudState = LiveGazeHudState(),
        profiles: List<CalibrationProfile> = emptyList(),
        sessions: List<TrackingSession> = emptyList(),
        isAttentionRecording: Boolean = false,
        onDeleteProfile: (String) -> Unit = {},
        onSelectSessionReplay: (TrackingSession) -> Unit = {},
        onDeleteFirestoreOnly: () -> Unit = {},
        onEraseAllTelemetry: () -> Unit = {},
        onSignOut: () -> Unit = {}
    ) {
        composeRule.setContent {
            CloudPrivacyScreen(
                hudState = hudState,
                currentUserId = "uid_regression",
                profilesState = UiDataState.Success(profiles),
                sessionsState = UiDataState.Success(sessions),
                onToggleCloudSync = {},
                onActivateProfile = {},
                onDeleteProfile = onDeleteProfile,
                onSelectSessionReplay = onSelectSessionReplay,
                onDeleteSession = {},
                onDeleteFirestoreOnly = onDeleteFirestoreOnly,
                onEraseAllTelemetry = onEraseAllTelemetry,
                onSignOut = onSignOut,
                isAttentionRecording = isAttentionRecording
            )
        }
    }

    // A176 / B28: wiping the vault needs an explicit confirmation; cancel deletes nothing.
    @Test
    fun `wipe cloud and local vault runs only after confirmation`() {
        var erased = 0
        setPrivacyScreen(onEraseAllTelemetry = { erased++ })

        composeRule.onNodeWithTag("erase_all_cloud_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(0, erased)
        composeRule.onNodeWithTag("destructive_action_confirm_dialog").assertExists()

        composeRule.onNodeWithTag("cancel_destructive_action_button").performClick()
        composeRule.waitForIdle()
        assertEquals(0, erased)
        composeRule.onNodeWithTag("destructive_action_confirm_dialog").assertDoesNotExist()

        composeRule.onNodeWithTag("erase_all_cloud_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("confirm_destructive_action_button").performClick()
        composeRule.waitForIdle()
        assertEquals(1, erased)
    }

    // A176: the Firestore-only delete is confirmed too.
    @Test
    fun `delete firestore data runs only after confirmation`() {
        var deleted = 0
        setPrivacyScreen(onDeleteFirestoreOnly = { deleted++ })

        composeRule.onNodeWithTag("delete_firestore_data_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(0, deleted)

        composeRule.onNodeWithTag("confirm_destructive_action_button").performClick()
        composeRule.waitForIdle()
        assertEquals(1, deleted)
    }

    // B28: per-row profile deletion is irreversible as well.
    @Test
    fun `deleting a profile row asks for confirmation first`() {
        val deletedIds = mutableListOf<String>()
        val profile = CalibrationProfile(id = "p1", name = "Desk", deviceModel = "Test")
        setPrivacyScreen(profiles = listOf(profile), onDeleteProfile = { deletedIds += it })

        composeRule.onNodeWithTag("delete_profile_p1").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(deletedIds.isEmpty())

        composeRule.onNodeWithTag("confirm_destructive_action_button").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("p1"), deletedIds)
    }

    // A177: signing out mid-deletion leaves a partial cloud wipe.
    @Test
    fun `sign out is disabled while cloud deletion is in flight`() {
        setPrivacyScreen(hudState = LiveGazeHudState(isDeletingCloudData = true))
        composeRule.onNodeWithTag("sign_out_button").performScrollTo().assertIsNotEnabled()
    }

    // A173: opening a replay while recording silently discarded the recording.
    @Test
    fun `session replay cannot be opened while a recording is running`() {
        var replays = 0
        val session = TrackingSession(id = "s1", title = "Morning read", deviceModel = "Test")
        setPrivacyScreen(sessions = listOf(session), isAttentionRecording = true, onSelectSessionReplay = { replays++ })

        composeRule.onNodeWithTag("session_row_s1").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(0, replays)
        composeRule.onNodeWithTag("replay_locked_while_recording_text").assertExists()
    }

    // B43: the cloud opt-in Switch was announced as an unlabeled "Switch".
    @Test
    fun `cloud sync switch carries the opt-in label`() {
        val title = ApplicationProvider.getApplicationContext<Context>().getString(R.string.cloud_telemetry_opt_in_title)
        setPrivacyScreen()
        composeRule.onNodeWithTag("cloud_sync_toggle").assert(hasContentDescription(title))
    }

    // A187: banner hidden in replay / after Stop & Save even though the flag stays latched in the VM.
    @Test
    fun `thermal guard banner is hidden when not recording`() {
        composeRule.setContent {
            AttentionAnalyticsScreen(
                attentionState = AttentionSessionLiveState(isRecording = false, thermalThrottleDetected = true),
                reticleRootPosition = { Offset.Zero },
                passageSections = listOf(SectionPassageItem("sec_1", "Alpha heading", "Body one", 2)),
                sectionSummaries = emptyList(),
                onStartRecording = {},
                onStopAndSaveRecording = {}
            )
        }
        composeRule.onNodeWithText("Thermal Guard", substring = true).assertDoesNotExist()
    }

    // A171 / A172 / A168 / A180: off-passage gaze reports the sentinel (not null), the passage-frame point is
    // reported, reticle moves are hit-tested without recomposition, and leaving the screen stops section crediting.
    @Test
    fun `attention screen reports section hits and passage points and resets on dispose`() {
        val sections = listOf(
            SectionPassageItem("sec_1", "Alpha heading", "Body one", 2),
            SectionPassageItem("sec_2", "Beta heading", "Body two", 2)
        )
        var reticle by mutableStateOf(Offset.Zero)
        var show by mutableStateOf(true)
        val hits = mutableListOf<String?>()
        val passagePoints = mutableListOf<Offset?>()
        composeRule.setContent {
            if (show) {
                AttentionAnalyticsScreen(
                    attentionState = AttentionSessionLiveState(isRecording = true),
                    reticleRootPosition = { reticle },
                    passageSections = sections,
                    sectionSummaries = emptyList(),
                    onUpdateLayoutHitSection = { hits += it },
                    onUpdatePassageGazePoint = { passagePoints += it },
                    onStartRecording = {},
                    onStopAndSaveRecording = {}
                )
            }
        }
        composeRule.waitForIdle()

        val headingCenter = composeRule.onNodeWithText("Alpha heading").fetchSemanticsNode().boundsInRoot.center
        composeRule.runOnIdle { reticle = headingCenter }
        composeRule.waitForIdle()
        assertEquals("sec_1", hits.last())
        val local = passagePoints.last()
        assertNotNull(local)
        assertTrue("passage-local y $local", local!!.y in 0f..0.5f)
        assertTrue("passage-local x $local", local.x in 0f..1f)

        // Top-left corner: outside every section and the passage (screen padding / header card).
        composeRule.runOnIdle { reticle = Offset(1f, 1f) }
        composeRule.waitForIdle()
        assertEquals(ATTENTION_OFF_PASSAGE_SECTION_ID, hits.last())
        assertNull(passagePoints.last())

        composeRule.runOnIdle { reticle = headingCenter }
        composeRule.waitForIdle()
        assertEquals("sec_1", hits.last())

        // Leaving the tab while the recording keeps running must not keep crediting sec_1.
        composeRule.runOnIdle { show = false }
        composeRule.waitForIdle()
        assertEquals(ATTENTION_OFF_PASSAGE_SECTION_ID, hits.last())
        assertNull(passagePoints.last())
    }
}
