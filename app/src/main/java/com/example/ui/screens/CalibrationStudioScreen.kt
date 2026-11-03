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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.GazeCalibrationMath
import com.example.ui.viewmodel.CalibrationFlowStep
import com.example.ui.viewmodel.CalibrationWizardState
import com.example.ui.viewmodel.LiveGazeHudState
import java.util.Locale

@Composable
fun CalibrationStudioScreen(
    calibrationState: CalibrationWizardState,
    hudState: LiveGazeHudState,
    onStartWizard: () -> Unit,
    onCancelWizard: () -> Unit,
    onUpdateProfileName: (String) -> Unit,
    onSaveProfile: () -> Unit,
    onSnapSimulatorToCurrentTarget: (Float, Float) -> Unit
) {
    when (calibrationState.step) {
        CalibrationFlowStep.IDLE -> {
            CalibrationIdleOverview(
                hudState = hudState,
                onStartWizard = onStartWizard
            )
        }
        CalibrationFlowStep.FIT_5_POINTS,
        CalibrationFlowStep.VALIDATE_4_POINTS -> {
            ActiveTargetCanvas(
                calibrationState = calibrationState,
                hudState = hudState,
                onCancelWizard = onCancelWizard,
                onSnapSimulatorToCurrentTarget = onSnapSimulatorToCurrentTarget
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
    hudState: LiveGazeHudState,
    onStartWizard: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "5-Point Standardized Regression + 4-Point Validation",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Phase A fits a 12-parameter (2×6) Ridge-regularized affine matrix across 5 screen targets using standardized unit-scale features (confidence-weighted iris centroid, 3D head Euler angles, and inter-ocular scale). Phase B evaluates 4 held-out quadrant centroids to measure both Mean Screen Error (%) and Worst-Quadrant Error (%).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricBadgeCard(
                        label = "MEAN SCREEN ERR",
                        value = String.format(Locale.US, "±%.1f%%", hudState.activeErrorRadiusPct),
                        sub = String.format(Locale.US, "~%.1f mm (approx)", hudState.activeErrorMm),
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
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Start Calibration")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Launch 5-Point Fit + 4-Point Validation",
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
                    text = "Live 6D Feature Vector Telemetry",
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
                        "Eye Confidence: Left %.0f%% • Right %.0f%% | Inter-Ocular Dist: %.1f px | Eye Open: %.2f",
                        f.leftEyeConfidence * 100f,
                        f.rightEyeConfidence * 100f,
                        f.eyeDistancePx,
                        f.combinedEyeOpenProb
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ActiveTargetCanvas(
    calibrationState: CalibrationWizardState,
    hudState: LiveGazeHudState,
    onCancelWizard: () -> Unit,
    onSnapSimulatorToCurrentTarget: (Float, Float) -> Unit
) {
    val allTargets = GazeCalibrationMath.CALIBRATION_AND_VALIDATION_TARGETS
    val target = allTargets.getOrNull(calibrationState.currentTargetIndex) ?: allTargets.first()
    val isValidation = target.isValidationPoint
    val targetColor = if (isValidation) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = target.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = targetColor
                    )
                    Text(
                        text = if (calibrationState.isSettlingDelay) {
                            "Settling saccade (400 ms) — keep fixated on pulsing ring..."
                        } else {
                            "Sampling feature vectors (${(calibrationState.currentTargetProgress * 100).toInt()}%)..."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onSnapSimulatorToCurrentTarget(target.normX, target.normY) },
                        modifier = Modifier.testTag("snap_sim_to_target_button")
                    ) {
                        Text("Lock Sim Here", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = onCancelWizard,
                        modifier = Modifier.testTag("cancel_calibration_button")
                    ) {
                        Text("Cancel", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(18.dp))
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val tx = target.normX * size.width
                val ty = target.normY * size.height

                // Outer target ring
                drawCircle(
                    color = targetColor.copy(alpha = 0.22f),
                    radius = 38.dp.toPx(),
                    center = Offset(tx, ty)
                )
                drawCircle(
                    color = targetColor,
                    radius = 26.dp.toPx(),
                    center = Offset(tx, ty),
                    style = Stroke(width = 3.dp.toPx())
                )
                // Progress arc
                drawArc(
                    color = targetColor,
                    startAngle = -90f,
                    sweepAngle = calibrationState.currentTargetProgress * 360f,
                    useCenter = false,
                    topLeft = Offset(tx - 32.dp.toPx(), ty - 32.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(64.dp.toPx(), 64.dp.toPx()),
                    style = Stroke(width = 5.dp.toPx())
                )
                // Bullseye center dot
                drawCircle(
                    color = targetColor,
                    radius = 6.dp.toPx(),
                    center = Offset(tx, ty)
                )
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
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
                        contentDescription = "Validation Complete",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Held-Out 4-Point Validation Report",
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
                        sub = String.format(Locale.US, "~%.1f mm (approx)", calibrationState.validationMeanErrorMm),
                        modifier = Modifier.weight(1f)
                    )
                    MetricBadgeCard(
                        label = "WORST QUADRANT",
                        value = String.format(Locale.US, "±%.1f%%", calibrationState.validationMaxErrorPct),
                        sub = "Governs Tile Sizing",
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

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
