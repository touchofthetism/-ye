package com.example.benchmark

import android.content.Context
import android.graphics.PointF
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.data.model.CalibrationProfile
import com.example.data.model.DEFAULT_AFFINE_MATRIX_2X6
import com.example.data.model.GazeFeatureVector
import com.example.data.model.TrackingSession
import com.example.data.repository.EyeTrackingRepository
import com.example.data.repository.LocalTelemetryStore
import com.example.domain.BenchmarkRunner
import com.example.domain.BlinkHysteresisDetector
import com.example.domain.BrowRaiseHysteresisDetector
import com.example.domain.CameraGazeAnalyzerEngine
import com.example.domain.GazeCalibrationMath
import com.example.domain.GazeKalmanFilter2D
import com.example.domain.OneEuroFilter2D
import com.example.domain.ReplayScenario
import com.example.domain.ReplayTestHarness
import com.example.ui.viewmodel.EyeTrackingViewModel
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Method
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Opt-in micro-benchmarks for every per-frame and per-session hot path.
 *
 * Run with:
 *   gradle :app:testDebugUnitTest -Pbenchmark --tests 'com.example.benchmark.PipelineBenchmarkTest'
 *
 * Results (median / p90 nanoseconds per operation over repeated timed rounds, after warm-up) are
 * printed and written to app/build/benchmark/benchmark-results.json so runs can be diffed.
 * JVM numbers are a relative signal for regressions, not on-device latency.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PipelineBenchmarkTest {

    private val results = JSONArray()
    private val quality = JSONObject()

    @Volatile
    private var sink: Double = 0.0

    @Before
    fun requireOptIn() {
        assumeTrue(
            "Benchmarks are opt-in; pass -Pbenchmark to Gradle",
            System.getProperty("oculagaze.benchmark") == "true"
        )
    }

    @Test
    fun runAllBenchmarks() {
        benchEyeRoiAnalysis()
        benchCalibrationMath()
        benchFilters()
        benchDetectors()
        benchReplayHarness()
        benchBenchmarkRunner()
        benchViewModelFramePipeline()
        benchLocalTelemetryStore()
        writeResults()
    }

    // ---------------------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------------------

    private fun bench(
        name: String,
        opsPerRound: Int,
        warmupRounds: Int = 8,
        rounds: Int = 25,
        block: () -> Unit
    ) {
        repeat(warmupRounds) { block() }
        val perOpNs = DoubleArray(rounds)
        for (r in 0 until rounds) {
            val t0 = System.nanoTime()
            block()
            perOpNs[r] = (System.nanoTime() - t0).toDouble() / opsPerRound
        }
        perOpNs.sort()
        val median = perOpNs[rounds / 2]
        val p90 = perOpNs[((rounds - 1) * 0.9).toInt()]
        val min = perOpNs[0]
        println(String.format(java.util.Locale.US, "BENCH %-48s median=%12.1f ns/op  p90=%12.1f  min=%12.1f", name, median, p90, min))
        results.put(
            JSONObject()
                .put("name", name)
                .put("medianNsPerOp", median)
                .put("p90NsPerOp", p90)
                .put("minNsPerOp", min)
                .put("opsPerRound", opsPerRound)
        )
    }

    private fun writeResults() {
        val out = File("build/benchmark").apply { mkdirs() }
        val json = JSONObject()
            .put("javaVersion", System.getProperty("java.version"))
            .put("benchmarks", results)
            .put("quality", quality)
        File(out, "benchmark-results.json").writeText(json.toString(2))
        println("BENCH results written to ${File(out, "benchmark-results.json").absolutePath}")
    }

    // ---------------------------------------------------------------------------------------------
    // 1. Eye ROI pixel analysis (the per-frame CPU hot loop in CameraGazeAnalyzerEngine)
    // ---------------------------------------------------------------------------------------------

    private class SyntheticFrame(
        val yBytes: ByteArray,
        val rawWidth: Int,
        val rawHeight: Int,
        val rotation: Int,
        val rowStride: Int,
        val leftEye: List<PointF>,
        val rightEye: List<PointF>
    )

    /**
     * Paints an upright (portrait) face crop into a raw landscape Y-plane buffer for the given
     * sensor rotation, with sclera, a dark iris/pupil disc, a specular glint and sensor noise.
     */
    private fun makeFrame(rotation: Int, eyeWidthPx: Float, rowPadding: Int = 64, seed: Int = 7): SyntheticFrame {
        val uprightW = 960
        val uprightH = 1280
        val rawWidth = if (rotation == 90 || rotation == 270) uprightH else uprightW
        val rawHeight = if (rotation == 90 || rotation == 270) uprightW else uprightH
        val rowStride = rawWidth + rowPadding
        val buf = ByteArray(rowStride * rawHeight)
        val rnd = Random(seed)

        val eyeA = eyeWidthPx / 2f
        val eyeB = eyeWidthPx * 0.19f
        val irisR = eyeWidthPx * 0.17f
        val leftC = PointF(480f - eyeWidthPx * 1.55f, 560f)
        val rightC = PointF(480f + eyeWidthPx * 1.55f, 560f)
        val leftIris = PointF(leftC.x + eyeA * 0.18f, leftC.y + eyeB * 0.15f)
        val rightIris = PointF(rightC.x + eyeA * 0.18f, rightC.y + eyeB * 0.15f)

        fun uprightLuma(ux: Int, uy: Int): Int {
            var luma = 150
            for ((c, iris) in listOf(leftC to leftIris, rightC to rightIris)) {
                val ex = (ux - c.x) / eyeA
                val ey = (uy - c.y) / eyeB
                if (ex * ex + ey * ey <= 1f) {
                    luma = 205
                    val d = hypot((ux - iris.x).toDouble(), (uy - iris.y).toDouble())
                    if (d <= irisR) luma = if (d <= irisR * 0.45) 22 else 58
                    if (hypot((ux - (iris.x - irisR * 0.3)).toDouble(), (uy - (iris.y - irisR * 0.3)).toDouble()) < irisR * 0.18) luma = 250
                }
            }
            return (luma + rnd.nextInt(-6, 7)).coerceIn(0, 255)
        }

        for (sy in 0 until rawHeight) {
            for (sx in 0 until rawWidth) {
                val ux: Int
                val uy: Int
                when (rotation) {
                    90 -> { ux = rawHeight - 1 - sy; uy = sx }
                    180 -> { ux = rawWidth - 1 - sx; uy = rawHeight - 1 - sy }
                    270 -> { ux = sy; uy = rawWidth - 1 - sx }
                    else -> { ux = sx; uy = sy }
                }
                buf[sy * rowStride + sx] = uprightLuma(ux, uy).toByte()
            }
        }

        fun contour(c: PointF): List<PointF> = (0 until 16).map { i ->
            // Index 0 = medial corner, index 8 = lateral corner, matching ML Kit's 16-point eye contour.
            val theta = PI * i / 8.0
            PointF((c.x + eyeA * cos(theta)).toFloat(), (c.y - eyeB * sin(theta)).toFloat())
        }
        return SyntheticFrame(buf, rawWidth, rawHeight, rotation, rowStride, contour(leftC), contour(rightC))
    }

    private fun allocateWithoutConstructor(cls: Class<*>): Any {
        val unsafeCls = Class.forName("sun.misc.Unsafe")
        val field = unsafeCls.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        return unsafeCls.getMethod("allocateInstance", Class::class.java).invoke(unsafe, cls)
    }

    /** Resolves the engine's ROI analysis entry point (signature-tolerant across refactors). */
    private fun roiAnalyzer(): (List<PointF>, Float, SyntheticFrame, Int) -> Any {
        val engineCls = CameraGazeAnalyzerEngine::class.java
        val m: Method = engineCls.declaredMethods.first { it.name == "analyzeEyeContourRoi" && it.parameterCount == 9 }
        m.isAccessible = true
        val engine = allocateWithoutConstructor(engineCls)
        return { pts, open, f, step ->
            m.invoke(engine, pts, open, f.yBytes, f.rawWidth, f.rawHeight, f.rotation, f.rowStride, 1, step)
        }
    }

    private fun benchEyeRoiAnalysis() {
        val analyze = roiAnalyzer()
        for ((label, rotation, eyeW) in listOf(
            Triple("arm-length 64px eye, rot270", 270, 64f),
            Triple("close 140px eye, rot270", 270, 140f),
            Triple("arm-length 64px eye, rot90", 90, 64f),
            Triple("arm-length 64px eye, rot0", 0, 64f)
        )) {
            val frame = makeFrame(rotation, eyeW)
            for (step in listOf(1, 2)) {
                bench("eyeRoi.both-eyes [$label, step=$step]", opsPerRound = 400) {
                    var acc = 0
                    for (i in 0 until 400) {
                        acc += analyze(frame.leftEye, 0.92f, frame, step).hashCode()
                        acc += analyze(frame.rightEye, 0.92f, frame, step).hashCode()
                    }
                    sink += acc
                }
            }
        }
        // Record the ROI output for the reference frame so optimizations can be checked for drift.
        val ref = makeFrame(270, 64f)
        quality.put("eyeRoi.left.rot270.64px", analyze(ref.leftEye, 0.92f, ref, 1).toString())
        quality.put("eyeRoi.right.rot270.64px", analyze(ref.rightEye, 0.92f, ref, 1).toString())
    }

    // ---------------------------------------------------------------------------------------------
    // 2. Calibration math
    // ---------------------------------------------------------------------------------------------

    private fun syntheticSamples(n: Int, seed: Int): List<Pair<GazeFeatureVector, Pair<Float, Float>>> {
        val rnd = Random(seed)
        val targets = GazeCalibrationMath.FIVE_POINT_CALIBRATION_TARGETS
        return (0 until n).map { i ->
            val t = targets[i % targets.size]
            val feat = GazeFeatureVector(
                gx = (t.normX - 0.5) * 0.40 + rnd.nextDouble(-0.01, 0.01),
                gy = (t.normY - 0.5) * 0.35 + rnd.nextDouble(-0.01, 0.01),
                yaw = (0.5 - t.normX) * 12.0 + rnd.nextDouble(-0.5, 0.5),
                pitch = (0.5 - t.normY) * 10.0 + rnd.nextDouble(-0.5, 0.5),
                roll = rnd.nextDouble(-1.0, 1.0),
                eyeDistancePx = 148.0 + rnd.nextDouble(-3.0, 3.0),
                faceDetected = true,
                timestampMs = 1_000L + i * 33L
            )
            feat to (t.normX to t.normY)
        }
    }

    private fun benchCalibrationMath() {
        val feat = GazeFeatureVector(gx = 0.08, gy = -0.04, yaw = 3.0, pitch = -2.0, roll = 0.5, faceDetected = true, timestampMs = 1L)
        val coeffs = DEFAULT_AFFINE_MATRIX_2X6
        bench("calib.projectGaze", opsPerRound = 200_000) {
            var acc = 0.0
            for (i in 0 until 200_000) acc += GazeCalibrationMath.projectGaze(feat, coeffs).first
            sink += acc
        }
        bench("feature.toStandardizedFeatureArray", opsPerRound = 200_000) {
            var acc = 0.0
            for (i in 0 until 200_000) acc += feat.toStandardizedFeatureArray()[1]
            sink += acc
        }
        val fit125 = syntheticSamples(125, 1)
        bench("calib.fitRidgeAffineMatrix2x6 [125 samples]", opsPerRound = 200) {
            var acc = 0.0
            for (i in 0 until 200) acc += GazeCalibrationMath.fitRidgeAffineMatrix2x6(fit125)[0]
            sink += acc
        }
        val fit1000 = syntheticSamples(1000, 2)
        bench("calib.fitRidgeAffineMatrix2x6 [1000 samples]", opsPerRound = 40) {
            var acc = 0.0
            for (i in 0 until 40) acc += GazeCalibrationMath.fitRidgeAffineMatrix2x6(fit1000)[0]
            sink += acc
        }
        val fitted = GazeCalibrationMath.fitRidgeAffineMatrix2x6(fit125)
        val validation = syntheticSamples(100, 3).map { (f, t) -> f to (t.first * 0.6f + 0.2f to t.second * 0.6f + 0.2f) }
        bench("calib.evaluateHeldOutValidation [100 samples]", opsPerRound = 2_000) {
            var acc = 0.0
            for (i in 0 until 2_000) acc += GazeCalibrationMath.evaluateHeldOutValidation(fitted, validation, 148.0).meanErrorPct
            sink += acc
        }
        val rnd = Random(4)
        var t = 0L
        val gazeStream = (0 until 3_000).map {
            t += 33L
            val cluster = (it / 40) % 6
            Triple(0.15f + cluster * 0.13f + rnd.nextFloat() * 0.03f, 0.2f + (cluster % 3) * 0.25f + rnd.nextFloat() * 0.03f, t)
        }
        bench("calib.clusterFixations [3000 samples]", opsPerRound = 50) {
            var acc = 0
            for (i in 0 until 50) acc += GazeCalibrationMath.clusterFixations(gazeStream).size
            sink += acc.toDouble()
        }
        quality.put("calib.fit125.coeffs", JSONArray(fitted))
        quality.put("calib.validation.meanErrorPct", GazeCalibrationMath.evaluateHeldOutValidation(fitted, validation, 148.0).meanErrorPct)
        quality.put("calib.clusterFixations.count", GazeCalibrationMath.clusterFixations(gazeStream).size)
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Filters and detectors
    // ---------------------------------------------------------------------------------------------

    private fun benchFilters() {
        val rnd = Random(5)
        val xs = DoubleArray(4096) { 0.5 + rnd.nextDouble(-0.02, 0.02) }
        val ys = DoubleArray(4096) { 0.5 + rnd.nextDouble(-0.02, 0.02) }
        val oneEuro = OneEuroFilter2D(minCutoff = 0.85, beta = 0.035, dCutoff = 1.0)
        var ts = 0L
        bench("filter.OneEuroFilter2D.filter", opsPerRound = 100_000) {
            var acc = 0.0
            for (i in 0 until 100_000) {
                ts += 33L
                acc += oneEuro.filter(xs[i and 4095], ys[i and 4095], ts).first
            }
            sink += acc
        }
        val kalman = GazeKalmanFilter2D()
        bench("filter.GazeKalmanFilter2D.filter", opsPerRound = 100_000) {
            var acc = 0.0
            for (i in 0 until 100_000) {
                ts += 33L
                acc += kalman.filter(xs[i and 4095], ys[i and 4095], ts).first
            }
            sink += acc
        }
    }

    private fun benchDetectors() {
        val blink = BlinkHysteresisDetector()
        val brow = BrowRaiseHysteresisDetector()
        var ts = 0L
        bench("detector.BlinkHysteresis.update", opsPerRound = 200_000) {
            var acc = 0.0
            for (i in 0 until 200_000) {
                ts += 33L
                val open = if ((i / 12) % 9 == 0) 0.1f else 0.9f
                acc += blink.update(open, ts).first
            }
            sink += acc
        }
        bench("detector.BrowRaiseHysteresis.update", opsPerRound = 200_000) {
            var acc = 0.0
            for (i in 0 until 200_000) {
                ts += 33L
                val ratio = if ((i / 15) % 7 == 0) 0.46f else 0.34f
                acc += brow.update(ratio, ts).first
            }
            sink += acc
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 4. Replay harness and BenchmarkRunner scorecard
    // ---------------------------------------------------------------------------------------------

    private fun benchReplayHarness() {
        bench("replay.generateScenarioTrace [all scenarios x180]", opsPerRound = 1, rounds = 15) {
            var acc = 0
            for (s in ReplayScenario.entries) acc += ReplayTestHarness.generateScenarioTrace(s, frameCount = 180).size
            sink += acc.toDouble()
        }
        val traces = ReplayScenario.entries.associateWith { ReplayTestHarness.generateScenarioTrace(it, frameCount = 180) }
        bench("replay.evaluateTrace [all scenarios x180]", opsPerRound = 1, rounds = 15) {
            var acc = 0
            for ((s, trace) in traces) {
                acc += ReplayTestHarness.evaluateTrace(
                    scenario = s,
                    trace = trace,
                    blinkDetector = BlinkHysteresisDetector(),
                    browDetector = BrowRaiseHysteresisDetector(),
                    pupilFilter = OneEuroFilter2D(minCutoff = 0.85, beta = 0.035, dCutoff = 1.0),
                    screenFilter = OneEuroFilter2D(minCutoff = 0.90, beta = 0.040, dCutoff = 1.0),
                    matrixCoeffs = DEFAULT_AFFINE_MATRIX_2X6
                ).totalFramesEvaluated
            }
            sink += acc.toDouble()
        }
    }

    private fun benchBenchmarkRunner() {
        bench("BenchmarkRunner.runCompleteBenchmark", opsPerRound = 1, warmupRounds = 3, rounds = 10) {
            sink += BenchmarkRunner.runCompleteBenchmark().meanAccuracyPx
        }
        val card = BenchmarkRunner.runCompleteBenchmark()
        quality.put(
            "scorecard",
            JSONObject()
                .put("allScenariosPassed", card.allScenariosPassed)
                .put("failedScenarios", JSONArray(card.scenarioResults.filter { !it.passed }.map { it.scenario.name }))
                .put("meanAccuracyPx", card.meanAccuracyPx)
                .put("worstEdgeAccuracyPx", card.worstEdgeAccuracyPx)
                .put("rmsJitterPx", card.rmsJitterPx)
                .put("falseActivationsPerMinute", card.falseActivationsPerMinute)
                .put("latencyMedianMs", card.latencyMedianMs)
                .put("latencyP95Ms", card.latencyP95Ms)
        )
        println(card.toFormattedReport())
    }

    // ---------------------------------------------------------------------------------------------
    // 5. ViewModel per-frame pipeline (projection, smoothing, dwell, blink, attention recording)
    // ---------------------------------------------------------------------------------------------

    private fun newViewModel(userId: String): EyeTrackingViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
        val dbId = context.getString(R.string.firestore_database_id)
        return EyeTrackingViewModel(
            repository = EyeTrackingRepository(db = FirebaseFirestore.getInstance(dbId)),
            currentUserId = userId,
            localStore = LocalTelemetryStore(context)
        )
    }

    private fun benchViewModelFramePipeline() {
        val trace = ReplayScenario.entries.flatMap { ReplayTestHarness.generateScenarioTrace(it, frameCount = 120) }
        var ts = 10_000L
        fun retimed(i: Int): GazeFeatureVector {
            ts += 33L
            return trace[i % trace.size].copy(timestampMs = ts)
        }
        val frames = 3_000

        val idleVm = newViewModel("bench_idle_user")
        bench("viewModel.processIncomingFeatureFrame [idle deck]", opsPerRound = frames, warmupRounds = 4, rounds = 12) {
            for (i in 0 until frames) idleVm.processIncomingFeatureFrame(retimed(i))
            shadowOf(Looper.getMainLooper()).idle()
        }

        val recVm = newViewModel("bench_rec_user")
        recVm.startAttentionRecording()
        bench("viewModel.processIncomingFeatureFrame [attention recording]", opsPerRound = frames, warmupRounds = 4, rounds = 12) {
            for (i in 0 until frames) recVm.processIncomingFeatureFrame(retimed(i))
            shadowOf(Looper.getMainLooper()).idle()
        }
        recVm.clearSessionReplay()
    }

    // ---------------------------------------------------------------------------------------------
    // 6. Local telemetry vault (SharedPreferences + JSON)
    // ---------------------------------------------------------------------------------------------

    private fun benchLocalTelemetryStore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = LocalTelemetryStore(context)
        val user = "bench_store_user"
        store.clearAllLocalData(user)
        repeat(20) { i ->
            store.saveLocalProfile(user, CalibrationProfile(id = "p$i", userId = user, name = "Profile $i", isActive = i == 19))
            store.saveLocalSession(
                user,
                TrackingSession(
                    id = "s$i",
                    userId = user,
                    title = "Session $i",
                    sectionLoads = List(6) { k -> "sec_$k:${k * 1000}:${k * 5}" },
                    clusterPoints = List(40) { k -> "0.${k % 9}:0.${(k + 3) % 9}:${k * 12}" }
                )
            )
        }
        bench("store.loadLocalProfiles [20]", opsPerRound = 200) {
            var acc = 0
            for (i in 0 until 200) acc += store.loadLocalProfiles(user).size
            sink += acc.toDouble()
        }
        bench("store.loadLocalSessions [20]", opsPerRound = 200) {
            var acc = 0
            for (i in 0 until 200) acc += store.loadLocalSessions(user).size
            sink += acc.toDouble()
        }
        bench("store.saveLocalProfile [into 20]", opsPerRound = 50) {
            for (i in 0 until 50) store.saveLocalProfile(user, CalibrationProfile(id = "p${i % 20}", userId = user, name = "Profile ${i % 20}"))
        }
        store.clearAllLocalData(user)
    }
}
