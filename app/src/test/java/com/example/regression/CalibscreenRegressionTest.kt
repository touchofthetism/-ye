package com.example.regression

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.model.GazeFeatureVector
import com.example.domain.GazeCalibrationMath
import com.example.ui.screens.CalibrationStudioScreen
import com.example.ui.screens.CalibrationTargetFullscreenOverlay
import com.example.ui.screens.calibrationTargetCenterPx
import com.example.ui.screens.currentCalibrationFixationTarget
import com.example.ui.viewmodel.CalibrationFlowStep
import com.example.ui.viewmodel.CalibrationWizardState
import com.example.ui.viewmodel.LiveGazeHudState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for CalibrationStudioScreen.kt:
 * - real-device captures never drive the gaze simulator (synthetic calibration samples),
 * - non-current target nodes never record under the current target,
 * - leaving the screen mid-wizard cancels the wizard,
 * - "no face" is reported instead of an endless settling message,
 * - unmeasured / in-sample accuracy is never shown as a validated screen error,
 * - the fixation target is drawn full-frame (the frame gaze is normalized to) and passes touches through.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class CalibscreenRegressionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val faceLockedFeature = GazeFeatureVector(faceDetected = true)

    private fun fitStepAt(index: Int) = CalibrationWizardState(
        step = CalibrationFlowStep.FIT_5_POINTS,
        currentTargetIndex = index,
        isSettlingDelay = true
    )

    @Test
    fun captureButton_neverSnapsSimulatorUnlessSimulatorIsAlreadyOn() {
        val snapTargets = mutableListOf<Pair<Float, Float>>()
        var captureCalls = 0
        var hud by mutableStateOf(LiveGazeHudState(rawFeature = faceLockedFeature, isSimulatorProxyEnabled = false))

        composeRule.setContent {
            CalibrationStudioScreen(
                calibrationState = fitStepAt(1),
                hudState = hud,
                onStartWizard = {},
                onCancelWizard = {},
                onUpdateProfileName = {},
                onSaveProfile = {},
                onSnapSimulatorToCurrentTarget = { x, y -> snapTargets += x to y },
                onCaptureManualSamples = { captureCalls++ }
            )
        }

        // Real camera (simulator off): capture must use the user's own frames, never the simulator.
        composeRule.onNodeWithTag("capture_gaze_point_button").performClick()
        composeRule.runOnIdle {
            assertEquals("capture on real hardware must not turn on / move the simulator", emptyList<Pair<Float, Float>>(), snapTargets)
            assertEquals(1, captureCalls)
        }

        // Simulator explicitly on: the capture may place the simulated gaze on the target first.
        composeRule.runOnIdle { hud = hud.copy(isSimulatorProxyEnabled = true) }
        composeRule.onNodeWithTag("capture_gaze_point_button").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(0.14f to 0.16f), snapTargets)
            assertEquals(2, captureCalls)
        }
    }

    @Test
    fun tappingNonCurrentTargetNode_recordsNothingUnderTheCurrentTarget() {
        val snapTargets = mutableListOf<Pair<Float, Float>>()
        var captureCalls = 0

        composeRule.setContent {
            CalibrationStudioScreen(
                calibrationState = fitStepAt(1),
                hudState = LiveGazeHudState(rawFeature = faceLockedFeature, isSimulatorProxyEnabled = true),
                onStartWizard = {},
                onCancelWizard = {},
                onUpdateProfileName = {},
                onSaveProfile = {},
                onSnapSimulatorToCurrentTarget = { x, y -> snapTargets += x to y },
                onCaptureManualSamples = { captureCalls++ }
            )
        }

        // P5 is not the current target (P2 is): tapping it must not feed P5's features into P2.
        composeRule.onNodeWithTag("calibration_point_target_4").performClick()
        composeRule.runOnIdle {
            assertEquals(emptyList<Pair<Float, Float>>(), snapTargets)
            assertEquals(0, captureCalls)
        }

        // The current node still captures for its own coordinate.
        composeRule.onNodeWithTag("calibration_point_target_1").performClick()
        composeRule.runOnIdle {
            assertEquals(listOf(0.14f to 0.16f), snapTargets)
            assertEquals(1, captureCalls)
        }
    }

    @Test
    fun leavingCompositionMidWizard_cancelsWizard_butStepTransitionsAndSummaryDoNot() {
        var cancelCalls = 0
        var show by mutableStateOf(true)
        var state by mutableStateOf(fitStepAt(2))

        composeRule.setContent {
            if (show) {
                CalibrationStudioScreen(
                    calibrationState = state,
                    hudState = LiveGazeHudState(rawFeature = faceLockedFeature),
                    onStartWizard = {},
                    onCancelWizard = { cancelCalls++ },
                    onUpdateProfileName = {},
                    onSaveProfile = {},
                    onSnapSimulatorToCurrentTarget = { _, _ -> }
                )
            }
        }

        // FIT -> COMPLETED_SUMMARY keeps the screen composed: no cancel.
        composeRule.runOnIdle { state = CalibrationWizardState(step = CalibrationFlowStep.COMPLETED_SUMMARY) }
        composeRule.runOnIdle { assertEquals(0, cancelCalls) }

        // Back in the wizard, a tab switch (screen leaves composition) must cancel it.
        composeRule.runOnIdle { state = fitStepAt(2) }
        composeRule.runOnIdle { show = false }
        composeRule.runOnIdle { assertEquals(1, cancelCalls) }

        // Leaving while the summary is shown must keep the unsaved result.
        composeRule.runOnIdle {
            state = CalibrationWizardState(step = CalibrationFlowStep.COMPLETED_SUMMARY)
            show = true
        }
        composeRule.runOnIdle { show = false }
        composeRule.runOnIdle { assertEquals(1, cancelCalls) }
    }

    @Test
    fun wizardHeader_reportsNoFace_insteadOfEndlessSettling() {
        var hud by mutableStateOf(LiveGazeHudState(rawFeature = GazeFeatureVector(faceDetected = false)))

        composeRule.setContent {
            CalibrationStudioScreen(
                calibrationState = fitStepAt(0),
                hudState = hud,
                onStartWizard = {},
                onCancelWizard = {},
                onUpdateProfileName = {},
                onSaveProfile = {},
                onSnapSimulatorToCurrentTarget = { _, _ -> }
            )
        }

        composeRule.onNodeWithTag("calibration_sample_counter_text")
            .assertTextContains("No face detected", substring = true)

        composeRule.runOnIdle { hud = hud.copy(rawFeature = faceLockedFeature) }
        composeRule.onNodeWithTag("calibration_sample_counter_text")
            .assertTextContains("Settling", substring = true)
    }

    @Test
    fun idleOverview_uncalibratedHud_showsNotCalibrated_notInventedNumbers() {
        // Default HUD = no calibration applied yet (contract: null error fields mean "not validated").
        var hud by mutableStateOf(LiveGazeHudState())

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

        composeRule.onAllNodesWithText("Not calibrated").assertCountEquals(2)
        composeRule.onAllNodesWithText("±10.9%").assertCountEquals(0)
        composeRule.onAllNodesWithText("±14.2%").assertCountEquals(0)
        composeRule.onAllNodesWithText("13.4 mm", substring = true).assertCountEquals(0)

        // Measured values still render as numbers.
        composeRule.runOnIdle {
            hud = hud.copy(activeErrorRadiusPct = 4.2f, activeMaxQuadrantErrorPct = 6.5f, activeErrorMm = 5.1)
        }
        composeRule.onNodeWithText("±4.2%").assertExists()
        composeRule.onNodeWithText("±6.5%").assertExists()
        composeRule.onAllNodesWithText("Not calibrated").assertCountEquals(0)
    }

    @Test
    fun idleOverview_appliedFitWithoutHeldOutValidation_showsNotValidated_notNotCalibrated() {
        // A 5-point fit is applied (hasActiveCalibration) but was never validated: error fields stay null.
        composeRule.setContent {
            CalibrationStudioScreen(
                calibrationState = CalibrationWizardState(),
                hudState = LiveGazeHudState(hasActiveCalibration = true, activeProfileName = "Desk A"),
                onStartWizard = {},
                onCancelWizard = {},
                onUpdateProfileName = {},
                onSaveProfile = {},
                onSnapSimulatorToCurrentTarget = { _, _ -> }
            )
        }

        composeRule.onAllNodesWithText("Not validated").assertCountEquals(2)
        composeRule.onAllNodesWithText("5-point fit only", substring = true).assertCountEquals(2)
        composeRule.onAllNodesWithText("Not calibrated").assertCountEquals(0)
        composeRule.onAllNodesWithText("Default matrix", substring = true).assertCountEquals(0)
    }

    @Test
    fun idleTelemetry_noFaceFrame_showsDashes_notZeroConfidenceOrDefaultIod() {
        var hud by mutableStateOf(
            LiveGazeHudState(
                rawFeature = GazeFeatureVector(
                    faceDetected = false,
                    leftEyeConfidence = 0f,
                    rightEyeConfidence = 0f,
                    leftEyeOpenProb = 0f,
                    rightEyeOpenProb = 0f
                )
            )
        )

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

        composeRule.onNodeWithTag("calibration_eye_telemetry_text")
            .assertTextContains("Eye Confidence: Left -- • Right -- | IOD: -- | Eye Open: --", substring = true)
        composeRule.onAllNodesWithText("148.0 px", substring = true).assertCountEquals(0)

        // A face-detected frame shows the measured values.
        composeRule.runOnIdle {
            hud = hud.copy(
                rawFeature = GazeFeatureVector(
                    faceDetected = true,
                    leftEyeConfidence = 0.81f,
                    rightEyeConfidence = 0.77f,
                    leftEyeOpenProb = 0.9f,
                    rightEyeOpenProb = 0.9f,
                    eyeDistancePx = 151.2
                )
            )
        }
        composeRule.onNodeWithTag("calibration_eye_telemetry_text")
            .assertTextContains("Left 81% • Right 77% | IOD: 151.2 px | Eye Open: 0.90", substring = true)
    }

    @Test
    fun summary_quickFit_labelsInSampleResidual_notValidatedScreenError() {
        var state by mutableStateOf(
            CalibrationWizardState(
                step = CalibrationFlowStep.COMPLETED_SUMMARY,
                includeHeldOutValidation = false,
                validationMeanErrorPct = 2.3,
                validationMaxErrorPct = 3.1,
                validationMeanErrorMm = 2.9,
                totalCollectedSamples = 90
            )
        )

        composeRule.setContent {
            CalibrationStudioScreen(
                calibrationState = state,
                hudState = LiveGazeHudState(),
                onStartWizard = {},
                onCancelWizard = {},
                onUpdateProfileName = {},
                onSaveProfile = {},
                onSnapSimulatorToCurrentTarget = { _, _ -> }
            )
        }

        composeRule.onNodeWithText("FIT RESIDUAL (IN-SAMPLE)").assertExists()
        composeRule.onNodeWithText("WORST FIT POINT").assertExists()
        composeRule.onAllNodesWithText("MEAN SCREEN ERROR").assertCountEquals(0)
        composeRule.onAllNodesWithText("WORST QUADRANT").assertCountEquals(0)
        composeRule.onNodeWithTag("calibration_in_sample_caveat").assertExists()

        // Choosing the 5+4 mode alone proves nothing: until the ViewModel marks the metrics as held-out,
        // they stay labelled as an in-sample residual.
        composeRule.runOnIdle { state = state.copy(includeHeldOutValidation = true) }
        composeRule.onNodeWithText("FIT RESIDUAL (IN-SAMPLE)").assertExists()
        composeRule.onAllNodesWithText("MEAN SCREEN ERROR").assertCountEquals(0)
        composeRule.onNodeWithTag("calibration_in_sample_caveat").assertExists()

        // A held-out run keeps the validated labels and drops the caveat.
        composeRule.runOnIdle { state = state.copy(metricsAreHeldOut = true) }
        composeRule.onNodeWithText("MEAN SCREEN ERROR").assertExists()
        composeRule.onNodeWithText("WORST QUADRANT").assertExists()
        composeRule.onNodeWithText("±2.3%").assertExists()
        composeRule.onNodeWithTag("calibration_in_sample_caveat").assertDoesNotExist()
    }

    @Test
    fun summary_unmeasuredMetrics_showNotMeasured_neverNaN() {
        composeRule.setContent {
            CalibrationStudioScreen(
                calibrationState = CalibrationWizardState(
                    step = CalibrationFlowStep.COMPLETED_SUMMARY,
                    includeHeldOutValidation = true,
                    metricsAreHeldOut = true,
                    validationMeanErrorPct = Double.NaN,
                    validationMaxErrorPct = Double.NaN,
                    validationMeanErrorMm = Double.NaN
                ),
                hudState = LiveGazeHudState(),
                onStartWizard = {},
                onCancelWizard = {},
                onUpdateProfileName = {},
                onSaveProfile = {},
                onSnapSimulatorToCurrentTarget = { _, _ -> }
            )
        }

        composeRule.onAllNodesWithText("NaN", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Not measured").assertCountEquals(2)
    }

    @Test
    fun fullscreenOverlay_drawsCurrentTargetInItsOwnFrame_andPassesTouchesThrough() {
        // Target selection follows step/index: fit points 0-4, held-out points 5-8, nothing otherwise.
        val fit = GazeCalibrationMath.FIVE_POINT_CALIBRATION_TARGETS
        val heldOut = GazeCalibrationMath.HELD_OUT_VALIDATION_TARGETS
        assertEquals(fit[2], currentCalibrationFixationTarget(fitStepAt(2)))
        assertEquals(
            heldOut[1],
            currentCalibrationFixationTarget(
                CalibrationWizardState(step = CalibrationFlowStep.VALIDATE_4_POINTS, currentTargetIndex = 6)
            )
        )
        assertNull(currentCalibrationFixationTarget(CalibrationWizardState(step = CalibrationFlowStep.IDLE)))
        assertNull(currentCalibrationFixationTarget(CalibrationWizardState(step = CalibrationFlowStep.COMPLETED_SUMMARY)))

        // Drawn at normX * width, normY * height of the overlay (the root frame gaze is normalized to).
        val center = calibrationTargetCenterPx(fit[1], 1000f, 2000f)
        assertEquals(140f, center.x, 1e-3f)
        assertEquals(320f, center.y, 1e-3f)

        var underneathClicks = 0
        var state by mutableStateOf(fitStepAt(0))
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                Button(
                    onClick = { underneathClicks++ },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("content_under_overlay")
                ) {
                    Text("Under")
                }
                CalibrationTargetFullscreenOverlay(calibrationState = state, modifier = Modifier.fillMaxSize())
            }
        }

        composeRule.onNodeWithTag("calibration_fullscreen_target_overlay").assertExists()
        // The click lands on the screen center, exactly where P1's bullseye is drawn.
        composeRule.onNodeWithTag("content_under_overlay").performClick()
        composeRule.runOnIdle { assertEquals(1, underneathClicks) }

        composeRule.runOnIdle { state = CalibrationWizardState(step = CalibrationFlowStep.COMPLETED_SUMMARY) }
        composeRule.onNodeWithTag("calibration_fullscreen_target_overlay").assertDoesNotExist()
    }
}
