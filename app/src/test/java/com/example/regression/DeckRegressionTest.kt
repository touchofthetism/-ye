package com.example.regression

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.example.data.model.AacActionTile
import com.example.data.model.DwellSpeedPreset
import com.example.domain.BenchmarkRunner
import com.example.domain.GazeCalibrationMath
import com.example.domain.ReplayScenario
import com.example.ui.components.DwellTriggerSettingsDialog
import com.example.ui.screens.GazeControlDeckScreen
import com.example.ui.screens.tileCentersInGazeFrame
import com.example.ui.viewmodel.LiveGazeHudState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Regressions for the Control Deck and the dwell settings dialog:
 * - the hovered tile is cleared when the deck leaves composition and while a dialog covers it,
 * - bounds of tiles that left the grid are not hit-testable,
 * - focus hysteresis is 14 dp on the device's density,
 * - the 3x3 "Validated" deck needs measured accuracy and uses the shared layout classifier,
 * - dwell sliders represent every preset and round instead of truncating,
 * - the benchmark summary reports the run's own verdict and counts, Locale.US formatted,
 * - visible tile centres are reported in the gaze (root overlay) frame for drift correction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class DeckRegressionTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Runs [block] with Robolectric reporting new windows as app-visible, so their ViewRootImpl draws.
     * A Compose text field invalidates its dialog window after the first layout and finishes measuring
     * in that draw pass; Robolectric's default (windows not visible, so never drawn) leaves the edit
     * dialog permanently "not idle". Only the icon-free edit dialog runs drawn: the legacy graphics
     * shadow cannot create the bitmaps that vector icons (e.g. in the settings dialog) draw into.
     */
    private fun <T> withWindowsMarkedVisible(block: () -> T): T {
        val previous = System.setProperty(WINDOWS_MARKED_VISIBLE, "true")
        try {
            return block()
        } finally {
            if (previous != null) System.setProperty(WINDOWS_MARKED_VISIBLE, previous) else System.clearProperty(WINDOWS_MARKED_VISIBLE)
        }
    }

    private val tiles = (1..9).map { i ->
        AacActionTile(
            id = "t$i",
            title = "Tile t$i",
            subtitle = "Sub $i",
            speechPhrase = "Phrase number $i",
            category = "BASIC"
        )
    }

    /** Stands in for the ViewModel: focus updates are recorded and fed back into the HUD. */
    private class DeckHarness(initialHud: LiveGazeHudState = LiveGazeHudState()) {
        var hud by mutableStateOf(initialHud)
        var reticle by mutableStateOf(Offset.Zero)
        var show by mutableStateOf(true)
        var gazeFrame by mutableStateOf(Rect.Zero)
        val centerCalls = mutableListOf<Map<String, Pair<Float, Float>>>()
        val focusCalls = mutableListOf<String?>()
        val durations = mutableListOf<Long>()
        val edits = mutableListOf<Triple<String, String, String>>()
    }

    @Composable
    private fun DeckUnderTest(h: DeckHarness) {
        if (h.show) {
            GazeControlDeckScreen(
                hudState = h.hud,
                reticleRootPosition = h.reticle,
                tiles = tiles,
                onUpdateFocusedTile = { id ->
                    h.focusCalls += id
                    h.hud = h.hud.copy(focusedTileId = id)
                },
                onManualTileClick = {},
                onChangeDwellDurationMs = { h.durations += it },
                onEditTilePhrase = { id, title, phrase -> h.edits += Triple(id, title, phrase) },
                gazeFrameRootBounds = h.gazeFrame,
                onUpdateTileCentersNorm = { h.centerCalls += it }
            )
        }
    }

    private fun setDeck(h: DeckHarness, density: Density? = null) {
        composeRule.setContent {
            if (density != null) {
                CompositionLocalProvider(LocalDensity provides density) { DeckUnderTest(h) }
            } else {
                DeckUnderTest(h)
            }
        }
        composeRule.waitForIdle()
    }

    private fun tileBounds(id: String) =
        composeRule.onNodeWithTag("aac_tile_$id").fetchSemanticsNode().boundsInRoot

    private fun moveReticle(h: DeckHarness, to: Offset) {
        composeRule.runOnIdle { h.reticle = to }
        composeRule.waitForIdle()
    }

    // A151 / B16: leaving the deck must clear the hovered tile, or dwell / blink / brow fire it on other tabs.
    @Test
    fun leavingTheDeck_clearsTheHoveredTile() {
        val h = DeckHarness()
        setDeck(h)
        moveReticle(h, tileBounds("t1").center)
        assertEquals("t1", h.hud.focusedTileId)

        composeRule.runOnIdle { h.show = false }
        composeRule.waitForIdle()

        assertNull("deck left composition with a tile still hovered", h.focusCalls.last())
        assertNull(h.hud.focusedTileId)
    }

    // A154 / B18: tiles under the settings dialog must not stay focused (and dwell-fire) while it is open.
    @Test
    fun settingsDialog_suspendsGazeFocus_andHitTestingResumesOnClose() {
        val h = DeckHarness()
        setDeck(h)
        val t1 = tileBounds("t1").center
        val t2 = tileBounds("t2").center
        moveReticle(h, t1)
        assertEquals("t1", h.hud.focusedTileId)

        composeRule.onNodeWithTag("open_dwell_settings_button").performClick()
        composeRule.waitForIdle()
        assertNull("opening the dialog must clear focus", h.hud.focusedTileId)

        // Gaze rests on dialog content that overlays another tile: nothing may be focused.
        moveReticle(h, t2)
        assertNull(h.hud.focusedTileId)
        assertFalse(h.focusCalls.contains("t2"))

        composeRule.onNodeWithTag("dismiss_dwell_settings_button").performClick()
        composeRule.waitForIdle()
        assertEquals("hit testing resumes from the current reticle", "t2", h.hud.focusedTileId)
    }

    // A154 / B18 (edit dialog) + A165: editing suspends gaze focus, and a blank field cannot be "saved".
    @Test
    fun editDialog_suspendsGazeFocus_andSaveNeedsLabelAndPhrase() = withWindowsMarkedVisible {
        val h = DeckHarness()
        setDeck(h)
        moveReticle(h, tileBounds("t1").center)
        assertEquals("t1", h.hud.focusedTileId)

        composeRule.onNodeWithTag("edit_aac_tile_t1").performClick()
        composeRule.waitForIdle()
        assertNull("typing in the edit dialog must not dwell on the tile under it", h.hud.focusedTileId)

        composeRule.onNodeWithTag("edit_tile_phrase_field").performTextClearance()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitForIdle()
        assertTrue("a blank phrase must not be submitted (the VM would silently drop it)", h.edits.isEmpty())

        composeRule.onNodeWithTag("edit_tile_phrase_field").performTextInput("Hello there")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Save").assertIsEnabled()
        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(Triple("t1", "Tile t1", "Hello there")), h.edits)
    }

    // B29 / A153 / A256 / A310: after 3x3 -> 2x2, the removed tiles' old rects must not be hit-testable.
    @Test
    fun tilesThatLeftTheGrid_areNotHitTestable() {
        val h = DeckHarness(LiveGazeHudState(activeErrorRadiusPct = 5f, activeMaxQuadrantErrorPct = 8f))
        setDeck(h)
        val oldT9 = tileBounds("t9")
        // Near the bottom of the old bottom-right tile (row 3 of the 3x3 grid).
        val inOldT9 = Offset(oldT9.center.x, oldT9.bottom - 4f)

        composeRule.runOnIdle { h.hud = h.hud.copy(activeErrorRadiusPct = 14f) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("aac_tile_t9").assertDoesNotExist()
        // Precondition: that point is below every visible 2x2 tile, i.e. only the stale rect contains it.
        assertTrue(inOldT9.y > tileBounds("t3").bottom && inOldT9.y > tileBounds("t4").bottom)

        moveReticle(h, inOldT9)
        assertFalse("invisible tile t9 was focused", h.focusCalls.contains("t9"))
        assertNull(h.hud.focusedTileId)
    }

    // A163 / B31: the boundary margin is 14 dp on this device's density, not 14 * 2.75 px.
    @Test
    fun focusHysteresis_isFourteenDpOnTheDeviceDensity() {
        val h = DeckHarness()
        setDeck(h, density = Density(density = 1f, fontScale = 1f))
        val t1 = tileBounds("t1")
        val t2 = tileBounds("t2")
        moveReticle(h, t1.center)
        assertEquals("t1", h.hud.focusedTileId)

        // Inside t2, 18-30 px (= dp at density 1) right of t1's edge: beyond 14 dp, within the old 38.5 px.
        moveReticle(h, Offset(t2.left + 8f, t1.center.y))
        assertEquals("t2", h.hud.focusedTileId)
    }

    // A155 / B27 / A156 / C3: unmeasured accuracy gives the protective 2x2 deck; layout label == summary label.
    @Test
    fun deckLayout_needsMeasuredAccuracy_andMatchesTheSharedClassifier() {
        val h = DeckHarness(LiveGazeHudState())
        setDeck(h)
        composeRule.onNodeWithTag("aac_tile_t4").assertExists()
        composeRule.onNodeWithTag("aac_tile_t5").assertDoesNotExist()
        composeRule.onAllNodesWithText("Validated Deck", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText(GazeCalibrationMath.gridLayoutLabel(false), substring = true).assertExists()

        // Mean 11 %, worst quadrant 16 %: the calibration summary recommends exactly this layout.
        composeRule.runOnIdle {
            h.hud = h.hud.copy(activeErrorRadiusPct = 11.0f, activeMaxQuadrantErrorPct = 16.0f)
        }
        composeRule.waitForIdle()
        val expected = GazeCalibrationMath.gridLayoutLabel(GazeCalibrationMath.qualifiesFor3x3Grid(11.0, 16.0))
        composeRule.onNodeWithText(expected).assertExists()
        composeRule.onNodeWithTag("aac_tile_t9").assertExists()

        composeRule.runOnIdle {
            h.hud = h.hud.copy(activeErrorRadiusPct = 13.0f, activeMaxQuadrantErrorPct = 14.0f)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(GazeCalibrationMath.gridLayoutLabel(false)).assertExists()
        composeRule.onNodeWithTag("aac_tile_t5").assertDoesNotExist()
    }

    // A164: a long triggered phrase must not reflow the card and move the tile grid under a fixed gaze.
    @Test
    fun longTriggeredSpeech_doesNotMoveTheTileGrid() {
        val h = DeckHarness(LiveGazeHudState(lastTriggeredSpeech = "Hi"))
        setDeck(h)
        val before = tileBounds("t1")

        composeRule.runOnIdle {
            h.hud = h.hud.copy(lastTriggeredSpeech = "[Tile t1 • Dwell (1000ms)] \"" + "very long phrase ".repeat(30) + "\"")
        }
        composeRule.waitForIdle()
        assertEquals(before, tileBounds("t1"))
    }

    // B32 / A162 / A161: the deck slider shows and sets 650 / 1900 ms presets, rounding the float value.
    @Test
    fun deckDwellSlider_representsEveryPreset() {
        val h = DeckHarness(LiveGazeHudState(dwellTriggerMs = 650L, activeDwellPreset = DwellSpeedPreset.FAST_READER))
        setDeck(h)
        val slider = composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
        val info = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(650f, info.current, 0.5f)
        assertEquals(400f, info.range.start, 0.01f)
        assertEquals(2200f, info.range.endInclusive, 0.01f)

        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(1900f) }
        composeRule.waitForIdle()
        assertEquals(1900L, h.durations.last())
    }

    // A161: the stepped dialog slider's 1900 tick comes back as 1899.9999f and must not become 1899 ms;
    // a preset is only shown selected while its tolerance is also in effect.
    @Test
    fun dialogDurationSlider_roundsTicks_andPresetSelectionChecksTolerance() {
        val durations = mutableListOf<Long>()
        var hud by mutableStateOf(
            LiveGazeHudState(
                dwellTriggerMs = 1000L,
                activeDwellPreset = DwellSpeedPreset.BALANCED,
                dwellSensitivityRadiusMultiplier = 1.55f
            )
        )
        composeRule.setContent {
            DwellTriggerSettingsDialog(
                hudState = hud,
                onDismissRequest = {},
                onChangeDurationMs = { durations += it },
                onChangeSensitivityMultiplier = {},
                onSelectPreset = {}
            )
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithContentDescription("Selected").assertCountEquals(0)

        composeRule.runOnIdle { hud = hud.copy(dwellSensitivityRadiusMultiplier = 1.0f) }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithContentDescription("Selected").assertCountEquals(1)

        composeRule.onNodeWithTag("dwell_duration_slider")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1900f) }
        composeRule.waitForIdle()
        assertEquals(1900L, durations.last())
    }

    // A160 / B26 / A306 / A35 / C7 / C8: the summary shows the run's verdict and false-trigger count, the
    // real sub-millisecond latency, no device/thermal claim, and Locale.US numbers.
    @Test
    fun benchmarkSummary_reportsTheRunsOwnResults() {
        val base = BenchmarkRunner.runCompleteBenchmark()
        val failing = base.copy(
            allScenariosPassed = false,
            falseActivationsPerMinute = 2.0,
            latencyMedianMs = 0.0042,
            midasFalseActivationCount = 3,
            scenarioResults = base.scenarioResults.map { r ->
                if (r.scenario == ReplayScenario.CONTINUOUS_TALKING) r.copy(passed = false, falseActivations = 3)
                else r.copy(falseActivations = 0)
            }
        )
        val gated = failing.scenarioResults.filter { it.scenario.category == "MIDAS_TOUCH" || it.scenario.category == "COMMAND" }
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            composeRule.setContent {
                DwellTriggerSettingsDialog(
                    hudState = LiveGazeHudState(lastBenchmarkReport = failing),
                    onDismissRequest = {},
                    onChangeDurationMs = {},
                    onChangeSensitivityMultiplier = {},
                    onSelectPreset = {}
                )
            }
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText("0 false blinks", substring = true).assertCountEquals(0)
            composeRule.onAllNodesWithText("Pixel 10 Pro XL Stress Benchmark", substring = true).assertCountEquals(0)
            composeRule.onAllNodesWithText("thermal endurance", substring = true).assertCountEquals(0)
            composeRule.onNodeWithText("FAILURES DETECTED", substring = true).assertExists()
            composeRule.onNodeWithText("3 false triggers (3 in Midas-touch scenarios, 2.0/min)", substring = true)
                .assertExists()
            composeRule.onNodeWithText("${gated.count { it.passed }}/${gated.size} gated scenarios passed", substring = true)
                .assertExists()
            composeRule.onNodeWithText("post-feature JVM processing median 0.004 ms", substring = true).assertExists()
            // The forward-model accuracy fields are a synthetic projection check, not measured accuracy.
            composeRule.onNodeWithText("synthetic projection check", substring = true).assertExists()
            composeRule.onNodeWithText("not measured gaze accuracy", substring = true).assertExists()
            composeRule.onAllNodesWithText("synthetic-trace accuracy", substring = true).assertCountEquals(0)
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    // A188 / B56: drift correction needs tile centres in the gaze frame (the root overlay Box), not root px.
    @Test
    fun tileCentersInGazeFrame_normalizesToTheOverlayFrame() {
        val frame = Rect(left = 0f, top = 100f, right = 1000f, bottom = 2100f)
        val bounds = mapOf(
            "a" to Rect(100f, 300f, 300f, 500f),
            "gone" to Rect(500f, 500f, 700f, 700f),
            "clipped" to Rect(800f, 2100f, 900f, 2100f)
        )
        val centers = tileCentersInGazeFrame(bounds, setOf("a", "clipped"), frame)
        assertEquals(setOf("a"), centers.keys)
        assertEquals(0.2f, centers.getValue("a").first, 1e-6f)
        assertEquals(0.15f, centers.getValue("a").second, 1e-6f)
        assertTrue("no frame yet: nothing to normalize against", tileCentersInGazeFrame(bounds, setOf("a"), Rect.Zero).isEmpty())
    }

    @Test
    fun deck_reportsVisibleTileCentresInTheGazeFrame_andClearsThemOnLeave() {
        val h = DeckHarness()
        setDeck(h)
        assertTrue("without a frame no centre may be reported", h.centerCalls.all { it.isEmpty() })

        val frame = Rect(left = 0f, top = 50f, right = 1130f, bottom = 2450f)
        composeRule.runOnIdle { h.gazeFrame = frame }
        composeRule.waitForIdle()
        var latest = h.centerCalls.last()
        assertEquals(setOf("t1", "t2", "t3", "t4"), latest.keys)
        val t2 = tileBounds("t2").center
        assertEquals((t2.x - frame.left) / frame.width, latest.getValue("t2").first, 1e-3f)
        assertEquals((t2.y - frame.top) / frame.height, latest.getValue("t2").second, 1e-3f)

        // 2x2 -> 3x3: the newly visible tiles are reported too.
        composeRule.runOnIdle { h.hud = h.hud.copy(activeErrorRadiusPct = 5f, activeMaxQuadrantErrorPct = 8f) }
        composeRule.waitForIdle()
        latest = h.centerCalls.last()
        assertEquals((1..9).map { "t$it" }.toSet(), latest.keys)

        composeRule.runOnIdle { h.show = false }
        composeRule.waitForIdle()
        assertTrue("stale centres must be cleared when the deck leaves", h.centerCalls.last().isEmpty())
    }

    // A167 / C7: numeric readouts on the deck use Locale.US regardless of the device locale.
    @Test
    fun browRatioReadout_usesLocaleUs() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            setDeck(DeckHarness(LiveGazeHudState()))
            composeRule.onNodeWithText("Brow Ratio: 0.340 (N:0.34→R:0.42)").assertExists()
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    private companion object {
        const val WINDOWS_MARKED_VISIBLE = "robolectric.areWindowsMarkedVisible"
    }
}
