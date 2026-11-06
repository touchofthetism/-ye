package com.example.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Sample gaps longer than this (or timestamps that go backwards) re-initialize the gaze filters from
 * the current sample instead of resuming from a stale state. Equal to the dt clamp, so gaps up to 1 s
 * (blinks, short dropouts) behave exactly as before.
 */
const val GAZE_FILTER_STALE_GAP_RESET_MS: Long = 1000L

private fun isStaleGap(lastTimestampMs: Long, timestampMs: Long): Boolean {
    val gapMs = timestampMs - lastTimestampMs
    return gapMs < 0L || gapMs > GAZE_FILTER_STALE_GAP_RESET_MS
}

/**
 * Production implementation of the 1€ (One Euro) Filter (Casiez, Roussel, Vogel, ACM CHI 2012).
 * Formulated specifically for interactive human gaze and pupil tracking systems:
 *
 * It uses a first-order low-pass filter whose cutoff frequency adapts based on signal speed:
 * - At low speed (fixation / resting eye gaze), cutoff frequency drops to [minCutoff] (e.g. 0.85 Hz),
 *   heavily damping high-frequency sensor quantization, corneal reflection flicker, and ocular tremor.
 * - At high speed (voluntary saccade / rapid gaze shifts), cutoff frequency expands to
 *   minCutoff + [beta] * |filtered velocity| (velocity in input units per second).
 *
 * The speed adaptation only takes effect when beta is scaled to the input's units: for normalized
 * coordinates (speeds of a few units/s) beta must be of order 1. A beta near 0 (e.g. 0.035) turns the
 * filter into a fixed first-order low-pass at minCutoff with no saccade speed-up.
 *
 * @param minCutoff Minimum cutoff frequency in Hz (default 0.85 Hz).
 * @param beta Speed coefficient in Hz per (unit/s) of filtered speed (default 1.2).
 * @param dCutoff Cutoff frequency in Hz for the derivative/speed filter (default 1.0 Hz).
 */
class OneEuroFilter(
    var minCutoff: Double = 0.85,
    var beta: Double = 1.2,
    var dCutoff: Double = 1.0
) {
    private var xPrev: Double? = null
    private var dxPrev: Double = 0.0
    private var tPrev: Long? = null

    /**
     * Filters a 1D scalar sample.
     * @param x Current raw sample value.
     * @param timestampMs Monotonic sample timestamp in milliseconds.
     * @return Filtered sample value.
     */
    fun filter(x: Double, timestampMs: Long): Double {
        val lastT = tPrev
        val lastX = xPrev

        if (lastT == null || lastX == null || isStaleGap(lastT, timestampMs)) {
            xPrev = x
            dxPrev = 0.0
            tPrev = timestampMs
            return x
        }

        val dtSec = ((timestampMs - lastT).coerceIn(1L, 1000L)) / 1000.0
        tPrev = timestampMs

        // 1. Estimate instantaneous derivative (velocity)
        val rawDx = (x - lastX) / dtSec

        // 2. Filter derivative with cutoff dCutoff
        val alphaD = computeAlpha(dtSec, dCutoff)
        val filteredDx = alphaD * rawDx + (1.0 - alphaD) * dxPrev
        dxPrev = filteredDx

        // 3. Dynamic cutoff frequency based on filtered velocity magnitude
        val cutoff = minCutoff + beta * abs(filteredDx)

        // 4. Filter signal with dynamic cutoff
        val alphaX = computeAlpha(dtSec, cutoff)
        val filteredX = alphaX * x + (1.0 - alphaX) * lastX
        xPrev = filteredX

        return filteredX
    }

    fun reset() {
        xPrev = null
        dxPrev = 0.0
        tPrev = null
    }

    val lastValue: Double?
        get() = xPrev

    companion object {
        fun computeAlpha(dtSec: Double, cutoffHz: Double): Double {
            val tau = 1.0 / (2.0 * PI * cutoffHz.coerceAtLeast(1e-4))
            return 1.0 / (1.0 + tau / dtSec.coerceAtLeast(1e-4))
        }
    }
}

/**
 * 2D One Euro Filter for paired coordinates (e.g. pupil centroid gx, gy or screen coordinates).
 *
 * Coupling the Euclidean velocity magnitude:
 * speed = sqrt(dx^2 + dy^2)
 * ensures that diagonal saccades expand the low-pass cutoff frequency synchronously for both axes,
 * preventing elliptical warping or anisotropic lag during diagonal eye sweeps.
 */
class OneEuroFilter2D(
    var minCutoff: Double = 0.85,
    var beta: Double = 1.2,
    var dCutoff: Double = 1.0
) {
    private var xPrev: Double? = null
    private var yPrev: Double? = null
    private var dxPrev: Double = 0.0
    private var dyPrev: Double = 0.0
    private var tPrev: Long? = null

    /**
     * Filters a 2D coordinate pair.
     * @param x Raw X coordinate.
     * @param y Raw Y coordinate.
     * @param timestampMs Monotonic sample timestamp in milliseconds.
     * @return Filtered (X, Y) pair.
     */
    fun filter(x: Double, y: Double, timestampMs: Long): Pair<Double, Double> {
        val lastT = tPrev
        val lastX = xPrev
        val lastY = yPrev

        if (lastT == null || lastX == null || lastY == null || isStaleGap(lastT, timestampMs)) {
            xPrev = x
            yPrev = y
            dxPrev = 0.0
            dyPrev = 0.0
            tPrev = timestampMs
            return Pair(x, y)
        }

        val dtSec = ((timestampMs - lastT).coerceIn(1L, 1000L)) / 1000.0
        tPrev = timestampMs

        // 1. Instantaneous derivatives
        val rawDx = (x - lastX) / dtSec
        val rawDy = (y - lastY) / dtSec

        // 2. Filter derivatives
        val alphaD = OneEuroFilter.computeAlpha(dtSec, dCutoff)
        val filteredDx = alphaD * rawDx + (1.0 - alphaD) * dxPrev
        val filteredDy = alphaD * rawDy + (1.0 - alphaD) * dyPrev
        dxPrev = filteredDx
        dyPrev = filteredDy

        // 3. Coupled Euclidean speed
        val speed = sqrt(filteredDx * filteredDx + filteredDy * filteredDy)
        val dynamicCutoff = minCutoff + beta * speed

        // 4. Filter coordinates with coupled dynamic cutoff
        val alpha = OneEuroFilter.computeAlpha(dtSec, dynamicCutoff)
        val filteredX = alpha * x + (1.0 - alpha) * lastX
        val filteredY = alpha * y + (1.0 - alpha) * lastY

        xPrev = filteredX
        yPrev = filteredY

        return Pair(filteredX, filteredY)
    }

    fun reset() {
        xPrev = null
        yPrev = null
        dxPrev = 0.0
        dyPrev = 0.0
        tPrev = null
    }

    val lastPosition: Pair<Double, Double>?
        get() {
            // Read each field once so a concurrent reset() cannot null it between check and use.
            val x = xPrev
            val y = yPrev
            return if (x != null && y != null) Pair(x, y) else null
        }
}

/**
 * 2D Linear Kalman Filter tracking 4D state vector x = [x, y, vx, vy]^T with constant-velocity model.
 * Implemented with closed-form scalar 2x2 covariance updates per axis (no matrix objects). Note that
 * filter(), [state] and [velocity] return a boxed Pair<Double, Double>, so each call allocates.
 *
 * Defaults are tuned for normalized [0,1] screen coordinates at 10-30 FPS: with Q = 1.0 and R = 1e-2 a
 * 0.4 step reaches 90% in about 330 ms after warm-up (Q = 1e-3 needed about 1.65 s). As with any
 * constant-velocity model, a step overshoots by roughly 15-20% before settling.
 *
 * @param processNoiseQ White-acceleration variance in (normalized units / s^2)^2 (default 1.0).
 * @param measurementNoiseR Measurement variance in normalized units^2 (default 1e-2).
 */
class GazeKalmanFilter2D(
    var processNoiseQ: Double = 1.0,
    var measurementNoiseR: Double = 1e-2
) {
    // State vector: x, y, vx, vy
    private var posX: Double = 0.0
    private var posY: Double = 0.0
    private var velX: Double = 0.0
    private var velY: Double = 0.0

    // Covariance terms for X (p00, p01, p10, p11)
    private var pX00: Double = 1.0
    private var pX01: Double = 0.0
    private var pX10: Double = 0.0
    private var pX11: Double = 1.0

    // Covariance terms for Y (p00, p01, p10, p11)
    private var pY00: Double = 1.0
    private var pY01: Double = 0.0
    private var pY10: Double = 0.0
    private var pY11: Double = 1.0

    private var tPrev: Long? = null
    private var isInitialized: Boolean = false

    fun filter(measX: Double, measY: Double, timestampMs: Long): Pair<Double, Double> {
        val lastT = tPrev
        if (!isInitialized || lastT == null || isStaleGap(lastT, timestampMs)) {
            posX = measX
            posY = measY
            velX = 0.0
            velY = 0.0
            pX00 = 1.0; pX01 = 0.0; pX10 = 0.0; pX11 = 1.0
            pY00 = 1.0; pY01 = 0.0; pY10 = 0.0; pY11 = 1.0
            tPrev = timestampMs
            isInitialized = true
            return Pair(measX, measY)
        }

        val dt = ((timestampMs - lastT).coerceIn(1L, 1000L)) / 1000.0
        tPrev = timestampMs

        // 1. Predict Step (constant velocity model)
        val predX = posX + velX * dt
        val predY = posY + velY * dt

        val dt2 = dt * dt
        val q00 = 0.25 * dt2 * dt2 * processNoiseQ
        val q01 = 0.5 * dt * dt2 * processNoiseQ
        val q11 = dt2 * processNoiseQ

        val predPX00 = pX00 + dt * (pX10 + pX01) + dt2 * pX11 + q00
        val predPX01 = pX01 + dt * pX11 + q01
        val predPX10 = pX10 + dt * pX11 + q01
        val predPX11 = pX11 + q11

        val predPY00 = pY00 + dt * (pY10 + pY01) + dt2 * pY11 + q00
        val predPY01 = pY01 + dt * pY11 + q01
        val predPY10 = pY10 + dt * pY11 + q01
        val predPY11 = pY11 + q11

        // 2. Update Step for X
        val resX = measX - predX
        val sX = predPX00 + measurementNoiseR
        val kX0 = predPX00 / sX
        val kX1 = predPX10 / sX

        posX = predX + kX0 * resX
        velX = velX + kX1 * resX

        pX00 = (1.0 - kX0) * predPX00
        pX01 = (1.0 - kX0) * predPX01
        pX10 = predPX10 - kX1 * predPX00
        pX11 = predPX11 - kX1 * predPX01

        // 3. Update Step for Y
        val resY = measY - predY
        val sY = predPY00 + measurementNoiseR
        val kY0 = predPY00 / sY
        val kY1 = predPY10 / sY

        posY = predY + kY0 * resY
        velY = velY + kY1 * resY

        pY00 = (1.0 - kY0) * predPY00
        pY01 = (1.0 - kY0) * predPY01
        pY10 = predPY10 - kY1 * predPY00
        pY11 = predPY11 - kY1 * predPY01

        return Pair(posX, posY)
    }

    fun reset() {
        isInitialized = false
        tPrev = null
        posX = 0.0
        posY = 0.0
        velX = 0.0
        velY = 0.0
    }

    val state: Pair<Double, Double>
        get() = Pair(posX, posY)

    val velocity: Pair<Double, Double>
        get() = Pair(velX, velY)
}
