package com.example.enrollment

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.EnrollmentScanOverlayHost
import com.example.data.model.EnrollmentBaseline
import com.example.domain.EnrollmentFailure
import com.example.domain.EnrollmentGuidance
import com.example.domain.EnrollmentPhase
import com.example.domain.EnrollmentScanState
import com.example.domain.EnrollmentWarning
import com.example.ui.components.EnrollmentScanOverlay
import com.example.ui.components.enrollmentBaselineSummaryLines
import com.example.ui.components.enrollmentCompleteSummary
import com.example.ui.components.enrollmentFailureText
import com.example.ui.components.enrollmentGuidanceNeedsFixation
import com.example.ui.components.enrollmentGuidanceText
import com.example.ui.components.enrollmentPhaseLabel
import com.example.ui.components.enrollmentSecondaryLine
import com.example.ui.components.enrollmentWarningLabel
import com.example.ui.components.enrollmentWarningLabelForName
import com.example.ui.components.formatEnrollmentCapturedAt
import com.example.ui.components.isEnrollmentScanPhaseActive
import com.example.ui.screens.CalibrationStudioScreen
import com.example.ui.viewmodel.CalibrationWizardState
import com.example.ui.viewmodel.LiveGazeHudState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI side of the per-user face & eye enrollment scan (contract C4):
 * - the Enrollment card of the idle Calibrate overview shows "Not enrolled" + the explanation and the
 *   scan button without a baseline, and the measured summary with Re-scan / Clear with one,
 * - the card reports the outcome of the scan that just ended (COMPLETE summary, FAILED reason) and keeps
 *   its buttons disabled while a scan runs,
 * - the overlay shows the guidance text for the current guidance, the "Skip brow step" button only in
 *   BROW_RAISE, Cancel invokes the callback, nothing is composed outside the active phases, and touches
 *   outside its buttons reach the content underneath,
 * - the MainActivity host cancels a scan when it leaves composition mid-scan, but not on completion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class UiEnrollmentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val baseline = EnrollmentBaseline(
        version = 1,
        capturedAtMs = 1_762_700_000_123L, // 2025-11-09
        deviceModel = "Pixel 10 Pro XL",
        openEyeLevel = 0.91f,
        closedEyeLevel = 0.12f,
        leftOpenLevel = 0.93f,
        rightOpenLevel = 0.89f,
        baselineEyeDistancePx = 151.25,
        neutralBrowRatio = 0.335f,
        raisedBrowRatio = 0.418f,
        naturalBlinkCount = 3,
        naturalBlinkMedianMs = 190L,
        naturalBlinkP95Ms = 260L,
        closeBlinkThreshold = 0.3965f,
        openBlinkThreshold = 0.6335f,
        intentionalBlinkMinMs = 350L,
        meanAmbientLuma = 138,
        meanEyeConfidence = 0.87f,
        frameCount = 412,
        warnings = listOf("LOW_LIGHT", "FEW_NATURAL_BLINKS")
    )

    private fun scanning(phase: EnrollmentPhase, guidance: EnrollmentGuidance) =
        EnrollmentScanState(phase = phase, guidance = guidance, phaseProgress = 0.5f, overallProgress = 0.4f)

    // ------------------------------------------------------------------ Enrollment card

    @Test
    fun card_withoutBaseline_showsNotEnrolled_explanation_andStartInvokesCallback() {
        var startCalls = 0
        var clearCalls = 0
        composeRule.setContent {
            CalibrationStudioScreen(
                calibrationState = CalibrationWizardState(),
                hudState = LiveGazeHudState(),
                onStartWizard = {},
                onCancelWizard = {},
                onUpdateProfileName = {},
                onSaveProfile = {},
                onSnapSimulatorToCurrentTarget = { _, _ -> },
                enrollmentState = EnrollmentScanState(),
                enrollmentBaseline = null,
                onStartEnrollment = { startCalls++ },
                onClearEnrollment = { clearCalls++ }
            )
        }

        composeRule.onNodeWithTag("enrollment_card").assertExists()
        composeRule.onNodeWithTag("enrollment_status_text").assertTextEquals("Not enrolled")
        composeRule.onNodeWithTag("enrollment_explanation_text")
            .assertTextContains("15-second scan", substring = true)
        composeRule.onNodeWithTag("enrollment_explanation_text")
            .assertTextContains("No images are stored", substring = true)
        composeRule.onNodeWithTag("rescan_enrollment_button").assertDoesNotExist()
        composeRule.onNodeWithTag("clear_enrollment_button").assertDoesNotExist()
        composeRule.onNodeWithTag("enrollment_outcome_text").assertDoesNotExist()

        composeRule.onNodeWithTag("start_enrollment_button").assertIsEnabled().performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(1, startCalls)
            assertEquals(0, clearCalls)
        }
    }

    @Test
    fun card_withBaseline_showsEnrolledSummary_andRescanClearInvokeCallbacks() {
        var startCalls = 0
        var clearCalls = 0
        composeRule.setContent {
            CalibrationStudioScreen(
                calibrationState = CalibrationWizardState(),
                hudState = LiveGazeHudState(),
                onStartWizard = {},
                onCancelWizard = {},
                onUpdateProfileName = {},
                onSaveProfile = {},
                onSnapSimulatorToCurrentTarget = { _, _ -> },
                enrollmentState = EnrollmentScanState(),
                enrollmentBaseline = baseline,
                onStartEnrollment = { startCalls++ },
                onClearEnrollment = { clearCalls++ }
            )
        }

        composeRule.onNodeWithTag("enrollment_status_text").assertTextContains("Enrolled ", substring = true)
        composeRule.onNodeWithTag("enrollment_status_text").assertTextContains("2025", substring = true)
        composeRule.onNodeWithTag("start_enrollment_button").assertDoesNotExist()
        composeRule.onNodeWithTag("enrollment_explanation_text").assertDoesNotExist()

        // The measured numbers, Locale.US formatted.
        composeRule.onNodeWithText("Eye levels: open 0.91 • closed 0.12 (left 0.93, right 0.89)").assertExists()
        composeRule.onNodeWithText("Blink thresholds: close 0.40 • open 0.63").assertExists()
        composeRule.onNodeWithText("Command blink: at least 350 ms").assertExists()
        composeRule.onNodeWithText("Natural blinks during the scan: 3 (median 190 ms, p95 260 ms)").assertExists()
        composeRule.onNodeWithText("Brow ratio: neutral 0.335 • raised 0.418").assertExists()
        composeRule.onNodeWithTag("enrollment_warnings_text").assertTextEquals("Warnings: Low light, Few natural blinks")

        composeRule.onNodeWithTag("rescan_enrollment_button").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, startCalls) }
        composeRule.onNodeWithTag("clear_enrollment_button").performScrollTo().performClick()
        composeRule.runOnIdle {
            assertEquals(1, startCalls)
            assertEquals(1, clearCalls)
        }
    }

    @Test
    fun card_showsOutcomeOfTheScanThatJustEnded_andDisablesButtonsWhileScanning() {
        var state by mutableStateOf(EnrollmentScanState())
        var enrolled by mutableStateOf<EnrollmentBaseline?>(null)
        composeRule.setContent {
            CalibrationStudioScreen(
                calibrationState = CalibrationWizardState(),
                hudState = LiveGazeHudState(),
                onStartWizard = {},
                onCancelWizard = {},
                onUpdateProfileName = {},
                onSaveProfile = {},
                onSnapSimulatorToCurrentTarget = { _, _ -> },
                enrollmentState = state,
                enrollmentBaseline = enrolled
            )
        }

        // FAILED: the reason is shown, the person is still not enrolled.
        composeRule.runOnIdle {
            state = EnrollmentScanState(
                phase = EnrollmentPhase.FAILED,
                guidance = EnrollmentGuidance.FAILED,
                failure = EnrollmentFailure.EYES_DID_NOT_CLOSE
            )
        }
        composeRule.onNodeWithTag("enrollment_outcome_text")
            .assertTextContains("Scan failed: Eye closure was not detected", substring = true)
        composeRule.onNodeWithTag("enrollment_status_text").assertTextEquals("Not enrolled")

        // Scanning: the card says so and its own buttons cannot restart the scan under the overlay.
        composeRule.runOnIdle { state = scanning(EnrollmentPhase.EYES_OPEN, EnrollmentGuidance.LOOK_AT_DOT) }
        composeRule.onNodeWithTag("enrollment_status_text").assertTextEquals("Scanning…")
        composeRule.onNodeWithTag("start_enrollment_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("enrollment_outcome_text").assertDoesNotExist()

        // COMPLETE: the applied numbers are summarized and the baseline summary appears.
        composeRule.runOnIdle {
            enrolled = baseline
            state = EnrollmentScanState(
                phase = EnrollmentPhase.COMPLETE,
                guidance = EnrollmentGuidance.DONE,
                phaseProgress = 1f,
                overallProgress = 1f,
                result = baseline
            )
        }
        composeRule.onNodeWithTag("enrollment_outcome_text")
            .assertTextEquals(enrollmentCompleteSummary(baseline))
        composeRule.onNodeWithTag("enrollment_outcome_text")
            .assertTextContains("blink close 0.40 / open 0.63, command blink at least 350 ms", substring = true)
        composeRule.onNodeWithTag("enrollment_status_text").assertTextContains("Enrolled ", substring = true)
        composeRule.onNodeWithTag("rescan_enrollment_button").assertIsEnabled()
        composeRule.onNodeWithTag("clear_enrollment_button").assertIsEnabled()

        // Re-scan in progress with a baseline present: both buttons are disabled.
        composeRule.runOnIdle { state = scanning(EnrollmentPhase.POSITIONING, EnrollmentGuidance.NO_FACE) }
        composeRule.onNodeWithTag("rescan_enrollment_button").assertIsNotEnabled()
        composeRule.onNodeWithTag("clear_enrollment_button").assertIsNotEnabled()
    }

    // ------------------------------------------------------------------ overlay

    @Test
    fun overlay_showsGuidanceAndPhase_skipBrowOnlyInBrowRaise_cancelInvokesCallback() {
        var cancelCalls = 0
        var skipCalls = 0
        var state by mutableStateOf(scanning(EnrollmentPhase.EYES_CLOSED, EnrollmentGuidance.CLOSE_EYES))
        composeRule.setContent {
            EnrollmentScanOverlay(
                state = state,
                onCancel = { cancelCalls++ },
                onSkipBrow = { skipCalls++ },
                modifier = Modifier.fillMaxSize()
            )
        }

        composeRule.onNodeWithTag("enrollment_scan_overlay").assertExists()
        composeRule.onNodeWithTag("enrollment_guidance_text").assertTextEquals("Now close both eyes.")
        composeRule.onNodeWithTag("enrollment_phase_label").assertTextEquals("Step 3 of 4 — Eyes closed")
        composeRule.onNodeWithTag("enrollment_skip_brow_button").assertDoesNotExist()
        composeRule.onNodeWithTag("enrollment_cancel_button").assertExists()
        // Eyes closed: the dot does not ask to be looked at.
        composeRule.onNodeWithTag("enrollment_fixation_dot")
            .assert(hasContentDescription("Fixation dot. Step progress 50 percent"))

        composeRule.runOnIdle {
            state = scanning(EnrollmentPhase.BROW_RAISE, EnrollmentGuidance.RAISE_BROWS).copy(
                liveEyeOpenProb = 0.87f,
                warnings = setOf(EnrollmentWarning.LOW_LIGHT, EnrollmentWarning.FEW_NATURAL_BLINKS),
                retries = 1,
                elapsedMs = 9_400L
            )
        }
        composeRule.onNodeWithTag("enrollment_guidance_text").assertTextEquals("Raise your eyebrows and hold them up.")
        composeRule.onNodeWithTag("enrollment_phase_label").assertTextEquals("Step 4 of 4 — Raise your brows")
        composeRule.onNodeWithTag("enrollment_eye_open_label").assertTextEquals("Eye openness 0.87")
        composeRule.onNodeWithTag("enrollment_warning_chip_LOW_LIGHT").assertTextEquals("Low light")
        composeRule.onNodeWithTag("enrollment_warning_chip_FEW_NATURAL_BLINKS").assertTextEquals("Few natural blinks")
        composeRule.onNodeWithTag("enrollment_secondary_text").assertTextEquals("Retry 1 • 9s elapsed")
        composeRule.onNodeWithTag("enrollment_fixation_dot")
            .assert(hasContentDescription("look here", substring = true))

        composeRule.onNodeWithTag("enrollment_skip_brow_button").assertExists().performClick()
        composeRule.runOnIdle {
            assertEquals(1, skipCalls)
            assertEquals(0, cancelCalls)
        }
        composeRule.onNodeWithTag("enrollment_cancel_button").performClick()
        composeRule.runOnIdle {
            assertEquals(1, skipCalls)
            assertEquals(1, cancelCalls)
        }
    }

    @Test
    fun overlay_composesNothingOutsideTheActivePhases() {
        var state by mutableStateOf(EnrollmentScanState())
        composeRule.setContent {
            EnrollmentScanOverlay(state = state, onCancel = {}, onSkipBrow = {}, modifier = Modifier.fillMaxSize())
        }
        composeRule.onNodeWithTag("enrollment_scan_overlay").assertDoesNotExist()

        for (phase in listOf(
            EnrollmentPhase.POSITIONING, EnrollmentPhase.EYES_OPEN, EnrollmentPhase.EYES_CLOSED, EnrollmentPhase.BROW_RAISE
        )) {
            composeRule.runOnIdle { state = scanning(phase, EnrollmentGuidance.HOLD_STILL) }
            composeRule.onNodeWithTag("enrollment_scan_overlay").assertExists()
        }

        composeRule.runOnIdle {
            state = EnrollmentScanState(phase = EnrollmentPhase.COMPLETE, guidance = EnrollmentGuidance.DONE, result = baseline)
        }
        composeRule.onNodeWithTag("enrollment_scan_overlay").assertDoesNotExist()
        composeRule.runOnIdle {
            state = EnrollmentScanState(
                phase = EnrollmentPhase.FAILED,
                guidance = EnrollmentGuidance.FAILED,
                failure = EnrollmentFailure.CANCELLED
            )
        }
        composeRule.onNodeWithTag("enrollment_scan_overlay").assertDoesNotExist()
    }

    @Test
    fun overlay_passesTouchesThroughOutsideItsButtons() {
        var underneathClicks = 0
        var cancelCalls = 0
        composeRule.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                Button(
                    onClick = { underneathClicks++ },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("content_under_enrollment_overlay")
                ) {
                    Text("Under")
                }
                EnrollmentScanOverlay(
                    state = scanning(EnrollmentPhase.EYES_OPEN, EnrollmentGuidance.LOOK_AT_DOT),
                    onCancel = { cancelCalls++ },
                    onSkipBrow = {},
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // The click lands on the screen centre, exactly where the fixation dot is drawn.
        composeRule.onNodeWithTag("content_under_enrollment_overlay").performClick()
        composeRule.runOnIdle {
            assertEquals(1, underneathClicks)
            assertEquals(0, cancelCalls)
        }
        // The buttons themselves do consume their touch.
        composeRule.onNodeWithTag("enrollment_cancel_button").performClick()
        composeRule.runOnIdle {
            assertEquals(1, underneathClicks)
            assertEquals(1, cancelCalls)
        }
    }

    @Test
    fun overlayHost_cancelsWhenLeavingCompositionMidScan_butNotOnCompletion() {
        var cancelCalls = 0
        val holder = mutableStateOf(scanning(EnrollmentPhase.EYES_OPEN, EnrollmentGuidance.LOOK_AT_DOT))
        var show by mutableStateOf(true)
        composeRule.setContent {
            if (show) {
                EnrollmentScanOverlayHost(
                    enrollmentState = holder,
                    onCancel = { cancelCalls++ },
                    onSkipBrow = {}
                )
            }
        }
        composeRule.onNodeWithTag("enrollment_scan_overlay").assertExists()
        composeRule.onNodeWithTag("enrollment_guidance_text")
            .assertTextEquals(enrollmentGuidanceText(EnrollmentGuidance.LOOK_AT_DOT))

        // A scanned frame reaches the overlay through the holder.
        composeRule.runOnIdle { holder.value = scanning(EnrollmentPhase.EYES_CLOSED, EnrollmentGuidance.KEEP_EYES_CLOSED) }
        composeRule.onNodeWithTag("enrollment_guidance_text").assertTextEquals("Keep both eyes closed.")

        // Tab switch mid-scan: the host leaves composition while the phase is active -> cancel.
        composeRule.runOnIdle { show = false }
        composeRule.runOnIdle { assertEquals(1, cancelCalls) }

        // Normal end: MainActivity removes the host because the phase turned terminal -> no cancel.
        composeRule.runOnIdle {
            holder.value = scanning(EnrollmentPhase.BROW_RAISE, EnrollmentGuidance.RAISE_BROWS)
            show = true
        }
        composeRule.onNodeWithTag("enrollment_skip_brow_button").assertExists()
        composeRule.runOnIdle {
            holder.value = EnrollmentScanState(phase = EnrollmentPhase.COMPLETE, guidance = EnrollmentGuidance.DONE, result = baseline)
            show = false
        }
        composeRule.runOnIdle { assertEquals(1, cancelCalls) }
    }

    // ------------------------------------------------------------------ pure helpers

    @Test
    fun helpers_activePhases_guidanceTexts_andLabelsAreCompleteAndDistinct() {
        assertTrue(isEnrollmentScanPhaseActive(EnrollmentPhase.POSITIONING))
        assertTrue(isEnrollmentScanPhaseActive(EnrollmentPhase.EYES_OPEN))
        assertTrue(isEnrollmentScanPhaseActive(EnrollmentPhase.EYES_CLOSED))
        assertTrue(isEnrollmentScanPhaseActive(EnrollmentPhase.BROW_RAISE))
        assertFalse(isEnrollmentScanPhaseActive(EnrollmentPhase.IDLE))
        assertFalse(isEnrollmentScanPhaseActive(EnrollmentPhase.COMPLETE))
        assertFalse(isEnrollmentScanPhaseActive(EnrollmentPhase.FAILED))

        val guidanceTexts = EnrollmentGuidance.entries.map(::enrollmentGuidanceText)
        assertTrue(guidanceTexts.all { it.isNotBlank() && !it.contains('_') })
        assertEquals("every guidance needs its own line", EnrollmentGuidance.entries.size, guidanceTexts.toSet().size)

        val phaseLabels = EnrollmentPhase.entries.map(::enrollmentPhaseLabel)
        assertEquals(EnrollmentPhase.entries.size, phaseLabels.toSet().size)
        assertTrue(phaseLabels.all { it.isNotBlank() })

        val warningLabels = EnrollmentWarning.entries.map(::enrollmentWarningLabel)
        assertEquals(EnrollmentWarning.entries.size, warningLabels.toSet().size)
        assertEquals("Brow step skipped", enrollmentWarningLabelForName("BROW_SKIPPED"))
        assertEquals("SOMETHING_NEW", enrollmentWarningLabelForName("SOMETHING_NEW"))

        val failureTexts = (EnrollmentFailure.entries.map { enrollmentFailureText(it) } + enrollmentFailureText(null))
        assertEquals(EnrollmentFailure.entries.size + 1, failureTexts.toSet().size)
        assertTrue(failureTexts.all { it.isNotBlank() })

        // The dot pulses only while the person should be looking at it.
        assertTrue(enrollmentGuidanceNeedsFixation(EnrollmentGuidance.LOOK_AT_DOT))
        assertTrue(enrollmentGuidanceNeedsFixation(EnrollmentGuidance.HOLD_STILL))
        assertTrue(enrollmentGuidanceNeedsFixation(EnrollmentGuidance.OPEN_EYES))
        assertTrue(enrollmentGuidanceNeedsFixation(EnrollmentGuidance.RAISE_BROWS))
        assertFalse(enrollmentGuidanceNeedsFixation(EnrollmentGuidance.CLOSE_EYES))
        assertFalse(enrollmentGuidanceNeedsFixation(EnrollmentGuidance.KEEP_EYES_CLOSED))
        assertFalse(enrollmentGuidanceNeedsFixation(EnrollmentGuidance.NO_FACE))
    }

    @Test
    fun helpers_summaryNeverInventsAMeasurement_andFormatsLocaleUs() {
        val lines = enrollmentBaselineSummaryLines(baseline)
        assertEquals(6, lines.size)
        assertEquals("Blink thresholds: close 0.40 • open 0.63", lines[1])
        assertEquals("Command blink: at least 350 ms", lines[2])
        assertEquals("Brow ratio: neutral 0.335 • raised 0.418", lines[4])
        assertTrue(lines[5].startsWith("Conditions: 412 frames • eye confidence 87% • luma 138/255 • IOD 151.3 px"))
        assertTrue(lines[5].endsWith("• Pixel 10 Pro XL"))

        // A skipped brow step and zero natural blinks are reported as such, never as a number.
        val sparse = baseline.copy(raisedBrowRatio = null, naturalBlinkCount = 0, naturalBlinkMedianMs = 0L, naturalBlinkP95Ms = 0L, deviceModel = "")
        val sparseLines = enrollmentBaselineSummaryLines(sparse)
        assertEquals("Natural blinks during the scan: none", sparseLines[3])
        assertEquals("Brow ratio: neutral 0.335 • raised not measured", sparseLines[4])
        assertFalse(sparseLines.any { it.contains("0.418") })
        assertFalse(sparseLines[5].contains("•  "))
        assertTrue(enrollmentCompleteSummary(sparse).endsWith("brow neutral 0.335 (brow raise not measured)."))
        assertEquals(
            "Scan complete: blink close 0.40 / open 0.63, command blink at least 350 ms, brow neutral 0.335 / raised 0.418.",
            enrollmentCompleteSummary(baseline)
        )

        val captured = formatEnrollmentCapturedAt(baseline.capturedAtMs)
        assertTrue(captured, captured.startsWith("Nov ") && captured.contains("2025"))

        // Secondary line: nothing to say on a fresh, centred, sub-second scan.
        assertNull(enrollmentSecondaryLine(scanning(EnrollmentPhase.EYES_OPEN, EnrollmentGuidance.LOOK_AT_DOT)))
        assertEquals(
            "Face not in position yet",
            enrollmentSecondaryLine(scanning(EnrollmentPhase.POSITIONING, EnrollmentGuidance.CENTER_FACE))
        )
        assertEquals(
            "Face in position • 2s elapsed",
            enrollmentSecondaryLine(
                scanning(EnrollmentPhase.POSITIONING, EnrollmentGuidance.HOLD_STILL)
                    .copy(liveFaceCentered = true, elapsedMs = 2_300L)
            )
        )
    }
}
