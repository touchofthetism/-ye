package com.example.optimization

import android.content.Context
import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.data.model.AnalysisResolutionTier
import com.example.data.model.BlinkType
import com.example.data.model.CalibrationProfile
import com.example.data.model.DEFAULT_AFFINE_MATRIX_2X6
import com.example.data.model.FixationClusterPoint
import com.example.data.model.GazeFeatureVector
import com.example.data.model.GazeFilterMode
import com.example.data.model.GazeOverlayVisualMode
import com.example.data.model.GazePostureProfile
import com.example.data.model.GazeTriggerMode
import com.example.data.model.LiveGazeTrailPoint
import com.example.data.model.TrackingSession
import com.example.data.repository.EyeTrackingRepository
import com.example.data.repository.LocalTelemetryStore
import com.example.domain.BlinkHysteresisDetector
import com.example.domain.BrowRaiseHysteresisDetector
import com.example.domain.GazeCalibrationMath
import com.example.domain.GazeKalmanFilter2D
import com.example.domain.OneEuroFilter2D
import com.example.ui.viewmodel.EyeTrackingViewModel
import com.example.ui.viewmodel.LiveGazeHudState
import com.example.ui.viewmodel.UiDataState
import com.example.ui.viewmodel.advanceLiveGazeTrail
import com.example.ui.viewmodel.appendFpsSample
import com.example.ui.viewmodel.averageOfLastFpsSamples
import com.example.ui.viewmodel.isRunningOnEmulatorHardware
import com.example.ui.viewmodel.liveGazeTrailDecayFactor
import com.example.ui.viewmodel.resolutionStepCauseLabel
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.Locale
import java.util.UUID
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Equivalence tests for the EyeTrackingViewModel optimizations. Each optimized path is checked against a
 * frozen copy of the pre-optimization implementation (the private `reference*` members below) on seeded
 * random inputs; floats are compared bit for bit and lists element for element.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VmOptimizationTest {

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

    private fun freshUser() = "vm_opt_" + UUID.randomUUID().toString().replace("-", "").take(12)

    private fun newVm(
        userId: String = freshUser(),
        store: LocalTelemetryStore? = null
    ): EyeTrackingViewModel {
        val dbId = context.getString(R.string.firestore_database_id)
        return EyeTrackingViewModel(
            repository = EyeTrackingRepository(db = FirebaseFirestore.getInstance(dbId)),
            currentUserId = userId,
            localStore = store
        )
    }

    private fun face(ts: Long, open: Float = 0.92f) = GazeFeatureVector(
        leftEyeOpenProb = open,
        rightEyeOpenProb = open,
        faceDetected = true,
        timestampMs = ts
    )

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    // =============================================================================================
    // 1. Live heatmap trail: exp table, hypot fast path, exact-size single allocation
    // =============================================================================================

    /** Frozen pre-optimization EyeTrackingViewModel.updateLiveHeatmapTrail. */
    private fun referenceTrail(
        prevTrail: List<LiveGazeTrailPoint>,
        normX: Float,
        normY: Float,
        faceDetected: Boolean,
        nowMs: Long,
        dtMs: Long
    ): List<LiveGazeTrailPoint> {
        val decayFactor = kotlin.math.exp(-dtMs.toDouble() / 2400.0).toFloat().coerceIn(0.82f, 0.99f)
        val decayed = ArrayList<LiveGazeTrailPoint>(minOf(prevTrail.size + 1, 32))
        val startIndex = (prevTrail.size - 32).coerceAtLeast(0)
        for (i in startIndex until prevTrail.size) {
            val pt = prevTrail[i]
            val age = nowMs - pt.timestampMs
            val nextIntensity = pt.intensity * decayFactor
            if (age <= 4800L && nextIntensity >= 0.08f) {
                decayed.add(pt.copy(intensity = nextIntensity))
            }
        }

        if (!faceDetected) return decayed

        val last = decayed.lastOrNull()
        if (last != null) {
            val dist = kotlin.math.hypot((normX - last.normX).toDouble(), (normY - last.normY).toDouble()).toFloat()
            if (dist <= 0.065f) {
                val boosted = (last.intensity + 0.18f).coerceAtMost(1.0f)
                val mergedX = (last.normX * 0.72f + normX * 0.28f).coerceIn(0.02f, 0.98f)
                val mergedY = (last.normY * 0.72f + normY * 0.28f).coerceIn(0.02f, 0.98f)
                decayed[decayed.lastIndex] = LiveGazeTrailPoint(
                    normX = mergedX,
                    normY = mergedY,
                    intensity = boosted,
                    timestampMs = nowMs
                )
                return decayed
            }
        }

        if (decayed.size >= 32) {
            decayed.removeAt(0)
        }
        decayed.add(
            LiveGazeTrailPoint(
                normX = normX,
                normY = normY,
                intensity = 0.34f,
                timestampMs = nowMs
            )
        )
        return decayed
    }

    private fun referenceDecayFactor(dtMs: Long): Float =
        kotlin.math.exp(-dtMs.toDouble() / 2400.0).toFloat().coerceIn(0.82f, 0.99f)

    @Test
    fun `trail decay table equals exp for every frame interval`() {
        for (dt in -200L..1_500L) {
            assertEquals("dt=$dt", referenceDecayFactor(dt).toBits(), liveGazeTrailDecayFactor(dt).toBits())
        }
        for (dt in longArrayOf(Long.MIN_VALUE / 2, -1_000_000L, 1_000_000L, Long.MAX_VALUE / 2)) {
            assertEquals("dt=$dt", referenceDecayFactor(dt).toBits(), liveGazeTrailDecayFactor(dt).toBits())
        }
    }

    private fun randomDt(rng: Random): Long = when (rng.nextInt(20)) {
        0 -> -60L + rng.nextInt(60)
        1 -> rng.nextLong(0L, 16L)
        2 -> 501L + rng.nextInt(400)
        else -> 16L + rng.nextInt(485)
    }

    /** Random point with age and intensity clustered around the 4.8 s / 0.08 cut-offs. */
    private fun randomTrailPoint(rng: Random, nowMs: Long, decay: Float): LiveGazeTrailPoint {
        val age = when (rng.nextInt(4)) {
            0 -> 4_790L + rng.nextInt(21)
            1 -> -50L + rng.nextInt(100)
            else -> rng.nextLong(0L, 6_000L)
        }
        val intensity = when (rng.nextInt(5)) {
            0 -> (0.08f / decay) * (1f + (rng.nextFloat() - 0.5f) * 1e-6f) // straddles the cut-off
            1 -> 0.08f / decay
            2 -> 1.0f
            else -> rng.nextFloat()
        }
        return LiveGazeTrailPoint(
            normX = 0.02f + rng.nextFloat() * 0.96f,
            normY = 0.02f + rng.nextFloat() * 0.96f,
            intensity = intensity,
            timestampMs = nowMs - age
        )
    }

    /** Gaze candidate at a distance from [base] concentrated at the merge radius and the fast-path bands. */
    private fun candidateNear(rng: Random, base: LiveGazeTrailPoint?): Pair<Float, Float> {
        if (base == null || rng.nextInt(10) == 0) {
            return when (rng.nextInt(40)) {
                0 -> Float.NaN to 0.5f
                1 -> 0.5f to Float.NaN
                else -> (0.02f + rng.nextFloat() * 0.96f) to (0.02f + rng.nextFloat() * 0.96f)
            }
        }
        val radius = when (rng.nextInt(9)) {
            0 -> 0.065 + (rng.nextDouble() - 0.5) * 2e-8
            1 -> 0.065 + (rng.nextDouble() - 0.5) * 2e-6
            2 -> 0.065 + (rng.nextDouble() - 0.5) * 2e-3
            3 -> 0.06403 + (rng.nextDouble() - 0.5) * 2e-5 // sure-inside band edge
            4 -> 0.06633 + (rng.nextDouble() - 0.5) * 2e-5 // sure-outside band edge
            5 -> 0.0
            6 -> rng.nextDouble() * 0.03
            else -> rng.nextDouble() * 0.3
        }
        val angle = if (rng.nextInt(6) == 0) (rng.nextInt(4) * Math.PI / 2) else rng.nextDouble() * 2 * Math.PI
        return (base.normX + (radius * cos(angle)).toFloat()) to (base.normY + (radius * sin(angle)).toFloat())
    }

    @Test
    fun `live trail single steps match the frozen reference on random trails`() {
        val rng = Random(20_261_106)
        var merges = 0
        var drops = 0
        repeat(60_000) { iter ->
            val nowMs = 100_000L + rng.nextInt(10_000)
            val dt = randomDt(rng)
            val decay = referenceDecayFactor(dt)
            val size = when (rng.nextInt(6)) {
                0 -> 0
                1 -> 32
                2 -> 33 + rng.nextInt(8)
                else -> rng.nextInt(33)
            }
            val prev = List(size) { randomTrailPoint(rng, nowMs, decay) }
            val base = if (prev.isEmpty()) null else prev[if (rng.nextBoolean()) prev.lastIndex else rng.nextInt(prev.size)]
            val (nx, ny) = candidateNear(rng, base)
            val faceDetected = rng.nextInt(6) != 0
            val expected = referenceTrail(prev, nx, ny, faceDetected, nowMs, dt)
            val actual = advanceLiveGazeTrail(prev, nx, ny, faceDetected, nowMs, dt)
            assertEquals("iter $iter (size $size, dt $dt, face $faceDetected)", expected, actual)
            if (faceDetected && expected.isNotEmpty() && expected.last().intensity != 0.34f) merges++
            if (faceDetected && size >= 32 && expected.size == 32) drops++
        }
        assertTrue("merge path exercised: $merges", merges > 1_000)
        assertTrue("full-trail path exercised: $drops", drops > 1_000)
    }

    @Test
    fun `live trail chained over its own output matches the frozen reference`() {
        val rng = Random(42)
        repeat(400) { chain ->
            var refTrail: List<LiveGazeTrailPoint> = emptyList()
            var optTrail: List<LiveGazeTrailPoint> = emptyList()
            var nowMs = 50_000L + rng.nextInt(1_000)
            var gx = 0.5f
            var gy = 0.5f
            repeat(300) { step ->
                val dt = randomDt(rng)
                nowMs += when (rng.nextInt(60)) {
                    0 -> 5_000L // everything ages out
                    1 -> 0L
                    else -> dt.coerceAtLeast(0L)
                }
                when (rng.nextInt(12)) {
                    0 -> { gx = 0.02f + rng.nextFloat() * 0.96f; gy = 0.02f + rng.nextFloat() * 0.96f }
                    1 -> {
                        val (cx, cy) = candidateNear(rng, refTrail.lastOrNull())
                        if (cx.isFinite() && cy.isFinite()) { gx = cx; gy = cy }
                    }
                    else -> {
                        gx = (gx + (rng.nextFloat() - 0.5f) * 0.03f).coerceIn(0.02f, 0.98f)
                        gy = (gy + (rng.nextFloat() - 0.5f) * 0.03f).coerceIn(0.02f, 0.98f)
                    }
                }
                val faceDetected = rng.nextInt(10) != 0
                refTrail = referenceTrail(refTrail, gx, gy, faceDetected, nowMs, dt)
                optTrail = advanceLiveGazeTrail(optTrail, gx, gy, faceDetected, nowMs, dt)
                assertEquals("chain $chain step $step", refTrail, optTrail)
            }
        }
    }

    // =============================================================================================
    // 2. Session FPS timeline helpers and the step-down banner cause
    // =============================================================================================

    @Test
    fun `fps timeline helpers match plus takeLast and takeLast average`() {
        val rng = Random(7)
        repeat(6_000) { iter ->
            val size = rng.nextInt(131)
            val timeline: List<Int> = when (rng.nextInt(4)) {
                0 -> if (size == 0) emptyList() else listOf(rng.nextInt(61))
                else -> List(size) { rng.nextInt(61) }
            }
            val fps = rng.nextInt(61)
            for (max in intArrayOf(0, 1, 2, 10, 119, 120, 121, 200)) {
                assertEquals("iter $iter max $max", (timeline + fps).takeLast(max), appendFpsSample(timeline, fps, max))
            }
            for (n in intArrayOf(0, 1, 9, 10, 11, timeline.size, timeline.size + 3)) {
                assertEquals(
                    "iter $iter n $n",
                    timeline.takeLast(n).average().toBits(),
                    averageOfLastFpsSamples(timeline, n).toBits()
                )
            }
        }
    }

    @Test
    fun `resolution step cause labels are unchanged`() {
        for (fps in -5..80) {
            assertEquals("Low analysis rate ($fps FPS < 15)", resolutionStepCauseLabel(true, fps))
            assertEquals("Thermal guard ($fps FPS, >40% below session baseline)", resolutionStepCauseLabel(false, fps))
        }
    }

    @Test
    fun `session fps timeline and thermal flag match the frozen reference timer`() {
        assumeFalse(isRunningOnEmulatorHardware())
        val vm = newVm()
        var t = 1_000_000L
        repeat(30) { vm.processIncomingFeatureFrame(face(t)); t += 33L }
        vm.startAttentionRecording()
        val startFps = vm.liveHudState.value.analyzerFps
        // Frozen pre-optimization session timer state
        var refTimeline: List<Int> = listOf(startFps)
        var refBaseline = startFps.toDouble().coerceAtLeast(10.0)
        var refThrottled = false
        var refElapsed = 0
        var throttledTicks = 0
        val rng = Random(11)
        repeat(150) { tick ->
            val interval = if (tick in 40..95) 85L + rng.nextInt(50) else 28L + rng.nextInt(12)
            repeat(10) { vm.processIncomingFeatureFrame(face(t)); t += interval }
            val fps = vm.liveHudState.value.analyzerFps
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))

            refElapsed++
            val nextTimeline = (refTimeline + fps).takeLast(120)
            if (refElapsed <= 60 && nextTimeline.isNotEmpty()) {
                refBaseline = nextTimeline.average().coerceAtLeast(10.0)
            }
            val recentAvg = nextTimeline.takeLast(10).average()
            refThrottled = if (refThrottled) {
                recentAvg < refBaseline * 0.80
            } else {
                refElapsed > 15 && recentAvg < refBaseline * 0.60
            }
            refTimeline = nextTimeline
            if (refThrottled) throttledTicks++

            val st = vm.attentionState.value
            assertEquals("tick $tick elapsed", refElapsed, st.elapsedSeconds)
            assertEquals("tick $tick timeline", refTimeline, st.fpsTimeline)
            assertEquals("tick $tick thermal", refThrottled, st.thermalThrottleDetected)
        }
        assertEquals(120, refTimeline.size)
        assertTrue("thermal path exercised: $throttledTicks", throttledTicks > 0)
        vm.clearSessionReplay()
        vm.stopAndSaveAttentionSession()
    }

    // =============================================================================================
    // 3. Fixation clustering over sub-list views instead of copies
    // =============================================================================================

    @Test
    fun `clustering a window view equals clustering the copied window`() {
        val rng = Random(3)
        repeat(400) { iter ->
            val size = rng.nextInt(700)
            var ts = 10_000L
            var cx = rng.nextFloat()
            var cy = rng.nextFloat()
            val samples = ArrayList<Triple<Float, Float, Long>>(size)
            repeat(size) {
                if (rng.nextInt(20) == 0) { cx = rng.nextFloat(); cy = rng.nextFloat() }
                ts += 20L + rng.nextInt(30) + if (rng.nextInt(60) == 0) 400L else 0L
                samples.add(Triple(cx + (rng.nextFloat() - 0.5f) * 0.05f, cy + (rng.nextFloat() - 0.5f) * 0.05f, ts))
            }
            val n = samples.size
            assertEquals(
                "iter $iter live window",
                GazeCalibrationMath.clusterFixations(samples.takeLast(240)),
                GazeCalibrationMath.clusterFixations(samples.subList((n - 240).coerceAtLeast(0), n))
            )
            val half = n / 2
            assertEquals(
                "iter $iter archived half",
                GazeCalibrationMath.clusterFixations(ArrayList(samples.subList(0, half)), maxClusters = Int.MAX_VALUE),
                GazeCalibrationMath.clusterFixations(samples.subList(0, half), maxClusters = Int.MAX_VALUE)
            )
        }
    }

    @Test
    fun `recorded fixations, live clusters and the saved session match the frozen reference`() {
        assumeFalse(isRunningOnEmulatorHardware())
        val vm = newVm()
        vm.startAttentionRecording()
        vm.updateActiveReadingSectionFromLayout("sec_2")
        val rng = Random(99)
        // Frozen pre-optimization sample buffer, archive and live-cluster logic
        val refSamples = ArrayList<Triple<Float, Float, Long>>()
        val refArchived = ArrayList<FixationClusterPoint>()
        var refLive: List<FixationClusterPoint> = emptyList()
        var archives = 0
        var t = 2_000_000L
        var fx = 0.5f
        var fy = 0.5f
        repeat(4_300) { i ->
            if (rng.nextInt(25) == 0) { fx = 0.05f + rng.nextFloat() * 0.9f; fy = 0.05f + rng.nextFloat() * 0.9f }
            val point = if (rng.nextInt(40) == 0) {
                null
            } else {
                Offset(fx + (rng.nextFloat() - 0.5f) * 0.02f, fy + (rng.nextFloat() - 0.5f) * 0.02f)
            }
            vm.updatePassageGazePoint(point)
            val faceDetected = rng.nextInt(30) != 0
            val open = if (rng.nextInt(30) == 0) 0.2f else 0.92f
            t += 25L + rng.nextInt(20) + if (rng.nextInt(100) == 0) 400L else 0L
            vm.processIncomingFeatureFrame(
                GazeFeatureVector(
                    leftEyeOpenProb = open,
                    rightEyeOpenProb = open,
                    faceDetected = faceDetected,
                    timestampMs = t
                )
            )

            if (faceDetected) {
                val passagePoint = point?.takeIf { open >= 0.50f }
                if (passagePoint != null) {
                    if (refSamples.size >= 3_600) {
                        val olderHalf = refSamples.subList(0, 1_800)
                        refArchived.addAll(
                            GazeCalibrationMath.clusterFixations(ArrayList(olderHalf), maxClusters = Int.MAX_VALUE)
                        )
                        olderHalf.clear()
                        archives++
                    }
                    refSamples.add(Triple(passagePoint.x, passagePoint.y, t))
                }
                if (passagePoint != null && (refSamples.size % 4 == 0 || refLive.isEmpty())) {
                    refLive = GazeCalibrationMath.clusterFixations(refSamples.takeLast(240))
                }
            }
            assertEquals("frame $i live clusters", refLive, vm.attentionState.value.computedClusters)
        }
        assertTrue("archive path exercised", archives > 0)

        vm.stopAndSaveAttentionSession()
        val all = refArchived + GazeCalibrationMath.clusterFixations(refSamples, maxClusters = Int.MAX_VALUE)
        val display = if (all.size <= 40) {
            all
        } else {
            all.indices.sortedByDescending { all[it].dwellMs }.take(40).sorted().map { all[it] }
        }
        val saved = requireNotNull(vm.attentionState.value.selectedReplaySession)
        assertEquals(all.size, saved.fixationClusterCount)
        assertEquals(display, vm.attentionState.value.computedClusters)
        assertEquals(
            display.map { String.format(Locale.US, "%.3f,%.3f,%d,%s", it.normX, it.normY, it.dwellMs, "p") },
            saved.clusterPoints
        )
    }

    // =============================================================================================
    // 4. Cloud streams keyed off the dedicated sync flag instead of every HUD emission
    // =============================================================================================

    @Test
    fun `profile and session lists still come from the vault while frames stream`() {
        assumeFalse(isRunningOnEmulatorHardware())
        val store = LocalTelemetryStore(context)
        val user = freshUser()
        store.saveLocalProfile(user, CalibrationProfile(id = "local_cal_opt", userId = user, name = "Opt Desk"))
        store.saveLocalSession(user, TrackingSession(id = "local_sess_opt", userId = user, title = "Opt Session"))
        val vm = newVm(user, store)
        assertFalse(vm.liveHudState.value.cloudSyncEnabled)
        val scope = CoroutineScope(Dispatchers.Main.immediate)
        scope.launch { vm.calibrationProfilesState.collect { } }
        scope.launch { vm.trackingSessionsState.collect { } }
        idleMain()
        var t = 1_000_000L
        repeat(300) { vm.processIncomingFeatureFrame(face(t)); t += 33L }
        idleMain()

        val profiles = vm.calibrationProfilesState.value
        assertTrue(profiles.toString(), profiles is UiDataState.Success)
        assertEquals(listOf("local_cal_opt"), (profiles as UiDataState.Success).data.map { it.id })
        val sessions = vm.trackingSessionsState.value
        assertTrue(sessions.toString(), sessions is UiDataState.Success)
        assertEquals(listOf("local_sess_opt"), (sessions as UiDataState.Success).data.map { it.id })
        assertEquals("Opt Desk", vm.liveHudState.value.activeProfileName)

        // A local list change still reaches the merged state through the same combine
        vm.deleteTrackingSession("local_sess_opt")
        idleMain()
        assertEquals(emptyList<String>(), (vm.trackingSessionsState.value as UiDataState.Success).data.map { it.id })
        scope.cancel()
    }

    @Test
    fun `stored cloud opt-in still initialises the HUD flag and toggling keeps it mirrored`() {
        assumeFalse(isRunningOnEmulatorHardware())
        val store = LocalTelemetryStore(context)
        val user = freshUser()
        store.setCloudSyncEnabled(user, true)
        val vm = newVm(user, store)
        assertTrue(vm.liveHudState.value.cloudSyncEnabled)
        vm.toggleCloudSync(false)
        assertFalse(vm.liveHudState.value.cloudSyncEnabled)
        assertFalse(store.isCloudSyncEnabled(user))
        assertFalse(newVm(user, store).liveHudState.value.cloudSyncEnabled)
    }

    // =============================================================================================
    // 5. Whole per-frame HUD pipeline against the frozen pre-optimization processFrameInternal
    // =============================================================================================

    /**
     * Frozen copy of the pre-optimization EyeTrackingViewModel.processFrameInternal for a fresh, uncalibrated
     * engine with no focused tile, no calibration wizard, no recording and the simulator off (the paths where
     * those features are no-ops are dropped). Allocations are kept exactly as they were (Pairs, stepCause).
     */
    private inner class ReferenceHudPipeline(
        private val triggerMode: GazeTriggerMode,
        private val filterMode: GazeFilterMode,
        private val overlayMode: GazeOverlayVisualMode,
        private val activePosture: GazePostureProfile,
        initial: LiveGazeHudState
    ) {
        private val activeMatrix: List<Double> = DEFAULT_AFFINE_MATRIX_2X6
        private val activeBaselineEyeDistPx = 148.0
        private val calibrationPosture = GazePostureProfile.UPRIGHT
        private val blinkDetector = BlinkHysteresisDetector(
            closeThreshold = 0.45f,
            openThreshold = 0.65f,
            intentionalMinMs = 350L,
            maxCommandMs = 800L
        )
        private val browDetector = BrowRaiseHysteresisDetector(
            neutralBrowRatio = 0.34f,
            raisedBrowRatio = 0.42f,
            holdTargetMs = 400L
        )
        private val screenGazeFilter = OneEuroFilter2D(minCutoff = 0.90, beta = 1.2, dCutoff = 1.0)
        private val screenKalmanFilter = GazeKalmanFilter2D(processNoiseQ = 1.0, measurementNoiseR = 1e-2)

        private var lastFrameTimestampMs: Long = 0L
        private var smoothedFrameIntervalMs: Double = 66.0
        private var below15FpsDurationMs: Long = 0L
        private var lastResolutionStepChangeMs: Long = 0L
        private var lowFpsDurationMs: Long = 0L
        private var fpsRecoveryDurationMs: Long = 0L
        private var above20FpsDurationMs: Long = 0L
        private var edgePinnedDurationMs: Long = 0L
        private var lastBrowEvalMs: Long = 0L

        // HUD mirror
        var normX = initial.normX
        var normY = initial.normY
        var trail: List<LiveGazeTrailPoint> = initial.liveTrailPoints
        var blinkHoldProgress = initial.blinkHoldProgress
        var browHoldProgress = initial.browHoldProgress
        var lastBlinkLabel = initial.lastBlinkLabel
        var naturalBlinkCount = initial.naturalBlinkCount
        var intentionalBlinkCount = initial.intentionalBlinkCount
        var browRaiseTriggerCount = initial.browRaiseTriggerCount
        var hasDrift = initial.hasDistanceDriftWarning
        var showNudge = initial.showRecalibrateNudge
        var perfMode = initial.isPerformanceModeActive
        var tier = initial.resolutionTier
        var analyzerFps = initial.analyzerFps
        var banner: String? = null

        // Coverage counters, so the random streams are known to reach every branch
        var stepDowns = 0
        var stepUps = 0
        var extendedRests = 0

        fun process(feature: GazeFeatureVector) {
            val now = feature.timestampMs
            val dtMs = if (lastFrameTimestampMs > 0L) (now - lastFrameTimestampMs).coerceIn(16L, 500L) else 66L
            lastFrameTimestampMs = now
            smoothedFrameIntervalMs = 0.85 * smoothedFrameIntervalMs + 0.15 * dtMs.toDouble()
            val liveFps = (1000.0 / smoothedFrameIntervalMs.coerceAtLeast(25.0)).roundToInt().coerceIn(1, 60)

            lowFpsDurationMs = if (liveFps < 12) (lowFpsDurationMs + dtMs).coerceAtMost(15000L) else 0L
            fpsRecoveryDurationMs = if (liveFps >= 15) (fpsRecoveryDurationMs + dtMs).coerceAtMost(20000L) else 0L
            val thermalDrop = false
            val nextPerfMode = when {
                lowFpsDurationMs >= 3000L || thermalDrop -> true
                liveFps >= 20 -> false
                fpsRecoveryDurationMs >= 5000L -> false
                else -> perfMode
            }
            val simulatorProxy = false
            below15FpsDurationMs = if (liveFps < 15 && !simulatorProxy) {
                (below15FpsDurationMs + dtMs).coerceAtMost(20000L)
            } else {
                (below15FpsDurationMs - dtMs / 2).coerceAtLeast(0L)
            }
            above20FpsDurationMs = if (liveFps >= 20 && !thermalDrop && !simulatorProxy) {
                (above20FpsDurationMs + dtMs).coerceAtMost(20000L)
            } else {
                0L
            }
            val canStepResolution = (now - lastResolutionStepChangeMs) >= 6000L
            val lowRateStep = below15FpsDurationMs >= 2500L
            val stepCause = if (lowRateStep) {
                "Low analysis rate ($liveFps FPS < 15)"
            } else {
                "Thermal guard ($liveFps FPS, >40% below session baseline)"
            }
            val nextTier = if (canStepResolution && (lowRateStep || thermalDrop)) {
                when (tier) {
                    AnalysisResolutionTier.TIER_HIGH_1280 -> {
                        lastResolutionStepChangeMs = now
                        below15FpsDurationMs = 0L
                        banner = "$stepCause: stepping camera analysis 1280×960 → 960×720."
                        stepDowns++
                        AnalysisResolutionTier.TIER_MED_960
                    }
                    AnalysisResolutionTier.TIER_MED_960 -> {
                        lastResolutionStepChangeMs = now
                        below15FpsDurationMs = 0L
                        banner = "$stepCause: stepping camera analysis 960×720 → 640×480."
                        stepDowns++
                        AnalysisResolutionTier.TIER_LOW_640
                    }
                    AnalysisResolutionTier.TIER_LOW_640 -> AnalysisResolutionTier.TIER_LOW_640
                }
            } else if (canStepResolution && above20FpsDurationMs >= 10_000L) {
                when (tier) {
                    AnalysisResolutionTier.TIER_LOW_640 -> {
                        lastResolutionStepChangeMs = now
                        above20FpsDurationMs = 0L
                        banner = "Analysis rate recovered ($liveFps FPS for 10 s): stepping camera analysis 640×480 → 960×720."
                        stepUps++
                        AnalysisResolutionTier.TIER_MED_960
                    }
                    AnalysisResolutionTier.TIER_MED_960 -> {
                        lastResolutionStepChangeMs = now
                        above20FpsDurationMs = 0L
                        banner = "Analysis rate recovered ($liveFps FPS for 10 s): stepping camera analysis 960×720 → 1280×960."
                        stepUps++
                        AnalysisResolutionTier.TIER_HIGH_1280
                    }
                    AnalysisResolutionTier.TIER_HIGH_1280 -> AnalysisResolutionTier.TIER_HIGH_1280
                }
            } else {
                tier
            }

            val (rawPredX, rawPredY) = GazeCalibrationMath.projectGaze(
                feature = feature,
                matrixCoeffs = activeMatrix,
                baselineEyeDistPx = activeBaselineEyeDistPx
            )
            val posture = activePosture
            val postureAdjustedX = (rawPredX + (posture.xBias - calibrationPosture.xBias)).coerceIn(0.02f, 0.98f)
            val postureAdjustedY = (rawPredY + (posture.yBias - calibrationPosture.yBias)).coerceIn(0.02f, 0.98f)

            val isEyelidClosing = feature.combinedEyeOpenProb < 0.50f

            val (smoothedX, smoothedY) = if (feature.faceDetected && !isEyelidClosing) {
                when (filterMode) {
                    GazeFilterMode.ONE_EURO -> {
                        val (fx, fy) = screenGazeFilter.filter(postureAdjustedX.toDouble(), postureAdjustedY.toDouble(), now)
                        Pair(fx.toFloat().coerceIn(0.02f, 0.98f), fy.toFloat().coerceIn(0.02f, 0.98f))
                    }
                    GazeFilterMode.KALMAN -> {
                        val (kx, ky) = screenKalmanFilter.filter(postureAdjustedX.toDouble(), postureAdjustedY.toDouble(), now)
                        Pair(kx.toFloat().coerceIn(0.02f, 0.98f), ky.toFloat().coerceIn(0.02f, 0.98f))
                    }
                    GazeFilterMode.RAW -> Pair(postureAdjustedX, postureAdjustedY)
                }
            } else {
                Pair(normX, normY)
            }

            val mode = triggerMode
            val blinkEnabled = mode == GazeTriggerMode.ALL_ACTIVE || mode == GazeTriggerMode.COMMAND_BLINK
            val browEnabled = mode == GazeTriggerMode.ALL_ACTIVE || mode == GazeTriggerMode.BROW_RAISE

            val (blinkProgress, blinkEvent) = if (feature.faceDetected) {
                blinkDetector.update(feature.combinedEyeOpenProb, now)
            } else {
                Pair(blinkHoldProgress, null)
            }
            val (browProgress, browFired) = if (browEnabled && feature.faceDetected && !isEyelidClosing) {
                if (lastBrowEvalMs > 0L && now - lastBrowEvalMs > browDetector.holdTargetMs) {
                    browDetector.cancelPendingRaise()
                }
                lastBrowEvalMs = now
                browDetector.update(feature.browToEyeRatio, now)
            } else {
                if (!browEnabled || !feature.faceDetected) {
                    browDetector.cancelPendingRaise()
                }
                Pair(0f, false)
            }

            var natBlinks = naturalBlinkCount
            var cmdBlinks = intentionalBlinkCount
            var browCount = browRaiseTriggerCount
            var blinkLabel = lastBlinkLabel

            if (browFired) {
                browCount++
                val holdMs = browDetector.holdTargetMs
                blinkLabel = "Brow raise ($holdMs ms hold) — no tile focused"
            }

            if (blinkEvent != null) {
                when (blinkEvent.type) {
                    BlinkType.NATURAL -> {
                        natBlinks++
                        blinkLabel = "Natural blink (${blinkEvent.durationMs} ms) — ignored for triggers"
                    }
                    BlinkType.INTENTIONAL_COMMAND -> {
                        cmdBlinks++
                        blinkLabel = if (blinkEnabled) {
                            "Command blink (${blinkEvent.durationMs} ms) — no tile focused"
                        } else {
                            "Command blink (${blinkEvent.durationMs} ms) — disabled in ${mode.label}"
                        }
                    }
                    BlinkType.EXTENDED_REST -> {
                        blinkLabel = "Extended closure (${blinkEvent.durationMs} ms) — trigger cancelled"
                        extendedRests++
                    }
                }
            }

            val distRatio = if (activeBaselineEyeDistPx > 20.0 && feature.faceDetected) {
                kotlin.math.abs(feature.eyeDistancePx - activeBaselineEyeDistPx) / activeBaselineEyeDistPx
            } else {
                0.0
            }
            val drift = distRatio > 0.15

            val isPinnedAtEdge = smoothedX <= 0.04f || smoothedX >= 0.96f || smoothedY <= 0.04f || smoothedY >= 0.96f
            edgePinnedDurationMs = if (feature.faceDetected && (isPinnedAtEdge || drift)) {
                (edgePinnedDurationMs + dtMs).coerceAtMost(20000L)
            } else {
                (edgePinnedDurationMs - dtMs / 2).coerceAtLeast(0L)
            }
            val nudge = edgePinnedDurationMs >= 4000L
            val updatedTrail = if (overlayMode == GazeOverlayVisualMode.RETICLE_ONLY) {
                if (trail.isEmpty()) trail else emptyList()
            } else {
                referenceTrail(trail, smoothedX, smoothedY, feature.faceDetected, now, dtMs)
            }

            normX = smoothedX
            normY = smoothedY
            trail = updatedTrail
            blinkHoldProgress = if (blinkEnabled) blinkProgress else 0f
            browHoldProgress = browProgress
            lastBlinkLabel = blinkLabel
            naturalBlinkCount = natBlinks
            intentionalBlinkCount = cmdBlinks
            browRaiseTriggerCount = browCount
            hasDrift = drift
            showNudge = nudge
            perfMode = nextPerfMode
            tier = nextTier
            analyzerFps = liveFps
        }
    }

    private object Seg {
        const val FIXATE = 0
        const val SACCADE = 1
        const val BLINK = 2
        const val DROOP = 3
        const val BROW = 4
        const val NO_FACE = 5
        const val KINDS = 7 // 6 = a second fixation weight
    }

    /** Seeded frame stream: random segments, a scripted gesture/pinning block and a sustained 30 FPS block. */
    private fun randomFrameStream(rng: Random, randomFrames: Int): List<GazeFeatureVector> {
        val frames = ArrayList<GazeFeatureVector>(randomFrames * 2 + 800)
        var t = 1_000_000L + rng.nextInt(1_000)
        var kind = Seg.FIXATE
        var left = 0
        var gxTarget = 0.0
        var gyTarget = 0.0
        var yaw = 0.0
        var pitch = 0.0
        var intervalMs = 33L
        var eyeDist = 148.0
        var singleEye = false

        fun add(
            face: Boolean,
            open: Float,
            brow: Float,
            gx: Double,
            gy: Double,
            stepMs: Long
        ) {
            t += stepMs
            val leftConf = if (face) 0.5f + rng.nextFloat() * 0.5f else 0f
            frames.add(
                GazeFeatureVector(
                    gx = gx,
                    gy = gy,
                    yaw = yaw + (rng.nextDouble() - 0.5) * 0.4,
                    pitch = pitch + (rng.nextDouble() - 0.5) * 0.4,
                    roll = (rng.nextDouble() - 0.5) * 4.0,
                    eyeDistancePx = eyeDist,
                    leftEyeOpenProb = if (face) open else 0f,
                    rightEyeOpenProb = if (face) (open + (rng.nextFloat() - 0.5f) * 0.06f).coerceIn(0f, 1f) else 0f,
                    leftEyeConfidence = leftConf,
                    rightEyeConfidence = if (face) (0.5f + rng.nextFloat() * 0.5f) else 0f,
                    browToEyeRatio = brow,
                    isSingleEyeMode = singleEye,
                    faceDetected = face,
                    timestampMs = t
                )
            )
        }

        fun randomFrame(fastPhase: Boolean) {
            if (left <= 0) {
                kind = rng.nextInt(Seg.KINDS)
                left = when (kind) {
                    Seg.BLINK -> 1 + rng.nextInt(30)
                    Seg.BROW -> 3 + rng.nextInt(40)
                    else -> 5 + rng.nextInt(60)
                }
                if (kind == Seg.SACCADE || rng.nextInt(3) == 0) {
                    gxTarget = (rng.nextDouble() - 0.5) * 0.6
                    gyTarget = (rng.nextDouble() - 0.5) * 0.5
                }
                yaw = (rng.nextDouble() - 0.5) * 10.0
                pitch = (rng.nextDouble() - 0.5) * 8.0
                intervalMs = when (rng.nextInt(10)) {
                    0, 1, 2 -> 95L + rng.nextInt(70)
                    3 -> 66L
                    4 -> 16L + rng.nextInt(12)
                    else -> 33L
                }
                eyeDist = if (rng.nextInt(5) == 0) 105.0 + rng.nextDouble() * 90.0 else 148.0 + (rng.nextDouble() - 0.5) * 8.0
                singleEye = rng.nextInt(8) == 0
            }
            left--
            val r = rng.nextInt(250)
            val stepMs = when {
                fastPhase -> 32L + rng.nextInt(3)
                r == 0 -> 520L + rng.nextInt(1_500) // gap in the stream
                r == 1 -> 0L // repeated timestamp
                else -> (intervalMs + rng.nextInt(5) - 2).coerceAtLeast(1L)
            }
            val face = kind != Seg.NO_FACE
            val open = when (kind) {
                Seg.BLINK -> 0.02f + rng.nextFloat() * 0.3f
                Seg.DROOP -> 0.40f + rng.nextFloat() * 0.3f
                else -> 0.80f + rng.nextFloat() * 0.18f
            }
            val brow = if (kind == Seg.BROW) 0.45f + rng.nextFloat() * 0.12f else 0.32f + rng.nextFloat() * 0.04f
            add(
                face = face,
                open = open,
                brow = brow,
                gx = gxTarget + (rng.nextDouble() - 0.5) * 0.01,
                gy = gyTarget + (rng.nextDouble() - 0.5) * 0.01,
                stepMs = stepMs
            )
        }

        fun scriptedGestures() {
            singleEye = false
            eyeDist = 148.0
            fun open(n: Int) = repeat(n) { add(true, 0.92f, 0.34f, 0.0, 0.0, 33L) }
            open(15)
            repeat(20) { add(true, 0.92f, 0.52f, 0.0, 0.0, 33L) } // 660 ms brow raise
            open(15)
            for (closedFrames in intArrayOf(5, 15, 35)) { // natural, command, extended closures
                repeat(closedFrames) { add(true, 0.05f, 0.34f, 0.0, 0.0, 33L) }
                open(15)
            }
            repeat(160) { add(true, 0.92f, 0.34f, 0.45, 0.0, 33L) } // > 4 s pinned at the right edge
            eyeDist = 110.0
            repeat(160) { add(true, 0.92f, 0.34f, 0.0, 0.0, 33L) } // > 4 s distance drift
            eyeDist = 148.0
            open(15)
        }

        repeat(randomFrames) { randomFrame(fastPhase = false) }
        scriptedGestures()
        left = 0
        repeat(450) { randomFrame(fastPhase = true) } // sustained ~30 FPS: resolution and perf mode recover
        left = 0
        repeat(randomFrames) { randomFrame(fastPhase = false) }
        return frames
    }

    private fun assertHudMatches(where: String, ref: ReferenceHudPipeline, vm: EyeTrackingViewModel, feature: GazeFeatureVector) {
        val hud = vm.liveHudState.value
        assertSame("$where rawFeature", feature, hud.rawFeature)
        assertEquals("$where normX", ref.normX.toBits(), hud.normX.toBits())
        assertEquals("$where normY", ref.normY.toBits(), hud.normY.toBits())
        assertEquals("$where trail", ref.trail, hud.liveTrailPoints)
        assertEquals("$where blinkHold", ref.blinkHoldProgress.toBits(), hud.blinkHoldProgress.toBits())
        assertEquals("$where browHold", ref.browHoldProgress.toBits(), hud.browHoldProgress.toBits())
        assertEquals("$where label", ref.lastBlinkLabel, hud.lastBlinkLabel)
        assertEquals("$where natural", ref.naturalBlinkCount, hud.naturalBlinkCount)
        assertEquals("$where command", ref.intentionalBlinkCount, hud.intentionalBlinkCount)
        assertEquals("$where brow", ref.browRaiseTriggerCount, hud.browRaiseTriggerCount)
        assertEquals("$where drift", ref.hasDrift, hud.hasDistanceDriftWarning)
        assertEquals("$where nudge", ref.showNudge, hud.showRecalibrateNudge)
        assertEquals("$where perf", ref.perfMode, hud.isPerformanceModeActive)
        assertEquals("$where tier", ref.tier, hud.resolutionTier)
        assertEquals("$where fps", ref.analyzerFps, hud.analyzerFps)
        assertEquals("$where dwell", 0f.toBits(), hud.focusedTileDwellProgress.toBits())
        assertEquals("$where banner", ref.banner, vm.statusBannerMessage.value)
    }

    @Test
    fun `per-frame HUD pipeline matches the frozen pre-optimization reference`() {
        assumeFalse(isRunningOnEmulatorHardware())
        var stepDowns = 0
        var stepUps = 0
        var extendedRests = 0
        var naturals = 0
        var commands = 0
        var brows = 0
        var perfFrames = 0
        var nudgeFrames = 0
        var driftFrames = 0
        var trailFrames = 0
        for (seed in 1..24) {
            val trigger = GazeTriggerMode.entries[seed % GazeTriggerMode.entries.size]
            val filter = GazeFilterMode.entries[seed % GazeFilterMode.entries.size]
            val overlay = GazeOverlayVisualMode.entries[(seed / 3) % GazeOverlayVisualMode.entries.size]
            val posture = GazePostureProfile.entries[(seed / 2) % GazePostureProfile.entries.size]
            val vm = newVm()
            vm.setTriggerMode(trigger)
            vm.setGazeFilterMode(filter)
            vm.setPostureProfile(posture)
            while (vm.liveHudState.value.overlayVisualMode != overlay) vm.cycleOverlayVisualMode()
            vm.dismissStatusBanner()
            val ref = ReferenceHudPipeline(trigger, filter, overlay, posture, vm.liveHudState.value)
            val frames = randomFrameStream(Random(seed * 7_919L), randomFrames = 1_000)
            frames.forEachIndexed { i, f ->
                vm.processIncomingFeatureFrame(f)
                ref.process(f)
                assertHudMatches("seed $seed ($trigger/$filter/$overlay/$posture) frame $i", ref, vm, f)
                if (ref.perfMode) perfFrames++
                if (ref.showNudge) nudgeFrames++
                if (ref.hasDrift) driftFrames++
                if (ref.trail.isNotEmpty()) trailFrames++
            }
            stepDowns += ref.stepDowns
            stepUps += ref.stepUps
            extendedRests += ref.extendedRests
            naturals += ref.naturalBlinkCount
            commands += ref.intentionalBlinkCount
            brows += ref.browRaiseTriggerCount
        }
        val coverage = "down=$stepDowns up=$stepUps ext=$extendedRests nat=$naturals cmd=$commands brow=$brows " +
            "perf=$perfFrames nudge=$nudgeFrames drift=$driftFrames trail=$trailFrames"
        assertTrue(coverage, stepDowns > 0 && stepUps > 0 && extendedRests > 0)
        assertTrue(coverage, naturals > 0 && commands > 0 && brows > 0)
        assertTrue(coverage, perfFrames > 0 && nudgeFrames > 0 && driftFrames > 0 && trailFrames > 0)
    }
}
