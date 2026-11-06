package com.example.ui.components

import android.util.Size
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraFront
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.AnalysisResolutionTier
import com.example.data.model.GazeFeatureVector
import com.example.data.model.GazeOverlayVisualMode
import com.example.domain.CameraGazeAnalyzerEngine
import com.example.ui.viewmodel.LiveGazeHudState
import com.example.ui.viewmodel.isRunningOnEmulatorHardware
import java.util.concurrent.Executors
import kotlin.math.roundToInt

@Composable
fun OpticalDiagnosticsBar(
    hudState: LiveGazeHudState,
    hasCameraPermission: Boolean,
    onRequestCameraPermission: () -> Unit,
    onToggleSimulator: (Boolean) -> Unit,
    onCycleOverlayMode: () -> Unit = {},
    onCycleResolutionTier: () -> Unit = {},
    onSimulatorDragTarget: (Float, Float) -> Unit,
    onSimulateCommandBlink: () -> Unit,
    onSimulateBrowRaise: () -> Unit = {},
    onTriggerRecalibration: () -> Unit = {},
    onDismissRecalibrateNudge: () -> Unit = {},
    onFeatureFrame: (GazeFeatureVector) -> Unit
) {
    var showSimulatorPad by remember { mutableStateOf(false) }
    var expandCameraPreview by remember { mutableStateOf(true) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
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
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                if (hudState.rawFeature.faceDetected) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.tertiary
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = if (hudState.isSimulatorProxyEnabled) {
                                    "SIM PROXY • ${hudState.analyzerFps} FPS"
                                } else if (hudState.rawFeature.faceDetected) {
                                    "OPTICAL LOCK • ${hudState.analyzerFps} FPS"
                                } else {
                                    "SEARCHING FACE... • ${hudState.analyzerFps} FPS"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${hudState.resolutionTier.targetWidth}×${hudState.resolutionTier.targetHeight}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
                                    .pointerInput(Unit) {
                                        detectTapGestures { onCycleResolutionTier() }
                                    }
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                                    .testTag("resolution_tier_badge")
                            )
                            if (hudState.rawFeature.isSingleEyeMode) {
                                Text(
                                    text = hudState.rawFeature.activeEyeLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                            if (hudState.isPerformanceModeActive) {
                                Text(
                                    text = "PERF MODE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        val lConf = (hudState.rawFeature.leftEyeConfidence * 100).roundToInt()
                        val rConf = (hudState.rawFeature.rightEyeConfidence * 100).roundToInt()
                        Text(
                            text = "Gaze (${(hudState.normX * 100).roundToInt()}%, ${(hudState.normY * 100).roundToInt()}%) • L:$lConf% R:$rConf% • ±${String.format(java.util.Locale.US, "%.1f", hudState.activeErrorRadiusPct)}% • Luma:${hudState.rawFeature.ambientLuma}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = expandCameraPreview,
                        onClick = { expandCameraPreview = !expandCameraPreview },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.CameraFront,
                                contentDescription = "Toggle Camera Viewfinder Size",
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        label = {
                            Text(
                                text = if (expandCameraPreview) "Cam Full" else "Cam Mini",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.testTag("toggle_camera_feed_size_chip")
                    )
                    FilterChip(
                        selected = true,
                        onClick = onCycleOverlayMode,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Layers,
                                contentDescription = "Overlay Mode",
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        label = {
                            Text(
                                text = hudState.overlayVisualMode.shortLabel,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.testTag("toggle_overlay_mode_chip")
                    )
                    FilterChip(
                        selected = hudState.isSimulatorProxyEnabled || showSimulatorPad,
                        onClick = {
                            val next = !hudState.isSimulatorProxyEnabled
                            onToggleSimulator(next)
                            showSimulatorPad = next
                        },
                        label = {
                            Text(
                                text = if (hudState.isSimulatorProxyEnabled) "Sim Active" else "Sim Proxy",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.testTag("toggle_simulator_chip")
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Real-Time CameraX PreviewView + ML Kit Face Detection Pipeline Monitor in Main UI
            if (hasCameraPermission) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .border(
                            width = 1.dp,
                            color = if (hudState.rawFeature.faceDetected && !hudState.isSimulatorProxyEnabled) {
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.65f)
                            } else {
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                            },
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val previewW = if (expandCameraPreview) 116.dp else 56.dp
                    val previewH = if (expandCameraPreview) 150.dp else 72.dp

                    Box(
                        modifier = Modifier
                            .size(width = previewW, height = previewH)
                            .clip(RoundedCornerShape(10.dp))
                            .border(
                                width = 1.5.dp,
                                color = if (hudState.rawFeature.faceDetected && !hudState.isSimulatorProxyEnabled) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                shape = RoundedCornerShape(10.dp)
                            )
                            .pointerInput(hudState.isSimulatorProxyEnabled) {
                                detectTapGestures {
                                    if (hudState.isSimulatorProxyEnabled) {
                                        onToggleSimulator(false)
                                        showSimulatorPad = false
                                    } else {
                                        expandCameraPreview = !expandCameraPreview
                                    }
                                }
                            }
                            .testTag("live_camera_preview_box")
                    ) {
                        MiniFrontCameraPreview(
                            isPerformanceMode = hudState.isPerformanceModeActive,
                            resolutionTier = hudState.resolutionTier,
                            onFeatureFrame = { frame ->
                                if (!hudState.isSimulatorProxyEnabled) {
                                    onFeatureFrame(frame)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Real-time ML Kit Face Bounding Box, Eye ROI, Pupil Centroid & Brow Overlay
                        MlKitFaceOverlayCanvas(
                            feature = hudState.rawFeature,
                            isSimulatorActive = hudState.isSimulatorProxyEnabled,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val f = hudState.rawFeature
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (hudState.isSimulatorProxyEnabled) {
                                    "CAMERAX STANDBY (TAP PREVIEW FOR LIVE)"
                                } else {
                                    "PIXEL 10 PRO XL 42MP (~103° FOV) PIPELINE"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (f.faceDetected && !hudState.isSimulatorProxyEnabled) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                            Text(
                                text = "${f.frameWidthPx}×${f.frameHeightPx} @ ${f.sensorRotationDeg}°",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Text(
                            text = String.format(
                                java.util.Locale.US,
                                "Iris Centroid: gx=%+.3f, gy=%+.3f • IOD=%.0f px",
                                f.gx,
                                f.gy,
                                f.eyeDistancePx
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Text(
                            text = String.format(
                                java.util.Locale.US,
                                "Head Euler: Yaw %+.1f° • Pitch %+.1f° • Roll %+.1f°",
                                f.yaw,
                                f.pitch,
                                f.roll
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (expandCameraPreview) {
                            Text(
                                text = String.format(
                                    java.util.Locale.US,
                                    "Eye Open: L %.0f%% / R %.0f%% • Brow Ratio: %.2f (Trigger ≥ %.2f)",
                                    f.leftEyeOpenProb * 100f,
                                    f.rightEyeOpenProb * 100f,
                                    f.browToEyeRatio,
                                    (hudState.neutralBrowRatio + hudState.raisedBrowRatio) * 0.5f
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (hudState.isSimulatorProxyEnabled) {
                                OutlinedButton(
                                    onClick = {
                                        onToggleSimulator(false)
                                        showSimulatorPad = false
                                    },
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CameraFront,
                                        contentDescription = "Switch to Live Camera",
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Switch to Live Front Camera", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraFront,
                            contentDescription = "Camera Permission Required",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Grant front-camera access to enable the live CameraX PreviewView & ML Kit face-tracking feed.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onRequestCameraPermission,
                        modifier = Modifier.testTag("request_camera_button"),
                        contentPadding = ButtonDefaults.ContentPadding
                    ) {
                        Text("Enable Camera", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // Honest Low-Light Degraded Mode Banner (Pixel 10 Pro XL has no IR illuminator or front depth sensor)
            if (hudState.rawFeature.isLowLightDegraded) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("low_light_degraded_banner"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Low-Light Degraded Mode",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "LOW-LIGHT DEGRADED MODE (Luma ${hudState.rawFeature.ambientLuma}/255): No IR illuminator on Pixel 10 Pro XL — visible-light iris contrast is physics-limited; damping noisy iris offsets and prioritizing head-pose + region dwell.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // Per-eye glare or >15% head-distance drift warnings
            if (hudState.rawFeature.hasGlareWarning || hudState.hasDistanceDriftWarning) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Optical Warning",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val warningText = buildString {
                        if (hudState.rawFeature.hasGlareWarning) {
                            append("Eyeglass specular glare detected (using unoccluded eye). ")
                        }
                        if (hudState.hasDistanceDriftWarning) {
                            append("Head distance (IOD) shifted >15% from calibration baseline.")
                        }
                    }
                    Text(
                        text = warningText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            // Non-blocking Recalibration Nudge when gaze is pinned at screen edge or geometry drifted
            AnimatedVisibility(visible = hudState.showRecalibrateNudge) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Handheld geometry shifted — Recalibrate without losing session?",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Row {
                        TextButton(
                            onClick = onTriggerRecalibration,
                            modifier = Modifier.testTag("recalibrate_nudge_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Recalibrate",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Recalibrate", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = onDismissRecalibrateNudge) {
                            Text("Ignore", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            // Expression & Blink Hysteresis Hold Bar
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = hudState.lastBlinkLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Brow:${hudState.browRaiseTriggerCount} • Cmd:${hudState.intentionalBlinkCount} • Nat:${hudState.naturalBlinkCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            val holdProgress = maxOf(hudState.blinkHoldProgress, hudState.browHoldProgress)
            if (holdProgress > 0f) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { holdProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.secondary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            // Interactive Simulator Touchpad for Cloud Emulator / Desktop Testing
            AnimatedVisibility(visible = hudState.isSimulatorProxyEnabled || showSimulatorPad) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Emulator Proxy Pad (Drag gaze • Trigger Brow Raise or Command Blink):",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        OutlinedButton(
                            onClick = onSimulateBrowRaise,
                            modifier = Modifier.testTag("simulate_brow_raise_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Face,
                                contentDescription = "Simulate Brow Raise",
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Brow (400ms)", style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        OutlinedButton(
                            onClick = onSimulateCommandBlink,
                            modifier = Modifier.testTag("simulate_command_blink_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = "Simulate Command Blink",
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Blink (460ms)", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(74.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.background)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
                            .pointerInput(Unit) {
                                detectTapGestures { offset ->
                                    val nx = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0.05f, 0.95f)
                                    val ny = (offset.y / size.height.coerceAtLeast(1)).coerceIn(0.05f, 0.95f)
                                    onSimulatorDragTarget(nx, ny)
                                }
                            }
                            .pointerInput(Unit) {
                                detectDragGestures { change, _ ->
                                    change.consume()
                                    val nx = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0.05f, 0.95f)
                                    val ny = (change.position.y / size.height.coerceAtLeast(1)).coerceIn(0.05f, 0.95f)
                                    onSimulatorDragTarget(nx, ny)
                                }
                            }
                            .testTag("simulator_touchpad")
                    ) {
                        val primaryColor = MaterialTheme.colorScheme.primary
                        val secondaryColor = MaterialTheme.colorScheme.secondary
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val cx = hudState.normX * size.width
                            val cy = hudState.normY * size.height
                            drawCircle(
                                color = primaryColor.copy(alpha = 0.25f),
                                radius = 18.dp.toPx(),
                                center = Offset(cx, cy)
                            )
                            drawCircle(
                                color = secondaryColor,
                                radius = 5.dp.toPx(),
                                center = Offset(cx, cy)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniFrontCameraPreview(
    isPerformanceMode: Boolean,
    resolutionTier: AnalysisResolutionTier,
    onFeatureFrame: (GazeFeatureVector) -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnFeatureFrame by androidx.compose.runtime.rememberUpdatedState(onFeatureFrame)
    val latestResolutionTier by androidx.compose.runtime.rememberUpdatedState(resolutionTier)
    val isEmulator = remember { isRunningOnEmulatorHardware() }
    val analyzer = remember {
        CameraGazeAnalyzerEngine { vector ->
            latestOnFeatureFrame(vector)
        }
    }

    var boundCameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var boundImageAnalysis by remember { mutableStateOf<ImageAnalysis?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var boundTier by remember { mutableStateOf<AnalysisResolutionTier?>(null) }

    LaunchedEffect(isPerformanceMode) {
        analyzer.isPerformanceModeEnabled = isPerformanceMode
    }

    fun bindCameraUseCases(view: PreviewView, tier: AnalysisResolutionTier) {
        if (isEmulator || !view.isAttachedToWindow || view.width <= 0 || view.height <= 0) {
            return
        }
        if (boundTier == tier && boundCameraProvider != null) {
            return
        }
        val ctx = view.context
        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
        cameraProviderFuture.addListener({
            try {
                if (!view.isAttachedToWindow) return@addListener
                val cameraProvider = cameraProviderFuture.get()
                boundCameraProvider = cameraProvider
                boundTier = tier

                val previewResolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                // Pixel 10 Pro XL 42 MP autofocus front camera (~103° wide FOV):
                // Deliberately request high-resolution analysis frames (1280×960 default) so iris
                // detail at arm's length is preserved, and step down (1280 -> 960 -> 640) when FPS < 15.
                val analysisResolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(tier.targetWidth, tier.targetHeight),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val preview = Preview.Builder()
                    .setResolutionSelector(previewResolutionSelector)
                    .build()
                    .also {
                        it.surfaceProvider = view.surfaceProvider
                    }

                boundImageAnalysis?.clearAnalyzer()
                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(analysisResolutionSelector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also {
                        it.setAnalyzer(analyzer.analysisExecutor, analyzer)
                    }
                boundImageAnalysis = imageAnalysis

                val selector = when {
                    cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
                        CameraSelector.DEFAULT_BACK_CAMERA
                    else -> CameraSelector.DEFAULT_FRONT_CAMERA
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                android.util.Log.w("ÆyeCamera", "CameraX bind skipped or failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    LaunchedEffect(resolutionTier, previewViewRef) {
        val view = previewViewRef ?: return@LaunchedEffect
        bindCameraUseCases(view, resolutionTier)
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                boundImageAnalysis?.clearAnalyzer()
                boundCameraProvider?.unbindAll()
            } catch (_: Exception) {
            }
            analyzer.close()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            previewViewRef = previewView

            if (!isEmulator) {
                previewView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    if (v is PreviewView && v.width > 0 && v.height > 0) {
                        bindCameraUseCases(v, latestResolutionTier)
                    }
                }
                previewView.post {
                    bindCameraUseCases(previewView, latestResolutionTier)
                }
            }
            previewView
        }
    )
}

/**
 * Real-time ML Kit Face Detection Overlay rendered directly over the CameraX `PreviewView`.
 * Maps normalized upright mirrored coordinates `[0, 1]` through the exact `ScaleType.FILL_CENTER`
 * aspect-fill transform (`frameWidthPx × frameHeightPx`) to draw:
 * 1. ML Kit Face Bounding Box with corner brackets (`#10B981` Emerald when locked).
 * 2. Left & Right Eye ROI rings and `#00E5FF` Cyan dark-iris pupil centroid crosshairs.
 * 3. Left & Right Eyebrow top contour bars for visual brow-raise feedback.
 */
@Composable
private fun MlKitFaceOverlayCanvas(
    feature: GazeFeatureVector,
    isSimulatorActive: Boolean,
    modifier: Modifier = Modifier
) {
    val isEmulator = remember { isRunningOnEmulatorHardware() }
    Canvas(modifier = modifier) {
        val dstW = size.width
        val dstH = size.height
        if (dstW <= 1f || dstH <= 1f) return@Canvas

        val srcW = feature.frameWidthPx.toFloat().coerceAtLeast(1f)
        val srcH = feature.frameHeightPx.toFloat().coerceAtLeast(1f)
        val scale = maxOf(dstW / srcW, dstH / srcH)
        val scaledW = srcW * scale
        val scaledH = srcH * scale
        val offsetX = (dstW - scaledW) * 0.5f
        val offsetY = (dstH - scaledH) * 0.5f

        fun mapX(nx: Float): Float = (offsetX + nx * scaledW).coerceIn(2f, dstW - 2f)
        fun mapY(ny: Float): Float = (offsetY + ny * scaledH).coerceIn(2f, dstH - 2f)

        val emerald = Color(0xFF10B981)
        val cyan = Color(0xFF00E5FF)
        val amber = Color(0xFFF59E0B)

        // When running on a headless emulator or in simulator mode, render an optical sensor grid
        // and head silhouette inside the PreviewView so the viewport is visually informative
        if (isEmulator || isSimulatorActive) {
            drawRect(color = Color(0xFF0B1220).copy(alpha = 0.82f))
            val stepX = dstW / 4f
            val stepY = dstH / 4f
            for (i in 1..3) {
                drawLine(
                    color = cyan.copy(alpha = 0.10f),
                    start = Offset(stepX * i, 0f),
                    end = Offset(stepX * i, dstH),
                    strokeWidth = 1f
                )
                drawLine(
                    color = cyan.copy(alpha = 0.10f),
                    start = Offset(0f, stepY * i),
                    end = Offset(dstW, stepY * i),
                    strokeWidth = 1f
                )
            }
        }

        if (!feature.faceDetected) {
            val ovalW = dstW * 0.62f
            val ovalH = dstH * 0.68f
            drawOval(
                color = amber.copy(alpha = 0.75f),
                topLeft = Offset((dstW - ovalW) * 0.5f, (dstH - ovalH) * 0.5f),
                size = androidx.compose.ui.geometry.Size(ovalW, ovalH),
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                )
            )
            return@Canvas
        }

        // 1. ML Kit Face Bounding Box Corner Brackets
        val bLeft = mapX(feature.faceBoxLeft)
        val bTop = mapY(feature.faceBoxTop)
        val bRight = mapX(feature.faceBoxRight)
        val bBottom = mapY(feature.faceBoxBottom)
        val bracketLen = minOf(bRight - bLeft, bBottom - bTop).coerceAtLeast(12f) * 0.24f
        val boxStroke = 2.dp.toPx()

        if (isEmulator || isSimulatorActive) {
            drawOval(
                color = emerald.copy(alpha = 0.14f),
                topLeft = Offset(bLeft, bTop),
                size = androidx.compose.ui.geometry.Size((bRight - bLeft).coerceAtLeast(8f), (bBottom - bTop).coerceAtLeast(8f))
            )
        }

        // Top-Left corner
        drawLine(emerald, Offset(bLeft, bTop), Offset(bLeft + bracketLen, bTop), boxStroke, StrokeCap.Round)
        drawLine(emerald, Offset(bLeft, bTop), Offset(bLeft, bTop + bracketLen), boxStroke, StrokeCap.Round)
        // Top-Right corner
        drawLine(emerald, Offset(bRight, bTop), Offset(bRight - bracketLen, bTop), boxStroke, StrokeCap.Round)
        drawLine(emerald, Offset(bRight, bTop), Offset(bRight, bTop + bracketLen), boxStroke, StrokeCap.Round)
        // Bottom-Left corner
        drawLine(emerald, Offset(bLeft, bBottom), Offset(bLeft + bracketLen, bBottom), boxStroke, StrokeCap.Round)
        drawLine(emerald, Offset(bLeft, bBottom), Offset(bLeft, bBottom - bracketLen), boxStroke, StrokeCap.Round)
        // Bottom-Right corner
        drawLine(emerald, Offset(bRight, bBottom), Offset(bRight - bracketLen, bBottom), boxStroke, StrokeCap.Round)
        drawLine(emerald, Offset(bRight, bBottom), Offset(bRight, bBottom - bracketLen), boxStroke, StrokeCap.Round)

        // 2. Left & Right Eye Centers + Dark-Iris Pupil Centroid Crosshairs
        val lEye = Offset(mapX(feature.leftEyeNormX), mapY(feature.leftEyeNormY))
        val rEye = Offset(mapX(feature.rightEyeNormX), mapY(feature.rightEyeNormY))
        val lPupil = Offset(mapX(feature.leftPupilNormX), mapY(feature.leftPupilNormY))
        val rPupil = Offset(mapX(feature.rightPupilNormX), mapY(feature.rightPupilNormY))

        val eyeRadius = ((bRight - bLeft) * 0.11f).coerceIn(5.dp.toPx(), 14.dp.toPx())
        drawCircle(
            color = emerald.copy(alpha = 0.65f),
            radius = eyeRadius,
            center = lEye,
            style = Stroke(width = 1.2.dp.toPx())
        )
        drawCircle(
            color = emerald.copy(alpha = 0.65f),
            radius = eyeRadius,
            center = rEye,
            style = Stroke(width = 1.2.dp.toPx())
        )

        // Pupil centroids in high-contrast Electric Cyan
        val crossLen = 4.dp.toPx()
        drawCircle(color = cyan, radius = 2.5.dp.toPx(), center = lPupil)
        drawLine(cyan, Offset(lPupil.x - crossLen, lPupil.y), Offset(lPupil.x + crossLen, lPupil.y), 1.2.dp.toPx())
        drawLine(cyan, Offset(lPupil.x, lPupil.y - crossLen), Offset(lPupil.x, lPupil.y + crossLen), 1.2.dp.toPx())

        drawCircle(color = cyan, radius = 2.5.dp.toPx(), center = rPupil)
        drawLine(cyan, Offset(rPupil.x - crossLen, rPupil.y), Offset(rPupil.x + crossLen, rPupil.y), 1.2.dp.toPx())
        drawLine(cyan, Offset(rPupil.x, rPupil.y - crossLen), Offset(rPupil.x, rPupil.y + crossLen), 1.2.dp.toPx())

        // 3. Eyebrow Top Contour Bars above Left & Right Eyes
        val lBrowY = mapY(feature.leftBrowNormY)
        val rBrowY = mapY(feature.rightBrowNormY)
        val browHalfW = eyeRadius * 1.15f
        val browColor = if (feature.browToEyeRatio >= 0.38f) amber else cyan.copy(alpha = 0.80f)
        drawLine(
            color = browColor,
            start = Offset(lEye.x - browHalfW, lBrowY),
            end = Offset(lEye.x + browHalfW, lBrowY),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawLine(
            color = browColor,
            start = Offset(rEye.x - browHalfW, rBrowY),
            end = Offset(rEye.x + browHalfW, rBrowY),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

/**
 * Real-time Gaze Overlay rendered via Jetpack Compose `Canvas` at full 120Hz display refresh rate:
 * 1. Thermal Gaze Heatmap Trail (`RETICLE_AND_HEATMAP` or `HEATMAP_ONLY`): Renders multi-stop radial
 *    Gaussian heat-map splats and a decaying trajectory trail from the processed face-detection
 *    coordinates (`hudState.liveTrailPoints`), shifting thermally from Cyan (#00E5FF) -> Emerald (#10B981)
 *    -> Amber (#F59E0B) -> Crimson (#EF4444) as gaze dwells on a region.
 * 2. Region-Confidence Cursor & Optical Crosshair (`RETICLE_AND_HEATMAP` or `RETICLE_ONLY`): Renders
 *    the validation-scaled uncertainty circle (`activeErrorRadiusPct`), N/S/E/W optical tick marks,
 *    a 360° gesture hold arc (for Dwell, Brow-Raise, or Command-Blink), and a live coordinate badge.
 */
@Composable
fun RegionConfidenceReticleOverlay(
    hudState: LiveGazeHudState,
    onReticleRootPositionChanged: (Offset) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val textMeasurer = rememberTextMeasurer()

    val targetNormX by androidx.compose.runtime.rememberUpdatedState(hudState.normX)
    val targetNormY by androidx.compose.runtime.rememberUpdatedState(hudState.normY)
    val latestOnReticleRootPositionChanged by androidx.compose.runtime.rememberUpdatedState(onReticleRootPositionChanged)

    var displayNormX by remember { mutableFloatStateOf(hudState.normX) }
    var displayNormY by remember { mutableFloatStateOf(hudState.normY) }
    var overlayRootBounds by remember { mutableStateOf(Rect.Zero) }

    // Continuous 120Hz display-refresh interpolation loop decoupled from 10-20 FPS camera inference.
    // Keyed on Unit so the coroutine never cancels/restarts when new camera samples arrive.
    LaunchedEffect(Unit) {
        var lastNanos = 0L
        var lastReportedRootX = -1f
        var lastReportedRootY = -1f
        while (true) {
            withFrameNanos { nowNanos ->
                val dtSec = if (lastNanos == 0L) 0.0083f else ((nowNanos - lastNanos) / 1_000_000_000f).coerceIn(0.002f, 0.04f)
                lastNanos = nowNanos
                val dx = targetNormX - displayNormX
                val dy = targetNormY - displayNormY
                val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

                // Adaptive speed: faster catchup during saccades (> 0.06 dist), silk-smooth damping during micro-dwell (< 0.015 dist)
                val speed = if (dist > 0.06f) 24f else if (dist > 0.02f) 18f else 13f
                val lerpFactor = (dtSec * speed).coerceIn(0.12f, 0.65f)

                if (dist > 0.00015f) {
                    displayNormX += dx * lerpFactor
                    displayNormY += dy * lerpFactor
                } else {
                    displayNormX = targetNormX
                    displayNormY = targetNormY
                }

                if (overlayRootBounds.width > 0f && overlayRootBounds.height > 0f) {
                    val rootX = overlayRootBounds.left + displayNormX * overlayRootBounds.width
                    val rootY = overlayRootBounds.top + displayNormY * overlayRootBounds.height
                    if (kotlin.math.abs(rootX - lastReportedRootX) > 0.35f || kotlin.math.abs(rootY - lastReportedRootY) > 0.35f) {
                        lastReportedRootX = rootX
                        lastReportedRootY = rootY
                        latestOnReticleRootPositionChanged(Offset(rootX, rootY))
                    }
                }
            }
        }
    }

    val mode = hudState.overlayVisualMode
    val showHeatmap = mode == GazeOverlayVisualMode.RETICLE_AND_HEATMAP || mode == GazeOverlayVisualMode.HEATMAP_ONLY
    val showCursor = mode == GazeOverlayVisualMode.RETICLE_AND_HEATMAP || mode == GazeOverlayVisualMode.RETICLE_ONLY

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("realtime_gaze_canvas_overlay")
            .onGloballyPositioned { coords ->
                overlayRootBounds = coords.boundsInRoot()
                if (overlayRootBounds.width > 0f && overlayRootBounds.height > 0f) {
                    onReticleRootPositionChanged(
                        Offset(
                            overlayRootBounds.left + displayNormX * overlayRootBounds.width,
                            overlayRootBounds.top + displayNormY * overlayRootBounds.height
                        )
                    )
                }
            }
    ) {
        val cx = displayNormX * size.width
        val cy = displayNormY * size.height
        val errorRadiusPx = ((hudState.activeErrorRadiusPct / 100f) * size.width).coerceIn(28.dp.toPx(), 120.dp.toPx())
        val activeProgress = maxOf(
            hudState.focusedTileDwellProgress,
            hudState.blinkHoldProgress,
            hudState.browHoldProgress
        )

        // --- LAYER 1: Real-Time Thermal Heatmap & Scan-Path Trail ---
        if (showHeatmap) {
            val trail = hudState.liveTrailPoints
            // Draw decaying radial Gaussian thermal splats for recent fixation trail nodes
            for (pt in trail) {
                val px = pt.normX * size.width
                val py = pt.normY * size.height
                val heat = pt.intensity.coerceIn(0.10f, 1.0f)
                val splatRadius = (32.dp.toPx() + heat * 56.dp.toPx())

                val coreColor = when {
                    heat >= 0.72f -> Color(0xFFEF4444) // Hot Crimson fixation peak
                    heat >= 0.45f -> Color(0xFFF59E0B) // Warm Amber dwell buildup
                    else -> Color(0xFF10B981)          // Emerald entry glow
                }
                val midColor = if (heat >= 0.55f) Color(0xFFF59E0B) else Color(0xFF00E5FF)

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            coreColor.copy(alpha = 0.34f * heat),
                            midColor.copy(alpha = 0.18f * heat),
                            Color.Transparent
                        ),
                        center = Offset(px, py),
                        radius = splatRadius
                    ),
                    radius = splatRadius,
                    center = Offset(px, py)
                )
            }

            // Connect trail nodes with a fading comet scan-path line
            if (trail.size >= 2) {
                for (i in 0 until (trail.size - 1)) {
                    val p1 = Offset(trail[i].normX * size.width, trail[i].normY * size.height)
                    val p2 = Offset(trail[i + 1].normX * size.width, trail[i + 1].normY * size.height)
                    val segmentAlpha = ((i + 1).toFloat() / trail.size.toFloat()) * 0.45f
                    drawLine(
                        color = primaryColor.copy(alpha = segmentAlpha),
                        start = p1,
                        end = p2,
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
                    )
                }
            }

            // Live instantaneous thermal aura at current gaze center (cx, cy)
            val liveHeat = maxOf(activeProgress, trail.lastOrNull()?.intensity ?: 0.35f).coerceIn(0.25f, 1.0f)
            val liveAuraRadius = errorRadiusPx * (1.15f + 0.35f * liveHeat)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFEF4444).copy(alpha = 0.28f * liveHeat),
                        Color(0xFFF59E0B).copy(alpha = 0.18f * liveHeat),
                        primaryColor.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = liveAuraRadius
                ),
                radius = liveAuraRadius,
                center = Offset(cx, cy)
            )
        }

        // --- LAYER 2: 120Hz Region-Confidence Cursor & Optical Crosshair ---
        if (showCursor) {
            // Outer translucent validation error circle
            drawCircle(
                color = primaryColor.copy(alpha = 0.12f),
                radius = errorRadiusPx,
                center = Offset(cx, cy)
            )
            drawCircle(
                color = primaryColor.copy(alpha = 0.78f),
                radius = errorRadiusPx,
                center = Offset(cx, cy),
                style = Stroke(width = 2.dp.toPx())
            )

            // Precision N/S/E/W optical reticle ticks around the confidence perimeter
            val tickInner = errorRadiusPx - 6.dp.toPx()
            val tickOuter = errorRadiusPx + 6.dp.toPx()
            val tickColor = primaryColor.copy(alpha = 0.85f)
            drawLine(tickColor, Offset(cx, cy - tickOuter), Offset(cx, cy - tickInner), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(tickColor, Offset(cx, cy + tickInner), Offset(cx, cy + tickOuter), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(tickColor, Offset(cx - tickOuter, cy), Offset(cx - tickInner, cy), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(tickColor, Offset(cx + tickInner, cy), Offset(cx + tickOuter, cy), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)

            // Dwell / Brow-Raise / Command-Blink 360-degree progress arc ring
            if (activeProgress > 0.02f) {
                drawArc(
                    color = secondaryColor,
                    startAngle = -90f,
                    sweepAngle = activeProgress * 360f,
                    useCenter = false,
                    topLeft = Offset(cx - errorRadiusPx, cy - errorRadiusPx),
                    size = androidx.compose.ui.geometry.Size(errorRadiusPx * 2, errorRadiusPx * 2),
                    style = Stroke(width = 4.5.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Center centroid dot & ring
            drawCircle(
                color = Color(0xFF0B0F17),
                radius = 6.dp.toPx(),
                center = Offset(cx, cy)
            )
            drawCircle(
                color = Color.White,
                radius = 4.dp.toPx(),
                center = Offset(cx, cy)
            )

            // Floating telemetry coordinate callout pill beside the reticle
            val coordText = "${(displayNormX * 100).roundToInt()}%, ${(displayNormY * 100).roundToInt()}%"
            val measured = textMeasurer.measure(
                text = coordText,
                style = TextStyle(
                    color = primaryColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )
            val labelX = (cx + errorRadiusPx + 6.dp.toPx())
                .coerceAtMost(size.width - measured.size.width - 8.dp.toPx())
                .coerceAtLeast(6.dp.toPx())
            val labelY = (cy - measured.size.height / 2f)
                .coerceIn(6.dp.toPx(), (size.height - measured.size.height - 6.dp.toPx()).coerceAtLeast(6.dp.toPx()))

            drawText(
                textLayoutResult = measured,
                topLeft = Offset(labelX, labelY)
            )
        }
    }
}
