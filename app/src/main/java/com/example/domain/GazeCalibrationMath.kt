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

    /** Upper guard for reported normalized errors; the measured value is otherwise reported unchanged. */
    const val MAX_REPORTED_ERROR_PCT = 100.0

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
        return projectStandardized(feature, baselineEyeDistPx, { w[it] }) { x, y -> Pair(x, y) }
    }

    /**
     * Allocation-free [projectGaze] for per-frame callers: writes the same clamped (x, y) to out[0] and
     * out[1] and returns [out].
     */
    fun projectGazeInto(
        feature: GazeFeatureVector,
        matrixCoeffs: List<Double>,
        baselineEyeDistPx: Double,
        out: FloatArray
    ): FloatArray {
        val w = if (matrixCoeffs.size == 12) matrixCoeffs else DEFAULT_AFFINE_MATRIX_2X6
        return projectStandardized(feature, baselineEyeDistPx, { w[it] }) { x, y ->
            out[0] = x
            out[1] = y
            out
        }
    }

    /**
     * Features 1..5 of [GazeFeatureVector.toStandardizedFeatureArray] (feature 0 is the constant 1.0),
     * handed to [block] without allocating the array. Same expressions, so the values are bit-identical.
     */
    private inline fun <R> withStandardizedFeatures(
        feature: GazeFeatureVector,
        baselineEyeDistPx: Double,
        block: (f1: Double, f2: Double, f3: Double, f4: Double, f5: Double) -> R
    ): R {
        val safeBaseDist = baselineEyeDistPx.coerceAtLeast(30.0)
        val distScale = ((feature.eyeDistancePx - safeBaseDist) / safeBaseDist).coerceIn(-0.6, 0.6)
        return block(
            (feature.gx / 0.25).coerceIn(-2.5, 2.5),
            (feature.gy / 0.20).coerceIn(-2.5, 2.5),
            (feature.yaw / 20.0).coerceIn(-2.5, 2.5),
            (feature.pitch / 15.0).coerceIn(-2.5, 2.5),
            ((feature.roll / 15.0) + 0.5 * distScale).coerceIn(-2.5, 2.5)
        )
    }

    /**
     * [projectGaze]'s weighted sums and clamp, reading coefficient j (0..11) through [coef]. Each axis is
     * summed in the original j = 0..5 order; coef(0) * f0 is coef(0) exactly because f0 is 1.0.
     */
    private inline fun <R> projectStandardized(
        feature: GazeFeatureVector,
        baselineEyeDistPx: Double,
        coef: (Int) -> Double,
        block: (x: Float, y: Float) -> R
    ): R = withStandardizedFeatures(feature, baselineEyeDistPx) { f1, f2, f3, f4, f5 ->
        var predX = 0.0
        predX += coef(0)
        predX += coef(1) * f1
        predX += coef(2) * f2
        predX += coef(3) * f3
        predX += coef(4) * f4
        predX += coef(5) * f5
        var predY = 0.0
        predY += coef(6)
        predY += coef(7) * f1
        predY += coef(8) * f2
        predY += coef(9) * f3
        predY += coef(10) * f4
        predY += coef(11) * f5
        block(predX.toFloat().coerceIn(0.02f, 0.98f), predY.toFloat().coerceIn(0.02f, 0.98f))
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

        // Indexed below: copy once if the caller's list has no constant-time get
        val list = if (samples is RandomAccess) samples else ArrayList(samples)
        val n = list.size
        val kept = IntArray(n)
        val keptCount = trimOutliersByTargetMad(list, kept)
        // Fewer than 5 survivors: fit every sample, in input order
        val useKept = keptCount >= 5
        val activeCount = if (useKept) keptCount else n

        val dim = 6
        // Row-major dim x dim normal matrix and the two right-hand sides
        val a = DoubleArray(dim * dim)
        val bx = DoubleArray(dim)
        val by = DoubleArray(dim)
        val f = DoubleArray(dim)
        f[0] = 1.0

        for (k in 0 until activeCount) {
            val (feat, target) = list[if (useKept) kept[k] else k]
            withStandardizedFeatures(feat, baselineEyeDistPx) { f1, f2, f3, f4, f5 ->
                f[1] = f1
                f[2] = f2
                f[3] = f3
                f[4] = f4
                f[5] = f5
            }
            val tx = target.first.toDouble()
            val ty = target.second.toDouble()
            for (r in 0 until dim) {
                val fr = f[r]
                bx[r] += fr * tx
                by[r] += fr * ty
                // f[r] * f[c] == f[c] * f[r] exactly, so the upper triangle is accumulated and mirrored below
                val row = r * dim
                for (c in r until dim) {
                    a[row + c] += fr * f[c]
                }
            }
        }
        for (r in 1 until dim) {
            for (c in 0 until r) a[r * dim + c] = a[c * dim + r]
        }

        // Ridge penalty + prior shrinkage toward the default weights. The fixed divisors leave real
        // phone-distance iris offsets far below unit scale (gx/0.25 is often within +/-0.15), so a uniform
        // penalty would dominate their tiny sum of squares and pull the fit back to the prior, compressing
        // the corners toward the centre. The iris features (1, 2) are therefore penalized relative to their
        // own mean square (clamped to 0.02..1.0 so a near-constant feature still shrinks to the prior).
        // Head-pose features (3..5) keep the full penalty and the intercept the light 0.05 penalty. The
        // divisors themselves are unchanged, so stored matrices remain valid.
        val prior = DEFAULT_AFFINE_MATRIX_2X6
        val sampleCount = activeCount.coerceAtLeast(1)
        val effectiveLambda = ridgeLambda * sampleCount
        for (i in 0 until dim) {
            val diag = i * dim + i
            val reg = when (i) {
                0 -> effectiveLambda * 0.05
                // a[diag] is still the raw sum of f[i]^2 here (reg is added below)
                1, 2 -> effectiveLambda * (a[diag] / sampleCount).coerceIn(0.02, 1.0)
                else -> effectiveLambda
            }
            a[diag] += reg
            bx[i] += reg * prior[i]
            by[i] += reg * prior[6 + i]
        }

        val solution = DoubleArray(2 * dim)
        if (!solveLinearSystem6x6(a, bx, by, solution)) return DEFAULT_AFFINE_MATRIX_2X6

        val combined = ArrayList<Double>(12)
        for (v in solution) combined.add(v.coerceIn(-5.0, 5.0))
        return combined
    }

    /**
     * Groups samples by target coordinate, computes the median (gx, gy) per target, and discards
     * outlier frames exceeding 2.8 * MAD (or blinks where combinedEyeOpenProb < 0.35).
     *
     * Writes the indices of the kept samples to [kept] in fitting order (targets in first-seen order,
     * input order within a target) and returns how many were kept. Targets are grouped by Pair equality
     * as groupBy did, and medians are taken from primitive sorts, whose total order (-0.0 before 0.0,
     * NaN last) is the one the boxed Double sort used, so the kept set is unchanged.
     */
    private fun trimOutliersByTargetMad(
        samples: List<Pair<GazeFeatureVector, Pair<Float, Float>>>,
        kept: IntArray
    ): Int {
        val n = samples.size

        // Target group of every sample, numbered in first-seen order
        val groupOf = IntArray(n)
        val groupIds = HashMap<Pair<Float, Float>, Int>()
        var groupSizes = IntArray(8)
        var groupCount = 0
        var lastKey: Pair<Float, Float>? = null
        var lastGroup = -1
        for (i in 0 until n) {
            val key = samples[i].second
            if (lastGroup < 0 || key != lastKey) {
                lastGroup = groupIds[key] ?: groupCount.also { id ->
                    groupIds[key] = id
                    if (id == groupSizes.size) groupSizes = groupSizes.copyOf(id * 2)
                    groupCount++
                }
                lastKey = key
            }
            groupOf[i] = lastGroup
            groupSizes[lastGroup]++
        }

        // Stable counting sort of the sample indices by group (groupSizes becomes the write cursor)
        val groupStart = IntArray(groupCount + 1)
        var maxGroupSize = 0
        for (g in 0 until groupCount) {
            val size = groupSizes[g]
            if (size > maxGroupSize) maxGroupSize = size
            groupStart[g + 1] = groupStart[g] + size
            groupSizes[g] = groupStart[g]
        }
        val order = IntArray(n)
        for (i in 0 until n) order[groupSizes[groupOf[i]]++] = i

        val values = DoubleArray(maxGroupSize) // sort scratch for the medians
        val devs = DoubleArray(maxGroupSize) // distance of each open member to the median, in member order
        var out = 0
        for (g in 0 until groupCount) {
            val start = groupStart[g]
            val end = groupStart[g + 1]

            // Open-eye members (or the whole group if none is open), written straight to kept[out until out + m]
            var m = 0
            for (p in start until end) {
                val i = order[p]
                if (samples[i].first.combinedEyeOpenProb >= 0.35f) kept[out + m++] = i
            }
            if (m == 0) {
                for (p in start until end) kept[out + m++] = order[p]
            }
            if (m <= 3) {
                out += m
                continue
            }

            for (k in 0 until m) values[k] = samples[kept[out + k]].first.gx
            values.sort(0, m)
            val medGx = values[m / 2]
            for (k in 0 until m) values[k] = samples[kept[out + k]].first.gy
            values.sort(0, m)
            val medGy = values[m / 2]

            for (k in 0 until m) {
                val feat = samples[kept[out + k]].first
                devs[k] = hypot(feat.gx - medGx, feat.gy - medGy)
            }
            devs.copyInto(values, 0, 0, m)
            values.sort(0, m)
            val mad = values[m / 2].coerceAtLeast(0.015)
            val cutoff = mad * 2.8

            var inliers = 0
            for (k in 0 until m) if (devs[k] <= cutoff) inliers++
            if (inliers >= 2) {
                // Compact in place, keeping member order (the write index never passes the read index)
                var write = out
                for (k in 0 until m) if (devs[k] <= cutoff) kept[write++] = kept[out + k]
                out += inliers
            } else {
                out += m
            }
        }
        return out
    }

    /**
     * Gauss-Jordan elimination with partial pivoting on the row-major 6x6 [matrix], for both right-hand
     * sides at once. Pivot choice and row factors depend only on the matrix columns, so each solution is
     * bit-identical to solving its right-hand side alone. Writes the solution for [rhsX] to out[0..5] and
     * for [rhsY] to out[6..11]; returns false when a pivot is below 1e-10 (singular system).
     */
    private fun solveLinearSystem6x6(
        matrix: DoubleArray,
        rhsX: DoubleArray,
        rhsY: DoubleArray,
        out: DoubleArray
    ): Boolean {
        val n = rhsX.size
        val width = n + 2
        val aug = DoubleArray(n * width)
        for (r in 0 until n) {
            val row = r * width
            matrix.copyInto(aug, row, r * n, r * n + n)
            aug[row + n] = rhsX[r]
            aug[row + n + 1] = rhsY[r]
        }

        for (col in 0 until n) {
            var pivotRow = col
            var maxVal = abs(aug[col * width + col])
            for (r in (col + 1) until n) {
                val v = abs(aug[r * width + col])
                if (v > maxVal) {
                    maxVal = v
                    pivotRow = r
                }
            }
            if (maxVal < 1e-10) return false

            val pivotBase = col * width
            if (pivotRow != col) {
                val otherBase = pivotRow * width
                for (c in 0 until width) {
                    val tmp = aug[pivotBase + c]
                    aug[pivotBase + c] = aug[otherBase + c]
                    aug[otherBase + c] = tmp
                }
            }

            val pivot = aug[pivotBase + col]
            for (c in col until width) {
                aug[pivotBase + c] /= pivot
            }

            for (r in 0 until n) {
                if (r != col) {
                    val rowBase = r * width
                    val factor = aug[rowBase + col]
                    for (c in col until width) {
                        aug[rowBase + c] -= factor * aug[pivotBase + c]
                    }
                }
            }
        }

        for (r in 0 until n) {
            out[r] = aug[r * width + n]
            out[n + r] = aug[r * width + n + 1]
        }
        return true
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
     * The values are the measured means, without floors (a 0.2 % fit reports 0.2 %, not a minimum).
     * Percentages are capped at [MAX_REPORTED_ERROR_PCT]; a non-finite feature makes the metrics NaN.
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
        // Resolved and unboxed once instead of once per sample
        val w = if (matrixCoeffs.size == 12) matrixCoeffs else DEFAULT_AFFINE_MATRIX_2X6
        val coeffs = DoubleArray(12) { w[it] }
        var totalMmError = 0.0
        var totalNormError = 0.0

        // Per-target running sums (first-seen order) for the worst-quadrant error. Summed in sample order
        // and divided by the count, which is exactly what List.average() computed.
        val targetErrors = LinkedHashMap<Pair<Float, Float>, TargetErrorSum>()
        var lastTarget: Pair<Float, Float>? = null
        var lastSum: TargetErrorSum? = null

        for ((feat, actualTarget) in validationSamples) {
            val errPct = projectStandardized(feat, baselineEyeDistPx, { coeffs[it] }) { predX, predY ->
                val dxNorm = (predX - actualTarget.first).toDouble()
                val dyNorm = (predY - actualTarget.second).toDouble()
                val dxMm = dxNorm * safeWidthMm
                val dyMm = dyNorm * safeHeightMm
                totalMmError += hypot(dxMm, dyMm)
                hypot(dxNorm, dyNorm) * 100.0
            }
            totalNormError += errPct

            var sum = lastSum
            if (sum == null || actualTarget != lastTarget) {
                sum = targetErrors.getOrPut(actualTarget) { TargetErrorSum() }
                lastTarget = actualTarget
                lastSum = sum
            }
            sum.total += errPct
            sum.count++
        }

        // The measured means are reported as measured: a fit better than 1.5 % or 2 mm is shown as such.
        // Only guards remain: a non-finite projection (NaN features) leaves the metric NaN, i.e. "not
        // measured", and percentages are capped at 100 (only a hopeless fit gets there, since projections are
        // clamped to the screen). For on-screen targets the mm error cannot exceed the screen diagonal anyway.
        val count = validationSamples.size.toDouble()
        val meanMm = totalMmError / count
        val meanPct = (totalNormError / count).coerceAtMost(MAX_REPORTED_ERROR_PCT)
        // The worst per-target mean is never below the overall mean (the lower bound only absorbs rounding).
        // maxOf as in maxOrNull(), so a NaN target mean still propagates.
        var worstTargetPct = 0.0
        var hasTarget = false
        for (sum in targetErrors.values) {
            val targetMean = sum.total / sum.count
            worstTargetPct = if (hasTarget) maxOf(worstTargetPct, targetMean) else targetMean
            hasTarget = true
        }
        val maxQuadrantPct = if (hasTarget) worstTargetPct.coerceIn(meanPct, MAX_REPORTED_ERROR_PCT) else meanPct

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
        val clusters = ArrayList<FixationClusterPoint>()

        // Squared-distance bounds that settle `hypot(dx, dy) <= dispersionThreshold` without hypot
        // (see [withinDispersion]); only for a positive, normal, finite threshold.
        val useSquaredBounds = dispersionThreshold.isFinite() && dispersionThreshold >= DISPERSION_SQUARED_MIN_THRESHOLD
        val thresholdD = dispersionThreshold.toDouble()
        val thresholdSq = thresholdD * thresholdD
        val acceptBelowSq = thresholdSq * (1.0 - DISPERSION_SQUARED_MARGIN)
        val rejectAboveSq = thresholdSq * (1.0 + DISPERSION_SQUARED_MARGIN)

        val first = rawTimedSamples[0]
        var sumX = first.first
        var sumY = first.second
        var count = 1
        var startTs = first.third
        var lastTs = startTs

        for (i in 1 until rawTimedSamples.size) {
            val sample = rawTimedSamples[i]
            val x = sample.first
            val y = sample.second
            val ts = sample.third
            val centroidX = sumX / count
            val centroidY = sumY / count
            val gapBreak = ts - lastTs > maxGapMs

            if (!gapBreak &&
                withinDispersion(x - centroidX, y - centroidY, dispersionThreshold, useSquaredBounds, acceptBelowSq, rejectAboveSq)
            ) {
                sumX += x
                sumY += y
                count++
                lastTs = ts
            } else {
                val dwell = lastTs - startTs
                if (dwell >= minDwellMs) clusters.add(fixationPoint(sumX, sumY, count, dwell))
                sumX = x
                sumY = y
                count = 1
                startTs = ts
                lastTs = ts
            }
        }

        val finalDwell = lastTs - startTs
        if (finalDwell >= minDwellMs) clusters.add(fixationPoint(sumX, sumY, count, finalDwell))

        val cap = maxClusters.coerceAtLeast(0)
        return if (clusters.size > cap) clusters.takeLast(cap) else clusters
    }

    private fun fixationPoint(sumX: Float, sumY: Float, count: Int, dwellMs: Long) = FixationClusterPoint(
        normX = (sumX / count).coerceIn(0.05f, 0.95f),
        normY = (sumY / count).coerceIn(0.05f, 0.95f),
        dwellMs = dwellMs
    )

    /**
     * Exactly `hypot(dx, dy) <= threshold` for kotlin.math's Float hypot (Math.hypot of the widened values,
     * rounded to Float). Outside a 1e-6 relative band around threshold^2 the squared distance decides:
     * the widened squares are exact, their sum is off by at most 2^-53, Math.hypot is within 1 ulp
     * (2^-52) and the Float rounding moves the result by at most one half Float ulp (2^-24 for a normal
     * threshold), all far inside the band. Inside the band, for NaN, or without [useSquaredBounds],
     * hypot itself decides.
     */
    private fun withinDispersion(
        dx: Float,
        dy: Float,
        threshold: Float,
        useSquaredBounds: Boolean,
        acceptBelowSq: Double,
        rejectAboveSq: Double
    ): Boolean {
        if (useSquaredBounds) {
            val ddx = dx.toDouble()
            val ddy = dy.toDouble()
            val distSq = ddx * ddx + ddy * ddy
            if (distSq < acceptBelowSq) return true
            if (distSq > rejectAboveSq) return false
        }
        return hypot(dx, dy) <= threshold
    }

    /** Running worst-quadrant error sum of one validation target. */
    private class TargetErrorSum {
        var total = 0.0
        var count = 0
    }

    /** Relative band around threshold^2 inside which [withinDispersion] defers to hypot. */
    private const val DISPERSION_SQUARED_MARGIN = 1e-6

    /** Smallest threshold for the squared-distance shortcut (any normal Float works; tiny ones use hypot). */
    private const val DISPERSION_SQUARED_MIN_THRESHOLD = 1e-30f
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
                return CLOSURE_STARTED
            }
            return EYES_OPEN
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
                return if (event == null) EYES_OPEN else Pair(0f, event)
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

        // Shared immutable results for the constant-valued updates (no per-frame Pair or Float boxing)
        private val EYES_OPEN: Pair<Float, BlinkClassificationEvent?> = Pair(0f, null)
        private val CLOSURE_STARTED: Pair<Float, BlinkClassificationEvent?> = Pair(0.05f, null)
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
                return RAISE_STARTED
            }
            return NOT_RAISED
        } else {
            if (browToEyeRatio < releaseThreshold) {
                isBrowRaisedState = false
                firedForCurrentRaise = false
                return NOT_RAISED
            }
            val elapsed = (timestampMs - raiseStartTimestampMs).coerceAtLeast(0L)
            val progress = (elapsed.toFloat() / holdTargetMs.coerceAtLeast(250L).toFloat()).coerceIn(0f, 1f)
            if (progress >= 1f && !firedForCurrentRaise) {
                firedForCurrentRaise = true
                return RAISE_FIRED
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

        // Shared immutable results for the constant-valued updates (no per-frame Pair or Float boxing)
        private val NOT_RAISED = Pair(0f, false)
        private val RAISE_STARTED = Pair(0.08f, false)
        private val RAISE_FIRED = Pair(1f, true)
    }
}
