package com.example

import com.example.data.model.DEFAULT_AFFINE_MATRIX_2X6
import com.example.data.model.GazeFeatureVector
import com.example.domain.GazeCalibrationMath
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM unit test (no Robolectric) of the gaze projection: the default matrix maps the neutral pose to the
 * screen centre, follows the documented sign convention, and the output is clamped to the visible screen.
 * (Replaces the template `2 + 2 == 4` placeholder, which exercised no app code.)
 */
class ExampleUnitTest {

    @Test
    fun projectGaze_mapsNeutralToCentre_followsSignConvention_andClampsToTheScreen() {
        val (cx, cy) = GazeCalibrationMath.projectGaze(GazeFeatureVector(), DEFAULT_AFFINE_MATRIX_2X6)
        assertEquals(0.5f, cx, 1e-6f)
        assertEquals(0.5f, cy, 1e-6f)

        // +gx / +yaw = looking right (+x); +gy / +pitch = looking down (+y)
        val (rx, ry) = GazeCalibrationMath.projectGaze(GazeFeatureVector(gx = 0.05, yaw = 2.0), DEFAULT_AFFINE_MATRIX_2X6)
        assertEquals(0.5f + 0.42f * 0.2f + 0.32f * 0.1f, rx, 1e-5f)
        assertEquals(0.5f, ry, 1e-5f)
        val (dx, dy) = GazeCalibrationMath.projectGaze(GazeFeatureVector(gy = 0.04, pitch = 1.5), DEFAULT_AFFINE_MATRIX_2X6)
        assertEquals(0.5f, dx, 1e-5f)
        assertEquals(0.5f + 0.44f * 0.2f + 0.36f * 0.1f, dy, 1e-5f)

        // Extreme features saturate at the 2 % screen margin instead of leaving the screen
        val far = GazeFeatureVector(gx = 5.0, gy = -5.0, yaw = 90.0, pitch = -90.0)
        assertEquals(Pair(0.98f, 0.02f), GazeCalibrationMath.projectGaze(far, DEFAULT_AFFINE_MATRIX_2X6))

        // A malformed matrix falls back to the default mapping
        assertEquals(Pair(cx, cy), GazeCalibrationMath.projectGaze(GazeFeatureVector(), listOf(1.0, 2.0)))
    }
}
