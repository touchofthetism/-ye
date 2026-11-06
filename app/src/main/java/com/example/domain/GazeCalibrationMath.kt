package com.example.domain

import com.example.data.model.BlinkClassificationEvent
import com.example.data.model.BlinkType
import com.example.data.model.CalibrationTargetPoint
import com.example.data.model.DEFAULT_AFFINE_MATRIX_2X6
import com.example.data.model.FixationClusterPoint
import com.example.data.model.GazeFeatureVector
import com.example.data.model.ValidationMetricsResult
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min

/**
 * Mathematical engine implementing:
 * 1. Standardized-Feature Ridge-Regularized 2x6 Cross-Coupled Ordinary Least Squares (OLS)
 *    mapping standardized f_std = [1, gx/0.25, gy/0.20, yaw/20, pitch/15, roll/15 + 0.5*distScale]^T
 *    to normalized screen coordinates (x_screen, y_screen) with per-sample penalty lambda = 2.5e-2
 *    (iris terms scaled by their own mean square, see [fitRidgeAffineMatrix2x6]).
 * 2. 4-Point Held-Out Validation reporting Primary Normalized Screen Error (meanErrorPct),
 *    Worst-Quadrant Error (maxErrorPct), and Approximate Physical Error (~mm, calibrated for
 *    Google Pixel 10 Pro XL 6.8" LTPO OLED 71.3mm x 158.4mm active area or DisplayMetrics fallback).
 * 3. Frame-Interval-Adaptive Exponential Smoothing for 10-15 FPS camera pipelines.
 * 4. Canonical Schmitt-Trigger Hysteresis Blink State Machine (80-349ms Natural, 350-800ms Command,
 *    >800ms Extended Rest).
 */
object GazeCalibrationMath {

    // Google Pixel 10 Pro XL physical active display dimensions (6.8" 20:9 LTPO OLED)
    const val PIXEL_10_PRO_XL_WIDTH_MM = 71.3
    const val PIXEL_10_PRO_XL_HEIGHT_MM = 158.4

    // Single source of truth for the Control Deck layout decision (validated metrics only).
    const val GRID_3X3_MAX_MEAN_ERROR_PCT = 12.0
    const val GRID_3X3_MAX_WORST_QUADRANT_PCT = 16.5
    const val RECALIBRATION_RECOMMENDED_WORST_QUADRANT_PCT = 24.0

    /**
     * True only when both held-out metrics are finite (i.e. actually measured) and within the
     * 3x3 thresholds. Non-finite input (not validated) always yields the protective 2x2 deck.
     */
    fun qualifiesFor3x3Grid(meanErrorPct: Double, maxErrorPct: Double): Boolean =
        meanErrorPct.isFinite() && maxErrorPct.isFinite() &&
            meanErrorPct <= GRID_3X3_MAX_MEAN_ERROR_PCT &&
            maxErrorPct <= GRID_3X3_MAX_WORST_QUADRANT_PCT

    fun gridLayoutLabel(use3x3: Boolean): String =
        if (use3x3) "3×3 Validated Deck (9 tiles)" else "2×2 Protective Region Deck (4 tiles)"

    val FIVE_POINT_CALIBRATION_TARGETS: List<CalibrationTargetPoint> = listOf(
        CalibrationTargetPoint(
            index = 0,
            label = "Fit 1/5 • Center",
            normX = 0.50f,
            normY = 0.50f,
            isValidationPoint = false,
            shortName = "P1 Center",
            guidancePrompt = "Look directly at the Center screen coordinate (50%, 50%) at comfortable reading distance."
        ),
        CalibrationTargetPoint(
            index = 1,
            label = "Fit 2/5 • Top-Left",
            normX = 0.14f,
            normY = 0.16f,
            isValidationPoint = false,
            shortName = "P2 Top-Left",
            guidancePrompt = "Shift your gaze to the Top-Left corner (14%, 16%) while keeping your chin natural."
        ),
        CalibrationTargetPoint(
            index = 2,
            label = "Fit 3/5 • Top-Right",
            normX = 0.86f,
            normY = 0.16f,
            isValidationPoint = false,
            shortName = "P3 Top-Right",
            guidancePrompt = "Sweep across to the Top-Right coordinate (86%, 16%) and hold steady on the bullseye."
        ),
        CalibrationTargetPoint(
            index = 3,
            label = "Fit 4/5 • Bottom-Left",
            normX = 0.14f,
            normY = 0.84f,
            isValidationPoint = false,
            shortName = "P4 Bottom-Left",
            guidancePrompt = "Look down to the Bottom-Left coordinate (14%, 84%) with eyes open so iris centroids lock."
        ),
        CalibrationTargetPoint(
            index = 4,
            label = "Fit 5/5 • Bottom-Right",
            normX = 0.86f,
            normY = 0.84f,
            isValidationPoint = false,
            shortName = "P5 Bottom-Right",
            guidancePrompt = "Fixate on the final Bottom-Right coordinate (86%, 84%) to complete the 5-point regression fit."
        )
    )

    val HELD_OUT_VALIDATION_TARGETS: List<CalibrationTargetPoint> = listOf(
        CalibrationTargetPoint(
            index = 5,
            label = "Validate 1/4 • Upper-Left Quadrant",
            normX = 0.30f,
            normY = 0.32f,
            isValidationPoint = true,
            shortName = "V1 Upper-Left",
            guidancePrompt = "Look at the Upper-Left quadrant centroid (30%, 32%) to verify held-out accuracy."
        ),
        CalibrationTargetPoint(
            index = 6,
            label = "Validate 2/4 • Upper-Right Quadrant",
            normX = 0.70f,
            normY = 0.32f,
            isValidationPoint = true,
            shortName = "V2 Upper-Right",
            guidancePrompt = "Look at the Upper-Right quadrant centroid (70%, 32%) to measure quadrant error."
        ),
        CalibrationTargetPoint(
            index = 7,
            label = "Validate 3/4 • Lower-Left Quadrant",
            normX = 0.30f,
            normY = 0.68f,
            isValidationPoint = true,
            shortName = "V3 Lower-Left",
            guidancePrompt = "Look at the Lower-Left quadrant centroid (30%, 68%) to test lower-screen tracking."
        ),
        CalibrationTargetPoint(
            index = 8,
            label = "Validate 4/4 • Lower-Right Quadrant",
            normX = 0.70f,
            normY = 0.68f,
            isValidationPoint = true,
            shortName = "V4 Lower-Right",
            guidancePrompt = "Fixate on the Lower-Right quadrant centroid (70%, 68%) to finalize validation."
        )
    )

    val CALIBRATION_AND_VALIDATION_TARGETS: List<CalibrationTargetPoint> =
        FIVE_POINT_CALIBRATION_TARGETS + HELD_OUT_VALIDATION_TARGETS

    fun projectGaze(
        feature: GazeFeatureVector,
        matrixCoeffs: List<Double>,
        baselineEyeDistPx: Double = 148.0
    ): Pair<Float, Float> {
        val w = if (matrixCoeffs.size == 12) matrixCoeffs else DEFAULT_AFFINE_MATRIX_2X6
        val f = feature.toStandardizedFeatureArray(baselineEyeDistPx)
        var predX = 0.0
        var predY = 0.0
        for (j in 0 until 6) {
            predX += w[j] * f[j]
            predY += w[6 + j] * f[j]
        }
        return Pair(
            predX.toFloat().coerceIn(0.02f, 0.98f),
            predY.toFloat().coerceIn(0.02f, 0.98f)
        )
    }

    /**
     * Frame-rate-independent 1€ (EuroFilter)-inspired adaptive exponential smoothing:
     * - At rest (delta = 0): tau = baseTauMs (95 ms default)
     * - tau falls linearly with displacement to 0.42 * baseTauMs (~40 ms) for delta >= 0.12,
     *   never below 35 ms
     * - Returned alpha = 1 - exp(-dt / tau), with dt clamped to 16..250 ms and alpha clamped to [0.20, 0.92]
     */
    fun computeAdaptiveSmoothingAlpha(
        dtMs: Long,
        deltaDisplacement: Float = 0.0f,
        baseTauMs: Double = 95.0
    ): Float {
        val clampedDt = dtMs.coerceIn(16L, 250L).toDouble()
        val saccadeBoost = (deltaDisplacement / 0.12f).coerceIn(0f, 1f)
        val dynamicTau = (baseTauMs * (1.0 - 0.58 * saccadeBoost)).coerceAtLeast(35.0)
        return (1.0 - exp(-clampedDt / dynamicTau)).toFloat().coerceIn(0.20f, 0.92f)
    }

    /**
     * Solves Ridge-regularized Least Squares on standardized feature vectors after performing
     * per-target Median Absolute Deviation (MAD) outlier rejection (removing micro-saccade or blink frames
     * while preserving full sample rank N >> 6 rather than collapsing to an underdetermined 5-point median).
     */
    fun fitRidgeAffineMatrix2x6(
        samples: List<Pair<GazeFeatureVector, Pair<Float, Float>>>,
        baselineEyeDistPx: Double = 148.0,
        ridgeLambda: Double = 2.5e-2
    ): List<Double> {
        if (samples.size < 5) return DEFAULT_AFFINE_MATRIX_2X6

        val cleanedSamples = trimOutliersByTargetMad(samples)
        val activeSamples = if (cleanedSamples.size >= 5) cleanedSamples else samples

        val dim = 6
        val a = Array(dim) { DoubleArray(dim) }
        val bx = DoubleArray(dim)
        val by = DoubleArray(dim)

        for ((feat, target) in activeSamples) {
            val f = feat.toStandardizedFeatureArray(baselineEyeDistPx)
            val tx = target.first.toDouble()
            val ty = target.second.toDouble()
            for (r in 0 until dim) {
                bx[r] += f[r] * tx
                by[r] += f[r] * ty
                for (c in 0 until dim) {
                    a[r][c] += f[r] * f[c]
                }
            }
        }

        // Ridge penalty + prior shrinkage toward the default weights. The fixed divisors leave real
        // phone-distance iris offsets far below unit scale (gx/0.25 is often within +/-0.15), so a uniform
        // penalty would dominate their tiny sum of squares and pull the fit back to the prior, compressing
        // the corners toward the centre. The iris features (1, 2) are therefore penalized relative to their
        // own mean square (clamped to 0.02..1.0 so a near-constant feature still shrinks to the prior).
        // Head-pose features (3..5) keep the full penalty and the intercept the light 0.05 penalty. The
        // divisors themselves are unchanged, so stored matrices remain valid.
        val prior = DEFAULT_AFFINE_MATRIX_2X6
        val sampleCount = activeSamples.size.coerceAtLeast(1)
        val effectiveLambda = ridgeLambda * sampleCount
        for (i in 0 until dim) {
            val reg = when (i) {
                0 -> effectiveLambda * 0.05
                // a[i][i] is still the raw sum of f[i]^2 here (reg is added below)
                1, 2 -> effectiveLambda * (a[i][i] / sampleCount).coerceIn(0.02, 1.0)
                else -> effectiveLambda
            }
            a[i][i] += reg
            bx[i] += reg * prior[i]
            by[i] += reg * prior[6 + i]
        }

        val wx = solveLinearSystem6x6(a, bx) ?: return DEFAULT_AFFINE_MATRIX_2X6
        val wy = solveLinearSystem6x6(a, by) ?: return DEFAULT_AFFINE_MATRIX_2X6

        val combined = ArrayList<Double>(12)
        for (v in wx) combined.add(v.coerceIn(-5.0, 5.0))
        for (v in wy) combined.add(v.coerceIn(-5.0, 5.0))
        return combined
    }

    /**
     * Groups samples by target coordinate, computes the median (gx, gy) per target, and discards
     * outlier frames exceeding 2.5 * MAD (or blinks where combinedEyeOpenProb < 0.35).
     */
    private fun trimOutliersByTargetMad(
        samples: List<Pair<GazeFeatureVector, Pair<Float, Float>>>
    ): List<Pair<GazeFeatureVector, Pair<Float, Float>>> {
        val grouped = samples.groupBy { it.second }
        val result = ArrayList<Pair<GazeFeatureVector, Pair<Float, Float>>>(samples.size)

        for ((_, group) in grouped) {
            val openFiltered = group.filter { it.first.combinedEyeOpenProb >= 0.35f }
                .ifEmpty { group }
            if (openFiltered.size <= 3) {
                result.addAll(openFiltered)
                continue
            }
            val sortedGx = openFiltered.map { it.first.gx }.sorted()
            val sortedGy = openFiltered.map { it.first.gy }.sorted()
            val medGx = sortedGx[sortedGx.size / 2]
            val medGy = sortedGy[sortedGy.size / 2]

            val devs = openFiltered.map { hypot(it.first.gx - medGx, it.first.gy - medGy) }.sorted()
            val mad = devs[devs.size / 2].coerceAtLeast(0.015)
            val cutoff = mad * 2.8

            val inliers = openFiltered.filter {
                hypot(it.first.gx - medGx, it.first.gy - medGy) <= cutoff
            }
            result.addAll(if (inliers.size >= 2) inliers else openFiltered)
        }
        return result
    }

    private fun solveLinearSystem6x6(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
        val n = rhs.size
        val aug = Array(n) { r ->
            DoubleArray(n + 1) { c -> if (c < n) matrix[r][c] else rhs[r] }
        }

        for (col in 0 until n) {
            var pivotRow = col
            var maxVal = abs(aug[col][col])
            for (r in (col + 1) until n) {
                if (abs(aug[r][col]) > maxVal) {
                    maxVal = abs(aug[r][col])
                    pivotRow = r
                }
            }
            if (maxVal < 1e-10) return null

            if (pivotRow != col) {
                val tmp = aug[col]
                aug[col] = aug[pivotRow]
                aug[pivotRow] = tmp
            }

            val pivot = aug[col][col]
            for (c in col..n) {
                aug[col][c] /= pivot
            }

            for (r in 0 until n) {
                if (r != col) {
                    val factor = aug[r][col]
                    for (c in col..n) {
                        aug[r][c] -= factor * aug[col][c]
                    }
                }
            }
        }

        return DoubleArray(n) { r -> aug[r][n] }
    }

    /**
     * Evaluates held-out validation samples grouped by quadrant target:
     * - meanErrorPct: Primary trustworthy normalized screen error (%)
     * - maxErrorPct: Worst-quadrant error (%) so a single dead corner is never masked by the mean
     * - approxMeanErrorMm: Approximate physical error (~mm) using the supplied screen width/height in mm
     *   (Pixel 10 Pro XL geometry when the supplied values are not finite and positive)
     *
     * meanErrorPct and maxErrorPct are Euclidean errors in normalized screen space (x as a fraction of
     * width, y as a fraction of height) times 100. They are NOT % of width or % of the diagonal, so on a
     * non-square screen 1% of y is a different physical distance than 1% of x; consumers that convert
     * them to pixels must scale x by width and y by height (an ellipse, not a circle).
     *
     * With no samples nothing was measured: all metrics are NaN and the description says
     * "Not validated (no samples)".
     */
    fun evaluateHeldOutValidation(
        matrixCoeffs: List<Double>,
        validationSamples: List<Pair<GazeFeatureVector, Pair<Float, Float>>>,
        baselineEyeDistPx: Double,
        screenWidthMm: Double = PIXEL_10_PRO_XL_WIDTH_MM,
        screenHeightMm: Double = PIXEL_10_PRO_XL_HEIGHT_MM
    ): ValidationMetricsResult {
        if (validationSamples.isEmpty()) {
            return ValidationMetricsResult(
                meanErrorPct = Double.NaN,
                maxErrorPct = Double.NaN,
                approxMeanErrorMm = Double.NaN,
                recommendedGridDesc = "Not validated (no samples)"
            )
        }

        val safeWidthMm = if (screenWidthMm.isFinite() && screenWidthMm > 0.0) screenWidthMm else PIXEL_10_PRO_XL_WIDTH_MM
        val safeHeightMm = if (screenHeightMm.isFinite() && screenHeightMm > 0.0) screenHeightMm else PIXEL_10_PRO_XL_HEIGHT_MM
        var totalMmError = 0.0
        var totalNormError = 0.0

        // Group by target coordinate to compute worst-quadrant error
        val targetErrors = mutableMapOf<Pair<Float, Float>, MutableList<Double>>()

        for ((feat, actualTarget) in validationSamples) {
            val (predX, predY) = projectGaze(feat, matrixCoeffs, baselineEyeDistPx)
            val dxNorm = (predX - actualTarget.first).toDouble()
            val dyNorm = (predY - actualTarget.second).toDouble()
            val dxMm = dxNorm * safeWidthMm
            val dyMm = dyNorm * safeHeightMm
            val errMm = hypot(dxMm, dyMm)
            val errPct = hypot(dxNorm, dyNorm) * 100.0

            totalMmError += errMm
            totalNormError += errPct
            targetErrors.getOrPut(actualTarget) { mutableListOf() }.add(errPct)
        }

        val count = validationSamples.size.toDouble()
        val meanMm = (totalMmError / count).coerceIn(2.0, 120.0)
        val meanPct = (totalNormError / count).coerceIn(1.5, 85.0)
        val maxQuadrantPct = targetErrors.values
            .mapNotNull { list -> if (list.isEmpty()) null else list.average() }
            .maxOrNull()
            ?.coerceIn(meanPct, 95.0)
            ?: (meanPct * 1.25).coerceAtMost(95.0)

        // Recommend exactly the layout the Control Deck renders for these metrics (same thresholds)
        val recommendation = gridLayoutLabel(qualifiesFor3x3Grid(meanPct, maxQuadrantPct)) +
            (if (maxQuadrantPct > RECALIBRATION_RECOMMENDED_WORST_QUADRANT_PCT) " — Recalibration Recommended" else "")

        return ValidationMetricsResult(
            meanErrorPct = meanPct,
            maxErrorPct = maxQuadrantPct,
            approxMeanErrorMm = meanMm,
            recommendedGridDesc = recommendation
        )
    }

    /**
     * Dispersion-based fixation clustering over (normX, normY, timestampMs) samples.
     * A cluster ends when a sample lies farther than [dispersionThreshold] from the running centroid,
     * or when more than [maxGapMs] passed since the previous sample (face loss / dropped frames), so
     * time without valid gaze is never counted as fixation dwell. Dwell is lastTs - startTs of the
     * cluster's own samples. Returns at most the [maxClusters] most recent clusters.
     */
    fun clusterFixations(
        rawTimedSamples: List<Triple<Float, Float, Long>>,
        dispersionThreshold: Float = 0.12f,
        minDwellMs: Long = 180L,
        maxGapMs: Long = 300L,
        maxClusters: Int = 40
    ): List<FixationClusterPoint> {
        if (rawTimedSamples.size < 2) return emptyList()
        val clusters = mutableListOf<FixationClusterPoint>()

        var sumX = rawTimedSamples[0].first
        var sumY = rawTimedSamples[0].second
        var count = 1
        var startTs = rawTimedSamples[0].third
        var lastTs = startTs

        for (i in 1 until rawTimedSamples.size) {
            val (x, y, ts) = rawTimedSamples[i]
            val centroidX = sumX / count
            val centroidY = sumY / count
            val dist = hypot(x - centroidX, y - centroidY)
            val gapBreak = ts - lastTs > maxGapMs

            if (!gapBreak && dist <= dispersionThreshold) {
                sumX += x
                sumY += y
                count++
                lastTs = ts
            } else {
                val dwell = lastTs - startTs
                if (dwell >= minDwellMs) {
                    clusters.add(
                        FixationClusterPoint(
                            normX = (sumX / count).coerceIn(0.05f, 0.95f),
                            normY = (sumY / count).coerceIn(0.05f, 0.95f),
                            dwellMs = dwell
                        )
                    )
                }
                sumX = x
                sumY = y
                count = 1
                startTs = ts
                lastTs = ts
            }
        }

        val finalDwell = lastTs - startTs
        if (finalDwell >= minDwellMs) {
            clusters.add(
                FixationClusterPoint(
                    normX = (sumX / count).coerceIn(0.05f, 0.95f),
                    normY = (sumY / count).coerceIn(0.05f, 0.95f),
                    dwellMs = finalDwell
                )
            )
        }

        val cap = maxClusters.coerceAtLeast(0)
        return if (clusters.size > cap) clusters.takeLast(cap) else clusters
    }
}

/**
 * Canonical Schmitt-trigger hysteresis blink detector (§2.6 unified windows):
 * - Close threshold: fused eye-open probability < 0.45
 * - Reopen threshold: fused eye-open probability > 0.65
 * - Natural involuntary blink: observed closure < intentionalMinMs and estimated closure >= [naturalMinMs]
 *   [default 80-349 ms]. The observed closure (first closed frame to first reopened frame) is a whole
 *   number of frame intervals, so the estimate adds half the running mean frame interval to it: a blink
 *   caught in a single frame still counts at 10-15 FPS, while a single-frame dip at 30 FPS is rejected.
 * - Intentional command blink: intentionalMinMs .. maxCommandMs observed [default 350-800 ms]
 * - Extended closure / rest: > maxCommandMs [default > 800 ms]
 */
class BlinkHysteresisDetector(
    var closeThreshold: Float = 0.45f,
    var openThreshold: Float = 0.65f,
    var intentionalMinMs: Long = 350L,
    var maxCommandMs: Long = 800L
) {
    var naturalMinMs: Long = 80L
    private var isEyesClosedState: Boolean = false
    private var closedStartTimestampMs: Long = 0L
    private var lastUpdateTimestampMs: Long = 0L
    private var frameIntervalMs: Double = DEFAULT_FRAME_INTERVAL_MS

    fun update(eyeOpenProb: Float, timestampMs: Long): Pair<Float, BlinkClassificationEvent?> {
        if (lastUpdateTimestampMs > 0L) {
            val dt = (timestampMs - lastUpdateTimestampMs).coerceIn(16L, 250L).toDouble()
            frameIntervalMs = 0.8 * frameIntervalMs + 0.2 * dt
        }
        lastUpdateTimestampMs = timestampMs

        if (!isEyesClosedState) {
            if (eyeOpenProb < closeThreshold) {
                isEyesClosedState = true
                closedStartTimestampMs = timestampMs
                return Pair(0.05f, null)
            }
            return Pair(0f, null)
        } else {
            val elapsedMs = (timestampMs - closedStartTimestampMs).coerceAtLeast(0L)
            if (eyeOpenProb > openThreshold) {
                isEyesClosedState = false
                val estimatedClosureMs = elapsedMs + (frameIntervalMs / 2.0).toLong()
                val event = when {
                    elapsedMs < intentionalMinMs && estimatedClosureMs >= naturalMinMs ->
                        BlinkClassificationEvent(BlinkType.NATURAL, elapsedMs, timestampMs)
                    elapsedMs in intentionalMinMs..maxCommandMs ->
                        BlinkClassificationEvent(BlinkType.INTENTIONAL_COMMAND, elapsedMs, timestampMs)
                    elapsedMs > maxCommandMs ->
                        BlinkClassificationEvent(BlinkType.EXTENDED_REST, elapsedMs, timestampMs)
                    else -> null
                }
                return Pair(0f, event)
            } else {
                val progress = min(1f, elapsedMs.toFloat() / intentionalMinMs.toFloat())
                return Pair(progress, null)
            }
        }
    }

    fun reset() {
        isEyesClosedState = false
        closedStartTimestampMs = 0L
        lastUpdateTimestampMs = 0L
        frameIntervalMs = DEFAULT_FRAME_INTERVAL_MS
    }

    companion object {
        /** Initial frame-interval estimate (~15 FPS) before any timestamps have been observed. */
        const val DEFAULT_FRAME_INTERVAL_MS = 66.0
    }
}

/**
 * Brow-Raise Expression Trigger with Schmitt-trigger hysteresis and hold-time gating (400 ms default).
 * Because a brow raise keeps both eyes open, the tracker never loses pupil lock at the moment of selection.
 * Uses per-user calibrated neutralBrowRatio and raisedBrowRatio (or defaults 0.34 -> 0.42):
 * - Enter raise state when pitch-compensated browToEyeRatio >= raiseThreshold
 *   (neutral + 0.60 * max(raised - neutral, 0.035), see [raiseThresholdFor])
 * - Fire once held >= holdTargetMs (default 400 ms, within 350-600 ms spec)
 * - Disarm until brow relaxes below releaseThreshold (neutral + 0.32 * max(raised - neutral, 0.035))
 * - An unfired hold restarts after an update gap > [MAX_HOLD_UPDATE_GAP_MS]; callers that skip updates
 *   (face lost, brow trigger disabled) call [cancelPendingRaise] so a stale raise never fires on resume.
 */
class BrowRaiseHysteresisDetector(
    var neutralBrowRatio: Float = 0.34f,
    var raisedBrowRatio: Float = 0.42f,
    var holdTargetMs: Long = 400L
) {
    private var isBrowRaisedState: Boolean = false
    private var raiseStartTimestampMs: Long = 0L
    private var firedForCurrentRaise: Boolean = false
    private var lastUpdateTimestampMs: Long = 0L

    val raiseThreshold: Float
        get() = raiseThresholdFor(neutralBrowRatio, raisedBrowRatio)

    val releaseThreshold: Float
        get() = releaseThresholdFor(neutralBrowRatio, raisedBrowRatio)

    fun update(browToEyeRatio: Float, timestampMs: Long): Pair<Float, Boolean> {
        if (isBrowRaisedState && !firedForCurrentRaise && lastUpdateTimestampMs > 0L &&
            timestampMs - lastUpdateTimestampMs > MAX_HOLD_UPDATE_GAP_MS
        ) {
            // Long frame outage (e.g. app backgrounded): the hold was not observed, so restart it.
            raiseStartTimestampMs = timestampMs
        }
        lastUpdateTimestampMs = timestampMs

        if (!isBrowRaisedState) {
            if (browToEyeRatio >= raiseThreshold) {
                isBrowRaisedState = true
                raiseStartTimestampMs = timestampMs
                firedForCurrentRaise = false
                return Pair(0.08f, false)
            }
            return Pair(0f, false)
        } else {
            if (browToEyeRatio < releaseThreshold) {
                isBrowRaisedState = false
                firedForCurrentRaise = false
                return Pair(0f, false)
            }
            val elapsed = (timestampMs - raiseStartTimestampMs).coerceAtLeast(0L)
            val progress = (elapsed.toFloat() / holdTargetMs.coerceAtLeast(250L).toFloat()).coerceIn(0f, 1f)
            if (progress >= 1f && !firedForCurrentRaise) {
                firedForCurrentRaise = true
                return Pair(1f, true)
            }
            return Pair(progress, false)
        }
    }

    /**
     * Cancels a raise that has started but not fired yet, so the next update has to observe a fresh
     * full hold. A raise that already fired stays disarmed until the brow relaxes below release.
     */
    fun cancelPendingRaise() {
        if (isBrowRaisedState && !firedForCurrentRaise) {
            isBrowRaisedState = false
        }
    }

    fun reset() {
        isBrowRaisedState = false
        firedForCurrentRaise = false
        raiseStartTimestampMs = 0L
        lastUpdateTimestampMs = 0L
    }

    companion object {
        /** Update gaps longer than this restart an unfired hold. Above the ~420-430 ms test/simulator gaps. */
        const val MAX_HOLD_UPDATE_GAP_MS = 1000L

        /** The ratio at which the detector enters the raise state (same formula as [raiseThreshold]). */
        fun raiseThresholdFor(neutral: Float, raised: Float): Float =
            neutral + 0.60f * (raised - neutral).coerceAtLeast(0.035f)

        /** The ratio below which a raise is released (same formula as [releaseThreshold]). */
        fun releaseThresholdFor(neutral: Float, raised: Float): Float =
            neutral + 0.32f * (raised - neutral).coerceAtLeast(0.035f)
    }
}
