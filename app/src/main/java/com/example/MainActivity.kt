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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.ui.text.font.FontWeight

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
    val coroutineScope = rememberCoroutineScope()
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
        if (granted) {
            viewModel.toggleSimulatorProxyMode(false)
        } else {
            viewModel.toggleSimulatorProxyMode(true)
        }
    }

    // Automatically request front-camera permission on first launch so physical devices lock immediately
    LaunchedEffect(Unit) {
        val currentlyGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        hasCameraPermission = currentlyGranted
        if (currentlyGranted) {
            viewModel.toggleSimulatorProxyMode(false)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
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
                        viewModel.processIncomingFeatureFrame(frame)
                    }
                )

                // Always-visible Privacy Settings Toggle (Cloud Telemetry Opt-In/Out) & Firestore Data Deletion Bar
                // when on Calibrate, Control Deck, or Attention Lab tabs (Tab 3 renders the expanded CloudPrivacyScreen card)
                if (selectedTab != 3) {
                    Row(
                        modifier = Modifier
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
                                imageVector = if (hudState.cloudSyncEnabled) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                                contentDescription = stringResource(R.string.cloud_telemetry_opt_in_title),
                                tint = if (hudState.cloudSyncEnabled) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                },
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = if (hudState.cloudSyncEnabled) {
                                        "Cloud Telemetry: OPTED IN"
                                    } else {
                                        "Cloud Telemetry: OPTED OUT (Local)"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (hudState.cloudSyncEnabled) {
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
                                checked = hudState.cloudSyncEnabled,
                                onCheckedChange = { viewModel.toggleCloudSync(it) },
                                modifier = Modifier.testTag("cloud_sync_toggle")
                            )
                            OutlinedButton(
                                onClick = { viewModel.deleteFirestoreCloudTelemetry() },
                                enabled = !hudState.isDeletingCloudData,
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
                                    text = if (hudState.isDeletingCloudData) "Deleting…" else "Delete Cloud",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
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
                        1 -> GazeControlDeckScreen(
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
                                signOutUser(
                                    context = context,
                                    credentialManager = credentialManager,
                                    onSignOutComplete = {},
                                    scope = coroutineScope,
                                    auth = auth
                                )
                            },
                            onChangeDwellDurationMs = { viewModel.setDwellTriggerDuration(it) },
                            onChangeDwellSensitivityMultiplier = { viewModel.setDwellSensitivityMultiplier(it) },
                            onSelectDwellPreset = { viewModel.setDwellSpeedPreset(it) }
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
