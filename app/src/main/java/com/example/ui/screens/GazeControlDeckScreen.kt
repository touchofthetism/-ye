package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.AacActionTile
import com.example.data.model.GazeTriggerMode
import com.example.domain.GazeCalibrationMath
import com.example.ui.components.DWELL_DURATION_SLIDER_RANGE_MS
import com.example.ui.viewmodel.LiveGazeHudState
import java.util.Locale
import kotlin.math.roundToLong

/** Focus hysteresis around the focused tile before the user's sensitivity multiplier, in dp. */
private val FOCUS_HYSTERESIS_BASE_DP = 14.dp

/**
 * Grid decision (§3.5 locked product decision), shared with the calibration summary's recommended
 * layout through [GazeCalibrationMath.qualifiesFor3x3Grid]: the 3x3 deck (9 tiles) only when both
 * error metrics were actually measured (non-null) and are within the 3x3 thresholds; otherwise the
 * protective 2x2 large-region deck (4 tiles). Null means "not validated" (e.g. the default matrix).
 */
internal fun shouldUseValidated3x3Grid(meanErrorPct: Float?, maxQuadrantErrorPct: Float?): Boolean =
    meanErrorPct != null && maxQuadrantErrorPct != null &&
        GazeCalibrationMath.qualifiesFor3x3Grid(meanErrorPct.toDouble(), maxQuadrantErrorPct.toDouble())

/**
 * Gaze hit test for the deck. Keeps the current tile while the reticle stays within [hysteresisPx]
 * of its bounds, otherwise returns the visible tile under the reticle (or null). Only ids in
 * [visibleTileIds] can win, so bounds of tiles that left the grid are never hit-testable.
 */
internal fun resolveFocusedDeckTile(
    reticleRootPosition: Offset,
    tileBounds: Map<String, Rect>,
    visibleTileIds: Set<String>,
    currentFocusedId: String?,
    hysteresisPx: Float
): String? {
    val currentRect = currentFocusedId?.takeIf { it in visibleTileIds }?.let { tileBounds[it] }
    val stillInCurrent = currentRect != null &&
        reticleRootPosition.x >= (currentRect.left - hysteresisPx) &&
        reticleRootPosition.x <= (currentRect.right + hysteresisPx) &&
        reticleRootPosition.y >= (currentRect.top - hysteresisPx) &&
        reticleRootPosition.y <= (currentRect.bottom + hysteresisPx)
    if (stillInCurrent) {
        // Maintain focus on currently fixated tile
        return currentFocusedId
    }
    return tileBounds.entries.firstOrNull { (id, rect) ->
        id in visibleTileIds && rect.contains(reticleRootPosition)
    }?.key
}

@Composable
fun GazeControlDeckScreen(
    hudState: LiveGazeHudState,
    reticleRootPosition: Offset,
    tiles: List<AacActionTile>,
    onUpdateFocusedTile: (String?) -> Unit,
    onManualTileClick: (String) -> Unit,
    onChangeDwellDurationMs: (Long) -> Unit,
    onChangeDwellSensitivityMultiplier: (Float) -> Unit = {},
    onSelectDwellPreset: (com.example.data.model.DwellSpeedPreset) -> Unit = {},
    onChangeTriggerMode: (GazeTriggerMode) -> Unit = {},
    onCalibrateNeutralBrow: () -> Unit = {},
    onCalibrateRaisedBrow: () -> Unit = {},
    onEditTilePhrase: (String, String, String) -> Unit = { _, _, _ -> },
    onSelectPosture: (com.example.data.model.GazePostureProfile) -> Unit = {},
    onSelectFilterMode: (com.example.data.model.GazeFilterMode) -> Unit = {},
    onRunBenchmark: () -> Unit = {}
) {
    // The reticle position changes up to every display frame. Only the hit-test coroutine reads it
    // (through this State), so a move recomposes just this wrapper: the deck body skips and the
    // hit-test effect is not relaunched.
    val latestReticleRootPosition = rememberUpdatedState(reticleRootPosition)
    GazeControlDeckContent(
        hudState = hudState,
        reticleRootPosition = latestReticleRootPosition,
        tiles = tiles,
        onUpdateFocusedTile = onUpdateFocusedTile,
        onManualTileClick = onManualTileClick,
        onChangeDwellDurationMs = onChangeDwellDurationMs,
        onChangeDwellSensitivityMultiplier = onChangeDwellSensitivityMultiplier,
        onSelectDwellPreset = onSelectDwellPreset,
        onChangeTriggerMode = onChangeTriggerMode,
        onCalibrateNeutralBrow = onCalibrateNeutralBrow,
        onCalibrateRaisedBrow = onCalibrateRaisedBrow,
        onEditTilePhrase = onEditTilePhrase,
        onSelectPosture = onSelectPosture,
        onSelectFilterMode = onSelectFilterMode,
        onRunBenchmark = onRunBenchmark
    )
}

@Composable
private fun GazeControlDeckContent(
    hudState: LiveGazeHudState,
    reticleRootPosition: State<Offset>,
    tiles: List<AacActionTile>,
    onUpdateFocusedTile: (String?) -> Unit,
    onManualTileClick: (String) -> Unit,
    onChangeDwellDurationMs: (Long) -> Unit,
    onChangeDwellSensitivityMultiplier: (Float) -> Unit,
    onSelectDwellPreset: (com.example.data.model.DwellSpeedPreset) -> Unit,
    onChangeTriggerMode: (GazeTriggerMode) -> Unit,
    onCalibrateNeutralBrow: () -> Unit,
    onCalibrateRaisedBrow: () -> Unit,
    onEditTilePhrase: (String, String, String) -> Unit,
    onSelectPosture: (com.example.data.model.GazePostureProfile) -> Unit,
    onSelectFilterMode: (com.example.data.model.GazeFilterMode) -> Unit,
    onRunBenchmark: () -> Unit
) {
    val tileBoundsMap = remember { mutableStateMapOf<String, Rect>() }
    var editingTile by remember { mutableStateOf<AacActionTile?>(null) }
    var editTitleText by remember { mutableStateOf("") }
    var editPhraseText by remember { mutableStateOf("") }
    var showDwellSettingsDialog by remember { mutableStateOf(false) }

    val measuredMeanErrorPct: Float? = hudState.activeErrorRadiusPct
    val measuredMaxQuadrantErrorPct: Float? = hudState.activeMaxQuadrantErrorPct
    val isAccuracyValidated = measuredMeanErrorPct != null && measuredMaxQuadrantErrorPct != null
    val use3x3Grid = shouldUseValidated3x3Grid(measuredMeanErrorPct, measuredMaxQuadrantErrorPct)
    val columnCount = if (use3x3Grid) 3 else 2
    val visibleTiles = remember(tiles, use3x3Grid) { tiles.take(if (use3x3Grid) 9 else 4) }
    val visibleTileIds = remember(visibleTiles) { visibleTiles.mapTo(HashSet()) { it.id } }

    // Gaze input is suspended while a dialog covers the deck: the user is reading or typing there,
    // and the tiles underneath must not dwell, blink- or brow-fire.
    val gazeInputSuspended = showDwellSettingsDialog || editingTile != null

    // 14 dp focus hysteresis (scaled by the user's sensitivity multiplier) in this device's pixels,
    // the same unit as the reticle position and the tile bounds.
    val focusHysteresisPx = with(LocalDensity.current) { FOCUS_HYSTERESIS_BASE_DP.toPx() } *
        hudState.dwellSensitivityRadiusMultiplier

    val latestOnUpdateFocusedTile by rememberUpdatedState(onUpdateFocusedTile)
    val latestFocusedTileId by rememberUpdatedState(hudState.focusedTileId)
    val latestVisibleTileIds by rememberUpdatedState(visibleTileIds)
    val latestFocusHysteresisPx by rememberUpdatedState(focusHysteresisPx)

    // Hit-test using the exact root-window pixel coordinate of the rendered confidence reticle,
    // re-run whenever the reticle moves or a tile's bounds change, with focus hysteresis expansion
    // to prevent jitter/flicker at tile boundaries.
    LaunchedEffect(gazeInputSuspended) {
        if (gazeInputSuspended) {
            latestOnUpdateFocusedTile(null)
            return@LaunchedEffect
        }
        snapshotFlow { reticleRootPosition.value to tileBoundsMap.toMap() }
            .collect { (position, bounds) ->
                if (position != Offset.Zero) {
                    latestOnUpdateFocusedTile(
                        resolveFocusedDeckTile(
                            reticleRootPosition = position,
                            tileBounds = bounds,
                            visibleTileIds = latestVisibleTileIds,
                            currentFocusedId = latestFocusedTileId,
                            hysteresisPx = latestFocusHysteresisPx
                        )
                    )
                }
            }
    }

    // Leaving the deck (tab switch, back) must clear the hovered tile, or dwell, command blink and
    // brow raise keep firing (and speaking) it on other tabs.
    DisposableEffect(Unit) {
        onDispose { latestOnUpdateFocusedTile(null) }
    }

    if (showDwellSettingsDialog) {
        com.example.ui.components.DwellTriggerSettingsDialog(
            hudState = hudState,
            onDismissRequest = { showDwellSettingsDialog = false },
            onChangeDurationMs = onChangeDwellDurationMs,
            onChangeSensitivityMultiplier = onChangeDwellSensitivityMultiplier,
            onSelectPreset = onSelectDwellPreset,
            onSelectPosture = onSelectPosture,
            onSelectFilterMode = onSelectFilterMode,
            onRunBenchmark = onRunBenchmark
        )
    }

    if (editingTile != null) {
        val currentTile = editingTile!!
        // The ViewModel ignores an edit with a blank label or phrase, so Save is only offered when
        // both are filled; otherwise the dialog would close as if saved and drop the whole edit.
        val isTitleBlank = editTitleText.isBlank()
        val isPhraseBlank = editPhraseText.isBlank()
        AlertDialog(
            onDismissRequest = { editingTile = null },
            title = { Text("Customize AAC Tile") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editTitleText,
                        onValueChange = { editTitleText = it },
                        label = { Text("Tile Label") },
                        singleLine = true,
                        isError = isTitleBlank,
                        supportingText = if (isTitleBlank) {
                            { Text("A tile label is required") }
                        } else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_tile_title_field")
                    )
                    OutlinedTextField(
                        value = editPhraseText,
                        onValueChange = { editPhraseText = it },
                        label = { Text("Spoken TTS Phrase") },
                        isError = isPhraseBlank,
                        supportingText = if (isPhraseBlank) {
                            { Text("A spoken phrase is required") }
                        } else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("edit_tile_phrase_field")
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onEditTilePhrase(currentTile.id, editTitleText, editPhraseText)
                        editingTile = null
                    },
                    enabled = !isTitleBlank && !isPhraseBlank,
                    modifier = Modifier.testTag("save_tile_edit_button")
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingTile = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        // Speech Synthesis + Expression Calibration & Trigger Mode Selector Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "TTS Output",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            // Same label as the calibration summary's recommended layout; "Validated"
                            // only when measured metrics unlocked the 3x3 deck.
                            text = GazeCalibrationMath.gridLayoutLabel(use3x3Grid) +
                                (if (isAccuracyValidated) "" else " • not yet validated"),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.testTag("deck_layout_title")
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { showDwellSettingsDialog = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.testTag("open_dwell_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Dwell Settings",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Dwell: ${hudState.dwellTriggerMs}ms (${String.format(java.util.Locale.US, "%.1f×", hudState.dwellSensitivityRadiusMultiplier)})",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Fixed two-line height: a longer or shorter phrase must not reflow the card and
                // shift the tile grid under a fixating gaze.
                Text(
                    text = hudState.lastTriggeredSpeech,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("aac_speech_output_text")
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Trigger Mode Selector + Quick 2-Step Brow Expression Calibration
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GazeTriggerMode.entries.forEach { mode ->
                        FilterChip(
                            selected = hudState.triggerMode == mode,
                            onClick = { onChangeTriggerMode(mode) },
                            label = {
                                Text(
                                    text = when (mode) {
                                        GazeTriggerMode.ALL_ACTIVE -> "All Triggers"
                                        GazeTriggerMode.BROW_RAISE -> "Brow Raise"
                                        GazeTriggerMode.COMMAND_BLINK -> "Cmd Blink"
                                        GazeTriggerMode.DWELL_ONLY -> "Dwell Only"
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Brow Ratio: ${String.format(Locale.US, "%.3f", hudState.rawFeature.browToEyeRatio)} (N:${String.format(Locale.US, "%.2f", hudState.neutralBrowRatio)}→R:${String.format(Locale.US, "%.2f", hudState.raisedBrowRatio)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = onCalibrateNeutralBrow,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.testTag("sample_neutral_brow_button")
                    ) {
                        Text("1. Neutral Brow", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    OutlinedButton(
                        onClick = onCalibrateRaisedBrow,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.testTag("sample_raised_brow_button")
                    ) {
                        Text("2. Raised Brow", style = MaterialTheme.typography.labelSmall)
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Dwell Time:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    // Same range as the settings dialog, so every preset (650 ms .. 1900 ms) is shown
                    // and settable here; rounding keeps float slider values from truncating 1 ms low.
                    Slider(
                        value = hudState.dwellTriggerMs.toFloat(),
                        onValueChange = { onChangeDwellDurationMs(it.roundToLong()) },
                        valueRange = DWELL_DURATION_SLIDER_RANGE_MS,
                        modifier = Modifier
                            .weight(1f)
                            .height(26.dp)
                            .testTag("deck_dwell_duration_slider")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(visibleTiles, key = { it.id }) { tile ->
                // A tile that leaves the grid (3x3 -> 2x2 switch, scrolled out) drops its bounds,
                // so the hit test never focuses or dwell-fires a tile that is not on screen.
                DisposableEffect(tile.id) {
                    onDispose { tileBoundsMap.remove(tile.id) }
                }
                val isFocused = hudState.focusedTileId == tile.id
                val borderColor = if (isFocused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                }
                val containerBg = if (isFocused) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                } else {
                    MaterialTheme.colorScheme.surface
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (use3x3Grid) 104.dp else 124.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(containerBg)
                        .border(if (isFocused) 2.5.dp else 1.dp, borderColor, RoundedCornerShape(16.dp))
                        .clickable { onManualTileClick(tile.id) }
                        .onGloballyPositioned { coords ->
                            tileBoundsMap[tile.id] = coords.boundsInRoot()
                        }
                        .padding(12.dp)
                        .testTag("aac_tile_${tile.id}")
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (tile.category == "SYSTEM") Icons.Default.CenterFocusWeak else Icons.Default.RecordVoiceOver,
                                    contentDescription = tile.title,
                                    tint = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = tile.category,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isFocused) {
                                    CircularProgressIndicator(
                                        progress = {
                                            maxOf(
                                                hudState.focusedTileDwellProgress,
                                                hudState.browHoldProgress,
                                                hudState.blinkHoldProgress
                                            )
                                        },
                                        modifier = Modifier.size(22.dp),
                                        color = MaterialTheme.colorScheme.secondary,
                                        strokeWidth = 2.5.dp
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                IconButton(
                                    onClick = {
                                        editTitleText = tile.title
                                        editPhraseText = tile.speechPhrase
                                        editingTile = tile
                                    },
                                    modifier = Modifier
                                        .size(28.dp)
                                        .testTag("edit_aac_tile_${tile.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Edit ${tile.title}",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }

                        Column {
                            Text(
                                text = tile.title,
                                style = if (use3x3Grid) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = tile.speechPhrase,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
