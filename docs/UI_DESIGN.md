# Æye — UI design specification (final)

Status: approved direction, ready to implement. This document is the single source for the UI overhaul.
Ground truth for what exists today: `docs/UI_INVENTORY.md`. Capability numbers such as **#97** refer to the numbered
list in section 2 of that inventory. No app code was changed to produce this document.

How this spec was made: three complete designs were judged by three reviewers. The speak-first design ("A") won
(147 points, against 145 and 133). This spec starts from A, takes the best parts of the other two where they do not
conflict, and fixes every rule violation the reviewers listed. Appendix A records each finding and where it was
resolved.

Fixed user decisions (not re-opened here): the audience is end users who rely on the app, set up with a helper;
the flow is rethought (guided first run, one main screen, reading and settings tucked away); the look is a calm dark
instrument with one accent colour. All telemetry, simulator controls, resolution, filter and posture knobs and the
benchmark live behind a hidden developer mode.

Conventions: sizes are dp unless marked sp. Type roles are defined in section 5.2. "Touch 56" means the clickable
node's semantics bounds are at least 56 × 56 dp. `hud.*` = `LiveGazeHudState`, `cal.*` = `CalibrationWizardState`,
`att.*` = `AttentionSessionLiveState`, `enr.*` = `EnrollmentScanState`, `VM.fn()` = `EyeTrackingViewModel` function.
Quoted text is final copy. Every string shown to end users becomes a string resource (section 9.2).

---

## 1. Information architecture and navigation

### 1.1 Rules that shape every screen

1. **Gaze acts only on tiles.** Dwell, command blink and brow raise can fire a tile on the Speak screen and nothing
   else. On the Read screen gaze is measured against passage sections and triggers nothing. No button, switch, list
   row, banner, dialog or navigation element can be activated by gaze.
2. **Nothing moves under the pointer.** On the two gaze screens (Speak, Read) every region has a fixed height. Banners
   use a reserved lane. `AnimatedVisibility` and any animation that changes layout are not allowed on these screens.
3. **One banner at a time, and no banner keeps the lane.** A priority policy picks the banner (section 3). Every banner
   leaves after a maximum time, so a person who cannot touch the screen never loses later messages.
4. **Honest words.** "Not calibrated", "Not checked", "Accuracy limited", "Not measured" and "Simulator" are states with
   their own copy. A default, simulated or clamped value is never shown as measured. The simulator label lives in the
   status block, which no banner can cover.
5. **One job per screen.** Helper tasks are separate screens reached from one Menu. No sheet mixes speed settings with
   erase or sign-out.
6. **Touch controls work with TalkBack and Switch Access.** Every action is a plain `Button`, `Switch`, `Checkbox`,
   `RadioButton` or clickable row with a label. There are no long-press-only or gesture-only actions.
7. **Developer mode is a separate room.** Telemetry, simulator controls, engine knobs and the benchmark exist only on
   the Developer screen and in clearly marked developer slots, and only while developer mode is on.

### 1.2 Top-level state machine

```
signed out ───────────────────────────────► SignInScreen
signed in  ───────────────────────────────► MainShell(uid) ─► NavHost(start = resolveStartDestination(setupState))
     setupState.consentAcceptedAtMs == null ─► "setup/welcome"
     setupState.completedAtMs == null       ─► "setup/welcome"   (Welcome resumes at the next unfinished step)
     otherwise                              ─► "speak"
```

* `resolveStartDestination(setup: SetupState): String` and `nextSetupStep(setup, hasCameraPermission, hasBaseline,
  hasActiveCalibration): String` are pure functions in `ui/navigation/AppRoutes.kt` (unit-tested).
* Consent is the gate. The camera pipeline does not bind, and no face analysis runs, until consent is recorded.
* A completed user whose camera permission is later revoked stays on Speak, with the "Camera off" status and a
  banner. Setup is not re-entered.
* **Existing accounts** (an enrollment baseline or a saved profile exists, consent is missing): setup runs once. The
  Face scan and Calibration steps show "Already done on <date>" with **Keep and continue** as the primary action, so
  nobody repeats work. The Privacy step offers **Sign out** and **Erase my data** before agreeing.

### 1.3 Routes

Navigation Compose (`libs.androidx.navigation.compose`, already in the version catalog at 2.8.9; line 108 of
`app/build.gradle.kts` is uncommented). String routes, no typed arguments. One `NavHost` inside `MainShell`.

| Route | Screen | Reached from | Gaze | Reticle (section 4) |
|---|---|---|---|---|
| (outside shell) | SignInScreen | app start, sign-out | no | none |
| `setup/welcome` | SetupWelcomeScreen | first sign-in, "Run setup again" | no | hidden |
| `setup/privacy` | ConsentScreen (step 1 of 6) | Welcome | no | hidden |
| `setup/camera` | SetupCameraScreen (step 2) | Privacy | no | hidden |
| `setup/scan` | SetupScanScreen (step 3) | Camera | scan only | hidden |
| `setup/calibrate` | SetupCalibrateScreen (step 4) | Scan | wizard only | hidden |
| `setup/tiles` | SetupTilesScreen (step 5, optional) | Calibrate | no | hidden |
| `setup/backup` | SetupBackupScreen (step 6) | Tiles | no | hidden |
| `setup/ready` | SetupReadyScreen | Backup | no | hidden |
| `speak` | SpeakScreen (**home**) | Ready, Back from the Menu | **yes** | ring |
| `menu` | MenuScreen | Speak ▸ Menu button | no | hidden |
| `read` | ReadScreen | Menu | **measured only** | dot while recording |
| `read/history` | ReadingHistoryScreen | Read ▸ Past readings, Privacy | no | hidden |
| `read/replay` | ReadingReplayScreen | history row; after "Finish and save" | no | hidden |
| `calibrate` | CalibrateScreen (overview, wizard, result) | Menu, recalibrate banner, help sheet | wizard only | hidden |
| `facescan` | FaceScanScreen | Menu, Calibrate, Privacy | scan only | hidden |
| `recenter` | RecenterScreen | Menu, help sheet | capture only | hidden |
| `tiles` | TilesScreen | Menu | no | hidden |
| `settings` | SettingsScreen | Menu | no | hidden |
| `settings/brow` | BrowSetupScreen | Settings | no (live gauge) | hidden |
| `privacy` | PrivacyScreen | Menu | no | hidden |
| `about` | AboutScreen | Menu | no | hidden |
| `developer` | DeveloperScreen | Menu (developer mode only) | telemetry | full |

Removed: the four bottom tabs (**#6**), the Back-to-tab-0 handler (**#7**), the inline cloud bar (**#13–15**) and the
persistent diagnostics bar (**#22–46**, relocated per section 6).

### 1.4 Back behaviour

| Where | System Back (and the top-bar Back button) |
|---|---|
| Sign-in | Finishes the Activity. |
| Setup ▸ Welcome | Opens `ConfirmDialog(SignOut)`. **Stay** keeps the screen. |
| Setup ▸ Privacy … Backup | Goes to the previous setup step. A running scan or wizard is cancelled first. |
| Setup ▸ Ready | Does nothing. Only **Start speaking** leaves. |
| Speak | Opens `ConfirmDialog(LeaveApp)` ("Leave Æye?" / **Stay** / **Leave**). A stray touch must not close an assistive home screen. |
| Menu and every screen below it (idle) | Pops to the previous destination. The stack always ends on Speak. |
| Read while recording | Pops. The recording continues (**#126**). Speak shows the recording chip (2.3). |
| Calibrate wizard (collecting) | Cancels the wizard (`VM.cancelCalibrationWizard()`) and shows the overview with an inline notice "Calibration cancelled. Nothing was changed." No dialog (a dialog would pause sampling anyway). |
| Calibrate result | Opens `ConfirmDialog(DiscardCalibration)`. |
| Face scan in progress | Cancels the scan (`VM.cancelEnrollmentScan()`), stays on the idle state (**#88**). |
| Tile edit dialog, any sheet | Closes the dialog or sheet. Unsaved text is discarded only after `ConfirmDialog(DiscardEdit)` when a field changed. |

### 1.5 First run and returning users

* **First run**: Sign in → Welcome → 1 Privacy → 2 Camera → 3 Face scan → 4 Calibration → 5 Tile phrases
  (optional) → 6 Backup choice → Ready → Speak. Steps 3 to 5 can be skipped one by one with an honest consequence
  line. From step 2 onward the top bar offers **Skip setup** with a confirmation. It is never offered before consent.
* **Returning user**: Sign in (auto sign-in, **#2**) → Speak. Camera permission is re-checked on resume as today
  (**#8**). Denial never switches the app into simulator mode. It shows the "Camera off" status.
* **Sign-out** (Privacy, Welcome, Consent) → Sign in, with auto sign-in suppressed (**#2**, unchanged).
* **Erase everything** clears consent and setup state, so the next launch starts at Welcome, as its copy promises.

### 1.6 Developer mode

* **Unlock**: About ▸ tap the **Version** row 7 times (`about_version_row`). Taps 4 to 6 show an inline countdown
  "3 more taps to turn on developer mode". The 7th tap opens `ConfirmDialog(DeveloperMode)`: "Turn on developer mode?"
  / "Shows live tracking numbers, the camera picture, simulator controls and engineering settings. Meant for setup and
  support, not daily use." / **Not now** / **Turn on**.
* **Storage**: device-wide, not per account, never cleared by erase (`LocalTelemetryStore.isDeveloperModeEnabled()`).
  Exposed as `VM.developerModeEnabled`.
* **When on**: the Menu gains a **Developer** row. The Menu button on Speak carries a "DEV" badge. The developer
  slots on the wizard, scan, replay and history screens appear (2.16.3). The optional developer strip can be enabled.
* **When off**: the `developer` route is not registered in the `NavHost`. On hardware the simulator is forced off and
  the overlay mode is forced to `RETICLE_ONLY`. The ViewModel ignores `toggleSimulatorProxyMode(true)`,
  `cycleOverlayVisualMode()` and the two `simulate…` functions, so isolation does not depend on a hidden control.
* **Emulators** always run synthetic frames (ViewModel rule, unchanged). Without developer mode the app still works
  and every surface says "Simulator".

### 1.7 Shell composition (`ui/shell/MainShell.kt`)

```
MainShell(uid)                                   creates the per-user ViewModel (#9), TTS engine and haptics (#10)
 Box(fillMaxSize)                                root gaze frame: reports bounds and physical size (#16)
 ├─ CameraPipelineHost                           no UI. Owns CameraX (#31). Binds only when consent is recorded,
 │                                               permission is granted, the simulator is off and this is not an emulator
 ├─ NavHost                                      one screen at a time
 ├─ BannerOverlayHost                            non-gaze screens only. Speak and Read lay out their own reserved lane
 ├─ DevHudStrip                                  developer mode and its switch only
 └─ GazeReticle                                  top-most composable. Dialogs and sheets are separate windows above it
 CompositionLocals: LocalBannerQueue, LocalSpeechPreview, LocalCameraPipeline, LocalDeveloperMode, LocalGazeSuspension
```

* `CameraPipelineHost` binds `ImageAnalysis` only. A `Preview` use case is added while a `CameraPreviewSurface` is in
  composition (Setup ▸ Camera, the positioning phase of the face scan, Developer ▸ Camera) and removed when it leaves.
* The shell calls `VM.setCameraPermissionGranted(granted)` on launch, on the permission result and on resume. It
  never calls `toggleSimulatorProxyMode` because of a permission result.
* `LocalGazeSuspension` is a counter. Every dialog and sheet increments it while shown. While it is above zero the
  reticle is hidden and tile focus is cleared (today's rule, **#100**).

### 1.8 Where gaze input is live

| Screen | What gaze does |
|---|---|
| Speak | Focuses and fires visible tiles (dwell, command blink, brow raise). |
| Read, recording | Credits dwell to the passage section under the gaze point. Fires nothing. |
| Calibrate wizard, Face scan, Recenter capture | Samples are collected for the measurement. Fires nothing. |
| Developer | Telemetry display. The deck is not composed, so nothing fires. |
| Everything else | Nothing. The deck is not composed, `updateFocusedTile(null)` was emitted on leaving Speak. |

---

## 2. Screen-by-screen specification

Reference device: 411 × 891 dp portrait, system bars through `safeDrawing` insets. Side gutter 16 (24 at widths of
600 and above). Content width is capped at 640 and centred on wider screens. Heights marked "fixed" are computed once
from `LocalDensity.fontScale` (the "≤ 1.3" value, or the "> 1.3" value) and never animate.

### 2.0 Shared components (`ui/components/`)

**`TopBar(title, onBack?, trailing?)`** — height 56 (72 above font scale 1.3). Back is a 56 × 56 icon button
(`top_bar_back`, contentDescription "Back"). Title is titleLarge, one line, ellipsis. The trailing slot holds up to two
56 × 56 buttons or one text button.

**`BigButton(text, onClick, style, enabled, loading)`** — full width (max 480), height 64, labelLarge, shape 16.
Primary: accent fill, `OnAccent` text. Secondary: 2 dp `Outline` border, `TextPrimary` text. Danger: 2 dp `Danger`
border and text (filled only inside confirm dialogs). Loading shows a 24 dp spinner left of the text, disables the
node, and changes the text to the "…ing" form.

**`StatusBlock(status)`** — the tracking status on Speak and Read. Height = top bar height, takes the remaining
width. Leading 28 dp icon, then two lines: title (titleMedium, `TextPrimary`) and caption (bodyMedium,
`TextSecondary`), each one line. A trailing 20 dp `Warning` icon appears while any tracking condition is active but
not currently shown as a banner. Icon and words always, never colour alone. The whole block is one clickable node
(`tracking_status`, role Button, contentDescription "Tracking status: <title>. <caption>.", stateDescription lists
active conditions). It is announced by TalkBack only when the title changes.

Status comes from the pure function `trackingStatus(hud, setup, nowMs, msSinceCameraBound, gridFallback)` in
`ui/components/StatusBlock.kt`. First match wins:

| Condition | Title | Caption | Icon | Tone |
|---|---|---|---|---|
| `hud.gazeInputState == SIMULATED` (simulator or emulator) | "Simulator" | "Not live" | `Science` | neutral, never success |
| `NO_CAMERA_PERMISSION` | "Camera off" | "Tap for help" | `VideocamOff` | warning |
| `hud.hasNoFrontCamera` | "No camera" | "No front camera" | `VideocamOff` | warning |
| `NO_FRAMES`, under 4 s since the camera was bound | "Starting" | "Camera starting" | `HourglassEmpty` | neutral |
| `NO_FRAMES` otherwise | "No picture" | "Camera not sending" | `VideocamOff` | warning |
| `NO_FACE` for more than 900 ms | "No face" | "Face not found" | `FaceRetouchingOff` | warning |
| `EYES_CLOSED` for more than 800 ms | "Paused" | "Eyes closed" | `VisibilityOff` | neutral |
| tracking, `!hud.hasActiveCalibration` | "Tracking" | "Not calibrated" | `Visibility` | neutral |
| tracking, active profile is simulated | "Tracking" | "Simulated calibration" | `Visibility` | neutral |
| tracking, calibration applied, `activeErrorRadiusPct == null` | "Tracking" | "Not checked" | `Visibility` | neutral |
| tracking, checked, does not qualify for 9 tiles | "Tracking" | "Accuracy limited" | `Visibility` | neutral |
| tracking, qualifies, but the screen cannot fit 9 tiles | "Tracking" | "Screen fits 4 tiles" | `Visibility` | neutral |
| tracking, qualifies | "Tracking" | "9 tiles" | `Visibility` | neutral |

The 900 ms grace equals the ViewModel's invalid-gaze reset (`DWELL_INVALID_RESET_MS`). The 800 ms grace equals the
longest command blink, so a blink never flashes "Paused". The same debounced state drives reticle visibility
(section 4), so status and reticle can never disagree. The status never shows a number.

**`TrackingHelpSheet`** (modal bottom sheet, touch only, `tracking_help_sheet`) opens from the status block:
* Headline: title and caption. One plain sentence of advice for the state, for example "Move so the whole face is in
  the picture, with light on the face." or "Close Æye and open it again. If this keeps happening, restart the phone."
* "Right now" list (icon + bodyLarge each, only the active ones): low light, glare, one eye visible, distance changed,
  phone busy or warm, speech off, simulator on, recalibration suggested.
* "Layout" line (`help_layout_text`), honest in every case: "9 tiles: the calibration was checked and is accurate
  enough." / "4 large tiles: the calibration was checked and accuracy is limited." / "4 large tiles: the calibration
  was not checked." / "4 large tiles: not calibrated yet." / "4 large tiles: this screen is too small for 9."
* Actions (BigButton, only those that apply): **Turn on camera** (`tracking_help_action_camera`), **Recalibrate**
  (`tracking_help_action_recalibrate` → `calibrate`), **Recenter** (`tracking_help_action_recenter` → `recenter`),
  **Fix speech** (`tracking_help_action_speech`), **Close**.

**`ConfirmDialog(spec)`** — the only confirmation dialog. Tags are kept from today: `destructive_action_confirm_dialog`,
`confirm_destructive_action_button` (56 high; `Danger` fill for destructive variants, accent fill otherwise),
`cancel_destructive_action_button` (text button, 56 high). Title headlineSmall, body bodyLarge. It replaces
`confirm_delete_cloud_dialog` and its buttons (**#15**, **#140**).

| Variant | Title | Body | Confirm / Cancel |
|---|---|---|---|
| DeleteCloudCopies | "Delete the cloud copies?" | "Removes every calibration and reading summary saved in your private cloud space. What is on this phone stays." | "Delete cloud copies" / "Cancel" |
| EraseEverything | "Erase everything?" | "Removes all calibrations, readings, tile changes and the face scan from this phone and from the cloud. Æye goes back to first-time setup. This cannot be undone." | "Erase everything" / "Cancel" |
| DeleteProfile | "Delete this calibration?" | "“<name>” is removed from this phone and, if cloud backup is on, from the cloud." | "Delete" / "Cancel" |
| DeleteSession | "Delete this reading?" | "“<title>” is removed from this phone and, if cloud backup is on, from the cloud." | "Delete" / "Cancel" |
| ClearEnrollment | "Remove the face scan?" | "Blink and eyebrow detection go back to standard settings until you scan again. Nothing else is deleted." | "Remove scan" / "Cancel" |
| ResetTiles | "Reset all tiles?" | "Every tile goes back to its original label and phrase." | "Reset tiles" / "Cancel" |
| SignOut | "Sign out?" | "Æye's data stays on this phone for when you sign in again." (during setup: "Setup is not finished. You can sign in again later to continue.") | "Sign out" / "Stay" |
| LeaveApp (neutral) | "Leave Æye?" | none | "Leave" / "Stay" |
| DiscardCalibration | "Discard this calibration?" | "The dots you just looked at will not be saved." | "Discard" / "Keep" |
| DiscardEdit (neutral) | "Discard your changes?" | none | "Discard" / "Keep editing" |
| SkipSetup (neutral) | "Skip the rest of setup?" | "Æye will work with standard settings and 4 large tiles. A helper can finish these steps later from the Menu." | "Skip" / "Continue setup" |
| RestartSetup (neutral) | "Run setup again?" | "The guided steps start again. Nothing is deleted." | "Run setup" / "Cancel" |
| DeveloperMode (neutral) | see 1.6 | see 1.6 | "Turn on" / "Not now" |

**`SettingRow`** — list row, minimum height 72: leading 28 dp icon, titleMedium label, bodyMedium supporting text (up to
2 lines), trailing control (switch, chevron or radio). The whole row is one clickable node, touch 56.

**`RadioRow`** — a `SettingRow` with `Role.RadioButton` and a 28 dp radio. The selected row also shows a `Check` icon
and the word "Selected" as stateDescription, so selection is not colour alone.

**`InlineNotice(kind, text, action?)`** — a message inside the content (not a banner): shape 20, `SurfaceRaised`, 24 dp
icon (info, success, warning, error), bodyMedium text, optional text button. Used for list states, form hints and
"needs the live camera" notes.

**`StatTile(label, value)`** — height 88, `Surface`, shape 20: bodyMedium label, titleLarge value.

**`CameraPreviewSurface(modifier)`** (`ui/camera/`) — a mirrored 3:4 `PreviewView` (FILL_CENTER), shape 20, with
**`FaceGuideOverlay`**: a dashed oval guide, corner brackets when a face is found. It draws no text and no numbers.
The full ML Kit overlay (`MlKitFaceOverlayCanvas`, **#32**) is used only in Developer ▸ Camera.

**Developer slots** — `DevOnly { … }` renders its content only while developer mode is on. The composables placed in
these slots live in `ui/developer/DevInline.kt` (2.16.3). Monospace type appears only inside them and on the Developer
screen.

**`selectionTimingSentence(t: SelectionTimings)`** (`ui/components/SelectionTimingCopy.kt`, pure) — returns
"A long blink of <min>–<max> ms selects. Normal blinks (<natMin>–<min − 1> ms) never select anything. Raised eyebrows
select after <browHold> ms. Selection pauses whenever the face or the eyes are not seen." The numbers come from
`hud.selectionTimings`, which the ViewModel fills from the live detectors (350–800, 80–349 and 400 by default), so
user-facing timing claims cannot drift from the detector. Used on the Privacy step, in Settings and in About.

### 2.1 SignInScreen (`ui/auth/SignInScreen.kt`)

Purpose: sign in. Layout: centred column, max width 480, gutter 24.

* App mark: 72 dp `ic_optics_hero_illustration` in a circle (replaces the 155 dp hero, **#5**).
* `auth_title` headlineLarge "Æye". `auth_subtitle` bodyLarge "Speak and read with your eyes."
* Error: `InlineNotice(error)` directly above the button (`sign_in_error_text`). The mapped messages are unchanged
  (**#4**): no Google account, Play services out of date, interrupted, not configured, generic.
* `BigButton` "Sign in with Google" (`google_sign_in_button`). Loading variant "Signing in…" while the automatic
  sign-in or the tap is in flight (**#3**). stateDescription "Signing in" while loading.
* Footer bodyMedium: "A helper usually does the first setup. It takes about five minutes." Text button
  "How Æye uses your data" (`open_privacy_note_button`) opens `PrivacyNoteSheet` (full note, 2.2.2).
* Removed: the device badge, the feature rows and the Firestore paths (**#5**).

States: idle; loading (button disabled, spinner); error (notice shown, button enabled).

### 2.2 Setup flow (`ui/setup/`)

**`SetupScaffold(step, title, content, primary, secondary?)`** — `TopBar` (Back, title, trailing text button
**Skip setup** `setup_skip_all_button` on steps 2 to 6 of a first run → `ConfirmDialog(SkipSetup)` →
`VM.markSetupComplete(skipped = true)` → Speak). Under the title: a 6-segment indicator (6 pills, 8 high; filled =
done or current) with the text "Step n of 6" (`setup_step_indicator`). Content scrolls. The primary `BigButton` and
an optional secondary text button (56 high) are pinned to the bottom. A haptic tick (`CLOCK_TICK`) marks each
completed step.

#### 2.2.1 SetupWelcomeScreen (`setup/welcome`)

* headlineMedium "Hello, <first name>." (or "Hello.").
* bodyLarge "A helper should do these steps together with the person who will use Æye, sitting comfortably in front
  of the phone. It takes about five minutes."
* Checklist, 6 rows (`setup_checklist_row_<n>`; number badge, titleMedium, trailing icon + word): "Privacy",
  "Camera", "Face scan · 15 seconds", "Calibration · about 1 minute", "Tile phrases · optional", "Backup choice".
  Trailing state: `CheckCircle` + "Done" / "To do" / "Optional". Done means: consent recorded; permission granted;
  baseline present; active calibration; (tiles are always "Optional"); backup choice made.
* Primary "Start" (or "Continue" when some steps are done) (`setup_start_button`) → `nextSetupStep(...)`.
* Secondary "Sign out" (`setup_sign_out_button`) → `ConfirmDialog(SignOut)`.

#### 2.2.2 ConsentScreen (`setup/privacy`) — step 1 "Your privacy"

* bodyLarge paragraphs, each with a 28 dp icon:
  1. `Videocam` — "Æye uses the front camera to work out where you are looking, when you blink and when you raise
     your eyebrows."
  2. `HideImage` — "Camera pictures are analyzed on this phone and thrown away straight away. They are never saved
     and never sent anywhere."
  3. `Face` — "Æye keeps a few numbers about your eyes, blinks and eyebrows, and your calibration. They stay on this
     phone unless you turn on cloud backup later."
  4. `DeleteOutline` — "You can erase everything at any time from Menu ▸ Privacy & account."
* Two checkbox rows (72 high, whole row clickable; `consent_camera_checkbox`, `consent_storage_checkbox`): "I understand
  the camera is used and pictures are not saved." / "I agree that Æye keeps these numbers on this phone."
* Expander "How selection works" (`consent_how_it_works`, 56 high): "Look at a tile for about a second to select it.
  You can change the time later." followed by `selectionTimingSentence(...)` (`consent_how_it_works_text`).
* Text button "Read the full privacy note" (`consent_full_note_button`) → `PrivacyNoteSheet` with `privacy_notice_body`:
  "Æye analyzes front-camera pictures in memory only, to find your eyes, eyebrows and head position. The pictures
  are never stored or transmitted. The numbers Æye keeps (calibration results, blink timings, reading summaries) stay
  on this phone unless you turn on cloud backup, which copies calibrations and reading summaries to a private space
  tied to your Google account. Face-scan numbers are never copied. You can delete everything from the Privacy screen
  at any time."
* Primary "I agree, continue" (`consent_continue_button`), enabled only when both boxes are ticked (stateDescription
  "Tick both boxes to continue") → `VM.acceptConsent()` → `setup/camera`.
* Secondary "Sign out" (`consent_sign_out_button`) → `ConfirmDialog(SignOut)`.
* Only when this account already has data on the phone (baseline, profiles or sessions): Danger text button
  "Erase my data" (`consent_erase_button`) → `ConfirmDialog(EraseEverything)` → `VM.eraseAllCloudTelemetry()`. The
  person can leave or erase without agreeing.
* Re-entry with consent already recorded: boxes are ticked, primary reads "Continue".

#### 2.2.3 SetupCameraScreen (`setup/camera`) — step 2 "Camera"

The system permission prompt is launched once on entry if it was never asked.

| State | Content |
|---|---|
| Not asked, or denied once | `CameraFront` 64 dp. headlineSmall "Æye needs the front camera". bodyLarge "The camera follows the eyes while Æye is open. Nothing is recorded or sent. Only the person using Æye should be in front of the phone." Primary "Allow camera" (`request_camera_button`). Secondary "Continue without the camera for now" (`camera_skip_button`). |
| Denied permanently | Same heading. bodyLarge "Camera access is turned off for Æye. Turn it on in the phone's settings, then come back." Primary "Open phone settings" (`open_app_settings_button`). Secondary "Continue without the camera for now". Permission is re-checked on resume. |
| Granted | `CameraPreviewSurface` 240 × 320, centred. Under it, titleMedium with icon (`camera_face_status_text`): "Face found" (`CheckCircle`) / "No face yet. Move into the oval." (`FaceRetouchingOff`). bodyMedium "Good light on the face, no strong light behind. Glasses are fine." Primary "Continue" (`camera_continue_button`), always enabled so a helper can position the person later. |
| No front camera | `VideocamOff` 64 dp. headlineSmall "This phone has no front camera". bodyLarge "Æye cannot follow the eyes here. Tiles can still be tapped by hand." Primary "Continue" (`camera_continue_button`). |
| Simulator or emulator | `InlineNotice(warning)` "Simulated camera. The face scan and a real calibration are not available." Primary "Continue". |

Continuing without a working camera makes the next two steps show their "needs the live camera" state, where only
**Skip for now** is available. Setup never dead-ends.

#### 2.2.4 SetupScanScreen (`setup/scan`) — step 3 "Face scan"

Hosts `FaceScanContent(mode = Setup)` (2.9) inside the scaffold. Differences from the standalone screen:

* Primary "Start scan" (`start_enrollment_button`). Secondary "Skip for now" (`setup_skip_button`) with the line
  "Blink and eyebrow selection will use standard timings until you scan."
* Baseline already present: `InlineNotice(success)` "Already done on <d MMM yyyy>." Primary "Keep and continue",
  secondary "Scan again" (`rescan_enrollment_button`).
* No working camera, simulator or emulator: `InlineNotice(warning)` "The face scan needs the live front camera."
  Primary disabled, "Skip for now" only.
* After `enr.phase == COMPLETE`: the result card (2.9.1) and primary "Continue" → `setup/calibrate`.
* After FAILED: `InlineNotice(error)` "The scan did not finish: <enrollmentFailureText>". Primary "Try again",
  secondary "Skip for now".

#### 2.2.5 SetupCalibrateScreen (`setup/calibrate`) — step 4 "Calibration"

Hosts `CalibrateContent(mode = Setup)` (2.8). Differences:

* Intro: headlineMedium "Teach Æye where you look". bodyLarge "Dots appear one at a time: 5 dots, then 4 more that
  check the result. Look at each dot and keep the head still. A helper can hold the phone steady or use a stand.
  About one minute."
* Primary "Start calibration" (`start_calibration_button`) → `VM.startCalibrationAndValidationWizard(true)`. The short
  calibration is not offered during setup.
* Secondary "Skip for now" (`setup_skip_button`) with the line "Æye will still work, with 4 large tiles. The pointer
  is approximate until you calibrate."
* Active profile already present: `InlineNotice(success)` "Already calibrated: <name>." plus the honest accuracy line
  (2.8.1). Primary "Keep and continue", secondary "Calibrate again".
* No working camera: `InlineNotice(warning)` "Calibration needs the live front camera." Only "Skip for now".
* Result screen: primary reads "Save and continue" → `setup/tiles`.

#### 2.2.6 SetupTilesScreen (`setup/tiles`) — step 5 "Tile phrases" (optional)

Hosts `TilesContent(mode = Setup)` (2.10). Primary "Continue" (`setup_tiles_continue_button`) → `setup/backup`. Lead
text: "These are the things Æye can say. Change them to this person's own words now, or later from the Menu."

#### 2.2.7 SetupBackupScreen (`setup/backup`) — step 6 "Backup"

* bodyLarge "Backup copies calibrations and reading summaries to a private space in your Google account, so they come
  back if you reinstall or change phones. Face-scan numbers and camera pictures are never copied. Backup stays off
  unless you turn it on."
* Two option cards (96 high, `Role.RadioButton`, `Check` icon and "Selected" on the chosen one):
  `backup_off_option` "Keep everything on this phone" / "Recommended if you are unsure" (selected by default);
  `backup_on_option` "Back up to my Google account" / "Signed in as <email>".
* Primary "Continue" (`backup_continue_button`) → applies the choice (`VM.toggleCloudSync(choice)` only when it differs
  from the current value), `VM.markBackupChosen()` → `setup/ready`. Tapping a card selects it and changes nothing else.

#### 2.2.8 SetupReadyScreen (`setup/ready`)

* `CheckCircle` 64 dp (success) with headlineLarge "Ready".
* Summary rows (icon + titleMedium + bodyMedium; `setup_summary_scan`, `_calibration`, `_tiles`, `_speech`, `_backup`):
  * Face scan: "Done" / "Skipped. Standard blink timings are in use."
  * Calibration: "Checked. Good accuracy." / "Checked. Accuracy is limited." / "Saved, not checked." / "Skipped. The
    pointer is approximate." / "Simulated. Not a real measurement." No number appears unless it was measured, and a
    simulated profile is never described as checked.
  * Tiles: "9 tiles" / "4 large tiles for now".
  * Speech: "On" / "Off. No text-to-speech voice is installed."
  * Backup: "On" / "Off. Everything stays on this phone."
* bodyLarge "Hand the phone to <first name, or 'the person using Æye'>. Looking at a tile for a moment speaks it. A
  helper can open the Menu at the top right to change tiles or settings."
* Primary "Start speaking" (`setup_finish_button`) → `VM.markSetupComplete()` → `speak` (setup routes popped).

### 2.3 SpeakScreen (`speak`, `ui/speak/SpeakScreen.kt`) — the home

Purpose: speak by looking. Tag `speak_screen`. Fixed regions, top to bottom, inside the safe area:

| Region | Height (≤ 1.3 / > 1.3 font scale) | Content |
|---|---|---|
| Top bar | 56 / 72 | `StatusBlock` (weight 1). While a reading is being recorded: `RecordingChip`. Then the **Menu** button. |
| Banner lane | 72 / 96 | `BannerLane` (reserved). Empty = nothing drawn. |
| Spoken strip | 72 / 96 | The last spoken phrase. |
| Grid | the rest | The tiles. Never scrolls. |
| Developer strip | 40, developer mode and its switch only | `DevHudStrip` |

* **Menu button** (`open_menu_button`): 56 × 56, `Menu` icon 28 dp, contentDescription "Menu". Touch only. In developer
  mode it carries a "DEV" badge (labelSmall on `SurfaceRaised`) and the description "Menu, developer mode is on".
* **RecordingChip** (`speak_stop_recording_chip`, only while `att.isRecording`): 56 high, `SurfaceRaised`, a
  `FiberManualRecord` icon, "m:ss" and a `Stop` icon. contentDescription "Recording a reading, m minutes s seconds.
  Stop and save." Touch only → `VM.stopAndSaveAttentionSession()` then `VM.clearSessionReplay()`. The person stays on
  Speak and the "Reading saved" notice follows.
* **Spoken strip** (`aac_speech_output_text`, tag kept): `VolumeUp` 24 dp + bodyLarge text, 2 lines, ellipsis. Default
  "Look at a tile to speak." After a tile fires (`hud.lastSpoken`): the phrase in quotes, then a bodyMedium
  `TextSecondary` method word: "· held" / "· blink" / "· eyebrows" / "· tapped". With the simulator on, the text is
  prefixed "Simulator · ". When speech is unavailable the icon is `VolumeOff` and the method word is replaced by
  "· shown, not spoken". A recenter shows "Recentering. Look at the middle of the screen." Fixed height, so a long
  phrase never moves the grid (pins the existing test).

**Grid.** `SpeakGridLayout.resolve(availableWidthDp, availableHeightDp, qualifiesFor9)` (pure, unit-tested, in
`ui/speak/SpeakGridLayout.kt`):

* `qualifiesFor9 = shouldUseValidated3x3Grid(hud.activeErrorRadiusPct, hud.activeMaxQuadrantErrorPct)` — the
  unchanged classifier (**#90**, **#97**). The grid stays 2 × 2 until a checked calibration qualifies.
* `fits9 = (availableHeight − 2·gap) / 3 ≥ 96 && (availableWidth − 2·gap) / 3 ≥ 96`.
* Columns = 3 only when `qualifiesFor9 && fits9`, otherwise 2. Visible tiles = `tiles.take(columns²)` in list order.
* Gap 12, outer padding 16. Tiles fill their cells. Reference device: 2 × 2 tiles are about 183 × 290, 3 × 3 about
  118 × 189. `userScrollEnabled = false`: a tile is never off-screen, so every tile is reachable by gaze.
* When a qualifying calibration falls back to 4 tiles for lack of space, the status caption says "Screen fits 4
  tiles" and the help sheet says why. This is a spatial fallback and is never worded as an accuracy statement.

**Tile** (`AacTile`, tag `aac_tile_<id>`). One merged semantics node: contentDescription "<title>. Says: <phrase>.",
role Button, onClickLabel "Speak". Child icons have no description (fixes the double reading, defect 14).

| Part | Spec |
|---|---|
| Container | Shape 24, `Surface`, 2 dp `Hairline` border. |
| Icon | 32 dp, by category: COMMUNICATION `ChatBubble`, NEEDS `PanTool`, READER `MenuBook`, SYSTEM `CenterFocusStrong`, anything else `ChatBubble`. |
| Label | The title. `tileLarge` (32 sp) with 2 columns, shrinking to no less than 24 sp before it ellipsizes; `tileSmall` (24 sp) with 3 columns. Up to 2 lines, centred. |
| Phrase | 2 columns only: bodyMedium `TextSecondary`, up to 2 lines, centred. Hidden when it equals the title or above font scale 1.5. |
| Focused (`hud.focusedTileId == id`) | 3 dp accent border, 12 % accent fill, and a 6 dp **progress bar along the bottom edge**, accent, filling left to right with max(dwell, blink hold, brow hold) (replaces the 22 dp ring, **#98**). The border appears instantly and clears with a 100 ms fade. stateDescription "Selected. Keep looking to speak." |
| Fired (600 ms after `hud.lastSpoken.tileId == id`) | 3 dp success border and a 32 dp `Check` icon at the top right. stateDescription "Spoken". A visual confirmation that does not depend on speech. |
| Touch | Tap → `VM.fireTileAction(id, "Tap")` (**#99**). There is no long-press and no edit control on the tile. Tiles are edited only on the Tiles screen (**#102**). |

Hit testing is unchanged (`ui/speak/DeckHitTest.kt`): `resolveFocusedDeckTile` with 14 dp × `dwellSensitivityRadiusMultiplier`
hysteresis, visible tiles only, suspended while `LocalGazeSuspension` is above zero, focus cleared on leaving the
screen, tile centres reported through `updateTileCentersNorm` (**#100**, **#101**).

States:

| State | Presentation |
|---|---|
| Not calibrated | Status "Tracking / Not calibrated". 4 tiles. Once per process a hint banner: "Not calibrated yet. The pointer is approximate. A helper can calibrate from the Menu." |
| Calibrated, not checked | Status "Tracking / Not checked". 4 tiles. |
| Checked, accuracy limited | Status "Tracking / Accuracy limited". 4 tiles. |
| Checked and accurate | Status "Tracking / 9 tiles". 9 tiles when they fit. On the change from 4 to 9 a hint banner: "Good calibration. 9 tiles are now shown." |
| Face lost | Status "No face / Face not found" after 900 ms. Progress resets (ViewModel). The reticle fades. Tiles stay tappable. |
| Eyes closed for long | Status "Paused / Eyes closed" after 800 ms. Nothing fires (ViewModel). |
| Camera off | Status "Camera off / Tap for help". Banner (section 3). Reticle hidden. Tiles stay tappable. |
| No frames | Status "Starting", then "No picture". Banner after 5 s. |
| Simulator (emulator or developer) | Status "Simulator / Not live". Reticle drawn in its simulated form (section 4). The strip prefixes "Simulator · ". |
| Speech unavailable | Banner "Speech is off…". The strip shows `VolumeOff` and "· shown, not spoken". Tiles still highlight and show the Fired state. |
| A reading is being recorded | `RecordingChip` in the top bar. |
| Dialog or sheet open | Gaze focus suspended and cleared. Reticle hidden. |

### 2.4 MenuScreen (`menu`, `ui/shell/MenuScreen.kt`)

Purpose: the helper's hub. Tag `menu_screen`. `TopBar` "Menu" with Back. No gaze interaction.

* **Tracking card** (`menu_tracking_card`, `Surface`, shape 20), three lines, icon + bodyLarge:
  * `Face` — "Face scan: done on <d MMM yyyy>" / "Face scan: not done"
  * `CenterFocusStrong` — "Calibration: <name>, checked" / "Calibration: <name>, not checked" / "Calibration: <name>,
    simulated" / "Calibration: none"
  * `GridView` — "Tiles: 9" / "Tiles: 4 (accuracy limited)" / "Tiles: 4 (not checked)" / "Tiles: 4 (not calibrated)" /
    "Tiles: 4 (screen too small for 9)"
* Rows (`SettingRow`, chevron trailing):

| Tag | Icon | Label | Supporting text |
|---|---|---|---|
| `menu_row_read` | `MenuBook` | "Read" | "Read a passage while Æye follows your eyes" / while recording "Recording · m:ss" |
| `menu_row_calibrate` | `CenterFocusStrong` | "Calibrate" | "About one minute. Fixes a pointer that is off." |
| `menu_row_recenter` | `FilterCenterFocus` | "Recenter" | "A quick fix when tiles feel shifted" |
| `menu_row_facescan` | `Face` | "Face scan" | "Teaches Æye this person's blinks and eyebrows" |
| `menu_row_tiles` | `GridView` | "Tiles" | "Change what each tile says" |
| `menu_row_settings` | `Tune` | "Settings" | "<preset name> · <trigger mode name>" |
| `menu_row_privacy` | `Shield` | "Privacy & account" | "Cloud backup on · <email>" / "Cloud backup off · <email>" |
| `menu_row_about` | `Info` | "About" | "Version <versionName>" |
| `menu_row_developer` (developer mode only) | `Code` | "Developer" | "Live numbers, simulator, benchmark" |

* Text button "Run setup again" (`menu_rerun_setup_button`) → `ConfirmDialog(RestartSetup)` → `VM.restartSetup()` →
  `setup/welcome`.

### 2.5 ReadScreen (`read`, `ui/reading/ReadScreen.kt`)

Purpose: read a passage while gaze is measured. Tag `read_screen`. Fixed regions: `TopBar` 56 ("Read", Back, trailing
**Past readings** `History` icon button `open_reading_history_button`) → banner lane 72 (reserved) → passage (weight 1,
scrolls by touch) → action bar 96.

Passage (`reading_passage`): three `PassageSectionCard`s in a shape-20 `Surface` container, 16 between cards.

* Heading titleLarge. Body bodyLarge (18/26).
* While recording, the section under the gaze point (`att.activeSectionId`) gets a 4 dp accent bar on its leading
  edge **and** a labelMedium tag "Reading" at the heading's end (`section_reading_tag_<id>`). Not colour alone
  (**#122**). The bar and tag occupy reserved space, so nothing shifts. No percentages are shown while recording.
* Not recording, after a session in this run (`sectionDwellMsMap` not empty): a line under each heading
  (`section_dwell_label_<id>`): "Looked at 64 % of the time" / "Looked at 0 % of the time" / "Not measured" (the
  `formatSectionDwellLabel` truth rules are kept: no number when nothing was measured) and a 6 dp accent bar (**#121**).
* Hit testing is unchanged: `resolveLayoutHitReport`, `passageLocalGazePoint`, `VM.updateActiveReadingSectionFromLayout`,
  `VM.updatePassageGazePoint`, `VM.detachReadingSectionLayout()` on leaving (**#123**, **#124**, **#126**).
* `InlineNotice(info)` at the top of the scroll when `!hud.hasActiveCalibration`: "Not calibrated. The record of where
  you looked will be rough."
* Passage copy (`VM.readingPassageSections`, **#120**), ids `sec_1..sec_3` unchanged:
  1. "A walk by the river" — "The path along the river is quiet in the early morning. Mist lifts off the water, and
     the first joggers pass with a nod. By the old stone bridge a heron stands perfectly still, waiting. Further on,
     the bakery has just opened; the smell of warm bread drifts across the road. It is a good place to think, or to
     think about nothing at all."
  2. "Looking after houseplants" — "Most houseplants die from too much water, not too little. Check the soil with a
     finger: if the top two centimetres are dry, water slowly until it runs from the bottom, then let it drain. Give
     leafy plants bright but indirect light, and turn the pot a quarter each week so it grows evenly. Dust the leaves
     now and then; plants breathe through them."
  3. "A short history of tea" — "Tea was first drunk in China thousands of years ago, long before it reached Europe
     in the 1600s. It arrived in Britain as a luxury and became an everyday drink within a century. Today it is the
     most widely drunk beverage in the world after water. Black, green and white teas all come from the same plant;
     the difference is in how the leaves are dried and treated."

Action bar (`read_action_bar`, 96, `SurfaceLow`, top hairline), one `BigButton`:

* Idle: "Start reading" (`start_attention_session_button`) → `VM.startAttentionRecording()` (**#116**).
* Recording: "Finish and save · m:ss" (`stop_attention_session_button`, Secondary style) →
  `VM.stopAndSaveAttentionSession()`. The ViewModel already selects the saved session, and the screen navigates to
  `read/replay` (**#117**). The elapsed time inside the label is `attention_elapsed_text`.
* Developer slot above the button while recording: `DevRecordingDetails` (natural and command blink counts, cluster
  count, frame-rate guard state).

Thermal guard while recording (**#119**, `shouldShowThermalGuard` kept): a banner (section 3). Reticle: a dot while
recording, hidden otherwise (section 4). The old title, the "Nat Blinks / Cmd Blinks" split and the cluster count leave
the end-user screen (**#114**, **#115**; see 2.6 and 2.16.3).

### 2.6 ReadingReplayScreen (`read/replay`)

Purpose: show one saved reading. Source: `att.selectedReplaySession` (null → pop). Tag `reading_replay_screen`.

* `TopBar`: the session title (new default "Reading <d MMM, HH:mm>"), Back, trailing **Delete**
  (`delete_session_<id>`) → `ConfirmDialog(DeleteSession)`. A simulated session shows a labelSmall "SIMULATED" badge
  beside the title and an `InlineNotice(warning)` "Recorded with simulated gaze. Not a real measurement."
* Three `StatTile`s (`replay_stat_time`, `replay_stat_pace`, `replay_stat_blinks`): "Time" "1:42"; "Pace"
  "about 180 words a minute" or "Not measured" (`formatEstimatedWpm` rules kept: an empty or short session never
  shows 0); "Blinks" "12.0 a minute".
* Switch row "Show where you looked" (`replay_heatmap_switch`, default on) toggles `FixationHeatmapOverlay` over the
  passage (`attention_heatmap_canvas`, **#125**). Node numbers are 16 sp inside 28 dp discs. For a session saved
  before passage-aligned fixations the switch is disabled with the text "Not available for this reading".
* The passage with the per-section lines of 2.5. Scrolls.
* Developer slot: `DevSessionDetails` (2.16.3).
* Bottom `BigButton` Secondary "Done" (`close_session_replay_button`) → `VM.clearSessionReplay()` and pop (**#118**).

### 2.7 ReadingHistoryScreen (`read/history`)

* `TopBar` "Past readings", Back.
* While recording: `InlineNotice(info)` (`replay_locked_while_recording_text`) "Finish the current reading first."
  All rows are disabled (**#137**, **#138**).
* `UiDataState`: Loading → three placeholder rows and "Loading…"; Error → `InlineNotice(error)` with the message;
  empty → `InlineNotice(info)` "No readings yet. Start one from the Read screen." (**#146**).
* Rows (`session_row_<id>`, minimum 96): title titleMedium; bodyMedium "1:42 · about 180 words a minute · 12.0 blinks a
  minute" (or "pace not measured"); bodyMedium "Looked mostly at the top of the page" / "…the middle…" / "…the
  bottom…" / "Where you looked was not measured" (from the zone percentages; the largest zone wins; all zero means
  not measured). Simulated sessions carry a labelSmall "SIMULATED" badge (**#141**). Row tap →
  `VM.selectSessionForReplay(session)` → `read/replay`. Trailing 56 dp delete button (`delete_session_<id>`,
  contentDescription "Delete <title>") → `ConfirmDialog(DeleteSession)` → `VM.deleteTrackingSession(id)` (**#139**).
* Developer slot per row: `DevSessionDetails` (raw zone percentages and the legacy summary line).

### 2.8 Calibrate (`calibrate`, `ui/calibration/`)

`CalibrateContent(mode)` has three states keyed on `cal.step`: IDLE → overview; FIT_5_POINTS / VALIDATE_4_POINTS →
wizard (full screen); COMPLETED_SUMMARY → result. A `DisposableEffect` cancels a collecting wizard when the content
leaves composition (**#70**). Step transitions and reaching the result do not cancel.

#### 2.8.1 Overview

`TopBar` "Calibrate", Back. Scroll column, 16 between items:

1. **Accuracy card** (`calibration_overview_card`, `Surface`, shape 20). Overline labelMedium "Current calibration".
   Headline headlineMedium and bodyLarge lines, by state:

   | State | Headline | Lines |
   |---|---|---|
   | No active calibration | "Not calibrated" | "Æye is using a standard starting point. The pointer is approximate and Æye shows 4 large tiles." |
   | Active profile is simulated | "Simulated calibration" | "Made with simulated gaze. It is not a real measurement and is never backed up." |
   | Applied, not checked (`activeErrorRadiusPct == null`) | "Not checked" | "Saved from 5 dots only. Accuracy was not checked on separate dots, so Æye keeps 4 large tiles." |
   | Checked, qualifies | "Checked: good" | "Typical error about ±4.2 % of the screen (~13 mm). Worst area ±6.5 %. Æye shows 9 tiles." |
   | Checked, does not qualify | "Checked: accuracy is limited" | "Typical error about ±13.8 % of the screen (~40 mm). Worst area ±19.0 %. Æye keeps 4 large tiles." |

   Numbers appear only when measured. The "~x mm" part appears only when `hud.activeErrorMm` is finite and always
   carries "~". "In use: <name>" (`active_profile_name_text`) is shown when a calibration is active (**#59**).
2. bodyLarge "Dots appear one at a time: 5 dots, then 4 more that check the result. About one minute. Keep the head
   still and move only the eyes."
3. When `enrollmentBaseline == null`: `InlineNotice(warning)` "Face scan not done. Blink and eyebrow selection use
   standard timings." with the action "Scan now" → `facescan`.
4. `BigButton` "Start full calibration" (`start_calibration_button`) → `VM.startCalibrationAndValidationWizard(true)`.
5. `BigButton` Secondary "Quick recalibrate (5 dots, not checked)" (`quick_calibration_button`) →
   `VM.startCalibrationAndValidationWizard(false)`. Supporting bodyMedium: "Faster, but accuracy is not measured and
   Æye keeps 4 large tiles." The full calibration is the default everywhere (**#57**, fixes defect 11).
6. No working camera (permission off or no front camera): items 4 and 5 are disabled and an `InlineNotice(warning)`
   says "Calibration needs the live front camera."
7. **Saved calibrations** (moved from Cloud & Privacy, **#134–136**). Header titleMedium "Saved calibrations".
   Loading / Error / empty ("No saved calibrations yet.") through `InlineNotice`. Rows (`profile_row_<id>`, minimum 96,
   `Role.RadioButton`): name titleMedium; bodyMedium "Checked · typical ±4.2 % (~13 mm) · worst ±6.5 %" / "Not
   checked. Accuracy unknown." / "Simulated. Not a real measurement."; bodyMedium "Saved <d MMM yyyy>". The row in use
   shows `CheckCircle` and the label "In use" (`profile_in_use_badge_<id>`), not only a border. Row tap →
   `VM.activateSavedProfile(profile)`. Trailing 56 dp delete button (`delete_profile_<id>`, contentDescription
   "Delete <name>") → `ConfirmDialog(DeleteProfile)` → `VM.deleteCalibrationProfile(id)`. Developer slot per row:
   `DevProfileDetails` (device model and the legacy error line).

#### 2.8.2 Wizard (`five_point_calibration_screen`)

Full screen, `Bg`, no top bar. Reticle hidden. Banners deferred (section 3).

* **Target** (`CalibrationTargetFullscreenOverlay`, tag `calibration_fullscreen_target_overlay`; the maths in
  `currentCalibrationFixationTarget` and `calibrationTargetCenterPx` is unchanged; touches pass through, **#69**): halo
  56 radius pulsing at 1200 ms, ring 36 with a 3 dp stroke, progress ring 88 with a 4 dp stroke =
  `cal.currentTargetProgress`, centre dot 10. All accent. A **check dot also carries a square outline** (104 side,
  2 dp), so fit and check dots differ by shape and by the word "Check" in the text, not by colour. With animations
  turned off in system settings the halo is a static ring. A haptic tick marks each completed dot.
* **Control band**: all text and controls sit in **one band** that is always on the side away from the current
  target. `wizardBandPlacement(frame, targetNorm)` (pure, in `ui/calibration/WizardBand.kt`):
  * frame height ≥ 600: the band is at the **bottom** when `target.y < 0.5`, otherwise at the **top**. Band height 260
    (220 below a frame height of 700: the headline is dropped).
  * frame height < 600 (landscape): the band is a side column of width min(320, 40 % of the frame), at the **end**
    when `target.x < 0.5`, otherwise at the **start**. The dots wrap.
  * Invariant (unit-tested for all 9 targets on 411 × 891, 360 × 640, 891 × 411, 640 × 360, 600 × 960 and
    1280 × 800): the band never intersects the circle of radius 72 around the target.
  * The band changes side only between dots, during the settling delay. Its buttons ignore input for 300 ms after a
    move, so a finger already on its way cannot hit the wrong button.
* Band content, top to bottom:
  * headlineSmall "Look at the dot" (fit) / "Look at the dot to check" (check).
  * bodyLarge `active_calibration_target_label`: "Dot 2 of 5" / "Check 1 of 4". No coordinates (**#62**).
  * bodyLarge `calibration_sample_counter_text` (**#63**, same three conditions): `Warning` icon + "Face not found.
    Look at the camera." (warning colour and icon; sampling is paused) / "Hold still…" (settling) / "Collecting… 12 of
    18".
  * Dots row (`calibration_5_point_stepper`): 5 nodes (fit) or 4 (check), each 56 × 56 with a 16 dp dot: done = filled
    with a check glyph, current = ring, waiting = outline. Tags `calibration_point_target_<i>`. contentDescription
    "Dot 1 of 5, done" / "Dot 2 of 5, current. Capture now." / "Dot 3 of 5, waiting". Only the current node has a
    click action → `VM.captureManualSamplesForCurrentTarget()`. This keeps manual capture reachable for TalkBack and
    Switch Access (**#64**, **#68**).
  * Buttons, side by side, 64 high: Secondary "Cancel" (`cancel_calibration_button`, **#67**) and Primary "Capture
    now" (`capture_gaze_point_button`, **#65**). Capture semantics are unchanged: on hardware it never moves the
    simulator; with the simulator already on it snaps to the target first.
  * Developer slot: `DevWizardDetails` — the legacy heading with coordinates, the raw counter text, per-dot "n/18"
    counts and "Lock sim here" (`snap_sim_to_target_button`, enabled only with the simulator on, **#66**).

#### 2.8.3 Result (`calibration_summary_screen`)

`TopBar` "Calibration result" (Back = `ConfirmDialog(DiscardCalibration)`). Scroll column:

* **Result card** — icon, `calibration_result_title` (headlineSmall), `calibration_result_body` (bodyLarge), and the
  layout sentence as its own node `recommended_layout_text` (**#72**, **#73**, **#75**):

  | Outcome (condition) | Icon | Title | Body | Layout sentence |
  |---|---|---|---|---|
  | Good (`metricsAreHeldOut`, qualifies for 9) | `CheckCircle` success | "Good calibration" | "Typical error about ±4.2 % of the screen (~13 mm). Worst area ±6.5 %." | "Æye will show 9 tiles." |
  | Limited (`metricsAreHeldOut`, does not qualify) | `Info` | "Checked, accuracy is limited" | "Typical error about ±13.8 % of the screen (~40 mm). Worst area ±19.0 %. For 9 tiles, try again with steady light on the face and the head kept still." | "Æye will keep 4 large tiles." |
  | Short, not checked (quick calibration) | `Info` | "Short calibration, not checked" | `calibration_in_sample_caveat`: "Only how well the 5 dots fitted was measured (±3.1 % fit, worst dot ±5.0 %). This is not an accuracy check." | "Æye will keep 4 large tiles. Run the full calibration to check accuracy." |
  | Check dots not scored (full calibration, `!metricsAreHeldOut`) | `Info` | "The check dots were not measured" | `calibration_in_sample_caveat`: "Only how well the 5 dots fitted was measured (±3.1 % fit, worst dot ±5.0 %). This is not an accuracy check. Try again and keep looking at each check dot." | "Æye will keep 4 large tiles." |
  | Not measured (NaN metrics) | `ErrorOutline` | "Not measured" | "Æye could not measure this calibration. Please try again." | none. **Save is disabled.** |

  "~x mm" appears only when `cal.validationMeanErrorMm` is finite. "NaN" is never shown. When
  `cal.usedSimulatedFrames` is true the card carries a labelSmall "SIMULATED" badge and the extra line "Made with
  simulated gaze. Saved as SIMULATED and never backed up. It is not a real measurement."
* `OutlinedTextField` "Name" (`profile_name_input`), default "Calibration <d MMM, HH:mm>" (**#77**; no device name).
* `BigButton` "Save and use" (`save_calibration_button`) → `VM.saveCompletedCalibrationProfile()` → overview (in
  setup: "Save and continue" → `setup/tiles`). Disabled for the Not measured outcome; the ViewModel guard stays.
* `BigButton` Secondary "Try again" (`recalibrate_button`) → `VM.startCalibrationAndValidationWizard()` (same mode).
* Text button "Discard" (`discard_calibration_button`) → `ConfirmDialog(DiscardCalibration)` → overview.
* Developer slot: `DevCalibrationResultDetails` (`calibration_dev_details`) — the legacy report title, per-dot sample
  table (one row per entry of `cal.pointSampleCounts`), total samples, fitted matrix "W_x / W_y" and the raw
  `recommendedGridLabel` (**#71**, **#74**, **#76**). The data exists only until Save, so it is shown here.

### 2.9 Face scan (`facescan`, `ui/facescan/`)

#### 2.9.1 Idle — `FaceScanContent` (`enrollment_card` on the status card)

`TopBar` "Face scan", Back.

* **Status card**: overline "Face scan". Headline (`enrollment_status_text`): "Not done" / "Done on <d MMM yyyy,
  HH:mm>" / "Scanning…". bodyMedium outcome (`enrollment_outcome_text`) after a scan in this visit: "Scan complete." /
  "The scan did not finish: <enrollmentFailureText>" (the 7 existing plain texts) (**#47**, **#48**).
* Without a baseline: bodyLarge (`enrollment_explanation_text`) "A 15-second scan of the face and eyes teaches Æye
  this person's own blinks and eyebrow movements, so a deliberate blink is not confused with a normal one. No images
  are stored, and nothing is synced." `BigButton` "Scan my face" (`start_enrollment_button`) (**#49**, **#50**).
* With a baseline — three plain lines (`enrollment_plain_summary`): "Blink: a deliberate blink is at least <n> ms" /
  "Natural blinks: typically <median> ms" or "Natural blinks: none measured" / "Eyebrows: raise measured" or
  "Eyebrows: not measured (step skipped)". A summary never invents a measurement. Warnings line
  (`enrollment_warnings_text`) "Warnings: Low light, Few natural blinks" (same label source, **#52**). Expander
  "Details" (`enrollment_details_toggle`) with the 6 exact `enrollmentBaselineSummaryLines` (**#51**). Privacy line
  (**#53**): "Only derived numbers are stored on this phone. No images, never synced." Buttons: `BigButton` Secondary
  "Scan again" (`rescan_enrollment_button`, **#54**); `BigButton` Danger "Remove scan" (`clear_enrollment_button`) →
  `ConfirmDialog(ClearEnrollment)` → `VM.clearEnrollmentBaseline()` (**#55**; the confirmation is new, defect 12).
* Buttons are disabled while scanning. Simulator, emulator or no working camera: `InlineNotice(warning)` "The face
  scan needs the live front camera." and the scan buttons are disabled. A running calibration:
  `InlineNotice(info)` "Finish the calibration first."

#### 2.9.2 Scanning — `EnrollmentScanOverlay` (`enrollment_scan_overlay`)

Composes nothing outside the active phases (pinned by the existing test). Full-screen opaque `Bg`. Reticle hidden,
banners deferred. Triggers stay suppressed by the ViewModel (**#89**).

| Element | Spec |
|---|---|
| Phase label | labelMedium `enrollment_phase_label`: "Step 2 of 4 — Eyes open" (`enrollmentPhaseLabel`, unchanged) (**#79**). |
| Overall progress | 6 dp bar `enrollment_overall_progress`, contentDescription "Overall scan progress n percent" (**#80**). |
| Guidance | headlineLarge `enrollment_guidance_text`, two lines reserved. The 14 `enrollmentGuidanceText` strings are unchanged (**#81**). |
| Secondary line | bodyLarge `enrollment_secondary_text` (`enrollmentSecondaryLine`, unchanged) (**#82**). |
| Eye state | `enrollment_eye_open_label`: "Eyes open" / "Eyes closed" (threshold 0.5 on `enr.liveEyeOpenProb`) with a 12 dp bar `enrollment_eye_open_bar`. Developer slot `DevScanDetails` appends the number, for example " · 0.87" (**#83**). |
| Warning chips | `enrollment_warning_chips`, children `enrollment_warning_chip_<NAME>`: `Warning` icon + labelMedium text. Not interactive (**#84**). |
| Fixation dot | centred 160 dp node `enrollment_fixation_dot`, pulsing halo only when fixation is needed, phase ring; contentDescription unchanged (**#85**). |
| Positioning preview | **POSITIONING phase only**: `CameraPreviewSurface` 144 × 192 (108 × 144 below a frame height of 700) with `FaceGuideOverlay`, centred between the dot and the buttons, with the bodyMedium line "Face found" / "Move into the oval". It sits in reserved space and is removed for the later phases, so the person looks at the dot. |
| Buttons | bottom: `BigButton` Secondary "Cancel" (`enrollment_cancel_button`, **#86**) and, in BROW_RAISE only, Secondary "Skip eyebrow step" (`enrollment_skip_brow_button`, **#87**). |

A haptic tick marks each completed phase. Leaving the screen mid-scan cancels it through `EnrollmentScanOverlayHost`
(kept, **#88**).

### 2.10 Tiles (`tiles`, `ui/speak/TilesScreen.kt`)

`TilesContent(mode)`. `TopBar` "Tiles", Back. bodyMedium intro: "Each tile shows a short label and speaks a longer
phrase. Tiles 1 to 4 are the ones shown in the 4-tile layout. All 9 are shown when a full calibration measures good
accuracy." Rows (`tile_row_<id>`, minimum 96): position badge "1" to "9" (32 dp circle, labelLarge, `SecondaryContainer`);
title titleMedium; phrase bodyMedium, up to 2 lines; rows 1 to 4 carry the labelMedium chip "In 4-tile layout";
trailing 56 dp edit button (`edit_aac_tile_<id>`, contentDescription "Edit <title>"). Row tap or the edit button opens
`TileEditDialog`. Bottom text button "Reset all tiles" (`reset_tiles_button`) → `ConfirmDialog(ResetTiles)` →
`VM.resetAacTilesToDefaults()`.

**`TileEditDialog`** (`edit_tile_dialog`): title "Edit tile <n>". `OutlinedTextField` "Label" (`edit_tile_title_field`,
one line, 24 characters, counter, hint "One or two short words read best") and "What it says"
(`edit_tile_phrase_field`, 3 lines, 140 characters). Supporting error "Both fields are needed" when either is blank.
Text button "Hear it" (`preview_tile_phrase_button`) speaks the phrase field through `LocalSpeechPreview` (the shell's
TTS engine) without firing a tile; it is disabled with "Speech is off" when no voice is installed. "Save"
(`save_tile_edit_button`, disabled when either field is blank) → `VM.updateAacTilePhrase(id, title, phrase)`.
"Cancel". For `tile_recenter` a note reads "This tile also recenters the pointer." For `tile_read_aloud`: "With its
original phrase, this tile reads out a summary of the last reading."

Default tiles (ViewModel `defaultAacTiles`, ids and special behaviours unchanged, **#103**):

| id | Label | Says |
|---|---|---|
| `tile_yes` | "Yes" | "Yes." |
| `tile_no` | "No" | "No." |
| `tile_water` | "Water" | "Could I have some water, please?" |
| `tile_comfort` | "Move me" | "Please help me change position." |
| `tile_read_aloud` | "Read back" | the generated summary: "I looked most at “<section heading>”, <n> percent of the time." / "I have not read anything yet." |
| `tile_recenter` | "Recenter" | "Recentering. Look at the middle of the screen." (runs the recenter capture, **#144**) |
| `tile_thanks` | "Thank you" | "Thank you very much." |
| `tile_question` | "Ask" | "I have a question." |
| `tile_rest` | "Eyes tired" | "My eyes need a rest." |

### 2.11 SettingsScreen (`settings`, `ui/settings/SettingsScreen.kt`)

`TopBar` "Settings", Back. Scroll column of section cards with titleMedium headers. This is the only place with
selection settings (**#91**, **#96** and **#127** are removed as duplicates).

**A. How a tile is selected** (`hud.triggerMode`, `VM.setTriggerMode`, **#93**) — four `RadioRow`s:

| Tag | Label | Enum |
|---|---|---|
| `trigger_mode_all_active` | "Holding a look, a long blink, or raised eyebrows (recommended)" | ALL_ACTIVE |
| `trigger_mode_command_blink` | "Holding a look, or a long blink" | COMMAND_BLINK |
| `trigger_mode_brow_raise` | "Holding a look, or raised eyebrows" | BROW_RAISE |
| `trigger_mode_dwell_only` | "Holding a look only" | DWELL_ONLY |

Footnote bodyMedium (`trigger_timing_footnote`) = `selectionTimingSentence(hud.selectionTimings)`.

**B. Speed** (**#109–111**):

* Four `RadioRow`s `dwell_preset_<NAME>`: "Quick: 0.65 s" / "Normal: 1 s (recommended)" / "Steady: 1.4 s, a little more
  forgiving" / "Extra steady: 1.9 s, most forgiving". A row is selected only when **both** the hold time and the
  pointer margin match the preset (`DwellSpeedPreset.matching(ms, multiplier)`). When none matches, the line
  `dwell_custom_summary_text` reads "Custom: 1.2 s hold, pointer margin 1.10×".
* Expander "Fine-tune" (`dwell_fine_tune_toggle`): "Hold time" with label `dwell_duration_label` ("1.0 s") and slider
  `dwell_duration_slider` (400–2200 ms, 17 steps); "Pointer margin" with label `dwell_sensitivity_label` ("Tighter
  (0.85×)" / "Normal" / "More forgiving (1.25×)") and slider `dwell_sensitivity_slider` (0.6–2.0, 13 steps). Slider
  touch areas are 56 high. `formatSensitivityLabel` ("1.00× (14 dp)") stays for the Developer screen.

**C. Eyebrows** — `SettingRow` "Eyebrow setup" (`open_brow_setup_row`), supporting "Teach Æye the relaxed and raised
eyebrows" → `settings/brow`.

**D. Speech** — `SettingRow` "Test the voice" (`speech_test_button`) → `LocalSpeechPreview.speak("This is how Æye
sounds.")`. When speech is unavailable: `InlineNotice(warning)` "Speech is off: no text-to-speech voice is installed."
with "Open phone speech settings" (`open_tts_settings_button`; intent `com.android.settings.TTS_SETTINGS`, falling
back to `Settings.ACTION_SETTINGS`).

Not here, by user decision 1 (Developer only): posture (**#106**), filter (**#107**), overlay mode (**#29**),
resolution (**#24**), benchmark (**#112**), drift counter (**#108**).

### 2.12 BrowSetupScreen (`settings/brow`, `ui/calibration/BrowSetupScreen.kt`)

`TopBar` "Eyebrow setup", Back. The two-step brow calibration (**#95**) with live feedback.

* bodyLarge "Æye compares the eyebrows with the relaxed face. Two steps, a few seconds each."
* **Live gauge** (`brow_gauge`): a 240 dp vertical track with a marker for the current `browToEyeRatio`, tick labels
  "Relaxed" (bottom) and "Raised" (top), and a dashed line with the label "Selects here" at
  `browRaiseTriggerThreshold`. Text under it (`brow_gauge_label`): "Eyebrows: relaxed" / "Eyebrows: raised" / "Face
  not found". No decimals. The numeric readout (**#94**) is on the Developer screen.
* Step 1 card: "1. Relax the face" + `BigButton` "Record relaxed" (`sample_neutral_brow_button`) →
  `VM.calibrateNeutralBrowBaseline()`. Inline result (`brow_step1_result_text`): "Recorded." / "Face not found. Look
  at the camera and try again."
* Step 2 card: "2. Raise the eyebrows and hold" + `BigButton` "Record raised" (`sample_raised_brow_button`) →
  `VM.calibrateRaisedBrowPeak()`. Inline result (`brow_step2_result_text`): "Recorded. The eyebrow trigger is set." /
  "The eyebrows did not go higher than relaxed. Raise them more and try again."
* The four brow notices arrive on the ViewModel notice channel and are rendered inline here, not as banners.

### 2.13 RecenterScreen (`recenter`, `ui/speak/RecenterScreen.kt`)

Purpose: the quick drift fix, reachable when the Recenter tile (position 6) is not in the 4-tile layout. It makes sure
the **user**, not the helper, is the one looking at the screen when the capture runs.

* `TopBar` "Recenter", Back. bodyLarge "Use this when tiles feel shifted. The person using Æye must be the one
  looking at the screen. Hold the phone where it normally is."
* A static fixation dot in the middle of the screen (`recenter_fixation_dot`: 16 dp dot, 56 ring). Reticle hidden.
* `BigButton` "Start" (`recenter_start_button`) → headlineLarge countdown above the dot (`recenter_countdown_text`):
  "Look at the dot · 3", "· 2", "· 1" → `VM.fireTileAction("tile_recenter", "Helper")` (the existing recenter
  capture; it speaks the tile's phrase) → "Hold still…" until the result notice arrives.
* Result (`recenter_result_text`, inline, from the notice channel): `CheckCircle` + "Recentered." with `BigButton`
  "Done" (pops to Speak) / `Warning` + "Could not recenter. Look at the dot and try again." with "Try again".
* No working camera: `InlineNotice(warning)` "Recentering needs the live front camera." Start is disabled.

### 2.14 PrivacyScreen (`privacy`, `ui/privacy/PrivacyScreen.kt`)

`TopBar` "Privacy & account", Back. Tag `privacy_settings_card` on the cloud card.

1. **Account card**: 48 dp circle with the name's initial, titleMedium name, bodyMedium email (or "Signed in with
   Google"). `BigButton` Secondary "Sign out" (`sign_out_button`; disabled while `hud.isDeletingCloudData`, **#133**) →
   `ConfirmDialog(SignOut)` → `signOutUser`.
2. **Cloud backup card**: switch row `cloud_sync_toggle` (contentDescription = `cloud_telemetry_opt_in_title`, new
   value "Cloud backup (optional)", **#128**). Status bodyLarge: on → `cloud_telemetry_status_opted_in` "On.
   Calibrations and reading summaries are copied to a private space tied to your Google account." / off →
   `cloud_telemetry_status_opted_out` "Off (default). Everything stays on this phone." Footnote: "Camera pictures and
   face-scan numbers are never copied, with or without backup."
3. **Your data card**:
   * Links: `SettingRow` "Saved calibrations (n)" → `calibrate`; "Past readings (n)" → `read/history`; "Face scan" →
     `facescan`.
   * `BigButton` Secondary "Delete cloud copies" (`delete_firestore_data_button`; loading label "Deleting…").
     **Enabled whenever a deletion is not already running, whether backup is on or off**, because copies can remain in
     the cloud after backup is turned off. → `ConfirmDialog(DeleteCloudCopies)` → `VM.deleteFirestoreCloudTelemetry()`
     (**#131**).
   * `BigButton` Danger "Erase everything" (`erase_all_cloud_button`) → `ConfirmDialog(EraseEverything)` →
     `VM.eraseAllCloudTelemetry()` → Welcome (**#132**).
   * bodyMedium `cloud_deletion_status_text`: the plain result of the last deletion ("Deleted 3 cloud items. This
     phone's data was kept." / "Could not delete the cloud copies. Check the connection and try again."), from the
     notice channel (**#130**). The raw summary is in Developer ▸ Account.
4. `SettingRow` "Privacy note" (`open_privacy_note_row`) → `PrivacyNoteSheet`.
5. Not shown to end users: UID, cloud paths, database id, "Privacy Mode" (**#129** → Developer).

### 2.15 AboutScreen (`about`, `ui/about/AboutScreen.kt`)

* App mark 56 dp, headlineMedium "Æye", bodyLarge "Æye follows your eyes with the front camera so you can speak with
  tiles and read hands-free."
* `SettingRow` "Version" (`about_version_row`), supporting "<versionName> (<versionCode>)". Seven taps → developer mode
  (1.6). Countdown text `developer_tap_hint_text`.
* `SettingRow` "How selection works" → sheet with `selectionTimingSentence(...)` and "Look at a tile for about a second
  to select it. A helper can change the time in Settings."
* `SettingRow` "Privacy note" → `PrivacyNoteSheet`. `SettingRow` "Open-source licenses" → the licenses activity if
  present, otherwise hidden.
* Developer mode on: `InlineNotice(info)` "Developer mode is on." with the action "Turn off" (`developer_mode_off_button`).

### 2.16 Developer (`developer`, `ui/developer/`) — only while developer mode is on

#### 2.16.1 DeveloperScreen

`TopBar` "Developer", Back, trailing text button "Turn off" (`developer_mode_off_button`). A fixed labelSmall header:
"DEVELOPER MODE. The numbers below are live engineering telemetry." Monospace (`OculaDevType.mono`) is allowed here,
in the developer slots and in the developer strip, and nowhere else. The reticle is drawn in full (section 4). Panels
(collapsible, state remembered); every legacy test tag is kept:

| Panel | Contents (capability numbers) |
|---|---|
| **Live status** | status dot with text (**#22**) and `analyzerStatusLabel` (**#23**); `resolution_tier_badge` button → `VM.cycleAnalysisResolutionTier()` (**#24**); single-eye badge (**#25**); "LOW FPS" badge (**#26**, **#142**); `gazeTelemetryLine` (**#27**); `hud.lastBlinkLabel` (**#43**); gesture counters (**#44**); hold-progress bar (**#45**); raw `opticalWarningText` (**#41**); raw nudge flag (**#42**); `hud.gazeInputState`. Switch `dev_hud_overlay_switch` "Show the developer strip on Speak and Read". |
| **Camera** | `CameraPreviewSurface` 240 × 320 (`live_camera_preview_box`) with `MlKitFaceOverlayCanvas` (**#31**, **#32**); switch `toggle_camera_feed_size_chip` "Large preview" (**#28**); `cameraPipelineLabel` (**#34**); frame geometry (**#35**); iris centroid (**#36**); head Euler (**#37**); `eyeOpenBrowLine` (**#38**); "No front camera" (**#33**); permission row with `request_camera_button` (**#40**). |
| **Simulator** | Card titled "SIMULATOR. Synthetic data. Results are labelled SIMULATED and never synced." Switch `toggle_simulator_chip` (**#30**; on an emulator it is locked on with "Always on: this is an emulator"; disabled with "Stop the recording / calibration / scan first" while one runs); touchpad `simulator_touchpad` 160 high (**#46**); `simulate_command_blink_button` "Blink (460 ms)"; `simulate_brow_raise_button` "Brow (400 ms)"; `switch_to_live_camera_button` "Switch to live camera" (**#39**). |
| **Engine** | filter chips `filter_mode_chip_<name>` (**#107**); posture chips `posture_chip_<name>` (**#106**); overlay cycler `toggle_overlay_mode_chip` (**#29**, enables the heat trail **#20**); drift counter (**#108**); `brow_ratio_readout` "Brow Ratio: 0.340 (N:0.34→R:0.42)" (**#94**); blink timing sliders `blink_min_command_ms_slider` (280–700) and `blink_close_threshold_slider` (0.15–0.40) → `VM.setBlinkTimingThreshold` (first UI caller); `formatSensitivityLabel` readout. |
| **Calibration** | `five_point_preview_map` with the sequence text (**#58**); raw active metrics (**#59**); `gridLayoutLabel(qualifies)` (**#90**); `calibration_eye_telemetry_text` and the `f_raw` line (**#61**). |
| **Face scan** | the 6 baseline lines and `enrollmentCompleteSummary` (**#51**); raw warnings; `hud.enrolledAtMs` (**#147**). |
| **Benchmark** | description; `run_benchmark_button` "Run replay benchmark" / "Running…"; `benchmark_summary_text` (`formatBenchmarkSummaryLine`, **#112**). |
| **Account & storage** | UID, cloud paths, database id, privacy mode (**#129**); raw `hud.lastCloudDeletionSummary` (**#130**); interaction settings dump (**#145**). |
| **Message log** | `dev_message_log`: the last 50 entries of `VM.noticeHistory`, newest first, each with time, notice key and the unchanged technical text (**#12**). "Copy" button. |

#### 2.16.2 DevHudStrip

Bottom of Speak and Read, height 40, `SurfaceLow`, mono 14: "LOCK 28fps · 1280 · G 42%,61% · L92 R90 · ±4.2% · luma
142". It is a reserved region (the grid is laid out above it), so switching it does not move tiles mid-dwell.

#### 2.16.3 Developer slots (`ui/developer/DevInline.kt`)

| Composable | Where | Shows |
|---|---|---|
| `DevWizardDetails(cal, hud)` | Calibrate wizard band | legacy heading with coordinates, raw counter text, per-dot counts, "Lock sim here" |
| `DevCalibrationResultDetails(cal)` | Calibrate result | report title, per-dot table, total samples, matrix, raw layout label |
| `DevScanDetails(enr)` | Face scan, scanning | eye-openness number, retries, elapsed time |
| `DevRecordingDetails(att)` | Read, recording | natural and command blink counts, cluster count, frame-rate guard |
| `DevSessionDetails(session)` | Replay, history rows | `formatSessionSummaryLine` (zone percentages), natural and command blink counts, cluster count |
| `DevProfileDetails(profile)` | Saved calibration rows | device model, `formatProfileErrorLine` |
