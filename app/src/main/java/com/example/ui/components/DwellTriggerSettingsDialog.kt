package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.DwellSpeedPreset
import com.example.data.model.GazeFilterMode
import com.example.data.model.GazePostureProfile
import com.example.domain.BenchmarkScorecard
import com.example.domain.ReplayScenario
import com.example.ui.viewmodel.LiveGazeHudState
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Dwell duration slider range shared by every dwell slider, so all presets (650..1900 ms) fit. */
val DWELL_DURATION_SLIDER_RANGE_MS: ClosedFloatingPointRange<Float> = 400f..2200f

/** Replay categories that have a pass criterion; the others are replayed without one. */
private val GATED_BENCHMARK_CATEGORIES = setOf("MIDAS_TOUCH", "COMMAND")

/**
 * One-line summary of the last synthetic replay run, built only from values the run produced:
 * the pass/fail verdict, how many gated scenarios passed, the false-trigger counts (the per-minute
 * rate is the report's Midas-touch rate, so it is shown next to the Midas-touch count it belongs to),
 * the measured post-feature processing latency and the forward-model projection check. It is a JVM
 * replay of synthetic feature traces, not an on-device camera, latency, accuracy or thermal
 * measurement, and says so.
 */
fun formatBenchmarkSummaryLine(report: BenchmarkScorecard): String {
    val gated = report.scenarioResults.filter { it.scenario.category in GATED_BENCHMARK_CATEGORIES }
    val ungatedCount = report.scenarioResults.size - gated.size
    val falseTriggers = report.scenarioResults.sumOf { it.falseActivations }
    val verdict = if (report.allScenariosPassed) "PASS" else "FAILURES DETECTED"
    return String.format(
        Locale.US,
        "Latest synthetic replay: %s • %d/%d gated scenarios passed, %d replayed without pass criteria • " +
            "%d false triggers (%d in Midas-touch scenarios, %.1f/min) • " +
            "post-feature JVM processing median %.3f ms (excl. camera + face detection) • " +
            "synthetic projection check %.1f px (%.2f°), not measured gaze accuracy.",
        verdict,
        gated.count { it.passed },
        gated.size,
        ungatedCount,
        falseTriggers,
        report.midasFalseActivationCount,
        report.falseActivationsPerMinute,
        report.latencyMedianMs,
        report.meanAccuracyPx,
        report.meanAccuracyDeg
    )
}

@Composable
fun DwellTriggerSettingsDialog(
    hudState: LiveGazeHudState,
    onDismissRequest: () -> Unit,
    onChangeDurationMs: (Long) -> Unit,
    onChangeSensitivityMultiplier: (Float) -> Unit,
    onSelectPreset: (DwellSpeedPreset) -> Unit,
    onSelectPosture: (GazePostureProfile) -> Unit = {},
    onSelectFilterMode: (GazeFilterMode) -> Unit = {},
    onRunBenchmark: () -> Unit = {}
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.testTag("dwell_settings_dialog"),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = "Tracking & Dwell Settings",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Gaze, Dwell & Posture Engine",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Configure 1€ Filter jitter suppression, posture compensation profiles, and dwell thresholds for Google Pixel 10 Pro XL.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 1. Posture Compensation Profile (Upright, Reclining, Lying Down)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Posture Profile & Gravity Compensation",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            GazePostureProfile.entries.forEach { posture ->
                                val isSelected = hudState.activePosture == posture
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSelectPosture(posture) },
                                    label = { Text(posture.shortName, style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.testTag("posture_chip_${posture.name.lowercase()}")
                                )
                            }
                        }
                    }
                }

                // 2. Gaze Stabilization Filter Mode (One Euro vs Kalman vs Raw)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.FilterAlt,
                                contentDescription = "Gaze Filter Mode",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Gaze Stabilization Filter",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            GazeFilterMode.entries.forEach { mode ->
                                val isSelected = hudState.filterMode == mode
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSelectFilterMode(mode) },
                                    label = { Text(mode.shortName, style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.testTag("filter_mode_chip_${mode.name.lowercase()}")
                                )
                            }
                        }
                        Text(
                            text = "Quiet background drift updates: ${hudState.backgroundRecalibrationCount} micro-bias adjustments absorbed.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 3. Dwell Speed Presets Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = "Speed Presets",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Eye-Tracking Speed Presets",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        DwellSpeedPreset.entries.forEach { preset ->
                            // A preset is only "selected" while both of its values are in effect;
                            // a hand-edited duration or boundary tolerance is no longer that preset.
                            val isSelected = hudState.activeDwellPreset == preset &&
                                hudState.dwellTriggerMs == preset.durationMs &&
                                abs(hudState.dwellSensitivityRadiusMultiplier - preset.radiusToleranceMultiplier) < 0.01f
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                        else MaterialTheme.colorScheme.surface
                                    )
                                    .border(
                                        width = if (isSelected) 1.5.dp else 0.5.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .clickable { onSelectPreset(preset) }
                                    .padding(horizontal = 10.dp, vertical = 7.dp)
                                    .testTag("dwell_preset_${preset.name.lowercase()}"),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = preset.title,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = preset.subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 4. Custom Duration Threshold Slider (400 ms .. 2200 ms)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Timelapse,
                                contentDescription = "Duration Threshold",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Duration Threshold:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = "${hudState.dwellTriggerMs} ms",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("dwell_duration_label")
                        )
                    }

                    // Tick values come back from the slider as float lerps (e.g. 1899.9999f for the
                    // 1900 ms tick), so round instead of truncating.
                    Slider(
                        value = hudState.dwellTriggerMs.toFloat(),
                        onValueChange = { onChangeDurationMs(it.roundToLong()) },
                        valueRange = DWELL_DURATION_SLIDER_RANGE_MS,
                        steps = 17,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dwell_duration_slider")
                    )
                }

                // 5. Tile Lock Sensitivity & Boundary Tolerance Slider (0.6x .. 2.0x)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Lock Sensitivity (Boundary Margin):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = String.format(Locale.US, "%.2f× (%d dp)", hudState.dwellSensitivityRadiusMultiplier, (14f * hudState.dwellSensitivityRadiusMultiplier).roundToInt()),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.testTag("dwell_sensitivity_label")
                        )
                    }

                    Slider(
                        value = hudState.dwellSensitivityRadiusMultiplier,
                        onValueChange = { onChangeSensitivityMultiplier(it) },
                        valueRange = 0.6f..2.0f,
                        steps = 13,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dwell_sensitivity_slider")
                    )
                }

                // 6. In-App Benchmark Mode Trigger & Report Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Assessment,
                                contentDescription = "Benchmark Suite",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Synthetic Replay Self-Test",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        Text(
                            text = "Replays ${ReplayScenario.entries.size} synthetic feature traces (dim light, glasses glare, rapid blinks, continuous speech, a long-session stream) through the filters, gaze projection and blink/brow detectors. Not an on-device camera, latency or thermal measurement.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Button(
                            onClick = onRunBenchmark,
                            enabled = !hudState.isBenchmarkRunning,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("run_benchmark_button")
                        ) {
                            if (hudState.isBenchmarkRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Running Benchmark...")
                            } else {
                                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Run Automated Benchmark Suite")
                            }
                        }

                        val report = hudState.lastBenchmarkReport
                        if (report != null) {
                            Text(
                                text = formatBenchmarkSummaryLine(report),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (report.allScenariosPassed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("benchmark_summary_text")
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismissRequest,
                modifier = Modifier.testTag("dismiss_dwell_settings_button")
            ) {
                Text("Apply & Close")
            }
        }
    )
}
