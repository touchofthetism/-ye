package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.example.ui.screens.CloudPrivacyScreen
import com.example.ui.screens.GazeControlDeckScreen
import com.example.ui.screens.signOutUser
import com.example.ui.theme.OculaGazeTheme
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

internal fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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

    if (user == null) {
        AuthScreen(onAuthSuccess = {})
    } else {
        AuthenticatedMainWorkbench(currentUserId = user.uid)
    }
}

@Composable
fun AuthenticatedMainWorkbench(currentUserId: String) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }

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
    DisposableEffect(context) {
        var instance: TextToSpeech? = null
        instance = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                instance?.language = Locale.US
            }
        }
        ttsEngine = instance
        onDispose {
            instance?.stop()
            instance?.shutdown()
        }
    }

    // Wire TTS synthesis + crisp haptic feedback to ViewModel tile triggers
    LaunchedEffect(viewModel, ttsEngine, hostView) {
        val metrics = context.resources.displayMetrics
        val widthMm = (metrics.widthPixels / metrics.xdpi.coerceAtLeast(120f)) * 25.4
        val heightMm = (metrics.heightPixels / metrics.ydpi.coerceAtLeast(120f)) * 25.4
        viewModel.setDisplayPhysicalSizeMm(widthMm, heightMm)

        viewModel.registerTileTriggerListener { tile ->
            hostView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            ttsEngine?.speak(tile.speechPhrase, TextToSpeech.QUEUE_FLUSH, null, tile.id)
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
        if (!granted) {
            viewModel.toggleSimulatorProxyMode(true)
        }
    }

    val hudState by viewModel.liveHudState.collectAsStateWithLifecycle()
    val calibrationState by viewModel.calibrationState.collectAsStateWithLifecycle()
    val attentionState by viewModel.attentionState.collectAsStateWithLifecycle()
    val aacTiles by viewModel.aacTilesState.collectAsStateWithLifecycle()
    val profilesState by viewModel.calibrationProfilesState.collectAsStateWithLifecycle()
    val sessionsState by viewModel.trackingSessionsState.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBannerMessage.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }
    var reticleRootPosition by remember { mutableStateOf(Offset.Zero) }

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
                        viewModel.processIncomingFeatureFrame(frame)
                    }
                )

                if (statusBanner != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f))
                            .clickable { viewModel.dismissStatusBanner() }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
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
                            }
                        )
                        1 -> GazeControlDeckScreen(
                            hudState = hudState,
                            reticleRootPosition = reticleRootPosition,
                            tiles = aacTiles,
                            onUpdateFocusedTile = { viewModel.updateFocusedTile(it) },
                            onManualTileClick = { viewModel.fireTileAction(it, "Tap") },
                            onChangeDwellDurationMs = { viewModel.setDwellTriggerDuration(it) },
                            onChangeTriggerMode = { viewModel.setTriggerMode(it) },
                            onCalibrateNeutralBrow = { viewModel.calibrateNeutralBrowBaseline() },
                            onCalibrateRaisedBrow = { viewModel.calibrateRaisedBrowPeak() },
                            onEditTilePhrase = { id, title, phrase ->
                                viewModel.updateAacTilePhrase(id, title, phrase)
                            }
                        )
                        2 -> AttentionAnalyticsScreen(
                            attentionState = attentionState,
                            reticleRootPosition = reticleRootPosition,
                            passageSections = viewModel.readingPassageSections,
                            sectionSummaries = viewModel.computeSectionSummariesForDisplay(),
                            onUpdateLayoutHitSection = { viewModel.updateActiveReadingSectionFromLayout(it) },
                            onClearReplay = { viewModel.clearSessionReplay() },
                            onStartRecording = { viewModel.startAttentionRecording() },
                            onStopAndSaveRecording = { viewModel.stopAndSaveAttentionSession() }
                        )
                        3 -> CloudPrivacyScreen(
                            hudState = hudState,
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
                            onEraseAllTelemetry = { viewModel.eraseAllCloudTelemetry() },
                            onSignOut = {
                                signOutUser(
                                    context = context,
                                    credentialManager = credentialManager,
                                    onSignOutComplete = {},
                                    scope = coroutineScope
                                )
                            }
                        )
                    }
                }
            }

            // Live 120Hz Region-Level Confidence Circle Reticle Overlay reporting root coordinates
            RegionConfidenceReticleOverlay(
                hudState = hudState,
                onReticleRootPositionChanged = { reticleRootPosition = it }
            )
        }
    }
}
