package com.example.enrollment

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.data.model.CalibrationProfile
import com.example.data.model.EnrollmentBaseline
import com.example.data.model.GazeFeatureVector
import com.example.data.repository.EyeTrackingRepository
import com.example.data.repository.LocalTelemetryStore
import com.example.domain.EnrollmentFailure
import com.example.domain.EnrollmentGuidance
import com.example.domain.EnrollmentPhase
import com.example.domain.EnrollmentScanState
import com.example.domain.EnrollmentWarning
import com.example.ui.viewmodel.CalibrationFlowStep
import com.example.ui.viewmodel.EyeTrackingViewModel
import com.example.ui.viewmodel.isRunningOnEmulatorHardware
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
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

/**
 * ViewModel side of the per-user face & eye enrollment scan (contract C3):
 * - the scan is refused (banner only) in simulator mode and while the calibration wizard collects,
 * - a complete synthetic scan fed through processIncomingFeatureFrame persists the baseline, applies the
 *   personalized blink thresholds (observable through blink classification) and brow ratios and stamps
 *   enrolledAtMs; a new ViewModel for the same account restores all of it,
 * - starting the wizard cancels a running scan; no tile fires during a scan while the blink counters keep
 *   working; simulated frames fail the scan without touching the detectors,
 * - clearing the enrollment goes back to the defaults, or to the active profile's blink settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VmEnrollmentTest {

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

    private fun freshUser() = "vm_enr_" + UUID.randomUUID().toString().replace("-", "").take(12)

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

    /** A measured face frame: centred face box, both eyes measured, no lighting warnings (the defaults). */
    private fun face(ts: Long, open: Float = 0.92f, brow: Float = 0.34f) = GazeFeatureVector(
        leftEyeOpenProb = open,
        rightEyeOpenProb = open,
        browToEyeRatio = brow,
        faceDetected = true,
        timestampMs = ts
    )

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    private fun isActivePhase(phase: EnrollmentPhase) =
        phase != EnrollmentPhase.IDLE && phase != EnrollmentPhase.COMPLETE && phase != EnrollmentPhase.FAILED

    /**
     * Feeds [count] open-eye face frames at 33 ms and returns the next frame time.
     */
    private fun openFrames(vm: EyeTrackingViewModel, from: Long, count: Int, brow: Float = 0.34f): Long {
        var t = from
        repeat(count) { vm.processIncomingFeatureFrame(face(t, brow = brow)); t += 33L }
        return t
    }

    /**
     * One eyelid closure to [level] lasting [durationMs] (frames at 33 ms), followed by two open frames so the
     * detector classifies it. Returns the next frame time.
     */
    private fun closure(vm: EyeTrackingViewModel, from: Long, durationMs: Long, level: Float): Long {
        var t = from
        val start = t
        while (t < start + durationMs) { vm.processIncomingFeatureFrame(face(t, open = level)); t += 33L }
        repeat(2) { vm.processIncomingFeatureFrame(face(t)); t += 33L }
        return t
    }

    /**
     * Drives a running scan by following the engine's guidance with synthetic frames at 33 ms: open eyes
     * (with two scripted ~130 ms natural blinks while the eyes-open levels are collected), eyes closed to
     * [closedLevel] when asked, reopened when asked, brows raised to [raisedBrow] when asked. Stops when
     * [stopWhen] holds or the scan is no longer active. Returns the next frame time.
     */
    private fun driveScan(
        vm: EyeTrackingViewModel,
        from: Long,
        closedLevel: Float = 0.08f,
        raisedBrow: Float = 0.44f,
        stopWhen: (EnrollmentScanState) -> Boolean = { false }
    ): Long {
        var t = from
        var eyesOpenFrame = 0
        var guard = 0
        while (guard < 6000) {
            val state = vm.enrollmentState.value
            if (!isActivePhase(state.phase) || stopWhen(state)) break
            val frame = when (state.guidance) {
                EnrollmentGuidance.CLOSE_EYES, EnrollmentGuidance.KEEP_EYES_CLOSED -> face(t, open = closedLevel)
                EnrollmentGuidance.OPEN_EYES -> face(t)
                EnrollmentGuidance.RAISE_BROWS -> face(t, brow = raisedBrow)
                else -> if (state.phase == EnrollmentPhase.EYES_OPEN) {
                    val idx = eyesOpenFrame++
                    val blinking = idx in 15..18 || idx in 45..48
                    face(t, open = if (blinking) 0.10f else 0.92f)
                } else {
                    face(t)
                }
            }
            vm.processIncomingFeatureFrame(frame)
            t += 33L
            guard++
        }
        return t
    }

    private fun seededBaseline(capturedAtMs: Long = 1_762_000_000_000L) = EnrollmentBaseline(
        version = 1,
        capturedAtMs = capturedAtMs,
        deviceModel = "Test device",
        openEyeLevel = 0.90f,
        closedEyeLevel = 0.10f,
        leftOpenLevel = 0.91f,
        rightOpenLevel = 0.89f,
        baselineEyeDistancePx = 150.0,
        neutralBrowRatio = 0.36f,
        raisedBrowRatio = 0.45f,
        naturalBlinkCount = 4,
        naturalBlinkMedianMs = 180L,
        naturalBlinkP95Ms = 320L,
        closeBlinkThreshold = 0.30f,
        openBlinkThreshold = 0.55f,
        intentionalBlinkMinMs = 400L,
        meanAmbientLuma = 140,
        meanEyeConfidence = 0.88f,
        frameCount = 300,
        warnings = emptyList()
    )

    // --- start is refused where frames are not measurements, or while the wizard owns the overlay --------

    @Test
    fun `start is refused in simulator mode with a banner and nothing else changes`() {
        val vm = newVm(store = null)
        vm.toggleSimulatorProxyMode(true)
        vm.dismissStatusBanner()
        vm.startEnrollmentScan()
        assertEquals(EnrollmentPhase.IDLE, vm.enrollmentState.value.phase)
        val banner = vm.statusBannerMessage.value
        assertNotNull("a refusal must be explained", banner)
        assertTrue(banner!!, banner.contains("live front camera"))
        assertNull(vm.enrollmentBaseline.value)
        assertNull(vm.liveHudState.value.enrolledAtMs)
        vm.toggleSimulatorProxyMode(false)
    }

    @Test
    fun `start is refused while the calibration wizard is collecting and the wizard keeps running`() {
        assumeFalse(isRunningOnEmulatorHardware())
        val vm = newVm(store = null)
        vm.startCalibrationAndValidationWizard(includeValidation = false)
        assertEquals(CalibrationFlowStep.FIT_5_POINTS, vm.calibrationState.value.step)
        vm.dismissStatusBanner()
        vm.startEnrollmentScan()
        assertEquals(EnrollmentPhase.IDLE, vm.enrollmentState.value.phase)
        assertEquals(CalibrationFlowStep.FIT_5_POINTS, vm.calibrationState.value.step)
        val banner = vm.statusBannerMessage.value
        assertNotNull(banner)
        assertTrue(banner!!, banner.contains("calibration"))
        vm.cancelCalibrationWizard()
    }

    // --- a complete synthetic scan personalizes the detectors and is persisted ---------------------------

    @Test
    fun `a complete scan persists the baseline, applies thresholds and brow ratios and restores them in a new ViewModel`() {
        assumeFalse(isRunningOnEmulatorHardware())
        val store = LocalTelemetryStore(context)
        val user = freshUser()
        val vm = newVm(user, store)
        assertNull(vm.enrollmentBaseline.value)
        assertNull(vm.liveHudState.value.enrolledAtMs)
        assertEquals(EnrollmentPhase.IDLE, vm.enrollmentState.value.phase)

        // Control: with the default 0.45 close threshold a 450 ms dip to 0.40 is a command blink
        var t = openFrames(vm, 1_000_000L, 4)
        t = closure(vm, t, 450L, 0.40f)
        assertEquals("default thresholds must still be in force before the scan", 1, vm.liveHudState.value.intentionalBlinkCount)

        vm.startEnrollmentScan()
        assertEquals(EnrollmentPhase.POSITIONING, vm.enrollmentState.value.phase)
        t = driveScan(vm, t)

        val state = vm.enrollmentState.value
        assertEquals("scan should finish: guidance=${state.guidance} failure=${state.failure}", EnrollmentPhase.COMPLETE, state.phase)
        val baseline = vm.enrollmentBaseline.value
        assertNotNull("COMPLETE publishes the baseline", baseline)
        baseline!!
        // The published baseline is the engine's result (the ViewModel only fills in a blank device model)
        assertEquals(baseline.copy(deviceModel = ""), state.result!!.copy(deviceModel = ""))
        assertEquals("the baseline is saved to the on-device vault", baseline, store.loadEnrollmentBaseline(user))

        // Derived numbers: open 0.92 / closed 0.08 -> close 0.374 / open 0.626; two ~130 ms natural blinks
        assertEquals(0.92f, baseline.openEyeLevel, 0.01f)
        assertEquals(0.08f, baseline.closedEyeLevel, 0.01f)
        assertTrue("close ${baseline.closeBlinkThreshold}", baseline.closeBlinkThreshold in 0.33f..0.42f)
        assertTrue("open ${baseline.openBlinkThreshold}", baseline.openBlinkThreshold in 0.58f..0.68f)
        assertTrue(baseline.openBlinkThreshold > baseline.closeBlinkThreshold + 0.05f)
        assertEquals(2, baseline.naturalBlinkCount)
        assertEquals(350L, baseline.intentionalBlinkMinMs)
        assertEquals(0.34f, baseline.neutralBrowRatio, 0.005f)
        assertNotNull(baseline.raisedBrowRatio)
        assertEquals(0.44f, baseline.raisedBrowRatio!!, 0.01f)
        assertFalse(baseline.warnings.contains(EnrollmentWarning.FEW_NATURAL_BLINKS.name))
        assertFalse(baseline.warnings.contains(EnrollmentWarning.EYE_RANGE_SMALL_DEFAULTS_USED.name))
        assertTrue(baseline.capturedAtMs > 0L)

        val hud = vm.liveHudState.value
        assertEquals(baseline.capturedAtMs, hud.enrolledAtMs)
        assertEquals(0.34f, hud.neutralBrowRatio, 0.005f)
        assertEquals(0.44f, hud.raisedBrowRatio, 0.01f)
        val banner = vm.statusBannerMessage.value
        assertNotNull(banner)
        assertTrue(banner!!, banner.startsWith("Enrollment complete:"))

        // The brow ratios are mirrored into the saved interaction settings (like a manual brow calibration)
        val settings = store.loadInteractionSettings(user)
        assertNotNull(settings)
        assertEquals(0.34f, settings!!.neutralBrowRatio, 0.005f)
        assertEquals(0.44f, settings.raisedBrowRatio, 0.01f)

        // Observable through blink classification: the same 0.40 dip no longer crosses the 0.374 close
        // threshold, while a real closure still does
        t = openFrames(vm, t, 4)
        val cmdBefore = vm.liveHudState.value.intentionalBlinkCount
        t = closure(vm, t, 450L, 0.40f)
        assertEquals("a 0.40 dip is above the enrolled close threshold", cmdBefore, vm.liveHudState.value.intentionalBlinkCount)
        t = closure(vm, t, 450L, 0.10f)
        assertEquals(cmdBefore + 1, vm.liveHudState.value.intentionalBlinkCount)

        // A new ViewModel for the same account restores the enrollment
        val vm2 = newVm(user, store)
        assertEquals(baseline, vm2.enrollmentBaseline.value)
        val hud2 = vm2.liveHudState.value
        assertEquals(baseline.capturedAtMs, hud2.enrolledAtMs)
        assertEquals(0.34f, hud2.neutralBrowRatio, 0.005f)
        assertEquals(0.44f, hud2.raisedBrowRatio, 0.01f)
        var t2 = openFrames(vm2, 2_000_000L, 4)
        t2 = closure(vm2, t2, 450L, 0.40f)
        assertEquals("restored thresholds: a 0.40 dip is not a blink", 0, vm2.liveHudState.value.intentionalBlinkCount)
        closure(vm2, t2, 450L, 0.10f)
        assertEquals(1, vm2.liveHudState.value.intentionalBlinkCount)

        // Another account on the same device is not enrolled
        val other = newVm(freshUser(), store)
        assertNull(other.enrollmentBaseline.value)
        assertNull(other.liveHudState.value.enrolledAtMs)
    }

    @Test
    fun `a stored baseline is applied at launch through the range-checked blink path`() {
        val store = LocalTelemetryStore(context)
        val user = freshUser()
        val seeded = seededBaseline()
        store.saveEnrollmentBaseline(user, seeded)
        val vm = newVm(user, store)
        assertEquals(seeded, vm.enrollmentBaseline.value)
        val hud = vm.liveHudState.value
        assertEquals(seeded.capturedAtMs, hud.enrolledAtMs)
        assertEquals(0.36f, hud.neutralBrowRatio, 1e-6f)
        assertEquals(0.45f, hud.raisedBrowRatio, 1e-6f)

        // Command minimum 400 ms: a 396 ms closure is a natural blink here, a command blink by default (350)
        var t = openFrames(vm, 1_000_000L, 4)
        t = closure(vm, t, 370L, 0.10f)
        var h = vm.liveHudState.value
        assertEquals(0, h.intentionalBlinkCount)
        assertEquals(1, h.naturalBlinkCount)
        // Close threshold 0.30: a dip to 0.35 is not a closure (it is with the default 0.45)
        t = closure(vm, t, 450L, 0.35f)
        h = vm.liveHudState.value
        assertEquals(0, h.intentionalBlinkCount)
        // A 450 ms closure to 0.10 is a command blink
        closure(vm, t, 450L, 0.10f)
        assertEquals(1, vm.liveHudState.value.intentionalBlinkCount)
    }

    @Test
    fun `an inconsistent stored baseline falls back to the default thresholds but keeps its brow ratios`() {
        val store = LocalTelemetryStore(context)
        val user = freshUser()
        // open below close + gap: not a usable Schmitt pair; command minimum above the command maximum
        store.saveEnrollmentBaseline(
            user,
            seededBaseline().copy(closeBlinkThreshold = 0.60f, openBlinkThreshold = 0.62f, intentionalBlinkMinMs = 900L)
        )
        val vm = newVm(user, store)
        assertEquals(0.36f, vm.liveHudState.value.neutralBrowRatio, 1e-6f)
        var t = openFrames(vm, 1_000_000L, 4)
        // Default close 0.45: a dip to 0.50 is not a closure (it would be with the stored 0.60)
        t = closure(vm, t, 450L, 0.50f)
        assertEquals(0, vm.liveHudState.value.intentionalBlinkCount)
        // Default minimum 350: a 450 ms closure is a command blink (a 900 ms minimum would never allow one)
        closure(vm, t, 450L, 0.10f)
        assertEquals(1, vm.liveHudState.value.intentionalBlinkCount)
    }

    // --- the wizard takes over, tiles stay quiet, simulated frames are refused ---------------------------

    @Test
    fun `starting the calibration wizard cancels a running scan`() {
        assumeFalse(isRunningOnEmulatorHardware())
        val vm = newVm(store = null)
        val t = openFrames(vm, 1_000_000L, 3)
        vm.startEnrollmentScan()
        openFrames(vm, t, 5)
        assertTrue(isActivePhase(vm.enrollmentState.value.phase))

        vm.startCalibrationAndValidationWizard(includeValidation = false)
        val state = vm.enrollmentState.value
        assertEquals(EnrollmentPhase.FAILED, state.phase)
        assertEquals(EnrollmentFailure.CANCELLED, state.failure)
        assertNull(state.result)
        assertEquals(CalibrationFlowStep.FIT_5_POINTS, vm.calibrationState.value.step)
        val banner = vm.statusBannerMessage.value
        assertNotNull(banner)
        assertTrue(banner!!, banner.contains("cancelled"))
        assertNull("a cancelled scan enrolls nothing", vm.enrollmentBaseline.value)
        vm.cancelCalibrationWizard()
    }

    @Test
    fun `no tile fires during a scan while the blink counters keep working, and dwell works again after cancel`() {
        assumeFalse(isRunningOnEmulatorHardware())
        val vm = newVm(store = null)
        val fired = mutableListOf<String>()
        vm.registerTileTriggerListener { fired += it.id }
        vm.updateFocusedTile("tile_yes")
        var t = openFrames(vm, 1_000_000L, 2)
        vm.startEnrollmentScan()
        assertTrue(isActivePhase(vm.enrollmentState.value.phase))

        // 2 s of steady fixation: twice the 1,000 ms dwell, no activation
        t = openFrames(vm, t, 60)
        assertTrue("dwell must not fire during the scan: $fired", fired.isEmpty())
        assertEquals(0f, vm.liveHudState.value.focusedTileDwellProgress, 1e-6f)

        // A 450 ms command blink is counted but does not activate the tile
        t = closure(vm, t, 450L, 0.10f)
        var hud = vm.liveHudState.value
        assertEquals(1, hud.intentionalBlinkCount)
        assertTrue(hud.lastBlinkLabel, hud.lastBlinkLabel.contains("ignored during the enrollment scan"))
        assertTrue("a command blink must not fire a tile during the scan: $fired", fired.isEmpty())

        // A held brow raise is counted but does not activate the tile either
        t = openFrames(vm, t, 15, brow = 0.45f)
        hud = vm.liveHudState.value
        assertEquals(1, hud.browRaiseTriggerCount)
        assertTrue(hud.lastBlinkLabel, hud.lastBlinkLabel.contains("ignored during the enrollment scan"))
        assertTrue("a brow raise must not fire a tile during the scan: $fired", fired.isEmpty())
        assertTrue(isActivePhase(vm.enrollmentState.value.phase))

        vm.cancelEnrollmentScan()
        assertEquals(EnrollmentPhase.FAILED, vm.enrollmentState.value.phase)
        assertEquals(EnrollmentFailure.CANCELLED, vm.enrollmentState.value.failure)
        val banner = vm.statusBannerMessage.value
        assertNotNull(banner)
        assertTrue(banner!!, banner.contains("cancelled"))

        // Gaze activation is back: a fresh fixation completes the dwell
        openFrames(vm, t, 40)
        assertEquals(listOf("tile_yes"), fired)
    }

    @Test
    fun `a simulated frame fails the scan and leaves the detectors unchanged`() {
        assumeFalse(isRunningOnEmulatorHardware())
        val vm = newVm(store = null)
        var t = openFrames(vm, 1_000_000L, 2)
        vm.startEnrollmentScan()
        t = openFrames(vm, t, 3)
        vm.processIncomingFeatureFrame(face(t).copy(isSimulated = true))
        val state = vm.enrollmentState.value
        assertEquals(EnrollmentPhase.FAILED, state.phase)
        assertEquals(EnrollmentFailure.SIMULATED_FRAMES, state.failure)
        val banner = vm.statusBannerMessage.value
        assertNotNull(banner)
        assertTrue(banner!!, banner.lowercase().contains("simulated"))
        assertNull(vm.enrollmentBaseline.value)
        val hud = vm.liveHudState.value
        assertNull(hud.enrolledAtMs)
        assertEquals(0.34f, hud.neutralBrowRatio, 1e-6f)
        assertEquals(0.42f, hud.raisedBrowRatio, 1e-6f)
    }

    @Test
    fun `frames processed without a scan leave the enrollment idle`() {
        val vm = newVm(store = null)
        var t = openFrames(vm, 1_000_000L, 10)
        t = closure(vm, t, 450L, 0.10f)
        vm.processIncomingFeatureFrame(GazeFeatureVector(faceDetected = false, timestampMs = t))
        assertEquals(EnrollmentScanState(), vm.enrollmentState.value)
        assertNull(vm.enrollmentBaseline.value)
        assertNull(vm.liveHudState.value.enrolledAtMs)
        assertEquals(1, vm.liveHudState.value.intentionalBlinkCount)
    }

    // --- brow step skipped, cancel bookkeeping ----------------------------------------------------------

    @Test
    fun `skipping the brow step completes the scan with BROW_SKIPPED and keeps the raised brow default`() {
        assumeFalse(isRunningOnEmulatorHardware())
        val store = LocalTelemetryStore(context)
        val user = freshUser()
        val vm = newVm(user, store)
        val t = openFrames(vm, 1_000_000L, 2)
        vm.startEnrollmentScan()
        driveScan(vm, t) { it.phase == EnrollmentPhase.BROW_RAISE }
        assertEquals(EnrollmentPhase.BROW_RAISE, vm.enrollmentState.value.phase)

        vm.skipEnrollmentBrowStep()
        val state = vm.enrollmentState.value
        assertEquals(EnrollmentPhase.COMPLETE, state.phase)
        val baseline = vm.enrollmentBaseline.value
        assertNotNull(baseline)
        assertNull(baseline!!.raisedBrowRatio)
        assertTrue(baseline.warnings.contains(EnrollmentWarning.BROW_SKIPPED.name))
        assertEquals(baseline, store.loadEnrollmentBaseline(user))
        val hud = vm.liveHudState.value
        assertEquals(0.34f, hud.neutralBrowRatio, 0.005f)
        // raised = max(current raised 0.42, neutral + separation 0.375)
        assertEquals(0.42f, hud.raisedBrowRatio, 1e-6f)
        assertEquals(baseline.capturedAtMs, hud.enrolledAtMs)
        val banner = vm.statusBannerMessage.value
        assertNotNull(banner)
        assertTrue(banner!!, banner.contains("brow step skipped"))

        // Outside the brow step the call is a no-op
        vm.skipEnrollmentBrowStep()
        assertEquals(state, vm.enrollmentState.value)
    }

    @Test
    fun `cancel without a running scan does nothing`() {
        val vm = newVm(store = null)
        vm.cancelEnrollmentScan()
        assertEquals(EnrollmentPhase.IDLE, vm.enrollmentState.value.phase)
        assertNull(vm.statusBannerMessage.value)
    }

    // --- clearing the enrollment ---------------------------------------------------------------------------

    @Test
    fun `clearing the enrollment removes it from the vault and restores the default detectors`() {
        val store = LocalTelemetryStore(context)
        val user = freshUser()
        store.saveEnrollmentBaseline(user, seededBaseline())
        val vm = newVm(user, store)
        assertNotNull(vm.enrollmentBaseline.value)
        assertEquals(0.45f, vm.liveHudState.value.raisedBrowRatio, 1e-6f)

        vm.clearEnrollmentBaseline()
        assertNull(store.loadEnrollmentBaseline(user))
        assertNull(vm.enrollmentBaseline.value)
        assertEquals(EnrollmentPhase.IDLE, vm.enrollmentState.value.phase)
        val hud = vm.liveHudState.value
        assertNull(hud.enrolledAtMs)
        assertEquals(0.34f, hud.neutralBrowRatio, 1e-6f)
        assertEquals(0.42f, hud.raisedBrowRatio, 1e-6f)
        val settings = store.loadInteractionSettings(user)
        assertNotNull(settings)
        assertEquals(0.34f, settings!!.neutralBrowRatio, 1e-6f)
        assertEquals(0.42f, settings.raisedBrowRatio, 1e-6f)
        val banner = vm.statusBannerMessage.value
        assertNotNull(banner)
        assertTrue(banner!!, banner.startsWith("Enrollment cleared"))

        // Default 350 ms minimum again: the 396 ms closure that was natural under the 400 ms baseline is a command blink
        val t = openFrames(vm, 1_000_000L, 4)
        closure(vm, t, 370L, 0.10f)
        assertEquals(1, vm.liveHudState.value.intentionalBlinkCount)

        // A new ViewModel starts unenrolled
        val vm2 = newVm(user, store)
        assertNull(vm2.enrollmentBaseline.value)
        assertNull(vm2.liveHudState.value.enrolledAtMs)
    }

    @Test
    fun `clearing the enrollment keeps the active profile's blink settings`() {
        val store = LocalTelemetryStore(context)
        val user = freshUser()
        store.saveLocalProfile(
            user,
            CalibrationProfile(
                id = "local_cal_enr",
                userId = user,
                name = "Desk profile",
                closeBlinkThreshold = 0.25,
                openBlinkThreshold = 0.55,
                intentionalBlinkMinMs = 450L
            )
        )
        store.saveEnrollmentBaseline(user, seededBaseline())
        val vm = newVm(user, store)
        assertEquals("local_cal_enr", vm.liveHudState.value.activeProfileId)
        assertNotNull(vm.enrollmentBaseline.value)

        vm.clearEnrollmentBaseline()
        assertNull(vm.enrollmentBaseline.value)
        assertEquals("local_cal_enr", vm.liveHudState.value.activeProfileId)
        val banner = vm.statusBannerMessage.value
        assertNotNull(banner)
        assertTrue(banner!!, banner.contains("Desk profile"))

        // Profile minimum 450 ms: a 396 ms closure stays a natural blink (it would be a command blink at 350)
        var t = openFrames(vm, 1_000_000L, 4)
        t = closure(vm, t, 370L, 0.10f)
        var hud = vm.liveHudState.value
        assertEquals(0, hud.intentionalBlinkCount)
        assertEquals(1, hud.naturalBlinkCount)
        // Profile close 0.25: a dip to 0.30 is not a closure (it would be with the default 0.45)
        closure(vm, t, 500L, 0.30f)
        hud = vm.liveHudState.value
        assertEquals(0, hud.intentionalBlinkCount)
        assertEquals(1, hud.naturalBlinkCount)
    }

    @Test
    fun `an active profile's stored blink settings are layered over the enrollment at launch`() {
        val store = LocalTelemetryStore(context)
        val user = freshUser()
        store.saveEnrollmentBaseline(user, seededBaseline()) // minimum 400 ms
        store.saveLocalProfile(
            user,
            CalibrationProfile(id = "local_cal_over", userId = user, name = "Over", intentionalBlinkMinMs = 500L)
        )
        val vm = newVm(user, store)
        assertNotNull(vm.enrollmentBaseline.value)
        assertEquals(0.36f, vm.liveHudState.value.neutralBrowRatio, 1e-6f)
        // 462 ms closure: a command blink under the baseline's 400 ms, natural under the profile's 500 ms
        val t = openFrames(vm, 1_000_000L, 4)
        closure(vm, t, 440L, 0.10f)
        val hud = vm.liveHudState.value
        assertEquals(0, hud.intentionalBlinkCount)
        assertEquals(1, hud.naturalBlinkCount)
        idleMain()
    }
}
