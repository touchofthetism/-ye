package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.repository.EyeTrackingRepository
import com.example.data.repository.LocalTelemetryStore
import com.example.ui.components.OpticalDiagnosticsBar
import com.example.ui.components.RegionConfidenceReticleOverlay
import com.example.ui.screens.AttentionAnalyticsScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.CalibrationStudioScreen
import com.example.ui.screens.CalibrationTargetFullscreenOverlay
import com.example.ui.screens.CloudPrivacyScreen
import com.example.ui.screens.GazeControlDeckScreen
import com.example.ui.screens.signOutUser
import com.example.ui.theme.OculaGazeTheme
import com.example.ui.viewmodel.CalibrationFlowStep
import com.example.ui.viewmodel.EyeTrackingViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight

internal fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

/**
 * Haptic used to confirm an AAC tile activation. [HapticFeedbackConstants.CONFIRM] only exists
 * from API 30; on API 24-29 the window manager ignores it and nothing vibrates, so fall back to
 * [HapticFeedbackConstants.VIRTUAL_KEY] there.
 */
@SuppressLint("InlinedApi")
internal fun tileTriggerHapticFeedbackConstant(sdkInt: Int = Build.VERSION.SDK_INT): Int =
    if (sdkInt >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.CONFIRM
    } else {
        HapticFeedbackConstants.VIRTUAL_KEY
    }

/**
 * Per-user ViewModels live in the Activity's ViewModelStore (keyed by uid), so they outlive the
 * workbench. They are dropped only on the signed-in -> signed-out transition; a direct uid switch
 * must not clear the store because the new user's ViewModel is already in it.
 */
internal fun shouldClearUserScopedViewModels(previousUid: String?, newUid: String?): Boolean =
    previousUid != null && newUid == null

/** Physical length of [px] pixels at [dpi], with the same 120 dpi floor the app always used. */
internal fun pixelsToMillimeters(px: Int, dpi: Float): Double =
    (px / dpi.coerceAtLeast(120f)) * 25.4

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The theme is always dark, so the system bars must always use light icons. The default
        // SystemBarStyle.auto follows the system light/dark mode instead (dark icons on dark).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            OculaGazeTheme {
                AppRootNavigation()
            }
        }
    }
}

@Composable
fun AppRootNavigation(auth: FirebaseAuth = Firebase.auth) {
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val user = currentUser

    // After sign-out, clear the signed-out user's ViewModel so its simulator ticker and session
    // timer stop and nothing keeps referencing the disposed workbench. This app keeps no other
    // ViewModel in the Activity store.
    val viewModelStoreOwner = LocalViewModelStoreOwner.current
    var lastSignedInUid by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(user?.uid) {
        val uid = user?.uid
        if (shouldClearUserScopedViewModels(previousUid = lastSignedInUid, newUid = uid)) {
            viewModelStoreOwner?.viewModelStore?.clear()
        }
        lastSignedInUid = uid
    }

    if (user == null) {
        AuthScreen(onAuthSuccess = {}, auth = auth)
    } else {
        AuthenticatedMainWorkbench(
            currentUserId = user.uid,
            currentUserEmail = user.email.orEmpty(),
            currentUserName = user.displayName.orEmpty(),
            auth = auth
        )
    }
}

@Composable
fun AuthenticatedMainWorkbench(
    currentUserId: String,
    currentUserEmail: String = "",
    currentUserName: String = "",
    auth: FirebaseAuth = Firebase.auth
) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val credentialManager = remember { CredentialManager.create(context) }
    val firestoreDatabaseId = stringResource(R.string.firestore_database_id)

    val viewModel: EyeTrackingViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                val localStore = LocalTelemetryStore(app)
                EyeTrackingViewModel(
                    repository = EyeTrackingRepository(db),
                    currentUserId = currentUserId,
                    localStore = localStore
                )
            }
        }
    )

    // Initialize Android TextToSpeech for AAC Hands-Free Control Deck
    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    // ttsReady: onInit(SUCCESS) arrived. ttsUnavailable: init or the last speak() failed, so the
    // AAC user must be told that tile phrases are not being spoken.
    var ttsReady by remember { mutableStateOf(false) }
    var ttsUnavailable by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        var instance: TextToSpeech? = null
        var disposed = false
        ttsReady = false
        instance = TextToSpeech(context) { status ->
            // The constructor can report ERROR synchronously, while instance is still null.
            val engine = instance
            if (!disposed) {
                if (status == TextToSpeech.SUCCESS && engine != null) {
                    if (engine.setLanguage(Locale.US) < TextToSpeech.LANG_AVAILABLE) {
                        engine.setLanguage(Locale.getDefault())
                    }
                    ttsReady = true
                    ttsUnavailable = false
                } else {
                    ttsReady = false
                    ttsUnavailable = true
                }
            }
        }
        ttsEngine = instance
        onDispose {
            disposed = true
            ttsReady = false
            ttsEngine = null
            instance?.stop()
            instance?.shutdown()
        }
    }

    // Size of the root content Box that hosts RegionConfidenceReticleOverlay. Gaze normX/normY is
    // normalized to this frame, so mm errors must be scaled by its physical size, not the panel's.
    var contentRootSizePx by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(viewModel, contentRootSizePx) {
        if (contentRootSizePx.width > 0 && contentRootSizePx.height > 0) {
            val metrics = context.resources.displayMetrics
            viewModel.setDisplayPhysicalSizeMm(
                pixelsToMillimeters(contentRootSizePx.width, metrics.xdpi),
                pixelsToMillimeters(contentRootSizePx.height, metrics.ydpi)
            )
        }
    }

    // Wire TTS synthesis + crisp haptic feedback to ViewModel tile triggers. The ViewModel invokes
    // the listener on the main thread (camera frames are handed over via submitCameraFrame).
    // Unregister on dispose: the ViewModel outlives this composition and must not keep the
    // View (and through it the Activity) or the shut-down TTS engine reachable.
    DisposableEffect(viewModel, hostView) {
        val hapticConstant = tileTriggerHapticFeedbackConstant()
        viewModel.registerTileTriggerListener { tile ->
            hostView.performHapticFeedback(hapticConstant)
            val engine = ttsEngine
            if (engine != null && ttsReady) {
                ttsUnavailable = engine.speak(
                    tile.speechPhrase,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    tile.id
                ) != TextToSpeech.SUCCESS
            }
        }
        onDispose {
            viewModel.registerTileTriggerListener { }
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            viewModel.toggleSimulatorProxyMode(false)
        } else {
            viewModel.toggleSimulatorProxyMode(true)
        }
    }

    // Automatically request front-camera permission on first launch so physical devices lock immediately.
    // Saved across configuration changes so a rotation neither re-prompts nor overrides a simulator
    // mode the user chose; it resets when the workbench leaves composition (sign-out).
    var initialCameraCheckDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val currentlyGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        hasCameraPermission = currentlyGranted
        if (!initialCameraCheckDone) {
            initialCameraCheckDone = true
            if (currentlyGranted) {
                viewModel.toggleSimulatorProxyMode(false)
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    // Permission granted from system Settings while the app was in the background: pick it up on resume.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted && !hasCameraPermission) {
            viewModel.toggleSimulatorProxyMode(false)
        }
        hasCameraPermission = granted
    }

    val hudState by viewModel.liveHudState.collectAsStateWithLifecycle()
    val calibrationState by viewModel.calibrationState.collectAsStateWithLifecycle()
    val attentionState by viewModel.attentionState.collectAsStateWithLifecycle()
    val aacTiles by viewModel.aacTilesState.collectAsStateWithLifecycle()
    val profilesState by viewModel.calibrationProfilesState.collectAsStateWithLifecycle()
    val sessionsState by viewModel.trackingSessionsState.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBannerMessage.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // Written by the reticle overlay up to every display frame. Only read inside ReadInOwnScope so
    // those writes do not recompose this whole workbench.
    val reticleRootPositionState = remember { mutableStateOf(Offset.Zero) }
    var isSigningOut by remember { mutableStateOf(false) }

    BackHandler(enabled = selectedTab != 0) {
        selectedTab = 0
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = {
                        Icon(
                            imageVector = if (selectedTab == 0) Icons.Filled.CenterFocusStrong else Icons.Outlined.CenterFocusStrong,
                            contentDescription = stringResource(R.string.nav_calibrate)
                        )
                    },
                    label = { Text(stringResource(R.string.nav_calibrate)) },
                    modifier = Modifier.testTag("nav_tab_calibrate")
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = {
                        Icon(
                            imageVector = if (selectedTab == 1) Icons.Filled.GridView else Icons.Outlined.GridView,
                            contentDescription = stringResource(R.string.nav_control)
                        )
                    },
                    label = { Text(stringResource(R.string.nav_control)) },
                    modifier = Modifier.testTag("nav_tab_control")
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = {
                        Icon(
                            imageVector = if (selectedTab == 2) Icons.Filled.Analytics else Icons.Outlined.Analytics,
                            contentDescription = stringResource(R.string.nav_attention)
                        )
                    },
                    label = { Text(stringResource(R.string.nav_attention)) },
                    modifier = Modifier.testTag("nav_tab_attention")
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = {
                        Icon(
                            imageVector = if (selectedTab == 3) Icons.Filled.CloudSync else Icons.Outlined.CloudSync,
                            contentDescription = stringResource(R.string.nav_cloud)
                        )
                    },
                    label = { Text(stringResource(R.string.nav_cloud)) },
                    modifier = Modifier.testTag("nav_tab_cloud")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .onSizeChanged { contentRootSizePx = it }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                OpticalDiagnosticsBar(
                    hudState = hudState,
                    hasCameraPermission = hasCameraPermission,
                    onRequestCameraPermission = {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onToggleSimulator = { viewModel.toggleSimulatorProxyMode(it) },
                    onCycleOverlayMode = { viewModel.cycleOverlayVisualMode() },
                    onCycleResolutionTier = { viewModel.cycleAnalysisResolutionTier() },
                    onSimulatorDragTarget = { nx, ny ->
                        viewModel.updateSimulatorGazeTarget(nx, ny)
                    },
                    onSimulateCommandBlink = {
                        viewModel.simulateIntentionalCommandBlink()
                    },
                    onSimulateBrowRaise = {
                        viewModel.simulateBrowRaiseCommand()
                    },
                    onTriggerRecalibration = {
                        viewModel.dismissRecalibrateNudge()
                        selectedTab = 0
                        viewModel.startCalibrationAndValidationWizard()
                    },
                    onDismissRecalibrateNudge = {
                        viewModel.dismissRecalibrateNudge()
                    },
                    onFeatureFrame = { frame ->
                        // Called on the CameraX analysis thread; the ViewModel hops to main.
                        viewModel.submitCameraFrame(frame)
                    }
                )

                // Always-visible Privacy Settings Toggle (Cloud Telemetry Opt-In/Out) & Firestore Data Deletion Bar
                // when on Calibrate, Control Deck, or Attention Lab tabs (Tab 3 renders the expanded CloudPrivacyScreen card)
                if (selectedTab != 3) {
                    InlineCloudPrivacyBar(
                        cloudSyncEnabled = hudState.cloudSyncEnabled,
                        isDeletingCloudData = hudState.isDeletingCloudData,
                        currentUserId = currentUserId,
                        onToggleCloudSync = { viewModel.toggleCloudSync(it) },
                        onConfirmDeleteCloud = { viewModel.deleteFirestoreCloudTelemetry() }
                    )
                }

                if (ttsUnavailable) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.18f))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("tts_unavailable_banner"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.tts_unavailable_warning),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                if (statusBanner != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f))
                            .clickable { viewModel.dismissStatusBanner() }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("status_banner_row"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = statusBanner!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Dismiss",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (selectedTab) {
                        0 -> CalibrationStudioScreen(
                            calibrationState = calibrationState,
                            hudState = hudState,
                            onStartWizard = { viewModel.startCalibrationAndValidationWizard() },
                            onCancelWizard = { viewModel.cancelCalibrationWizard() },
                            onUpdateProfileName = { viewModel.updateDraftProfileName(it) },
                            onSaveProfile = { viewModel.saveCompletedCalibrationProfile() },
                            onSnapSimulatorToCurrentTarget = { tx, ty ->
                                viewModel.updateSimulatorGazeTarget(tx, ty)
                            },
                            onToggleIncludeValidation = { includeVal ->
                                viewModel.setIncludeHeldOutValidation(includeVal)
                            },
                            onCaptureManualSamples = {
                                viewModel.captureManualSamplesForCurrentTarget()
                            }
                        )
                        1 -> ReadInOwnScope(reticleRootPositionState) { reticleRootPosition ->
                            GazeControlDeckScreen(
                                hudState = hudState,
                                reticleRootPosition = reticleRootPosition,
                                tiles = aacTiles,
                                onUpdateFocusedTile = { viewModel.updateFocusedTile(it) },
                                onManualTileClick = { viewModel.fireTileAction(it, "Tap") },
                                onChangeDwellDurationMs = { viewModel.setDwellTriggerDuration(it) },
                                onChangeDwellSensitivityMultiplier = { viewModel.setDwellSensitivityMultiplier(it) },
                                onSelectDwellPreset = { viewModel.setDwellSpeedPreset(it) },
                                onChangeTriggerMode = { viewModel.setTriggerMode(it) },
                                onCalibrateNeutralBrow = { viewModel.calibrateNeutralBrowBaseline() },
                                onCalibrateRaisedBrow = { viewModel.calibrateRaisedBrowPeak() },
                                onEditTilePhrase = { id, title, phrase ->
                                    viewModel.updateAacTilePhrase(id, title, phrase)
                                },
                                onSelectPosture = { viewModel.setPostureProfile(it) },
                                onSelectFilterMode = { viewModel.setGazeFilterMode(it) },
                                onRunBenchmark = { viewModel.runBenchmarkSuite() }
                            )
                        }
                        2 -> {
                            // Summaries depend only on attentionState; do not rebuild them on
                            // unrelated recompositions.
                            val sectionSummaries = remember(viewModel, attentionState) {
                                viewModel.computeSectionSummariesForDisplay()
                            }
                            // A recording keeps running on other tabs; once the passage leaves
                            // composition no section may be credited with dwell.
                            DisposableEffect(viewModel) {
                                onDispose { viewModel.detachReadingSectionLayout() }
                            }
                            AttentionAnalyticsScreen(
                                attentionState = attentionState,
                                // Provider: read only inside the screen's hit-test coroutine.
                                reticleRootPosition = { reticleRootPositionState.value },
                                passageSections = viewModel.readingPassageSections,
                                sectionSummaries = sectionSummaries,
                                onUpdateLayoutHitSection = { viewModel.updateActiveReadingSectionFromLayout(it) },
                                onClearReplay = { viewModel.clearSessionReplay() },
                                onStartRecording = { viewModel.startAttentionRecording() },
                                onStopAndSaveRecording = { viewModel.stopAndSaveAttentionSession() }
                            )
                        }
                        3 -> CloudPrivacyScreen(
                            hudState = hudState,
                            currentUserId = currentUserId,
                            currentUserEmail = currentUserEmail,
                            currentUserName = currentUserName,
                            firestoreDatabaseId = firestoreDatabaseId,
                            profilesState = profilesState,
                            sessionsState = sessionsState,
                            onToggleCloudSync = { viewModel.toggleCloudSync(it) },
                            onActivateProfile = { viewModel.activateSavedProfile(it) },
                            onDeleteProfile = { viewModel.deleteCalibrationProfile(it) },
                            onSelectSessionReplay = { session ->
                                viewModel.selectSessionForReplay(session)
                                selectedTab = 2
                            },
                            onDeleteSession = { viewModel.deleteTrackingSession(it) },
                            onDeleteFirestoreOnly = { viewModel.deleteFirestoreCloudTelemetry() },
                            onEraseAllTelemetry = { viewModel.eraseAllCloudTelemetry() },
                            onSignOut = {
                                // Run the credential clear on the Activity's lifecycle scope: the
                                // sign-out removes this composition, which would cancel a scope
                                // remembered here while clearCredentialState is still in flight.
                                if (!isSigningOut) {
                                    isSigningOut = true
                                    signOutUser(
                                        context = context,
                                        credentialManager = credentialManager,
                                        onSignOutComplete = { isSigningOut = false },
                                        scope = lifecycleOwner.lifecycleScope,
                                        auth = auth
                                    )
                                }
                            },
                            onChangeDwellDurationMs = { viewModel.setDwellTriggerDuration(it) },
                            onChangeDwellSensitivityMultiplier = { viewModel.setDwellSensitivityMultiplier(it) },
                            onSelectDwellPreset = { viewModel.setDwellSpeedPreset(it) },
                            isAttentionRecording = attentionState.isRecording
                        )
                    }
                }
            }

            // Calibration fixation targets are drawn in the same frame as the reticle overlay
            // (this root Box), which is the frame gaze normX/normY is normalized to.
            if (selectedTab == 0 &&
                (calibrationState.step == CalibrationFlowStep.FIT_5_POINTS ||
                    calibrationState.step == CalibrationFlowStep.VALIDATE_4_POINTS)
            ) {
                CalibrationTargetFullscreenOverlay(
                    calibrationState = calibrationState,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Live 120Hz Region-Level Confidence Circle Reticle Overlay reporting root coordinates
            RegionConfidenceReticleOverlay(
                hudState = hudState,
                onReticleRootPositionChanged = { reticleRootPositionState.value = it }
            )
        }
    }
}

/**
 * Reads [state] inside its own restart scope, so frequent writes (the reticle position is written
 * up to every display frame) recompose only [content], not the caller.
 */
@Composable
private fun <T> ReadInOwnScope(state: State<T>, content: @Composable (T) -> Unit) {
    content(state.value)
}

/**
 * Always-visible cloud telemetry opt-in switch plus the "Delete Cloud" shortcut. Deleting the
 * Firestore copy is irreversible, so the button only opens a confirmation dialog;
 * [onConfirmDeleteCloud] runs once the user confirms.
 */
@Composable
internal fun InlineCloudPrivacyBar(
    cloudSyncEnabled: Boolean,
    isDeletingCloudData: Boolean,
    currentUserId: String,
    onToggleCloudSync: (Boolean) -> Unit,
    onConfirmDeleteCloud: () -> Unit,
    modifier: Modifier = Modifier
) {
    var confirmDeleteCloud by remember { mutableStateOf(false) }
    val cloudSyncLabel = stringResource(R.string.cloud_telemetry_opt_in_title)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("inline_privacy_telemetry_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = if (cloudSyncEnabled) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                contentDescription = cloudSyncLabel,
                tint = if (cloudSyncEnabled) {
                    MaterialTheme.colorScheme.secondary
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = if (cloudSyncEnabled) {
                        "Cloud Telemetry: OPTED IN"
                    } else {
                        "Cloud Telemetry: OPTED OUT (Local)"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (cloudSyncEnabled) {
                        "Syncing to Firestore (/users/${currentUserId.take(6)}…)"
                    } else {
                        "Zero cloud writes • On-device only"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Switch(
                checked = cloudSyncEnabled,
                onCheckedChange = onToggleCloudSync,
                modifier = Modifier
                    .semantics { contentDescription = cloudSyncLabel }
                    .testTag("cloud_sync_toggle")
            )
            OutlinedButton(
                onClick = { confirmDeleteCloud = true },
                enabled = !isDeletingCloudData,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("delete_firestore_data_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteForever,
                    contentDescription = stringResource(R.string.delete_firestore_telemetry_button),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isDeletingCloudData) "Deleting…" else "Delete Cloud",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (confirmDeleteCloud) {
        AlertDialog(
            onDismissRequest = { confirmDeleteCloud = false },
            title = { Text(stringResource(R.string.confirm_delete_cloud_dialog_title)) },
            text = { Text(stringResource(R.string.confirm_delete_cloud_dialog_body, currentUserId)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteCloud = false
                        onConfirmDeleteCloud()
                    },
                    modifier = Modifier.testTag("confirm_delete_cloud_button")
                ) {
                    Text(
                        text = stringResource(R.string.confirm_delete_cloud_action),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmDeleteCloud = false },
                    modifier = Modifier.testTag("cancel_delete_cloud_button")
                ) {
                    Text(stringResource(R.string.cancel_action))
                }
            },
            modifier = Modifier.testTag("confirm_delete_cloud_dialog")
        )
    }
}
