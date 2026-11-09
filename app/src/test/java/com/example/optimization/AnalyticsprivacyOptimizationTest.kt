package com.example.optimization

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.data.model.CalibrationProfile
import com.example.data.model.DwellSpeedPreset
import com.example.data.model.FixationClusterPoint
import com.example.data.model.SectionDwellSummary
import com.example.data.model.SectionPassageItem
import com.example.data.model.TrackingSession
import com.example.ui.screens.AttentionAnalyticsScreen
import com.example.ui.screens.CloudPrivacyScreen
import com.example.ui.screens.ClusterLabelLayouts
import com.example.ui.screens.buildFixationHeatmapFrame
import com.example.ui.screens.formatSensitivityLabel
import com.example.ui.screens.formatSessionSummaryLine
import com.example.ui.screens.formatZoneSummary
import com.example.ui.viewmodel.AttentionSessionLiveState
import com.example.ui.viewmodel.LiveGazeHudState
import com.example.ui.viewmodel.UiDataState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

// ---------------------------------------------------------------------------------------------------------------
// Frozen pre-optimization references (copied verbatim from AttentionAnalyticsScreen.kt / CloudPrivacyScreen.kt).
// ---------------------------------------------------------------------------------------------------------------

private fun referenceFormatEstimatedWpm(wordsPerMinute: Int): String =
    if (wordsPerMinute > 0) "$wordsPerMinute est. WPM (dwelled sections)" else "est. WPM n/a"

private fun referenceFormatZoneSummary(topPct: Int, middlePct: Int, bottomPct: Int): String =
    if (topPct + middlePct + bottomPct <= 0) {
        "Zones n/a"
    } else {
        String.format(Locale.US, "Zones T:%d%% M:%d%% B:%d%%", topPct, middlePct, bottomPct)
    }

private fun referenceFormatSessionSummaryLine(session: TrackingSession): String = String.format(
    Locale.US,
    "%ds • %s • %.1f blinks/min • %s",
    session.durationSeconds,
    referenceFormatEstimatedWpm(session.effectiveWordsPerMinute),
    session.blinksPerMinute,
    referenceFormatZoneSummary(session.topZonePercentage, session.middleZonePercentage, session.bottomZonePercentage)
)

private fun referenceFormatProfileErrorLine(profile: CalibrationProfile): String = String.format(
    Locale.US,
    "%s • Calib. error (%s): mean ±%.1f%% (approx. ~%.1fmm) • worst ±%.1f%%",
    profile.deviceModel,
    if (profile.heldOutValidated) "held-out validation" else "in-sample fit (unvalidated)",
    profile.meanErrorPct,
    profile.meanErrorMm,
    profile.maxErrorPct
)

private fun referenceFormatSensitivityLabel(multiplier: Float): String =
    String.format(Locale.US, "%.2f× (%d dp)", multiplier, (14f * multiplier).roundToInt())

/** Text, or the exception text for inputs (NaN) where both versions throw from roundToInt. */
private inline fun outcome(block: () -> String): String =
    try {
        block()
    } catch (e: IllegalArgumentException) {
        "threw ${e::class.java.name}: ${e.message}"
    }

/** The replay subtitle exactly as the header Text built it inline. */
private fun referenceReplaySubtitle(replay: TrackingSession): String =
    "${replay.durationSeconds}s • ${referenceFormatEstimatedWpm(replay.effectiveWordsPerMinute)} • ${String.format(Locale.US, "%.1f", replay.blinksPerMinute)} blinks/min • ${replay.fixationClusterCount} clusters"

/** One splat as the old Canvas lambda computed it on every draw. */
private class ReferenceSplat(val cx: Float, val cy: Float, val radius: Float, val brush: Brush)

private fun referenceSplats(clusters: List<FixationClusterPoint>, size: Size, density: Density): List<ReferenceSplat> =
    with(density) {
        clusters.map { cluster ->
            val cx = cluster.normX * size.width
            val cy = cluster.normY * size.height
            val weightNorm = (cluster.dwellMs.toFloat() / 2200f).coerceIn(0.25f, 1.0f)
            val radius = (42.dp.toPx() + weightNorm * 46.dp.toPx())
            ReferenceSplat(
                cx = cx,
                cy = cy,
                radius = radius,
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFEF4444).copy(alpha = 0.46f * weightNorm),
                        Color(0xFFF59E0B).copy(alpha = 0.28f * weightNorm),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = radius
                )
            )
        }
    }

/** The label style the old Canvas lambda allocated per label per draw. */
private val referenceLabelStyle = TextStyle(
    color = Color(0xFF0B0F17),
    fontSize = 10.sp,
    fontWeight = FontWeight.Bold
)

private fun bits(value: Float): Int = java.lang.Float.floatToIntBits(value)

private fun randomCluster(random: Random): FixationClusterPoint {
    val dwellMs = when (random.nextInt(10)) {
        0 -> 0L
        1 -> listOf(1L, 549L, 550L, 551L, 2199L, 2200L, 2201L).random(random)
        2 -> Long.MAX_VALUE
        3 -> -random.nextLong(0L, 5_000L)
        else -> random.nextLong(0L, 12_000L)
    }
    val normX = if (random.nextInt(8) == 0) listOf(0f, 1f, -0f, 0.5f, 1e-7f).random(random) else random.nextFloat() * 2f - 0.5f
    val normY = if (random.nextInt(8) == 0) listOf(0f, 1f, -0f, 0.5f, 1e-7f).random(random) else random.nextFloat() * 2f - 0.5f
    return FixationClusterPoint(normX, normY, dwellMs)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AnalyticsprivacyOptimizationTest {

    @Test
    fun `zone summary template matches the Locale_US String_format reference`() {
        val edges = listOf(0, 1, -1, 33, 34, 100, -100, 999_999, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE - 1)
        for (t in edges) for (m in edges) for (b in edges) {
            assertEquals("($t,$m,$b)", referenceFormatZoneSummary(t, m, b), formatZoneSummary(t, m, b))
        }
        val random = Random(20261106)
        repeat(50_000) {
            val t = if (it % 3 == 0) random.nextInt() else random.nextInt(-50, 150)
            val m = if (it % 5 == 0) random.nextInt() else random.nextInt(-50, 150)
            val b = if (it % 7 == 0) random.nextInt() else random.nextInt(-50, 150)
            assertEquals("($t,$m,$b)", referenceFormatZoneSummary(t, m, b), formatZoneSummary(t, m, b))
        }
    }

    @Test
    fun `session summary line matches the single String_format reference`() {
        val doubleEdges = listOf(
            0.0, -0.0, 0.05, 0.15, 0.25, 0.35, 0.45, 12.25, 12.35, 12.5, 99.95, 1e-300, -1e-300, 1e300,
            Double.MIN_VALUE, Double.MAX_VALUE, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
        )
        val intEdges = listOf(0, 1, -1, 30, 184, Int.MAX_VALUE, Int.MIN_VALUE)
        val random = Random(42)
        repeat(20_000) { i ->
            val session = TrackingSession(
                id = "s$i",
                title = "t",
                deviceModel = "Test",
                durationSeconds = if (i % 4 == 0) intEdges.random(random) else random.nextInt(-10, 10_000),
                blinksPerMinute = when (i % 5) {
                    0 -> doubleEdges.random(random)
                    1 -> Double.fromBits(random.nextLong())
                    else -> random.nextDouble(-5.0, 120.0)
                },
                effectiveWordsPerMinute = if (i % 3 == 0) intEdges.random(random) else random.nextInt(-5, 900),
                topZonePercentage = if (i % 6 == 0) intEdges.random(random) else random.nextInt(-5, 101),
                middleZonePercentage = if (i % 7 == 0) intEdges.random(random) else random.nextInt(-5, 101),
                bottomZonePercentage = if (i % 9 == 0) intEdges.random(random) else random.nextInt(-5, 101)
            )
            assertEquals(session.toString(), referenceFormatSessionSummaryLine(session), formatSessionSummaryLine(session))
        }
    }

    @Test
    fun `sensitivity label template matches the single String_format reference`() {
        val edges = floatArrayOf(
            0f, -0f, 0.6f, 0.65f, 0.85f, 1f, 1.005f, 1.015f, 1.025f, 1.25f, 1.55f, 2f, 0.035714287f, 0.10714286f,
            -1.55f, -0.035714287f, 1e-8f, 153391.69f, Float.MIN_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE,
            Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN
        )
        for (m in edges) {
            assertEquals("$m", outcome { referenceFormatSensitivityLabel(m) }, outcome { formatSensitivityLabel(m) })
        }
        // Every position of the 0.6..2.0 slider with 13 steps, computed the way the slider snaps.
        for (step in 0..14) {
            val m = 0.6f + step * (1.4f / 14f)
            assertEquals("step $step", referenceFormatSensitivityLabel(m), formatSensitivityLabel(m))
        }
        val random = Random(1955)
        repeat(100_000) { i ->
            val m = when (i % 4) {
                0 -> random.nextFloat() * 1.4f + 0.6f
                1 -> Float.fromBits(random.nextInt())
                2 -> (random.nextFloat() - 0.5f) * 400f
                else -> random.nextInt(-1000, 1000) / 28f // multiplier * 14 lands on x.5 dp ties
            }
            assertEquals("$m", outcome { referenceFormatSensitivityLabel(m) }, outcome { formatSensitivityLabel(m) })
        }
    }

    @Test
    fun `cached heatmap frame matches the per-draw geometry and brushes bit for bit`() {
        val random = Random(7)
        val densities = listOf(1f, 1.5f, 2f, 2.625f, 2.75f, 3f, 3.5f, 4f)
        val sizes = listOf(Size(0f, 0f), Size(1080f, 1920f), Size(393.5f, 1200.25f), Size(1f, 1f), Size(2400f, 7000f))
        val counts = listOf(0, 1, 2, 3, 7, 8, 9, 40, 41, 64, 65, 100)
        repeat(600) { iteration ->
            val count = if (iteration < counts.size) counts[iteration] else random.nextInt(0, 121)
            val clusters = List(count) { randomCluster(random) }
            val size = if (iteration % 3 == 0) {
                sizes.random(random)
            } else {
                Size(random.nextFloat() * 4000f, random.nextFloat() * 9000f)
            }
            val density = Density(if (iteration % 2 == 0) densities.random(random) else 0.75f + random.nextFloat() * 3.5f)

            val frame = buildFixationHeatmapFrame(clusters, size, density)
            val expected = referenceSplats(clusters, size, density)

            assertEquals(count, frame.centerX.size)
            assertEquals(count, frame.centerY.size)
            assertEquals(count, frame.splatRadius.size)
            assertEquals(count, frame.splatBrush.size)
            expected.forEachIndexed { i, ref ->
                val where = "iteration $iteration cluster $i ${clusters[i]} size $size density ${density.density}"
                assertEquals(where, bits(ref.cx), bits(frame.centerX[i]))
                assertEquals(where, bits(ref.cy), bits(frame.centerY[i]))
                assertEquals(where, bits(ref.radius), bits(frame.splatRadius[i]))
                assertEquals(where, ref.brush, frame.splatBrush[i])
                // Trajectory endpoints and node centers were Offset(clusters[i].normX * size.width, ...) again.
                assertEquals(where, Offset(ref.cx, ref.cy), Offset(frame.centerX[i], frame.centerY[i]))
            }
            with(density) {
                assertEquals(bits(2.dp.toPx()), bits(frame.trajectoryStrokeWidthPx))
                assertEquals(bits(11.dp.toPx()), bits(frame.nodeOutlineRadiusPx))
                assertEquals(bits(10.dp.toPx()), bits(frame.nodeFillRadiusPx))
            }
        }
    }

    @Test
    fun `cluster label cache returns the layouts TextMeasurer measures for each index`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (density in listOf(1f, 2.75f)) {
            val measurer = TextMeasurer(
                defaultFontFamilyResolver = createFontFamilyResolver(context),
                defaultDensity = Density(density),
                defaultLayoutDirection = LayoutDirection.Ltr
            )
            val labels = ClusterLabelLayouts(measurer)
            // Two passes in draw order (1..N), past the 64-entry cache bound and past TextMeasurer's own 8-entry LRU.
            repeat(2) { pass ->
                for (index in 0 until 72) {
                    val expected = measurer.measure(text = "${index + 1}", style = referenceLabelStyle, skipCache = true)
                    val actual = labels.layoutFor(index)
                    val where = "density $density pass $pass label ${index + 1}"
                    assertEquals(where, expected.layoutInput, actual.layoutInput)
                    assertEquals(where, expected.size, actual.size)
                    assertEquals(where, expected.firstBaseline, actual.firstBaseline, 0f)
                    assertEquals(where, expected.lastBaseline, actual.lastBaseline, 0f)
                    assertEquals(where, expected.multiParagraph.width, actual.multiParagraph.width, 0f)
                    assertEquals(where, expected.multiParagraph.height, actual.multiParagraph.height, 0f)
                }
            }
            // Labels within the bound are laid out once and then reused by every draw.
            for (index in 0 until 64) {
                assertSame("label ${index + 1}", labels.layoutFor(index), labels.layoutFor(index))
            }
        }
    }
}

/** Compose-level equivalence: the split into skippable children shows the same text and wiring as before. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AnalyticsprivacyComposeOptimizationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sections = listOf(
        SectionPassageItem("sec_1", "Alpha heading", "Body one", 2),
        SectionPassageItem("sec_2", "Beta heading", "Body two", 2)
    )

    private fun summaries(first: Int, second: Int) = listOf(
        SectionDwellSummary("sec_1", "Alpha heading", first * 10L, first),
        SectionDwellSummary("sec_2", "Beta heading", second * 10L, second)
    )

    @Test
    fun `attention header and section labels follow per-frame state like the inline layout did`() {
        var state by mutableStateOf(
            AttentionSessionLiveState(
                isRecording = true,
                elapsedSeconds = 12,
                sessionNaturalBlinks = 3,
                sessionIntentionalBlinks = 1,
                activeSectionId = "sec_1"
            )
        )
        var sectionSummaries by mutableStateOf(summaries(64, 36))
        composeRule.setContent {
            AttentionAnalyticsScreen(
                attentionState = state,
                reticleRootPosition = { Offset.Zero },
                passageSections = sections,
                sectionSummaries = sectionSummaries,
                onStartRecording = {},
                onStopAndSaveRecording = {}
            )
        }
        composeRule.onNodeWithText("Recording (12s) • Nat Blinks: 3 • Cmd Blinks: 1").assertExists()
        composeRule.onNodeWithText("Section Dwell & Fixation Centroid Lab").assertExists()
        composeRule.onNodeWithText("64% dwell").assertExists()
        composeRule.onNodeWithText("36% dwell").assertExists()
        composeRule.onNodeWithTag("stop_attention_session_button").assertExists()
        composeRule.onNodeWithTag("start_attention_session_button").assertDoesNotExist()

        // Recorded frames: new state and summary instances whose shown values do not change.
        repeat(30) { frame ->
            composeRule.runOnIdle {
                state = state.copy(
                    topZoneMs = state.topZoneMs + 33L,
                    sectionDwellMsMap = mapOf("sec_1" to 640L + frame, "sec_2" to 360L)
                )
                sectionSummaries = sectionSummaries.map { it.copy() }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("64% dwell").assertExists()
        composeRule.onNodeWithText("Recording (12s) • Nat Blinks: 3 • Cmd Blinks: 1").assertExists()

        composeRule.runOnIdle {
            sectionSummaries = summaries(70, 30)
            state = state.copy(elapsedSeconds = 13, sessionNaturalBlinks = 4, activeSectionId = "sec_2")
        }
        composeRule.onNodeWithText("70% dwell").assertExists()
        composeRule.onNodeWithText("30% dwell").assertExists()
        composeRule.onNodeWithText("64% dwell").assertDoesNotExist()
        composeRule.onNodeWithText("Recording (13s) • Nat Blinks: 4 • Cmd Blinks: 1").assertExists()

        composeRule.runOnIdle { sectionSummaries = summaries(0, 0) }
        composeRule.onAllNodesWithText("dwell n/a").assertCountEquals(2)

        composeRule.runOnIdle { state = state.copy(thermalThrottleDetected = true) }
        composeRule.onNodeWithText("Thermal Guard", substring = true).assertExists()

        val replay = TrackingSession(
            id = "r1",
            title = "Morning read",
            deviceModel = "Test",
            durationSeconds = 95,
            blinksPerMinute = 12.25,
            effectiveWordsPerMinute = 184,
            fixationClusterCount = 7
        )
        composeRule.runOnIdle {
            state = AttentionSessionLiveState(isRecording = false, selectedReplaySession = replay, thermalThrottleDetected = true)
        }
        composeRule.onNodeWithText("Session Replay: Morning read").assertExists()
        composeRule.onNodeWithText(referenceReplaySubtitle(replay)).assertExists()
        composeRule.onNodeWithTag("close_session_replay_button").assertExists()
        composeRule.onNodeWithTag("start_attention_session_button").assertExists()
        composeRule.onNodeWithText("Thermal Guard", substring = true).assertDoesNotExist()

        composeRule.runOnIdle { state = AttentionSessionLiveState() }
        composeRule.onNodeWithText("Section Dwell & Fixation Centroid Lab").assertExists()
        composeRule.onNodeWithText("Measures layout section dwell (%) & dispersion fixation clusters at camera FPS.").assertExists()
        composeRule.onNodeWithTag("close_session_replay_button").assertDoesNotExist()
    }

    @Test
    fun `hit reports are unchanged when relayouts re-deliver equal section bounds`() {
        var reticle by mutableStateOf(Offset.Zero)
        var sectionSummaries by mutableStateOf(summaries(10, 90))
        var state by mutableStateOf(AttentionSessionLiveState(isRecording = true, activeSectionId = "sec_1"))
        val hits = mutableListOf<String?>()
        val passagePoints = mutableListOf<Offset?>()
        composeRule.setContent {
            AttentionAnalyticsScreen(
                attentionState = state,
                reticleRootPosition = { reticle },
                passageSections = sections,
                sectionSummaries = sectionSummaries,
                onUpdateLayoutHitSection = { hits += it },
                onUpdatePassageGazePoint = { passagePoints += it },
                onStartRecording = {},
                onStopAndSaveRecording = {}
            )
        }
        val alpha = composeRule.onNodeWithText("Alpha heading").fetchSemanticsNode().boundsInRoot.center
        val beta = composeRule.onNodeWithText("Beta heading").fetchSemanticsNode().boundsInRoot.center
        composeRule.runOnIdle { reticle = alpha }
        composeRule.waitForIdle()
        assertEquals("sec_1", hits.last())
        val hitsBefore = hits.size
        val pointsBefore = passagePoints.size

        // Recorded frames: labels change width and every section is laid out and positioned again at equal bounds.
        repeat(20) { frame ->
            composeRule.runOnIdle {
                sectionSummaries = summaries(10 + frame, 90 - frame)
                // Header text is kept fixed so the passage itself does not move; labels stay two digits wide.
                state = state.copy(
                    topZoneMs = frame * 33L,
                    activeSectionId = if (frame % 2 == 0) "sec_1" else "sec_2"
                )
            }
        }
        composeRule.waitForIdle()
        // Same reticle, same bounds: the hit-test flow emitted nothing new, before and after the write guard.
        assertEquals(hitsBefore, hits.size)
        assertEquals(pointsBefore, passagePoints.size)

        composeRule.runOnIdle { reticle = beta }
        composeRule.waitForIdle()
        assertEquals("sec_2", hits.last())
        composeRule.runOnIdle { reticle = alpha }
        composeRule.waitForIdle()
        assertEquals("sec_1", hits.last())
    }

    @Test
    fun `heatmap node is present exactly while clusters are`() {
        var clusters by mutableStateOf(emptyList<FixationClusterPoint>())
        composeRule.setContent {
            AttentionAnalyticsScreen(
                attentionState = AttentionSessionLiveState(isRecording = true, computedClusters = clusters),
                reticleRootPosition = { Offset.Zero },
                passageSections = sections,
                sectionSummaries = summaries(50, 50),
                onStartRecording = {},
                onStopAndSaveRecording = {}
            )
        }
        composeRule.onNodeWithTag("attention_heatmap_canvas").assertDoesNotExist()
        val random = Random(3)
        composeRule.runOnIdle { clusters = List(45) { randomCluster(random) } }
        composeRule.onNodeWithTag("attention_heatmap_canvas").assertExists()
        composeRule.runOnIdle { clusters = emptyList() }
        composeRule.onNodeWithTag("attention_heatmap_canvas").assertDoesNotExist()
        composeRule.runOnIdle { clusters = List(3) { randomCluster(random) } }
        composeRule.onNodeWithTag("attention_heatmap_canvas").assertExists()
    }

    @Test
    fun `privacy screen content follows the hud fields it shows and ignores per-frame ones`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val optedIn = context.getString(R.string.cloud_telemetry_status_opted_in)
        val optedOut = context.getString(R.string.cloud_telemetry_status_opted_out)
        var hud by mutableStateOf(LiveGazeHudState())
        composeRule.setContent {
            CloudPrivacyScreen(
                hudState = hud,
                currentUserId = "uid_opt",
                profilesState = UiDataState.Success(emptyList()),
                sessionsState = UiDataState.Success(emptyList()),
                onToggleCloudSync = {},
                onActivateProfile = {},
                onDeleteProfile = {},
                onSelectSessionReplay = {},
                onDeleteSession = {},
                onEraseAllTelemetry = {},
                onSignOut = {}
            )
        }
        composeRule.onAllNodesWithText("1000 ms").assertCountEquals(2)
        composeRule.onNodeWithText("1.00× (14 dp)").assertExists()
        composeRule.onNodeWithText(optedOut).assertExists()
        // Chip labels and tags now come from per-ordinal tables built once.
        DwellSpeedPreset.entries.forEach { preset ->
            composeRule.onNodeWithTag("speed_preset_chip_${preset.name.lowercase()}")
                .assert(hasText(preset.title.take(12)))
        }

        repeat(20) { frame ->
            composeRule.runOnIdle { hud = hud.copy(normX = frame / 20f, analyzerFps = 30 + frame) }
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("1000 ms").assertCountEquals(2)

        composeRule.runOnIdle { hud = hud.copy(dwellTriggerMs = 1400L) }
        composeRule.onAllNodesWithText("1400 ms").assertCountEquals(2)
        // The remembered sensitivity label is keyed on the multiplier only.
        composeRule.onNodeWithText("1.00× (14 dp)").assertExists()
        composeRule.runOnIdle { hud = hud.copy(dwellSensitivityRadiusMultiplier = 1.55f) }
        composeRule.onNodeWithText("1.55× (22 dp)").assertExists()
        composeRule.runOnIdle { hud = hud.copy(cloudSyncEnabled = true) }
        composeRule.onNodeWithText(optedIn).assertExists()
        composeRule.onNodeWithText(optedOut).assertDoesNotExist()
        composeRule.runOnIdle { hud = hud.copy(lastCloudDeletionSummary = "Deleted 3 cloud documents") }
        composeRule.onNodeWithTag("cloud_deletion_status_text").assert(hasText("Deleted 3 cloud documents"))
        composeRule.runOnIdle { hud = hud.copy(isDeletingCloudData = true) }
        composeRule.onNodeWithTag("sign_out_button").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `keyed session rows keep the right summary and click target when the list shifts`() {
        fun session(id: String, seconds: Int, bpm: Double, top: Int) = TrackingSession(
            id = id,
            title = "Session $id",
            deviceModel = "Test",
            durationSeconds = seconds,
            blinksPerMinute = bpm,
            effectiveWordsPerMinute = seconds * 3,
            topZonePercentage = top,
            middleZonePercentage = 100 - top,
            bottomZonePercentage = 0
        )
        val a = session("a", 11, 10.25, 50)
        val b = session("b", 22, 0.0, 0)
        val c = session("c", 33, 18.75, 70)
        val n = session("n", 44, 7.05, 20)
        var sessions by mutableStateOf(listOf(a, b, c))
        val replays = mutableListOf<String>()
        composeRule.setContent {
            CloudPrivacyScreen(
                hudState = LiveGazeHudState(),
                profilesState = UiDataState.Success(emptyList()),
                sessionsState = UiDataState.Success(sessions),
                onToggleCloudSync = {},
                onActivateProfile = {},
                onDeleteProfile = {},
                onSelectSessionReplay = { replays += it.id },
                onDeleteSession = {},
                onEraseAllTelemetry = {},
                onSignOut = {}
            )
        }
        fun assertRow(s: TrackingSession) {
            composeRule.onNodeWithTag("session_row_${s.id}").assert(hasText(referenceFormatSessionSummaryLine(s)))
        }
        listOf(a, b, c).forEach(::assertRow)

        composeRule.runOnIdle { sessions = listOf(n, a, c) }
        composeRule.onNodeWithTag("session_row_b").assertDoesNotExist()
        listOf(n, a, c).forEach(::assertRow)

        composeRule.onNodeWithTag("session_row_c").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("c"), replays)
    }

    @Test
    fun `keyed profile rows keep the right error line and delete target, duplicates included`() {
        val p1 = CalibrationProfile(id = "p1", name = "One", deviceModel = "Test", meanErrorPct = 6.5, maxErrorPct = 8.25, meanErrorMm = 7.9)
        val p2 = CalibrationProfile(id = "p2", name = "Two", deviceModel = "Test", meanErrorPct = 4.25, maxErrorPct = 5.5, meanErrorMm = 5.1, heldOutValidated = true)
        val p3 = CalibrationProfile(id = "p3", name = "Three", deviceModel = "Test", meanErrorPct = 9.75, maxErrorPct = 12.0, meanErrorMm = 11.6)
        var profiles by mutableStateOf(listOf(p1, p2, p3))
        val deleted = mutableListOf<String>()
        composeRule.setContent {
            CloudPrivacyScreen(
                hudState = LiveGazeHudState(activeProfileId = "p3"),
                profilesState = UiDataState.Success(profiles),
                sessionsState = UiDataState.Success(emptyList()),
                onToggleCloudSync = {},
                onActivateProfile = {},
                onDeleteProfile = { deleted += it },
                onSelectSessionReplay = {},
                onDeleteSession = {},
                onEraseAllTelemetry = {},
                onSignOut = {}
            )
        }
        listOf(p1, p2, p3).forEach { composeRule.onNodeWithText(referenceFormatProfileErrorLine(it)).assertExists() }

        composeRule.runOnIdle { profiles = listOf(p2, p3) }
        composeRule.onNodeWithTag("delete_profile_p1").assertDoesNotExist()
        composeRule.onNodeWithText(referenceFormatProfileErrorLine(p1)).assertDoesNotExist()
        listOf(p2, p3).forEach { composeRule.onNodeWithText(referenceFormatProfileErrorLine(it)).assertExists() }

        composeRule.onNodeWithTag("delete_profile_p3").performScrollTo().performClick()
        composeRule.onNodeWithTag("confirm_destructive_action_button").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf("p3"), deleted)

        // Same id twice (e.g. a legacy record without an id): both rows still render.
        composeRule.runOnIdle { profiles = listOf(p2, p2.copy(name = "Two again"), p3) }
        composeRule.onAllNodesWithTag("delete_profile_p2").assertCountEquals(2)
        composeRule.onNodeWithText("Two again", substring = true).assertExists()
    }
}
