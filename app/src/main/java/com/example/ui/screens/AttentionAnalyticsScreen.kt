package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.example.data.model.SectionDwellSummary
import com.example.data.model.SectionPassageItem
import com.example.ui.viewmodel.AttentionSessionLiveState
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.Locale

/**
 * Reported through `onUpdateLayoutHitSection` when the section bounds are attached but the reticle is over none of
 * them (header card, gaps and padding around the sections, HUD above the scroll viewport), and when this screen
 * leaves composition while a recording may keep running. `null` keeps its meaning of "section bounds not attached
 * yet", which is the only case where the ViewModel may fall back to fixed vertical bands.
 */
const val ATTENTION_OFF_PASSAGE_SECTION_ID = "__off_passage__"

/** Passage Box geometry in root coordinates: unclipped origin and size (scroll included) plus the visible, clipped bounds. */
internal data class PassageLayoutSnapshot(
    val originInRoot: Offset,
    val size: Size,
    val visibleBoundsInRoot: Rect
)

/** Section id under the reticle, [ATTENTION_OFF_PASSAGE_SECTION_ID] when bounds are known but none is hit, null when no bounds are attached. */
internal fun resolveLayoutHitReport(reticleRootPosition: Offset, sectionBounds: Map<String, Rect>): String? {
    if (sectionBounds.isEmpty()) return null
    return sectionBounds.entries.firstOrNull { (_, rect) -> rect.contains(reticleRootPosition) }?.key
        ?: ATTENTION_OFF_PASSAGE_SECTION_ID
}

/**
 * Reticle position normalized to the passage Box (the frame the fixation heatmap Canvas draws in), or null when the
 * reticle is not over the visible part of the passage.
 */
internal fun passageLocalGazePoint(reticleRootPosition: Offset, passage: PassageLayoutSnapshot): Offset? {
    if (passage.size.width <= 0f || passage.size.height <= 0f) return null
    if (!passage.visibleBoundsInRoot.contains(reticleRootPosition)) return null
    val x = (reticleRootPosition.x - passage.originInRoot.x) / passage.size.width
    val y = (reticleRootPosition.y - passage.originInRoot.y) / passage.size.height
    return if (x in 0f..1f && y in 0f..1f) Offset(x, y) else null
}

/** The thermal flag is latched per recording; it only describes the live session. */
internal fun shouldShowThermalGuard(state: AttentionSessionLiveState): Boolean =
    state.isRecording && state.thermalThrottleDetected

/** Words of the sections the gaze dwelled on per minute of on-passage time; 0 means not enough on-passage gaze. */
internal fun formatEstimatedWpm(wordsPerMinute: Int): String =
    if (wordsPerMinute > 0) "$wordsPerMinute est. WPM (dwelled sections)" else "est. WPM n/a"

/**
 * Per-section dwell label. When no section has any dwell (the shares sum to 0: nothing recorded yet, or a saved
 * session without on-passage gaze) there is no share to show, so every section reads "n/a" instead of "0%".
 */
internal fun formatSectionDwellLabel(percentage: Int, anySectionDwell: Boolean): String =
    if (anySectionDwell) "$percentage% dwell" else "dwell n/a"

/** Compatibility overload for callers that read the reticle state in their own scope. */
@Composable
fun AttentionAnalyticsScreen(
    attentionState: AttentionSessionLiveState,
    reticleRootPosition: Offset,
    passageSections: List<SectionPassageItem>,
    sectionSummaries: List<SectionDwellSummary>,
    onUpdateLayoutHitSection: (String?) -> Unit = {},
    onUpdatePassageGazePoint: (Offset?) -> Unit = {},
    onClearReplay: () -> Unit = {},
    onStartRecording: () -> Unit,
    onStopAndSaveRecording: () -> Unit
) {
    val latestReticle = rememberUpdatedState(reticleRootPosition)
    AttentionAnalyticsScreen(
        attentionState = attentionState,
        reticleRootPosition = { latestReticle.value },
        passageSections = passageSections,
        sectionSummaries = sectionSummaries,
        onUpdateLayoutHitSection = onUpdateLayoutHitSection,
        onUpdatePassageGazePoint = onUpdatePassageGazePoint,
        onClearReplay = onClearReplay,
        onStartRecording = onStartRecording,
        onStopAndSaveRecording = onStopAndSaveRecording
    )
}

/**
 * @param reticleRootPosition Provider of the reticle's root-window position. It is read only inside the hit-test
 *   coroutine, so reticle motion and scrolling do not recompose this screen.
 * @param onUpdateLayoutHitSection See [ATTENTION_OFF_PASSAGE_SECTION_ID] for the meaning of null vs. the sentinel.
 * @param onUpdatePassageGazePoint Reticle position normalized to the passage Box (the heatmap Canvas frame), or null
 *   when the reticle is off the visible passage or the screen has left composition.
 */
@Composable
fun AttentionAnalyticsScreen(
    attentionState: AttentionSessionLiveState,
    reticleRootPosition: () -> Offset = { Offset.Zero },
    passageSections: List<SectionPassageItem>,
    sectionSummaries: List<SectionDwellSummary>,
    onUpdateLayoutHitSection: (String?) -> Unit = {},
    onUpdatePassageGazePoint: (Offset?) -> Unit = {},
    onClearReplay: () -> Unit = {},
    onStartRecording: () -> Unit,
    onStopAndSaveRecording: () -> Unit
) {
    val sectionBoundsMap = remember { mutableStateMapOf<String, Rect>() }
    var passageLayout by remember { mutableStateOf<PassageLayoutSnapshot?>(null) }
    val textMeasurer = rememberTextMeasurer()
    val scrollState = rememberScrollState()
    val latestReticle by rememberUpdatedState(reticleRootPosition)
    val latestOnHit by rememberUpdatedState(onUpdateLayoutHitSection)
    val latestOnPassagePoint by rememberUpdatedState(onUpdatePassageGazePoint)

    // Hit-test the rendered reticle's root-window coordinate against the live scrolled section bounding boxes.
    // Reticle, section bounds and passage geometry are read only here, so reticle motion, scrolling and layout
    // shifts (e.g. a banner pushing the passage down) re-run the hit-test without recomposing the screen.
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(latestReticle(), sectionBoundsMap.toMap(), passageLayout) }
            .distinctUntilChanged()
            .collect { (pos, bounds, passage) ->
                if (pos != Offset.Zero) {
                    latestOnHit(resolveLayoutHitReport(pos, bounds))
                    latestOnPassagePoint(passage?.let { passageLocalGazePoint(pos, it) })
                }
            }
    }

    // A recording keeps running after the user leaves this tab; stop crediting the last hit section and
    // stop recording passage-frame fixation samples while the passage is not on screen.
    DisposableEffect(Unit) {
        onDispose {
            latestOnHit(ATTENTION_OFF_PASSAGE_SECTION_ID)
            latestOnPassagePoint(null)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val replay = attentionState.selectedReplaySession
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (replay != null && !attentionState.isRecording) {
                                "Session Replay: ${replay.title}"
                            } else {
                                "Section Dwell & Fixation Centroid Lab"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = when {
                                attentionState.isRecording ->
                                    "Recording (${attentionState.elapsedSeconds}s) • Nat Blinks: ${attentionState.sessionNaturalBlinks} • Cmd Blinks: ${attentionState.sessionIntentionalBlinks}"
                                replay != null ->
                                    "${replay.durationSeconds}s • ${formatEstimatedWpm(replay.effectiveWordsPerMinute)} • ${String.format(Locale.US, "%.1f", replay.blinksPerMinute)} blinks/min • ${replay.fixationClusterCount} clusters"
                                else ->
                                    "Measures layout section dwell (%) & dispersion fixation clusters at camera FPS."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    if (!attentionState.isRecording) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (replay != null) {
                                OutlinedButton(
                                    onClick = onClearReplay,
                                    modifier = Modifier.testTag("close_session_replay_button")
                                ) {
                                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close Replay")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Exit Replay", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Button(
                                onClick = onStartRecording,
                                modifier = Modifier.testTag("start_attention_session_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(imageVector = Icons.Default.FiberManualRecord, contentDescription = "Record")
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Record", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    } else {
                        Button(
                            onClick = onStopAndSaveRecording,
                            modifier = Modifier.testTag("stop_attention_session_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(imageVector = Icons.Default.Stop, contentDescription = "Stop")
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Stop & Save", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }

                if (shouldShowThermalGuard(attentionState)) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Thermal Guard: Device warming up (>40% FPS drop from baseline) — Performance Mode engaged automatically.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    // positionInRoot/size are unclipped and include the scroll offset (the heatmap Canvas frame);
                    // boundsInRoot is clipped to the scroll viewport (what is actually visible).
                    passageLayout = PassageLayoutSnapshot(
                        originInRoot = coords.positionInRoot(),
                        size = coords.size.toSize(),
                        visibleBoundsInRoot = coords.boundsInRoot()
                    )
                }
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val anySectionDwell = sectionSummaries.sumOf { it.percentage } > 0
                passageSections.forEach { section ->
                    val summary = sectionSummaries.firstOrNull { it.sectionId == section.sectionId }
                    val isCurrentlyLookedAt = attentionState.isRecording && attentionState.activeSectionId == section.sectionId
                    val pct = summary?.percentage ?: 0

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isCurrentlyLookedAt) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                                }
                            )
                            .border(
                                width = if (isCurrentlyLookedAt) 1.5.dp else 1.dp,
                                color = if (isCurrentlyLookedAt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .onGloballyPositioned { coords ->
                                sectionBoundsMap[section.sectionId] = coords.boundsInRoot()
                            }
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = section.heading,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = formatSectionDwellLabel(pct, anySectionDwell),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = section.body,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (pct / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.tertiary,
                            trackColor = MaterialTheme.colorScheme.background
                        )
                    }
                }
            }

            if (attentionState.computedClusters.isNotEmpty()) {
                val clusters = attentionState.computedClusters
                Canvas(
                    modifier = Modifier
                        .matchParentSize()
                        .testTag("attention_heatmap_canvas")
                ) {
                    // 1. Gaussian-splat density heatmap circles
                    for (cluster in clusters) {
                        val cx = cluster.normX * size.width
                        val cy = cluster.normY * size.height
                        val weightNorm = (cluster.dwellMs.toFloat() / 2200f).coerceIn(0.25f, 1.0f)
                        val radius = (42.dp.toPx() + weightNorm * 46.dp.toPx())

                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFEF4444).copy(alpha = 0.46f * weightNorm),
                                    Color(0xFFF59E0B).copy(alpha = 0.28f * weightNorm),
                                    Color.Transparent
                                ),
                                center = Offset(cx, cy),
                                radius = radius
                            ),
                            radius = radius,
                            center = Offset(cx, cy)
                        )
                    }

                    // 2. Dashed centroid sequence trajectory lines
                    for (i in 0 until (clusters.size - 1)) {
                        val p1 = Offset(clusters[i].normX * size.width, clusters[i].normY * size.height)
                        val p2 = Offset(clusters[i + 1].normX * size.width, clusters[i + 1].normY * size.height)
                        drawLine(
                            color = Color(0xFF00E5FF).copy(alpha = 0.72f),
                            start = p1,
                            end = p2,
                            strokeWidth = 2.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                        )
                    }

                    // 3. Numbered fixation cluster sequence nodes (1..N)
                    clusters.forEachIndexed { index, cluster ->
                        val cx = cluster.normX * size.width
                        val cy = cluster.normY * size.height
                        drawCircle(
                            color = Color(0xFF0B0F17),
                            radius = 11.dp.toPx(),
                            center = Offset(cx, cy)
                        )
                        drawCircle(
                            color = Color(0xFF00E5FF),
                            radius = 10.dp.toPx(),
                            center = Offset(cx, cy)
                        )
                        val labelLayout = textMeasurer.measure(
                            text = "${index + 1}",
                            style = TextStyle(
                                color = Color(0xFF0B0F17),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        drawText(
                            textLayoutResult = labelLayout,
                            topLeft = Offset(
                                cx - labelLayout.size.width / 2f,
                                cy - labelLayout.size.height / 2f
                            )
                        )
                    }
                }
            }
        }
    }
}
