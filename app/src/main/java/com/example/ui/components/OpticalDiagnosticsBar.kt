package com.example.ui.components

import android.os.Looper
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
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.AnalysisResolutionTier
import com.example.data.model.GazeFeatureVector
import com.example.data.model.GazeOverlayVisualMode
import com.example.domain.BrowRaiseHysteresisDetector
import com.example.domain.CameraGazeAnalyzerEngine
import com.example.ui.viewmodel.LiveGazeHudState
import com.example.ui.viewmodel.isRunningOnEmulatorHardware
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
    val isEmulator = remember { isRunningOnEmulatorHardware() }
    // analyzerFps is 0 only when no frame has arrived recently (the ViewModel watchdog zeroes it), so the
    // last rawFeature is stale then and must not be shown as a face lock.
    val hasRecentFrames = hudState.analyzerFps > 0
    // "Locked" styling only for real camera frames: emulator frames come from the synthetic ticker.
    val isLiveOpticalLock = hasRecentFrames && hudState.rawFeature.faceDetected &&
        !hudState.isSimulatorProxyEnabled && !isEmulator
    val browRaiseThreshold = remember(hudState.neutralBrowRatio, hudState.raisedBrowRatio) {
        browRaiseTriggerThreshold(hudState.neutralBrowRatio, hudState.raisedBrowRatio)
    }

    // Warning flags only change on face-detected frames (a dropped detection must not hide them) and are
    // debounced so the fixed-height warning slot below never flickers on per-frame threshold noise.
    val warningLatch = remember { OpticalWarningLatch() }
    warningLatch.update(hudState.rawFeature)
    val showLowLightWarning = rememberDebouncedFlag(warningLatch.isLowLight)
    val showGlareWarning = rememberDebouncedFlag(warningLatch.hasGlare)
    val showDistanceDriftWarning = rememberDebouncedFlag(hudState.hasDistanceDriftWarning)

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
                                if (hasRecentFrames && hudState.rawFeature.faceDetected) MaterialTheme.colorScheme.secondary
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
                                text = analyzerStatusLabel(
                                    isSimulator = hudState.isSimulatorProxyEnabled,
                                    isEmulator = isEmulator,
                                    faceDetected = hudState.rawFeature.faceDetected,
                                    fps = hudState.analyzerFps
                                ),
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
                                    .clickable(onClickLabel = "Cycle analysis resolution", role = Role.Button) {
                                        onCycleResolutionTier()
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
                                // States the trigger condition (sustained low analysis FPS / thermal drop),
                                // not a claimed mitigation.
                                Text(
                                    text = "LOW FPS",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            text = gazeTelemetryLine(hudState),
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
                            color = if (isLiveOpticalLock) {
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
                                color = if (isLiveOpticalLock) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable(
                                onClickLabel = if (hudState.isSimulatorProxyEnabled) "Switch to live camera" else "Toggle preview size",
                                role = Role.Button
                            ) {
                                if (hudState.isSimulatorProxyEnabled) {
                                    onToggleSimulator(false)
                                    showSimulatorPad = false
                                } else {
                                    expandCameraPreview = !expandCameraPreview
                                }
                            }
                            .testTag("live_camera_preview_box")
                    ) {
                        MiniFrontCameraPreview(
                            isPerformanceMode = hudState.isPerformanceModeActive,
                            resolutionTier = hudState.resolutionTier,
                            // The camera is unbound (not just ignored) while the simulator proxy drives gaze,
                            // so the "CAMERAX STANDBY" label is true and the privacy indicator goes off.
                            isCameraActive = !hudState.isSimulatorProxyEnabled,
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
                            browRaiseThreshold = browRaiseThreshold,
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
                                text = cameraPipelineLabel(
                                    isSimulator = hudState.isSimulatorProxyEnabled,
                                    isEmulator = isEmulator,
                                    deviceModel = android.os.Build.MODEL
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isLiveOpticalLock) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                            Text(
                                text = "${f.frameWidthPx}×${f.frameHeightPx} @ ${f.sensorRotationDeg}°" +
                                    (if (isEmulator || hudState.isSimulatorProxyEnabled) " (synthetic)" else ""),
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
                                text = eyeOpenBrowLine(f, browRaiseThreshold),
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

            // Optical warnings (visible-light low-light mode, eyeglass glare, head-distance drift) share ONE
            // always-present slot whose height is fixed to two label lines, so a warning appearing or
            // clearing never moves the deck tiles, calibration targets or reading sections below.
            val warningText = opticalWarningText(
                showLowLight = showLowLightWarning,
                lowLightLuma = warningLatch.lowLightLuma,
                showGlare = showGlareWarning,
                glareSentence = warningLatch.glareSentence,
                showDistanceDrift = showDistanceDriftWarning
            )
            val warningColor = if (showLowLightWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
            val warningLineHeight = MaterialTheme.typography.labelSmall.lineHeight.takeIf { it.isSp } ?: 16.sp
            val warningSlotHeight = with(LocalDensity.current) { warningLineHeight.toDp() * 2 } + 8.dp
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(warningSlotHeight)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (warningText != null) warningColor.copy(alpha = 0.15f) else Color.Transparent)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag(if (showLowLightWarning) "low_light_degraded_banner" else "optical_warning_slot"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (warningText != null) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = if (showLowLightWarning) "Low-Light Degraded Mode" else "Optical Warning",
                        tint = warningColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = warningText,
                        style = MaterialTheme.typography.labelSmall,
                        color = warningColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = "No optical warnings",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1
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
            // Space is always reserved (hidden via alpha) so every natural blink does not shift the layout.
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { holdProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .alpha(if (holdProgress > 0f) 1f else 0f)
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

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
    isCameraActive: Boolean,
    onFeatureFrame: (GazeFeatureVector) -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val latestOnFeatureFrame by androidx.compose.runtime.rememberUpdatedState(onFeatureFrame)
    val latestResolutionTier by androidx.compose.runtime.rememberUpdatedState(resolutionTier)
    val latestIsCameraActive by androidx.compose.runtime.rememberUpdatedState(isCameraActive)
    val isEmulator = remember { isRunningOnEmulatorHardware() }
    // The engine reports from its background analysis executor. Frames are handed on to the ViewModel on
    // the main thread, conflated to the newest one, so all gaze state stays main-thread confined and the
    // main queue cannot grow while the UI thread is busy. Pixel work stays on the analysis executor.
    val frameConflator = remember {
        MainThreadFrameConflator(
            mainExecutor = ContextCompat.getMainExecutor(context),
            isOnMainThread = { Looper.myLooper() == Looper.getMainLooper() },
            deliver = { vector -> latestOnFeatureFrame(vector) }
        )
    }
    val analyzer = remember {
        CameraGazeAnalyzerEngine { vector ->
            frameConflator.submit(vector)
        }
    }

    var boundCameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var boundImageAnalysis by remember { mutableStateOf<ImageAnalysis?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var boundTier by remember { mutableStateOf<AnalysisResolutionTier?>(null) }
    var hasNoFrontCamera by remember { mutableStateOf(false) }
    // Tier of the bind request in flight. Written synchronously (boundTier is only set once the async
    // provider listener has bound successfully), so layout/post/effect triggers that arrive before the
    // listener runs do not each schedule another full unbindAll() + bindToLifecycle() cycle.
    val requestedTier = remember { arrayOfNulls<AnalysisResolutionTier>(1) }

    LaunchedEffect(isPerformanceMode) {
        analyzer.isPerformanceModeEnabled = isPerformanceMode
    }

    fun bindCameraUseCases(view: PreviewView, tier: AnalysisResolutionTier) {
        if (isEmulator || !latestIsCameraActive || !view.isAttachedToWindow || view.width <= 0 || view.height <= 0) {
            return
        }
        if (requestedTier[0] == tier) {
            return
        }
        if (boundTier == tier && boundCameraProvider != null) {
            // Already bound to this tier: drop any pending request for another tier.
            requestedTier[0] = null
            return
        }
        requestedTier[0] = tier
        val ctx = view.context
        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
        cameraProviderFuture.addListener({
            // Superseded by a newer tier, cancelled by a pause, or disposed.
            if (requestedTier[0] != tier) return@addListener
            try {
                if (!view.isAttachedToWindow || !latestIsCameraActive) return@addListener
                val cameraProvider = cameraProviderFuture.get()

                // The feature pipeline mirrors X and the yaw/gx signs for a user-facing camera, and a back
                // camera faces away from the user, so never fall back to it.
                if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    hasNoFrontCamera = true
                    android.util.Log.w("ÆyeCamera", "No front camera reported; gaze camera not bound")
                    return@addListener
                }
                hasNoFrontCamera = false

                val previewResolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                // Request the selected analysis tier (1280×960 default) so iris detail at arm's length is
                // preserved, and step down (1280 -> 960 -> 640) when FPS < 15. CameraX may negotiate a
                // different size; the HUD shows the frame size actually delivered.
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

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageAnalysis
                )
                // Commit the "bound" guard only after a successful bind, so a failed attempt is retried.
                boundCameraProvider = cameraProvider
                boundTier = tier
            } catch (e: Exception) {
                boundTier = null
                android.util.Log.w("ÆyeCamera", "CameraX bind skipped or failed: ${e.message}")
            } finally {
                if (requestedTier[0] == tier) requestedTier[0] = null
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    // Unbinds the camera while the simulator proxy drives gaze. The analyzer and its executor stay alive
    // (an in-flight ML Kit task must never post to a shut-down executor); a later activation rebinds.
    fun releaseCameraUseCases() {
        requestedTier[0] = null
        try {
            boundImageAnalysis?.clearAnalyzer()
            boundCameraProvider?.unbindAll()
        } catch (e: Exception) {
            android.util.Log.w("ÆyeCamera", "CameraX unbind failed: ${e.message}")
        }
        boundImageAnalysis = null
        boundCameraProvider = null
        boundTier = null
    }

    LaunchedEffect(isCameraActive, resolutionTier, previewViewRef) {
        val view = previewViewRef ?: return@LaunchedEffect
        if (isCameraActive) {
            bindCameraUseCases(view, resolutionTier)
        } else {
            releaseCameraUseCases()
        }
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            frameConflator.dispose()
            requestedTier[0] = null
            try {
                boundImageAnalysis?.clearAnalyzer()
                boundCameraProvider?.unbindAll()
            } catch (_: Exception) {
            }
            analyzer.close()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
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
        if (hasNoFrontCamera) {
            Text(
                text = "No front camera",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
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
    browRaiseThreshold: Float,
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
        // Amber exactly when the brow detector would start a raise hold (calibrated threshold).
        val browColor = if (feature.browToEyeRatio >= browRaiseThreshold) amber else cyan.copy(alpha = 0.80f)
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
 * Real-time Gaze Overlay rendered via Jetpack Compose, interpolating the cursor at display refresh rate
 * while it moves (the frame loop idles once the cursor has settled):
 * 1. Thermal Gaze Heatmap Trail (`RETICLE_AND_HEATMAP` or `HEATMAP_ONLY`): Renders multi-stop radial
 *    Gaussian heat-map splats and a decaying trajectory trail from the processed face-detection
 *    coordinates (`hudState.liveTrailPoints`), shifting thermally from Cyan (#00E5FF) -> Emerald (#10B981)
 *    -> Amber (#F59E0B) -> Crimson (#EF4444) as gaze dwells on a region. Cached per trail update.
 * 2. Region-Confidence Cursor & Optical Crosshair (`RETICLE_AND_HEATMAP` or `RETICLE_ONLY`): Renders
 *    the validation-scaled uncertainty ellipse (`activeErrorRadiusPct` applied per axis; a wide default
 *    when not validated), N/S/E/W optical tick marks, a 360° gesture hold arc (for Dwell, Brow-Raise,
 *    or Command-Blink), and a live coordinate badge.
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

    // Root position is pushed to the parent (which recomposes the whole screen content) only after the
    // interpolated cursor moved by a visible step, plus once exactly when it settles.
    val coarseReportStepPx = with(LocalDensity.current) { 1.5.dp.toPx() }

    // Display-refresh interpolation loop decoupled from 10-20 FPS camera inference.
    // Keyed on Unit so the coroutine never cancels/restarts when new camera samples arrive. Once the cursor
    // has converged it suspends until the target moves, so an idle reticle requests no frames.
    LaunchedEffect(Unit) {
        var lastNanos = 0L
        var lastReportedRootX = -1f
        var lastReportedRootY = -1f
        while (true) {
            val converged = withFrameNanos { nowNanos ->
                val dtSec = if (lastNanos == 0L) 0.0083f else ((nowNanos - lastNanos) / 1_000_000_000f).coerceIn(0.002f, 0.04f)
                lastNanos = nowNanos
                val dx = targetNormX - displayNormX
                val dy = targetNormY - displayNormY
                val dist = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

                // Adaptive speed: faster catchup during saccades (> 0.06 dist), silk-smooth damping during micro-dwell (< 0.015 dist)
                val speed = if (dist > 0.06f) 24f else if (dist > 0.02f) 18f else 13f
                val lerpFactor = (dtSec * speed).coerceIn(0.12f, 0.65f)

                val settled = dist <= 0.00015f
                if (!settled) {
                    displayNormX += dx * lerpFactor
                    displayNormY += dy * lerpFactor
                } else {
                    displayNormX = targetNormX
                    displayNormY = targetNormY
                }

                if (overlayRootBounds.width > 0f && overlayRootBounds.height > 0f) {
                    val rootX = overlayRootBounds.left + displayNormX * overlayRootBounds.width
                    val rootY = overlayRootBounds.top + displayNormY * overlayRootBounds.height
                    val reportStepPx = if (settled) 0.35f else coarseReportStepPx
                    if (kotlin.math.abs(rootX - lastReportedRootX) > reportStepPx || kotlin.math.abs(rootY - lastReportedRootY) > reportStepPx) {
                        lastReportedRootX = rootX
                        lastReportedRootY = rootY
                        latestOnReticleRootPositionChanged(Offset(rootX, rootY))
                    }
                }
                settled
            }
            if (converged) {
                // targetNormX/Y are rememberUpdatedState States, so snapshotFlow observes them.
                snapshotFlow { targetNormX to targetNormY }
                    .first { (tx, ty) -> tx != displayNormX || ty != displayNormY }
                lastNanos = 0L
            }
        }
    }

    val mode = hudState.overlayVisualMode
    val showHeatmap = mode == GazeOverlayVisualMode.RETICLE_AND_HEATMAP || mode == GazeOverlayVisualMode.HEATMAP_ONLY
    val showCursor = mode == GazeOverlayVisualMode.RETICLE_AND_HEATMAP || mode == GazeOverlayVisualMode.RETICLE_ONLY
    val trail = hudState.liveTrailPoints
    val coordLabelStyle = remember(primaryColor) {
        TextStyle(
            color = primaryColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        // --- LAYER 1: Real-Time Thermal Heatmap & Scan-Path Trail ---
        // Drawn in its own node that never reads the interpolated cursor position: the gradient shaders and
        // dash effect are rebuilt only when the trail (camera rate) or the size changes, not on every vsync.
        if (showHeatmap) {
            Spacer(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
                        val segmentStrokePx = 2.dp.toPx()
                        val splats = trail.map { pt ->
                            val center = Offset(pt.normX * size.width, pt.normY * size.height)
                            val heat = pt.intensity.coerceIn(0.10f, 1.0f)
                            val splatRadius = (32.dp.toPx() + heat * 56.dp.toPx())

                            val coreColor = when {
                                heat >= 0.72f -> Color(0xFFEF4444) // Hot Crimson fixation peak
                                heat >= 0.45f -> Color(0xFFF59E0B) // Warm Amber dwell buildup
                                else -> Color(0xFF10B981)          // Emerald entry glow
                            }
                            val midColor = if (heat >= 0.55f) Color(0xFFF59E0B) else Color(0xFF00E5FF)
                            HeatmapSplat(
                                center = center,
                                radius = splatRadius,
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        coreColor.copy(alpha = 0.34f * heat),
                                        midColor.copy(alpha = 0.18f * heat),
                                        Color.Transparent
                                    ),
                                    center = center,
                                    radius = splatRadius
                                )
                            )
                        }
                        onDrawBehind {
                            // Decaying radial Gaussian thermal splats for recent fixation trail nodes
                            for (splat in splats) {
                                drawCircle(brush = splat.brush, radius = splat.radius, center = splat.center)
                            }
                            // Connect trail nodes with a fading comet scan-path line
                            for (i in 0 until (splats.size - 1)) {
                                val segmentAlpha = ((i + 1).toFloat() / splats.size.toFloat()) * 0.45f
                                drawLine(
                                    color = primaryColor.copy(alpha = segmentAlpha),
                                    start = splats[i].center,
                                    end = splats[i + 1].center,
                                    strokeWidth = segmentStrokePx,
                                    cap = StrokeCap.Round,
                                    pathEffect = dashEffect
                                )
                            }
                        }
                    }
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // Own render layer: per-frame cursor invalidations re-record only this layer.
                .graphicsLayer { }
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
            // Error % is Euclidean in normalized space (x per width, y per height), so the uncertainty region
            // is an ellipse with per-axis radii; it is not capped at a fixed dp size.
            val errorRadii = reticleUncertaintyRadiiPx(
                errorPct = hudState.activeErrorRadiusPct,
                widthPx = size.width,
                heightPx = size.height,
                minRadiusPx = 28.dp.toPx()
            )
            val rx = errorRadii.width
            val ry = errorRadii.height
            val errorTopLeft = Offset(cx - rx, cy - ry)
            val errorSize = androidx.compose.ui.geometry.Size(rx * 2f, ry * 2f)
            val activeProgress = maxOf(
                hudState.focusedTileDwellProgress,
                hudState.blinkHoldProgress,
                hudState.browHoldProgress
            )

            if (showHeatmap) {
                // Live instantaneous thermal aura at current gaze center (cx, cy)
                val liveHeat = maxOf(activeProgress, trail.lastOrNull()?.intensity ?: 0.35f).coerceIn(0.25f, 1.0f)
                val liveAuraRadius = maxOf(rx, ry) * (1.15f + 0.35f * liveHeat)
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

            // --- LAYER 2: Region-Confidence Cursor & Optical Crosshair ---
            if (showCursor) {
                // Outer translucent validation error ellipse
                drawOval(
                    color = primaryColor.copy(alpha = 0.12f),
                    topLeft = errorTopLeft,
                    size = errorSize
                )
                drawOval(
                    color = primaryColor.copy(alpha = 0.78f),
                    topLeft = errorTopLeft,
                    size = errorSize,
                    style = Stroke(width = 2.dp.toPx())
                )

                // Precision N/S/E/W optical reticle ticks around the confidence perimeter
                val tickHalf = 6.dp.toPx()
                val tickColor = primaryColor.copy(alpha = 0.85f)
                drawLine(tickColor, Offset(cx, cy - ry - tickHalf), Offset(cx, cy - ry + tickHalf), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(tickColor, Offset(cx, cy + ry - tickHalf), Offset(cx, cy + ry + tickHalf), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(tickColor, Offset(cx - rx - tickHalf, cy), Offset(cx - rx + tickHalf, cy), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(tickColor, Offset(cx + rx - tickHalf, cy), Offset(cx + rx + tickHalf, cy), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)

                // Dwell / Brow-Raise / Command-Blink 360-degree progress arc ring
                if (activeProgress > 0.02f) {
                    drawArc(
                        color = secondaryColor,
                        startAngle = -90f,
                        sweepAngle = activeProgress * 360f,
                        useCenter = false,
                        topLeft = errorTopLeft,
                        size = errorSize,
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
                    style = coordLabelStyle
                )
                val labelX = (cx + rx + 6.dp.toPx())
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
}

private class HeatmapSplat(val center: Offset, val radius: Float, val brush: Brush)

/**
 * The brow ratio at which [BrowRaiseHysteresisDetector] actually starts a raise hold for this calibration
 * (neutral + 0.60 * max(raised - neutral, 0.035)), so the HUD and overlay show the real trigger.
 */
internal fun browRaiseTriggerThreshold(neutralBrowRatio: Float, raisedBrowRatio: Float): Float =
    BrowRaiseHysteresisDetector.raiseThresholdFor(neutralBrowRatio, raisedBrowRatio)

/**
 * Expanded-preview eye-open / brow line. No-face frames carry placeholder values (0 eye-open probability,
 * default brow ratio), so they show "--" instead of reading as closed eyes or a neutral brow.
 */
internal fun eyeOpenBrowLine(feature: GazeFeatureVector, browRaiseThreshold: Float): String {
    val trigger = String.format(java.util.Locale.US, "%.2f", browRaiseThreshold)
    return if (feature.faceDetected) {
        String.format(
            java.util.Locale.US,
            "Eye Open: L %.0f%% / R %.0f%% • Brow Ratio: %.2f (Trigger ≥ %s)",
            feature.leftEyeOpenProb * 100f,
            feature.rightEyeOpenProb * 100f,
            feature.browToEyeRatio,
            trigger
        )
    } else {
        "Eye Open: L -- / R -- • Brow Ratio: -- (Trigger ≥ $trigger)"
    }
}

/** Error % used for the reticle when accuracy has not been validated (deliberately wide). */
internal const val UNCALIBRATED_RETICLE_ERROR_PCT = 20f

/**
 * Per-axis uncertainty radii in pixels for a validated Euclidean error percentage expressed in normalized
 * screen space (x as a fraction of width, y as a fraction of height). Returned as Size(rx, ry).
 * A null or non-finite error (not validated) uses [UNCALIBRATED_RETICLE_ERROR_PCT]. Each radius is at least
 * [minRadiusPx] and at most half of its axis, so a poor calibration is shown as poor instead of being capped
 * at a reassuring fixed size.
 */
internal fun reticleUncertaintyRadiiPx(
    errorPct: Float?,
    widthPx: Float,
    heightPx: Float,
    minRadiusPx: Float
): androidx.compose.ui.geometry.Size {
    val pct = errorPct?.takeIf { it.isFinite() && it >= 0f } ?: UNCALIBRATED_RETICLE_ERROR_PCT
    val frac = pct / 100f
    val rx = (frac * widthPx).coerceIn(minRadiusPx, maxOf(minRadiusPx, widthPx * 0.5f))
    val ry = (frac * heightPx).coerceIn(minRadiusPx, maxOf(minRadiusPx, heightPx * 0.5f))
    return androidx.compose.ui.geometry.Size(rx, ry)
}

/**
 * HUD telemetry line. Eye confidences and luma are only shown for frames with a detected face (no-face frames
 * carry placeholder defaults), and the accuracy pill says "uncalibrated" until a validated error exists.
 */
internal fun gazeTelemetryLine(hudState: LiveGazeHudState): String {
    val f = hudState.rawFeature
    val gaze = "Gaze (${(hudState.normX * 100).roundToInt()}%, ${(hudState.normY * 100).roundToInt()}%)"
    val eyes = if (f.faceDetected) {
        "L:${(f.leftEyeConfidence * 100).roundToInt()}% R:${(f.rightEyeConfidence * 100).roundToInt()}%"
    } else {
        "L:-- R:--"
    }
    val validatedErrorPct: Float? = hudState.activeErrorRadiusPct
    val accuracy = if (validatedErrorPct != null && validatedErrorPct.isFinite()) {
        "±" + String.format(java.util.Locale.US, "%.1f", validatedErrorPct) + "%"
    } else {
        "uncalibrated"
    }
    val luma = if (f.faceDetected) "Luma:${f.ambientLuma}" else "Luma:--"
    return "$gaze • $eyes • $accuracy • $luma"
}

/**
 * Header status: says where the frames come from instead of claiming an optical lock for synthetic data.
 * fps <= 0 means no frame arrived recently (the last face result is stale), shown as "NO FRAMES", never "0 FPS".
 */
internal fun analyzerStatusLabel(isSimulator: Boolean, isEmulator: Boolean, faceDetected: Boolean, fps: Int): String {
    val rate = if (fps > 0) "$fps FPS" else "NO FRAMES"
    return when {
        isSimulator -> "SIM PROXY • $rate"
        isEmulator -> "EMULATOR SYNTHETIC • $rate"
        fps <= 0 -> "CAMERA • NO FRAMES"
        faceDetected -> "OPTICAL LOCK • $rate"
        else -> "SEARCHING FACE... • $rate"
    }
}

/** Camera pipeline label: device-neutral, no unverified sensor/FOV claims. */
internal fun cameraPipelineLabel(isSimulator: Boolean, isEmulator: Boolean, deviceModel: String?): String =
    when {
        isSimulator -> "CAMERAX STANDBY (TAP PREVIEW FOR LIVE)"
        isEmulator -> "EMULATOR • NO CAMERA BOUND (SYNTHETIC FRAMES)"
        else -> {
            val model = deviceModel?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(java.util.Locale.US) ?: "DEVICE"
            "FRONT CAMERA PIPELINE • $model"
        }
    }

/** Glare sentence built from the engine's actual eye-selection decision for that frame. */
internal fun glareWarningSentence(feature: GazeFeatureVector): String =
    if (feature.isSingleEyeMode) {
        "Eyeglass specular glare detected (tracking ${feature.activeEyeLabel})."
    } else {
        "Eyeglass specular glare detected (both eyes fused, glare-weighted)."
    }

internal fun lowLightWarningSentence(luma: Int): String =
    "LOW LIGHT (luma $luma/255): visible-light front camera, no IR illumination; iris offsets damped, head pose + region dwell prioritized."

/** Text for the single fixed-height warning slot, low-light first; null when no warning is active. */
internal fun opticalWarningText(
    showLowLight: Boolean,
    lowLightLuma: Int,
    showGlare: Boolean,
    glareSentence: String,
    showDistanceDrift: Boolean
): String? {
    val parts = ArrayList<String>(3)
    if (showLowLight) parts.add(lowLightWarningSentence(lowLightLuma))
    if (showGlare && glareSentence.isNotEmpty()) parts.add(glareSentence)
    if (showDistanceDrift) parts.add("Head distance (IOD) shifted >15% from calibration baseline.")
    return if (parts.isEmpty()) null else parts.joinToString(" ")
}

/**
 * Holds the optical warning inputs across frames. Face-detected frames set them; no-face frames carry
 * placeholder defaults, so they can raise low light (a real frame-luma measurement, if the engine reports
 * one) but never clear a warning or reset the glare eye-selection. [lowLightLuma] and [glareSentence]
 * describe the last frame that actually raised the warning, so the text shown while a debounced warning
 * is fading out stays consistent with it.
 */
internal class OpticalWarningLatch {
    var isLowLight: Boolean = false
        private set
    var lowLightLuma: Int = 0
        private set
    var hasGlare: Boolean = false
        private set
    var glareSentence: String = ""
        private set

    fun update(feature: GazeFeatureVector) {
        if (feature.faceDetected) {
            isLowLight = feature.isLowLightDegraded
            if (feature.isLowLightDegraded) lowLightLuma = feature.ambientLuma
            hasGlare = feature.hasGlareWarning
            if (feature.hasGlareWarning) glareSentence = glareWarningSentence(feature)
        } else if (feature.isLowLightDegraded) {
            isLowLight = true
            lowLightLuma = feature.ambientLuma
        }
    }
}

/** Returns [raw] only once it has held for [onDelayMs] (turning on) or [offDelayMs] (turning off). */
@Composable
private fun rememberDebouncedFlag(raw: Boolean, onDelayMs: Long = 1000L, offDelayMs: Long = 2000L): Boolean {
    var stable by remember { mutableStateOf(raw) }
    LaunchedEffect(raw) {
        if (raw != stable) {
            delay(if (raw) onDelayMs else offDelayMs)
            stable = raw
        }
    }
    return stable
}

/**
 * Hands camera feature frames to [deliver] on the main thread. Frames submitted from the analysis thread are
 * conflated: at most one drain task is queued and it delivers the newest pending frame, so a busy main thread
 * never builds a backlog. Frames submitted on the main thread are delivered directly. Nothing is delivered
 * after [dispose].
 */
internal class MainThreadFrameConflator(
    private val mainExecutor: Executor,
    private val isOnMainThread: () -> Boolean,
    private val deliver: (GazeFeatureVector) -> Unit
) {
    private val pending = AtomicReference<GazeFeatureVector?>(null)
    private val disposed = AtomicBoolean(false)
    private val drainTask = Runnable {
        val frame = pending.getAndSet(null)
        if (frame != null && !disposed.get()) {
            deliver(frame)
        }
    }

    fun submit(frame: GazeFeatureVector) {
        if (disposed.get()) return
        if (isOnMainThread()) {
            pending.set(null)
            deliver(frame)
            return
        }
        if (pending.getAndSet(frame) == null) {
            try {
                mainExecutor.execute(drainTask)
            } catch (_: RejectedExecutionException) {
                pending.set(null)
            }
        }
    }

    fun dispose() {
        disposed.set(true)
        pending.set(null)
    }
}
