package com.example.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.GazeCalibrationMath
import com.example.ui.viewmodel.CalibrationFlowStep
import com.example.ui.viewmodel.CalibrationWizardState
import com.example.ui.viewmodel.LiveGazeHudState
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun CalibrationStudioScreen(
    calibrationState: CalibrationWizardState,
    hudState: LiveGazeHudState,
    onStartWizard: () -> Unit,
    onCancelWizard: () -> Unit,
    onUpdateProfileName: (String) -> Unit,
    onSaveProfile: () -> Unit,
    onSnapSimulatorToCurrentTarget: (Float, Float) -> Unit,
    onToggleIncludeValidation: (Boolean) -> Unit = {},
    onCaptureManualSamples: () -> Unit = {}
) {
    when (calibrationState.step) {
        CalibrationFlowStep.IDLE -> {
            CalibrationIdleOverview(
                calibrationState = calibrationState,
                hudState = hudState,
                onStartWizard = onStartWizard,
                onToggleIncludeValidation = onToggleIncludeValidation
            )
        }
        CalibrationFlowStep.FIT_5_POINTS,
        CalibrationFlowStep.VALIDATE_4_POINTS -> {
            FivePointCalibrationScreen(
                calibrationState = calibrationState,
                hudState = hudState,
                onCancelWizard = onCancelWizard,
                onSnapSimulatorToCurrentTarget = onSnapSimulatorToCurrentTarget,
                onCaptureManualSamples = onCaptureManualSamples
            )
        }
        CalibrationFlowStep.COMPLETED_SUMMARY -> {
            CalibrationValidationSummaryCard(
                calibrationState = calibrationState,
                onUpdateProfileName = onUpdateProfileName,
                onSaveProfile = onSaveProfile,
                onRecalibrate = onStartWizard
            )
        }
    }
}

@Composable
private fun CalibrationIdleOverview(
    calibrationState: CalibrationWizardState,
    hudState: LiveGazeHudState,
    onStartWizard: () -> Unit,
    onToggleIncludeValidation: (Boolean) -> Unit
) {
    val fiveTargets = GazeCalibrationMath.FIVE_POINT_CALIBRATION_TARGETS
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val outlineColor = MaterialTheme.colorScheme.outline

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("calibration_overview_card"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CenterFocusStrong,
                        contentDescription = stringResource(R.string.calibration_5_point_title),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.calibration_5_point_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.calibration_5_point_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Mode selector: 5-Point Quick Fit vs 5-Point Fit + 4-Point Held-Out Validation
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !calibrationState.includeHeldOutValidation,
                        onClick = { onToggleIncludeValidation(false) },
                        label = {
                            Text(
                                text = stringResource(R.string.calibration_mode_5_point_only),
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        modifier = Modifier.testTag("mode_5_point_only_chip")
                    )
                    FilterChip(
                        selected = calibrationState.includeHeldOutValidation,
                        onClick = { onToggleIncludeValidation(true) },
                        label = {
                            Text(
                                text = stringResource(R.string.calibration_mode_5_plus_4),
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        modifier = Modifier.testTag("mode_5_plus_4_validation_chip")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 5-Point Coordinate Preview Map & Sequence Chips
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(132.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                        .padding(8.dp)
                        .testTag("five_point_preview_map")
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                        for (i in 0 until fiveTargets.size - 1) {
                            val pA = fiveTargets[i]
                            val pB = fiveTargets[i + 1]
                            drawLine(
                                color = outlineColor.copy(alpha = 0.55f),
                                start = Offset(pA.normX * size.width, pA.normY * size.height),
                                end = Offset(pB.normX * size.width, pB.normY * size.height),
                                strokeWidth = 2.dp.toPx(),
                                pathEffect = dash
                            )
                        }
                        fiveTargets.forEachIndexed { idx, pt ->
                            val cx = pt.normX * size.width
                            val cy = pt.normY * size.height
                            val c = if (idx == 0) secondaryColor else primaryColor
                            drawCircle(
                                color = c.copy(alpha = 0.22f),
                                radius = 14.dp.toPx(),
                                center = Offset(cx, cy)
                            )
                            drawCircle(
                                color = c,
                                radius = 9.dp.toPx(),
                                center = Offset(cx, cy),
                                style = Stroke(width = 2.dp.toPx())
                            )
                            drawCircle(
                                color = c,
                                radius = 3.5.dp.toPx(),
                                center = Offset(cx, cy)
                            )
                        }
                    }
                    Text(
                        text = "Sequence: P1(0.50,0.50) → P2(0.14,0.16) → P3(0.86,0.16) → P4(0.14,0.84) → P5(0.86,0.84)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricBadgeCard(
                        label = "MEAN SCREEN ERR",
                        value = String.format(Locale.US, "±%.1f%%", hudState.activeErrorRadiusPct),
                        sub = String.format(Locale.US, "~%.1f mm (approximate)", hudState.activeErrorMm),
                        modifier = Modifier.weight(1f)
                    )
                    MetricBadgeCard(
                        label = "WORST QUADRANT",
                        value = String.format(Locale.US, "±%.1f%%", hudState.activeMaxQuadrantErrorPct),
                        sub = "Profile: ${hudState.activeProfileName.take(16)}",
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onStartWizard,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("start_calibration_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.start_5_point_calibration)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (calibrationState.includeHeldOutValidation) {
                            "Launch 5-Point Fit + 4-Point Validation"
                        } else {
                            stringResource(R.string.start_5_point_calibration)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Live 6D Feature Vector & Pixel 10 Pro XL Telemetry",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                val f = hudState.rawFeature
                Text(
                    text = String.format(
                        Locale.US,
                        "f_raw = [1.0, gx=%.3f, gy=%.3f, yaw=%.1f°, pitch=%.1f°, roll=%.1f°]",
                        f.gx, f.gy, f.yaw, f.pitch, f.roll
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = String.format(
                        Locale.US,
                        "Eye Confidence: Left %.0f%% • Right %.0f%% | IOD: %.1f px | Eye Open: %.2f | Tier: %s | Luma: %d/255%s",
                        f.leftEyeConfidence * 100f,
                        f.rightEyeConfidence * 100f,
                        f.eyeDistancePx,
                        f.combinedEyeOpenProb,
                        hudState.resolutionTier.shortLabel,
                        f.ambientLuma,
                        if (f.isLowLightDegraded) " (LOW-LIGHT DEGRADED)" else ""
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Dedicated 5-Point Guided Calibration Screen in Jetpack Compose.
 * Guides the user sequentially across 5 normalized screen coordinates:
 * - P1 Center (0.50, 0.50)
 * - P2 Top-Left (0.14, 0.16)
 * - P3 Top-Right (0.86, 0.16)
 * - P4 Bottom-Left (0.14, 0.84)
 * - P5 Bottom-Right (0.86, 0.84)
 * (Plus optional 4 held-out validation quadrant coordinates when enabled.)
 */
@Composable
fun FivePointCalibrationScreen(
    calibrationState: CalibrationWizardState,
    hudState: LiveGazeHudState,
    onCancelWizard: () -> Unit,
    onSnapSimulatorToCurrentTarget: (Float, Float) -> Unit,
    onCaptureManualSamples: () -> Unit = {}
) {
    val allTargets = GazeCalibrationMath.CALIBRATION_AND_VALIDATION_TARGETS
    val fiveFitTargets = GazeCalibrationMath.FIVE_POINT_CALIBRATION_TARGETS
    val target = allTargets.getOrNull(calibrationState.currentTargetIndex) ?: allTargets.first()
    val isValidation = target.isValidationPoint
    val targetColor = if (isValidation) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    val completedColor = MaterialTheme.colorScheme.secondary
    val pendingColor = MaterialTheme.colorScheme.outline

    val infiniteTransition = rememberInfiniteTransition(label = "calibration_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.86f,
        targetValue = 1.24f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_ring_scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
            .testTag("five_point_calibration_screen")
    ) {
        // Step-by-Step Guidance Header Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("calibration_guidance_card"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = String.format(
                                Locale.US,
                                "%s • Screen Coord (%.2f, %.2f)",
                                target.label,
                                target.normX,
                                target.normY
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = targetColor,
                            modifier = Modifier.testTag("active_calibration_target_label")
                        )
                        Text(
                            text = target.guidancePrompt,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (calibrationState.isSettlingDelay) {
                                "Settling saccade (400 ms) — keep eyes fixated on pulsing center dot..."
                            } else {
                                String.format(
                                    Locale.US,
                                    "Collecting gaze samples: %d/%d at point (%d%%) • Total collected: %d pts",
                                    calibrationState.samplesCollectedForCurrentTarget,
                                    calibrationState.requiredSamplesPerTarget,
                                    (calibrationState.currentTargetProgress * 100).toInt(),
                                    calibrationState.totalCollectedSamples
                                )
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("calibration_sample_counter_text")
                        )
                    }
                }

                // 5-Point Coordinate Stepper Pills
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("calibration_5_point_stepper"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    fiveFitTargets.forEach { pt ->
                        val isCompleted = calibrationState.currentTargetIndex > pt.index
                        val isCurrent = calibrationState.currentTargetIndex == pt.index
                        val count = calibrationState.pointSampleCounts[pt.index] ?: 0
                        val bg = when {
                            isCurrent -> targetColor.copy(alpha = 0.22f)
                            isCompleted -> completedColor.copy(alpha = 0.16f)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                        val borderCol = when {
                            isCurrent -> targetColor
                            isCompleted -> completedColor
                            else -> pendingColor.copy(alpha = 0.5f)
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(bg)
                                .border(1.dp, borderCol, RoundedCornerShape(8.dp))
                                .padding(vertical = 4.dp, horizontal = 4.dp)
                                .testTag("calibration_step_pill_${pt.index}"),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (isCompleted) "✓ P${pt.index + 1}" else "P${pt.index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                color = if (isCurrent) targetColor else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = String.format(Locale.US, "(%.2f,%.2f)", pt.normX, pt.normY),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "$count/${calibrationState.requiredSamplesPerTarget}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isCompleted) completedColor else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Action controls for Simulator Lock, Manual Capture, and Cancel
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            onSnapSimulatorToCurrentTarget(target.normX, target.normY)
                            onCaptureManualSamples()
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .weight(1.2f)
                            .testTag("capture_gaze_point_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = stringResource(R.string.capture_gaze_sample_action),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${stringResource(R.string.capture_gaze_sample_action)} (${target.shortName})",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                    OutlinedButton(
                        onClick = { onSnapSimulatorToCurrentTarget(target.normX, target.normY) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("snap_sim_to_target_button")
                    ) {
                        Text("Lock Sim Here", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = onCancelWizard,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("cancel_calibration_button")
                    ) {
                        Text(stringResource(R.string.cancel_action), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Interactive 5-Point Coordinate Canvas
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
                .testTag("five_point_calibration_canvas")
        ) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { maxHeight.toPx() }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)

                // Subtle reference crosshair lines at screen midpoint (0.50, 0.50)
                drawLine(
                    color = pendingColor.copy(alpha = 0.18f),
                    start = Offset(size.width * 0.5f, 0f),
                    end = Offset(size.width * 0.5f, size.height),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = pendingColor.copy(alpha = 0.18f),
                    start = Offset(0f, size.height * 0.5f),
                    end = Offset(size.width, size.height * 0.5f),
                    strokeWidth = 1.dp.toPx()
                )

                // Draw sequential guide path across the 5 calibration targets
                for (i in 0 until fiveFitTargets.size - 1) {
                    val fromPt = fiveFitTargets[i]
                    val toPt = fiveFitTargets[i + 1]
                    val isActiveTransition = calibrationState.currentTargetIndex == toPt.index
                    drawLine(
                        color = if (isActiveTransition) {
                            targetColor.copy(alpha = 0.55f)
                        } else {
                            pendingColor.copy(alpha = 0.25f)
                        },
                        start = Offset(fromPt.normX * size.width, fromPt.normY * size.height),
                        end = Offset(toPt.normX * size.width, toPt.normY * size.height),
                        strokeWidth = if (isActiveTransition) 2.5.dp.toPx() else 1.5.dp.toPx(),
                        pathEffect = dashEffect
                    )
                }

                // Draw all 5 calibration screen coordinates (completed, pending, and active)
                fiveFitTargets.forEach { pt ->
                    val cx = pt.normX * size.width
                    val cy = pt.normY * size.height
                    val isDone = calibrationState.currentTargetIndex > pt.index
                    val isCurrent = calibrationState.currentTargetIndex == pt.index

                    if (isDone) {
                        drawCircle(
                            color = completedColor.copy(alpha = 0.20f),
                            radius = 18.dp.toPx(),
                            center = Offset(cx, cy)
                        )
                        drawCircle(
                            color = completedColor,
                            radius = 12.dp.toPx(),
                            center = Offset(cx, cy),
                            style = Stroke(width = 2.5.dp.toPx())
                        )
                        drawCircle(
                            color = completedColor,
                            radius = 4.5.dp.toPx(),
                            center = Offset(cx, cy)
                        )
                    } else if (!isCurrent) {
                        drawCircle(
                            color = pendingColor.copy(alpha = 0.38f),
                            radius = 13.dp.toPx(),
                            center = Offset(cx, cy),
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                        drawCircle(
                            color = pendingColor.copy(alpha = 0.45f),
                            radius = 3.dp.toPx(),
                            center = Offset(cx, cy)
                        )
                    }
                }

                // Draw active target (either one of the 5 fitting points or 4 validation points)
                val tx = target.normX * size.width
                val ty = target.normY * size.height

                // Live gaze line from current estimated screen gaze to active target coordinate
                val gx = hudState.normX * size.width
                val gy = hudState.normY * size.height
                drawLine(
                    color = targetColor.copy(alpha = 0.30f),
                    start = Offset(gx, gy),
                    end = Offset(tx, ty),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = dashEffect
                )

                // Animated pulsing outer halo guiding fixation to (tx, ty)
                drawCircle(
                    color = targetColor.copy(alpha = 0.22f),
                    radius = (38.dp.toPx() * pulseScale),
                    center = Offset(tx, ty)
                )
                drawCircle(
                    color = targetColor,
                    radius = 26.dp.toPx(),
                    center = Offset(tx, ty),
                    style = Stroke(width = 3.dp.toPx())
                )
                // Sample collection 360° progress arc
                drawArc(
                    color = targetColor,
                    startAngle = -90f,
                    sweepAngle = calibrationState.currentTargetProgress * 360f,
                    useCenter = false,
                    topLeft = Offset(tx - 32.dp.toPx(), ty - 32.dp.toPx()),
                    size = Size(64.dp.toPx(), 64.dp.toPx()),
                    style = Stroke(width = 5.dp.toPx())
                )
                // High-contrast bullseye center dot
                drawCircle(
                    color = targetColor,
                    radius = 6.5.dp.toPx(),
                    center = Offset(tx, ty)
                )
            }

            // Accessible interactive target nodes at each of the 5 screen coordinates
            val targetHitSize = 48.dp
            val halfHitPx = with(density) { (targetHitSize / 2).toPx() }
            fiveFitTargets.forEach { pt ->
                val xOffsetPx = (pt.normX * widthPx - halfHitPx).roundToInt()
                val yOffsetPx = (pt.normY * heightPx - halfHitPx).roundToInt()
                val isCurrent = calibrationState.currentTargetIndex == pt.index
                Box(
                    modifier = Modifier
                        .offset { IntOffset(xOffsetPx, yOffsetPx) }
                        .size(targetHitSize)
                        .clip(CircleShape)
                        .clickable {
                            onSnapSimulatorToCurrentTarget(pt.normX, pt.normY)
                            if (isCurrent) {
                                onCaptureManualSamples()
                            }
                        }
                        .semantics {
                            contentDescription = "${pt.shortName} coordinate (${pt.normX}, ${pt.normY})"
                        }
                        .testTag("calibration_point_target_${pt.index}"),
                    contentAlignment = Alignment.Center
                ) {}
            }
        }
    }
}

@Composable
private fun CalibrationValidationSummaryCard(
    calibrationState: CalibrationWizardState,
    onUpdateProfileName: (String) -> Unit,
    onSaveProfile: () -> Unit,
    onRecalibrate: () -> Unit
) {
    val fiveFitTargets = GazeCalibrationMath.FIVE_POINT_CALIBRATION_TARGETS

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .testTag("calibration_summary_screen"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Calibration Complete",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (calibrationState.includeHeldOutValidation) {
                            "5-Point Fit + 4-Point Validation Report"
                        } else {
                            "5-Point Screen Calibration Complete"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricBadgeCard(
                        label = "MEAN SCREEN ERROR",
                        value = String.format(Locale.US, "±%.1f%%", calibrationState.validationMeanErrorPct),
                        sub = String.format(Locale.US, "~%.1f mm (approximate)", calibrationState.validationMeanErrorMm),
                        modifier = Modifier.weight(1f)
                    )
                    MetricBadgeCard(
                        label = "WORST QUADRANT",
                        value = String.format(Locale.US, "±%.1f%%", calibrationState.validationMaxErrorPct),
                        sub = "Total Samples: ${calibrationState.totalCollectedSamples}",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Per-coordinate collected gaze sample breakdown for all 5 screen coordinates
                Text(
                    text = "Collected Gaze Samples by Screen Coordinate:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))
                fiveFitTargets.forEach { pt ->
                    val count = calibrationState.pointSampleCounts[pt.index] ?: calibrationState.requiredSamplesPerTarget
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = String.format(Locale.US, "%s — Coord (%.2f, %.2f)", pt.shortName, pt.normX, pt.normY),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "$count gaze samples",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Recommended Layout: ${calibrationState.recommendedGridLabel}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary
                )

                Spacer(modifier = Modifier.height(8.dp))

                val m = calibrationState.fittedMatrix
                if (m.size == 12) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "W_x = [%.2f, %.2f, %.2f, %.2f, %.2f, %.2f]\nW_y = [%.2f, %.2f, %.2f, %.2f, %.2f, %.2f]",
                            m[0], m[1], m[2], m[3], m[4], m[5],
                            m[6], m[7], m[8], m[9], m[10], m[11]
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = calibrationState.draftProfileName,
                    onValueChange = onUpdateProfileName,
                    label = { Text("Calibration Profile Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("profile_name_input")
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onRecalibrate,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("recalibrate_button")
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = "Recalibrate")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Retry")
                    }
                    Button(
                        onClick = onSaveProfile,
                        modifier = Modifier
                            .weight(1.4f)
                            .height(48.dp)
                            .testTag("save_calibration_button")
                    ) {
                        Icon(imageVector = Icons.Default.Save, contentDescription = "Save Profile")
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save & Apply Profile")
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricBadgeCard(
    label: String,
    value: String,
    sub: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = sub,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
