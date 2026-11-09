package com.example.optimization

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import com.example.ReadInOwnScope
import com.example.data.repository.LocalTelemetryStore
import com.example.isCalibrationTargetStep
import com.example.isCameraPermissionGranted
import com.example.prefetchLocalVault
import com.example.ui.viewmodel.CalibrationFlowStep
import com.example.ui.viewmodel.CalibrationWizardState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import java.util.Random
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Equivalence tests for the MainActivity.kt / AndroidManifest.xml / strings.xml optimization pass.
 * Each test pins the optimized code to a frozen copy of the pre-optimization logic or values.
 * Plain JVM tests; the Robolectric ones are in the classes below.
 */
class ActivityOptimizationTest {

    @Test
    fun `calibration target predicate matches the frozen overlay condition for every step and tab`() {
        for (step in CalibrationFlowStep.entries) {
            assertEquals(
                "step $step",
                frozenShowCalibrationOverlay(selectedTab = 0, calibrationState = CalibrationWizardState(step = step)),
                isCalibrationTargetStep(step)
            )
        }
        val rng = Random(0x5EED_0001L)
        repeat(5_000) { i ->
            // Tabs 0..3 are real; -1 and 4 cover out-of-range values.
            val tab = rng.nextInt(6) - 1
            val state = CalibrationWizardState(
                step = CalibrationFlowStep.entries[rng.nextInt(CalibrationFlowStep.entries.size)],
                includeHeldOutValidation = rng.nextBoolean(),
                currentTargetIndex = rng.nextInt(9),
                currentTargetProgress = rng.nextFloat(),
                samplesCollectedForCurrentTarget = i
            )
            assertEquals(
                "tab $tab, state $state",
                frozenShowCalibrationOverlay(tab, state),
                tab == 0 && isCalibrationTargetStep(state.step)
            )
        }
    }

    @Test
    fun `MainActivity resolves to the same label, theme and launcher entry as before`() {
        val manifest = parseXml(sourceFile("src/main/AndroidManifest.xml")).documentElement
        val application = manifest.childElements("application").single()
        val activity = application.childElements("activity")
            .single { it.getAttributeNS(ANDROID_NS, "name") == ".MainActivity" }

        // An activity without its own label/theme uses the application's.
        fun effective(attribute: String): String =
            activity.getAttributeNS(ANDROID_NS, attribute).ifEmpty { application.getAttributeNS(ANDROID_NS, attribute) }

        assertEquals("@string/app_name", effective("label"))
        assertEquals("@style/Theme.MyApplication", effective("theme"))
        assertEquals("@string/app_name", application.getAttributeNS(ANDROID_NS, "label"))
        assertEquals("@style/Theme.MyApplication", application.getAttributeNS(ANDROID_NS, "theme"))
        assertEquals("true", activity.getAttributeNS(ANDROID_NS, "exported"))

        val filter = activity.childElements("intent-filter").single()
        assertEquals(
            listOf("android.intent.action.MAIN"),
            filter.childElements("action").map { it.getAttributeNS(ANDROID_NS, "name") }
        )
        assertEquals(
            listOf("android.intent.category.LAUNCHER"),
            filter.childElements("category").map { it.getAttributeNS(ANDROID_NS, "name") }
        )

        assertEquals(
            FROZEN_PERMISSIONS,
            manifest.childElements("uses-permission").map { it.getAttributeNS(ANDROID_NS, "name") }
        )
        assertEquals(
            FROZEN_FEATURES,
            manifest.childElements("uses-feature").map {
                it.getAttributeNS(ANDROID_NS, "name") to it.getAttributeNS(ANDROID_NS, "required")
            }
        )
        for ((attribute, value) in FROZEN_APPLICATION_ATTRIBUTES) {
            assertEquals("application android:$attribute", value, application.getAttributeNS(ANDROID_NS, attribute))
        }
    }

    @Test
    fun `strings xml keeps every used string unchanged and only drops strings nothing references`() {
        val strings = parseXml(sourceFile("src/main/res/values/strings.xml")).documentElement
            .childElements("string")
            .associate { it.getAttribute("name") to it.textContent }
        assertEquals(FROZEN_USED_STRINGS, strings)

        val mainDir = checkNotNull(sourceFile("src/main/AndroidManifest.xml").parentFile)
        val sources = mainDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java" || it.extension == "xml") }
            .toList()
        assertTrue("expected to scan the main source set, found ${sources.size} files", sources.size > 10)
        for (name in REMOVED_UNUSED_STRINGS) {
            assertFalse("$name must stay removed", name in strings)
            val pattern = Regex("""(R\.string\.$name\b|@string/$name\b)""")
            val referencing = sources.filter { pattern.containsMatchIn(it.readText()) }
            assertTrue("$name was removed as unused but is referenced by $referencing", referencing.isEmpty())
        }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        val FROZEN_PERMISSIONS = listOf(
            "android.permission.INTERNET",
            "android.permission.CAMERA",
            "android.permission.VIBRATE"
        )

        val FROZEN_FEATURES = listOf(
            "android.hardware.camera" to "false",
            "android.hardware.camera.front" to "false"
        )

        val FROZEN_APPLICATION_ATTRIBUTES = linkedMapOf(
            "allowBackup" to "true",
            "dataExtractionRules" to "@xml/data_extraction_rules",
            "fullBackupContent" to "@xml/backup_rules",
            "icon" to "@mipmap/ic_launcher",
            "roundIcon" to "@mipmap/ic_launcher_round",
            "supportsRtl" to "true"
        )

        /** Pre-optimization strings that no code, layout or manifest referenced. */
        val REMOVED_UNUSED_STRINGS = listOf(
            "camera_permission_required",
            "grant_camera_permission",
            "simulator_mode_label",
            "erase_all_cloud_data",
            "privacy_telemetry_card_title"
        )

        /** Frozen copy of every other pre-optimization string (XML entities decoded). */
        val FROZEN_USED_STRINGS: Map<String, String> = linkedMapOf(
            "app_name" to "Æye",
            "auth_title" to "Æye Optical Workbench",
            "auth_subtitle" to "Region-level front-camera gaze tracking, 5-point calibration + 4-point held-out validation, pitch-compensated brow-raise & command-blink triggers, and section attention analytics.",
            "sign_in_google" to "Sign in with Google",
            "privacy_notice_title" to "Biometric Telemetry & Privacy Architecture",
            "privacy_notice_body" to "Raw front-camera frames are analyzed strictly in volatile device RAM to extract Eye-ROI pupil centroids, eyebrow displacement, and 3D head Euler angles—camera images are never saved or transmitted. Derived calibration matrices and session summaries persist locally on-device by default and sync to your private Cloud Firestore vault only when Cloud Sync is explicitly enabled.",
            "nav_calibrate" to "Calibrate",
            "nav_control" to "Control Deck",
            "nav_attention" to "Attention Lab",
            "nav_cloud" to "Cloud & Privacy",
            "cloud_telemetry_opt_in_title" to "Cloud Telemetry Opt-In (Firestore Sync)",
            "cloud_telemetry_status_opted_in" to "OPTED IN: Calibrations & session summaries sync to your private Firestore vault.",
            "cloud_telemetry_status_opted_out" to "OPTED OUT (Default): Zero cloud telemetry — all data stays strictly on-device.",
            "delete_firestore_telemetry_button" to "Delete Cloud Data (Firestore)",
            "deleting_firestore_telemetry_label" to "Deleting Firestore Data…",
            "confirm_delete_cloud_dialog_title" to "Delete Cloud Telemetry from Firestore?",
            "confirm_delete_cloud_dialog_body" to "This permanently deletes all calibration profiles and attention session telemetry stored under /users/%1\$s in Cloud Firestore. Your on-device local calibration profile will remain intact.",
            "confirm_delete_cloud_action" to "Delete from Firestore",
            "cancel_action" to "Cancel",
            "tts_unavailable_warning" to "Speech output unavailable: install or enable a text-to-speech engine in system settings. Tile phrases are shown but not spoken.",
            "calibration_5_point_title" to "5-Point Guided Screen Calibration",
            "calibration_5_point_subtitle" to "Fixate on each of the 5 screen coordinates (Center, Top-Left, Top-Right, Bottom-Left, Bottom-Right) as the target ring pulses to collect binocular iris & 3D head-pose gaze data points.",
            "calibration_mode_5_point_only" to "5-Point Quick Fit",
            "calibration_mode_5_plus_4" to "5-Point Fit + 4-Point Validation",
            "start_5_point_calibration" to "Start 5-Point Calibration",
            "capture_gaze_sample_action" to "Capture Point"
        )

        /** Frozen copy of the pre-optimization overlay condition in AuthenticatedMainWorkbench. */
        fun frozenShowCalibrationOverlay(selectedTab: Int, calibrationState: CalibrationWizardState): Boolean =
            selectedTab == 0 &&
                (calibrationState.step == CalibrationFlowStep.FIT_5_POINTS ||
                    calibrationState.step == CalibrationFlowStep.VALIDATE_4_POINTS)

        /** Gradle runs unit tests from the module directory; also accept the repository root. */
        fun sourceFile(modulePath: String): File {
            val candidates = listOf(File(modulePath), File("app", modulePath))
            return candidates.firstOrNull { it.isFile }
                ?: throw AssertionError(
                    "Cannot find $modulePath from ${File(".").absolutePath} (tried $candidates)"
                )
        }

        fun parseXml(file: File) = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)

        fun Element.childElements(tag: String): List<Element> {
            val nodes = childNodes
            return (0 until nodes.length)
                .map { nodes.item(it) }
                .filterIsInstance<Element>()
                .filter { it.tagName == tag }
        }
    }
}

/**
 * The workbench now passes camera-rate states to ReadInOwnScope (and a derivedStateOf for the
 * calibration overlay) instead of reading them in the Scaffold content. These tests check that the
 * scoped reads deliver exactly what the frozen direct reads delivered, while recomposing the caller
 * only once (or only when the derived value flips). AppRootNavigation now remembers its auth-state
 * flow; the last test checks it delivers the same users with a single listener registration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActivityOptimizationComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `ReadInOwnScope delivers the same values as a direct read without recomposing its caller`() {
        val rng = Random(0x5EED_0002L)
        // A small value range so equal consecutive writes (no-ops for a snapshot state) occur too.
        val writes = IntArray(80) { rng.nextInt(5) }
        val state = mutableIntStateOf(0)
        val frozenCallerCompositions = IntArray(1)
        val scopedCallerCompositions = IntArray(1)
        val frozenSeen = mutableListOf<Int>()
        val scopedSeen = mutableListOf<Int>()

        composeRule.setContent {
            FrozenDirectReadCaller(state, frozenCallerCompositions) { frozenSeen.add(it) }
            ScopedReadCaller(state, scopedCallerCompositions) { scopedSeen.add(it) }
        }
        composeRule.waitForIdle()
        for (value in writes) {
            composeRule.runOnIdle { state.intValue = value }
            composeRule.waitForIdle()
        }

        val expected = mutableListOf(0)
        for (value in writes) if (value != expected.last()) expected.add(value)
        assertEquals(expected, frozenSeen)
        assertEquals(expected, scopedSeen)
        // The frozen caller recomposed for every change; the scoped caller composed once.
        assertEquals(expected.size, frozenCallerCompositions[0])
        assertEquals(1, scopedCallerCompositions[0])
    }

    @Test
    fun `a state read inside the ReadInOwnScope content recomposes only that content`() {
        val rng = Random(0x5EED_0003L)
        val outer = mutableIntStateOf(0)
        val inner = mutableIntStateOf(0)
        val callerCompositions = IntArray(1)
        val seen = mutableListOf<Pair<Int, Int>>()

        composeRule.setContent {
            NestedScopedReadCaller(outer, inner, callerCompositions) { seen.add(it) }
        }
        composeRule.waitForIdle()

        val expected = mutableListOf(0 to 0)
        var current = 0 to 0
        repeat(120) {
            val writeOuter = rng.nextBoolean()
            val value = rng.nextInt(4)
            composeRule.runOnIdle {
                if (writeOuter) outer.intValue = value else inner.intValue = value
            }
            composeRule.waitForIdle()
            val next = if (writeOuter) value to current.second else current.first to value
            if (next != current) {
                expected.add(next)
                current = next
            }
        }

        assertEquals(expected, seen)
        assertEquals(1, callerCompositions[0])
    }

    @Test
    fun `derived calibration-target visibility shows the same overlay states and recomposes only on flips`() {
        val rng = Random(0x5EED_0004L)
        val wizard = mutableStateOf(CalibrationWizardState())
        val frozenCallerCompositions = IntArray(1)
        val derivedCallerCompositions = IntArray(1)
        val frozenShown = BooleanArray(1)
        val derivedShown = BooleanArray(1)
        val frozenOverlayStates = mutableListOf<CalibrationWizardState>()
        val derivedOverlayStates = mutableListOf<CalibrationWizardState>()

        composeRule.setContent {
            FrozenOverlayCaller(wizard, frozenCallerCompositions, frozenShown) { frozenOverlayStates.add(it) }
            DerivedOverlayCaller(wizard, derivedCallerCompositions, derivedShown) { derivedOverlayStates.add(it) }
        }
        composeRule.waitForIdle()

        val steps = CalibrationFlowStep.entries
        var step = CalibrationFlowStep.IDLE
        var writes = 0
        var flips = 0
        for (sample in 1..300) {
            // Mostly per-sample progress updates within a step, sometimes a step change.
            val nextStep = if (rng.nextInt(10) < 8) step else steps[rng.nextInt(steps.size)]
            if (isCalibrationTargetStep(nextStep) != isCalibrationTargetStep(step)) flips++
            step = nextStep
            val next = CalibrationWizardState(
                step = step,
                currentTargetIndex = rng.nextInt(9),
                currentTargetProgress = rng.nextFloat(),
                samplesCollectedForCurrentTarget = sample
            )
            composeRule.runOnIdle { wizard.value = next }
            composeRule.waitForIdle()
            writes++
            val visible = frozenShowsOverlay(next)
            assertEquals("sample $sample", visible, frozenShown[0])
            assertEquals("sample $sample", visible, derivedShown[0])
        }

        assertEquals(frozenOverlayStates, derivedOverlayStates)
        assertTrue("the random walk must show the overlay", frozenOverlayStates.isNotEmpty())
        assertTrue("the random walk must flip the overlay", flips > 2)
        assertEquals(1 + writes, frozenCallerCompositions[0])
        assertEquals(1 + flips, derivedCallerCompositions[0])
    }

    @Test
    fun `remembered auth flow delivers the same users as a per-composition flow but registers one listener`() {
        val rng = Random(0x5EED_0005L)
        val users = arrayOf<String?>(null, "uid_a", "uid_b", "uid_c")
        val frozenSource = FakeAuthStateSource()
        val rememberedSource = FakeAuthStateSource()
        val frozenSeen = mutableListOf<String?>()
        val rememberedSeen = mutableListOf<String?>()

        composeRule.setContent {
            FrozenAuthUserCollector(frozenSource) { frozenSeen.add(it) }
            RememberedAuthUserCollector(rememberedSource) { rememberedSeen.add(it) }
        }
        composeRule.waitForIdle()

        val expected = mutableListOf<String?>(null)
        repeat(60) {
            // Repeats of the current user (no change) are part of the walk.
            val next = users[rng.nextInt(users.size)]
            composeRule.runOnIdle {
                frozenSource.setUser(next)
                rememberedSource.setUser(next)
            }
            composeRule.waitForIdle()
            if (next != expected.last()) expected.add(next)
        }

        assertTrue("the walk must change the user", expected.size > 5)
        assertEquals(expected, frozenSeen)
        assertEquals(expected, rememberedSeen)
        // The frozen pattern re-registered the listener after every user change; the remembered
        // flow keeps its one registration. Neither leaks a listener.
        assertEquals(expected.size, frozenSource.registrations)
        assertEquals(1, rememberedSource.registrations)
        assertEquals(1, frozenSource.activeListeners)
        assertEquals(1, rememberedSource.activeListeners)
    }
}

/** Robolectric tests for the non-Compose helpers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ActivityOptimizationHelpersTest {

    @Test
    fun `camera permission helper matches the frozen ContextCompat check`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadowApp = shadowOf(app)

        shadowApp.denyPermissions(Manifest.permission.CAMERA)
        assertFalse(frozenCameraPermissionGranted(app))
        assertEquals(frozenCameraPermissionGranted(app), app.isCameraPermissionGranted())

        shadowApp.grantPermissions(Manifest.permission.CAMERA)
        assertTrue(frozenCameraPermissionGranted(app))
        assertEquals(frozenCameraPermissionGranted(app), app.isCameraPermissionGranted())

        shadowApp.denyPermissions(Manifest.permission.CAMERA)
        assertEquals(frozenCameraPermissionGranted(app), app.isCameraPermissionGranted())
    }

    @Test
    fun `prefetched vault is the SharedPreferences instance LocalTelemetryStore reads and writes`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val prefetched = prefetchLocalVault(app)
        assertSame(prefetched, app.getSharedPreferences(LocalTelemetryStore.VAULT_PREFS_NAME, Context.MODE_PRIVATE))

        val store = LocalTelemetryStore(app)
        val userId = "uid_prefetch_optimization"
        assertFalse(store.isCloudSyncEnabled(userId))
        store.setCloudSyncEnabled(userId, true)
        assertTrue(store.isCloudSyncEnabled(userId))
        // The store's write is visible through the prefetched object at once: it is the same instance.
        assertTrue(prefetched.all.isNotEmpty())

        // A second prefetch (activity recreation) returns the cached instance and changes nothing.
        val before = prefetched.all.toMap()
        assertSame(prefetched, prefetchLocalVault(app))
        assertEquals(before, prefetchLocalVault(app).all)
        assertTrue(store.isCloudSyncEnabled(userId))
    }

    private fun frozenCameraPermissionGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
}

/** Frozen pre-optimization pattern: the caller reads the state, so every change recomposes it. */
@Composable
private fun FrozenDirectReadCaller(state: State<Int>, compositions: IntArray, sink: (Int) -> Unit) {
    compositions[0]++
    val value = state.value
    ValueSink(value, sink)
}

@Composable
private fun ScopedReadCaller(state: State<Int>, compositions: IntArray, sink: (Int) -> Unit) {
    compositions[0]++
    ReadInOwnScope(state) { value -> ValueSink(value, sink) }
}

/** The workbench pattern for tab 0/1: a second state read inside the content lambda. */
@Composable
private fun NestedScopedReadCaller(
    outer: State<Int>,
    inner: State<Int>,
    compositions: IntArray,
    sink: (Pair<Int, Int>) -> Unit
) {
    compositions[0]++
    ReadInOwnScope(outer) { outerValue -> PairSink(outerValue, inner.value, sink) }
}

@Composable
private fun ValueSink(value: Int, sink: (Int) -> Unit) {
    SideEffect { sink(value) }
}

@Composable
private fun PairSink(first: Int, second: Int, sink: (Pair<Int, Int>) -> Unit) {
    SideEffect { sink(first to second) }
}

@Composable
private fun WizardStateSink(state: CalibrationWizardState, sink: (CalibrationWizardState) -> Unit) {
    SideEffect { sink(state) }
}

private fun frozenShowsOverlay(state: CalibrationWizardState): Boolean =
    state.step == CalibrationFlowStep.FIT_5_POINTS || state.step == CalibrationFlowStep.VALIDATE_4_POINTS

/** Frozen pre-optimization overlay block: the step is read in the caller on every wizard update. */
@Composable
private fun FrozenOverlayCaller(
    wizard: State<CalibrationWizardState>,
    compositions: IntArray,
    shown: BooleanArray,
    overlaySink: (CalibrationWizardState) -> Unit
) {
    compositions[0]++
    val calibrationState = wizard.value
    val show = calibrationState.step == CalibrationFlowStep.FIT_5_POINTS ||
        calibrationState.step == CalibrationFlowStep.VALIDATE_4_POINTS
    SideEffect { shown[0] = show }
    if (show) {
        WizardStateSink(calibrationState, overlaySink)
    }
}

/**
 * Stand-in for FirebaseAuth's listener API: like Firebase, it calls a newly added listener at once
 * with the current user. [authStateFlow] has the same shape as `FirebaseAuth.authStateFlow()`.
 * Used on the main thread only.
 */
private class FakeAuthStateSource {
    var currentUser: String? = null
        private set
    var registrations = 0
        private set
    private val listeners = mutableListOf<(String?) -> Unit>()
    val activeListeners: Int get() = listeners.size

    fun setUser(user: String?) {
        currentUser = user
        for (listener in listeners.toList()) listener(user)
    }

    fun authStateFlow(): Flow<String?> = callbackFlow {
        val listener: (String?) -> Unit = { trySend(it) }
        registrations++
        listeners.add(listener)
        listener(currentUser)
        awaitClose { listeners.remove(listener) }
    }
}

/** Frozen pre-optimization AppRootNavigation pattern: a fresh flow on every composition. */
@Composable
private fun FrozenAuthUserCollector(source: FakeAuthStateSource, sink: (String?) -> Unit) {
    val currentUser by source.authStateFlow().collectAsStateWithLifecycle(initialValue = source.currentUser)
    UserSink(currentUser, sink)
}

/** The optimized AppRootNavigation pattern: the flow is remembered per auth instance. */
@Composable
private fun RememberedAuthUserCollector(source: FakeAuthStateSource, sink: (String?) -> Unit) {
    val authUserFlow = remember(source) { source.authStateFlow() }
    val currentUser by authUserFlow.collectAsStateWithLifecycle(initialValue = source.currentUser)
    UserSink(currentUser, sink)
}

@Composable
private fun UserSink(user: String?, sink: (String?) -> Unit) {
    SideEffect { sink(user) }
}

/** The optimized overlay block of AuthenticatedMainWorkbench. */
@Composable
private fun DerivedOverlayCaller(
    wizard: State<CalibrationWizardState>,
    compositions: IntArray,
    shown: BooleanArray,
    overlaySink: (CalibrationWizardState) -> Unit
) {
    val showCalibrationTargets by remember(wizard) {
        derivedStateOf { isCalibrationTargetStep(wizard.value.step) }
    }
    compositions[0]++
    val show = showCalibrationTargets
    SideEffect { shown[0] = show }
    if (show) {
        ReadInOwnScope(wizard) { calibrationState -> WizardStateSink(calibrationState, overlaySink) }
    }
}
