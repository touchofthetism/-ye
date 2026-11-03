package com.example.ui.components

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
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
import com.example.data.model.GazeFeatureVector
import com.example.data.model.GazeOverlayVisualMode
import com.example.domain.CameraGazeAnalyzerEngine
import com.example.ui.viewmodel.LiveGazeHudState
import java.util.concurrent.Executors
import kotlin.math.roundToInt

@Composable
fun OpticalDiagnosticsBar(
    hudState: LiveGazeHudState,
    hasCameraPermission: Boolean,
    onRequestCameraPermission: () -> Unit,
    onToggleSimulator: (Boolean) -> Unit,
    onCycleOverlayMode: () -> Unit = {},
    onSimulatorDragTarget: (Float, Float) -> Unit,
    onSimulateCommandBlink: () -> Unit,
    onSimulateBrowRaise: () -> Unit = {},
    onTriggerRecalibration: () -> Unit = {},
    onDismissRecalibrateNudge: () -> Unit = {},
    onFeatureFrame: (GazeFeatureVector) -> Unit
) {
    var showSimulatorPad by remember { mutableStateOf(false) }

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
                    if (hasCameraPermission && !hudState.isSimulatorProxyEnabled) {
                        MiniFrontCameraPreview(
                            isPerformanceMode = hudState.isPerformanceModeActive,
                            onFeatureFrame = onFeatureFrame,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    } else {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (hudState.isSimulatorProxyEnabled) Icons.Default.TouchApp else Icons.Default.CameraFront,
                                contentDescription = "Sensor source",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (hudState.rawFeature.faceDetected) MaterialTheme.colorScheme.secondary
                                        else MaterialTheme.colorScheme.tertiary
                                    )
                            )
                            Text(
                                text = if (hudState.isSimulatorProxyEnabled) {
                                    "SIM PROXY • ${hudState.analyzerFps} FPS"
                                } else if (hudState.rawFeature.faceDetected) {
                                    "OPTICAL LOCK • ${hudState.analyzerFps} FPS"
                                } else {
                                    "SEARCHING FACE..."
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
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
                            text = "Gaze (${(hudState.normX * 100).roundToInt()}%, ${(hudState.normY * 100).roundToInt()}%) • L:$lConf% R:$rConf% • ±${String.format("%.1f", hudState.activeErrorRadiusPct)}% (Max ${String.format("%.1f", hudState.activeMaxQuadrantErrorPct)}%)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (!hasCameraPermission && !hudState.isSimulatorProxyEnabled) {
                        Button(
                            onClick = onRequestCameraPermission,
                            modifier = Modifier.testTag("request_camera_button"),
                            contentPadding = ButtonDefaults.ContentPadding
                        ) {
                            Text("Camera", style = MaterialTheme.typography.labelSmall)
                        }
                    }
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
                            append("Head distance shifted >15% from calibration baseline.")
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
                        text = "Position changed — Recalibrate without losing session?",
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
    onFeatureFrame: (GazeFeatureVector) -> Unit,
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val analyzer = remember { CameraGazeAnalyzerEngine(onFeatureFrame) }

    LaunchedEffect(isPerformanceMode) {
        analyzer.isPerformanceModeEnabled = isPerformanceMode
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            analyzer.close()
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(cameraExecutor, analyzer)
                        }
                    val selector = CameraSelector.DEFAULT_FRONT_CAMERA
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        selector,
                        preview,
                        imageAnalysis
                    )
                } catch (_: Exception) {
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
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

    var displayNormX by remember { mutableFloatStateOf(hudState.normX) }
    var displayNormY by remember { mutableFloatStateOf(hudState.normY) }
    var overlayRootBounds by remember { mutableStateOf(Rect.Zero) }

    // 120Hz display-refresh interpolation loop decoupled from 10-20 FPS camera inference
    LaunchedEffect(hudState.normX, hudState.normY) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nowNanos ->
                val dtSec = if (lastNanos == 0L) 0.008f else ((nowNanos - lastNanos) / 1_000_000_000f).coerceIn(0.004f, 0.05f)
                lastNanos = nowNanos
                val lerpFactor = (dtSec * 16f).coerceIn(0.08f, 0.45f)
                displayNormX += (hudState.normX - displayNormX) * lerpFactor
                displayNormY += (hudState.normY - displayNormY) * lerpFactor

                if (overlayRootBounds.width > 0f && overlayRootBounds.height > 0f) {
                    val rootX = overlayRootBounds.left + displayNormX * overlayRootBounds.width
                    val rootY = overlayRootBounds.top + displayNormY * overlayRootBounds.height
                    onReticleRootPositionChanged(Offset(rootX, rootY))
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
