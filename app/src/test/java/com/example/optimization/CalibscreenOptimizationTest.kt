package com.example.optimization

import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.data.model.AnalysisResolutionTier
import com.example.data.model.CalibrationTargetPoint
import com.example.data.model.DEFAULT_AFFINE_MATRIX_2X6
import com.example.data.model.GazeFeatureVector
import com.example.domain.GazeCalibrationMath
import com.example.ui.screens.CalibrationStudioScreen
import com.example.ui.screens.calibrationSampleCounterText
import com.example.ui.screens.calibrationTargetPulseSpec
import com.example.ui.screens.fitTargetPillCoordLabels
import com.example.ui.screens.fitTargetSummaryCoordLabels
import com.example.ui.screens.formatEyeTelemetryLine
import com.example.ui.screens.formatFittedMatrixTextOrNull
import com.example.ui.screens.formatRawFeatureVectorLine
import com.example.ui.viewmodel.CalibrationFlowStep
import com.example.ui.viewmodel.CalibrationWizardState
import com.example.ui.viewmodel.LiveGazeHudState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.Random

/**
 * Equivalence tests for the CalibrationStudioScreen.kt optimization pass. Every optimized helper is
 * pinned to a frozen copy of the pre-optimization code (the `frozen*` functions at the bottom of this
 * file) on many seeded random inputs plus edge cases. Plain JVM tests; the composition-level checks
 * (per-frame HUD holder, split skippable sections) are in [CalibscreenOptimizationUiTest] below.
 */
class CalibscreenOptimizationTest {

    @Test
    fun `eye telemetry line matches the frozen five-format implementation`() {
        val rng = Random(0xCA11_0001L)
        val tiers = tierLabels()
        for (i in 0 until 20_000) {
            val f = randomFeature(rng, i)
            val tier = tiers[rng.nextInt(tiers.size)]
            val expected = frozenFormatEyeTelemetryLine(f, tier)
            assertEquals("feature #$i ($f, tier=$tier)", expected, formatEyeTelemetryLine(f, tier))
            // The field-wise overload the composable calls must print the same text.
            assertEquals(
                "field overload #$i",
                expected,
                formatEyeTelemetryLine(
                    faceDetected = f.faceDetected,
                    leftEyeConfidence = f.leftEyeConfidence,
                    rightEyeConfidence = f.rightEyeConfidence,
                    eyeDistancePx = f.eyeDistancePx,
                    combinedEyeOpenProb = f.combinedEyeOpenProb,
                    ambientLuma = f.ambientLuma,
                    isLowLightDegraded = f.isLowLightDegraded,
                    tierLabel = tier
                )
            )
        }
        // Edge cases: placeholders vs measurements, extreme luma, both low-light states.
        for (face in listOf(true, false)) {
            for (lowLight in listOf(true, false)) {
                for (luma in intArrayOf(0, 255, -1, Int.MIN_VALUE, Int.MAX_VALUE)) {
                    for (v in floatArrayOf(0f, -0f, 0.005f, 0.995f, 1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                        val f = GazeFeatureVector(
                            faceDetected = face,
                            isLowLightDegraded = lowLight,
                            ambientLuma = luma,
                            leftEyeConfidence = v,
                            rightEyeConfidence = -v,
                            leftEyeOpenProb = v,
                            rightEyeOpenProb = v,
                            eyeDistancePx = v.toDouble(),
                            timestampMs = 0L
                        )
                        for (tier in tiers) {
                            assertEquals(frozenFormatEyeTelemetryLine(f, tier), formatEyeTelemetryLine(f, tier))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `raw feature vector line matches the frozen inline format`() {
        val rng = Random(0xCA11_0002L)
        for (i in 0 until 20_000) {
            val f = randomFeature(rng, i)
            assertEquals(
                "feature #$i",
                frozenFormatRawFeatureLine(f),
                formatRawFeatureVectorLine(f.gx, f.gy, f.yaw, f.pitch, f.roll)
            )
        }
        for (d in SPECIAL_DOUBLES) {
            val f = GazeFeatureVector(gx = d, gy = -d, yaw = d, pitch = -d, roll = d, timestampMs = 0L)
            assertEquals(frozenFormatRawFeatureLine(f), formatRawFeatureVectorLine(f.gx, f.gy, f.yaw, f.pitch, f.roll))
        }
    }

    @Test
    fun `sample counter text matches the frozen String_format version`() {
        val rng = Random(0xCA11_0003L)
        val ints = intArrayOf(0, 1, 9, 10, 18, 99, 100, 999, 1000, 12_345, -1, -18, Int.MIN_VALUE, Int.MAX_VALUE)
        val floats = floatArrayOf(
            0f, -0f, 0.005f, 0.0099f, 0.01f, 0.5f, 0.999f, 1f, 1.5f, -0.5f, 1e9f, -1e9f,
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MIN_VALUE, Float.MAX_VALUE
        )
        fun pickInt() = if (rng.nextInt(4) == 0) ints[rng.nextInt(ints.size)] else rng.nextInt(400) - 20
        fun pickFloat() = if (rng.nextInt(4) == 0) floats[rng.nextInt(floats.size)] else rng.nextFloat() * 1.2f - 0.1f
        for (i in 0 until 20_000) {
            val face = rng.nextInt(5) != 0
            val settling = rng.nextInt(4) == 0
            val collected = pickInt()
            val required = pickInt()
            val progress = pickFloat()
            val total = pickInt()
            assertEquals(
                "case #$i",
                frozenSampleCounterText(face, settling, collected, required, progress, total),
                calibrationSampleCounterText(face, settling, collected, required, progress, total)
            )
        }
    }

    @Test
    fun `precomputed pill and summary coordinate labels match the frozen per-composition formats`() {
        val targets = GazeCalibrationMath.FIVE_POINT_CALIBRATION_TARGETS
        assertEquals(targets.size, fitTargetPillCoordLabels.size)
        assertEquals(targets.size, fitTargetSummaryCoordLabels.size)
        targets.forEachIndexed { position, pt ->
            assertEquals(frozenPillCoordLabel(pt), fitTargetPillCoordLabels[position])
            assertEquals(frozenSummaryCoordLabel(pt), fitTargetSummaryCoordLabels[position])
        }
    }

    @Test
    fun `fitted matrix text matches the frozen inline format for every size and value`() {
        val rng = Random(0xCA11_0004L)
        for (i in 0 until 10_000) {
            val size = if (rng.nextInt(3) == 0) rng.nextInt(25) else 12
            val m = List(size) { randomDouble(rng) }
            assertEquals("matrix #$i (size $size)", frozenMatrixText(m), formatFittedMatrixTextOrNull(m))
        }
        assertEquals(frozenMatrixText(emptyList()), formatFittedMatrixTextOrNull(emptyList()))
        val specials = List(12) { SPECIAL_DOUBLES[it % SPECIAL_DOUBLES.size] }
        assertEquals(frozenMatrixText(specials), formatFittedMatrixTextOrNull(specials))
        assertEquals(
            frozenMatrixText(DEFAULT_AFFINE_MATRIX_2X6),
            formatFittedMatrixTextOrNull(DEFAULT_AFFINE_MATRIX_2X6)
        )
    }

    @Test
    fun `hoisted pulse spec equals the spec the overlay built on every recomposition`() {
        val frozen = frozenPulseSpec()
        assertEquals(frozen, calibrationTargetPulseSpec)
        assertEquals(frozen.hashCode(), calibrationTargetPulseSpec.hashCode())
    }
}

/**
 * Composition-level equivalence: the screen restructuring (per-frame HUD passed through one stable
 * holder, sections split into skippable composables fed primitives) must show exactly the text the
 * pre-optimization screen showed, after every per-frame / per-sample / per-keystroke update.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class CalibscreenOptimizationUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun idleOverview_tracksEveryHudFrame_exactlyAsTheFrozenFormatting() {
        val rng = Random(0xCA11_0101L)
        var hud by mutableStateOf(LiveGazeHudState(rawFeature = GazeFeatureVector(timestampMs = 0L)))
        composeRule.setContent {
            CalibrationStudioScreen(
                calibrationState = CalibrationWizardState(),
                hudState = hud,
                onStartWizard = {},
                onCancelWizard = {},
                onUpdateProfileName = {},
                onSaveProfile = {},
                onSnapSimulatorToCurrentTarget = { _, _ -> }
            )
        }
        val tiers = AnalysisResolutionTier.entries
        for (frame in 0 until 60) {
            // Every few frames repeat the previous vector with only its timestamp moved (as no-face frames
            // do): the lines that now skip on unchanged fields must still show the right text.
            val f = if (frame % 5 == 4) {
                hud.rawFeature.copy(timestampMs = frame.toLong())
            } else {
                randomFeature(rng, frame)
            }
            val tier = tiers[rng.nextInt(tiers.size)]
            val meanPct = randomNullableFloat(rng)
            val worstPct = randomNullableFloat(rng)
            val meanMm = randomNullableFloat(rng)?.toDouble()
            val hasCal = rng.nextBoolean()
            val profileName = "Profile-$frame-" + "x".repeat(rng.nextInt(20))
            composeRule.runOnIdle {
                hud = hud.copy(
                    rawFeature = f,
                    resolutionTier = tier,
                    activeErrorRadiusPct = meanPct,
                    activeMaxQuadrantErrorPct = worstPct,
                    activeErrorMm = meanMm,
                    hasActiveCalibration = hasCal,
                    activeProfileName = profileName
                )
            }
            composeRule.onNodeWithTag("calibration_eye_telemetry_text")
                .assertTextEquals(frozenFormatEyeTelemetryLine(f, tier.shortLabel))
            composeRule.onAllNodesWithText(frozenFormatRawFeatureLine(f)).assertCountEquals(1)

            val expected = frozenOverviewBadgeTexts(meanPct, worstPct, meanMm, hasCal, profileName)
            for ((text, count) in expected.groupingBy { it }.eachCount()) {
                composeRule.onAllNodesWithText(text).assertCountEquals(count)
            }
        }
    }

    @Test
    fun wizard_counterHeadingAndPills_matchFrozenTextAfterEverySample() {
        val rng = Random(0xCA11_0102L)
        var hud by mutableStateOf(LiveGazeHudState(rawFeature = GazeFeatureVector(faceDetected = true, timestampMs = 0L)))
        var state by mutableStateOf(CalibrationWizardState(step = CalibrationFlowStep.FIT_5_POINTS))
        composeRule.setContent {
            CalibrationStudioScreen(
                calibrationState = state,
                hudState = hud,
                onStartWizard = {},
                onCancelWizard = {},
                onUpdateProfileName = {},
                onSaveProfile = {},
                onSnapSimulatorToCurrentTarget = { _, _ -> }
            )
        }
        val fit = GazeCalibrationMath.FIVE_POINT_CALIBRATION_TARGETS
        for (sample in 0 until 60) {
            val index = rng.nextInt(11) - 1 // -1..9: out-of-range indices fall back to the first target
            val required = 1 + rng.nextInt(30)
            val collected = rng.nextInt(required + 1)
            val counts = buildMap { for (p in 0 until 9) if (rng.nextInt(4) != 0) put(p, rng.nextInt(40)) }
            val next = state.copy(
                step = if (index in 5..8) CalibrationFlowStep.VALIDATE_4_POINTS else CalibrationFlowStep.FIT_5_POINTS,
                currentTargetIndex = index,
                requiredSamplesPerTarget = required,
                samplesCollectedForCurrentTarget = collected,
                currentTargetProgress = collected.toFloat() / required,
                totalCollectedSamples = rng.nextInt(200),
                isSettlingDelay = rng.nextInt(4) == 0,
                pointSampleCounts = counts
            )
            val face = rng.nextInt(5) != 0
            composeRule.runOnIdle {
                state = next
                hud = hud.copy(rawFeature = hud.rawFeature.copy(faceDetected = face, timestampMs = sample.toLong()))
            }
            composeRule.onNodeWithTag("calibration_sample_counter_text")
                .assertTextEquals(frozenSampleCounterText(next, face))
            composeRule.onNodeWithTag("active_calibration_target_label")
                .assertTextEquals(frozenTargetHeading(frozenTargetForIndex(index)))
            fit.forEach { pt ->
                val pill = composeRule.onNodeWithTag("calibration_step_pill_${pt.index}").onChildren()
                pill.assertCountEquals(3)
                pill[0].assertTextEquals(if (index > pt.index) "✓ P${pt.index + 1}" else "P${pt.index + 1}")
                pill[1].assertTextEquals(frozenPillCoordLabel(pt))
                pill[2].assertTextEquals("${counts[pt.index] ?: 0}/$required")
            }
        }
    }

    @Test
    fun summaryReport_matchesFrozenTextAcrossKeystrokesAndMatrixChanges() {
        val rng = Random(0xCA11_0103L)
        val fivePlusFour = ApplicationProvider.getApplicationContext<Context>().getString(R.string.calibration_mode_5_plus_4)
        var state by mutableStateOf(CalibrationWizardState(step = CalibrationFlowStep.COMPLETED_SUMMARY))
        composeRule.setContent {
            CalibrationStudioScreen(
                calibrationState = state,
                hudState = LiveGazeHudState(rawFeature = GazeFeatureVector(timestampMs = 0L)),
                onStartWizard = {},
                onCancelWizard = {},
                onUpdateProfileName = {},
                onSaveProfile = {},
                onSnapSimulatorToCurrentTarget = { _, _ -> }
            )
        }
        val fit = GazeCalibrationMath.FIVE_POINT_CALIBRATION_TARGETS
        for (round in 0 until 30) {
            val size = if (rng.nextInt(4) == 0) rng.nextInt(20) else 12
            val counts = buildMap { for (p in 0 until 5) if (rng.nextInt(4) != 0) put(p, rng.nextInt(40)) }
            val base = state.copy(
                includeHeldOutValidation = rng.nextBoolean(),
                metricsAreHeldOut = rng.nextBoolean(),
                validationMeanErrorPct = randomMetric(rng),
                validationMaxErrorPct = randomMetric(rng),
                validationMeanErrorMm = randomMetric(rng),
                totalCollectedSamples = rng.nextInt(300),
                pointSampleCounts = counts,
                recommendedGridLabel = if (rng.nextInt(3) == 0) " " else "Grid-$round",
                fittedMatrix = List(size) { randomDouble(rng) }
            )
            // A few keystrokes (only draftProfileName changes), then the next round changes the report.
            for (k in 0 until 3) {
                val s = base.copy(draftProfileName = "Name $round/$k")
                composeRule.runOnIdle { state = s }
                assertSummaryMatchesFrozen(s, fit, fivePlusFour)
            }
        }
    }

    private fun assertSummaryMatchesFrozen(s: CalibrationWizardState, fit: List<CalibrationTargetPoint>, fivePlusFour: String) {
        val expected = frozenSummaryTexts(s, fit, fivePlusFour)
        for ((text, count) in expected.groupingBy { it }.eachCount()) {
            composeRule.onAllNodesWithText(text).assertCountEquals(count)
        }
        composeRule.onAllNodesWithTag("calibration_in_sample_caveat").assertCountEquals(if (s.metricsAreHeldOut) 0 else 1)
    }
}

// ---------------------------------------------------------------------------------------------------
// Random inputs
// ---------------------------------------------------------------------------------------------------

private val SPECIAL_DOUBLES = doubleArrayOf(
    0.0, -0.0, 0.0005, -0.0005, 0.0004999, 0.05, -0.05, 0.005, -0.005, 0.125, 2.5, -2.5, 1e-300, -1e-300,
    1e15, -1e15, 1.0E300, Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE,
    Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
)

private fun randomDouble(rng: Random): Double = when (rng.nextInt(10)) {
    0 -> SPECIAL_DOUBLES[rng.nextInt(SPECIAL_DOUBLES.size)]
    1 -> (rng.nextInt(20_001) - 10_000) / 1000.0 + 0.0005 // on a %.3f rounding boundary
    2 -> (rng.nextInt(2_001) - 1_000) / 10.0 + 0.05 // on a %.1f rounding boundary
    3 -> (rng.nextInt(2_001) - 1_000) / 100.0 + 0.005 // on a %.2f rounding boundary
    4 -> rng.nextGaussian() * 1e6
    else -> rng.nextGaussian() * 30.0
}

private fun randomFloat(rng: Random): Float = when (rng.nextInt(10)) {
    0 -> floatArrayOf(0f, -0f, 0.005f, 0.995f, 1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE)
        .let { it[rng.nextInt(it.size)] }
    1 -> rng.nextInt(1_001) / 1000f + 0.0005f
    2 -> rng.nextGaussian().toFloat() * 10f
    else -> rng.nextFloat()
}

private fun randomFeature(rng: Random, i: Int): GazeFeatureVector {
    val face = rng.nextInt(4) != 0
    return if (face) {
        GazeFeatureVector(
            gx = randomDouble(rng),
            gy = randomDouble(rng),
            yaw = randomDouble(rng),
            pitch = randomDouble(rng),
            roll = randomDouble(rng),
            eyeDistancePx = randomDouble(rng),
            leftEyeOpenProb = randomFloat(rng),
            rightEyeOpenProb = randomFloat(rng),
            leftEyeConfidence = randomFloat(rng),
            rightEyeConfidence = randomFloat(rng),
            isSingleEyeMode = rng.nextBoolean(),
            faceDetected = true,
            ambientLuma = if (rng.nextInt(8) == 0) rng.nextInt() else rng.nextInt(256),
            isLowLightDegraded = rng.nextInt(3) == 0,
            timestampMs = i.toLong()
        )
    } else {
        // The engine's no-face vector: placeholders, real luma / low-light only.
        GazeFeatureVector(
            leftEyeOpenProb = 0f,
            rightEyeOpenProb = 0f,
            leftEyeConfidence = 0f,
            rightEyeConfidence = 0f,
            faceDetected = false,
            ambientLuma = if (rng.nextInt(8) == 0) rng.nextInt() else rng.nextInt(256),
            isLowLightDegraded = rng.nextInt(3) == 0,
            timestampMs = i.toLong()
        )
    }
}

private fun randomNullableFloat(rng: Random): Float? = when (rng.nextInt(5)) {
    0 -> null
    1 -> Float.NaN
    else -> rng.nextFloat() * 20f
}

private fun randomMetric(rng: Random): Double = when (rng.nextInt(5)) {
    0 -> Double.NaN
    1 -> Double.POSITIVE_INFINITY
    else -> rng.nextDouble() * 20.0
}

private fun tierLabels(): List<String> =
    AnalysisResolutionTier.entries.map { it.shortLabel } + listOf("", "%d %s %%", "tier\nwith newline", "Ü-ß-日本")

// ---------------------------------------------------------------------------------------------------
// Frozen pre-optimization implementations (copied verbatim from CalibrationStudioScreen.kt at f3aac53)
// ---------------------------------------------------------------------------------------------------

private const val FROZEN_NOT_CALIBRATED_TEXT = "Not calibrated"
private const val FROZEN_NOT_MEASURED_TEXT = "Not measured"
private const val FROZEN_NOT_VALIDATED_TEXT = "Not validated"
private const val FROZEN_FIT_ONLY_TEXT = "5-point fit only"
private const val FROZEN_NOT_DETECTED_TEXT = "--"

private fun frozenFormatEyeTelemetryLine(f: GazeFeatureVector, tierLabel: String): String {
    val leftConf = if (f.faceDetected) String.format(Locale.US, "%.0f%%", f.leftEyeConfidence * 100f) else FROZEN_NOT_DETECTED_TEXT
    val rightConf = if (f.faceDetected) String.format(Locale.US, "%.0f%%", f.rightEyeConfidence * 100f) else FROZEN_NOT_DETECTED_TEXT
    val iod = if (f.faceDetected) String.format(Locale.US, "%.1f px", f.eyeDistancePx) else FROZEN_NOT_DETECTED_TEXT
    val eyeOpen = if (f.faceDetected) String.format(Locale.US, "%.2f", f.combinedEyeOpenProb) else FROZEN_NOT_DETECTED_TEXT
    return String.format(
        Locale.US,
        "Eye Confidence: Left %s • Right %s | IOD: %s | Eye Open: %s | Tier: %s | Luma: %d/255%s",
        leftConf,
        rightConf,
        iod,
        eyeOpen,
        tierLabel,
        f.ambientLuma,
        if (f.isLowLightDegraded) " (LOW-LIGHT DEGRADED)" else ""
    )
}

private fun frozenFormatRawFeatureLine(f: GazeFeatureVector): String = String.format(
    Locale.US,
    "f_raw = [1.0, gx=%.3f, gy=%.3f, yaw=%.1f°, pitch=%.1f°, roll=%.1f°]",
    f.gx, f.gy, f.yaw, f.pitch, f.roll
)

private fun frozenSampleCounterText(
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
    else -> String.format(
        Locale.US,
        "Collecting gaze samples: %d/%d at point (%d%%) • Total collected: %d pts",
        samplesCollectedForCurrentTarget,
        requiredSamplesPerTarget,
        (currentTargetProgress * 100).toInt(),
        totalCollectedSamples
    )
}

private fun frozenSampleCounterText(state: CalibrationWizardState, faceDetected: Boolean): String =
    frozenSampleCounterText(
        faceDetected,
        state.isSettlingDelay,
        state.samplesCollectedForCurrentTarget,
        state.requiredSamplesPerTarget,
        state.currentTargetProgress,
        state.totalCollectedSamples
    )

private fun frozenPillCoordLabel(pt: CalibrationTargetPoint): String =
    String.format(Locale.US, "(%.2f,%.2f)", pt.normX, pt.normY)

private fun frozenSummaryCoordLabel(pt: CalibrationTargetPoint): String =
    String.format(Locale.US, "%s — Coord (%.2f, %.2f)", pt.shortName, pt.normX, pt.normY)

private fun frozenTargetHeading(target: CalibrationTargetPoint): String = String.format(
    Locale.US,
    "%s • Screen Coord (%.2f, %.2f)",
    target.label,
    target.normX,
    target.normY
)

private fun frozenTargetForIndex(index: Int): CalibrationTargetPoint {
    val allTargets = GazeCalibrationMath.CALIBRATION_AND_VALIDATION_TARGETS
    return allTargets.getOrNull(index) ?: allTargets.first()
}

private fun frozenMatrixText(m: List<Double>): String? =
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

private fun frozenPulseSpec(): InfiniteRepeatableSpec<Float> = infiniteRepeatable(
    animation = tween(durationMillis = 750, easing = FastOutSlowInEasing),
    repeatMode = RepeatMode.Reverse
)

private fun frozenFormatErrorPctOrNull(value: Double?): String? =
    value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "±%.1f%%", it) }

private fun frozenFormatApproxMmOrNull(value: Double?): String? =
    value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "~%.1f mm (approximate)", it) }

/** Label / value / sub of both idle-overview metric badges, as the frozen CalibrationIdleOverview built them. */
private fun frozenOverviewBadgeTexts(
    meanErrPct: Float?,
    worstErrPct: Float?,
    meanErrMm: Double?,
    fitOnly: Boolean,
    activeProfileName: String
): List<String> {
    val meanErrText = frozenFormatErrorPctOrNull(meanErrPct?.toDouble())
    val worstErrText = frozenFormatErrorPctOrNull(worstErrPct?.toDouble())
    val unmeasuredValue = if (fitOnly) FROZEN_NOT_VALIDATED_TEXT else FROZEN_NOT_CALIBRATED_TEXT
    return listOf(
        "MEAN SCREEN ERR",
        meanErrText ?: unmeasuredValue,
        when {
            meanErrText != null -> frozenFormatApproxMmOrNull(meanErrMm) ?: "mm not measured"
            fitOnly -> "$FROZEN_FIT_ONLY_TEXT, run 5+4 validation to measure"
            else -> "Run calibration to measure"
        },
        "WORST QUADRANT",
        worstErrText ?: unmeasuredValue,
        when {
            worstErrText != null -> "Profile: ${activeProfileName.take(16)}"
            fitOnly -> "$FROZEN_FIT_ONLY_TEXT • Profile: ${activeProfileName.take(16)}"
            else -> "Default matrix, not validated"
        }
    )
}

/** Every text the frozen CalibrationValidationSummaryCard showed above the profile-name field. */
private fun frozenSummaryTexts(
    s: CalibrationWizardState,
    fit: List<CalibrationTargetPoint>,
    fivePlusFour: String
): List<String> {
    val metricsAreHeldOut = s.metricsAreHeldOut
    val meanErrText = frozenFormatErrorPctOrNull(s.validationMeanErrorPct)
    val worstErrText = frozenFormatErrorPctOrNull(s.validationMaxErrorPct)
    val texts = mutableListOf(
        if (s.includeHeldOutValidation) "5-Point Fit + 4-Point Validation Report" else "5-Point Screen Calibration Complete",
        if (metricsAreHeldOut) "MEAN SCREEN ERROR" else "FIT RESIDUAL (IN-SAMPLE)",
        meanErrText ?: FROZEN_NOT_MEASURED_TEXT,
        if (metricsAreHeldOut) {
            frozenFormatApproxMmOrNull(s.validationMeanErrorMm) ?: "mm not measured"
        } else {
            "not validated"
        },
        if (metricsAreHeldOut) "WORST QUADRANT" else "WORST FIT POINT",
        worstErrText ?: FROZEN_NOT_MEASURED_TEXT,
        if (metricsAreHeldOut) {
            "Total Samples: ${s.totalCollectedSamples}"
        } else {
            "not validated • ${s.totalCollectedSamples} samples"
        },
        "Collected Gaze Samples by Screen Coordinate:"
    )
    if (!metricsAreHeldOut) {
        texts += if (s.includeHeldOutValidation) {
            "No held-out validation sample was scored, so the screen error of this fit was " +
                "not measured. Run '$fivePlusFour' again " +
                "to measure it."
        } else {
            "Quick fit: these are residuals on the same ${s.totalCollectedSamples} " +
                "samples the fit was trained on. No held-out point was measured, so the real screen " +
                "error is unknown and usually larger. Run " +
                "'$fivePlusFour' to measure it."
        }
    }
    fit.forEach { pt ->
        texts += frozenSummaryCoordLabel(pt)
        texts += "${s.pointSampleCounts[pt.index] ?: 0} gaze samples"
    }
    if (s.recommendedGridLabel.isNotBlank()) {
        texts += "Recommended Layout: ${s.recommendedGridLabel}"
    }
    frozenMatrixText(s.fittedMatrix)?.let { texts += it }
    return texts
}
