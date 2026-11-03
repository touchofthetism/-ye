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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.AacActionTile
import com.example.data.model.GazeTriggerMode
import com.example.ui.viewmodel.LiveGazeHudState

@Composable
fun GazeControlDeckScreen(
    hudState: LiveGazeHudState,
    reticleRootPosition: Offset,
    tiles: List<AacActionTile>,
    onUpdateFocusedTile: (String?) -> Unit,
    onManualTileClick: (String) -> Unit,
    onChangeDwellDurationMs: (Long) -> Unit,
    onChangeTriggerMode: (GazeTriggerMode) -> Unit = {},
    onCalibrateNeutralBrow: () -> Unit = {},
    onCalibrateRaisedBrow: () -> Unit = {},
    onEditTilePhrase: (String, String, String) -> Unit = { _, _, _ -> }
) {
    val tileBoundsMap = remember { mutableStateMapOf<String, Rect>() }
    var editingTile by remember { mutableStateOf<AacActionTile?>(null) }
    var editTitleText by remember { mutableStateOf("") }
    var editPhraseText by remember { mutableStateOf("") }

    // Hit-test using the exact root-window pixel coordinate of the rendered confidence reticle
    LaunchedEffect(reticleRootPosition, tileBoundsMap.size) {
        if (reticleRootPosition != Offset.Zero) {
            val hitEntry = tileBoundsMap.entries.firstOrNull { (_, rect) ->
                rect.contains(reticleRootPosition)
            }
            onUpdateFocusedTile(hitEntry?.key)
        }
    }

    // Validation chooses the grid (§3.5 locked product decision):
    // If mean error <= 12.0% and worst-quadrant error <= 16.5% -> 3x3 Grid (9 tiles);
    // otherwise -> 2x2 Protective Large-Region Grid (4 tiles).
    val use3x3Grid = hudState.activeErrorRadiusPct <= 12.0f && hudState.activeMaxQuadrantErrorPct <= 16.5f
    val columnCount = if (use3x3Grid) 3 else 2
    val visibleTiles = if (use3x3Grid) tiles.take(9) else tiles.take(4)

    if (editingTile != null) {
        val currentTile = editingTile!!
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
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = editPhraseText,
                        onValueChange = { editPhraseText = it },
                        label = { Text("Spoken TTS Phrase") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onEditTilePhrase(currentTile.id, editTitleText, editPhraseText)
                        editingTile = null
                    }
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "TTS Output",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (use3x3Grid) "3×3 Validated Deck" else "2×2 Protective Region Deck",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = "Dwell: ${hudState.dwellTriggerMs} ms",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = hudState.lastTriggeredSpeech,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
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
                        text = "Brow Ratio: ${String.format("%.3f", hudState.rawFeature.browToEyeRatio)} (N:${String.format("%.2f", hudState.neutralBrowRatio)}→R:${String.format("%.2f", hudState.raisedBrowRatio)})",
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
                    Slider(
                        value = hudState.dwellTriggerMs.toFloat(),
                        onValueChange = { onChangeDwellDurationMs(it.toLong()) },
                        valueRange = 800f..1800f,
                        modifier = Modifier
                            .weight(1f)
                            .height(26.dp)
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
