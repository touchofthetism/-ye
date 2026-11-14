package com.example.ui.developer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.data.model.CalibrationProfile
import com.example.data.model.TrackingSession
import com.example.domain.EnrollmentScanState
import com.example.ui.viewmodel.AttentionSessionLiveState
import com.example.ui.viewmodel.CalibrationWizardState
import com.example.ui.viewmodel.LiveGazeHudState

/*
 * Developer slots (docs/UI_DESIGN.md 2.16.3). Each one renders nothing while developer mode is off.
 *
 * CONTRACT STUB (UI_DESIGN.md 11.3): the signatures are fixed so that screens can place the slots. The developer
 * panel engineer replaces the bodies.
 */

/** Calibrate wizard band: legacy heading with coordinates, raw counter text, per-dot counts, "Lock sim here". */
@Composable
fun DevWizardDetails(
    cal: CalibrationWizardState,
    hud: LiveGazeHudState,
    onLockSimulatorHere: () -> Unit,
    modifier: Modifier = Modifier
) {
}

/** Calibrate result: report title, per-dot table, total samples, matrix, raw layout label. */
@Composable
fun DevCalibrationResultDetails(cal: CalibrationWizardState, modifier: Modifier = Modifier) {
}

/** Face scan, scanning: eye-openness number, retries, elapsed time. */
@Composable
fun DevScanDetails(enr: EnrollmentScanState, modifier: Modifier = Modifier) {
}

/** Read, recording: natural and command blink counts, cluster count, frame-rate guard. */
@Composable
fun DevRecordingDetails(att: AttentionSessionLiveState, modifier: Modifier = Modifier) {
}

/** Replay and history rows: zone percentages, natural and command blink counts, cluster count. */
@Composable
fun DevSessionDetails(session: TrackingSession, modifier: Modifier = Modifier) {
}

/** Saved calibration rows: device model and the raw error line. */
@Composable
fun DevProfileDetails(profile: CalibrationProfile, modifier: Modifier = Modifier) {
}
