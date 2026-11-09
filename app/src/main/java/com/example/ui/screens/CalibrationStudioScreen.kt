package com.example.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
import com.example.data.model.CalibrationTargetPoint
import com.example.data.model.EnrollmentBaseline
import com.example.data.model.GazeFeatureVector
import com.example.domain.EnrollmentFailure
import com.example.domain.EnrollmentPhase
import com.example.domain.EnrollmentScanState
import com.example.domain.GazeCalibrationMath
import com.example.ui.components.enrollmentBaselineSummaryLines
import com.example.ui.components.enrollmentCompleteSummary
import com.example.ui.components.enrollmentFailureText
import com.example.ui.components.enrollmentWarningLabelForName
import com.example.ui.components.formatEnrollmentCapturedAt
import com.example.ui.components.isEnrollmentScanPhaseActive
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
    onCaptureManualSamples: () -> Unit = {},
    enrollmentState: EnrollmentScanState = EnrollmentScanState(),
    enrollmentBaseline: EnrollmentBaseline? = null,
    onStartEnrollment: () -> Unit = {},
    onClearEnrollment: () -> Unit = {}
) {
    // Leaving composition mid-wizard (tab switch, activity teardown) must end the run. Otherwise every
    // later camera frame is still recorded under a target that is no longer on screen, and the fit that
    // completes in the background becomes the live mapping. The step is read at dispose time, so a
    // normal FIT -> COMPLETED_SUMMARY transition (which keeps this screen composed) never cancels.
    val latestStep by rememberUpdatedState(calibrationState.step)
    val latestOnCancelWizard by rememberUpdatedState(onCancelWizard)
    DisposableEffect(Unit) {
        onDispose {
            if (isCalibrationCollectingStep(latestStep)) {
                latestOnCancelWizard()
            }
        }
    }

    // The HUD changes on every camera frame (~30 Hz). The idle overview gets this holder (one stable
    // instance) instead of the value, so it skips; only the small leaves that read the holder recompose,
    // and they see the new value in this same composition pass (the write happens before they compose).
    val latestHudState = rememberUpdatedState(hudState)

    when (calibrationState.step) {
        CalibrationFlowStep.IDLE -> {
            // Only the scan's phase and outcome reach the overview: a scanned frame changes neither, so the
            // overview keeps skipping per frame while the scan runs (the overlay shows the live values).
            CalibrationIdleOverview(
                includeHeldOutValidation = calibrationState.includeHeldOutValidation,
                hudState = latestHudState,
                onStartWizard = onStartWizard,
                onToggleIncludeValidation = onToggleIncludeValidation,
                enrollmentBaseline = enrollmentBaseline,
                enrollmentPhase = enrollmentState.phase,
                enrollmentResult = enrollmentState.result,
                enrollmentFailure = enrollmentState.failure,
                onStartEnrollment = onStartEnrollment,
                onClearEnrollment = onClearEnrollment
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
    includeHeldOutValidation: Boolean,
    hudState: State<LiveGazeHudState>,
    onStartWizard: () -> Unit,
    onToggleIncludeValidation: (Boolean) -> Unit,
    enrollmentBaseline: EnrollmentBaseline? = null,
    enrollmentPhase: EnrollmentPhase = EnrollmentPhase.IDLE,
    enrollmentResult: EnrollmentBaseline? = null,
    enrollmentFailure: EnrollmentFailure? = null,
    onStartEnrollment: () -> Unit = {},
    onClearEnrollment: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        EnrollmentCard(
            baseline = enrollmentBaseline,
            scanPhase = enrollmentPhase,
            scanResult = enrollmentResult,
            scanFailure = enrollmentFailure,
            onStartEnrollment = onStartEnrollment,
            onClearEnrollment = onClearEnrollment
        )
        CalibrationOverviewCardForHud(
            includeHeldOutValidation = includeHeldOutValidation,
            hudState = hudState,
            onStartWizard = onStartWizard,
            onToggleIncludeValidation = onToggleIncludeValidation
        )
        LiveFeatureTelemetryCard(hudState = hudState)
    }
}

/** Reads the per-frame HUD in its own tiny scope and hands the overview card only the fields it shows. */
@Composable
private fun CalibrationOverviewCardForHud(
    includeHeldOutValidation: Boolean,
    hudState: State<LiveGazeHudState>,
    onStartWizard: () -> Unit,
    onToggleIncludeValidation: (Boolean) -> Unit
) {
    val hud = hudState.value
    CalibrationOverviewCard(
        includeHeldOutValidation = includeHeldOutValidation,
        meanErrPct = hud.activeErrorRadiusPct,
        worstErrPct = hud.activeMaxQuadrantErrorPct,
        meanErrMm = hud.activeErrorMm,
        hasActiveCalibration = hud.hasActiveCalibration,
        activeProfileName = hud.activeProfileName,
        onStartWizard = onStartWizard,
        onToggleIncludeValidation = onToggleIncludeValidation
    )
}

@Composable
private fun CalibrationOverviewCard(
    includeHeldOutValidation: Boolean,
    meanErrPct: Float?,
    worstErrPct: Float?,
    meanErrMm: Double?,
    hasActiveCalibration: Boolean,
    activeProfileName: String,
    onStartWizard: () -> Unit,
    onToggleIncludeValidation: (Boolean) -> Unit
) {
    val fiveTargets = GazeCalibrationMath.FIVE_POINT_CALIBRATION_TARGETS
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val outlineColor = MaterialTheme.colorScheme.outline
    // Raw-px intervals (density independent): one native path effect instead of one per draw.
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("calibration_overview_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val title = stringResource(R.string.calibration_5_point_title)
                Icon(
                    imageVector = Icons.Default.CenterFocusStrong,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
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
                    selected = !includeHeldOutValidation,
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
                    selected = includeHeldOutValidation,
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
            }
            // Below the map, not over it: overlaid at BottomCenter it wrapped onto the P4/P5 markers.
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Sequence: P1(0.50,0.50) → P2(0.14,0.16) → P3(0.86,0.16) → P4(0.14,0.84) → P5(0.86,0.84)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))
            // Null means "not validated": either no calibration is applied (default matrix) or the
            // applied profile is a 5-point fit without held-out validation. Never show a number nobody
            // measured, and never call an applied fit "not calibrated".
            val meanErrText = formatErrorPctOrNull(meanErrPct?.toDouble())
            val worstErrText = formatErrorPctOrNull(worstErrPct?.toDouble())
            val fitOnly = hasActiveCalibration
            val unmeasuredValue = if (fitOnly) NOT_VALIDATED_TEXT else NOT_CALIBRATED_TEXT
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricBadgeCard(
                    label = "MEAN SCREEN ERR",
                    value = meanErrText ?: unmeasuredValue,
                    sub = when {
                        meanErrText != null -> formatApproxMmOrNull(meanErrMm) ?: "mm not measured"
                        fitOnly -> "$FIT_ONLY_TEXT, run 5+4 validation to measure"
                        else -> "Run calibration to measure"
                    },
                    modifier = Modifier.weight(1f)
                )
                MetricBadgeCard(
                    label = "WORST QUADRANT",
                    value = worstErrText ?: unmeasuredValue,
                    sub = when {
                        worstErrText != null -> "Profile: ${activeProfileName.take(16)}"
                        fitOnly -> "$FIT_ONLY_TEXT • Profile: ${activeProfileName.take(16)}"
                        else -> "Default matrix, not validated"
                    },
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
                    text = if (includeHeldOutValidation) {
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
}

/**
 * Face & eye enrollment card of the idle overview. Without a baseline it explains the ~15 s scan and
 * offers to run it; with one it lists the measured numbers (never anything that was not measured) and
 * offers a re-scan or a clear. The outcome of the scan that just ended (COMPLETE summary or FAILED reason)
 * is shown until the next scan or a clear. The buttons are disabled while a scan is running: the overlay
 * passes touches through, and a second start would restart the scan under the person's feet.
 */
@Composable
private fun EnrollmentCard(
    baseline: EnrollmentBaseline?,
    scanPhase: EnrollmentPhase,
    scanResult: EnrollmentBaseline?,
    scanFailure: EnrollmentFailure?,
    onStartEnrollment: () -> Unit,
    onClearEnrollment: () -> Unit
) {
    val scanActive = isEnrollmentScanPhaseActive(scanPhase)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("enrollment_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Face,
                    contentDescription = ENROLLMENT_CARD_TITLE,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = ENROLLMENT_CARD_TITLE,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(6.dp))

            val statusText = when {
                scanActive -> "Scanning…"
                baseline != null -> "Enrolled ${formatEnrollmentCapturedAt(baseline.capturedAtMs)}"
                else -> ENROLLMENT_NOT_ENROLLED_TEXT
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (baseline != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.testTag("enrollment_status_text")
            )

            // Outcome of the scan that just ended (the engine keeps it until the next start or a clear).
            val outcomeText = when (scanPhase) {
                EnrollmentPhase.COMPLETE -> (scanResult ?: baseline)?.let { enrollmentCompleteSummary(it) }
                    ?: "Scan complete."
                EnrollmentPhase.FAILED -> "Scan failed: ${enrollmentFailureText(scanFailure)}"
                else -> null
            }
            if (outcomeText != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = outcomeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (scanPhase == EnrollmentPhase.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                    modifier = Modifier.testTag("enrollment_outcome_text")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            if (baseline == null) {
                Text(
                    text = ENROLLMENT_EXPLANATION_TEXT,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("enrollment_explanation_text")
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onStartEnrollment,
                    enabled = !scanActive,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("start_enrollment_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Face,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Scan my face & eyes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                }
            } else {
                // Formatted once per baseline, not on every recomposition of the overview.
                val lines = remember(baseline) { enrollmentBaselineSummaryLines(baseline) }
                for (line in lines) {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
                if (baseline.warnings.isNotEmpty()) {
                    val warningsText = remember(baseline) {
                        "Warnings: " + baseline.warnings.joinToString(", ") { enrollmentWarningLabelForName(it) }
                    }
                    Text(
                        text = warningsText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier
                            .padding(vertical = 1.dp)
                            .testTag("enrollment_warnings_text")
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = ENROLLMENT_PRIVACY_TEXT,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = onStartEnrollment,
                        enabled = !scanActive,
                        modifier = Modifier
                            .weight(1.3f)
                            .heightIn(min = 48.dp)
                            .testTag("rescan_enrollment_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Re-scan")
                    }
                    OutlinedButton(
                        onClick = onClearEnrollment,
                        enabled = !scanActive,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("clear_enrollment_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Clear", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

/** Static card chrome; only [LiveFeatureTelemetryLinesForHud] inside it follows the per-frame HUD. */
@Composable
private fun LiveFeatureTelemetryCard(hudState: State<LiveGazeHudState>) {
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
            LiveFeatureTelemetryLinesForHud(hudState = hudState)
        }
    }
}

/**
 * Reads the per-frame HUD and hands each line only the primitives it prints. The feature vector itself
 * differs on every frame (its timestamp does), so a line keyed on it would re-format on every frame; keyed
 * on its own fields, a line skips its String.format whenever what it shows is unchanged (e.g. the
 * constant gx..roll and eye placeholders of no-face frames).
 */
@Composable
private fun LiveFeatureTelemetryLinesForHud(hudState: State<LiveGazeHudState>) {
    val hud = hudState.value
    val f = hud.rawFeature
    RawFeatureVectorLine(gx = f.gx, gy = f.gy, yaw = f.yaw, pitch = f.pitch, roll = f.roll)
    Spacer(modifier = Modifier.height(4.dp))
    EyeTelemetryLine(
        faceDetected = f.faceDetected,
        leftEyeConfidence = f.leftEyeConfidence,
        rightEyeConfidence = f.rightEyeConfidence,
        eyeDistancePx = f.eyeDistancePx,
        combinedEyeOpenProb = f.combinedEyeOpenProb,
        ambientLuma = f.ambientLuma,
        isLowLightDegraded = f.isLowLightDegraded,
        tierLabel = hud.resolutionTier.shortLabel
    )
}

@Composable
private fun RawFeatureVectorLine(gx: Double, gy: Double, yaw: Double, pitch: Double, roll: Double) {
    Text(
        text = formatRawFeatureVectorLine(gx, gy, yaw, pitch, roll),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun EyeTelemetryLine(
    faceDetected: Boolean,
    leftEyeConfidence: Float,
    rightEyeConfidence: Float,
    eyeDistancePx: Double,
    combinedEyeOpenProb: Float,
    ambientLuma: Int,
    isLowLightDegraded: Boolean,
    tierLabel: String
) {
    Text(
        text = formatEyeTelemetryLine(
            faceDetected = faceDetected,
            leftEyeConfidence = leftEyeConfidence,
            rightEyeConfidence = rightEyeConfidence,
            eyeDistancePx = eyeDistancePx,
            combinedEyeOpenProb = combinedEyeOpenProb,
            ambientLuma = ambientLuma,
            isLowLightDegraded = isLowLightDegraded,
            tierLabel = tierLabel
        ),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("calibration_eye_telemetry_text")
    )
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
 *
 * The fixation target itself is drawn full-frame by [CalibrationTargetFullscreenOverlay] (placed by
 * MainActivity in the same root Box the gaze output is normalized to). This screen only shows the
 * guidance, the controls and a small progress map.
 */
@Composable
fun FivePointCalibrationScreen(
    calibrationState: CalibrationWizardState,
    hudState: LiveGazeHudState,
    onCancelWizard: () -> Unit,
    onSnapSimulatorToCurrentTarget: (Float, Float) -> Unit,
    onCaptureManualSamples: () -> Unit = {}
) {
    // Only these two HUD fields matter here. Passing them as plain values lets the wizard body skip
    // recomposition on camera frames that change nothing it shows (the HUD state changes every frame).
    FivePointCalibrationContent(
        calibrationState = calibrationState,
        faceDetected = hudState.rawFeature.faceDetected,
        isSimulatorProxyEnabled = hudState.isSimulatorProxyEnabled,
        onCancelWizard = onCancelWizard,
        onSnapSimulatorToCurrentTarget = onSnapSimulatorToCurrentTarget,
        onCaptureManualSamples = onCaptureManualSamples
    )
}

@Composable
private fun FivePointCalibrationContent(
    calibrationState: CalibrationWizardState,
    faceDetected: Boolean,
    isSimulatorProxyEnabled: Boolean,
    onCancelWizard: () -> Unit,
    onSnapSimulatorToCurrentTarget: (Float, Float) -> Unit,
    onCaptureManualSamples: () -> Unit
) {
    // This body re-runs on every collected sample (the wizard state changes per sample). Each section is
    // a skippable composable fed only the values it shows, so a sample re-runs the counter text and the
    // current pill, not the target heading, the action buttons or the progress map (and its canvas).
    val fiveFitTargets = GazeCalibrationMath.FIVE_POINT_CALIBRATION_TARGETS
    val currentTargetIndex = calibrationState.currentTargetIndex
    val requiredSamplesPerTarget = calibrationState.requiredSamplesPerTarget
    val pointSampleCounts = calibrationState.pointSampleCounts
    val target = calibrationTargetForIndex(currentTargetIndex)
    val isValidation = target.isValidationPoint
    val targetColor = if (isValidation) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    val completedColor = MaterialTheme.colorScheme.secondary
    val pendingColor = MaterialTheme.colorScheme.outline
    val pillCoordLabels = fitTargetPillCoordLabels

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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
                        CalibrationTargetHeading(target = target, targetColor = targetColor)
                        CalibrationSampleCounter(
                            faceDetected = faceDetected,
                            isSettlingDelay = calibrationState.isSettlingDelay,
                            samplesCollectedForCurrentTarget = calibrationState.samplesCollectedForCurrentTarget,
                            requiredSamplesPerTarget = requiredSamplesPerTarget,
                            currentTargetProgress = calibrationState.currentTargetProgress,
                            totalCollectedSamples = calibrationState.totalCollectedSamples
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
                    fiveFitTargets.forEachIndexed { position, pt ->
                        CalibrationStepPill(
                            pointIndex = pt.index,
                            coordLabel = pillCoordLabels[position],
                            isCompleted = currentTargetIndex > pt.index,
                            isCurrent = currentTargetIndex == pt.index,
                            count = pointSampleCounts[pt.index] ?: 0,
                            requiredSamplesPerTarget = requiredSamplesPerTarget,
                            targetColor = targetColor,
                            completedColor = completedColor,
                            pendingColor = pendingColor,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                CalibrationWizardActions(
                    target = target,
                    isSimulatorProxyEnabled = isSimulatorProxyEnabled,
                    onCancelWizard = onCancelWizard,
                    onSnapSimulatorToCurrentTarget = onSnapSimulatorToCurrentTarget,
                    onCaptureManualSamples = onCaptureManualSamples
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Small progress map: a miniature of the screen, deliberately without a pulsing bullseye. The
        // target the user fixates is the full-frame one, drawn in the frame the gaze output uses.
        Text(
            text = "Progress map (miniature of the screen, not a target). Look at the pulsing target drawn on the screen itself.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("calibration_progress_map_label")
        )
        Spacer(modifier = Modifier.height(6.dp))
        CalibrationProgressMap(
            currentTargetIndex = currentTargetIndex,
            target = target,
            targetColor = targetColor,
            completedColor = completedColor,
            pendingColor = pendingColor,
            isSimulatorProxyEnabled = isSimulatorProxyEnabled,
            onSnapSimulatorToCurrentTarget = onSnapSimulatorToCurrentTarget,
            onCaptureManualSamples = onCaptureManualSamples
        )
    }
}

/** Target label and guidance prompt: recomposes only when the target (or its colour) changes. */
@Composable
private fun CalibrationTargetHeading(target: CalibrationTargetPoint, targetColor: Color) {
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
}

@Composable
private fun CalibrationSampleCounter(
    faceDetected: Boolean,
    isSettlingDelay: Boolean,
    samplesCollectedForCurrentTarget: Int,
    requiredSamplesPerTarget: Int,
    currentTargetProgress: Float,
    totalCollectedSamples: Int
) {
    Text(
        text = calibrationSampleCounterText(
            faceDetected = faceDetected,
            isSettlingDelay = isSettlingDelay,
            samplesCollectedForCurrentTarget = samplesCollectedForCurrentTarget,
            requiredSamplesPerTarget = requiredSamplesPerTarget,
            currentTargetProgress = currentTargetProgress,
            totalCollectedSamples = totalCollectedSamples
        ),
        style = MaterialTheme.typography.labelSmall,
        color = if (faceDetected) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.error
        },
        modifier = Modifier.testTag("calibration_sample_counter_text")
    )
}

/** One stepper pill. Only the pill whose count or state changed recomposes on a collected sample. */
@Composable
private fun CalibrationStepPill(
    pointIndex: Int,
    coordLabel: String,
    isCompleted: Boolean,
    isCurrent: Boolean,
    count: Int,
    requiredSamplesPerTarget: Int,
    targetColor: Color,
    completedColor: Color,
    pendingColor: Color,
    modifier: Modifier = Modifier
) {
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
        modifier = modifier
            .clip(StepPillShape)
            .background(bg)
            .border(1.dp, borderCol, StepPillShape)
            .padding(vertical = 4.dp, horizontal = 4.dp)
            .testTag("calibration_step_pill_$pointIndex"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (isCompleted) "✓ P${pointIndex + 1}" else "P${pointIndex + 1}",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
            color = if (isCurrent) targetColor else MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = coordLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "$count/$requiredSamplesPerTarget",
            style = MaterialTheme.typography.labelSmall,
            color = if (isCompleted) completedColor else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CalibrationWizardActions(
    target: CalibrationTargetPoint,
    isSimulatorProxyEnabled: Boolean,
    onCancelWizard: () -> Unit,
    onSnapSimulatorToCurrentTarget: (Float, Float) -> Unit,
    onCaptureManualSamples: () -> Unit
) {
    // Action controls for Simulator Lock, Manual Capture, and Cancel.
    // The simulator is only ever moved when it is already on: on real hardware a capture must
    // record the user's own camera frames, never a synthetic vector placed on the target.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val captureLabel = stringResource(R.string.capture_gaze_sample_action)
        Button(
            onClick = {
                if (isSimulatorProxyEnabled) {
                    onSnapSimulatorToCurrentTarget(target.normX, target.normY)
                }
                onCaptureManualSamples()
            },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .weight(1.2f)
                .testTag("capture_gaze_point_button")
        ) {
            Icon(
                imageVector = Icons.Default.Visibility,
                contentDescription = captureLabel,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "$captureLabel (${target.shortName})",
                style = MaterialTheme.typography.labelMedium
            )
        }
        OutlinedButton(
            onClick = {
                if (isSimulatorProxyEnabled) {
                    onSnapSimulatorToCurrentTarget(target.normX, target.normY)
                }
            },
            enabled = isSimulatorProxyEnabled,
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

/**
 * Miniature progress map. It depends on the target index, never on the per-sample counters, so a
 * collected sample neither re-subcomposes this BoxWithConstraints nor redraws its canvas.
 */
@Composable
private fun CalibrationProgressMap(
    currentTargetIndex: Int,
    target: CalibrationTargetPoint,
    targetColor: Color,
    completedColor: Color,
    pendingColor: Color,
    isSimulatorProxyEnabled: Boolean,
    onSnapSimulatorToCurrentTarget: (Float, Float) -> Unit,
    onCaptureManualSamples: () -> Unit
) {
    val fiveFitTargets = GazeCalibrationMath.FIVE_POINT_CALIBRATION_TARGETS
    // Raw-px intervals (density independent): one native path effect instead of one per draw.
    val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
            .testTag("five_point_calibration_canvas")
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        Canvas(modifier = Modifier.fillMaxSize()) {
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

            // Sequential guide path across the 5 calibration targets
            for (i in 0 until fiveFitTargets.size - 1) {
                val fromPt = fiveFitTargets[i]
                val toPt = fiveFitTargets[i + 1]
                val isActiveTransition = currentTargetIndex == toPt.index
                drawLine(
                    color = if (isActiveTransition) {
                        targetColor.copy(alpha = 0.55f)
                    } else {
                        pendingColor.copy(alpha = 0.25f)
                    },
                    start = Offset(fromPt.normX * size.width, fromPt.normY * size.height),
                    end = Offset(toPt.normX * size.width, toPt.normY * size.height),
                    strokeWidth = if (isActiveTransition) 2.dp.toPx() else 1.dp.toPx(),
                    pathEffect = dashEffect
                )
            }

            // Completed and pending fit points
            fiveFitTargets.forEach { pt ->
                val center = Offset(pt.normX * size.width, pt.normY * size.height)
                val isDone = currentTargetIndex > pt.index
                val isCurrent = currentTargetIndex == pt.index
                if (isDone) {
                    drawCircle(
                        color = completedColor,
                        radius = 7.dp.toPx(),
                        center = center,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                    drawCircle(
                        color = completedColor,
                        radius = 3.dp.toPx(),
                        center = center
                    )
                } else if (!isCurrent) {
                    drawCircle(
                        color = pendingColor.copy(alpha = 0.45f),
                        radius = 7.dp.toPx(),
                        center = center,
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            }

            // Current point (fit or held-out) as a plain position marker, not a fixation bullseye
            drawCircle(
                color = targetColor,
                radius = 5.dp.toPx(),
                center = Offset(target.normX * size.width, target.normY * size.height)
            )
        }

        // Accessible nodes at each of the 5 fit coordinates. Only the current one captures: tapping
        // another point must never record anything under the current target's label.
        val targetHitSize = 48.dp
        val halfHitPx = with(density) { (targetHitSize / 2).toPx() }
        fiveFitTargets.forEach { pt ->
            val xOffsetPx = (pt.normX * widthPx - halfHitPx).roundToInt()
            val yOffsetPx = (pt.normY * heightPx - halfHitPx).roundToInt()
            val isCurrent = currentTargetIndex == pt.index
            Box(
                modifier = Modifier
                    .offset { IntOffset(xOffsetPx, yOffsetPx) }
                    .size(targetHitSize)
                    .clip(CircleShape)
                    .then(
                        if (isCurrent) {
                            Modifier.clickable {
                                if (isSimulatorProxyEnabled) {
                                    onSnapSimulatorToCurrentTarget(pt.normX, pt.normY)
                                }
                                onCaptureManualSamples()
                            }
                        } else {
                            Modifier
                        }
                    )
                    .semantics {
                        contentDescription = "${pt.shortName} coordinate (${pt.normX}, ${pt.normY})"
                    }
                    .testTag("calibration_point_target_${pt.index}"),
                contentAlignment = Alignment.Center
            ) {}
        }
    }
}

/**
 * Full-frame fixation target for the calibration wizard.
 *
 * Gaze normX/normY is normalized to the root content Box that also hosts RegionConfidenceReticleOverlay,
 * and the fit labels each sample with the current target's (normX, normY). The target must therefore be
 * drawn in that same frame: MainActivity places this overlay in the root Box with Modifier.fillMaxSize(),
 * and it draws only the current target at (normX * width, normY * height) of its own bounds, with the
 * sample-progress ring. It has no pointer input, so every touch reaches the controls underneath.
 */
@Composable
fun CalibrationTargetFullscreenOverlay(
    calibrationState: CalibrationWizardState,
    modifier: Modifier = Modifier
) {
    val target = currentCalibrationFixationTarget(calibrationState) ?: return
    val targetColor = if (target.isValidationPoint) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    val progress = calibrationState.currentTargetProgress.coerceIn(0f, 1f)

    val pulseTransition = rememberInfiniteTransition(label = "calibration_target_pulse")
    val pulseScale = pulseTransition.animateFloat(
        initialValue = 0.86f,
        targetValue = 1.24f,
        animationSpec = calibrationTargetPulseSpec,
        label = "calibration_target_pulse_scale"
    )

    // Canvas is exactly Spacer(modifier.drawBehind(onDraw)). drawWithCache keeps that node, but builds
    // the strokes and geometry once per size / target / progress change instead of on every display
    // frame of the pulse; only the halo radius is computed per frame.
    Spacer(
        modifier = modifier
            .testTag("calibration_fullscreen_target_overlay")
            .drawWithCache {
                val center = calibrationTargetCenterPx(target, size.width, size.height)
                val haloColor = targetColor.copy(alpha = 0.22f)
                val haloBaseRadiusPx = 38.dp.toPx()
                val ringRadiusPx = 26.dp.toPx()
                val ringStroke = Stroke(width = 3.dp.toPx())
                val arcHalfSidePx = 32.dp.toPx()
                val arcTopLeft = Offset(center.x - arcHalfSidePx, center.y - arcHalfSidePx)
                val arcSize = Size(64.dp.toPx(), 64.dp.toPx())
                val arcStroke = Stroke(width = 5.dp.toPx())
                val sweepAngle = progress * 360f
                val dotRadiusPx = 6.5.dp.toPx()
                onDrawBehind {
                    // The pulse is read in the draw phase, so the animation redraws without recomposing
                    // and without rebuilding this cache.
                    drawCircle(
                        color = haloColor,
                        radius = haloBaseRadiusPx * pulseScale.value,
                        center = center
                    )
                    drawCircle(
                        color = targetColor,
                        radius = ringRadiusPx,
                        center = center,
                        style = ringStroke
                    )
                    // Sample collection 360° progress arc
                    drawArc(
                        color = targetColor,
                        startAngle = -90f,
                        sweepAngle = sweepAngle,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = arcStroke
                    )
                    // High-contrast bullseye center dot
                    drawCircle(
                        color = targetColor,
                        radius = dotRadiusPx,
                        center = center
                    )
                }
            }
    )
}

private const val ENROLLMENT_CARD_TITLE = "Face & Eye Enrollment"
internal const val ENROLLMENT_NOT_ENROLLED_TEXT = "Not enrolled"
internal const val ENROLLMENT_EXPLANATION_TEXT =
    "A 15-second scan of your face and eyes personalizes blink and brow detection to you: how open your " +
        "eyes are at rest and closed, how long your natural blinks last, and where your brows sit. Only " +
        "those numbers are kept, on this device only. No images are stored, and nothing is synced."
private const val ENROLLMENT_PRIVACY_TEXT = "Derived numbers only, stored on this device. No images, never synced."
private const val NOT_CALIBRATED_TEXT = "Not calibrated"
private const val NOT_MEASURED_TEXT = "Not measured"
private const val NOT_VALIDATED_TEXT = "Not validated"
private const val FIT_ONLY_TEXT = "5-point fit only"
private const val NOT_DETECTED_TEXT = "--"

/** Shared by clip and border of every stepper pill (immutable, so one instance serves all of them). */
private val StepPillShape = RoundedCornerShape(8.dp)

/** Pulse of the full-frame fixation target. Immutable, so one instance serves every recomposition. */
internal val calibrationTargetPulseSpec: InfiniteRepeatableSpec<Float> = infiniteRepeatable(
    animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
    repeatMode = RepeatMode.Reverse
)

/** "(x.xx,y.yy)" for each fit target, in FIVE_POINT_CALIBRATION_TARGETS order (constants: format once). */
internal val fitTargetPillCoordLabels: List<String> by lazy {
    GazeCalibrationMath.FIVE_POINT_CALIBRATION_TARGETS.map { pt ->
        String.format(Locale.US, "(%.2f,%.2f)", pt.normX, pt.normY)
    }
}

/** "Pn Name — Coord (x.xx, y.yy)" for each fit target, in FIVE_POINT_CALIBRATION_TARGETS order. */
internal val fitTargetSummaryCoordLabels: List<String> by lazy {
    GazeCalibrationMath.FIVE_POINT_CALIBRATION_TARGETS.map { pt ->
        String.format(Locale.US, "%s — Coord (%.2f, %.2f)", pt.shortName, pt.normX, pt.normY)
    }
}

/**
 * Eye telemetry line. On a no-face frame the engine reports 0 confidence / 0 eye-open and the default
 * inter-ocular distance: those are placeholders, not measurements, so they read "--".
 */
internal fun formatEyeTelemetryLine(f: GazeFeatureVector, tierLabel: String): String =
    formatEyeTelemetryLine(
        faceDetected = f.faceDetected,
        leftEyeConfidence = f.leftEyeConfidence,
        rightEyeConfidence = f.rightEyeConfidence,
        eyeDistancePx = f.eyeDistancePx,
        combinedEyeOpenProb = f.combinedEyeOpenProb,
        ambientLuma = f.ambientLuma,
        isLowLightDegraded = f.isLowLightDegraded,
        tierLabel = tierLabel
    )

/** [formatEyeTelemetryLine] from the individual fields it prints (lets the composable skip on them). */
internal fun formatEyeTelemetryLine(
    faceDetected: Boolean,
    leftEyeConfidence: Float,
    rightEyeConfidence: Float,
    eyeDistancePx: Double,
    combinedEyeOpenProb: Float,
    ambientLuma: Int,
    isLowLightDegraded: Boolean,
    tierLabel: String
): String {
    val lowLightSuffix = if (isLowLightDegraded) " (LOW-LIGHT DEGRADED)" else ""
    if (!faceDetected) {
        // Only the tier and luma are real here. A template prints an Int exactly as Locale.US "%d" does.
        return "Eye Confidence: Left $NOT_DETECTED_TEXT • Right $NOT_DETECTED_TEXT | IOD: $NOT_DETECTED_TEXT | " +
            "Eye Open: $NOT_DETECTED_TEXT | Tier: $tierLabel | Luma: $ambientLuma/255$lowLightSuffix"
    }
    // One Formatter pass per frame instead of five (four sub-formats spliced in with %s): every
    // specifier formats independently, so the text is identical.
    return String.format(
        Locale.US,
        "Eye Confidence: Left %.0f%% • Right %.0f%% | IOD: %.1f px | Eye Open: %.2f | Tier: %s | Luma: %d/255%s",
        leftEyeConfidence * 100f,
        rightEyeConfidence * 100f,
        eyeDistancePx,
        combinedEyeOpenProb,
        tierLabel,
        ambientLuma,
        lowLightSuffix
    )
}

/** "f_raw = [1.0, gx=..., gy=..., yaw=...°, pitch=...°, roll=...°]" line of the live telemetry card. */
internal fun formatRawFeatureVectorLine(gx: Double, gy: Double, yaw: Double, pitch: Double, roll: Double): String =
    String.format(
        Locale.US,
        "f_raw = [1.0, gx=%.3f, gy=%.3f, yaw=%.1f°, pitch=%.1f°, roll=%.1f°]",
        gx, gy, yaw, pitch, roll
    )

/**
 * Sample-counter line of the wizard header. Samples are only taken on face-detected frames, so without
 * a face the settling phase never ends: say so instead of "settling" forever. Built with a template
 * (an Int prints exactly as Locale.US "%d" does) because it changes on every collected sample.
 */
internal fun calibrationSampleCounterText(
    faceDetected: Boolean,
    isSettlingDelay: Boolean,
    samplesCollectedForCurrentTarget: Int,
    requiredSamplesPerTarget: Int,
    currentTargetProgress: Float,
    totalCollectedSamples: Int
): String = when {
    !faceDetected ->
        "No face detected — face the front camera. Sampling is paused until your face is found."
    isSettlingDelay ->
        "Settling saccade (400 ms) — keep your eyes on the pulsing on-screen target..."
    else ->
        "Collecting gaze samples: $samplesCollectedForCurrentTarget/$requiredSamplesPerTarget at point " +
            "(${(currentTargetProgress * 100).toInt()}%) • Total collected: $totalCollectedSamples pts"
}

/** The fitted 2x6 matrix as "W_x = [...]" / "W_y = [...]" rows; null unless it has exactly 12 entries. */
internal fun formatFittedMatrixTextOrNull(m: List<Double>): String? =
    if (m.size == 12) {
        String.format(
            Locale.US,
            "W_x = [%.2f, %.2f, %.2f, %.2f, %.2f, %.2f]\nW_y = [%.2f, %.2f, %.2f, %.2f, %.2f, %.2f]",
            m[0], m[1], m[2], m[3], m[4], m[5],
            m[6], m[7], m[8], m[9], m[10], m[11]
        )
    } else {
        null
    }

/** True while the wizard records camera frames under an on-screen target. */
internal fun isCalibrationCollectingStep(step: CalibrationFlowStep): Boolean =
    step == CalibrationFlowStep.FIT_5_POINTS || step == CalibrationFlowStep.VALIDATE_4_POINTS

/** Wizard target for [index]: fit points 0-4, then held-out points 5-8 (first point if out of range). */
private fun calibrationTargetForIndex(index: Int): CalibrationTargetPoint {
    val allTargets = GazeCalibrationMath.CALIBRATION_AND_VALIDATION_TARGETS
    return allTargets.getOrNull(index) ?: allTargets.first()
}

/** The point the user must fixate now, or null when the wizard is not collecting samples. */
internal fun currentCalibrationFixationTarget(state: CalibrationWizardState): CalibrationTargetPoint? =
    if (isCalibrationCollectingStep(state.step)) calibrationTargetForIndex(state.currentTargetIndex) else null

/** Pixel center of [target] in a [widthPx] x [heightPx] frame (the frame gaze normX/normY is normalized to). */
internal fun calibrationTargetCenterPx(target: CalibrationTargetPoint, widthPx: Float, heightPx: Float): Offset =
    Offset(target.normX * widthPx, target.normY * heightPx)

/** "±x.x%" for a measured error; null when nothing was measured (null, NaN or infinite). */
internal fun formatErrorPctOrNull(value: Double?): String? =
    value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "±%.1f%%", it) }

/** "~x.x mm (approximate)" for a measured error; null when nothing was measured. */
internal fun formatApproxMmOrNull(value: Double?): String? =
    value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "~%.1f mm (approximate)", it) }

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
                // Every keystroke in the profile-name field produces a new wizard state. The report above
                // the field depends on none of that, so it gets only its own fields and skips.
                CalibrationSummaryReport(
                    includeHeldOutValidation = calibrationState.includeHeldOutValidation,
                    metricsAreHeldOut = calibrationState.metricsAreHeldOut,
                    validationMeanErrorPct = calibrationState.validationMeanErrorPct,
                    validationMaxErrorPct = calibrationState.validationMaxErrorPct,
                    validationMeanErrorMm = calibrationState.validationMeanErrorMm,
                    totalCollectedSamples = calibrationState.totalCollectedSamples,
                    pointSampleCounts = calibrationState.pointSampleCounts,
                    recommendedGridLabel = calibrationState.recommendedGridLabel,
                    fittedMatrix = calibrationState.fittedMatrix
                )

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

/**
 * Everything in the summary card above the profile-name field. A NaN (unmeasured) metric can count as
 * changed on every call (Double parameters are compared with ==), so on that path the body may still
 * re-run per keystroke; its costly text (coordinate rows, matrix) is precomputed or remembered, and a
 * NaN metric formats nothing, so that re-run calls no String.format.
 */
@Composable
private fun CalibrationSummaryReport(
    includeHeldOutValidation: Boolean,
    metricsAreHeldOut: Boolean,
    validationMeanErrorPct: Double,
    validationMaxErrorPct: Double,
    validationMeanErrorMm: Double,
    totalCollectedSamples: Int,
    pointSampleCounts: Map<Int, Int>,
    recommendedGridLabel: String,
    fittedMatrix: List<Double>
) {
    val fiveFitTargets = GazeCalibrationMath.FIVE_POINT_CALIBRATION_TARGETS
    val summaryCoordLabels = fitTargetSummaryCoordLabels

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "Calibration Complete",
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(26.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = if (includeHeldOutValidation) {
                "5-Point Fit + 4-Point Validation Report"
            } else {
                "5-Point Screen Calibration Complete"
            },
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }

    Spacer(modifier = Modifier.height(14.dp))

    // Quick-fit mode scores the fit on the very samples it was trained on: that is a fit
    // residual, not a screen error, and no quadrant point was measured. Only metrics the
    // ViewModel marks as computed on the held-out targets earn the validated labels (the
    // selected mode alone does not prove any held-out sample was collected). Non-finite
    // metrics were not measured at all.
    val meanErrText = formatErrorPctOrNull(validationMeanErrorPct)
    val worstErrText = formatErrorPctOrNull(validationMaxErrorPct)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MetricBadgeCard(
            label = if (metricsAreHeldOut) "MEAN SCREEN ERROR" else "FIT RESIDUAL (IN-SAMPLE)",
            value = meanErrText ?: NOT_MEASURED_TEXT,
            sub = if (metricsAreHeldOut) {
                formatApproxMmOrNull(validationMeanErrorMm) ?: "mm not measured"
            } else {
                "not validated"
            },
            modifier = Modifier.weight(1f)
        )
        MetricBadgeCard(
            label = if (metricsAreHeldOut) "WORST QUADRANT" else "WORST FIT POINT",
            value = worstErrText ?: NOT_MEASURED_TEXT,
            sub = if (metricsAreHeldOut) {
                "Total Samples: $totalCollectedSamples"
            } else {
                "not validated • $totalCollectedSamples samples"
            },
            modifier = Modifier.weight(1f)
        )
    }

    if (!metricsAreHeldOut) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (includeHeldOutValidation) {
                // 5+4 run whose held-out targets produced no scored sample: nothing was validated.
                "No held-out validation sample was scored, so the screen error of this fit was " +
                    "not measured. Run '${stringResource(R.string.calibration_mode_5_plus_4)}' again " +
                    "to measure it."
            } else {
                "Quick fit: these are residuals on the same $totalCollectedSamples " +
                    "samples the fit was trained on. No held-out point was measured, so the real screen " +
                    "error is unknown and usually larger. Run " +
                    "'${stringResource(R.string.calibration_mode_5_plus_4)}' to measure it."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.testTag("calibration_in_sample_caveat")
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
    fiveFitTargets.forEachIndexed { position, pt ->
        // A point with no entry collected nothing: never show the required count as collected.
        val count = pointSampleCounts[pt.index] ?: 0
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = summaryCoordLabels[position],
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

    if (recommendedGridLabel.isNotBlank()) {
        Text(
            text = "Recommended Layout: $recommendedGridLabel",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.secondary
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    val matrixText = remember(fittedMatrix) { formatFittedMatrixTextOrNull(fittedMatrix) }
    if (matrixText != null) {
        Text(
            text = matrixText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
