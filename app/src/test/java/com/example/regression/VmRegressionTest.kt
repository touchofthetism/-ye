package com.example.regression

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.data.model.AnalysisResolutionTier
import com.example.data.model.CalibrationProfile
import com.example.data.model.DEFAULT_AFFINE_MATRIX_2X6
import com.example.data.model.GazeFeatureVector
import com.example.data.model.GazeFilterMode
import com.example.data.model.GazeTriggerMode
import com.example.data.model.TrackingSession
import com.example.data.repository.EyeTrackingRepository
import com.example.data.repository.LocalTelemetryStore
import com.example.domain.GazeCalibrationMath
import com.example.ui.viewmodel.CalibrationFlowStep
import com.example.ui.viewmodel.EyeTrackingViewModel
import com.example.ui.viewmodel.UNCALIBRATED_PROFILE_NAME
import com.example.ui.viewmodel.isRunningOnEmulatorHardware
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.math.sin

/**
 * Regression tests for EyeTrackingViewModel fixes (threading, honest metrics, dwell/blink/brow gating,
 * calibration wizard, background recalibration, recenter, attention analytics, profile lifecycle).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VmRegressionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("com.aistudio.oculagaze.rxkmpq")
                    .setProjectId("demo-no-project")
                    .setApiKey("fake-api-key")
                    .build()
            )
        }
    }

    private fun freshUser() = "vm_reg_" + UUID.randomUUID().toString().replace("-", "").take(12)

    private fun newVm(
        userId: String = freshUser(),
        store: LocalTelemetryStore? = LocalTelemetryStore(context)
    ): EyeTrackingViewModel {
        val dbId = context.getString(R.string.firestore_database_id)
        return EyeTrackingViewModel(
            repository = EyeTrackingRepository(db = FirebaseFirestore.getInstance(dbId)),
            currentUserId = userId,
            localStore = store
        )
    }

    private fun face(
        ts: Long,
        gx: Double = 0.0,
        gy: Double = 0.0,
        open: Float = 0.92f,
        brow: Float = 0.34f
    ) = GazeFeatureVector(
        gx = gx,
        gy = gy,
        leftEyeOpenProb = open,
        rightEyeOpenProb = open,
        browToEyeRatio = brow,
        faceDetected = true,
        timestampMs = ts
    )

    private fun noFace(ts: Long, brow: Float = 0.34f) =
        GazeFeatureVector(faceDetected = false, browToEyeRatio = brow, timestampMs = ts)

    /** gx that the default matrix maps to screen x (yaw/roll/distance neutral). */
    private fun gxFor(x: Double) = (x - 0.5) * 0.25 / 0.42

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * Feeds synthetic, slightly jittered face frames for the current wizard target until [stopWhen].
     * [gxGain] scales the synthetic gx so a fit can be made to differ from the default matrix.
     */
    private fun driveWizard(
        vm: EyeTrackingViewModel,
        startTs: Long,
        gxGain: Double = 0.42,
        stopWhen: (CalibrationFlowStep) -> Boolean
    ): Long {
        var t = startTs
        var guard = 0
        while (!stopWhen(vm.calibrationState.value.step) && guard < 5000) {
            val idx = vm.calibrationState.value.currentTargetIndex
            val target = GazeCalibrationMath.CALIBRATION_AND_VALIDATION_TARGETS[idx]
            val jitter = sin(guard * 1.7) * 0.003
            vm.processIncomingFeatureFrame(
                GazeFeatureVector(
                    gx = (target.normX - 0.5) * gxGain + jitter,
                    gy = (target.normY - 0.5) * 0.36 - jitter,
                    yaw = (target.normX - 0.5) * 14.0,
                    pitch = (target.normY - 0.5) * 12.0,
                    faceDetected = true,
                    timestampMs = t
                )
            )
            t += 33L
            guard++
        }
        return t
    }

    private fun isCollecting(step: CalibrationFlowStep) =
        step == CalibrationFlowStep.FIT_5_POINTS || step == CalibrationFlowStep.VALIDATE_4_POINTS

    // --- C3 / A214 / B49 / B62: uncalibrated state is reported as not validated -------------------------

    @Test
    fun `uncalibrated HUD reports no accuracy, no frame rate and the uncalibrated profile name`() {
        val hud = newVm(store = null).liveHudState.value
        assertNull(hud.activeErrorRadiusPct)
        assertNull(hud.activeMaxQuadrantErrorPct)
        assertNull(hud.activeErrorMm)
        assertFalse(hud.hasActiveCalibration)
        assertEquals(UNCALIBRATED_PROFILE_NAME, hud.activeProfileName)
        assertEquals("Uncalibrated (default matrix)", hud.activeProfileName)
        assertEquals(0, hud.analyzerFps)
    }

    // --- C1 / A254 / B68: camera frames hop to main and are dropped while the simulator is on ------------

    @Test
    fun `submitCameraFrame processes frames from a background thread on main and drops them in simulator mode`() {
        val vm = newVm(store = null)
        val frame = face(1_000_000L, gx = 0.05)
        val worker = Thread { vm.submitCameraFrame(frame) }
        worker.start()
        worker.join()
        idleMain()
        assertEquals(frame, vm.liveHudState.value.rawFeature)

        vm.toggleSimulatorProxyMode(true)
        vm.submitCameraFrame(face(1_000_033L, gx = -0.07))
        idleMain()
        assertEquals("camera frames must be dropped while the simulator is on", frame, vm.liveHudState.value.rawFeature)
        vm.toggleSimulatorProxyMode(false)
    }

    // --- A3 / A286 / C6: a no-face frame during a long closure does not end it as a command blink -------

    @Test
    fun `face dropout during an eyes-closed rest does not fire a command blink`() {
        val vm = newVm(store = null)
        var t = 1_000_000L
        repeat(5) { vm.processIncomingFeatureFrame(face(t)); t += 33L }
        val closeStart = t
        while (t < closeStart + 420L) { vm.processIncomingFeatureFrame(face(t, open = 0.10f)); t += 33L }
        // One dropped detection: the frame carries the default (unmeasured) 0.92 open probability
        vm.processIncomingFeatureFrame(noFace(t)); t += 33L
        while (t < closeStart + 2000L) { vm.processIncomingFeatureFrame(face(t, open = 0.10f)); t += 33L }
        repeat(3) { vm.processIncomingFeatureFrame(face(t)); t += 33L }

        val hud = vm.liveHudState.value
        assertEquals(0, hud.intentionalBlinkCount)
        assertTrue(hud.lastBlinkLabel, hud.lastBlinkLabel.contains("Extended closure"))
    }

    // --- A255 / A284 / C6: dwell only accumulates on valid gaze -------------------------------------------

    @Test
    fun `dwell pauses without a face and still fires after real fixation`() {
        val vm = newVm(store = null)
        val fired = mutableListOf<String>()
        vm.registerTileTriggerListener { fired += it.id }
        var t = System.currentTimeMillis()
        vm.updateFocusedTile("tile_water")
        repeat(6) { vm.processIncomingFeatureFrame(face(t)); t += 66L }
        repeat(25) { vm.processIncomingFeatureFrame(noFace(t)); t += 66L }
        assertTrue("dwell must not complete while no face is detected: $fired", fired.isEmpty())

        repeat(20) { vm.processIncomingFeatureFrame(face(t)); t += 66L }
        assertEquals(listOf("tile_water"), fired)
    }

    @Test
    fun `a gap in the frame stream resets a pending dwell`() {
        val vm = newVm(store = null)
        val fired = mutableListOf<String>()
        vm.registerTileTriggerListener { fired += it.id }
        var t = System.currentTimeMillis()
        vm.updateFocusedTile("tile_yes")
        repeat(9) { vm.processIncomingFeatureFrame(face(t)); t += 66L }
        t += 10_000L // app in background, no frames
        repeat(5) { vm.processIncomingFeatureFrame(face(t)); t += 66L }
        assertTrue("a resumed stream must not fire the stale hover: $fired", fired.isEmpty())
    }

    // --- A216 / A285: one hover activates a tile once ------------------------------------------------------

    @Test
    fun `brow raise selection is not followed by a second dwell selection of the same tile`() {
        val vm = newVm(store = null)
        val fired = mutableListOf<String>()
        vm.registerTileTriggerListener { fired += it.id }
        var t = System.currentTimeMillis()
        vm.updateFocusedTile("tile_yes")
        repeat(3) { vm.processIncomingFeatureFrame(face(t)); t += 66L }
        repeat(9) { vm.processIncomingFeatureFrame(face(t, brow = 0.45f)); t += 66L }
        repeat(12) { vm.processIncomingFeatureFrame(face(t)); t += 66L }
        assertEquals(1, vm.liveHudState.value.browRaiseTriggerCount)
        assertEquals(listOf("tile_yes"), fired)
    }

    // --- A226: a raise interrupted by face loss needs a fresh hold -----------------------------------------

    @Test
    fun `brow raise does not fire instantly after a face dropout`() {
        val vm = newVm(store = null)
        vm.setTriggerMode(GazeTriggerMode.BROW_RAISE)
        var t = 1_000_000L
        repeat(4) { vm.processIncomingFeatureFrame(face(t, brow = 0.45f)); t += 66L }
        repeat(10) { vm.processIncomingFeatureFrame(noFace(t, brow = 0.45f)); t += 66L }
        vm.processIncomingFeatureFrame(face(t, brow = 0.45f)); t += 66L
        assertEquals("resumed raise must not fire without a new hold", 0, vm.liveHudState.value.browRaiseTriggerCount)

        repeat(8) { vm.processIncomingFeatureFrame(face(t, brow = 0.45f)); t += 66L }
        assertEquals(1, vm.liveHudState.value.browRaiseTriggerCount)
    }

    // --- A229: labels report whether a tile actually fired ------------------------------------------------

    @Test
    fun `command blink without a focused tile says so instead of claiming a trigger`() {
        val vm = newVm(store = null)
        var t = 1_000_000L
        repeat(4) { vm.processIncomingFeatureFrame(face(t)); t += 33L }
        val closeStart = t
        while (t < closeStart + 450L) { vm.processIncomingFeatureFrame(face(t, open = 0.10f)); t += 33L }
        repeat(2) { vm.processIncomingFeatureFrame(face(t)); t += 33L }
        val hud = vm.liveHudState.value
        assertEquals(1, hud.intentionalBlinkCount)
        assertTrue(hud.lastBlinkLabel, hud.lastBlinkLabel.contains("no tile focused"))
        assertFalse(hud.lastBlinkLabel, hud.lastBlinkLabel.contains("tile triggered"))
    }

    // --- A94 / A229: Read Summary speaks real analytics -----------------------------------------------------

    @Test
    fun `read summary tile speaks the reading summary instead of a fixed claim`() {
        val vm = newVm()
        var spoken: String? = null
        vm.registerTileTriggerListener { spoken = it.speechPhrase }
        vm.fireTileAction("tile_read_aloud", "Tap")
        assertEquals("No reading session recorded yet.", spoken)
    }

    // --- A94: Recenter Gaze really recenters ---------------------------------------------------------------

    @Test
    fun `recenter tile shifts the mapping so the fixated centre lands at the midpoint`() {
        val vm = newVm(store = null)
        vm.setGazeFilterMode(GazeFilterMode.RAW)
        var t = 1_000_000L
        val drifted = gxFor(0.60)
        repeat(3) { vm.processIncomingFeatureFrame(face(t, gx = drifted)); t += 66L }
        assertEquals(0.60f, vm.liveHudState.value.normX, 0.005f)

        vm.fireTileAction("tile_recenter", "Tap")
        repeat(25) { vm.processIncomingFeatureFrame(face(t, gx = drifted)); t += 66L }
        assertEquals(0.50f, vm.liveHudState.value.normX, 0.01f)
    }

    // --- A188 / B56: background recalibration corrects toward the tile centre, ignores taps ---------------

    @Test
    fun `background recalibration moves toward the activated tile centre and ignores taps`() {
        val vm = newVm(store = null)
        vm.setGazeFilterMode(GazeFilterMode.RAW)
        vm.updateTileCentersNorm(mapOf("tile_yes" to (0.30f to 0.50f)))
        var t = 1_000_000L
        vm.processIncomingFeatureFrame(face(t)); t += 33L
        assertEquals(0.50f, vm.liveHudState.value.normX, 1e-4f)

        vm.fireTileAction("tile_yes", "Tap")
        assertEquals(0, vm.liveHudState.value.backgroundRecalibrationCount)
        assertEquals(DEFAULT_AFFINE_MATRIX_2X6, vm.currentMatrixForTest())

        vm.fireTileAction("tile_yes", "Dwell (1000ms)")
        assertEquals(1, vm.liveHudState.value.backgroundRecalibrationCount)
        vm.processIncomingFeatureFrame(face(t)); t += 33L
        val afterOne = vm.liveHudState.value.normX
        assertTrue("prediction must move toward the tile centre (got $afterOne)", afterOne < 0.50f)

        // The cumulative correction stays within a small band of the calibrated intercept
        repeat(200) { vm.fireTileAction("tile_yes", "Dwell (1000ms)") }
        vm.processIncomingFeatureFrame(face(t))
        assertTrue(vm.liveHudState.value.normX >= 0.449f)
    }

    // --- C5 / A189 / A210: no implicit simulator, no synthesized samples ---------------------------------

    @Test
    fun `updateSimulatorGazeTarget never turns the simulator on by itself on real hardware`() {
        assumeFalse(isRunningOnEmulatorHardware())
        val vm = newVm(store = null)
        vm.updateSimulatorGazeTarget(0.2f, 0.3f)
        assertFalse(vm.liveHudState.value.isSimulatorProxyEnabled)
        assertFalse(vm.liveHudState.value.rawFeature.faceDetected)
    }

    @Test
    fun `capture without a face adds no samples and live capture does not copy one frame`() {
        assumeFalse(isRunningOnEmulatorHardware())
        val vm = newVm(store = null)
        vm.startCalibrationAndValidationWizard(includeValidation = false)
        vm.captureManualSamplesForCurrentTarget()
        assertEquals(0, vm.calibrationState.value.totalCollectedSamples)
        assertTrue(vm.statusBannerMessage.value.orEmpty().contains("No face"))

        var t = System.currentTimeMillis()
        vm.processIncomingFeatureFrame(face(t)); t += 33L
        val before = vm.calibrationState.value.totalCollectedSamples
        vm.captureManualSamplesForCurrentTarget()
        assertEquals("capture must not replicate one frame into many samples", before, vm.calibrationState.value.totalCollectedSamples)
        vm.processIncomingFeatureFrame(face(t))
        assertEquals(before + 1, vm.calibrationState.value.totalCollectedSamples)
    }

    // --- A1 / B50 / A267: quick fit is not presented as validated ----------------------------------------

    @Test
    fun `five point quick fit is labelled not validated and keeps the HUD accuracy unvalidated after save`() {
        val vm = newVm()
        vm.startCalibrationAndValidationWizard(includeValidation = false)
        driveWizard(vm, System.currentTimeMillis() + 500L) { !isCollecting(it) }
        val cal = vm.calibrationState.value
        assertEquals(CalibrationFlowStep.COMPLETED_SUMMARY, cal.step)
        assertFalse(cal.metricsAreHeldOut)
        assertTrue(cal.recommendedGridLabel, cal.recommendedGridLabel.contains("not validated"))

        vm.saveCompletedCalibrationProfile()
        val hud = vm.liveHudState.value
        assertTrue(hud.hasActiveCalibration)
        assertNull("in-sample residual must not be reported as validated accuracy", hud.activeErrorRadiusPct)
        assertNull(hud.activeMaxQuadrantErrorPct)
    }

    // --- A149: held-out validation mode survives a save; held-out metrics are reported -------------------

    @Test
    fun `five plus four calibration reports held-out metrics and keeps the validation mode after save`() {
        val vm = newVm()
        vm.startCalibrationAndValidationWizard(includeValidation = true)
        driveWizard(vm, System.currentTimeMillis() + 500L) { !isCollecting(it) }
        val cal = vm.calibrationState.value
        assertEquals(CalibrationFlowStep.COMPLETED_SUMMARY, cal.step)
        assertTrue(cal.metricsAreHeldOut)

        vm.saveCompletedCalibrationProfile()
        assertTrue(vm.calibrationState.value.includeHeldOutValidation)
        assertNotNull(vm.liveHudState.value.activeErrorRadiusPct)
    }

    // --- A148: Save without a completed wizard does nothing ------------------------------------------------

    @Test
    fun `save outside the completed summary does not persist a default profile`() {
        val store = LocalTelemetryStore(context)
        val user = freshUser()
        val vm = newVm(user, store)
        vm.saveCompletedCalibrationProfile()
        assertTrue(store.loadLocalProfiles(user).isEmpty())
        assertEquals(UNCALIBRATED_PROFILE_NAME, vm.liveHudState.value.activeProfileName)
    }

    // --- A143 / A296 / B73: cancelling restores the pre-wizard mapping -----------------------------------

    @Test
    fun `cancel during validation restores the pre-wizard matrix`() {
        val vm = newVm(store = null)
        vm.setGazeFilterMode(GazeFilterMode.RAW)
        var t = System.currentTimeMillis()
        val probeGx = 0.05
        vm.processIncomingFeatureFrame(face(t, gx = probeGx)); t += 33L
        val before = vm.liveHudState.value.normX

        vm.startCalibrationAndValidationWizard(includeValidation = true)
        t = driveWizard(vm, t + 500L, gxGain = 0.20) { it != CalibrationFlowStep.FIT_5_POINTS }
        assertEquals(CalibrationFlowStep.VALIDATE_4_POINTS, vm.calibrationState.value.step)
        vm.processIncomingFeatureFrame(face(t, gx = probeGx)); t += 33L
        assertTrue(
            "the candidate fit should differ from the default for this probe",
            kotlin.math.abs(vm.liveHudState.value.normX - before) > 0.01f
        )

        vm.cancelCalibrationWizard()
        vm.processIncomingFeatureFrame(face(t, gx = probeGx))
        assertEquals(before, vm.liveHudState.value.normX, 1e-4f)
        assertEquals(DEFAULT_AFFINE_MATRIX_2X6, vm.currentMatrixForTest())
    }

    // --- B47: simulated calibrations are labelled ------------------------------------------------------------

    @Test
    fun `simulator calibration is saved labelled as simulated`() {
        val store = LocalTelemetryStore(context)
        val user = freshUser()
        val vm = newVm(user, store)
        vm.toggleSimulatorProxyMode(true)
        vm.startCalibrationAndValidationWizard(includeValidation = false)
        var guard = 0
        while (isCollecting(vm.calibrationState.value.step) && guard < 40) {
            val target = GazeCalibrationMath.CALIBRATION_AND_VALIDATION_TARGETS[vm.calibrationState.value.currentTargetIndex]
            vm.updateSimulatorGazeTarget(target.normX, target.normY)
            vm.captureManualSamplesForCurrentTarget()
            guard++
        }
        assertEquals(CalibrationFlowStep.COMPLETED_SUMMARY, vm.calibrationState.value.step)
        vm.saveCompletedCalibrationProfile()
        assertTrue(vm.liveHudState.value.activeProfileName.startsWith("[SIMULATED] "))
        assertTrue(store.loadLocalProfiles(user).first().name.startsWith("[SIMULATED] "))
        vm.toggleSimulatorProxyMode(false)
    }

    // --- A202: deleting the active profile unloads it ----------------------------------------------------------

    @Test
    fun `deleting the active profile resets the engine to the uncalibrated default`() {
        val store = LocalTelemetryStore(context)
        val user = freshUser()
        val custom = DEFAULT_AFFINE_MATRIX_2X6.toMutableList().also { it[0] = 0.42 }
        store.saveLocalProfile(
            user,
            CalibrationProfile(
                id = "local_cal_a",
                userId = user,
                name = "Desk A",
                matrixCoeffs = custom,
                meanErrorPct = 8.0,
                maxErrorPct = 11.0,
                meanErrorMm = 9.0
            )
        )
        val vm = newVm(user, store)
        assertEquals("Desk A", vm.liveHudState.value.activeProfileName)
        assertEquals(custom, vm.currentMatrixForTest())

        vm.deleteCalibrationProfile("local_cal_a")
        val hud = vm.liveHudState.value
        assertEquals(UNCALIBRATED_PROFILE_NAME, hud.activeProfileName)
        assertNull(hud.activeErrorRadiusPct)
        assertFalse(hud.hasActiveCalibration)
        assertEquals(DEFAULT_AFFINE_MATRIX_2X6, vm.currentMatrixForTest())
    }

    // --- A89 / A188(4): profile flow re-emissions do not re-apply the active profile -----------------------

    @Test
    fun `profile list re-emission keeps runtime drift corrections`() {
        val store = LocalTelemetryStore(context)
        val user = freshUser()
        store.saveLocalProfile(user, CalibrationProfile(id = "local_cal_c", userId = user, name = "Other"))
        store.saveLocalProfile(user, CalibrationProfile(id = "local_cal_a", userId = user, name = "Desk A"))
        val vm = newVm(user, store)
        assertEquals("Desk A", vm.liveHudState.value.activeProfileName)
        val scope = CoroutineScope(Dispatchers.Main.immediate)
        scope.launch { vm.calibrationProfilesState.collect { } }
        idleMain()

        vm.setGazeFilterMode(GazeFilterMode.RAW)
        vm.updateTileCentersNorm(mapOf("tile_yes" to (0.30f to 0.50f)))
        var t = 1_000_000L
        vm.processIncomingFeatureFrame(face(t)); t += 33L
        vm.fireTileAction("tile_yes", "Dwell (1000ms)")
        val nudged = vm.currentMatrixForTest()
        assertTrue(nudged[0] < 0.50)

        vm.deleteCalibrationProfile("local_cal_c") // emits a new local profile list
        idleMain()
        assertEquals("Desk A", vm.liveHudState.value.activeProfileName)
        assertEquals(nudged, vm.currentMatrixForTest())
        scope.cancel()
    }

    // --- B59 / A298 / A100: section dwell only for real section hits ---------------------------------------

    @Test
    fun `section dwell is not credited off-passage or after the layout detaches`() {
        val vm = newVm(store = null)
        vm.startAttentionRecording()
        var t = 1_000_000L
        vm.updateActiveReadingSectionFromLayout(null)
        repeat(20) { vm.processIncomingFeatureFrame(face(t, gy = -0.06)); t += 66L }
        assertEquals(0L, vm.attentionState.value.sectionDwellMsMap.values.sum())

        vm.updateActiveReadingSectionFromLayout("sec_2")
        repeat(10) { vm.processIncomingFeatureFrame(face(t)); t += 66L }
        val credited = vm.attentionState.value.sectionDwellMsMap["sec_2"] ?: 0L
        assertTrue(credited > 0L)

        vm.detachReadingSectionLayout()
        repeat(10) { vm.processIncomingFeatureFrame(face(t)); t += 66L }
        vm.updateActiveReadingSectionFromLayout(EyeTrackingViewModel.OFF_PASSAGE_SECTION_ID)
        repeat(10) { vm.processIncomingFeatureFrame(face(t)); t += 66L }
        assertEquals(credited, vm.attentionState.value.sectionDwellMsMap.values.sum())
        assertNull(vm.attentionState.value.activeSectionId)
        vm.stopAndSaveAttentionSession()
    }

    // --- A201 / B57 / B58: empty sessions carry no invented values -------------------------------------------

    @Test
    fun `session without gaze samples is saved with zero zones and zero WPM`() {
        val vm = newVm()
        vm.startAttentionRecording()
        vm.stopAndSaveAttentionSession()
        val s = requireNotNull(vm.attentionState.value.selectedReplaySession)
        assertEquals(0, s.topZonePercentage)
        assertEquals(0, s.middleZonePercentage)
        assertEquals(0, s.bottomZonePercentage)
        assertEquals(0, s.effectiveWordsPerMinute)
        assertEquals(0, s.fixationClusterCount)
    }

    @Test
    fun `passage word counts match the passage text`() {
        for (sec in newVm(store = null).readingPassageSections) {
            assertEquals(sec.sectionId, sec.body.trim().split(Regex("\\s+")).size, sec.wordCount)
        }
    }

    // --- A265: opening a replay never discards a running recording -----------------------------------------

    @Test
    fun `selecting a replay while recording keeps the recording`() {
        val vm = newVm()
        vm.startAttentionRecording()
        vm.selectSessionForReplay(TrackingSession(id = "local_sess_old", title = "Old session"))
        assertTrue(vm.attentionState.value.isRecording)
        assertNull(vm.attentionState.value.selectedReplaySession)
        assertTrue(vm.statusBannerMessage.value.orEmpty().contains("Stop & Save"))
        vm.stopAndSaveAttentionSession()
    }

    // --- A85: cloud consent is per account ------------------------------------------------------------------------

    @Test
    fun `cloud sync opt-in of one account is not inherited by another`() {
        val store = LocalTelemetryStore(context)
        val userA = freshUser()
        val userB = freshUser()
        val vmA = newVm(userA, store)
        vmA.toggleCloudSync(true)
        assertFalse(newVm(userB, store).liveHudState.value.cloudSyncEnabled)
        assertTrue(newVm(userA, store).liveHudState.value.cloudSyncEnabled)
        vmA.toggleCloudSync(false)
    }

    // --- B66: resolution steps back up when the rate recovers ----------------------------------------------------

    @Test
    fun `analysis resolution steps back up after a sustained high frame rate`() {
        val vm = newVm(store = null)
        var t = 1_000_000L
        repeat(40) { vm.processIncomingFeatureFrame(face(t)); t += 100L } // 10 FPS for 4 s
        assertEquals(AnalysisResolutionTier.TIER_MED_960, vm.liveHudState.value.resolutionTier)
        assertFalse(vm.statusBannerMessage.value.orEmpty().contains("Tensor"))

        repeat(18_000 / 33) { vm.processIncomingFeatureFrame(face(t)); t += 33L } // 30 FPS for 18 s
        assertEquals(AnalysisResolutionTier.TIER_HIGH_1280, vm.liveHudState.value.resolutionTier)
    }

    // --- A95 / A292: the 1€ screen filter adapts to saccades ----------------------------------------------------

    @Test
    fun `screen gaze filter reaches 90 percent of a saccade within four frames at 15 FPS`() {
        val vm = newVm(store = null)
        var t = 1_000_000L
        repeat(15) { vm.processIncomingFeatureFrame(face(t, gx = gxFor(0.2))); t += 66L }
        var framesTo90 = -1
        for (i in 1..30) {
            vm.processIncomingFeatureFrame(face(t, gx = gxFor(0.8))); t += 66L
            if (vm.liveHudState.value.normX >= 0.74f) { framesTo90 = i; break }
        }
        assertTrue("frames to 90%: $framesTo90", framesTo90 in 1..4)
    }

    // --- A48 / A276 / A306: benchmark off the UI thread, banner from the scorecard ----------------------------

    @Test
    fun `benchmark runs off the main thread and the banner reflects the scorecard`() {
        val vm = newVm(store = null)
        vm.runBenchmarkSuite()
        assertTrue("running state must be visible while the benchmark runs", vm.liveHudState.value.isBenchmarkRunning)
        val deadline = System.currentTimeMillis() + 120_000L
        while (vm.liveHudState.value.isBenchmarkRunning && System.currentTimeMillis() < deadline) {
            idleMain()
            Thread.sleep(20L)
        }
        idleMain()
        assertFalse(vm.liveHudState.value.isBenchmarkRunning)
        assertNotNull(vm.liveHudState.value.lastBenchmarkReport)
        val banner = vm.statusBannerMessage.value.orEmpty()
        assertFalse(banner, banner.contains("100% PASS"))
        assertTrue(banner, banner.contains("gated scenarios passed"))
    }
}
