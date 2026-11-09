package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.model.EnrollmentBaseline
import com.example.domain.EnrollmentFailure
import com.example.domain.EnrollmentGuidance
import com.example.domain.EnrollmentPhase
import com.example.domain.EnrollmentScanState
import com.example.domain.EnrollmentWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Full-screen guidance for the ~15 s face & eye enrollment scan.
 *
 * MainActivity places it in the root content Box (the frame gaze is normalized to) while the scan is in
 * an active phase; it composes nothing in IDLE, COMPLETE or FAILED. It shows a centred fixation dot that
 * pulses while the person should be looking at it, one plain-English guidance line per
 * [EnrollmentGuidance], the phase label with a progress ring (phaseProgress) around the dot and a thin
 * overall bar, a live eye-openness bar from [EnrollmentScanState.liveEyeOpenProb], one chip per warning,
 * a Cancel button and, only in BROW_RAISE, a "Skip brow step" button.
 *
 * The scrim only draws: it has no pointer input, so touches outside the two buttons reach the content
 * underneath exactly as they do under [com.example.ui.screens.CalibrationTargetFullscreenOverlay].
 */
@Composable
fun EnrollmentScanOverlay(
    state: EnrollmentScanState,
    onCancel: () -> Unit,
    onSkipBrow: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!isEnrollmentScanPhaseActive(state.phase)) return

    val guidanceText = enrollmentGuidanceText(state.guidance)
    val phaseLabel = enrollmentPhaseLabel(state.phase)
    val pulsing = enrollmentGuidanceNeedsFixation(state.guidance)
    val eyeOpen = state.liveEyeOpenProb.coerceIn(0f, 1f)
    val phaseProgress = state.phaseProgress.coerceIn(0f, 1f)
    val overallProgress = state.overallProgress.coerceIn(0f, 1f)
    val overallPercent = (overallProgress * 100f).roundToInt()
    val eyeOpenPercent = (eyeOpen * 100f).roundToInt()

    Box(modifier = modifier.testTag("enrollment_scan_overlay")) {
        // Scrim: draws only, no pointer input (see the KDoc).
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.82f))
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = phaseLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("enrollment_phase_label")
            )
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { overallProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .semantics { contentDescription = "Overall scan progress $overallPercent percent" }
                    .testTag("enrollment_overall_progress"),
                color = MaterialTheme.colorScheme.primary,
                trackColor = Color.White.copy(alpha = 0.18f)
            )
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = guidanceText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("enrollment_guidance_text")
            )
            val secondary = enrollmentSecondaryLine(state)
            if (secondary != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("enrollment_secondary_text")
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = String.format(Locale.US, "Eye openness %.2f", eyeOpen),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.testTag("enrollment_eye_open_label")
            )
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { eyeOpen },
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .semantics { contentDescription = "Live eye openness $eyeOpenPercent percent" }
                    .testTag("enrollment_eye_open_bar"),
                color = MaterialTheme.colorScheme.secondary,
                trackColor = Color.White.copy(alpha = 0.18f)
            )
            if (state.warnings.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                EnrollmentWarningChips(warnings = state.warnings)
            }
        }

        EnrollmentFixationDot(
            progress = phaseProgress,
            pulsing = pulsing,
            modifier = Modifier.align(Alignment.Center)
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .weight(1f)
                    .testTag("enrollment_cancel_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = "Cancel scan", style = MaterialTheme.typography.labelLarge)
            }
            if (state.phase == EnrollmentPhase.BROW_RAISE) {
                Button(
                    onClick = onSkipBrow,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .weight(1f)
                        .testTag("enrollment_skip_brow_button")
                ) {
                    Text(text = "Skip brow step", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/** Non-interactive warning chips (one per [EnrollmentWarning]), wrapping onto new lines; chips take no touches. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EnrollmentWarningChips(warnings: Set<EnrollmentWarning>) {
    val chipColor = MaterialTheme.colorScheme.tertiary
    FlowRow(
        modifier = Modifier.testTag("enrollment_warning_chips"),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (warning in warnings) {
            Text(
                text = enrollmentWarningLabel(warning),
                style = MaterialTheme.typography.labelSmall,
                color = chipColor,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(chipColor.copy(alpha = 0.16f))
                    .border(1.dp, chipColor.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .testTag("enrollment_warning_chip_${warning.name}")
            )
        }
    }
}

/**
 * Centred fixation dot with the phase-progress ring. The halo pulses only while [pulsing]; the pulse is
 * read in the draw phase, so it redraws without recomposing and without rebuilding the cached geometry.
 */
@Composable
private fun EnrollmentFixationDot(
    progress: Float,
    pulsing: Boolean,
    modifier: Modifier = Modifier
) {
    val dotColor = MaterialTheme.colorScheme.primary
    val haloScale: State<Float> = if (pulsing) {
        rememberInfiniteTransition(label = "enrollment_dot_pulse").animateFloat(
            initialValue = 0.86f,
            targetValue = 1.24f,
            animationSpec = enrollmentDotPulseSpec,
            label = "enrollment_dot_pulse_scale"
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }
    val progressPercent = (progress * 100f).roundToInt()
    val description = if (pulsing) {
        "Fixation dot, look here. Step progress $progressPercent percent"
    } else {
        "Fixation dot. Step progress $progressPercent percent"
    }

    Spacer(
        modifier = modifier
            .size(150.dp)
            .semantics { contentDescription = description }
            .testTag("enrollment_fixation_dot")
            .drawWithCache {
                val center = Offset(size.width / 2f, size.height / 2f)
                val haloColor = dotColor.copy(alpha = 0.20f)
                val haloBaseRadiusPx = 42.dp.toPx()
                val ringRadiusPx = 30.dp.toPx()
                val ringStroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                val trackColor = dotColor.copy(alpha = 0.25f)
                val arcTopLeft = Offset(center.x - ringRadiusPx, center.y - ringRadiusPx)
                val arcSize = Size(ringRadiusPx * 2f, ringRadiusPx * 2f)
                val sweepAngle = progress * 360f
                val dotRadiusPx = 7.dp.toPx()
                onDrawBehind {
                    drawCircle(
                        color = haloColor,
                        radius = haloBaseRadiusPx * haloScale.value,
                        center = center
                    )
                    drawCircle(
                        color = trackColor,
                        radius = ringRadiusPx,
                        center = center,
                        style = ringStroke
                    )
                    if (sweepAngle > 0f) {
                        drawArc(
                            color = dotColor,
                            startAngle = -90f,
                            sweepAngle = sweepAngle,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = ringStroke
                        )
                    }
                    drawCircle(
                        color = Color.White,
                        radius = dotRadiusPx + 2.dp.toPx(),
                        center = center
                    )
                    drawCircle(
                        color = dotColor,
                        radius = dotRadiusPx,
                        center = center
                    )
                }
            }
    )
}

/** Pulse of the fixation dot. Immutable, so one instance serves every recomposition. */
internal val enrollmentDotPulseSpec: InfiniteRepeatableSpec<Float> = infiniteRepeatable(
    animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
    repeatMode = RepeatMode.Reverse
)

/** True for the phases that consume frames (POSITIONING..BROW_RAISE); the overlay is shown only then. */
fun isEnrollmentScanPhaseActive(phase: EnrollmentPhase): Boolean =
    phase == EnrollmentPhase.POSITIONING ||
        phase == EnrollmentPhase.EYES_OPEN ||
        phase == EnrollmentPhase.EYES_CLOSED ||
        phase == EnrollmentPhase.BROW_RAISE

/** The fixation dot pulses while the person should be looking at it. */
internal fun enrollmentGuidanceNeedsFixation(guidance: EnrollmentGuidance): Boolean = when (guidance) {
    EnrollmentGuidance.HOLD_STILL,
    EnrollmentGuidance.LOOK_AT_DOT,
    EnrollmentGuidance.OPEN_EYES,
    EnrollmentGuidance.RAISE_BROWS -> true
    else -> false
}

/** One plain-English line per [EnrollmentGuidance]. */
fun enrollmentGuidanceText(guidance: EnrollmentGuidance): String = when (guidance) {
    EnrollmentGuidance.NO_FACE -> "No face found. Face the front camera."
    EnrollmentGuidance.CENTER_FACE -> "Move so your face is in the centre of the frame."
    EnrollmentGuidance.MOVE_CLOSER -> "Move a little closer to the phone."
    EnrollmentGuidance.MOVE_BACK -> "Move a little further from the phone."
    EnrollmentGuidance.MORE_LIGHT -> "Too dark. Turn towards a light source."
    EnrollmentGuidance.REDUCE_GLARE -> "Glare on your eyes. Tilt the phone or move away from the light."
    EnrollmentGuidance.HOLD_STILL -> "Hold still and look at the dot."
    EnrollmentGuidance.LOOK_AT_DOT -> "Look at the dot and blink as you normally would."
    EnrollmentGuidance.CLOSE_EYES -> "Now close both eyes."
    EnrollmentGuidance.KEEP_EYES_CLOSED -> "Keep both eyes closed."
    EnrollmentGuidance.OPEN_EYES -> "Open your eyes and look at the dot."
    EnrollmentGuidance.RAISE_BROWS -> "Raise your eyebrows and hold them up."
    EnrollmentGuidance.DONE -> "Done. Your enrollment is saved on this device."
    EnrollmentGuidance.FAILED -> "The scan could not finish."
}

/** "Step n of 4 — ..." for the active phases; a plain title for the terminal ones. */
fun enrollmentPhaseLabel(phase: EnrollmentPhase): String = when (phase) {
    EnrollmentPhase.IDLE -> "Enrollment scan"
    EnrollmentPhase.POSITIONING -> "Step 1 of 4 — Position your face"
    EnrollmentPhase.EYES_OPEN -> "Step 2 of 4 — Eyes open"
    EnrollmentPhase.EYES_CLOSED -> "Step 3 of 4 — Eyes closed"
    EnrollmentPhase.BROW_RAISE -> "Step 4 of 4 — Raise your brows"
    EnrollmentPhase.COMPLETE -> "Enrollment complete"
    EnrollmentPhase.FAILED -> "Enrollment failed"
}

/** Short chip label per [EnrollmentWarning]. */
fun enrollmentWarningLabel(warning: EnrollmentWarning): String = when (warning) {
    EnrollmentWarning.LOW_LIGHT -> "Low light"
    EnrollmentWarning.GLARE -> "Glare"
    EnrollmentWarning.SINGLE_EYE -> "One eye only"
    EnrollmentWarning.FEW_NATURAL_BLINKS -> "Few natural blinks"
    EnrollmentWarning.EYE_RANGE_SMALL_DEFAULTS_USED -> "Small eye range, defaults used"
    EnrollmentWarning.BROW_SKIPPED -> "Brow step skipped"
}

/** [enrollmentWarningLabel] for a stored warning name ([EnrollmentBaseline.warnings]); unknown names pass through. */
fun enrollmentWarningLabelForName(name: String): String =
    EnrollmentWarning.entries.firstOrNull { it.name == name }?.let(::enrollmentWarningLabel) ?: name

/** Why a scan failed, in plain English (null = unknown reason). */
fun enrollmentFailureText(failure: EnrollmentFailure?): String = when (failure) {
    EnrollmentFailure.POSITIONING_TIMEOUT ->
        "Your face could not be positioned in time. Centre it in the frame, in good light, and try again."
    EnrollmentFailure.FACE_LOST -> "The face was lost too often. Keep facing the camera and hold still."
    EnrollmentFailure.EYES_DID_NOT_CLOSE -> "Eye closure was not detected. Close both eyes fully when asked."
    EnrollmentFailure.EYES_DID_NOT_REOPEN -> "The eyes did not reopen in time."
    EnrollmentFailure.SIMULATED_FRAMES -> "Simulated frames are not measurements. The scan needs the live front camera."
    EnrollmentFailure.CANCELLED -> "The scan was cancelled."
    null -> "The scan failed."
}

/**
 * Secondary line under the guidance: the retry count, the face-position hint while positioning, and the
 * elapsed time. Null when there is nothing worth saying (first attempt, face centred, under a second).
 */
internal fun enrollmentSecondaryLine(state: EnrollmentScanState): String? {
    val parts = ArrayList<String>(3)
    if (state.retries > 0) parts.add("Retry ${state.retries}")
    if (state.phase == EnrollmentPhase.POSITIONING) {
        parts.add(if (state.liveFaceCentered) "Face in position" else "Face not in position yet")
    }
    val seconds = state.elapsedMs / 1000L
    if (seconds >= 1L) parts.add("${seconds}s elapsed")
    return if (parts.isEmpty()) null else parts.joinToString(" • ")
}

/** "MMM d, yyyy HH:mm" (Locale.US, device time zone) for [EnrollmentBaseline.capturedAtMs]. */
fun formatEnrollmentCapturedAt(capturedAtMs: Long): String =
    SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US).format(Date(capturedAtMs))

/**
 * The measured numbers of a baseline, one line each (Locale.US). Never invents a value: a brow raise that
 * was not measured reads "not measured", zero natural blinks read "none".
 */
fun enrollmentBaselineSummaryLines(baseline: EnrollmentBaseline): List<String> {
    val lines = ArrayList<String>(6)
    lines.add(
        String.format(
            Locale.US,
            "Eye levels: open %.2f • closed %.2f (left %.2f, right %.2f)",
            baseline.openEyeLevel,
            baseline.closedEyeLevel,
            baseline.leftOpenLevel,
            baseline.rightOpenLevel
        )
    )
    lines.add(
        String.format(
            Locale.US,
            "Blink thresholds: close %.2f • open %.2f",
            baseline.closeBlinkThreshold,
            baseline.openBlinkThreshold
        )
    )
    lines.add(String.format(Locale.US, "Command blink: at least %d ms", baseline.intentionalBlinkMinMs))
    lines.add(
        if (baseline.naturalBlinkCount > 0) {
            String.format(
                Locale.US,
                "Natural blinks during the scan: %d (median %d ms, p95 %d ms)",
                baseline.naturalBlinkCount,
                baseline.naturalBlinkMedianMs,
                baseline.naturalBlinkP95Ms
            )
        } else {
            "Natural blinks during the scan: none"
        }
    )
    val raised = baseline.raisedBrowRatio
    lines.add(
        if (raised != null) {
            String.format(Locale.US, "Brow ratio: neutral %.3f • raised %.3f", baseline.neutralBrowRatio, raised)
        } else {
            String.format(Locale.US, "Brow ratio: neutral %.3f • raised not measured", baseline.neutralBrowRatio)
        }
    )
    lines.add(
        String.format(
            Locale.US,
            "Conditions: %d frames • eye confidence %.0f%% • luma %d/255 • IOD %.1f px%s",
            baseline.frameCount,
            baseline.meanEyeConfidence * 100f,
            baseline.meanAmbientLuma,
            baseline.baselineEyeDistancePx,
            if (baseline.deviceModel.isNotBlank()) " • ${baseline.deviceModel}" else ""
        )
    )
    return lines
}

/** One-line summary of a completed scan's applied settings (Locale.US). */
fun enrollmentCompleteSummary(baseline: EnrollmentBaseline): String =
    String.format(
        Locale.US,
        "Scan complete: blink close %.2f / open %.2f, command blink at least %d ms, brow neutral %.3f%s.",
        baseline.closeBlinkThreshold,
        baseline.openBlinkThreshold,
        baseline.intentionalBlinkMinMs,
        baseline.neutralBrowRatio,
        baseline.raisedBrowRatio?.let { String.format(Locale.US, " / raised %.3f", it) } ?: " (brow raise not measured)"
    )
