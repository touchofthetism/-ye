# Æye (OculaGaze) — Current UI Inventory (ground truth for the redesign)

Source read in full: `app/src/main/java/com/example/MainActivity.kt` (896 lines), everything under
`app/src/main/java/com/example/ui/` (components, screens, theme, viewmodel), `res/values/strings.xml`,
`res/values/firebase_applet_config.xml`, `res/values/themes.xml`, the data-model enums the UI prints
(`data/model/GazeModels.kt`), `domain/EnrollmentScan.kt`, and the UI-referencing tests under `app/src/test`.
Nothing in the app was edited.

Legend for VM references: `hud.*` = `LiveGazeHudState` field, `cal.*` = `CalibrationWizardState`,
`att.*` = `AttentionSessionLiveState`, `enr.*` = `EnrollmentScanState`, `VM.fn()` = `EyeTrackingViewModel` public function.

---

## 1. Screens and overlays (composables, parameters, nesting)

### 1.1 Shell — `MainActivity.kt`

| Composable | Parameters | What it does |
|---|---|---|
| `MainActivity` (Activity) | — | `enableEdgeToEdge` with dark system bars (theme is dark-only), prefetches the local vault, `setContent { OculaGazeTheme { AppRootNavigation() } }`. |
| `AppRootNavigation(auth: FirebaseAuth = Firebase.auth)` | — | Collects `auth.authStateFlow()`; `user == null` → `AuthScreen`, else `AuthenticatedMainWorkbench`. Clears the Activity ViewModelStore only on signed-in → signed-out (`shouldClearUserScopedViewModels`). |
| `AuthenticatedMainWorkbench(currentUserId, currentUserEmail = "", currentUserName = "", auth)` | — | Creates `EyeTrackingViewModel` keyed by uid (factory with `EyeTrackingRepository`, `LocalTelemetryStore`). Owns: TTS engine (`TextToSpeech`, `Locale.US` → default), haptic + TTS tile-trigger listener (`VM.registerTileTriggerListener`), camera-permission launcher (auto-request on first launch; denial → `VM.toggleSimulatorProxyMode(true)`; re-check on `ON_RESUME`), `VM.setDisplayPhysicalSizeMm` from the content Box size, `selectedTab` (rememberSaveable, default **0 = Calibrate**), `BackHandler` (any tab → tab 0), `isSigningOut`. |
| └ `Scaffold.bottomBar = NavigationBar` | 4 × `NavigationBarItem` | tags `nav_tab_calibrate`, `nav_tab_control`, `nav_tab_attention`, `nav_tab_cloud`; icons CenterFocusStrong / GridView / Analytics / CloudSync (filled when selected, outlined otherwise); labels from `nav_*` strings. |
| └ content `Box` (root gaze frame) → `Column` | — | In order, top to bottom, on **every** tab: `OpticalDiagnosticsBar` → `InlineCloudPrivacyBar` (tabs 0–2 only) → TTS-unavailable banner (`tts_unavailable_banner`) → status banner row (`status_banner_row`, tap = `VM.dismissStatusBanner()`, trailing "Dismiss" text) → `Box(weight 1f)` with the tab screen. |
| └ overlays in the root Box (above the Column) | — | `CalibrationTargetFullscreenOverlay` (tab 0 and `cal.step` ∈ {FIT_5_POINTS, VALIDATE_4_POINTS}); `EnrollmentScanOverlayHost` (tab 0 and `enr.phase` active); `RegionConfidenceReticleOverlay` (always, top-most inside the Scaffold content; not over the NavigationBar or dialogs). |
| `ReadInOwnScope(state: State<T>, content)` | — | Recomposition isolation helper (per-frame HUD / reticle reads). |
| `EnrollmentScanOverlayHost(enrollmentState: State<EnrollmentScanState>, onCancel, onSkipBrow, modifier)` | — | Hosts `EnrollmentScanOverlay`; cancels the scan via `onCancel` if it leaves composition while a phase is active (tab switch). |
| `InlineCloudPrivacyBar(cloudSyncEnabled, isDeletingCloudData, currentUserId, onToggleCloudSync, onConfirmDeleteCloud, modifier)` | tag `inline_privacy_telemetry_bar` | Cloud icon (CloudDone/CloudOff), "Cloud Telemetry: OPTED IN" / "OPTED OUT (Local)", sub-line "Syncing to Firestore (/users/xxxxxx…)" / "Zero cloud writes • On-device only", `Switch` (`cloud_sync_toggle`, contentDescription = `cloud_telemetry_opt_in_title`), `OutlinedButton` "Delete Cloud"/"Deleting…" (`delete_firestore_data_button`, 48 dp) → `AlertDialog` (`confirm_delete_cloud_dialog`, buttons `confirm_delete_cloud_button` / `cancel_delete_cloud_button`). |

Tab → screen wiring (all callbacks map 1:1 to VM functions, see §3):

| Tab | Screen | Extra wiring |
|---|---|---|
| 0 Calibrate | `CalibrationStudioScreen` | `onSnapSimulatorToCurrentTarget` → `VM.updateSimulatorGazeTarget`; enrollment state/baseline passed in. |
| 1 Control Deck | `GazeControlDeckScreen` | receives `reticleRootPosition` (from the reticle overlay), `aacTiles`, `gazeFrameRootBounds` (root Box bounds). |
| 2 Attention Lab | `AttentionAnalyticsScreen` | `DisposableEffect` → `VM.detachReadingSectionLayout()`; `sectionSummaries = remember(attentionState) { VM.computeSectionSummariesForDisplay() }`; `passageSections = VM.readingPassageSections`. |
| 3 Cloud & Privacy | `CloudPrivacyScreen` | `onSelectSessionReplay` also sets `selectedTab = 2`; `onSignOut` → `signOutUser(...)` on the Activity lifecycle scope. |

### 1.2 `ui/screens/AuthScreen.kt`

| Composable / function | Parameters | Content |
|---|---|---|
| `AuthScreen(onAuthSuccess, auth)` | — | `LaunchedEffect` runs `attemptAutoSignIn` (button disabled meanwhile). Scrollable centred Column (max 520 dp): `AuthIntro()`, error `Card` (error tint, bodyMedium), `Button` "Sign in with Google" (`google_sign_in_button`, 54 dp, spinner while loading, VerifiedUser icon). |
| `AuthIntro()` (private) | — | Pill badge "PIXEL 10 PRO XL • OPTICAL GAZE WORKBENCH" (RemoveRedEye icon), `auth_title` (headlineLarge), `auth_subtitle` (bodyMedium), hero `Image` `ic_optics_hero_illustration` (155 dp), feature `Card` with 3 × `FeatureRow`: "5-Point Fit + 4-Point Held-Out Validation", "Schmitt-Trigger Blink & Section Dwell Lab" ("Disambiguates 80–349ms natural blinks from 350–800ms command blinks…"), privacy notice (`privacy_notice_title` + `privacy_notice_body` + "User-scoped Firestore collections (/users/{uid}/calibrations & /users/{uid}/sessions on DB <firestore_database_id>)"). |
| `FeatureRow(icon, iconTileBrush, title, desc)` (private) | — | 36 dp gradient icon tile + titleMedium + bodyMedium. |
| `attemptAutoSignIn(context, credentialManager, onAuthSuccess, onUnauthenticated, scope, auth)` | — | Credential Manager `GetGoogleIdOption` (authorized accounts, auto-select); suppressed by `AutoSignInGate.suppressedAfterSignOut`. |
| `onGoogleSignInClicked(context, credentialManager, onAuthSuccess, onAuthError, scope, onAuthCancelled, auth)` | — | `GetSignInWithGoogleOption`; maps exceptions to user-facing error strings (no account / Play services / interrupted / generic). |
| `signOutUser(context, credentialManager, onSignOutComplete, scope, auth)` | — | Clears credential state (3 s timeout) then `auth.signOut()`. |

### 1.3 `ui/components/OpticalDiagnosticsBar.kt` (the persistent HUD + camera + reticle)

| Composable | Parameters | Content |
|---|---|---|
| `OpticalDiagnosticsBar(hudState, hasCameraPermission, onRequestCameraPermission, onToggleSimulator, onCycleOverlayMode, onCycleResolutionTier, onSimulatorDragTarget, onSimulateCommandBlink, onSimulateBrowRaise, onTriggerRecalibration, onDismissRecalibrateNudge, onFeatureFrame)` | — | `Card` (surface, 14 dp radius, 12 dp padding) containing: header row (status dot, status label, resolution badge, single-eye badge, LOW FPS badge, telemetry line | chip row: Cam Full/Mini, overlay mode, Sim Proxy — `weight(1.1f)` + `horizontalScroll`), camera row (preview box 116×150 dp or 56×72 dp with `MiniFrontCameraPreview` + `MlKitFaceOverlayCanvas`, pipeline label, frame size line, iris centroid line, head Euler line, eye-open/brow line, "Switch to Live Front Camera") **or** permission-denied row ("Grant front-camera access…" + "Enable Camera"), fixed 2-line optical warning slot, `AnimatedVisibility` recalibrate nudge, blink label + gesture counters row, 5 dp hold `LinearProgressIndicator` (alpha 0 when idle), `AnimatedVisibility` simulator pad (label, "Brow (400ms)", "Blink (460ms)", 74 dp touchpad canvas). Local state: `showSimulatorPad`, `expandCameraPreview` (default true). Debounce: warnings 1 s on / 2 s off via `OpticalWarningLatch` + `rememberDebouncedFlag`. |
| `MiniFrontCameraPreview(isPerformanceMode, resolutionTier, isCameraActive, onFeatureFrame, modifier)` (private) | — | **Owns the CameraX binding** (`ProcessCameraProvider`, `Preview` 640×480 FILL_CENTER, `ImageAnalysis` at `resolutionTier` size, `CameraGazeAnalyzerEngine`, `MainThreadFrameConflator` → `onFeatureFrame` → `VM.submitCameraFrame`). Unbinds while the simulator proxy is on; never binds on emulator; "No front camera" label if none. Any redesign that removes or hides the HUD must relocate this. |
| `MlKitFaceOverlayCanvas(feature, isSimulatorActive, browRaiseThreshold, modifier)` (private) | — | Over the preview: synthetic grid (emulator/sim), dashed amber oval when no face, emerald face-box corner brackets, eye rings, cyan pupil crosshairs, brow bars (amber when ≥ brow trigger threshold). |
| `RegionConfidenceReticleOverlay(hudState, onReticleRootPositionChanged, modifier)` | tag `realtime_gaze_canvas_overlay` | Display-rate interpolated cursor. Layer 1 heatmap trail (`hud.liveTrailPoints`, radial splats cyan→emerald→amber→crimson + dashed comet line) when mode ∈ {RETICLE_AND_HEATMAP, HEATMAP_ONLY}; live aura. Layer 2 cursor when mode ∈ {RETICLE_AND_HEATMAP, RETICLE_ONLY}: uncertainty ellipse from `reticleUncertaintyRadiiPx(hud.activeErrorRadiusPct …)` (20 % when uncalibrated, ≥ 28 dp, ≤ half axis), N/S/E/W ticks, 360° progress arc = max(`hud.focusedTileDwellProgress`, `hud.blinkHoldProgress`, `hud.browHoldProgress`), centre dot, floating "xx%, yy%" coordinate pill (10 sp). Reports root position for deck / passage hit-testing. |
| Pure helpers (internal/public) | — | `browRaiseTriggerThreshold`, `eyeOpenBrowLine`, `reticleUncertaintyRadiiPx`, `gazeTelemetryLine`, `analyzerStatusLabel`, `cameraPipelineLabel`, `glareWarningSentence`, `lowLightWarningSentence`, `opticalWarningText`, `OpticalWarningLatch`, `MainThreadFrameConflator`, `UNCALIBRATED_RETICLE_ERROR_PCT = 20f`. |

### 1.4 `ui/screens/CalibrationStudioScreen.kt` (tab 0)

| Composable | Parameters | Content |
|---|---|---|
| `CalibrationStudioScreen(calibrationState, hudState, onStartWizard, onCancelWizard, onUpdateProfileName, onSaveProfile, onSnapSimulatorToCurrentTarget, onToggleIncludeValidation, onCaptureManualSamples, enrollmentState, enrollmentBaseline, onStartEnrollment, onClearEnrollment)` | — | Switches on `cal.step`: IDLE → `CalibrationIdleOverview`; FIT/VALIDATE → `FivePointCalibrationScreen`; COMPLETED_SUMMARY → `CalibrationValidationSummaryCard`. `DisposableEffect` cancels the wizard if the screen leaves composition mid-collection. |
| `CalibrationIdleOverview(...)` (private) | — | Scroll Column, 16 dp padding, 14 dp spacing: `EnrollmentCard`, `CalibrationOverviewCard` (via `CalibrationOverviewCardForHud`), `LiveFeatureTelemetryCard`. |
| `EnrollmentCard(baseline, scanPhase, scanResult, scanFailure, onStartEnrollment, onClearEnrollment)` (private) | tag `enrollment_card` | Title "Face & Eye Enrollment" (Face icon, titleLarge); status (`enrollment_status_text`): "Scanning…" / "Enrolled <MMM d, yyyy HH:mm>" / "Not enrolled"; outcome (`enrollment_outcome_text`): `enrollmentCompleteSummary(...)` or "Scan failed: <reason>"; without baseline: explanation (`enrollment_explanation_text`) + "Scan my face & eyes" (`start_enrollment_button`); with baseline: 6 summary lines, "Warnings: …" (`enrollment_warnings_text`), privacy line, "Re-scan" (`rescan_enrollment_button`) + "Clear" (`clear_enrollment_button`, error tint, **no confirmation**). Buttons disabled while scanning. |
| `CalibrationOverviewCard(includeHeldOutValidation, meanErrPct, worstErrPct, meanErrMm, hasActiveCalibration, activeProfileName, onStartWizard, onToggleIncludeValidation)` (private) | tag `calibration_overview_card` | Title `calibration_5_point_title` + subtitle; mode `FilterChip`s (`mode_5_point_only_chip`, `mode_5_plus_4_validation_chip`); 132 dp preview map canvas (`five_point_preview_map`) + "Sequence: P1(0.50,0.50) → P2(0.14,0.16) → …"; two `MetricBadgeCard`s "MEAN SCREEN ERR" / "WORST QUADRANT" (value ±x.x % / "Not validated" / "Not calibrated"; sub "~x mm (approximate)" / "mm not measured" / "5-point fit only, run 5+4 validation to measure" / "Run calibration to measure" / "Profile: <name>" / "Default matrix, not validated"); 52 dp `Button` (`start_calibration_button`) "Start 5-Point Calibration" or "Launch 5-Point Fit + 4-Point Validation". |
| `LiveFeatureTelemetryCard(hudState)` (private) | — | Title "Live 6D Feature Vector & Pixel 10 Pro XL Telemetry"; `RawFeatureVectorLine` ("f_raw = [1.0, gx=…, gy=…, yaw=…°, pitch=…°, roll=…°]"); `EyeTelemetryLine` (`calibration_eye_telemetry_text`: "Eye Confidence: Left n% • Right n% | IOD: x px | Eye Open: 0.xx | Tier: <label> | Luma: n/255 (LOW-LIGHT DEGRADED)"; "--" placeholders without a face). |
| `FivePointCalibrationScreen(calibrationState, hudState, onCancelWizard, onSnapSimulatorToCurrentTarget, onCaptureManualSamples)` → `FivePointCalibrationContent` | tag `five_point_calibration_screen` | Guidance `Card` (`calibration_guidance_card`): `CalibrationTargetHeading` ("Fit 2/5 • Top-Left • Screen Coord (0.14, 0.16)", `active_calibration_target_label`, + `guidancePrompt`), `CalibrationSampleCounter` (`calibration_sample_counter_text`), 5 × `CalibrationStepPill` (`calibration_5_point_stepper`, `calibration_step_pill_i`: "✓ P1"/"P1", "(0.50,0.50)", "n/18"), `CalibrationWizardActions` ("Capture Point (<short>)" `capture_gaze_point_button`; "Lock Sim Here" `snap_sim_to_target_button` enabled only in sim; "Cancel" `cancel_calibration_button`). Below: label `calibration_progress_map_label` ("Progress map (miniature of the screen, not a target)…") + `CalibrationProgressMap` (140 dp `BoxWithConstraints`, tag `five_point_calibration_canvas`, 5 invisible 48 dp accessible nodes `calibration_point_target_i`; only the current one captures). |
| `CalibrationTargetFullscreenOverlay(calibrationState, modifier)` | tag `calibration_fullscreen_target_overlay` | Draws the current fixation target in the root frame: pulsing halo (38 dp base), 26 dp ring, 64 dp progress arc (`cal.currentTargetProgress`), 6.5 dp dot; primary for fit points, secondary for validation points. No pointer input. |
| `CalibrationValidationSummaryCard(calibrationState, onUpdateProfileName, onSaveProfile, onRecalibrate)` (private) | tag `calibration_summary_screen` | `CalibrationSummaryReport` + `OutlinedTextField` "Calibration Profile Name" (`profile_name_input`) + "Retry" (`recalibrate_button`) / "Save & Apply Profile" (`save_calibration_button`), both 48 dp. |
| `CalibrationSummaryReport(includeHeldOutValidation, metricsAreHeldOut, validationMeanErrorPct, validationMaxErrorPct, validationMeanErrorMm, totalCollectedSamples, pointSampleCounts, recommendedGridLabel, fittedMatrix)` (private) | — | Title "5-Point Fit + 4-Point Validation Report" / "5-Point Screen Calibration Complete"; badges "MEAN SCREEN ERROR"/"WORST QUADRANT" (held-out) or "FIT RESIDUAL (IN-SAMPLE)"/"WORST FIT POINT" (quick fit), values or "Not measured"; `calibration_in_sample_caveat` text; "Collected Gaze Samples by Screen Coordinate:" 5 rows; "Recommended Layout: <recommendedGridLabel>"; matrix text "W_x = […] / W_y = […]". |
| `MetricBadgeCard(label, value, sub)` (private) | — | labelSmall label, headlineMedium value (primary), labelSmall sub. |
| Helpers | — | `isCalibrationCollectingStep`, `currentCalibrationFixationTarget`, `calibrationTargetCenterPx`, `formatErrorPctOrNull`, `formatApproxMmOrNull`, `formatEyeTelemetryLine`, `formatRawFeatureVectorLine`, `calibrationSampleCounterText`, `formatFittedMatrixTextOrNull`, `fitTargetPillCoordLabels`, `fitTargetSummaryCoordLabels`, `calibrationTargetPulseSpec`. |

### 1.5 `ui/components/EnrollmentScanOverlay.kt`

| Composable | Parameters | Content |
|---|---|---|
| `EnrollmentScanOverlay(state: EnrollmentScanState, onCancel, onSkipBrow, modifier)` | tag `enrollment_scan_overlay` | Composes nothing unless `enr.phase` ∈ {POSITIONING, EYES_OPEN, EYES_CLOSED, BROW_RAISE}. Black 82 % scrim (draw only, touches pass through). Top column: phase label (`enrollment_phase_label`, "Step n of 4 — …"), 3 dp overall bar (`enrollment_overall_progress`, contentDescription "Overall scan progress n percent"), guidance (headlineSmall bold white, `enrollment_guidance_text`), secondary line (`enrollment_secondary_text`: "Retry n • Face in position / Face not in position yet • ns elapsed"), "Eye openness 0.xx" (`enrollment_eye_open_label`) + 8 dp bar (`enrollment_eye_open_bar`), `EnrollmentWarningChips` (`enrollment_warning_chips`, `enrollment_warning_chip_<NAME>`). Centre: `EnrollmentFixationDot` (150 dp node, `enrollment_fixation_dot`, pulsing halo when fixation needed, phase-progress ring, contentDescription "Fixation dot, look here. Step progress n percent"). Bottom row: "Cancel scan" (`enrollment_cancel_button`, 48 dp) and, in BROW_RAISE only, "Skip brow step" (`enrollment_skip_brow_button`). |
| Public text helpers | — | `isEnrollmentScanPhaseActive`, `enrollmentGuidanceText` (14 lines), `enrollmentPhaseLabel` (7), `enrollmentWarningLabel` (6), `enrollmentWarningLabelForName`, `enrollmentFailureText` (7), `enrollmentSecondaryLine`, `formatEnrollmentCapturedAt`, `enrollmentBaselineSummaryLines` (6 lines), `enrollmentCompleteSummary`. |

### 1.6 `ui/screens/GazeControlDeckScreen.kt` (tab 1)

| Composable | Parameters | Content |
|---|---|---|
| `GazeControlDeckScreen(hudState, reticleRootPosition: Offset, tiles, onUpdateFocusedTile, onManualTileClick, onChangeDwellDurationMs, onChangeDwellSensitivityMultiplier, onSelectDwellPreset, onChangeTriggerMode, onCalibrateNeutralBrow, onCalibrateRaisedBrow, onEditTilePhrase, onSelectPosture, onSelectFilterMode, onRunBenchmark, gazeFrameRootBounds, onUpdateTileCentersNorm)` → `GazeControlDeckContent` | — | Column (12 dp h / 4 dp v padding): header `Card` and `LazyVerticalGrid`. Header card: VolumeUp icon + layout title (`deck_layout_title`: `gridLayoutLabel(use3x3)` + " • not yet validated"), `OutlinedButton` "Dwell: 1000ms (1.0×)" (`open_dwell_settings_button`, Tune icon) → `DwellTriggerSettingsDialog`; last speech text (`aac_speech_output_text`, bodyMedium, fixed 2 lines, secondary colour); 4 trigger-mode `FilterChip`s "All Triggers / Brow Raise / Cmd Blink / Dwell Only" (no tags); row "Brow Ratio: 0.340 (N:0.34→R:0.42)" + "1. Neutral Brow" (`sample_neutral_brow_button`) + "2. Raised Brow" (`sample_raised_brow_button`); row "Dwell Time:" + 26 dp-high `Slider` (`deck_dwell_duration_slider`, 400–2200, continuous). Grid: `GridCells.Fixed(2)` with 4 tiles (124 dp) or `Fixed(3)` with 9 tiles (104 dp) decided by `shouldUseValidated3x3Grid(hud.activeErrorRadiusPct, hud.activeMaxQuadrantErrorPct)`. Tile (`aac_tile_<id>`): 16 dp radius, focus border 2.5 dp primary + 16 % tint, category icon (CenterFocusWeak for SYSTEM, RecordVoiceOver otherwise, contentDescription = title) + category label, 22 dp `CircularProgressIndicator` (max of dwell/brow/blink progress) when focused, 28 dp edit `IconButton` (`edit_aac_tile_<id>`, "Edit <title>"), title (titleLarge 2×2 / titleMedium 3×3), phrase (bodySmall, 1 line). Click → `onManualTileClick`. Hit-test: `resolveFocusedDeckTile` with hysteresis 14 dp × `hud.dwellSensitivityRadiusMultiplier`; focus cleared when a dialog is open or the deck leaves composition; tile centres → `onUpdateTileCentersNorm`. |
| Tile edit `AlertDialog` | — | "Customize AAC Tile": `OutlinedTextField` "Tile Label" (`edit_tile_title_field`) + "Spoken TTS Phrase" (`edit_tile_phrase_field`), supporting error text when blank, "Save" (`save_tile_edit_button`, disabled when either blank) → `onEditTilePhrase(id, title, phrase)`, "Cancel". |
| Helpers | — | `shouldUseValidated3x3Grid`, `resolveFocusedDeckTile`, `tileCentersInGazeFrame`. |

### 1.7 `ui/components/DwellTriggerSettingsDialog.kt`

| Composable | Parameters | Content |
|---|---|---|
| `DwellTriggerSettingsDialog(hudState, onDismissRequest, onChangeDurationMs, onChangeSensitivityMultiplier, onSelectPreset, onSelectPosture, onSelectFilterMode, onRunBenchmark)` | tag `dwell_settings_dialog` | `AlertDialog` titled "Gaze, Dwell & Posture Engine" (Tune icon). Scrollable body: intro "Configure 1€ Filter jitter suppression, posture compensation profiles, and dwell thresholds for Google Pixel 10 Pro XL."; card "Posture Profile & Gravity Compensation" with `FilterChip`s Upright / Reclining / Lying Down (`posture_chip_<name>`); card "Gaze Stabilization Filter" with chips "1€ Filter / Kalman / Raw" (`filter_mode_chip_<name>`) + "Quiet background drift updates: n micro-bias adjustments absorbed."; card "Eye-Tracking Speed Presets" with 4 selectable rows (`dwell_preset_<name>`: title, subtitle, CheckCircle when selected — selected only if duration AND tolerance match exactly); "Duration Threshold:" + "n ms" (`dwell_duration_label`) + `Slider` (`dwell_duration_slider`, 400–2200, 17 steps); "Lock Sensitivity (Boundary Margin):" + "1.00× (14 dp)" (`dwell_sensitivity_label`) + `Slider` (`dwell_sensitivity_slider`, 0.6–2.0, 13 steps); card "Synthetic Replay Self-Test" (description of the replay, `Button` "Run Automated Benchmark Suite"/"Running Benchmark..." `run_benchmark_button`, summary text `benchmark_summary_text` via `formatBenchmarkSummaryLine`). Confirm button "Apply & Close" (`dismiss_dwell_settings_button`). |
| `DWELL_DURATION_SLIDER_RANGE_MS = 400f..2200f`, `formatBenchmarkSummaryLine(report)` | — | Shared by all three dwell sliders. |

### 1.8 `ui/screens/AttentionAnalyticsScreen.kt` (tab 2)

| Composable | Parameters | Content |
|---|---|---|
| `AttentionAnalyticsScreen(attentionState, reticleRootPosition: () -> Offset, passageSections, sectionSummaries, onUpdateLayoutHitSection, onUpdatePassageGazePoint, onClearReplay, onStartRecording, onStopAndSaveRecording)` (+ overload taking `reticleRootPosition: Offset`) | — | Scroll Column (12 dp / 6 dp): `AttentionSessionHeaderCard` then passage `Box` (surface, 16 dp radius, outline border) containing 3 × `PassageSectionCard` and, when `att.computedClusters` non-empty, `FixationHeatmapOverlay`. Hit-test coroutine reports section id / `ATTENTION_OFF_PASSAGE_SECTION_ID` and the passage-local gaze point; dispose reports off-passage + null. |
| `AttentionSessionHeaderCard(isRecording, elapsedSeconds, sessionNaturalBlinks, sessionIntentionalBlinks, replay, showThermalGuard, onClearReplay, onStartRecording, onStopAndSaveRecording)` (private) | — | Title "Section Dwell & Fixation Centroid Lab" or "Session Replay: <title>"; subtitle: recording → "Recording (ns) • Nat Blinks: n • Cmd Blinks: n", replay → "ns • n est. WPM (dwelled sections) / est. WPM n/a • x.x blinks/min • n clusters", idle → "Measures layout section dwell (%) & dispersion fixation clusters at camera FPS."; buttons "Exit Replay" (`close_session_replay_button`), "Record" (`start_attention_session_button`, primary), "Stop & Save" (`stop_attention_session_button`, error colour); thermal text "Thermal Guard: Device warming up (>40% FPS drop from baseline) — Performance Mode engaged automatically." |
| `PassageSectionCard(section, dwellPercentage, anySectionDwell, isCurrentlyLookedAt, sectionBoundsMap)` (private) | — | heading (titleMedium) + "n% dwell"/"dwell n/a" (labelMedium primary), body (bodyLarge 16 sp), 5 dp dwell `LinearProgressIndicator` (tertiary); looked-at state = primary 12 % tint + 1.5 dp primary border. |
| `FixationHeatmapOverlay(clusters)` (private) | tag `attention_heatmap_canvas` | Gaussian splats (crimson→amber), dashed cyan trajectory, numbered cyan nodes "1..N" (10 sp). |
| Helpers | — | `ATTENTION_OFF_PASSAGE_SECTION_ID`, `resolveLayoutHitReport`, `passageLocalGazePoint`, `shouldShowThermalGuard`, `formatEstimatedWpm`, `formatSectionDwellLabel`, `buildFixationHeatmapFrame`, `ClusterLabelLayouts`. |

### 1.9 `ui/screens/CloudPrivacyScreen.kt` (tab 3)

| Composable | Parameters | Content |
|---|---|---|
| `CloudPrivacyScreen(hudState, currentUserId, currentUserEmail, currentUserName, firestoreDatabaseId, profilesState, sessionsState, onToggleCloudSync, onActivateProfile, onDeleteProfile, onSelectSessionReplay, onDeleteSession, onDeleteFirestoreOnly, onEraseAllTelemetry, onSignOut, onChangeDwellDurationMs, onChangeDwellSensitivityMultiplier, onSelectDwellPreset, isAttentionRecording)` → `CloudPrivacyScreenContent` | — | Scroll Column (12 dp / 8 dp, 14 dp spacing): `DwellSettingsCard`, `PrivacySettingsCard`, `SavedProfilesCard`, `SessionsCard`; `DestructiveActionConfirmDialog` for the pending action. |
| `DwellSettingsCard(dwellTriggerMs, dwellSensitivityRadiusMultiplier, activeDwellPreset, …)` (private) | tag `dwell_settings_card` | PhoneAndroid icon, "Dwell Sensitivity & Speed Settings", "n ms", description, 4 preset `FilterChip`s (`speed_preset_chip_<name>`, label = `title.take(12)` → "High Precisi"), "Duration Threshold (Hold Time):" slider (`privacy_dwell_duration_slider`, 17 steps), "Lock Sensitivity (Boundary Margin):" slider (`privacy_dwell_sensitivity_slider`, 13 steps). **Third copy** of the dwell controls. |
| `PrivacySettingsCard(cloudSyncEnabled, isDeletingCloudData, lastCloudDeletionSummary, currentUserId, currentUserEmail, currentUserName, firestoreDatabaseId, onToggleCloudSync, onRequestDeleteFirestoreOnly, onRequestWipeCloudAndLocal, onSignOut)` (private) | tag `privacy_settings_card` | Cloud icon + `cloud_telemetry_opt_in_title` + `cloud_telemetry_status_opted_in/out` + `Switch` (`cloud_sync_toggle`); account panel ("<name> • <email>", "FirebaseAuth UID: …", "Scoped Paths: /users/<uid10>…/calibrations & /sessions", "Firestore DB: <id> • Privacy Mode: OPTED-IN (Cloud + Local) / OPTED-OUT (Local Only)", `cloud_deletion_status_text`); `Button` error-coloured `delete_firestore_telemetry_button` / `deleting_firestore_telemetry_label` (`delete_firestore_data_button`, 48 dp); `OutlinedButton` "Wipe Cloud & Local Vault" (`erase_all_cloud_button`); `OutlinedButton` "Sign Out" (`sign_out_button`, disabled while deleting). |
| `SavedProfilesCard(profilesState, activeProfileId, onActivateProfile, onRequestDeleteProfile)` (private) | — | "Saved Calibration Profiles (Device-Tagged)"; Loading "Loading calibration profiles..." / Error message / empty "No saved profiles yet. Run the calibration wizard…" / rows (clickable → activate; current = 1.5 dp secondary border; PhoneAndroid icon, name, `formatProfileErrorLine`: "<device> • Calib. error (held-out validation | in-sample fit (unvalidated)): mean ±x% (approx. ~x mm) • worst ±x%"; delete `IconButton` `delete_profile_<id>`). |
| `SessionsCard(sessionsState, isAttentionRecording, onSelectSessionReplay, onRequestDeleteSession)` (private) | — | "Recorded Section Attention Sessions (Tap to Replay)"; `replay_locked_while_recording_text`; Loading / Error / empty "No recorded sessions yet…" / rows (`session_row_<id>`, click disabled while recording; title + `formatSessionSummaryLine`: "ns • est. WPM … • x blinks/min • Zones T:n% M:n% B:n% | Zones n/a"; delete `delete_session_<id>`). |
| `DestructiveActionConfirmDialog(action, currentUserId, isDeletingCloudData, onConfirm, onDismiss)` (private) | tag `destructive_action_confirm_dialog` | 4 variants: DeleteFirestoreOnly (strings), WipeCloudAndLocal ("Wipe Cloud & Local Vault?" … "Wipe Everything"), DeleteProfile ("Delete calibration profile?" … "Delete Profile"), DeleteSession ("Delete attention session?" … "Delete Session"); `confirm_destructive_action_button` (error, bold) / `cancel_destructive_action_button`. |
| Helpers | — | `sliderValueToDwellMs`, `formatSensitivityLabel`, `formatProfileErrorLine`, `formatZoneSummary`, `formatSessionSummaryLine`, `indexOfActiveProfile`. |

### 1.10 Theme — `ui/theme/`

* `Color.kt`: `ObsidianBackground #0B0F17`, `SlateSurface #131B2E`, `SlateSurfaceVariant #1C2742`, `SlateBorder #283655`, `SlateContainerLow #0F1524`, `SlateContainerHigh #172138`, `SlateOutline #64748B`, `OpticalCyan #00E5FF`, `OpticalCyanDim #0088A3`, `ValidationEmerald #10B981`, `ValidationEmeraldDim #065F46`, `ThermalAmber #F59E0B`, `AlertCoral #EF4444`, `TextPrimary #F1F5F9`, `TextSecondary #94A3B8`, `TextMuted #64748B`. (Note: surfaces are `#131B2E`, not the `#151D2E` named in the brief.)
* `Theme.kt`: `OculaDarkColorScheme` (dark only; primary cyan, secondary emerald, tertiary amber, error coral; all `surfaceContainer*` roles mapped to the slate palette). `OculaGazeTheme(content)`.
* `Type.kt`: Space Grotesk (headlineLarge 28, headlineMedium 22, titleLarge 18, titleMedium 15, labelLarge 14), Plus Jakarta Sans (bodyLarge 16/25, bodyMedium 14/20), **JetBrains Mono (labelMedium 12, labelSmall 11)**. Not overridden (fall back to M3 Roboto defaults): headlineSmall (24, used by the enrollment guidance), titleSmall (14, enrollment start button), bodySmall (12, tile phrase, many descriptions). Fonts are single Regular faces; bold is synthesized.
* `res/values/themes.xml`: `Theme.MyApplication` = `android:Theme.DeviceDefault.NoActionBar`.

---

## 2. Numbered CAPABILITY LIST (every user-visible control, readout, banner, dialog, state)

Each item names the composable/home today and the VM function or field it uses. "local" = Compose-local state only.

### A. Shell, navigation, global bars

1. **Google sign-in gate** — `AuthScreen`; `AppRootNavigation` reads `FirebaseAuth.authStateFlow()`; no VM.
2. **Returning-user auto sign-in** — `attemptAutoSignIn` (Credential Manager, authorized accounts, auto-select); suppressed after an explicit sign-out via `AutoSignInGate`.
3. **"Sign in with Google" button** with loading spinner and disabled state (`google_sign_in_button`) — `onGoogleSignInClicked`.
4. **Sign-in error card** (red tint) with mapped messages (no account / Play services / interrupted / generic / missing client id).
5. **Sign-in intro copy**: "PIXEL 10 PRO XL • OPTICAL GAZE WORKBENCH" badge, `auth_title`, `auth_subtitle`, hero illustration, three feature rows incl. the privacy notice with Firestore paths and DB id — static.
6. **Bottom navigation, 4 tabs** Calibrate / Control Deck / Attention Lab / Cloud & Privacy (`nav_tab_*`) — `selectedTab` local (default 0 = Calibrate).
7. **System Back on tabs 1–3 returns to tab 0** — `BackHandler`.
8. **Camera permission flow**: auto-request on first launch; denial → `VM.toggleSimulatorProxyMode(true)`; grant on resume → `toggleSimulatorProxyMode(false)`; `hasCameraPermission` drives the HUD's camera row vs "Enable Camera" row (`request_camera_button`).
9. **Per-user ViewModel scope** (keyed by uid; cleared on sign-out) — `viewModel(key = uid)`.
10. **Text-to-speech output** on tile trigger (+ haptic CONFIRM/VIRTUAL_KEY) — `VM.registerTileTriggerListener`; `tile.speechPhrase`.
11. **TTS-unavailable banner** (`tts_unavailable_banner`, `tts_unavailable_warning`, red tint, labelSmall mono) — local `ttsUnavailable`.
12. **Status banner row** (`status_banner_row`, green tint, labelSmall mono, tap anywhere = dismiss, trailing "Dismiss" text, no auto-timeout) — `VM.statusBannerMessage` / `VM.dismissStatusBanner()`. Carries ~45 distinct messages (see §4.3).
13. **Inline cloud privacy bar on tabs 0–2** (`inline_privacy_telemetry_bar`): icon, "Cloud Telemetry: OPTED IN / OPTED OUT (Local)", "Syncing to Firestore (/users/xxxxxx…)" / "Zero cloud writes • On-device only" — `hud.cloudSyncEnabled`.
14. **Cloud sync Switch (inline bar)** (`cloud_sync_toggle`) — `VM.toggleCloudSync(Boolean)`.
15. **"Delete Cloud" shortcut (inline bar)** (`delete_firestore_data_button`, "Deleting…" while busy) → **confirm dialog** (`confirm_delete_cloud_dialog`, strings `confirm_delete_cloud_*`) — `hud.isDeletingCloudData`, `VM.deleteFirestoreCloudTelemetry()`.
16. **Display physical size reporting** (invisible; needed for mm error figures) — `VM.setDisplayPhysicalSizeMm`.
17. **Gaze reticle overlay, cursor layer** (`realtime_gaze_canvas_overlay`): interpolated cursor, uncertainty ellipse sized from `hud.activeErrorRadiusPct` (20 % wide when null/uncalibrated), N/S/E/W ticks, centre dot — `hud.normX`, `hud.normY`, `hud.activeErrorRadiusPct`, `hud.overlayVisualMode`.
18. **Reticle gesture progress arc** (360°, emerald) = max(`hud.focusedTileDwellProgress`, `hud.blinkHoldProgress`, `hud.browHoldProgress`).
19. **Reticle coordinate pill** "xx%, yy%" (10 sp) beside the cursor — derived from display position.
20. **Heatmap trail + live aura layer** (cyan→emerald→amber→crimson splats, dashed comet) — `hud.liveTrailPoints`, `hud.overlayVisualMode`.
21. **Reticle root-position feed** to the deck and passage hit tests — `onReticleRootPositionChanged` (local State), consumed by `VM.updateFocusedTile` / `VM.updateActiveReadingSectionFromLayout` / `VM.updatePassageGazePoint`.

### B. Persistent diagnostics bar (`OpticalDiagnosticsBar`, on every tab)

22. **Status dot** (emerald when frames recent and face detected, amber otherwise) — `hud.analyzerFps`, `hud.rawFeature.faceDetected`.
23. **Status label** `analyzerStatusLabel`: "OPTICAL LOCK • n FPS" / "SEARCHING FACE... • n FPS" / "CAMERA • NO FRAMES" / "SIM PROXY • …" / "EMULATOR SYNTHETIC • …" — `hud.isSimulatorProxyEnabled`, `isRunningOnEmulatorHardware()`, `hud.analyzerFps`, `faceDetected`.
24. **Resolution tier badge** "1280×960" (clickable, `resolution_tier_badge`, onClickLabel "Cycle analysis resolution") — `hud.resolutionTier`, `VM.cycleAnalysisResolutionTier()`.
25. **Single-eye badge** (e.g. "LEFT EYE") — `hud.rawFeature.isSingleEyeMode`, `activeEyeLabel`.
26. **"LOW FPS" badge** — `hud.isPerformanceModeActive`.
27. **Telemetry line** `gazeTelemetryLine`: "Gaze (x%, y%) • L:n% R:n% • ±e.e% | uncalibrated • Luma:n" — `hud.normX/Y`, `rawFeature.leftEyeConfidence/rightEyeConfidence/ambientLuma`, `hud.activeErrorRadiusPct`.
28. **"Cam Full / Cam Mini" chip** (`toggle_camera_feed_size_chip`) — local `expandCameraPreview`.
29. **Overlay mode chip** (`toggle_overlay_mode_chip`, label "Cursor + Heatmap / Cursor Only / Heatmap Only") — `hud.overlayVisualMode`, `VM.cycleOverlayVisualMode()`.
30. **"Sim Proxy / Sim Active" chip** (`toggle_simulator_chip`) — `hud.isSimulatorProxyEnabled`, `VM.toggleSimulatorProxyMode`.
31. **Live front-camera preview** (`live_camera_preview_box`, 116×150 or 56×72 dp; tap toggles size or switches sim → live) — `MiniFrontCameraPreview` owns CameraX + `CameraGazeAnalyzerEngine` and delivers frames via `VM.submitCameraFrame`; `hud.resolutionTier`, `hud.isPerformanceModeActive`, `hud.isSimulatorProxyEnabled`.
32. **ML Kit face overlay on the preview** (brackets, eye rings, pupil crosshairs, brow bars amber at trigger threshold, synthetic grid, dashed "no face" oval) — `hud.rawFeature.*`, `hud.neutralBrowRatio`, `hud.raisedBrowRatio`.
33. **"No front camera" label** — local `hasNoFrontCamera`.
34. **Camera pipeline label** `cameraPipelineLabel`: "FRONT CAMERA PIPELINE • <MODEL>" / "CAMERAX STANDBY (TAP PREVIEW FOR LIVE)" / "EMULATOR • NO CAMERA BOUND (SYNTHETIC FRAMES)".
35. **Frame geometry line** "960×1280 @ 270° (synthetic)" — `rawFeature.frameWidthPx/frameHeightPx/sensorRotationDeg`.
36. **Iris centroid line** "Iris Centroid: gx=±0.000, gy=±0.000 • IOD=n px" — `rawFeature.gx/gy/eyeDistancePx`.
37. **Head Euler line** "Head Euler: Yaw ±n° • Pitch ±n° • Roll ±n°" — `rawFeature.yaw/pitch/roll`.
38. **Eye-open / brow line** (expanded preview only) `eyeOpenBrowLine`: "Eye Open: L n% / R n% • Brow Ratio: 0.00 (Trigger ≥ 0.00)" — `rawFeature.leftEyeOpenProb/rightEyeOpenProb/browToEyeRatio`, brow threshold.
39. **"Switch to Live Front Camera" button** (sim on + expanded) — `VM.toggleSimulatorProxyMode(false)`.
40. **Camera-permission-denied row** "Grant front-camera access to enable the live CameraX PreviewView & ML Kit face-tracking feed." + "Enable Camera" (`request_camera_button`) — `hasCameraPermission`.
41. **Optical warning slot** (fixed 2-line height; `low_light_degraded_banner` / `optical_warning_slot`): low light ("LOW LIGHT (luma n/255): visible-light front camera, no IR illumination; iris offsets damped, head pose + region dwell prioritized."), eyeglass glare ("Eyeglass specular glare detected (tracking LEFT EYE | both eyes fused, glare-weighted)."), head-distance drift ("Head distance (IOD) shifted >15% from calibration baseline."), else "No optical warnings" — `rawFeature.isLowLightDegraded/ambientLuma/hasGlareWarning/isSingleEyeMode`, `hud.hasDistanceDriftWarning`; debounced 1 s / 2 s.
42. **Recalibrate nudge** (animated row) "Handheld geometry shifted — Recalibrate without losing session?" with "Recalibrate" (`recalibrate_nudge_button` → `VM.dismissRecalibrateNudge()` + tab 0 + `VM.startCalibrationAndValidationWizard()`) and "Ignore" (`VM.dismissRecalibrateNudge()`) — `hud.showRecalibrateNudge` (edge-pinned ≥ 4 s or drift).
43. **Blink/brow classification label** — `hud.lastBlinkLabel` (default "Ready (Brow 400ms • Blink 350-800ms • Nat 80-349ms)"; "Natural blink (n ms) — ignored for triggers"; "Command blink (n ms) — tile triggered! / repeat on the same tile suppressed / no tile focused / disabled in <mode> / ignored during the enrollment scan"; "Brow-Raise Trigger (400 ms hold) — tile triggered!" etc.; "Extended closure (n ms) — trigger cancelled").
44. **Gesture counters** "Brow:n • Cmd:n • Nat:n" — `hud.browRaiseTriggerCount`, `hud.intentionalBlinkCount`, `hud.naturalBlinkCount`.
45. **Gesture hold progress bar** (5 dp, alpha-hidden when idle) = max(`hud.blinkHoldProgress`, `hud.browHoldProgress`).
46. **Simulator pad section** (visible when sim on): caption "Emulator Proxy Pad (Drag gaze • Trigger Brow Raise or Command Blink):", "Brow (400ms)" (`simulate_brow_raise_button` → `VM.simulateBrowRaiseCommand()`), "Blink (460ms)" (`simulate_command_blink_button` → `VM.simulateIntentionalCommandBlink()`), 74 dp touchpad (`simulator_touchpad`, tap/drag → `VM.updateSimulatorGazeTarget(nx, ny)`) drawing the current `hud.normX/normY`.

### C. Calibrate tab — idle overview (`CalibrationIdleOverview`)

47. **Enrollment card title** "Face & Eye Enrollment" + **status** "Not enrolled" / "Enrolled <date>" / "Scanning…" (`enrollment_status_text`) — `VM.enrollmentBaseline`, `enr.phase`.
48. **Enrollment outcome text** (`enrollment_outcome_text`): `enrollmentCompleteSummary(baseline)` after COMPLETE, "Scan failed: <enrollmentFailureText>" after FAILED — `enr.phase`, `enr.result`, `enr.failure`.
49. **Enrollment explanation** (no baseline) "A 15-second scan of your face and eyes personalizes blink and brow detection… No images are stored, and nothing is synced." (`enrollment_explanation_text`).
50. **"Scan my face & eyes" button** (`start_enrollment_button`, disabled while scanning) — `VM.startEnrollmentScan()` (refused with a banner when sim/emulator, or while the wizard collects).
51. **Enrollment baseline summary (6 lines)**: eye levels, blink thresholds, "Command blink: at least n ms", natural blinks (median/p95 or "none"), brow ratio (raised or "raised not measured"), conditions (frames, eye confidence, luma, IOD, device) — `EnrollmentBaseline.*`.
52. **Baseline warnings line** "Warnings: Low light, Few natural blinks" (`enrollment_warnings_text`) — `EnrollmentBaseline.warnings`.
53. **Enrollment privacy line** "Derived numbers only, stored on this device. No images, never synced."
54. **"Re-scan" button** (`rescan_enrollment_button`) — `VM.startEnrollmentScan()`.
55. **"Clear" enrollment button** (`clear_enrollment_button`, error tint, **no confirmation**) — `VM.clearEnrollmentBaseline()`.
56. **Calibration overview title/subtitle** (`calibration_5_point_title/subtitle`).
57. **Calibration mode chips** "5-Point Quick Fit" / "5-Point Fit + 4-Point Validation" (`mode_5_point_only_chip`, `mode_5_plus_4_validation_chip`) — `cal.includeHeldOutValidation`, `VM.setIncludeHeldOutValidation`. Default is quick fit.
58. **Five-point preview map** canvas (`five_point_preview_map`) + "Sequence: P1(0.50,0.50) → P2(0.14,0.16) → P3(0.86,0.16) → P4(0.14,0.84) → P5(0.86,0.84)" — static (`GazeCalibrationMath.FIVE_POINT_CALIBRATION_TARGETS`).
59. **Active accuracy badges** "MEAN SCREEN ERR" / "WORST QUADRANT": "±x.x%" or "Not validated" (fit applied) or "Not calibrated" (default matrix); sub-text "~x.x mm (approximate)" / "mm not measured" / "5-point fit only, run 5+4 validation to measure" / "Run calibration to measure" / "Profile: <name>" / "Default matrix, not validated" — `hud.activeErrorRadiusPct`, `hud.activeMaxQuadrantErrorPct`, `hud.activeErrorMm`, `hud.hasActiveCalibration`, `hud.activeProfileName`.
60. **Start calibration button** (`start_calibration_button`, 52 dp) "Start 5-Point Calibration" / "Launch 5-Point Fit + 4-Point Validation" — `VM.startCalibrationAndValidationWizard()`.
61. **Live feature telemetry card** "Live 6D Feature Vector & Pixel 10 Pro XL Telemetry": `f_raw` line and eye telemetry line (`calibration_eye_telemetry_text`) — `hud.rawFeature.*`, `hud.resolutionTier.shortLabel`.

### D. Calibrate tab — wizard (`FivePointCalibrationScreen` + full-screen target)

62. **Target heading** "Fit n/5 • <Name> • Screen Coord (x.xx, y.yy)" / "Validate n/4 • <Quadrant> …" (`active_calibration_target_label`) + guidance prompt "Keep your head still and fixate steadily on the pulsing center dot." — `cal.currentTargetIndex`.
63. **Sample counter** (`calibration_sample_counter_text`): "No face detected — face the front camera. Sampling is paused until your face is found." (red) / "Settling saccade (400 ms) — keep your eyes on the pulsing on-screen target..." / "Collecting gaze samples: n/18 at point (p%) • Total collected: n pts" — `rawFeature.faceDetected`, `cal.isSettlingDelay`, `cal.samplesCollectedForCurrentTarget`, `cal.requiredSamplesPerTarget`, `cal.currentTargetProgress`, `cal.totalCollectedSamples`.
64. **Five stepper pills** (`calibration_step_pill_0..4`): "✓ P1"/"P1", "(0.50,0.50)", "n/18"; current/completed/pending colours — `cal.currentTargetIndex`, `cal.pointSampleCounts`.
65. **"Capture Point (<short>)" button** (`capture_gaze_point_button`) — `VM.captureManualSamplesForCurrentTarget()`; also `VM.updateSimulatorGazeTarget(target)` first when sim is on.
66. **"Lock Sim Here" button** (`snap_sim_to_target_button`, enabled only in sim) — `VM.updateSimulatorGazeTarget`.
67. **"Cancel" button** (`cancel_calibration_button`) — `VM.cancelCalibrationWizard()`.
68. **Progress-map caption + miniature map** (`calibration_progress_map_label`, `five_point_calibration_canvas`) with 5 accessible 48 dp nodes (`calibration_point_target_i`; only the current one captures) — `cal.currentTargetIndex`.
69. **Full-screen pulsing fixation target** (`calibration_fullscreen_target_overlay`) drawn in the gaze frame with a progress ring; cyan for fit, emerald for validation; touches pass through — `cal.step`, `cal.currentTargetIndex`, `cal.currentTargetProgress`.
70. **Auto-cancel when leaving the tab mid-collection** — `VM.cancelCalibrationWizard()` from `DisposableEffect`.

### E. Calibrate tab — completion summary (`CalibrationValidationSummaryCard`)

71. **Summary title** "5-Point Fit + 4-Point Validation Report" / "5-Point Screen Calibration Complete" — `cal.includeHeldOutValidation`.
72. **Result badges**: held-out → "MEAN SCREEN ERROR" (+ "~x mm (approximate)") and "WORST QUADRANT" (+ "Total Samples: n"); quick fit → "FIT RESIDUAL (IN-SAMPLE)" (+ "not validated") and "WORST FIT POINT" (+ "not validated • n samples"); "Not measured" for NaN — `cal.metricsAreHeldOut`, `cal.validationMeanErrorPct`, `cal.validationMaxErrorPct`, `cal.validationMeanErrorMm`, `cal.totalCollectedSamples`.
73. **In-sample caveat** (`calibration_in_sample_caveat`): "Quick fit: these are residuals on the same n samples…" / "No held-out validation sample was scored…".
74. **Per-coordinate sample table** "Collected Gaze Samples by Screen Coordinate:" 5 rows "P1 Center — Coord (0.50, 0.50)  n gaze samples" — `cal.pointSampleCounts`.
75. **Recommended layout line** "Recommended Layout: 3×3 Validated Deck (9 tiles)" / "2×2 Protective Region Deck (4 tiles) — 5-point fit residual only, not validated; run 5-Point Fit + 4-Point Validation to unlock 3×3" — `cal.recommendedGridLabel`.
76. **Fitted matrix dump** "W_x = [...] / W_y = [...]" — `cal.fittedMatrix`.
77. **Profile name field** (`profile_name_input`, default "<Build.MODEL> Profile") — `cal.draftProfileName`, `VM.updateDraftProfileName`.
78. **"Retry"** (`recalibrate_button` → `VM.startCalibrationAndValidationWizard()`) and **"Save & Apply Profile"** (`save_calibration_button` → `VM.saveCompletedCalibrationProfile()`; refused with a banner when metrics are NaN).

### F. Enrollment scan overlay (`EnrollmentScanOverlay`, tab 0 only)

79. **Phase label** "Step 1 of 4 — Position your face" … "Step 4 of 4 — Raise your brows" (`enrollment_phase_label`) — `enr.phase`.
80. **Overall progress bar** (`enrollment_overall_progress`, a11y "Overall scan progress n percent") — `enr.overallProgress`.
81. **Guidance line** (one per `EnrollmentGuidance`: "No face found. Face the front camera.", "Hold still and look at the dot.", "Now close both eyes.", "Raise your eyebrows and hold them up.", …) (`enrollment_guidance_text`) — `enr.guidance`.
82. **Secondary line** "Retry n • Face in position / Face not in position yet • ns elapsed" (`enrollment_secondary_text`) — `enr.retries`, `enr.liveFaceCentered`, `enr.elapsedMs`.
83. **Live eye-openness readout + bar** "Eye openness 0.87" (`enrollment_eye_open_label`, `enrollment_eye_open_bar`) — `enr.liveEyeOpenProb`.
84. **Warning chips** "Low light", "Glare", "One eye only", "Few natural blinks", "Small eye range, defaults used", "Brow step skipped" (`enrollment_warning_chip_<NAME>`) — `enr.warnings`.
85. **Fixation dot with phase ring** (pulses while fixation is needed; a11y "Fixation dot, look here. Step progress n percent") (`enrollment_fixation_dot`) — `enr.phaseProgress`, `enr.guidance`.
86. **"Cancel scan"** (`enrollment_cancel_button`) — `VM.cancelEnrollmentScan()`.
87. **"Skip brow step"** (BROW_RAISE only, `enrollment_skip_brow_button`) — `VM.skipEnrollmentBrowStep()`.
88. **Auto-cancel on leaving tab 0 mid-scan** — `EnrollmentScanOverlayHost` → `VM.cancelEnrollmentScan()`.
89. **Gesture triggers suppressed during the scan** (dwell paused/reset; blink/brow labelled "ignored during the enrollment scan") — VM `processIncomingFeatureFrame` (`enrollmentActive`).

### G. Control Deck (`GazeControlDeckScreen`)

90. **Deck layout title** (`deck_layout_title`): "2×2 Protective Region Deck (4 tiles) • not yet validated" / "3×3 Validated Deck (9 tiles)" — `shouldUseValidated3x3Grid(hud.activeErrorRadiusPct, hud.activeMaxQuadrantErrorPct)` (mean ≤ 12 %, worst ≤ 16.5 %).
91. **"Dwell: 1000ms (1.0×)" settings button** (`open_dwell_settings_button`) → `DwellTriggerSettingsDialog` — `hud.dwellTriggerMs`, `hud.dwellSensitivityRadiusMultiplier`.
92. **Last-triggered speech readout** (`aac_speech_output_text`, 2 fixed lines) — `hud.lastTriggeredSpeech` (default instructional sentence; then "[<Title> • <Method>] "<phrase>"" / "[Recenter via <Method>] …").
93. **Trigger mode chips** "All Triggers / Brow Raise / Cmd Blink / Dwell Only" (no tags) — `hud.triggerMode`, `VM.setTriggerMode(GazeTriggerMode)`.
94. **Brow ratio readout** "Brow Ratio: 0.340 (N:0.34→R:0.42)" — `rawFeature.browToEyeRatio`, `hud.neutralBrowRatio`, `hud.raisedBrowRatio`.
95. **"1. Neutral Brow" / "2. Raised Brow" quick brow calibration** (`sample_neutral_brow_button`, `sample_raised_brow_button`) — `VM.calibrateNeutralBrowBaseline()`, `VM.calibrateRaisedBrowPeak()` (banners report captured values or "No face detected…" / "Brow raise not detected…").
96. **Inline "Dwell Time:" slider** (`deck_dwell_duration_slider`, 400–2200 ms continuous) — `hud.dwellTriggerMs`, `VM.setDwellTriggerDuration(Long)`.
97. **AAC tile grid** (2×2 × 124 dp or 3×3 × 104 dp; `aac_tile_<id>`): category icon + category label, title, 1-line phrase, focus border/tint — `VM.aacTilesState` (`AacActionTile.id/title/speechPhrase/category`), `hud.focusedTileId`.
98. **Tile focus progress ring** (22 dp) on the focused tile = max(dwell, brow, blink progress) — `hud.focusedTileDwellProgress`, `hud.browHoldProgress`, `hud.blinkHoldProgress`.
99. **Tap-to-speak a tile** — `VM.fireTileAction(id, "Tap")`.
100. **Gaze hit-test with hysteresis** (14 dp × `hud.dwellSensitivityRadiusMultiplier`; only visible tiles; suspended while a dialog is open; cleared on leave) — `VM.updateFocusedTile(id?)`.
101. **Tile centre feed for drift correction** — `VM.updateTileCentersNorm(map)`; count shown as `hud.backgroundRecalibrationCount` in the dialog.
102. **Edit tile icon button** (28 dp, `edit_aac_tile_<id>`) → **"Customize AAC Tile" dialog** ("Tile Label" `edit_tile_title_field`, "Spoken TTS Phrase" `edit_tile_phrase_field`, Save `save_tile_edit_button` disabled when blank, Cancel) — `VM.updateAacTilePhrase(id, title, phrase)` (persisted via `LocalTelemetryStore`).
103. **Default 9 tiles**: Affirm / Yes, Decline / No, Request Water, Adjust Position, Read Summary (READER; speaks `buildReadingSummaryPhrase()` when unedited), Recenter Gaze (SYSTEM; runs `startRecenterCapture()` and clears the nudge), Thank You, Ask Question, Eyes Tired — VM `defaultAacTiles`.
104. **Gaze-trigger safety** (not drawn, but behaviour the deck depends on): dwell accumulates only with face detected and eyes open (`gazeValid`), pauses/reset on invalid runs > 900 ms or frame gaps > 500 ms; same-tile refractory 1.5 s across modalities; natural blinks (80–349 ms) never fire; extended closure (> 800 ms) cancels and resets dwell — VM `updateTileHoverDwell`, `fireGazeTrigger`, `blinkDetector`, `browDetector`.

### H. Dwell / tracking settings dialog (`DwellTriggerSettingsDialog`)

105. **Dialog title & intro** "Gaze, Dwell & Posture Engine" / "Configure 1€ Filter jitter suppression, posture compensation profiles, and dwell thresholds for Google Pixel 10 Pro XL."
106. **Posture profile chips** Upright / Reclining / Lying Down (`posture_chip_*`) — `hud.activePosture`, `VM.setPostureProfile` (banner "Posture Profile: … (Y-bias ±0.000 applied …)").
107. **Gaze filter chips** 1€ Filter / Kalman / Raw (`filter_mode_chip_*`) — `hud.filterMode`, `VM.setGazeFilterMode` (banner "Gaze Stabilization: <label>").
108. **Drift-correction counter** "Quiet background drift updates: n micro-bias adjustments absorbed." — `hud.backgroundRecalibrationCount`.
109. **Speed preset rows** Fast Reader (650 ms, 0.85×) / Balanced (1,000 ms, 1.0×) / Relaxed / Steady (1,400 ms, 1.25×) / High Precision Assist (1,900 ms, 1.55×) (`dwell_preset_*`, CheckCircle when exactly selected) — `hud.activeDwellPreset`, `hud.dwellTriggerMs`, `hud.dwellSensitivityRadiusMultiplier`, `VM.setDwellSpeedPreset`.
110. **Duration threshold slider + label** (`dwell_duration_slider`, `dwell_duration_label`, 400–2200, 17 steps) — `VM.setDwellTriggerDuration`.
111. **Lock sensitivity slider + label** "1.00× (14 dp)" (`dwell_sensitivity_slider`, `dwell_sensitivity_label`, 0.6–2.0, 13 steps) — `VM.setDwellSensitivityMultiplier`.
112. **Synthetic replay benchmark** card: description, "Run Automated Benchmark Suite" / "Running Benchmark..." (`run_benchmark_button`), summary (`benchmark_summary_text`, `formatBenchmarkSummaryLine`) — `hud.isBenchmarkRunning`, `hud.lastBenchmarkReport`, `VM.runBenchmarkSuite()`.
113. **"Apply & Close"** (`dismiss_dwell_settings_button`) — local.

### I. Attention Lab (`AttentionAnalyticsScreen`)

114. **Header title** "Section Dwell & Fixation Centroid Lab" / "Session Replay: <title>" — `att.isRecording`, `att.selectedReplaySession`.
115. **Header subtitle**: "Recording (ns) • Nat Blinks: n • Cmd Blinks: n" / replay stats "ns • n est. WPM (dwelled sections) • x.x blinks/min • n clusters" / idle description — `att.elapsedSeconds`, `att.sessionNaturalBlinks`, `att.sessionIntentionalBlinks`, `TrackingSession.*`.
116. **"Record"** (`start_attention_session_button`) — `VM.startAttentionRecording()`.
117. **"Stop & Save"** (`stop_attention_session_button`, red) — `VM.stopAndSaveAttentionSession()` (banner: saved locally / synced / "[SIMULATED]").
118. **"Exit Replay"** (`close_session_replay_button`) — `VM.clearSessionReplay()`.
119. **Thermal guard text** (recording only) — `att.thermalThrottleDetected`.
120. **Reading passage, 3 sections** (headings "1. Optical Corneal Reflection & Visible-Light Constraints", "2. Standardized 2×6 Ridge Regression & Head-Pose Coupling", "3. Expression & Hysteresis Gating vs. Dwell Ergonomics"; bodies are engineering prose about the app itself) — `VM.readingPassageSections`.
121. **Per-section dwell label + bar** "n% dwell" / "dwell n/a" — `VM.computeSectionSummariesForDisplay()` (`SectionDwellSummary.percentage`).
122. **Currently-looked-at section highlight** (while recording) — `att.activeSectionId`, `att.isRecording`.
123. **Section hit-test feed** (section id / off-passage / null) — `VM.updateActiveReadingSectionFromLayout`.
124. **Passage-local gaze point feed** — `VM.updatePassageGazePoint(Offset?)`.
125. **Fixation heatmap** (`attention_heatmap_canvas`: splats, dashed trajectory, numbered nodes) — `att.computedClusters`.
126. **Recording keeps running on other tabs; layout detached on leave** — `VM.detachReadingSectionLayout()`.

### J. Cloud & Privacy (`CloudPrivacyScreen`)

127. **Dwell settings card** (third copy): "n ms", preset chips (`speed_preset_chip_*`, labels truncated to 12 chars), duration slider (`privacy_dwell_duration_slider`), sensitivity slider (`privacy_dwell_sensitivity_slider`) — `hud.dwellTriggerMs`, `hud.dwellSensitivityRadiusMultiplier`, `hud.activeDwellPreset`; `VM.setDwellSpeedPreset`, `setDwellTriggerDuration`, `setDwellSensitivityMultiplier`.
128. **Cloud sync card header + Switch** (`cloud_sync_toggle`, title `cloud_telemetry_opt_in_title`, status `cloud_telemetry_status_opted_in/out`) — `hud.cloudSyncEnabled`, `VM.toggleCloudSync`.
129. **Account panel**: "<name> • <email>" / "Authenticated Google Account", "FirebaseAuth UID: <uid>", "Scoped Paths: /users/<uid>…/calibrations & /sessions", "Firestore DB: <id> • Privacy Mode: OPTED-IN (Cloud + Local) / OPTED-OUT (Local Only)" — `currentUserId/Email/Name`, `firestoreDatabaseId`.
130. **Last cloud deletion summary** (`cloud_deletion_status_text`) — `hud.lastCloudDeletionSummary`.
131. **"Delete Cloud Data (Firestore)" button** (`delete_firestore_data_button`, "Deleting Firestore Data…" while busy) → confirm → `VM.deleteFirestoreCloudTelemetry()` — `hud.isDeletingCloudData`.
132. **"Wipe Cloud & Local Vault" button** (`erase_all_cloud_button`) → confirm "Wipe Everything" → `VM.eraseAllCloudTelemetry()` (also resets tiles to defaults, forgets enrollment, resets engine).
133. **"Sign Out" button** (`sign_out_button`, disabled while deleting) — `signOutUser`.
134. **Saved calibration profiles list** ("Saved Calibration Profiles (Device-Tagged)"; Loading / Error / empty / rows with name + `formatProfileErrorLine`; current row highlighted by border) — `VM.calibrationProfilesState` (`UiDataState`), `hud.activeProfileId`.
135. **Activate a profile** (tap row) — `VM.activateSavedProfile(profile)` (banner "Activated profile: … (x% mean • x% max)" or "(accuracy not validated)").
136. **Delete a profile** (`delete_profile_<id>`) → confirm → `VM.deleteCalibrationProfile(id)`.
137. **Recorded sessions list** ("Recorded Section Attention Sessions (Tap to Replay)"; lock text `replay_locked_while_recording_text` while recording; Loading / Error / empty / rows `session_row_<id>` with `formatSessionSummaryLine`) — `VM.trackingSessionsState`, `att.isRecording`.
138. **Open a session replay** (tap row; also switches to tab 2) — `VM.selectSessionForReplay(session)` (refused with banner while recording; legacy sessions show a "fixation heatmap is not shown" banner).
139. **Delete a session** (`delete_session_<id>`) → confirm → `VM.deleteTrackingSession(id)`.
140. **Destructive-action confirm dialog** (`destructive_action_confirm_dialog`, 4 variants, confirm/cancel buttons) — local `PendingDestructiveAction`.

### K. Behaviours surfaced only through the status banner or implicitly

141. **Simulator honesty**: simulated calibrations are saved as "[SIMULATED] <name>", device "Simulator", never synced; simulated sessions likewise; enrollment refuses simulated frames (`EnrollmentFailure.SIMULATED_FRAMES`) — VM `saveCompletedCalibrationProfile`, `stopAndSaveAttentionSession`, `startEnrollmentScan`.
142. **Performance mode / automatic resolution step-down** on sustained < 15 FPS or thermal drop — `hud.isPerformanceModeActive`, `hud.resolutionTier` (badge + "LOW FPS").
143. **Frame watchdog**: FPS zeroed after frames stop → "NO FRAMES" state — `hud.analyzerFps`.
144. **Recenter flow** (Recenter Gaze tile): captures 8 samples after 600 ms settle, shifts the mapping intercepts; banners "Gaze recentered: offset …" / "Recenter cancelled — face not stable…" — VM `updateRecenterCapture`.
145. **Settings persistence**: dwell, sensitivity, preset, trigger mode, filter, posture, brow ratios survive restart per user — VM `restoreInteractionSettings`/`persistInteractionSettings` (`LocalTelemetryStore`).
146. **Profile / session lists merge cloud + local** with Loading / Error states — `VM.calibrationProfilesState`, `VM.trackingSessionsState`.
147. **Enrollment applied at launch** before saved settings and the active profile — VM `init`; `hud.enrolledAtMs`.

---

## 3. ViewModel public API (`ui/viewmodel/EyeTrackingViewModel.kt`)

### 3.1 Exposed state

| Member | Type | Consumers today |
|---|---|---|
| `statusBannerMessage` | `StateFlow<String?>` | status banner row |
| `liveHudState` | `StateFlow<LiveGazeHudState>` | HUD, reticle, every screen |
| `calibrationState` | `StateFlow<CalibrationWizardState>` | Calibrate tab, fullscreen target |
| `attentionState` | `StateFlow<AttentionSessionLiveState>` | Attention Lab, Cloud tab (`isRecording`) |
| `calibrationProfilesState` | `StateFlow<UiDataState<List<CalibrationProfile>>>` | Cloud tab |
| `trackingSessionsState` | `StateFlow<UiDataState<List<TrackingSession>>>` | Cloud tab |
| `enrollmentState` | `StateFlow<EnrollmentScanState>` | Calibrate tab, enrollment overlay |
| `enrollmentBaseline` | `StateFlow<EnrollmentBaseline?>` | Calibrate tab (enrollment card) |
| `aacTilesState` / `aacTiles` | `StateFlow<List<AacActionTile>>` / snapshot | Control Deck |
| `readingPassageSections` | `List<SectionPassageItem>` (3 fixed sections) | Attention Lab |
| `UiDataState` | sealed: `Loading`, `Success(data)`, `Error(message)` | lists |
| `CalibrationFlowStep` | `IDLE, FIT_5_POINTS, VALIDATE_4_POINTS, COMPLETED_SUMMARY` | |
| `UNCALIBRATED_PROFILE_NAME` | `"Uncalibrated (default matrix)"` | HUD / overview |
| top-level `isRunningOnEmulatorHardware()` | `Boolean` | HUD labels |

### 3.2 Public functions

| Function | Purpose | Called from |
|---|---|---|
| `submitCameraFrame(feature)` | Main-thread entry for camera frames (dropped while sim on) | `OpticalDiagnosticsBar.onFeatureFrame` |
| `processIncomingFeatureFrame(feature)` | Full per-frame pipeline (projection, filters, blink/brow detectors, dwell, drift, nudge, enrollment, calibration, attention) | internal / tests |
| `setTriggerMode(GazeTriggerMode)` | Trigger modality; persisted | Deck chips |
| `cycleOverlayVisualMode()` | Reticle / heatmap layers | HUD chip |
| `cycleAnalysisResolutionTier()` | 1280 → 960 → 640 analysis size | HUD badge |
| `calibrateNeutralBrowBaseline()` / `calibrateRaisedBrowPeak()` | 2-step manual brow calibration (banners) | Deck |
| `simulateBrowRaiseCommand()` / `simulateIntentionalCommandBlink()` | Synthetic gestures (sim pad) | HUD sim pad |
| `updateSimulatorGazeTarget(normX, normY, simulatedEyeOpen = 0.92f)` | Synthetic gaze vector; no-op unless sim/emulator | HUD pad, wizard |
| `toggleSimulatorProxyMode(enabled)` | Sim proxy on/off (always on for emulators) | permission flow, HUD |
| `updateActiveReadingSectionFromLayout(sectionId?)` / `updatePassageGazePoint(Offset?)` / `detachReadingSectionLayout()` | Attention hit-test inputs | Attention Lab |
| `updateAacTilePhrase(tileId, newTitle, newSpeechPhrase)` | Edit tile (ignores blanks; persisted; banner) | Deck edit dialog |
| `dismissRecalibrateNudge()` | Clears nudge + edge timer | HUD |
| `setDisplayPhysicalSizeMm(widthMm, heightMm)` | mm scaling for error figures | workbench |
| `registerTileTriggerListener(listener)` | TTS/haptic hook | workbench |
| `dismissStatusBanner()` | Clears banner | banner row |
| `toggleCloudSync(enabled)` | Opt-in flag (persisted; banner) | inline bar, Cloud tab |
| `updateFocusedTile(tileId?, tileCenterNorm? = null)` | Hovered tile (resets dwell) | Deck hit-test |
| `updateTileCentersNorm(centers)` | Drift-correction references | Deck |
| `fireTileAction(tileId, triggerMethod = "Tap")` | Speak / recenter / read summary | Deck tap, VM gestures |
| `setPostureProfile(GazePostureProfile)` / `setGazeFilterMode(GazeFilterMode)` | Posture bias / filter (persisted; banners) | Dwell dialog |
| `runBenchmarkSuite()` | JVM synthetic replay (banner + report) | Dwell dialog |
| `setDwellTriggerDuration(ms)` (400–2500, snaps `activeDwellPreset` to nearest) / `setDwellSpeedPreset(preset)` (banner) / `setDwellSensitivityMultiplier(0.6–2.0)` | Dwell tuning (persisted) | Deck, dialog, Cloud tab |
| `setBlinkTimingThreshold(minCommandMs, closeThresh)` | Blink detector tuning (280–700 ms, 0.15–0.40) | **no UI caller** |
| `startEnrollmentScan()` / `cancelEnrollmentScan()` / `skipEnrollmentBrowStep()` / `clearEnrollmentBaseline()` | Enrollment lifecycle (banners) | Calibrate tab, overlay |
| `setIncludeHeldOutValidation(Boolean)` | Wizard mode | Calibrate chips |
| `startCalibrationAndValidationWizard(includeValidation = current)` | Starts wizard (cancels enrollment scan; snapshots engine) | Calibrate, summary Retry, nudge |
| `cancelCalibrationWizard()` | Only while collecting | Calibrate |
| `updateDraftProfileName(name)` | Summary field | Calibrate |
| `captureManualSamplesForCurrentTarget(sampleBatchCount = 18)` | Manual capture (live: waits for frames; sim: replicates vector) | Calibrate |
| `saveCompletedCalibrationProfile()` | Persists profile locally (+ cloud when opted in and not simulated) | Calibrate summary |
| `activateSavedProfile(profile)` / `deleteCalibrationProfile(profileId)` | Profile management (banners) | Cloud tab |
| `startAttentionRecording()` / `stopAndSaveAttentionSession(customTitle = "Section Reading Telemetry")` / `clearSessionReplay()` / `selectSessionForReplay(session)` / `computeSectionSummariesForDisplay()` / `deleteTrackingSession(sessionId)` | Attention sessions | Attention Lab, Cloud tab |
| `deleteFirestoreCloudTelemetry()` | Cloud-only erase (keeps vault) | inline bar, Cloud tab |
| `eraseAllCloudTelemetry()` | Vault + cloud wipe; resets tiles, enrollment, engine | Cloud tab |
| `onCleared()` | cancels jobs | framework |
| `currentMatrixForTest()` (internal) | tests | — |

### 3.3 `LiveGazeHudState` fields (defaults)

`normX = 0.50f`, `normY = 0.50f`, `rawFeature: GazeFeatureVector`, `blinkHoldProgress = 0f`, `browHoldProgress = 0f`, `lastBlinkLabel = "Ready (Brow 400ms • Blink 350-800ms • Nat 80-349ms)"`, `naturalBlinkCount = 0`, `intentionalBlinkCount = 0`, `browRaiseTriggerCount = 0`, `triggerMode = ALL_ACTIVE`, `overlayVisualMode = RETICLE_AND_HEATMAP`, `liveTrailPoints = []`, `neutralBrowRatio = 0.34f`, `raisedBrowRatio = 0.42f`, `hasDistanceDriftWarning = false`, `showRecalibrateNudge = false`, `isPerformanceModeActive = false`, `resolutionTier = TIER_HIGH_1280`, `isSimulatorProxyEnabled = false`, `cloudSyncEnabled = false`, `isDeletingCloudData = false`, `lastCloudDeletionSummary: String? = null`, `activeProfileName = UNCALIBRATED_PROFILE_NAME`, `activeProfileId: String? = null`, `activeProfileDeviceModel = Build.MODEL ?: "Pixel 10 Pro XL"`, `activeErrorRadiusPct: Float? = null` (null = not validated), `activeMaxQuadrantErrorPct: Float? = null`, `activeErrorMm: Double? = null`, `hasActiveCalibration = false`, `analyzerFps = 0`, `dwellTriggerMs = 1000L`, `dwellSensitivityRadiusMultiplier = 1.0f`, `activeDwellPreset = BALANCED`, `focusedTileId: String? = null`, `focusedTileDwellProgress = 0f`, `activePosture = UPRIGHT`, `filterMode = ONE_EURO`, `backgroundRecalibrationCount = 0`, `lastBenchmarkReport: BenchmarkScorecard? = null`, `isBenchmarkRunning = false`, `lastTriggeredSpeech = "Fixate on any tile and raise brows (400ms), command-blink (350–800ms), or dwell to speak."`, `enrolledAtMs: Long? = null`.

`GazeFeatureVector` fields the UI prints: `gx, gy, yaw, pitch, roll, eyeDistancePx (148.0), leftEyeOpenProb, rightEyeOpenProb (0.92), leftEyeConfidence, rightEyeConfidence (0.90), browToEyeRatio (0.34), isSingleEyeMode, activeEyeLabel ("BINOCULAR"), hasGlareWarning, faceDetected (false), faceBox*, *EyeNorm*, *PupilNorm*, *BrowNormY, frameWidthPx (960), frameHeightPx (1280), sensorRotationDeg (270), ambientLuma (142), isLowLightDegraded, timestampMs, isSimulated`; derived `combinedEyeOpenProb`.

### 3.4 `CalibrationWizardState` fields

`step = IDLE`, `includeHeldOutValidation = false`, `currentTargetIndex = 0`, `currentTargetProgress = 0f`, `samplesCollectedForCurrentTarget = 0`, `requiredSamplesPerTarget = 18`, `pointSampleCounts: Map<Int, Int> = {}`, `totalCollectedSamples = 0`, `isSettlingDelay = false`, `fittedMatrix = DEFAULT_AFFINE_MATRIX_2X6`, `capturedBaselineEyeDistPx = 148.0`, `validationMeanErrorMm = NaN`, `validationMeanErrorPct = NaN`, `validationMaxErrorPct = NaN` (NaN = not measured), `recommendedGridLabel = "Not validated"`, `draftProfileName = "Pixel 10 Pro XL Desk"`, `metricsAreHeldOut = false`.

### 3.5 `AttentionSessionLiveState` fields

`isRecording = false`, `elapsedSeconds = 0`, `activeSectionId: String? = null`, `sectionDwellMsMap: Map<String, Long> = {}`, `topZoneMs = 0L`, `middleZoneMs = 0L`, `bottomZoneMs = 0L`, `sessionNaturalBlinks = 0`, `sessionIntentionalBlinks = 0`, `computedClusters: List<FixationClusterPoint> = []`, `fpsTimeline: List<Int> = []`, `thermalThrottleDetected = false`, `selectedReplaySession: TrackingSession? = null`.

### 3.6 `EnrollmentScanState` fields (`domain/EnrollmentScan.kt`)

`phase: EnrollmentPhase = IDLE` (IDLE, POSITIONING, EYES_OPEN, EYES_CLOSED, BROW_RAISE, COMPLETE, FAILED), `guidance: EnrollmentGuidance = NO_FACE` (NO_FACE, CENTER_FACE, MOVE_CLOSER, MOVE_BACK, MORE_LIGHT, REDUCE_GLARE, HOLD_STILL, LOOK_AT_DOT, CLOSE_EYES, KEEP_EYES_CLOSED, OPEN_EYES, RAISE_BROWS, DONE, FAILED), `phaseProgress = 0f`, `overallProgress = 0f`, `elapsedMs = 0L`, `retries = 0`, `warnings: Set<EnrollmentWarning> = {}` (LOW_LIGHT, GLARE, SINGLE_EYE, FEW_NATURAL_BLINKS, EYE_RANGE_SMALL_DEFAULTS_USED, BROW_SKIPPED), `liveEyeOpenProb = 0f`, `liveFaceCentered = false`, `result: EnrollmentBaseline? = null` (COMPLETE only), `failure: EnrollmentFailure? = null` (POSITIONING_TIMEOUT, FACE_LOST, EYES_DID_NOT_CLOSE, EYES_DID_NOT_REOPEN, SIMULATED_FRAMES, CANCELLED).

### 3.7 Enums the UI renders (`data/model/GazeModels.kt`)

* `GazeTriggerMode(label)`: ALL_ACTIVE "Brow Raise + Blink + Dwell", BROW_RAISE "Brow Raise (400ms Hold) + Dwell", COMMAND_BLINK "Command Blink (350-800ms) + Dwell", DWELL_ONLY "Dwell Only (No Gesture)".
* `DwellSpeedPreset(title, subtitle, durationMs, radiusToleranceMultiplier)`: FAST_READER ("Fast Reader", "Rapid scanning • 650 ms", 650, 0.85), BALANCED ("Balanced", "Default tracking • 1,000 ms", 1000, 1.0), RELAXED ("Relaxed / Steady", "Stable & deliberate • 1,400 ms", 1400, 1.25), MOTOR_ASSIST ("High Precision Assist", "Forgiving dwell • 1,900 ms", 1900, 1.55).
* `GazeOverlayVisualMode(shortLabel)`: "Cursor + Heatmap", "Cursor Only", "Heatmap Only".
* `AnalysisResolutionTier(w, h, shortLabel)`: 1280×960 "1280w (High)", 960×720 "960w (Step-Down 1)", 640×480 "640w (Thermal Saver)".
* `GazePostureProfile(label, shortName, xBias, yBias)`: UPRIGHT "Sitting Upright (Desk / Mount)", RECLINING "Reclining (45° Tilt)" (−0.035), LYING_DOWN "Lying Down Flat (15° Tilt)" (−0.070).
* `GazeFilterMode(label, shortName)`: ONE_EURO "1€ Speed-Adaptive Filter (Recommended)"/"1€ Filter", KALMAN "Closed-Form 2D Kalman Filter"/"Kalman", RAW "Raw Unfiltered Stream"/"Raw".
* `AacActionTile(id, title, subtitle, speechPhrase, category)`; `CalibrationProfile` (name default "Pixel 10 Pro XL Desk Profile", `deviceModel`, `meanErrorMm/Pct`, `maxErrorPct`, `heldOutValidated`, `isActive`, …); `TrackingSession` (title, durationSeconds, blinksPerMinute, effectiveWordsPerMinute, fixationClusterCount, zone percentages, sectionLoads, clusterPoints); `EnrollmentBaseline` (eye levels, blink thresholds, `intentionalBlinkMinMs`, natural blink stats, brow ratios, luma, eye confidence, frameCount, warnings, capturedAtMs, deviceModel).

---

## 4. Strings

### 4.1 `res/values/strings.xml` (27 entries; all used; pinned verbatim by `ActivityOptimizationTest.FROZEN_USED_STRINGS`)

| name | value | used by |
|---|---|---|
| `app_name` | Æye | manifest label, tests |
| `auth_title` | Æye Optical Workbench | AuthScreen |
| `auth_subtitle` | Region-level front-camera gaze tracking, 5-point calibration + 4-point held-out validation, pitch-compensated brow-raise & command-blink triggers, and section attention analytics. | AuthScreen |
| `sign_in_google` | Sign in with Google | AuthScreen |
| `privacy_notice_title` | Biometric Telemetry & Privacy Architecture | AuthScreen |
| `privacy_notice_body` | Raw front-camera frames are analyzed strictly in volatile device RAM to extract Eye-ROI pupil centroids, eyebrow displacement, and 3D head Euler angles—camera images are never saved or transmitted. Derived calibration matrices and session summaries persist locally on-device by default and sync to your private Cloud Firestore vault only when Cloud Sync is explicitly enabled. | AuthScreen |
| `nav_calibrate` / `nav_control` / `nav_attention` / `nav_cloud` | Calibrate / Control Deck / Attention Lab / Cloud & Privacy | NavigationBar |
| `cloud_telemetry_opt_in_title` | Cloud Telemetry Opt-In (Firestore Sync) | inline bar a11y, Cloud tab |
| `cloud_telemetry_status_opted_in` | OPTED IN: Calibrations & session summaries sync to your private Firestore vault. | Cloud tab |
| `cloud_telemetry_status_opted_out` | OPTED OUT (Default): Zero cloud telemetry — all data stays strictly on-device. | Cloud tab |
| `delete_firestore_telemetry_button` | Delete Cloud Data (Firestore) | Cloud tab, inline icon a11y |
| `deleting_firestore_telemetry_label` | Deleting Firestore Data… | Cloud tab |
| `confirm_delete_cloud_dialog_title` | Delete Cloud Telemetry from Firestore? | both delete-cloud dialogs |
| `confirm_delete_cloud_dialog_body` | This permanently deletes all calibration profiles and attention session telemetry stored under /users/%1$s in Cloud Firestore. Your on-device local calibration profile will remain intact. | both dialogs |
| `confirm_delete_cloud_action` | Delete from Firestore | both dialogs |
| `cancel_action` | Cancel | dialogs, wizard |
| `tts_unavailable_warning` | Speech output unavailable: install or enable a text-to-speech engine in system settings. Tile phrases are shown but not spoken. | TTS banner |
| `calibration_5_point_title` | 5-Point Guided Screen Calibration | overview card |
| `calibration_5_point_subtitle` | Fixate on each of the 5 screen coordinates (Center, Top-Left, Top-Right, Bottom-Left, Bottom-Right) as the target ring pulses to collect binocular iris & 3D head-pose gaze data points. | overview card |
| `calibration_mode_5_point_only` | 5-Point Quick Fit | mode chip, caveats |
| `calibration_mode_5_plus_4` | 5-Point Fit + 4-Point Validation | mode chip, caveats |
| `start_5_point_calibration` | Start 5-Point Calibration | start button |
| `capture_gaze_sample_action` | Capture Point | wizard |

Other resources: `firebase_applet_config.xml` → `firestore_database_id` (shown on the Auth screen and Cloud tab); `default_web_client_id` (Google sign-in, from google-services). Fonts: `space_grotesk.ttf`, `plus_jakarta_sans.ttf`, `jetbrains_mono.ttf`. Drawable `ic_optics_hero_illustration`.

### 4.2 Hard-coded UI strings (Kotlin, English, `Locale.US` formatting — not localizable)

The overwhelming majority of visible text is hard-coded in composables and helpers: every HUD label, warning sentence, deck/dialog/attention/cloud title, enrollment guidance (`enrollmentGuidanceText`, `enrollmentPhaseLabel`, `enrollmentWarningLabel`, `enrollmentFailureText`), calibration headings/pills/badges/caveats, metric labels ("MEAN SCREEN ERR", "FIT RESIDUAL (IN-SAMPLE)", …), constants `ENROLLMENT_EXPLANATION_TEXT`, `ENROLLMENT_PRIVACY_TEXT`, "Not calibrated" / "Not measured" / "Not validated" / "5-point fit only", `GazeCalibrationMath.gridLayoutLabel` ("3×3 Validated Deck (9 tiles)" / "2×2 Protective Region Deck (4 tiles)"), target labels ("Fit 1/5 • Center" … "Validate 4/4 • Lower-Right Quadrant"), enum labels (§3.7), default tile titles/phrases, the 3 passage sections, auth error messages, and the dialog copy for Wipe / Delete Profile / Delete Session.

### 4.3 Status-banner messages produced by the VM (all shown in the one mono banner row)

Brow calibration: "No face detected — face the front camera and try again." · "Captured Neutral Brow baseline (0.000). Now raise eyebrows and tap 'Sample Raised Brow'." (button is actually labelled "2. Raised Brow") · "Brow raise not detected (… vs neutral …) — raise higher and retry." · "Expression calibrated: Neutral … → Raised … (Trigger @ …)."
Tiles: "Updated phrase tile "<title>"." · "Gaze recentered: offset ±x.x% x, ±x.x% y applied." · "Recenter cancelled — face not stable. Look at the centre of the screen and try again."
Cloud: "Cloud Telemetry OPTED IN — calibrations & sessions sync to Firestore (/users/<uid>) and local storage." · "Cloud Telemetry OPTED OUT — Firestore sync halted; profiles & sessions stay strictly on-device." · "Deleted n Firestore cloud document(s) under /users/<uid> (on-device vault preserved)." · "Firestore deletion error: …" · "Erased all local & Firestore biometric records (n cloud docs removed)." · "Cleared local vault; cloud erase error: …"
Settings: "Posture Profile: <label> (Y-bias ±0.000 applied relative to the calibration posture)." · "Gaze Stabilization: <label>" · "Dwell Speed Preset: <title> (n ms • n% tolerance)" · "Synthetic replay benchmark PASS|FAILURES DETECTED: …" · "Benchmark failed: …"
Enrollment: "Enrollment scan started — face the front camera and follow the on-screen guidance (about 15 seconds)." · "Enrollment needs the live front camera — …" (sim / emulator variants) · "Finish or cancel the running calibration before starting the enrollment scan." · "Enrollment scan cancelled — blink and brow detection are unchanged." · "Enrollment complete: eyes open … / closed … → blink close < …, re-open > …, command blink ≥ n ms; brow neutral … → raised …; n natural blink(s), median n ms, over n frames. Warnings: …" · "Enrollment failed: …" (6 variants) · "Enrollment cleared — …" (2 variants) · "Enrollment ended without a measurable result — nothing was changed."
Calibration: "Enrollment scan cancelled: calibration started. …" · "No face detected — face the front camera, look at the target and try again." · "Capturing live gaze samples — keep looking at the target." · "Calibration error was not measured — run the calibration again before saving." · "Calibration saved to on-device storage (Cloud Sync is paused)." · "Calibration profile synced to Cloud Firestore & local vault." · "Saved locally; Cloud sync error: …" · "Simulated calibration saved on this device only (not synced, labelled SIMULATED)." (+ eviction note) · "Activated profile: <name> (x% mean • x% max)" / "(accuracy not validated)" · "Calibration profile deleted from this device and Cloud Firestore." · "Deleted on this device; cloud delete failed: …" · "Local calibration profile deleted."
Sessions: "Session saved to on-device storage (Cloud Sync is paused)." · "Attention session synced to Cloud Firestore & local vault." · "Simulated session saved on this device only …" · "Stop & Save the current recording before opening a replay." · "This session was recorded before passage-aligned fixations; its fixation heatmap is not shown." · "Session deleted from this device and Cloud Firestore." · "Local session deleted."

---

## 5. UI tests and what they pin

Test classes under `app/src/test` that reference a composable, a `testTag`, or UI text (Robolectric + `createComposeRule`, sdk 36). "Pins" = the exact tag / text / behaviour that will break if the redesign changes it.

### `enrollment/UiEnrollmentTest.kt` — `UiEnrollmentTest` (CalibrationStudioScreen enrollment card, EnrollmentScanOverlay, host)
* `card_withoutBaseline_showsNotEnrolled_explanation_andStartInvokesCallback` — `enrollment_status_text` == "Not enrolled"; explanation contains "15-second scan" and "No images are stored"; no rescan/clear/outcome nodes; `start_enrollment_button` enabled and fires `onStartEnrollment` once.
* `card_withBaseline_showsEnrolledSummary_andRescanClearInvokeCallbacks` — status contains "Enrolled " + year; start/explanation absent; the 6 exact summary lines exist (e.g. "Blink thresholds: close 0.40 • open 0.63"); `enrollment_warnings_text` == "Warnings: Low light, Few natural blinks"; rescan → start callback, clear → clear callback.
* `card_showsOutcomeOfTheScanThatJustEnded_andDisablesButtonsWhileScanning` — FAILED shows "Scan failed: Eye closure was not detected"; active scan shows "Scanning…" and disables start; COMPLETE shows `enrollmentCompleteSummary(baseline)`; rescan/clear disabled while scanning.
* `overlay_showsGuidanceAndPhase_skipBrowOnlyInBrowRaise_cancelInvokesCallback` — exact guidance/phase strings ("Now close both eyes.", "Step 3 of 4 — Eyes closed", "Raise your eyebrows and hold them up.", "Step 4 of 4 — Raise your brows"), "Eye openness 0.87", warning chip texts, "Retry 1 • 9s elapsed", fixation-dot contentDescriptions, skip button only in BROW_RAISE, callbacks fire once each.
* `overlay_composesNothingOutsideTheActivePhases` — `enrollment_scan_overlay` absent in IDLE/COMPLETE/FAILED, present in active phases.
* `overlay_passesTouchesThroughOutsideItsButtons` — a node under the scrim receives the click; Cancel still works.
* `overlayHost_cancelsWhenLeavingCompositionMidScan_butNotOnCompletion` — host disposal mid-scan calls `onCancel` once; completion does not.
* `helpers_activePhases_guidanceTexts_andLabelsAreCompleteAndDistinct` — every guidance/phase/warning/failure value has a distinct non-blank text; `enrollmentGuidanceNeedsFixation` table.
* `helpers_summaryNeverInventsAMeasurement_andFormatsLocaleUs` — 6 summary lines, "none"/"raised not measured" wording, Locale.US numbers, `formatEnrollmentCapturedAt` format, `enrollmentSecondaryLine` cases.

### `regression/DeckRegressionTest.kt` — `DeckRegressionTest` (GazeControlDeckScreen, DwellTriggerSettingsDialog)
* `leavingTheDeck_clearsTheHoveredTile` — disposing the deck reports `onUpdateFocusedTile(null)`.
* `settingsDialog_suspendsGazeFocus_andHitTestingResumesOnClose` — `open_dwell_settings_button` clears focus; no focus while open; `dismiss_dwell_settings_button` resumes hit-testing.
* `editDialog_suspendsGazeFocus_andSaveNeedsLabelAndPhrase` — `edit_aac_tile_t1` clears focus; "Save" disabled with a blank phrase and emits nothing; enabled after typing; edit tuple delivered.
* `tilesThatLeftTheGrid_areNotHitTestable` — `aac_tile_t9` absent in 2×2; a reticle at its old position focuses nothing.
* `focusHysteresis_isFourteenDpOnTheDeviceDensity` — focus kept within 14 dp of the tile, lost beyond.
* `deckLayout_needsMeasuredAccuracy_andMatchesTheSharedClassifier` — null metrics → 4 tiles and no "Validated Deck" text, `gridLayoutLabel(false)` shown; qualifying metrics → 9 tiles; non-qualifying → 4.
* `longTriggeredSpeech_doesNotMoveTheTileGrid` — tile bounds identical before/after a long `lastTriggeredSpeech`.
* `deckDwellSlider_representsEveryPreset` — `deck_dwell_duration_slider` range 400–2200, current 650, 1900 reachable.
* `dialogDurationSlider_roundsTicks_andPresetSelectionChecksTolerance` — one "Selected" icon only when duration and tolerance both match; slider ticks round to 1900.
* `benchmarkSummary_reportsTheRunsOwnResults` — `benchmark_summary_text` contains "FAILURES DETECTED", "3 false triggers (3 in Midas-touch scenarios, 2.0/min)", gated counts, "post-feature JVM processing median 0.004 ms", "synthetic projection check", "not measured gaze accuracy"; never "0 false blinks", "Pixel 10 Pro XL Stress Benchmark", "thermal endurance", "synthetic-trace accuracy".
* `tileCentersInGazeFrame_normalizesToTheOverlayFrame` / `deck_reportsVisibleTileCentresInTheGazeFrame_andClearsThemOnLeave` — centre normalisation math; 4 or 9 ids reported; empty map on leave / unknown frame.
* `browRatioReadout_usesLocaleUs` — exact text "Brow Ratio: 0.340 (N:0.34→R:0.42)" under a comma-decimal locale.

### `regression/ActivityRegressionTest.kt` — `ActivityRegressionTest` (no compose) + `ActivityRegressionComposeTest` (InlineCloudPrivacyBar)
* manifest TTS query / backup rules / data-extraction rules exclude the vault; `tileTriggerHapticFeedbackConstant` API fallback; `shouldClearUserScopedViewModels` truth table.
* `inline Delete Cloud deletes only after the confirmation dialog is accepted` — `delete_firestore_data_button` alone deletes nothing; `confirm_delete_cloud_button` deletes once and the dialog closes.
* `cancelling the inline Delete Cloud confirmation deletes nothing` — `cancel_delete_cloud_button` path.

### `regression/AuthRegressionTest.kt` — `AuthRegressionTest` (sign-in/out logic, no compose) + `AuthScreenCopyRegressionTest`
* sign-out clears credential state before Firebase sign-out, completes on timeout / cancelled scope; auto sign-in suppressed after sign-out; failures logged with provider message; cancelled scope not reported as failure; configuration errors show actionable text ("No Google account is available…", "needs an up-to-date version of Google Play services", "Sign-in was interrupted").
* `blinkCopy_advertisesTheDetectorsRealCommandWindow` — AuthScreen text contains "<intentionalMinMs>–<maxCommandMs>ms command blinks" (i.e. "350–800ms command blinks").

### `regression/CalibscreenRegressionTest.kt` — `CalibscreenRegressionTest` (CalibrationStudioScreen, FivePointCalibrationScreen, overlay)
* `captureButton_neverSnapsSimulatorUnlessSimulatorIsAlreadyOn` — `capture_gaze_point_button` captures without moving the sim on hardware; snaps to (0.14, 0.16) when sim is on.
* `tappingNonCurrentTargetNode_recordsNothingUnderTheCurrentTarget` — only `calibration_point_target_<current>` captures.
* `leavingCompositionMidWizard_cancelsWizard_butStepTransitionsAndSummaryDoNot` — cancel called once on dispose mid-fit, not on FIT→VALIDATE→SUMMARY.
* `wizardHeader_reportsNoFace_insteadOfEndlessSettling` — counter contains "No face detected", then "Settling".
* `idleOverview_uncalibratedHud_showsNotCalibrated_notInventedNumbers` — two "Not calibrated", no "±10.9%", "±14.2%", "13.4 mm"; validated HUD shows "±4.2%", "±6.5%".
* `idleOverview_appliedFitWithoutHeldOutValidation_showsNotValidated_notNotCalibrated` — two "Not validated", two "5-point fit only", no "Not calibrated"/"Default matrix".
* `idleTelemetry_noFaceFrame_showsDashes_notZeroConfidenceOrDefaultIod` — `calibration_eye_telemetry_text` "Left -- • Right -- | IOD: -- | Eye Open: --"; measured frame "Left 81% • Right 77% | IOD: 151.2 px | Eye Open: 0.90".
* `summary_quickFit_labelsInSampleResidual_notValidatedScreenError` — "FIT RESIDUAL (IN-SAMPLE)" / "WORST FIT POINT" + caveat for quick fit; "MEAN SCREEN ERROR" / "WORST QUADRANT" / "±2.3%" and no caveat when held-out.
* `summary_unmeasuredMetrics_showNotMeasured_neverNaN` — no "NaN", two "Not measured".
* `fullscreenOverlay_drawsCurrentTargetInItsOwnFrame_andPassesTouchesThrough` — `currentCalibrationFixationTarget` / `calibrationTargetCenterPx`; overlay present in fit step, absent in IDLE; underlying click passes.

### `regression/AnalyticsprivacyRegressionTest.kt` — `AnalyticsprivacyRegressionTest` (helpers) + `AnalyticsprivacyComposeRegressionTest` (CloudPrivacyScreen, AttentionAnalyticsScreen)
* helper tests: `resolveLayoutHitReport` null vs off-passage; `passageLocalGazePoint` normalisation incl. scroll; `shouldShowThermalGuard` only while recording; "est. WPM n/a", "Zones n/a", no "0%" for empty sessions; "dwell n/a" vs "0% dwell"; `sliderValueToDwellMs` rounding; `formatSensitivityLabel` "0.85× (12 dp)"; `indexOfActiveProfile` by id; `formatProfileErrorLine` says "held-out validation" or "in-sample fit (unvalidated)".
* `wipe cloud and local vault runs only after confirmation` — `erase_all_cloud_button` → `destructive_action_confirm_dialog`; cancel = nothing; confirm = once.
* `delete firestore data runs only after confirmation` — `delete_firestore_data_button` + `confirm_destructive_action_button`.
* `deleting a profile row asks for confirmation first` — `delete_profile_p1` then confirm.
* `sign out is disabled while cloud deletion is in flight` — `sign_out_button` not enabled.
* `session replay cannot be opened while a recording is running` — `session_row_s1` click ignored; `replay_locked_while_recording_text` present.
* `cloud sync switch carries the opt-in label` — `cloud_sync_toggle` contentDescription == `cloud_telemetry_opt_in_title`.
* `thermal guard banner is hidden when not recording` — no "Thermal Guard" text.
* `attention screen reports section hits and passage points and resets on dispose` — reticle over "Alpha heading" → "sec_1" + passage-local point; off → `ATTENTION_OFF_PASSAGE_SECTION_ID` + null.

### `optimization/CalibscreenOptimizationTest.kt` — `CalibscreenOptimizationTest` (formatters) + `CalibscreenOptimizationUiTest`
* formatter tests: eye telemetry / raw feature / sample counter / pill & summary coordinate labels / matrix text / pulse spec equal their frozen implementations for randomised inputs.
* `idleOverview_tracksEveryHudFrame_exactlyAsTheFrozenFormatting` — `calibration_eye_telemetry_text` equals frozen text for every frame/tier.
* `wizard_counterHeadingAndPills_matchFrozenTextAfterEverySample` — counter, `active_calibration_target_label`, pill children "✓ Pn"/"Pn", coord label, "n/required".
* `summaryReport_matchesFrozenTextAcrossKeystrokesAndMatrixChanges` — caveat present iff not held-out; texts stable across keystrokes.

### `optimization/ActivityOptimizationTest.kt` — `ActivityOptimizationTest` + `ActivityOptimizationComposeTest` + `ActivityOptimizationHelpersTest`
* `calibration target predicate matches the frozen overlay condition for every step and tab`.
* `MainActivity resolves to the same label, theme and launcher entry as before` — `@string/app_name`, `@style/Theme.MyApplication`, permissions INTERNET/CAMERA/VIBRATE, camera features optional, backup attributes.
* `strings xml keeps every used string unchanged and only drops strings nothing references` — **pins the exact name→value map of all 27 strings** (`FROZEN_USED_STRINGS`); any copy change in `strings.xml` fails this test until the frozen map is updated.
* `ReadInOwnScope …` ×2, `derived calibration-target visibility …`, `remembered auth flow …` — recomposition-isolation behaviour.
* `camera permission helper …`, `prefetched vault …` — helper parity.

### `optimization/AnalyticsprivacyOptimizationTest.kt` — `AnalyticsprivacyOptimizationTest` (formatters/heatmap cache) + `AnalyticsprivacyComposeOptimizationTest`
* formatters match references; heatmap frame geometry/brushes bit-identical; cluster label cache parity.
* `attention header and section labels follow per-frame state like the inline layout did` — "Recording (12s) • Nat Blinks: 3 • Cmd Blinks: 1", "Section Dwell & Fixation Centroid Lab", "64% dwell"/"36% dwell", stop/start buttons, "Thermal Guard" shown while recording+throttled; replay title "Session Replay: Morning read", replay subtitle, `close_session_replay_button`, idle description text.
* `hit reports are unchanged when relayouts re-deliver equal section bounds`.
* `heatmap node is present exactly while clusters are` — `attention_heatmap_canvas`.
* `privacy screen content follows the hud fields it shows and ignores per-frame ones` — "1.00× (14 dp)" → "1.55× (22 dp)", opted-in/out strings, `speed_preset_chip_*`, `cloud_deletion_status_text` "Deleted 3 cloud documents", `sign_out_button` disabled while deleting.
* `keyed session rows keep the right summary and click target when the list shifts` / `keyed profile rows keep the right error line and delete target, duplicates included` — `session_row_<id>` text, `delete_profile_<id>` targets.

### `optimization/AuthOptimizationTest.kt` — `AuthOptimizationTest` (flows) + `AuthScreenOptimizationTest`
* sign-in/sign-out flows match a frozen implementation for every credential outcome, context, gate state and seeded random sequences.
* `optimized AuthScreen renders the same semantics tree as the frozen screen` — **pins the entire AuthScreen semantics tree** (bounds and every property) across default / large-font / dark variants against a frozen copy of the screen embedded in the test.

### Adjacent (no composable, but pin UI text or theme)
* `regression/DiagRegressionTest.kt` — `MainThreadFrameConflator` behaviour; `browRaiseTriggerThreshold` uses the detector formula; `analyzerStatusLabel` says "NO FRAMES" not "0 FPS"; `eyeOpenBrowLine` dashes without a face, Locale.US with; `gazeTelemetryLine` says "uncalibrated" and hides no-face placeholders; `cameraPipelineLabel` makes no fixed hardware claims and flags synthetic frames; `glareWarningSentence` follows eye selection; `OpticalWarningLatch` ignores no-face frames except low light; `opticalWarningText` orders low-light first and is null when clear; `reticleUncertaintyRadiiPx` per-axis, uncapped, finite & wide when not validated.
* `regression/BuildthemeRegressionTest.kt` — fonts declared only at Regular weight; `surfaceContainer*` roles use the slate palette; unchecked `Switch` handle/border ≥ 3:1 against surfaces.
* `ExampleRobolectricTest` — `app_name` == "Æye" (plus detector/math tests). `VmRegressionTest` / `VmOptimizationTest` / `VmEnrollmentTest` assert VM banner strings and state (not composables).

---

## 6. Known UI defects visible in the current build

1. **HUD header squeeze / one-word-per-line status text.** The header `Row` gives the status column `weight(1f)` and the chip row `weight(1.1f, fill = false)` + `horizontalScroll`; the chips take more than half the width, so the status label truncates to an ellipsis and the un-clamped telemetry line ("Gaze (50%, 50%) • L:-- R:-- • uncalibrated • Luma:--") wraps into a narrow column — at font scale ≥ 1.3 on a 411 dp phone it degenerates to one or two tokens per line. The badge row (resolution / single-eye / LOW FPS) does not wrap at all.
2. **Stacked banners and bars above every tab.** HUD card (header, camera row, 2-line warning slot, nudge, blink row, hold bar, sim pad) + inline cloud bar + TTS banner + status banner all sit above the content. On the Control Deck the tile grid is left with roughly 40 % of a phone screen; the enrollment overlay and calibration target are also partially covered by the HUD because they draw in the root frame while the HUD occupies its top.
3. **Telemetry shown to end users everywhere**: FPS, resolution tier, iris centroid (gx/gy/IOD), head Euler angles, eye-open probabilities, luma, brow ratios to three decimals, `f_raw = [...]` vector, fitted matrix `W_x/W_y`, "Quiet background drift updates: n micro-bias adjustments", "Eye openness 0.87" during enrollment, raw benchmark statistics, Firebase UID and Firestore paths.
4. **Reticle and heatmap drawn over controls.** `RegionConfidenceReticleOverlay` is the top-most layer of the content Box: the ellipse, ticks, "xx%, yy%" pill and heat-trail cover tile labels, the passage text being read, HUD text and list rows. In "Cursor + Heatmap" (the default) the trail further reduces legibility of the Attention Lab passage.
5. **Layout shifts under gaze**: the recalibrate nudge (`AnimatedVisibility`), the simulator pad, the TTS banner and the status banner all appear/disappear above the tile grid and move the tiles while a dwell may be in progress (only the warning slot and the speech line were given fixed heights).
6. **Type sizes below the target floor.** Most status, warning, banner, counter and list-detail text is JetBrains Mono `labelSmall` 11 sp or `labelMedium` 12 sp; tile phrases and many descriptions use un-themed `bodySmall` (Roboto 12 sp); the reticle pill and heatmap numbers are hard-coded 10 sp. Deck tile titles are 18 sp (2×2) / 15 sp (3×3), below the 24 sp tile-label target. `headlineSmall` and `titleSmall` are not in the `Typography` override, so the enrollment guidance and the enrollment start button render in Roboto.
7. **Touch targets under 48 dp**: tile edit `IconButton` 28 dp with a 15 dp icon, resolution badge (text + 1 dp padding), `FilterChip`s (32 dp), status-banner "Dismiss" (plain text; the whole row is clickable without a button role), 26 dp-high deck slider, 10 dp status dot (information carrier with no label of its own).
8. **Device marketing / unverified claims**: "PIXEL 10 PRO XL • OPTICAL GAZE WORKBENCH" badge, "Live 6D Feature Vector & Pixel 10 Pro XL Telemetry", dialog copy "…dwell thresholds for Google Pixel 10 Pro XL", default profile names "Pixel 10 Pro XL Desk" / "Pixel 10 Pro XL Desk Profile", `activeProfileDeviceModel` fallback "Pixel 10 Pro XL", and the Auth screen's engineering feature list.
9. **Dwell settings exist in three places with different behaviour**: deck inline slider (continuous, 400–2200), dialog slider (17 steps) + sensitivity + presets, Cloud & Privacy card (17 steps + presets with labels truncated to 12 characters → "High Precisi"). The dialog preset is "selected" only when both values match exactly, the Cloud-tab chip only checks duration, so the two can disagree.
10. **Simulator controls in the end-user UI**: "Sim Proxy" chip, simulator pad, "Lock Sim Here", overlay-mode and resolution cyclers sit beside daily controls. Denying camera permission silently switches the app into simulator mode, so a user without permission sees a moving synthetic cursor labelled "SIM PROXY" instead of a clear "camera needed" state.
11. **First-run flow is absent**: the app opens on the Calibrate tab (a long scroll of enrollment card, calibration card and telemetry card); nothing sequences consent → enrollment → calibration; the default calibration mode is the quick fit, which can never unlock the 3×3 deck and leaves the summary labelled "not validated".
12. **"Clear" enrollment has no confirmation** although it discards biometric-derived data; every other destructive action confirms.
13. **Colour-only information**: the active profile row is marked only by a border colour; the looked-at passage section only by tint/border; the brow bar in the preview turns amber only; the status dot is colour only; focused tile relies on colour plus the progress ring (acceptable) but the ring is 22 dp.
14. **TalkBack duplication and gaps**: tile icons use `contentDescription = tile.title` so the title is read twice; the trigger-mode chips, HUD badges and the status banner have no testTags/semantics beyond text; the camera preview `Box` is a clickable with a label that changes meaning ("Toggle preview size" vs "Switch to live camera"); the "Dismiss" affordance is not a button.
15. **Jargon in navigation and headings**: "Calibrate", "Control Deck", "Attention Lab", "Cloud & Privacy", "Section Dwell & Fixation Centroid Lab", "Gaze, Dwell & Posture Engine", "Cloud Telemetry Opt-In (Firestore Sync)", "Wipe Cloud & Local Vault", "OPTICAL LOCK", "SEARCHING FACE...".
16. **Status banner is a firehose**: ~45 technical messages (Firestore paths, Y-bias values, matrix offsets, benchmark statistics) in 11 sp mono with no priority, no auto-dismiss and no history; a new message silently replaces the previous one.
17. **Warnings use error red for low light** (not an error) in an 11 sp mono two-line slot with a long technical sentence; the fixed slot also reserves two blank lines on every tab when there is nothing to say ("No optical warnings").
18. **Camera binding is coupled to the HUD composable.** `MiniFrontCameraPreview` inside `OpticalDiagnosticsBar` is the only CameraX binding; the preview is always visible when permission is granted (116×150 dp by default) and cannot be hidden, only shrunk to "Cam Mini".
19. **Reading material is engineering prose about the app** (the three passage sections), not end-user reading content, and the only tile for reading ("Read Summary") speaks "Reading current passage analytics aloud." by default.
20. **Duplicate testTags across the shell and the Cloud tab** (`cloud_sync_toggle`, `delete_firestore_data_button`) and two separate delete-cloud confirmation dialogs with different tags (`confirm_delete_cloud_*` vs `confirm_destructive_action_*`).
21. **Fixed dp heights and 2/3-column grids** (tiles 124/104 dp, preview maps 132/140 dp) with no adaptive layout for landscape, tablets or large font scales; the HUD chip row relies on horizontal scrolling with no affordance.
22. **Auth screen** presents a 155 dp hero image, an engineering feature list and Firestore collection paths before the single action the user needs; sign-in errors appear as a card between the copy and the button.
23. **No localisation**: all copy except 27 strings is hard-coded English with `Locale.US` number formatting; the `strings.xml` map and the AuthScreen semantics tree are frozen by tests, so any copy change must update `FROZEN_USED_STRINGS` and the frozen AuthScreen copy in `AuthOptimizationTest`.
