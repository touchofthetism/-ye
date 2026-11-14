# Æye — UI design specification (final)

Status: proposed design, awaiting the product owner's approval. Nothing here is implemented yet.
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

---

## 3. Banner and notification policy

### 3.1 One slot

Æye has one place for messages that are not part of a screen's own content: the banner. At most one banner is visible
at any time. There are no toasts, no snackbars and no stacked rows. The tracking status (2.0) and the spoken strip
(2.3) are separate regions. A banner never replaces or covers them, so the "Simulator" label and the last spoken phrase
stay visible whatever the banner shows.

**`Banner(item, onAction, onDismiss)`** (`ui/components/Banner.kt`, tag `app_banner`):

| Part | Spec |
|---|---|
| Container | Shape 16, `SurfaceRaised`, 1 dp `Hairline` border, 4 dp leading stripe in the kind colour. |
| Kind icon | 24 dp: `ErrorOutline` (problem), `Warning` (heads-up), `CheckCircle` (done), `Info` (note). The icon shape carries the kind. Colour only repeats it. |
| Body | bodyMedium, `TextPrimary`. With an action, the body is the button: one clickable node (`app_banner_body`, role Button, onClickLabel = the action) and its sentence ends with "Tap to …". Without an action it is plain text. |
| Dismiss | 56 × 56 icon button `app_banner_dismiss`, `Close` icon, contentDescription "Dismiss" ("Not now" on the recalibrate suggestion). |
| Announcement | contentDescription "<kind word>: <headline> <detail>". Kind words: "Problem", "Heads-up", "Done", "Note". `liveRegion` is Assertive for problems and heads-ups, Polite for the rest. |

The body is the button because a separate action button does not fit beside two lines of 16 sp text on a 360 dp
screen. One large touch area is also easier for a helper than a small text button.

Two hosts show the same `Banner`:

* **`BannerLane`** (Speak and Read, in `ui/components/Banner.kt`). The lane is always laid out at its fixed height
  (72, or 96 above font scale 1.3). The banner is 64 (88) high inside it. An empty lane draws nothing. A banner
  appears and leaves with a 150 ms cross-fade in place. Nothing changes size, so tiles and text never move
  (rule 2 of 1.1). The lane shows the headline only.
* **`BannerOverlayHost`** (`ui/shell/BannerOverlayHost.kt`, every other screen). The banner is drawn over the content,
  8 below the top bar, gutter 16, maximum width 608. It shows the headline and the detail (up to 4 lines) and takes
  the height it needs. It never pushes content. These screens are touch-only, so covering a strip of content for a
  few seconds harms nothing. Enter and exit: fade with an 8 dp slide, 150 ms.

### 3.2 Headline and detail

Every notice has a **headline** of at most 40 characters and an optional **detail** sentence.

* The lane shows the headline: two lines in the 72 lane, three in the 96 lane.
* TalkBack, the overlay host and the "Right now" list of the `TrackingHelpSheet` (2.0) give headline and detail.
* Where section 2 quotes a banner sentence, that sentence is the headline followed by the detail. Example: "Not
  calibrated yet." (headline) "The pointer is approximate. A helper can calibrate from the Menu." (detail).
* `BannerCopyFitTest` lays out every headline at 360 and 411 dp width and font scales 1.0, 1.3 and 1.5 and fails when
  one is cut off. Above 1.5 an ellipsis is accepted, because the full text is always spoken and listed in the help
  sheet.

### 3.3 Priority and time on screen

| P | Name | Icon and stripe | Longest time on screen | Used for |
|---|---|---|---|---|
| P0 | Problem | `ErrorOutline`, `Danger` | 12 s | Gaze or speech cannot work: camera off, no camera, no picture, speech off. |
| P1 | Heads-up | `Warning`, `Warning` | 10 s (6 s for "phone busy") | Tracking quality: light, glare, one eye, distance, phone busy or warm, recalibrate suggestion. |
| P2 | Result | done: `CheckCircle`, `Success`. Problem result: `ErrorOutline`, `Danger`. Simulated result: `Info`, `TextSecondary`. | 6 s (done), 10 s (problem result), 30 s (problem result with an action, overlay host only) | The outcome of something the person or helper did: saved, deleted, reset. |
| P3 | Hint | `Info`, `TextSecondary` | 6 s | A tip that needs no action. |

Every banner can be dismissed by touch and every banner leaves by itself. **No banner keeps the slot.** A person who
cannot touch the screen therefore always gets the next message. When a P0 or P1 banner leaves, the condition behind
it is still shown where it lives (3.8).

### 3.4 Queue rules

`BannerQueue` (`ui/components/BannerQueue.kt`, pure state machine: `offer(item, nowMs)`, `tick(nowMs)`,
`dismiss(key, nowMs)`, `pause()`, `resume(nowMs)`, `current`). `MainShell` owns one instance and provides it as
`LocalBannerQueue`.

1. **One at a time.** A banner stays at least 1.5 s before another may replace it.
2. **Higher priority replaces lower** once the 1.5 s have passed. Equal priority waits its turn, oldest first.
3. **Same key, same banner.** A new notice with the key of the visible one replaces its text in place and restarts
   its time.
4. **What happens to a replaced banner.** A P2 result goes back to the queue with its remaining time when at least
   2 s are left. A P3 hint is dropped. A P0 or P1 condition is not queued: conditions are re-evaluated (3.5).
5. **Queue size.** At most three P2 results (the oldest is dropped first and stays in the developer log) and one P3
   hint. A queued result waits at most 60 s, a hint at most 30 s.
6. **Results are never starved.** After two condition banners (P0 or P1) in a row, one queued result is shown before
   the next condition.
7. **Quiet period per condition.** After a condition banner leaves by timeout, the same key is not raised again for
   5 minutes. After a dismissal by touch, for 15 minutes. `SPEECH_OFF` and `NO_FRONT_CAMERA` are raised once per app
   process. A condition that was clear for 30 s and then returns may be raised again at once.
8. **Paused contexts.** While the calibration wizard collects, while the face scan runs, during the recenter
   countdown and capture, and while a dialog or sheet is open (`LocalGazeSuspension` above zero), the queue is
   paused: the visible banner is hidden, timers stop, nothing new is shown. On resume, conditions are re-evaluated
   and queued results continue. The wizard and the scan show their own inline status (2.8.2, 2.9.2).
9. **Scope by screen.**

   | Screens | Results (P2) | Conditions (P0, P1) | Hints (P3) |
   |---|---|---|---|
   | Speak | lane | all | yes |
   | Read | lane | all except `SPEECH_OFF` | no |
   | Calibrate overview | overlay | the four tracking-quality conditions | no |
   | Setup steps | overlay | none (each step has its own inline states) | no |
   | Menu, Tiles, Settings, Eyebrow setup, Privacy, About, Past readings, Replay, Face scan (idle), Recenter (idle) | overlay | none | no |
   | Developer | overlay | all | no |

### 3.5 Condition notices

Conditions are not posted by the ViewModel. The pure function `conditionNotices(hud, att, ttsUnavailable,
msSinceCameraBound, nowMs, latch): List<BannerItem>` in `ui/components/NoticePolicy.kt` derives them from state on
every HUD change. The same list feeds the banner queue, the warning icon of the `StatusBlock` and the "Right now"
list of the help sheet, so the three can never disagree. The two hints at the end of the table are events, not
states: `SpeakScreen` offers them to the queue itself.

| Key | P | Raised when | Headline | Detail | Body tap |
|---|---|---|---|---|---|
| `CAMERA_OFF` | P0 | `hud.gazeInputState == NO_CAMERA_PERMISSION` | "Camera is off. Tap to turn it on." | "Æye needs the front camera to follow your eyes. Tiles can still be tapped." | The permission prompt, or the phone's app settings when access was denied permanently (same as `tracking_help_action_camera`). |
| `NO_FRONT_CAMERA` | P0 | `hud.hasNoFrontCamera` | "This phone has no front camera." | "Æye cannot follow the eyes here. Tiles can still be tapped by hand." | none |
| `NO_PICTURE` | P0 | `NO_FRAMES` for more than 5 s after the camera was bound (**#143**) | "The camera is not sending pictures." | "Close Æye and open it again. If this keeps happening, restart the phone." | none |
| `SPEECH_OFF` | P0 | the text-to-speech engine did not start (**#11**) | "Speech is off. Tap to fix." | "No text-to-speech voice is installed. Tiles still show their words." | Opens the phone's speech settings (same intent as `open_tts_settings_button`). |
| `RECALIBRATE_NUDGE` | P1 | `hud.showRecalibrateNudge` (**#42**) | "Pointer may be off. Tap to recalibrate." | "The phone or the head seems to have moved since the calibration." | Body tag `recalibrate_nudge_button`: `VM.dismissRecalibrateNudge()`, then go to `calibrate`. Dismiss ("Not now"): `VM.dismissRecalibrateNudge()`. |
| `LOW_LIGHT` | P1 | latched `rawFeature.isLowLightDegraded` | "Too dark. Add light on the face." | "A lamp in front of the person works best. Avoid strong light from behind." | none |
| `GLARE` | P1 | latched `rawFeature.hasGlareWarning` | "Glare on the glasses." | "Tilt the glasses or the phone a little, or move the light." | none |
| `ONE_EYE` | P1 | latched `rawFeature.isSingleEyeMode` (**#25**) | "Only one eye is visible." | "Æye is following the left eye only. Accuracy may drop." (or "right", from `rawFeature.activeEyeLabel`) | none |
| `DISTANCE_CHANGED` | P1 | `hud.hasDistanceDriftWarning` | "You moved closer or further away." | "Move back to where you were, or recalibrate from the Menu." | none |
| `PHONE_BUSY` | P1 | `hud.isPerformanceModeActive` while not recording (**#26**, **#142**) | "Tracking slowed. Phone is busy or warm." | "Æye lowered the camera detail to keep up. It recovers by itself." | none |
| `THERMAL_GUARD` | P1 | `shouldShowThermalGuard(att)`, recording only (**#119**) | "Phone is warming up. Tracking slowed." | "The reading continues. The record may be less detailed." | none |
| `HINT_NOT_CALIBRATED` | P3 | first time Speak is shown in an app process while `!hud.hasActiveCalibration` | "Not calibrated yet." | "The pointer is approximate. A helper can calibrate from the Menu." | none |
| `HINT_NINE_TILES` | P3 | the Speak grid changes from 4 to 9 tiles | "Good calibration. 9 tiles are now shown." | none | none |

* `LOW_LIGHT`, `GLARE`, `ONE_EYE` and `DISTANCE_CHANGED` share one queue key (`TRACKING_QUALITY`). Only the first
  match in that order is offered, as `opticalWarningText` orders them today. They keep the existing debounce: 1 s
  on, 2 s off, through `OpticalWarningLatch` (**#41**).
* The recalibrate suggestion opens the Calibrate overview. It does not start the wizard by itself, because the
  person who taps is the helper and the person being calibrated has to be in position first.
* A recording that continues on another screen is not a banner. It is the `RecordingChip` on Speak (2.3) and the
  "Recording · m:ss" text of the Menu row (2.4).
* The simulator is not a banner. It is a tracking status (2.0), which no banner can cover.

### 3.6 Banner or inline

**Owner rule.** A notice that reports the result of an action is shown **inline** while the screen that owns the
action is the current route. It becomes a banner only when the person has already left that screen. Things that
never become banners: the wizard's counter and "Face not found" line, scan guidance and warning chips, eyebrow-setup
step results, the recenter result, form validation, list Loading / Error / empty states, "needs the live front
camera" notes, the cloud deletion status on Privacy, the calibration result card, replay statistics, and the spoken
strip.

Inline readers get their text from the same channel as the banner queue: a screen collects `VM.notices` for the keys
it owns while it is composed, and reads `VM.noticeHistory` when it needs the last result after a return (Privacy's
`cloud_deletion_status_text`).

### 3.7 ViewModel messages

The ViewModel keeps emitting every message it emits today, word for word, on the legacy `statusBannerMessage` flow.
In addition each message is posted on the notice channel with a key (section 7). The UI turns the key into plain
copy from string resources (9.2). The technical sentence is kept in `VM.noticeHistory` and shown in Developer ▸
Message log (2.16.1). `DEV_LOG` means the message has no end-user notice: the screen already shows the fact.

| Legacy message (unchanged technical text) | Key | What the person sees | Plain copy |
|---|---|---|---|
| "No face detected — face the front camera and try again." | `BROW_NO_FACE` | inline, Eyebrow setup, at the step that was tapped | "Face not found. Look at the camera and try again." |
| "Captured Neutral Brow baseline (…). Now raise eyebrows and tap 'Sample Raised Brow'." | `BROW_RELAXED_RECORDED` | inline `brow_step1_result_text` | "Recorded." |
| "Brow raise not detected (… vs neutral …) — raise higher and retry." | `BROW_RAISE_TOO_SMALL` | inline `brow_step2_result_text` | "The eyebrows did not go higher than relaxed. Raise them more and try again." |
| "Expression calibrated: Neutral … → Raised … (Trigger @ …)." | `BROW_RAISED_RECORDED` | inline `brow_step2_result_text` | "Recorded. The eyebrow trigger is set." |
| "Updated phrase tile "<title>"." | `TILE_UPDATED` | P2 done | "Tile saved." |
| (new, from `VM.resetAacTilesToDefaults()`) "AAC tiles reset to defaults." | `TILES_RESET` | P2 done | "All tiles were reset." |
| "Gaze recentered: offset ±x.x% x, ±x.x% y applied." | `RECENTER_DONE` | inline on Recenter (`recenter_result_text`). After the Recenter tile on Speak: P3. | "Recentered." |
| "Recenter cancelled — face not stable. Look at the centre of the screen and try again." | `RECENTER_FAILED` | inline on Recenter. After the Recenter tile on Speak: P2 problem. | inline "Could not recenter. Look at the dot and try again." Banner "Could not recenter." + "Look at the middle of the screen and try again." |
| "Cloud Telemetry OPTED IN — …" | `DEV_LOG` | the switch and its status line (2.14), the option card (2.2.7) | none |
| "Cloud Telemetry OPTED OUT — …" | `DEV_LOG` | the same | none |
| "Deleted n Firestore cloud document(s) under /users/<uid> (on-device vault preserved)." | `CLOUD_DELETE_DONE` (n) | inline `cloud_deletion_status_text` on Privacy. Elsewhere: P2 done. | inline "Deleted 3 cloud items. This phone's data was kept." Banner "Cloud copies deleted." |
| "Firestore deletion error: …" | `CLOUD_DELETE_FAILED` | inline on Privacy. Elsewhere: P2 problem. | inline "Could not delete the cloud copies. Check the connection and try again." Banner "Cloud copies were not deleted." |
| "Erased all local & Firestore biometric records (n cloud docs removed)." | `ERASE_DONE` | P2 done, on Welcome | "Everything was erased." |
| "Cleared local vault; cloud erase error: …" | `ERASE_CLOUD_FAILED` | P2 problem with an action, on Welcome, 30 s. Body tap runs `VM.deleteFirestoreCloudTelemetry()` again. | "Cloud copies remain. Tap to try again." + "This phone's data was erased, but the cloud copies could not be deleted." |
| "Posture Profile: <label> (Y-bias …)." | `DEV_LOG` | the posture chip on Developer ▸ Engine | none |
| "Gaze Stabilization: <label>" | `DEV_LOG` | the filter chip on Developer ▸ Engine | none |
| "Dwell Speed Preset: <title> (n ms • n% tolerance)" | `DEV_LOG` | the selected row in Settings ▸ Speed | none |
| "Synthetic replay benchmark PASS / FAILURES DETECTED: …" | `DEV_LOG` | `benchmark_summary_text` on Developer ▸ Benchmark | none |
| "Benchmark failed: …" | `DEV_LOG` | Developer ▸ Benchmark shows the newest benchmark line of the log under the button | none |
| "Enrollment scan started — …" | `DEV_LOG` | the scan screen itself | none |
| "Enrollment needs the live front camera — …" (simulator and emulator variants) | `DEV_LOG` | cannot be triggered: the scan buttons are disabled beside "The face scan needs the live front camera." (2.9.1) | none |
| "Finish or cancel the running calibration before starting the enrollment scan." | `DEV_LOG` | cannot be triggered: the buttons are disabled beside "Finish the calibration first." | none |
| "Enrollment scan cancelled — blink and brow detection are unchanged." | `SCAN_CANCELLED` | inline `enrollment_outcome_text`. Elsewhere: P2 note. | Banner "Face scan cancelled. Nothing changed." |
| "Enrollment complete: eyes open … Warnings: …" | `SCAN_DONE` | inline (status card and summary lines). Elsewhere: P2 done. | Banner "Face scan saved." |
| "Enrollment failed: …" (6 variants) | `SCAN_FAILED` | inline `enrollment_outcome_text`. Elsewhere: P2 problem. | inline "The scan did not finish: <enrollmentFailureText>". Banner "The face scan did not finish." |
| "Enrollment cleared — …" (2 variants) | `SCAN_REMOVED` | P2 done | "Face scan removed." + "Blink and eyebrow selection use standard timings." |
| "Enrollment ended without a measurable result — nothing was changed." | `SCAN_NO_RESULT` | inline `enrollment_outcome_text`. Elsewhere: P2 problem. | inline "The scan did not finish. Nothing was changed." Banner "The face scan did not finish." |
| "Enrollment scan cancelled: calibration started. …" | `SCAN_STOPPED_FOR_CALIBRATION` | held while the wizard runs, then P2 note | "Face scan stopped for calibration." |
| "No face detected — face the front camera, look at the target and try again." | `DEV_LOG` | the wizard counter: "Face not found. Look at the camera." (2.8.2) | none |
| "Capturing live gaze samples — keep looking at the target." | `DEV_LOG` | the wizard counter: "Collecting… n of 18" | none |
| "Calibration error was not measured — run the calibration again before saving." | `DEV_LOG` | the result card "Not measured" with Save disabled (2.8.3) | none |
| "Calibration saved to on-device storage (Cloud Sync is paused)." | `CALIBRATION_SAVED` | P2 done | "Calibration saved on this phone." |
| "Calibration profile synced to Cloud Firestore & local vault." | `CALIBRATION_SAVED_BACKED_UP` | P2 done | "Calibration saved and backed up." |
| "Saved locally; Cloud sync error: …" | `CALIBRATION_BACKUP_FAILED` | P2 problem | "Saved on this phone. Backup failed." + "The calibration works. It was not copied to the cloud." |
| "Simulated calibration saved on this device only (not synced, labelled SIMULATED)." (with or without the eviction note) | `CALIBRATION_SAVED_SIMULATED` | P2 note, `Info` icon, never the done icon | "Simulated calibration saved." + "Labelled SIMULATED. Never backed up. Not a real measurement." |
| "Activated profile: <name> (x% mean • x% max)" / "… (accuracy not validated)" | `PROFILE_IN_USE` | inline on Calibrate: the "In use" label moves and the accuracy card updates (Polite live region) | none |
| "Calibration profile deleted from this device and Cloud Firestore." / "Local calibration profile deleted." | `PROFILE_DELETED` | P2 done | "Calibration deleted." |
| "Deleted on this device; cloud delete failed: …" | `PROFILE_DELETE_CLOUD_FAILED` | P2 problem | "Deleted here. The cloud copy remains." + "Remove it with Privacy ▸ Delete cloud copies." |
| "Session saved to on-device storage (Cloud Sync is paused)." | `READING_SAVED` | P2 done | "Reading saved on this phone." |
| "Attention session synced to Cloud Firestore & local vault." | `READING_SAVED_BACKED_UP` | P2 done | "Reading saved and backed up." |
| "Simulated session saved on this device only …" | `READING_SAVED_SIMULATED` | P2 note, `Info` icon | "Simulated reading saved." + "Labelled SIMULATED. Never backed up. Not a real measurement." |
| "Stop & Save the current recording before opening a replay." | `DEV_LOG` | cannot be triggered: rows are disabled beside `replay_locked_while_recording_text` (2.7) | none |
| "This session was recorded before passage-aligned fixations; its fixation heatmap is not shown." | `DEV_LOG` | the disabled switch "Not available for this reading" (2.6) | none |
| "Session deleted from this device and Cloud Firestore." / "Local session deleted." | `READING_DELETED` | P2 done | "Reading deleted." |
| Any message not in this table | `DEV_LOG` | Developer ▸ Message log only | none |

`NoticeMappingTest` reads every `postNotice` call site and fails when a key has no row in the UI's `NoticeSpec` table
or a non-`DEV_LOG` key has no string resource. A new ViewModel message therefore cannot reach users as raw text and
cannot be lost silently.

### 3.8 Where a condition stays visible after its banner has left

| Condition | Permanent home |
|---|---|
| Camera off, no camera, no picture | `StatusBlock` title and caption (2.0). Help sheet advice and **Turn on camera**. |
| Speech off | Spoken strip: `VolumeOff` icon and "· shown, not spoken" (2.3). Help sheet "Right now" and **Fix speech**. Settings ▸ Speech notice (2.11). |
| Low light, glare, one eye, distance, phone busy or warm | `StatusBlock` trailing `Warning` icon and stateDescription. Help sheet "Right now" list with headline and detail. |
| Recalibration suggested | `StatusBlock` trailing `Warning` icon. Help sheet "Right now" and **Recalibrate**. |
| Not calibrated, not checked, accuracy limited | `StatusBlock` caption. Help sheet layout line. Menu tracking card (2.4). |
| Simulator | `StatusBlock` title "Simulator". Spoken strip prefix. Reticle shape (section 4). |

---

## 4. Reticle policy

The reticle is the mark that shows where Æye thinks the person is looking. Today it is drawn over everything, with a
filled ellipse, ticks, a number pill and a heat trail. In this design it appears only where gaze does something, it
is made of thin outlines, and it never carries text outside developer mode.

### 4.1 Component and layering

`GazeReticle(hud, mode, clipBounds, onReticleRootPositionChanged)` in `ui/components/GazeReticle.kt` (moved from
`RegionConfidenceReticleOverlay`; tag `realtime_gaze_canvas_overlay` kept). It draws only. It has no pointer input and
no semantics, so it never takes a touch and TalkBack never lands on it.

Layering inside `MainShell` (1.7), bottom to top: `NavHost` content (including the calibration target and the scan
overlay) → `BannerOverlayHost` → `DevHudStrip` → `GazeReticle`. Dialogs and bottom sheets are separate windows above
all of it, and the reticle is hidden while one is open.

The position feed is independent of drawing. `onReticleRootPositionChanged` keeps reporting the interpolated root
position on Speak, Read and Developer whether or not anything is drawn (**#21**). Hit testing therefore never depends
on the reticle being visible. Display-rate interpolation and the `ReadInOwnScope` isolation are unchanged.

### 4.2 When it is drawn

`reticleMode(route, status, isRecording, gazeSuspended, devStripOn): ReticleMode` is a pure function in
`ui/components/ReticlePolicy.kt`. `status` is the result of `trackingStatus(...)` (2.0), so the reticle and the status
block use the same debounced state.

| Where | Mode | Reason |
|---|---|---|
| `speak` | **RING** | Gaze selects tiles here. |
| `read`, recording | **DOT** | Gaze is measured. Text must stay readable. |
| `read`, not recording | HIDDEN | Nothing is measured. The per-section results are the content. |
| `read/replay`, `read/history` | HIDDEN | The replay has its own heatmap (2.6). |
| `developer` | **FULL** | Telemetry. |
| `speak` and `read` while developer mode is on **and** the developer strip switch is on | **FULL** | A developer can see the trail over the real screens. Off by default. |
| Sign-in, every `setup/…` step, `menu`, `calibrate` (overview, wizard and result), `facescan` (idle and scanning), `recenter`, `tiles`, `settings`, `settings/brow`, `privacy`, `about` | HIDDEN | Touch-only screens: the helper is operating the phone and a moving mark is noise. During the wizard, the scan and the recenter capture the person must look at the dot, not chase a pointer. |

On top of the table, the reticle is HIDDEN on every route when any of these holds:

| Condition | Behaviour |
|---|---|
| A dialog or sheet is open (`LocalGazeSuspension` above zero) | Hidden at once. Tile focus is cleared (**#100**). |
| Status is "Camera off", "No camera", "Starting" or "No picture" | Hidden. Nothing is being measured. |
| Status is "No face" (face lost for more than 900 ms) | Fades out over 200 ms. |
| Status is "Paused" (eyes closed for more than 800 ms) | Fades out over 200 ms. |
| Tracking returns | Fades in over 120 ms. |

During a normal blink or a detection gap shorter than the grace time the reticle stays where it was. It does not
flicker.

### 4.3 What is drawn

**RING (Speak).** All strokes, no fill, no text.

| Element | Spec |
|---|---|
| Pointer | A ring, 28 dp across, 2.5 dp stroke, accent at 90 % opacity, with a 1 dp `Bg` outline at 60 % so it stays visible on a focused tile. Centre dot 6 dp, accent. |
| Uncertainty outline | The ellipse from `reticleUncertaintyRadiiPx(hud.activeErrorRadiusPct, …)`. The maths is unchanged: 20 % of each axis when no checked accuracy exists, never smaller than 28 dp, never larger than half an axis. Drawn as a 1.5 dp dashed outline (6 on, 6 off), accent at 45 % opacity. **Never filled.** An uncalibrated or unchecked pointer therefore shows a large outline, which is the honest picture, and tile labels stay readable through it. |
| Progress | **None on the reticle.** Dwell, blink-hold and brow-hold progress are the 6 dp bar on the focused tile (2.3). One indicator, on the thing that will fire (**#18**, **#98**). |
| Ticks, coordinate pill, heat trail | Not drawn (**#19**, **#20** are developer only). |
| Simulated gaze (`hud.gazeInputState == SIMULATED`) | The ring becomes a **diamond** outline (a square turned 45°, 28 dp, same stroke) and the uncertainty outline becomes dotted (2 on, 6 off). The shape differs, not only the colour, and no text is needed. The status block says "Simulator" at the same time. |

**DOT (Read, recording).** A 10 dp dot, accent at 60 % opacity, with a 1.5 dp `Bg` outline. No ring, no uncertainty
outline, no progress. The accent bar and the "Reading" tag on the section (2.5) are the main feedback, so the dot can
be small. Simulated gaze: the dot becomes a 10 dp diamond.

**FULL (developer).** The legacy drawing, for engineering use: the layers follow `hud.overlayVisualMode` (pointer,
heat trail, or both); solid uncertainty ellipse (2 dp stroke, still no fill); N/S/E/W ticks; a 360° progress arc
(4 dp, accent) = max(dwell, blink hold, brow hold); centre dot; the coordinate pill "xx%, yy%" in `OculaDevType.mono`
14 sp; heat trail and live aura with the legacy colour ramp. The ramp's extra colours are allowed only here.

### 4.4 How it avoids covering controls and text

1. **It is absent on every touch-only screen.** List rows, buttons, sliders, dialogs and forms are never under it.
2. **It is clipped to the gaze content.** `SpeakScreen` reports the bounds of the tile grid and `ReadScreen` the
   bounds of the passage viewport through their `onGazeContentBounds` parameter (root coordinates, the same way the
   root gaze frame is reported today). `GazeReticle` clips its drawing to those bounds. It is never drawn over the
   top bar, the status block, the banner lane, the spoken strip, the Read action bar or the developer strip. The
   position used for hit testing is not clipped or clamped.
3. **At the edge it fades instead of vanishing.** When the gaze point is outside the clip bounds, the pointer is
   drawn at the nearest point on the edge at 35 % opacity, without the uncertainty outline. The person still sees
   that Æye is following, and nothing in the chrome is covered. Nothing outside the grid can be fired by gaze
   (rule 1 of 1.1).
4. **Outlines only.** No filled area ever sits on a tile label or on passage text. The widest stroke is 2.5 dp.
5. **No text.** The 10 sp coordinate pill is gone from daily use.
6. **Progress is on the tile**, along its bottom edge, away from the label.
7. **On Read it is a dot**, and it is hidden whenever the person is not being recorded.
8. **It leaves when Æye is not tracking**, so a frozen mark is never shown as live gaze.

`ReticlePolicyTest` pins the mode table, the hide conditions and the simulated shapes. `DiagRegressionTest` keeps
pinning `reticleUncertaintyRadiiPx` (per axis, wide and finite when not checked).

---

## 5. Design tokens

Files: `ui/theme/Color.kt`, `Type.kt`, `Theme.kt`, new `Tokens.kt` (spacing, shapes, sizes, motion) and new
`DevType.kt`. The theme stays dark only: a dark screen puts less glare on glasses, which helps tracking. `themes.xml`
is unchanged (pinned by a test).

### 5.1 Colour

One accent (cyan). Success, warning and danger are **semantic** colours: they appear only together with an icon or a
word that says the same thing. Green is no longer a second accent: progress bars, the calibration target and focus
all use the accent.

| Token | Hex | Material role | Use | Contrast |
|---|---|---|---|---|
| `Bg` | #0B0F17 | background, surfaceDim, surfaceContainerLowest | Window background, wizard, scan, banner lane | |
| `SurfaceLow` | #0F1524 | surfaceContainerLow | Read action bar, developer strip | |
| `Surface` | #151D2E | surface, surfaceContainer | Cards, tiles, rows, stat tiles (today #131B2E) | |
| `SurfaceRaised` | #1C2742 | surfaceVariant, surfaceContainerHigh, surfaceContainerHighest | Banners, inline notices, chips, dialogs, sheets, switch track (off) | |
| `SecondaryContainer` | #243150 | secondaryContainer | Position badges, neutral chips, tonal icon buttons | text 11.8 : 1 |
| `Hairline` | #5B6F96 | outlineVariant | Edges of tiles, cards and banners, dividers | 3.8 : 1 on `Bg`, 3.3 : 1 on `Surface` |
| `Outline` | #7C8DA8 | outline | Borders of controls: secondary buttons, text fields, switch handle (off), radio and checkbox (off) | 4.4 : 1 on `SurfaceRaised`, 5.0 : 1 on `Surface` |
| `Accent` | #00E5FF | primary | Focus border and tile progress, primary buttons, links, selected controls, reticle, calibration target | 12.5 : 1 on `Bg`, 10.9 : 1 on `Surface` |
| `AccentContainer` | #00414D | primaryContainer | Selected rows and option cards | text 10.3 : 1 |
| `OnAccent` | #00262C | onPrimary | Text and icons on `Accent` | 10.4 : 1 |
| `Success` | #10B981 | extension colour (not a Material role) | "Done" icons with their word, done banners, the Fired tile border with its check | 6.6 : 1 on `Surface` |
| `Warning` | #F59E0B | tertiary | Warning icons with their text, heads-up banners | 7.8 : 1 on `Surface` |
| `OnWarning` | #2A1800 | onTertiary | Text on `Warning` fills | 8.0 : 1 |
| `WarningContainer` | #3D2A05 | tertiaryContainer | Not used by app screens. Set so Material components never fall back. | |
| `Danger` | #F87171 | error | Destructive buttons, problem banners, error notices (lighter than today's #EF4444 so 16 sp text passes 4.5 : 1) | 6.1 : 1 on `Surface`, 5.4 : 1 on `SurfaceRaised` |
| `OnDanger` | #2B0B0B | onError | Text on a `Danger` fill (confirm buttons) | 6.6 : 1 |
| `DangerContainer` | #4A1215 | errorContainer | Text-field error state | |
| `TextPrimary` | #F1F5F9 | onBackground, onSurface | Headings, body, labels | 15.4 : 1 on `Surface` |
| `TextSecondary` | #A7B4C9 | onSurfaceVariant, secondary | Supporting text, overlines, neutral icons (lighter than today's #94A3B8) | 8.0 : 1 on `Surface`, 7.1 : 1 on `SurfaceRaised` |
| `TextDisabled` | #64748B | (disabled content) | Disabled labels only. Never carries information on its own. | 3.5 : 1 |
| `Scrim` | #000000 at 60 % | scrim | Behind dialogs and sheets | |

Rules:

* No elevation shadows and no tonal tint. `surfaceTint` is set to `Surface`, so raised Material surfaces never pick up
  a cyan cast. Depth comes from the four surface steps and from `Hairline`.
* Focused tile: 12 % `Accent` over `Surface` (about #123547). `TextPrimary` on it is 11.8 : 1.
* Data marks on the replay heatmap (2.6) use one hue: splats are `Accent` from 15 % to 60 % opacity, the path is a
  dashed `TextSecondary` line, node discs are `Accent` with `OnAccent` numbers. The crimson-to-amber ramp is gone
  from end-user screens.
* The developer screens may use the legacy multi-colour heat trail and amber brow bars. They are engineering views.
* The old names (`ObsidianBackground`, `SlateSurface`, `OpticalCyan`, `ValidationEmerald`, `ThermalAmber`,
  `AlertCoral` and the rest) stay as `@Deprecated` aliases until the legacy screens are deleted (9.3), then go.

### 5.2 Type

Fonts are the three files already in `res/font`: Space Grotesk, Plus Jakarta Sans, JetBrains Mono. Each is a single
Regular face, declared at Regular only (pinned by a test). Heavier weights are synthesized as today. No new font
files. Sizes are in sp and follow the system font scale.

| Role | Font | Size / line height (sp) | Weight | Used for |
|---|---|---|---|---|
| headlineLarge | Space Grotesk | 32 / 38 | Bold | "Æye" on sign-in, "Ready", scan guidance, recenter countdown |
| headlineMedium | Space Grotesk | 26 / 32 | Bold | Setup greetings and intros, the accuracy headline, the app name on About |
| headlineSmall | Space Grotesk | 24 / 30 | SemiBold | Dialog titles, the wizard line "Look at the dot", camera-step headings, result titles |
| titleLarge | Space Grotesk | 22 / 28 | SemiBold | Top bar titles, passage headings, `StatTile` values |
| titleMedium | Space Grotesk | 18 / 24 | Medium | Status title, row labels, card headers, checklist rows |
| bodyLarge | Plus Jakarta Sans | 18 / 26 | Normal | Passage text, explanations, the spoken strip, wizard and scan lines |
| bodyMedium | Plus Jakarta Sans | 16 / 24 | Normal | Supporting text, captions, banners, inline notices. **The floor.** |
| labelLarge | Space Grotesk | 18 / 24 | SemiBold | `BigButton` and text-button labels, position badges |
| labelMedium | Space Grotesk | 16 / 20 | Medium | Chips and tags ("Reading", "In use", "In 4-tile layout"), overlines (in `TextSecondary`), the scan phase label, heatmap node numbers |
| labelSmall | Space Grotesk | 16 / 20 | SemiBold, letter spacing 0.5 sp, set in capitals | Only "DEV", "SIMULATED" and the "DEVELOPER MODE" header |
| `OculaType.tileLarge` | Space Grotesk | 32 / 36 | Bold | Tile labels in the 4-tile layout. Shrinks step by step to 24 before it ellipsizes. |
| `OculaType.tileSmall` | Space Grotesk | 24 / 28 | Bold | Tile labels in the 9-tile layout |
| `OculaDevType.mono` | JetBrains Mono | 14 / 18 | Normal | Developer screen, developer slots and developer strip only |

* Roles that no screen uses are still set, so a Material component can never fall back to Roboto or go under the
  floor: displayLarge, displayMedium and displaySmall equal headlineLarge; titleSmall is Space Grotesk 16 / 22
  Medium; bodySmall is Plus Jakarta Sans 16 / 22.
* **Floor.** No text an end user can see is smaller than 16 sp. Tile labels are never smaller than 24 sp.
  `OculaDevType.mono` is the only smaller style and it is defined in `DevType.kt`, outside `Typography`, so app code
  cannot reach it through `MaterialTheme.typography`.
* **Monospace** appears only in `ui/developer/`. A source-scan test fails when `OculaDevType` or `jetbrains_mono` is
  referenced from any other package.
* **Large font scales.** Fixed-height regions use their second value above font scale 1.3 (section 2). Tile labels
  use at most font scale 1.5. Nothing in the end-user interface scrolls sideways.
* Numbers shown to end users use the device locale. Developer text keeps `Locale.US`, as the existing tests pin.

### 5.3 Spacing and shape (`Tokens.kt`)

| Token | Value | Use |
|---|---|---|
| `Space.xs` / `sm` / `md` / `lg` / `xl` / `xxl` / `xxxl` | 4 / 8 / 12 / 16 / 24 / 32 / 48 | The only spacing steps |
| Screen gutter | 16 (24 at widths of 600 and above) | All screens |
| Content width cap | 640, centred (480 for single-column forms and `BigButton`) | Wide screens |
| Space between cards or sections | 16 | Scroll columns |
| Card padding | 16 | Cards, notices |
| Tile grid | gap 12, outer padding 16 | Speak |
| `Shape.tile` | 24 | Tiles |
| `Shape.card` | 20 | Cards, inline notices, stat tiles, camera preview, passage container |
| `Shape.control` | 16 | Buttons, banners, text fields |
| `Shape.dialog` | 28 (top corners only on sheets) | Dialogs, bottom sheets |
| `Shape.pill` | fully rounded | Chips, badges, step pills |

### 5.4 Sizes

| What | Value |
|---|---|
| Minimum touch target | 56 × 56. Applies to every clickable node. `minimumInteractiveComponentSize` (48) is not enough: nodes use `sizeIn(minWidth = 56.dp, minHeight = 56.dp)`. |
| Gaze tile | at least 96 × 96, otherwise the grid falls back to 4 tiles (2.3) |
| Top bar | 56 (72 above font scale 1.3) |
| Banner lane, spoken strip | 72 (96 above font scale 1.3) |
| `BigButton` | 64 high, full width up to 480 |
| `SettingRow`, checkbox row | at least 72 |
| List rows with two supporting lines, option cards | at least 96 |
| `StatTile` | 88 |
| Read action bar | 96 |
| Developer strip | 40 |
| Slider touch height | 56 |
| Icons | 20 (status warning mark), 24 (inline, banner), 28 (rows, status, menu), 32 (tiles, fired check), 64 (empty and hero states), 72 (app mark on sign-in) |
| Strokes | tile and card edge 2 (`Hairline`), secondary button border 2 (`Outline`), focus border 3, tile progress bar 6, calibration ring 3 and progress ring 4, reticle 2.5 and 1.5 |

### 5.5 Motion and haptics

* **No layout animation on Speak and Read.** `AnimatedVisibility`, `animateContentSize` and animated padding are not
  used in `ui/speak/` and `ui/reading/`. A source-scan test enforces it.
* Tile focus border: appears at once, clears with a 100 ms fade. Fired border: 600 ms, then a 100 ms fade.
* Progress (tile bar, calibration ring, scan ring, overall bar) follows the ViewModel value linearly, with no easing.
* Banner: 150 ms cross-fade in the lane, 150 ms fade with an 8 dp slide in the overlay host.
* Reticle: 200 ms fade out, 120 ms fade in.
* Screen changes: 150 ms cross-fade. No slide, no spring, no bounce.
* Calibration halo and scan dot: 1200 ms sine pulse. With animations turned off in system settings
  (`ANIMATOR_DURATION_SCALE == 0`) every pulse is a static ring and every fade is instant.
* Haptics: the tile trigger keeps today's confirm effect (**#10**). New: one `CLOCK_TICK` on each finished setup
  step, each finished calibration dot and each finished scan phase.

### 5.6 Material 3 colour scheme (`Theme.kt`)

```kotlin
val OculaDarkColorScheme = darkColorScheme(
    primary = Accent,                 onPrimary = OnAccent,
    primaryContainer = AccentContainer, onPrimaryContainer = TextPrimary,
    secondary = TextSecondary,        onSecondary = Bg,                    // neutral: only cyan reads as accent
    secondaryContainer = SecondaryContainer, onSecondaryContainer = TextPrimary,
    tertiary = Warning,               onTertiary = OnWarning,
    tertiaryContainer = WarningContainer, onTertiaryContainer = TextPrimary,
    error = Danger,                   onError = OnDanger,
    errorContainer = DangerContainer, onErrorContainer = TextPrimary,
    background = Bg,                  onBackground = TextPrimary,
    surface = Surface,                onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised,   onSurfaceVariant = TextSecondary,
    surfaceTint = Surface,
    surfaceDim = Bg,                  surfaceBright = SurfaceRaised,
    surfaceContainerLowest = Bg,      surfaceContainerLow = SurfaceLow,
    surfaceContainer = Surface,       surfaceContainerHigh = SurfaceRaised,
    surfaceContainerHighest = SurfaceRaised,
    outline = Outline,                outlineVariant = Hairline,
    inverseSurface = TextPrimary,     inverseOnSurface = Bg,
    inversePrimary = AccentContainer, scrim = Color.Black,
)
```

`OculaGazeTheme` also provides `LocalOculaColors` (`success`, `textDisabled`) for the two colours that have no
Material role, `OculaType` (the two tile styles) and the `Tokens.kt` objects. System bars stay dark, as today.

Component defaults set once in the theme: `Switch` (checked track `Accent`, thumb `OnAccent`; unchecked track
`SurfaceRaised`, thumb and border `Outline`), `Slider` (active `Accent`, inactive `SurfaceRaised`, 56 high),
`Checkbox` and `RadioButton` (28 dp, `Accent` when selected, `Outline` when not), `OutlinedTextField` (shape 16,
`Outline` border, `Accent` when focused, `Danger` with an icon when in error).

---

## 6. Capability map

Every numbered capability of `docs/UI_INVENTORY.md` section 2, with its new home. "Developer" means the Developer
screen (2.16), which exists only while developer mode is on. "Slot" means a developer slot on an end-user screen
(2.16.3). **REMOVE** rows give the reason. Eight capabilities are removed outright (#6, #7, #13, #91, #96, #105,
#113, #127) and two in part (#5, #68). In every case the function survives elsewhere; what goes is a duplicate, a
piece of old navigation or a device claim. All other rows are moves. Count: 147 rows, checked by script.

| # | Capability | New home |
|---|---|---|
| | **A. Shell, navigation, global bars** | |
| 1 | Google sign-in gate | `SignInScreen` (2.1). The signed-in / signed-out switch is unchanged (1.2). |
| 2 | Automatic sign-in for returning users, suppressed after sign-out | 2.1 and 1.5. Logic moves unchanged to `ui/auth/GoogleSignIn.kt`. |
| 3 | "Sign in with Google" button with spinner and disabled state | 2.1 `google_sign_in_button` (`BigButton` loading form "Signing in…"). |
| 4 | Sign-in error card | 2.1 `InlineNotice(error)` `sign_in_error_text`, same mapped messages. |
| 5 | Sign-in intro copy | Kept: title, subtitle and app mark (2.1); the privacy notice as `PrivacyNoteSheet` (2.1, 2.2.2). **REMOVE in part**: the "PIXEL 10 PRO XL" badge, the engineering feature rows and the cloud paths. Reason: device marketing and jargon in front of the one action the person needs. |
| 6 | Bottom navigation with 4 tabs | **REMOVE.** Replaced by the Speak home and the Menu (1.3, 2.4). Reason: the tabs used jargon, opened on Calibrate instead of the daily screen and took space from the tiles. Every destination still exists as a route. |
| 7 | System Back returns to tab 0 | **REMOVE.** Replaced by the Back rules of 1.4, which always end on Speak. |
| 8 | Camera permission flow | Setup ▸ Camera (2.2.3): prompt, denied, permanently denied, granted. Re-check on resume in `MainShell` (1.7). Denial no longer turns on the simulator: it shows "Camera off" (2.0), the `CAMERA_OFF` banner (3.5) and the help-sheet action. |
| 9 | Per-user ViewModel scope | `MainShell(uid)` (1.7), unchanged. |
| 10 | Text-to-speech and haptic on tile trigger | `MainShell` (1.7), unchanged. Added: the Fired tile state (2.3) and `LocalSpeechPreview` for previews. |
| 11 | Speech-unavailable banner | `SPEECH_OFF` banner (3.5); spoken strip `VolumeOff` and "· shown, not spoken" (2.3); Settings ▸ Speech (2.11); help sheet **Fix speech** (2.0). |
| 12 | Status banner row (about 45 messages) | The single banner (section 3): `BannerLane` on Speak and Read, `BannerOverlayHost` elsewhere. Every message is mapped in 3.7. Technical text: Developer ▸ Message log (2.16.1). |
| 13 | Inline cloud bar on three tabs | **REMOVE.** Reason: a duplicate of the Privacy controls in engineering wording, on top of every screen. The state is on the Menu row `menu_row_privacy` (2.4); the controls are on Privacy (2.14). |
| 14 | Cloud sync switch in the inline bar | Privacy `cloud_sync_toggle` (2.14), now the only instance. First-run choice: Setup ▸ Backup (2.2.7). |
| 15 | "Delete Cloud" shortcut with its own dialog | Privacy `delete_firestore_data_button` with `ConfirmDialog(DeleteCloudCopies)` (2.14, 2.0). The duplicate `confirm_delete_cloud_*` dialog tags are retired. |
| 16 | Display physical size reporting | `MainShell` root gaze frame (1.7), unchanged. |
| 17 | Gaze reticle, pointer layer | `GazeReticle` (section 4): ring on Speak, dot on Read while recording, full drawing on Developer. |
| 18 | Reticle progress arc | End users: the 6 dp progress bar on the focused tile (2.3). The arc itself: FULL reticle, developer only (4.3). |
| 19 | Reticle coordinate pill | FULL reticle, developer only (4.3). Also in the `DevHudStrip` (2.16.2). |
| 20 | Heat trail and live aura | FULL reticle, developer only, switched by `toggle_overlay_mode_chip` (4.3, 2.16.1 Engine). |
| 21 | Reticle root-position feed | `GazeReticle.onReticleRootPositionChanged` (4.1), consumed by the Speak and Read hit tests. Unchanged. |
| | **B. Persistent diagnostics bar** | |
| 22 | Status dot | Developer ▸ Live status. End users: `StatusBlock` icon and words (2.0). |
| 23 | Status label ("OPTICAL LOCK • n FPS" …) | Developer ▸ Live status (`analyzerStatusLabel`). End users: `StatusBlock` titles (2.0). |
| 24 | Resolution tier badge and cycler | Developer ▸ Live status `resolution_tier_badge`; shown in `DevHudStrip`. |
| 25 | Single-eye badge | Developer ▸ Live status. End users: `ONE_EYE` banner naming the eye (3.5); help sheet "Right now" (2.0). |
| 26 | "LOW FPS" badge | Developer ▸ Live status. End users: `PHONE_BUSY` banner (3.5); help sheet "Right now". |
| 27 | Telemetry line | Developer ▸ Live status (`gazeTelemetryLine`); `DevHudStrip` (2.16.2). |
| 28 | "Cam Full / Cam Mini" chip | Developer ▸ Camera switch `toggle_camera_feed_size_chip` "Large preview". |
| 29 | Overlay mode chip | Developer ▸ Engine `toggle_overlay_mode_chip`. End users have fixed reticle forms (section 4). |
| 30 | Simulator chip | Developer ▸ Simulator `toggle_simulator_chip`, labelled, locked on for emulators (2.16.1, 1.6). |
| 31 | Live camera preview and the camera binding | Binding: `CameraPipelineHost` in the shell (1.7). Preview: `CameraPreviewSurface` on Setup ▸ Camera (2.2.3), in the positioning phase of the scan (2.9.2) and on Developer ▸ Camera (`live_camera_preview_box`). |
| 32 | ML Kit face overlay on the preview | Developer ▸ Camera (`MlKitFaceOverlayCanvas`). End users: the simpler `FaceGuideOverlay` (2.0). |
| 33 | "No front camera" label | `hud.hasNoFrontCamera`: status "No camera" (2.0), Setup ▸ Camera state (2.2.3), `NO_FRONT_CAMERA` banner (3.5), Developer ▸ Camera. |
| 34 | Camera pipeline label | Developer ▸ Camera. |
| 35 | Frame geometry line | Developer ▸ Camera. |
| 36 | Iris centroid line | Developer ▸ Camera. |
| 37 | Head angle line | Developer ▸ Camera. |
| 38 | Eye-open and brow line | Developer ▸ Camera (`eyeOpenBrowLine`). |
| 39 | "Switch to Live Front Camera" button | Developer ▸ Simulator `switch_to_live_camera_button`. |
| 40 | Permission-denied row with "Enable Camera" | Setup ▸ Camera `request_camera_button` and `open_app_settings_button` (2.2.3); status "Camera off" with help-sheet action `tracking_help_action_camera` (2.0); `CAMERA_OFF` banner (3.5); Developer ▸ Camera permission row. |
| 41 | Optical warning slot (low light, glare, distance) | Banners `LOW_LIGHT`, `GLARE`, `ONE_EYE`, `DISTANCE_CHANGED` with the same debounce (3.5); `StatusBlock` warning mark and help sheet "Right now" (2.0, 3.8). Raw text: Developer ▸ Live status. The always-reserved two-line slot is gone. |
| 42 | Recalibrate nudge | `RECALIBRATE_NUDGE` banner with `recalibrate_nudge_button` and "Not now" (3.5); help sheet **Recalibrate** (2.0). Raw flag: Developer ▸ Live status. |
| 43 | Blink and brow classification label | Developer ▸ Live status (`hud.lastBlinkLabel`). End users see the method word on the spoken strip (2.3). |
| 44 | Gesture counters | Developer ▸ Live status. |
| 45 | Gesture hold progress bar | Developer ▸ Live status. End users: the tile progress bar (2.3). |
| 46 | Simulator pad (touchpad, blink, brow) | Developer ▸ Simulator (`simulator_touchpad`, `simulate_command_blink_button`, `simulate_brow_raise_button`). |
| | **C. Calibrate tab, idle overview** | |
| 47 | Enrollment title and status | Face scan status card `enrollment_status_text` (2.9.1); Menu tracking card (2.4); Welcome checklist (2.2.1). |
| 48 | Enrollment outcome text | Face scan `enrollment_outcome_text` (2.9.1); Setup ▸ Face scan (2.2.4). |
| 49 | Enrollment explanation | Face scan `enrollment_explanation_text` (2.9.1). |
| 50 | "Scan my face & eyes" button | `start_enrollment_button` on Face scan (2.9.1) and Setup ▸ Face scan (2.2.4). |
| 51 | Baseline summary, 6 lines | Face scan: three plain lines `enrollment_plain_summary` and the "Details" expander with the six exact lines (2.9.1). Also Developer ▸ Face scan. |
| 52 | Baseline warnings line | Face scan `enrollment_warnings_text` (2.9.1). |
| 53 | Enrollment privacy line | Face scan (2.9.1). |
| 54 | "Re-scan" button | `rescan_enrollment_button` "Scan again" (2.9.1, 2.2.4). |
| 55 | "Clear" enrollment, no confirmation | `clear_enrollment_button` "Remove scan", now behind `ConfirmDialog(ClearEnrollment)` (2.9.1). |
| 56 | Calibration overview title and subtitle | Calibrate overview: accuracy card and the plain explanation (2.8.1 items 1 and 2); Setup intro (2.2.5). |
| 57 | Calibration mode chips (quick / full) | Two buttons on the overview: `start_calibration_button` (full, the default) and `quick_calibration_button` (2.8.1 items 4 and 5). The mode is passed when the wizard starts. |
| 58 | Five-point preview map and sequence text | Developer ▸ Calibration (`five_point_preview_map`). |
| 59 | Active accuracy badges with honest sub-text | Calibrate overview accuracy card and `active_profile_name_text` (2.8.1); `StatusBlock` caption (2.0); Menu tracking card (2.4). Raw metrics: Developer ▸ Calibration. |
| 60 | Start calibration button | `start_calibration_button` (2.8.1, 2.2.5). |
| 61 | Live feature telemetry card | Developer ▸ Calibration (`calibration_eye_telemetry_text`, the `f_raw` line). |
| | **D. Calibration wizard** | |
| 62 | Target heading with coordinates | Wizard `active_calibration_target_label` "Dot 2 of 5" / "Check 1 of 4" (2.8.2). Coordinates: slot `DevWizardDetails`. |
| 63 | Sample counter (no face / settling / collecting) | Wizard `calibration_sample_counter_text`, same three states in plain words (2.8.2). Raw text: slot `DevWizardDetails`. |
| 64 | Five stepper pills | Wizard dots row `calibration_5_point_stepper` with done / current / waiting (2.8.2). Per-dot counts: slot `DevWizardDetails`. |
| 65 | "Capture Point" button | Wizard `capture_gaze_point_button` "Capture now", for every user (2.8.2). |
| 66 | "Lock Sim Here" button | Slot `DevWizardDetails` `snap_sim_to_target_button`, developer mode with the simulator on (2.8.2). |
| 67 | "Cancel" button | Wizard `cancel_calibration_button` (2.8.2); system Back (1.4). |
| 68 | Progress-map caption, miniature map and accessible capture nodes | The accessible nodes `calibration_point_target_<i>` live on the dots row, 56 dp, only the current one captures (2.8.2). **REMOVE in part**: the miniature canvas and its caption. Reason: it repeated the dots row and was mistaken for a target. The static preview map stays on Developer ▸ Calibration (#58). |
| 69 | Full-screen pulsing fixation target | Wizard `calibration_fullscreen_target_overlay`, larger, check dots marked by a square outline (2.8.2). |
| 70 | Automatic cancel when leaving mid-collection | `CalibrateContent` `DisposableEffect` (2.8); Back rule (1.4). |
| | **E. Calibration result** | |
| 71 | Summary title | Result `calibration_result_title`, five outcomes (2.8.3). Legacy report title: slot `DevCalibrationResultDetails`. |
| 72 | Result badges | Result card `calibration_result_body` (2.8.3). |
| 73 | In-sample caveat | Result `calibration_in_sample_caveat` (2.8.3). |
| 74 | Per-coordinate sample table | Slot `DevCalibrationResultDetails` (`calibration_dev_details`, 2.8.3). |
| 75 | Recommended layout line | Result `recommended_layout_text` (2.8.3). Raw label: slot `DevCalibrationResultDetails`. |
| 76 | Fitted matrix dump | Slot `DevCalibrationResultDetails` (2.8.3). |
| 77 | Profile name field | Result `profile_name_input`, default "Calibration <d MMM, HH:mm>" (2.8.3). |
| 78 | "Retry" and "Save & Apply Profile" | Result `recalibrate_button` "Try again" and `save_calibration_button` "Save and use"; new `discard_calibration_button` (2.8.3). |
| | **F. Face scan overlay** | |
| 79 | Phase label | Scan `enrollment_phase_label` (2.9.2). |
| 80 | Overall progress bar | Scan `enrollment_overall_progress` (2.9.2). |
| 81 | Guidance line | Scan `enrollment_guidance_text`, larger type (2.9.2). |
| 82 | Secondary line | Scan `enrollment_secondary_text` (2.9.2). |
| 83 | Live eye-openness readout and bar | Scan `enrollment_eye_open_label` "Eyes open" / "Eyes closed" with `enrollment_eye_open_bar` (2.9.2). The number: slot `DevScanDetails`. |
| 84 | Warning chips | Scan `enrollment_warning_chips` (2.9.2). |
| 85 | Fixation dot with phase ring | Scan `enrollment_fixation_dot` (2.9.2). |
| 86 | "Cancel scan" | Scan `enrollment_cancel_button` (2.9.2); system Back (1.4). |
| 87 | "Skip brow step" | Scan `enrollment_skip_brow_button` "Skip eyebrow step" (2.9.2). |
| 88 | Automatic cancel when leaving mid-scan | `EnrollmentScanOverlayHost` on `facescan` and `setup/scan` (2.9.2, 1.4). |
| 89 | Triggers suppressed during the scan | ViewModel, unchanged (7.4). The tiles are also not on screen during a scan. |
| | **G. Control Deck** | |
| 90 | Deck layout title ("not yet validated") | `StatusBlock` caption (2.0), help sheet `help_layout_text` (2.0), Menu tracking card "Tiles: …" (2.4). Raw `gridLayoutLabel`: Developer ▸ Calibration. |
| 91 | "Dwell: 1000ms (1.0×)" button and its dialog on the deck | **REMOVE** from the gaze screen. Reason: a settings entry beside the tiles invites stray touches and is one of three copies of the same settings. Its content lives in Settings (2.11) and Developer ▸ Engine and Benchmark (2.16.1). |
| 92 | Last spoken phrase readout | Speak spoken strip `aac_speech_output_text`, from `hud.lastSpoken` (2.3). Always visible; never a banner. |
| 93 | Trigger mode chips | Settings A "How a tile is selected", four `RadioRow`s (2.11). |
| 94 | Brow ratio readout | Developer ▸ Engine `brow_ratio_readout`. End users: `brow_gauge` without numbers (2.12). |
| 95 | "1. Neutral Brow" / "2. Raised Brow" | `BrowSetupScreen` `sample_neutral_brow_button`, `sample_raised_brow_button` (2.12). |
| 96 | Inline "Dwell Time" slider on the deck | **REMOVE.** Reason: one of three copies of one setting, with different behaviour. The single slider is Settings ▸ Speed ▸ Fine-tune `dwell_duration_slider` (2.11). |
| 97 | Tile grid, 4 or 9 tiles | Speak grid (2.3), sized by `SpeakGridLayout.resolve`. |
| 98 | Tile focus progress ring | The 6 dp progress bar on the focused tile (2.3). |
| 99 | Tap a tile to speak | Speak tile tap (2.3). Works without the camera. |
| 100 | Gaze hit test with hysteresis, suspended under dialogs, cleared on leaving | `ui/speak/DeckHitTest.kt` (2.3) with `LocalGazeSuspension` (1.7). Unchanged logic. |
| 101 | Tile centre feed for drift correction | Speak (2.3) `updateTileCentersNorm`. The counter: Developer ▸ Engine (#108). |
| 102 | Edit-tile button and dialog | Tiles screen `edit_aac_tile_<id>` with `TileEditDialog` (2.10); Setup ▸ Tile phrases (2.2.6). No edit control on the tile itself. |
| 103 | Default 9 tiles | 2.10 table: plain labels and phrases, same ids and special behaviours. |
| 104 | Gaze-trigger safety rules | ViewModel, unchanged (7.4). The UI adds the "No face" and "Paused / Eyes closed" statuses (2.0). |
| | **H. Dwell and tracking settings dialog** | |
| 105 | Dialog title and intro ("… for Google Pixel 10 Pro XL") | **REMOVE.** Reason: the dialog is gone and its intro was a device claim. |
| 106 | Posture profile chips | Developer ▸ Engine `posture_chip_<name>`. |
| 107 | Gaze filter chips | Developer ▸ Engine `filter_mode_chip_<name>`. |
| 108 | Drift-correction counter | Developer ▸ Engine. |
| 109 | Speed preset rows | Settings B `dwell_preset_<NAME>` (2.11). Selected only when hold time and margin both match. |
| 110 | Hold-time slider and label | Settings B ▸ Fine-tune `dwell_duration_slider`, `dwell_duration_label` (2.11). |
| 111 | Sensitivity slider and label | Settings B ▸ Fine-tune `dwell_sensitivity_slider`, `dwell_sensitivity_label` (2.11). The "1.00× (14 dp)" readout: Developer ▸ Engine. |
| 112 | Synthetic replay benchmark | Developer ▸ Benchmark (`run_benchmark_button`, `benchmark_summary_text`). |
| 113 | "Apply & Close" | **REMOVE.** Reason: there is no dialog. Settings apply at once and Back leaves the screen. |
| | **I. Attention Lab** | |
| 114 | Header title | Read top bar "Read" (2.5); Replay top bar shows the reading's title (2.6). The lab title is dropped as jargon. |
| 115 | Header subtitle (recording / replay stats / idle text) | Recording: elapsed time in `stop_attention_session_button` (2.5), `RecordingChip` on Speak (2.3), Menu row (2.4). Replay: three `StatTile`s (2.6). Blink split and cluster count: slots `DevRecordingDetails`, `DevSessionDetails`. Idle text: Menu row supporting text. |
| 116 | "Record" | Read `start_attention_session_button` "Start reading" (2.5). |
| 117 | "Stop & Save" | Read `stop_attention_session_button` "Finish and save · m:ss", then the replay opens (2.5). Also `speak_stop_recording_chip` on Speak (2.3). |
| 118 | "Exit Replay" | Replay `close_session_replay_button` "Done" (2.6). |
| 119 | Thermal guard text | `THERMAL_GUARD` banner while recording (3.5). Guard state: slot `DevRecordingDetails`. |
| 120 | Reading passage, 3 sections | Read passage with three everyday texts, same ids (2.5). |
| 121 | Per-section dwell label and bar | `section_dwell_label_<id>` on Read after a session and on Replay (2.5, 2.6). |
| 122 | Looked-at section highlight | Accent bar plus the "Reading" tag `section_reading_tag_<id>` (2.5). |
| 123 | Section hit-test feed | Read (2.5), unchanged. |
| 124 | Passage-local gaze point feed | Read (2.5), unchanged. |
| 125 | Fixation heatmap | Replay `attention_heatmap_canvas` behind `replay_heatmap_switch` (2.6). |
| 126 | Recording continues on other screens; layout detached on leaving | 1.4 and 2.5, unchanged. Now visible through `RecordingChip` (2.3) and the Menu row (2.4). |
| | **J. Cloud & Privacy** | |
| 127 | Dwell settings card (third copy) | **REMOVE.** Reason: duplicate. Settings (2.11) is the only home. |
| 128 | Cloud sync card with switch | Privacy cloud backup card `cloud_sync_toggle` (2.14); Setup ▸ Backup (2.2.7). |
| 129 | Account panel (name, email, UID, paths, database id) | Privacy account card: name and email (2.14). UID, paths, database id and privacy mode: Developer ▸ Account & storage. |
| 130 | Last cloud deletion summary | Privacy `cloud_deletion_status_text` in plain words (2.14). Raw summary: Developer ▸ Account & storage. |
| 131 | "Delete Cloud Data (Firestore)" | Privacy `delete_firestore_data_button` "Delete cloud copies", enabled whenever no deletion is running (2.14). |
| 132 | "Wipe Cloud & Local Vault" | Privacy `erase_all_cloud_button` "Erase everything" (2.14); Consent `consent_erase_button` (2.2.2). |
| 133 | "Sign Out" | Privacy `sign_out_button` (2.14); Welcome `setup_sign_out_button` (2.2.1); Consent `consent_sign_out_button` (2.2.2). All confirm. |
| 134 | Saved calibration profiles list | Calibrate overview "Saved calibrations" (2.8.1 item 7), linked from Privacy (2.14). |
| 135 | Activate a profile | Row tap in "Saved calibrations" (2.8.1). |
| 136 | Delete a profile | `delete_profile_<id>` with `ConfirmDialog(DeleteProfile)` (2.8.1). |
| 137 | Recorded sessions list with the lock text | `ReadingHistoryScreen` (2.7), linked from Read and Privacy. |
| 138 | Open a session replay | Row tap → `read/replay` (2.7, 2.6). Locked while recording. Older readings: heatmap switch disabled. |
| 139 | Delete a session | `delete_session_<id>` on history rows and in the Replay top bar, with `ConfirmDialog(DeleteSession)` (2.7, 2.6). |
| 140 | Destructive-action confirm dialog (4 variants) | `ConfirmDialog` (2.0), one dialog and one tag set for 13 variants. |
| | **K. Behaviours shown only through banners or implicitly** | |
| 141 | Simulator honesty (labelled, never synced, scan refuses) | ViewModel, unchanged. UI: status "Simulator / Not live" (2.0), diamond reticle (4.3), "Simulator · " on the spoken strip (2.3), "SIMULATED" badges (2.6, 2.7, 2.8.1, 2.8.3), "Simulated. Not a real measurement." (2.2.8), ViewModel guards (1.6). |
| 142 | Performance mode and resolution step-down | ViewModel, unchanged. `PHONE_BUSY` banner outside a recording, `THERMAL_GUARD` during one (3.5); help sheet. Badges: Developer ▸ Live status. |
| 143 | Frame watchdog ("NO FRAMES") | Status "Starting" then "No picture" (2.0); `NO_PICTURE` banner after 5 s (3.5). Raw label: Developer ▸ Live status. |
| 144 | Recenter flow | The Recenter tile (2.10, 2.3) and `RecenterScreen` (2.13). Results: `RECENTER_DONE`, `RECENTER_FAILED` (3.7). |
| 145 | Settings persistence per user | Unchanged. Added: setup state per user and the device-wide developer flag (7.5). Settings dump: Developer ▸ Account & storage. |
| 146 | Lists merge cloud and local data, with Loading and Error states | Past readings (2.7) and Saved calibrations (2.8.1) through `InlineNotice` states. |
| 147 | Enrollment applied at launch | ViewModel, unchanged. Date: Menu tracking card (2.4). `hud.enrolledAtMs`: Developer ▸ Face scan. |

---

## 7. ViewModel changes

The redesign is presentation. `EyeTrackingViewModel` keeps its pipeline, its rules and every public signature. The
list below is everything it gains. Nothing else may change without a note in this section.

### 7.1 New types and read-only state

New file `ui/viewmodel/UiContracts.kt`:

```kotlin
enum class GazeInputState { NO_CAMERA_PERMISSION, NO_FRAMES, SIMULATED, NO_FACE, EYES_CLOSED, TRACKING }
data class SelectionTimings(val naturalBlinkMinMs: Long = 80, val commandBlinkMinMs: Long = 350,
                            val commandBlinkMaxMs: Long = 800, val browHoldMs: Long = 400)
enum class SpokenMethod { HELD, BLINK, EYEBROWS, TAPPED, HELPER }
data class LastSpoken(val tileId: String, val phrase: String, val method: SpokenMethod,
                      val atMs: Long, val isRecenter: Boolean)
enum class NoticeKey { DEV_LOG, /* one constant per key of 3.7 */ }
data class UserNotice(val key: NoticeKey, val args: List<String>, val technicalText: String, val atMs: Long)
const val SIMULATED_RECORD_PREFIX = "[SIMULATED] "      // today a private constant of the ViewModel
```

`data class SetupState(consentAcceptedAtMs: Long? = null, completedAtMs: Long? = null, skipped: Boolean = false,
backupChosenAtMs: Long? = null)` goes into `data/model/GazeModels.kt`.

| Member | What it is | Filled from | Needed by |
|---|---|---|---|
| `hud.gazeInputState` | One enum instead of four separate checks. Default `NO_FRAMES`. | Values the ViewModel already has, first match: simulated frame or emulator → `SIMULATED`; permission flag false → `NO_CAMERA_PERMISSION`; watchdog has zeroed the frame rate (**#143**) → `NO_FRAMES`; no face → `NO_FACE`; eyelids closing or closed → `EYES_CLOSED`; otherwise `TRACKING`. | `StatusBlock` (2.0), reticle (4.2), condition notices (3.5), Developer ▸ Live status |
| `hud.hasNoFrontCamera` | Boolean, default false | `setFrontCameraAvailable(false)` from `CameraPipelineHost` | 2.0, 2.2.3, 3.5 |
| `hud.lastSpoken` | `LastSpoken?`, default null | Set in `fireTileAction` beside the legacy `lastTriggeredSpeech` string, which stays | Spoken strip and Fired tile state (2.3) |
| `hud.selectionTimings` | `SelectionTimings` | The live detector values (`naturalMinMs`, `intentionalMinMs`, `maxCommandMs`, brow `holdTargetMs`); refreshed when a face scan or `setBlinkTimingThreshold` changes them | `selectionTimingSentence` on Consent, Settings and About (2.0) |
| `cal.usedSimulatedFrames` | Boolean | Mirrors the private `calibrationUsedSimulatedFrames` | "SIMULATED" badge on the result card (2.8.3) |
| `VM.setupState` | `StateFlow<SetupState>` | The store (7.5) | Start destination and setup steps (1.2, 2.2) |
| `VM.developerModeEnabled` | `StateFlow<Boolean>` | The store, device-wide | 1.6, 2.15, 2.16 |
| `VM.devStripEnabled` | `StateFlow<Boolean>` | The store, device-wide | `dev_hud_overlay_switch` (2.16.1), reticle FULL mode (4.2) |
| `VM.notices` | `SharedFlow<UserNotice>`, no replay | `postNotice` | Banner queue (3.4), inline readers (3.6) |
| `VM.noticeHistory` | `StateFlow<List<UserNotice>>`, newest first, 50 entries | `postNotice` | Developer ▸ Message log (2.16.1), Privacy deletion status (2.14) |

A simulated calibration or reading is recognized in the UI by `name.startsWith(SIMULATED_RECORD_PREFIX)`; the badge
is shown and the prefix is stripped from the visible name. No stored field changes.

### 7.2 New functions

| Function | What it does | Called from |
|---|---|---|
| `acceptConsent()` | Records `consentAcceptedAtMs`, persists. | Consent (2.2.2) |
| `markBackupChosen()` | Records `backupChosenAtMs`, persists. | Setup ▸ Backup (2.2.7) |
| `markSetupComplete(skipped: Boolean = false)` | Records `completedAtMs` and `skipped`, persists. | Ready (2.2.8), Skip setup (2.2) |
| `restartSetup()` | Clears `completedAtMs` only. Consent, the face scan and calibrations stay. | Menu (2.4) |
| `setDeveloperModeEnabled(enabled: Boolean)` | Persists the device-wide flag. Turning it off on hardware also turns the simulator off, sets the overlay mode to `RETICLE_ONLY` and turns the developer strip off. | About (2.15), Developer (2.16.1) |
| `setDevStripEnabled(enabled: Boolean)` | Persists the device-wide flag. | Developer ▸ Live status |
| `setCameraPermissionGranted(granted: Boolean)` | Stores the flag used by `gazeInputState`. Never touches the simulator. | `MainShell` (1.7) |
| `setFrontCameraAvailable(available: Boolean)` | Sets `hud.hasNoFrontCamera`. | `CameraPipelineHost` |
| `resetAacTilesToDefaults()` | Restores `defaultAacTiles`, persists, posts `TILES_RESET`. | Tiles (2.10) |
| `private postNotice(technical: String, key: NoticeKey = DEV_LOG, vararg args: String)` | Sets `_statusBannerMessage.value = technical` exactly as today, appends to `noticeHistory`, and emits on `notices` unless the key is `DEV_LOG`. | Replaces each of the about 50 `_statusBannerMessage.value = …` lines, with the key from 3.7. The sentences do not change. |

`setBlinkTimingThreshold` and `setIncludeHeldOutValidation` are unchanged. The first gains its first caller
(Developer ▸ Engine). The second loses its caller, because the wizard is always started with an explicit mode.

### 7.3 Small edits to existing code

| # | Edit | Why |
|---|---|---|
| 1 | `toggleSimulatorProxyMode(true)`, `cycleOverlayVisualMode()`, `simulateBrowRaiseCommand()` and `simulateIntentionalCommandBlink()` return without effect unless `developerModeEnabled` is true or the device is an emulator. | Simulator isolation must not depend on a hidden control (1.6). |
| 2 | `toggleSimulatorProxyMode` is ignored while a reading is recorded, the wizard collects or a scan runs. | Live and simulated data never mix inside one measurement. The switch is disabled on screen for the same reason (2.16.1). |
| 3 | With developer mode off on hardware, `overlayVisualMode` is `RETICLE_ONLY` from start-up. | End users never get the heat trail (section 4). |
| 4 | `eraseAllCloudTelemetry()` also clears the setup state. The developer flags are not touched. | "Erase everything" returns to first-time setup, as its dialog says (1.5). |
| 5 | Default names: `draftProfileName` becomes "Calibration <d MMM, HH:mm>" when the result is reached; the default reading title becomes "Reading <d MMM, HH:mm>"; `CalibrationProfile.name` defaults to "Calibration". The device-model fallback "Pixel 10 Pro XL" becomes "Unknown device" in `LiveGazeHudState`, `CalibrationProfile` and `TrackingSession`. | No device claims (inventory defect 8). |
| 6 | `CalibrationWizardState.includeHeldOutValidation` defaults to true. | The full calibration is the default everywhere (2.8.1). |
| 7 | `defaultAacTiles`: new labels and phrases from the table in 2.10. Ids, categories, order and the special behaviour of `tile_recenter` and `tile_read_aloud` are unchanged. The stored default phrase of `tile_read_aloud` is "Read back what I looked at."; while unedited the tile still speaks the generated summary. | Plain language (**#103**). |
| 8 | `buildReadingSummaryPhrase()` returns "I looked most at “<heading>”, <n> percent of the time." or "I have not read anything yet." | Plain language. |
| 9 | `readingPassageSections`: the three texts of 2.5, ids `sec_1` to `sec_3` unchanged. | End-user reading material (**#120**). |
| 10 | `ATTENTION_OFF_PASSAGE_SECTION_ID` moves from `ui/screens` into `ui/viewmodel`. | The ViewModel must not import a screen file that is being deleted. |
| 11 | `fireTileAction` maps its method string to `SpokenMethod` ("Dwell (…)" → `HELD`, "Intentional Blink" → `BLINK`, "Brow Raise" → `EYEBROWS`, "Tap" → `TAPPED`, "Helper" → `HELPER`). | The spoken strip shows a method word without parsing text (2.3). |
| 12 | The constructor gains one optional parameter, `content: AppContent = AppContent.Default`, holding the default tiles, the three passages and the two read-back sentences. The shell's factory fills it from string resources. | The ViewModel cannot read resources, and this copy must be translatable (9.2). Existing tests use the default and need no change. |

Effect on the ViewModel tests: `statusBannerMessage` still carries the same sentences, so the assertions on banner
text in `VmRegressionTest`, `VmOptimizationTest` and `VmEnrollmentTest` stay as they are. Tests that drive the
simulator add one set-up line, `setDeveloperModeEnabled(true)`. Tests that pin the old default tile titles, the old
passages or the old default names are updated to the new copy.

### 7.4 What does not change

* The frame pipeline, filters, posture bias, drift correction and the performance-mode step-down (**#142**).
* **Trigger safety (#104).** Dwell accumulates only with a face detected and the eyes open. It resets after 900 ms
  of invalid gaze or a 500 ms frame gap. Natural blinks (80–349 ms) never fire. A command blink is 350–800 ms. A
  longer closure cancels. A brow raise needs a 400 ms hold. The same tile cannot fire again for 1.5 s.
* **Off-screen tiles never fire.** The deck reports only visible tiles; `fireTileAction` ignores unknown ids.
* Triggers are suppressed during the face scan (**#89**). The scan refuses simulated frames (**#141**).
* Calibration maths, the held-out check and the 9-tile classifier `shouldUseValidated3x3Grid` (typical error at most
  12 %, worst area at most 16.5 %). No new accuracy thresholds are introduced anywhere in the UI.
* Simulated results are saved with the "[SIMULATED]" prefix and the device "Simulator", and are never synced.
* Cloud sync stays opt-in. `deleteFirestoreCloudTelemetry()` and `eraseAllCloudTelemetry()` keep their behaviour
  (apart from edit 4) and have no precondition on the backup switch.
* Profile and session lists, activation, deletion, merging of cloud and local data (**#146**), settings persistence
  (**#145**), the face scan applied at launch (**#147**).
* `statusBannerMessage`, `dismissStatusBanner()` and every other public member listed in the inventory.

### 7.5 Storage (`LocalTelemetryStore`, `GazeModels.kt`)

| Addition | Detail |
|---|---|
| `loadSetupState(userId)` / `saveSetupState(userId, state)` | Key `setup_state_<uid>`, JSON, per account. |
| `isDeveloperModeEnabled()` / `setDeveloperModeEnabled(Boolean)` | Key `developer_mode_enabled`, device-wide. |
| `isDevStripEnabled()` / `setDevStripEnabled(Boolean)` | Key `developer_strip_enabled`, device-wide. |
| `clearAllLocalData(userId)` | Also removes `setup_state_<uid>`. Leaves both developer keys. |
| `loadCustomAacTiles(...)` | One-time upgrade: a stored tile whose label and phrase both equal the **old** default for its id is replaced by the new default. Edited tiles are never touched. |

The new keys live in the same preferences file as the vault, which backup and device-transfer rules already exclude
(pinned by `ActivityRegressionTest`). Existing accounts need no migration code: they have no setup state, so setup
runs once with the "Keep and continue" options (1.2).

### 7.6 Deliberately not in the ViewModel

| Concern | Where it lives |
|---|---|
| Speech preview ("Hear it", "Test the voice") | `LocalSpeechPreview`, the shell's text-to-speech engine (1.7) |
| Banner queue, timers, quiet periods, condition notices | `BannerQueue`, `conditionNotices` (3.4, 3.5), pure, in `ui/components/` |
| Status wording and its 900 ms / 800 ms grace | `trackingStatus(...)` (2.0) |
| Reticle mode and clipping | `reticleMode(...)` (4.2) |
| Tile count and tile size | `SpeakGridLayout.resolve` (2.3) |
| Start destination and next setup step | `resolveStartDestination`, `nextSetupStep` (1.2) |
| Wizard band placement | `wizardBandPlacement` (2.8.2) |
| Camera binding, preview attach and detach, time since binding, the permission launcher | `CameraPipelineHost`, `MainShell` (1.7) |

---

## 8. Test plan

All UI tests stay Robolectric with `createComposeRule` (sdk 36), as today. "Owner" refers to the engineers of 9.1.

### 8.1 Existing test classes

| Class | Owner | What happens | Behaviours it must still pin |
|---|---|---|---|
| `enrollment/UiEnrollmentTest` | E4 | Rewritten against `FaceScanContent` and the restyled `EnrollmentScanOverlay`. | No baseline: status "Not done", the explanation mentions "15-second scan" and "No images are stored", only the start button exists and fires once. With a baseline: status "Done on …" with the year, the six exact summary lines (inside "Details"), "Warnings: Low light, Few natural blinks", rescan starts a scan. Failed scan shows "The scan did not finish: Eye closure was not detected". A running scan shows "Scanning…" and disables the buttons. Overlay: the exact guidance and phase strings, warning chip texts, the secondary line, the fixation-dot descriptions, skip only in the eyebrow phase, each callback fires once. The overlay composes nothing outside the active phases. The host cancels when it leaves composition mid-scan, not on completion. The helper tests (distinct texts, summaries never invent a measurement, `Locale.US`) are unchanged. **Changed on purpose:** "Remove scan" opens `ConfirmDialog(ClearEnrollment)` and only Confirm calls the callback; "Eyes open" / "Eyes closed" replace "Eye openness 0.87" (the number is asserted with developer mode on); the overlay is now opaque, so the old "touches pass through" case becomes "a touch outside the buttons activates nothing". |
| `regression/DeckRegressionTest` | E3 | Rewritten against `SpeakScreen`, `TilesScreen` and `TileEditDialog`. | Leaving Speak clears the hovered tile. An open dialog or sheet (the help sheet, the Leave dialog) suspends gaze focus, and hit testing resumes on close. Tiles that are not in the grid are absent and cannot be focused. Hysteresis is 14 dp on the device density. No measured accuracy → 4 tiles; qualifying → 9; not qualifying → 4; the result equals `shouldUseValidated3x3Grid`. A long spoken phrase does not move the grid. Tile centres are normalized to the gaze frame, 4 or 9 ids are reported and cleared on leaving. Save in the tile editor needs a label and a phrase and delivers the edited values. **Moved:** the slider and preset cases to `SettingsScreenTest`; the benchmark summary and the brow-ratio `Locale.US` cases to `DeveloperScreenTest`, with the same assertions. The deck slider case goes with #96. |
| `regression/ActivityRegressionTest` | E2 | Manifest, backup-rule, haptic and ViewModel-scope tests unchanged. The inline cloud bar cases move to `PrivacyScreenTest` (E6). | "Delete cloud copies" deletes only after the confirmation is accepted. Cancel deletes nothing. |
| `regression/AuthRegressionTest` | E2 | Sign-in and sign-out logic tests unchanged (imports from `ui/auth/GoogleSignIn.kt`). `AuthScreenCopyRegressionTest` is replaced by `SelectionTimingCopyTest` (E1). | The copy shown to users states the detector's real command-blink window: `selectionTimingSentence` built from the live detector contains "<intentionalMinMs>–<maxCommandMs> ms", and that sentence is on Consent and in Settings. |
| `regression/CalibscreenRegressionTest` | E4 | Rewritten against `CalibrateContent` (overview, wizard, result). | Capture never moves the simulator unless it is already on. Only the current dot node captures. Leaving mid-wizard cancels once; step changes and reaching the result do not. The counter reports "Face not found" instead of endless settling. Uncalibrated shows "Not calibrated" and none of the invented numbers ("±10.9", "±14.2", "13.4 mm"); a checked state shows its real numbers. A fit without a check shows "Not checked", never "Not calibrated". A quick fit is labelled as a fit with the caveat; a checked result has no caveat. Unmeasured shows "Not measured", never "NaN". The full-screen target is drawn in its own frame, exists only while collecting and lets touches through. **Added:** Save is disabled for "Not measured"; the five result outcomes and their layout sentences. **Moved:** the no-face telemetry dashes case to `DeveloperScreenTest`. |
| `regression/AnalyticsprivacyRegressionTest` | E5 | Split. Reading helpers and Read cases stay in this file. Privacy cases move to `PrivacyScreenTest` (E6), profile cases to `CalibrateScreenTest` (E4), slider helpers to `SettingsScreenTest` (E6). | Hit report: null versus off-passage. Passage-local point with scroll. Thermal guard only while recording. An empty session never shows a 0 pace or 0 % zones; "Not measured" where nothing was measured. Section hits and passage points are reported and reset on leaving. A replay cannot be opened while recording and the lock text is shown. Erase and cloud delete run only after confirmation. Deleting a calibration asks first. Sign-out is disabled while a deletion runs. `cloud_sync_toggle` carries the `cloud_telemetry_opt_in_title` description. `formatProfileErrorLine` still says "held-out validation" or "in-sample fit (unvalidated)" (now in the developer slot). |
| `optimization/ActivityOptimizationTest` | E2 | Predicate, `ReadInOwnScope`, auth-flow and helper tests unchanged. `FROZEN_USED_STRINGS` is regenerated from all `values/strings*.xml` files. | Every string that code references exists with its frozen value, and no unreferenced string remains. Label, theme, launcher entry and permissions of `MainActivity` are unchanged. |
| `optimization/AuthOptimizationTest` | E2 | Flow tests unchanged. The frozen semantics tree of the old screen is replaced by a role-and-label test of `SignInScreen`. | Sign-in flow parity for every credential outcome. The screen exposes `auth_title`, `auth_subtitle`, `google_sign_in_button` (role Button, disabled with "Signing in" while loading) and `sign_in_error_text` at font scales 1.0, 1.3 and 2.0. |
| `optimization/CalibscreenOptimizationTest` | E6 | Formatter tests unchanged (formatters move to `ui/developer/CalibrationDevFormatters.kt`). UI parity cases retargeted to the Developer screen and the developer slots. | The telemetry line follows every HUD frame. The wizard slot shows heading, counter and per-dot counts as the frozen formatters produce them. The result slot is stable across keystrokes and matrix changes. |
| `optimization/AnalyticsprivacyOptimizationTest` | E5 | Formatter and cache tests kept; heatmap geometry unchanged, brush expectations updated to the one-hue ramp (5.1). Compose cases retargeted to Read, Replay and Past readings; the privacy case moves to `PrivacyScreenTest`, the profile-row case to `CalibrateScreenTest`. | Read follows per-frame state without recomposing the passage. Hit reports do not change when equal bounds are delivered again. The heatmap node exists exactly while there are clusters and the switch is on. Keyed rows keep the right text and the right delete target when lists shift, duplicates included. |
| `regression/DiagRegressionTest` | E1 | Assertions unchanged; imports follow the moved helpers. | "NO FRAMES" wording, `Locale.US`, no hardware claims, synthetic frames flagged, warning order with low light first, the latch ignoring no-face frames, reticle radii per axis and wide when not checked. |
| `regression/BuildthemeRegressionTest` | E1 | Updated to the tokens of section 5. | Fonts declared at Regular only. Surface roles use the surface steps. Unchecked switch handle and border at least 3 : 1. **Added:** every `Typography` role is at least 16 sp; tile styles at least 24 sp; `secondary` is neutral; the contrast figures of 5.1. |
| `VmRegressionTest`, `VmOptimizationTest`, `VmEnrollmentTest` | E2 | Banner-text assertions unchanged. Simulator tests add `setDeveloperModeEnabled(true)`. Default tile, passage and name expectations updated (7.3). | All trigger-safety, enrollment, calibration, simulator-labelling and cloud behaviour of the ViewModel. |
| `ModelsstoreRegressionTest`, `ModelsstoreEnrollmentTest` | E2 | Extended. | Existing store behaviour, plus: setup state round trip, removal by `clearAllLocalData`, developer flags device-wide and untouched by erase, the one-time tile upgrade never touches an edited tile. |
| `ExampleRobolectricTest`, domain, engine, maths, repository, cloud-rule and benchmark tests | none | Unchanged. | |

### 8.2 New tests

| Class | Owner | Pins |
|---|---|---|
| `BannerQueueTest` (pure) | E1 | One at a time; 1.5 s minimum; priority replacement; same-key update; fate of replaced banners; queue sizes and waiting limits; the "two conditions, then one result" rule; quiet periods (5 min, 15 min, once per process); pause and resume; every banner ends by itself. |
| `NoticePolicyTest` (pure) | E1 | Each row of 3.5 from its state; tracking-quality order and debounce; thermal guard only while recording; `PHONE_BUSY` never while recording; scope by screen (3.4 rule 9); the owner rule (3.6). |
| `BannerCopyFitTest`, `BannerLaneComposeTest` | E1 | No headline is cut off at 360 and 411 dp, font scales 1.0, 1.3 and 1.5. The lane keeps its height when empty. Body and dismiss are at least 56 dp. Live-region levels. Kind word in the description. |
| `NoticeMappingTest` | E2 | Every `postNotice` key has a `NoticeSpec` row and, unless `DEV_LOG`, string resources. `postNotice` writes the legacy flow, the history (50 entries) and the channel. |
| `StatusBlockTest` (pure and compose) | E1 | The table of 2.0 row by row; "Simulator" is never success-coloured and always wins; "Starting" only within 4 s; the 900 ms and 800 ms grace; no digits in any status; the warning mark appears while a condition is active and not shown as a banner. |
| `ReticlePolicyTest` | E1 | The mode table and hide conditions of 4.2; diamond shapes for simulated gaze; clipping to the reported bounds; no arc, ticks, pill or trail outside FULL. |
| `SelectionTimingCopyTest` | E1 | The sentence is built from `SelectionTimings`; default text contains "350–800 ms", "80–349 ms" and "400 ms". |
| `ConfirmDialogTest` | E1 | All 13 variants: title, body, confirm and cancel labels, the kept tags; nothing runs before Confirm. |
| `AppRoutesTest` (pure), `NavigationTest` | E2 | `resolveStartDestination` and `nextSetupStep` for every combination; the Back table of 1.4, including the Leave dialog on Speak and wizard cancel; `developer` is not a route while developer mode is off; revoked permission stays on Speak. |
| `SetupFlowTest` | E2 | Camera never binds before consent. Both boxes are needed. Sign out and "Erase my data" work before consent. Skip setup is absent on step 1. Every step can be passed without a camera. Existing accounts see "Keep and continue". Ready reflects done, skipped and simulated states. Backup stays off unless chosen. |
| `CameraPipelineHostTest` | E2 | Analysis binds without a preview. The preview attaches and detaches without rebinding analysis and never during a scan, wizard or recording. Unbinds while the simulator is on. Never binds on an emulator or before consent. Reports a missing front camera. |
| `VmContractTest` | E2 | `gazeInputState` for each input; `selectionTimings` equals the detectors; `lastSpoken` for dwell, blink, brow, tap and helper; the simulator guards (7.3 edits 1 and 2); erase clears setup state; `resetAacTilesToDefaults`. |
| `SpeakGridLayoutTest` (pure), `SpeakScreenTest` | E3 | The 96 dp rule and the 4-tile fallback on small, landscape and large-font frames; the grid never scrolls; tile bounds are identical before and after a banner, a long phrase and the developer strip; one semantics node per tile with "Says:"; no long-press action; focus border and progress value; Fired state for 600 ms; strip default, method words, "Simulator · " prefix, "shown, not spoken"; recording chip stops and saves; tiles stay tappable with the camera off. |
| `TilesScreenTest`, `RecenterScreenTest` | E3 | Rows 1 to 4 carry "In 4-tile layout"; reset asks first; "Hear it" uses the preview speaker and fires no tile; discard asks when a field changed. Recenter: countdown, capture call, inline result, disabled without a camera. |
| `WizardBandTest` (pure) | E4 | For all 9 targets on the six frame sizes of 2.8.2 the band never intersects the 72 dp circle around the target; the band moves only between dots. |
| `CalibrateScreenTest`, `FaceScanScreenTest`, `BrowSetupScreenTest` | E4 | Full calibration is the default and the quick button passes `false`; "In use" is text and icon; activating and deleting rows; discard dialog; simulated badge and wording; scan disabled with the notice on simulator or no camera; positioning preview only in the first phase; gauge words without decimals; step results inline, never as banners. |
| `ReadScreenTest`, `ReadingReplayScreenTest`, `ReadingHistoryScreenTest` | E5 | "Reading" tag plus bar while recording; no percentages while recording; finish opens the replay; three stat tiles with "Not measured" rules; heatmap switch disabled for older readings; "SIMULATED" badge and notice; rows locked while recording; delete asks first; "Looked mostly at…" from the zone values. |
| `SettingsScreenTest`, `PrivacyScreenTest`, `AboutScreenTest` | E6 | Trigger rows match the enum; a preset is selected only when both values match, otherwise the "Custom" line; these are the only dwell sliders in the app; range 400–2200 and 1900 reachable. "Delete cloud copies" is enabled with backup off; all destructive paths confirm; no UID or path text. Seven taps and a confirmation turn developer mode on; turning it off forces the simulator off on hardware. |
| `DeveloperScreenTest`, `DevInlineTest` | E6 | Every legacy telemetry tag is present; the benchmark and brow-ratio assertions moved from the deck test; the simulator card is labelled and its switch is locked on an emulator and disabled during a measurement; slots render nothing while developer mode is off. |
| `HonestyCopyTest` | E1 (harness), all | For a matrix of fake states on every screen: "Not calibrated", "Not checked", "Accuracy limited", "Not measured" and "Simulator" appear for their states; no number is shown without a measurement; "SIMULATED" marks every simulated row; no "Pixel", "Firestore", "UID" or path text outside `ui/developer/`. |
| `AccessibilityAuditTest` | E1 (harness), all | On every screen: each clickable node is at least 56 × 56 dp (tiles 96), has a label and a role, and has no long-press-only action; no end-user text is under 16 sp; selected, focused and warning states have a non-colour cue (icon, text or stateDescription). |
| `SourceScanTest` | E1 | `OculaDevType` and `jetbrains_mono` only under `ui/developer/`. No `AnimatedVisibility` or `animateContentSize` under `ui/speak/` and `ui/reading/`. No hard-coded English sentences in end-user packages (strings come from resources). No new file imports `ui.screens`. |

### 8.3 Where each non-negotiable rule is pinned

| Rule | Tests |
|---|---|
| Honest states; nothing default, simulated or clamped shown as measured; 4 tiles until a checked calibration qualifies | `HonestyCopyTest`, `StatusBlockTest`, `CalibrateScreenTest`, `DeckRegressionTest`, `SpeakGridLayoutTest`, `SetupFlowTest` |
| No device claims | `HonestyCopyTest`, `DiagRegressionTest` |
| Simulator never mixes with live data and is always labelled; controls only in developer mode | `VmContractTest`, `VmRegressionTest`, `DeveloperScreenTest`, `NavigationTest`, `StatusBlockTest`, `ReticlePolicyTest` |
| Data stays on the phone unless backup is on; destructive actions confirm; sign-out, cloud delete and erase stay reachable | `PrivacyScreenTest`, `SetupFlowTest`, `ConfirmDialogTest`, `ActivityRegressionTest` (backup rules), repository rule tests |
| Triggers pause without a face or with closed eyes; natural blinks never fire; 350–800 ms and 400 ms; off-screen tiles never fire | ViewModel tests (unchanged), `DeckRegressionTest`, `SpeakScreenTest`, `SelectionTimingCopyTest` |
| TalkBack labels, Switch Access, nothing by colour alone | `AccessibilityAuditTest`, plus the manual pass below |
| Nothing moves under the pointer | `SpeakScreenTest`, `BannerLaneComposeTest`, `SourceScanTest` |
| Every capability has a home | The row count of section 6 (script), `DeveloperScreenTest` for the telemetry tags |

### 8.4 Manual checks on a phone before release

Automated tests cannot cover these. Each is done once per release candidate, on hardware.

1. TalkBack: complete setup, speak a tile by touch, change a phrase, erase everything.
2. Switch Access (two switches): reach and activate every control on Speak, Menu, Calibrate and Privacy.
3. Gaze, with and without glasses: dwell, long blink and eyebrow selection on 4 and 9 tiles; a banner appearing
   during a dwell does not move or cancel it.
4. Font scale 1.3 and 2.0, and landscape: no clipped labels, no sideways scrolling, the calibration band never under
   a dot.
5. Camera: deny, deny permanently, grant in phone settings and return; the preview on Setup ▸ Camera and in the scan
   attaches and detaches without a visible pause in tracking.
6. Phone without a text-to-speech voice: "Speech is off", tiles still show their words.

---

## 9. Implementation plan

Paths are relative to `app/src/main/java/com/example/` unless they start with `res/` or `app/`. Tests are under
`app/src/test/java/com/example/`.

### 9.1 File ownership for six engineers

No file appears in two lists. The eight legacy UI files are **read-only for everyone** until the last step (9.3):
new code copies the helpers it needs into its own package, and nobody edits the old files.

**E1. Theme, tokens and shared components**

| | Files |
|---|---|
| Creates | `ui/theme/Tokens.kt`, `ui/theme/DevType.kt`. In `ui/components/`: `TopBar.kt`, `BigButton.kt`, `SettingRow.kt` (with `RadioRow`), `InlineNotice.kt`, `StatTile.kt`, `ConfirmDialog.kt`, `StatusBlock.kt` (with `trackingStatus`), `TrackingHelpSheet.kt`, `Banner.kt` (`Banner`, `BannerLane`), `BannerQueue.kt`, `NoticePolicy.kt` (`NoticeSpec`, `conditionNotices`, `noticeCopy`), `GazeReticle.kt`, `ReticlePolicy.kt`, `SelectionTimingCopy.kt`, `PrivacyNoteSheet.kt`, `SimulatedRecord.kt` (`isSimulatedRecord`, `displayRecordName`), `DevOnly.kt`, `Locals.kt` (`LocalBannerQueue`, `LocalSpeechPreview`, `LocalDeveloperMode`, `LocalGazeSuspension`), `TrackingTelemetry.kt` (the pure helpers of the old diagnostics bar: `analyzerStatusLabel`, `cameraPipelineLabel`, `gazeTelemetryLine`, `eyeOpenBrowLine`, `browRaiseTriggerThreshold`, `glareWarningSentence`, `lowLightWarningSentence`, `opticalWarningText`, `OpticalWarningLatch`, `rememberDebouncedFlag`). Test support: `testsupport/ScreenAudit.kt`. |
| Edits | `ui/theme/Color.kt`, `ui/theme/Theme.kt`, `ui/theme/Type.kt`, `res/values/strings.xml` (the existing entries and all shared strings: common words, status, help sheet, confirm dialogs, notices). |
| Rewrites tests | `regression/DiagRegressionTest`, `regression/BuildthemeRegressionTest`. |
| New tests | `BannerQueueTest`, `NoticePolicyTest`, `BannerCopyFitTest`, `BannerLaneComposeTest`, `StatusBlockTest`, `ReticlePolicyTest`, `SelectionTimingCopyTest`, `ConfirmDialogTest`, `SourceScanTest`, and the harness of `HonestyCopyTest` and `AccessibilityAuditTest`. |

**E2. App shell, navigation, camera host, first-run flow, and the ViewModel contract**

| | Files |
|---|---|
| Creates | `ui/shell/MainShell.kt`, `ui/shell/MenuScreen.kt`, `ui/shell/BannerOverlayHost.kt`, `ui/navigation/AppRoutes.kt`, `ui/camera/CameraPipelineHost.kt` (with `MainThreadFrameConflator` and `LocalCameraPipeline`), `ui/camera/CameraPreviewSurface.kt` (with `FaceGuideOverlay`), `ui/auth/SignInScreen.kt`, `ui/auth/GoogleSignIn.kt`, `ui/setup/SetupScaffold.kt`, `SetupWelcomeScreen.kt`, `ConsentScreen.kt`, `SetupCameraScreen.kt`, `SetupScanScreen.kt`, `SetupCalibrateScreen.kt`, `SetupTilesScreen.kt`, `SetupBackupScreen.kt`, `SetupReadyScreen.kt`, `ui/viewmodel/UiContracts.kt`, `res/values/strings_shell.xml` (sign-in, setup, menu). |
| Edits | `MainActivity.kt` (keeps the Activity, `AppRootNavigation` and the small helpers the tests pin), `ui/viewmodel/EyeTrackingViewModel.kt` (section 7 only), `data/model/GazeModels.kt`, `data/repository/LocalTelemetryStore.kt`, `app/build.gradle.kts` (line 108). |
| Deletes, in the last step | `ui/screens/AuthScreen.kt`, `ui/screens/CalibrationStudioScreen.kt`, `ui/screens/GazeControlDeckScreen.kt`, `ui/screens/AttentionAnalyticsScreen.kt`, `ui/screens/CloudPrivacyScreen.kt`, `ui/components/DwellTriggerSettingsDialog.kt`, `ui/components/OpticalDiagnosticsBar.kt`, `ui/components/EnrollmentScanOverlay.kt`. |
| Rewrites tests | `regression/ActivityRegressionTest`, `regression/AuthRegressionTest`, `optimization/ActivityOptimizationTest`, `optimization/AuthOptimizationTest`, `regression/VmRegressionTest`, `optimization/VmOptimizationTest`, `enrollment/VmEnrollmentTest`, `regression/ModelsstoreRegressionTest`, `enrollment/ModelsstoreEnrollmentTest`. |
| New tests | `AppRoutesTest`, `NavigationTest`, `SetupFlowTest`, `CameraPipelineHostTest`, `VmContractTest`, `NoticeMappingTest`, `MenuScreenTest`, `SignInScreenTest`. |

`SetupScanScreen`, `SetupCalibrateScreen` and `SetupTilesScreen` are thin hosts. They embed E4's
`FaceScanContent(mode = Setup)` and `CalibrateContent(mode = Setup)` and E3's `TilesContent(mode = Setup)`.

**E3. Speak screen and tiles**

| | Files |
|---|---|
| Creates | In `ui/speak/`: `SpeakScreen.kt`, `SpeakGridLayout.kt`, `DeckHitTest.kt` (`shouldUseValidated3x3Grid`, `resolveFocusedDeckTile`, `tileCentersInGazeFrame`), `AacTile.kt`, `SpokenStrip.kt`, `RecordingChip.kt`, `TilesScreen.kt` (`TilesContent`), `TileEditDialog.kt`, `RecenterScreen.kt`. `res/values/strings_speak.xml`. |
| Rewrites tests | `regression/DeckRegressionTest`. |
| New tests | `SpeakGridLayoutTest`, `SpeakScreenTest`, `TilesScreenTest`, `RecenterScreenTest`. |

**E4. Setup flows: face scan and calibration**

| | Files |
|---|---|
| Creates | In `ui/calibration/`: `CalibrateScreen.kt` (`CalibrateContent`), `CalibrationOverview.kt` (accuracy card, saved calibrations, `formatErrorPctOrNull`, `formatApproxMmOrNull`, `indexOfActiveProfile`), `CalibrationWizard.kt`, `WizardBand.kt`, `CalibrationTargetOverlay.kt` (`CalibrationTargetFullscreenOverlay`, `currentCalibrationFixationTarget`, `calibrationTargetCenterPx`, `calibrationTargetPulseSpec`, `isCalibrationCollectingStep`), `CalibrationResult.kt`, `BrowSetupScreen.kt`. In `ui/facescan/`: `FaceScanScreen.kt` (`FaceScanContent`), `EnrollmentScanOverlay.kt` (the overlay, `EnrollmentScanOverlayHost` and the text helpers, copied from the legacy file). `res/values/strings_calibration.xml`, `res/values/strings_facescan.xml`. |
| Rewrites tests | `enrollment/UiEnrollmentTest`, `regression/CalibscreenRegressionTest`. |
| New tests | `WizardBandTest`, `CalibrateScreenTest`, `FaceScanScreenTest`, `BrowSetupScreenTest`. |

**E5. Reading screens**

| | Files |
|---|---|
| Creates | In `ui/reading/`: `ReadScreen.kt`, `PassageSectionCard.kt`, `ReadingHitTest.kt` (`resolveLayoutHitReport`, `passageLocalGazePoint`), `ReadingFormat.kt` (`shouldShowThermalGuard`, `formatEstimatedWpm`, `formatSectionDwellLabel`, `formatZoneSummary`, `formatSessionSummaryLine`), `FixationHeatmapOverlay.kt` (`buildFixationHeatmapFrame`, `ClusterLabelLayouts`), `ReadingReplayScreen.kt`, `ReadingHistoryScreen.kt`. `res/values/strings_reading.xml`. |
| Rewrites tests | `regression/AnalyticsprivacyRegressionTest`, `optimization/AnalyticsprivacyOptimizationTest`. |
| New tests | `ReadScreenTest`, `ReadingReplayScreenTest`, `ReadingHistoryScreenTest`. |

**E6. Settings, privacy, about and the developer panel**

| | Files |
|---|---|
| Creates | `ui/settings/SettingsScreen.kt`, `ui/settings/DwellSettings.kt` (`DWELL_DURATION_SLIDER_RANGE_MS`, `sliderValueToDwellMs`, `formatSensitivityLabel`, `DwellSpeedPreset.matching`), `ui/privacy/PrivacyScreen.kt`, `ui/about/AboutScreen.kt`. In `ui/developer/`: `DeveloperScreen.kt`, `DevPanels.kt`, `SimulatorPanel.kt`, `DevInline.kt`, `DevHudStrip.kt`, `MlKitFaceOverlayCanvas.kt`, `CalibrationDevFormatters.kt` (`formatEyeTelemetryLine`, `formatRawFeatureVectorLine`, `calibrationSampleCounterText`, `formatFittedMatrixTextOrNull`, `fitTargetPillCoordLabels`, `fitTargetSummaryCoordLabels`, `formatProfileErrorLine`, `formatBenchmarkSummaryLine`). `res/values/strings_settings.xml` (settings, privacy, about). |
| Rewrites tests | `optimization/CalibscreenOptimizationTest`. |
| New tests | `SettingsScreenTest`, `PrivacyScreenTest`, `AboutScreenTest`, `DeveloperScreenTest`, `DevInlineTest`. |

Every engineer also registers their screens in the two audit harnesses (`HonestyCopyTest`, `AccessibilityAuditTest`)
by adding one entry per screen state to a list in their own test package. Nobody edits the harness except E1.

### 9.2 String resources

**Rule.** Every text an end user can see or hear from TalkBack is a string resource: labels, body copy, banner
headlines and details, dialog text, content descriptions, state descriptions. The quoted copy in sections 2 and 3 is
the English value. Developer-only text (the Developer screen, developer slots, developer strip, the technical
sentences in the message log) stays in Kotlin, in English, with `Locale.US`.

**Files.** Android merges every file in `res/values/`, so the split below gives each engineer a file of their own.

| File | Owner | Holds |
|---|---|---|
| `strings.xml` | E1 | The existing entries (table below), `common_*`, `status_*`, `help_*`, `confirm_*`, `notice_*`, `a11y_*` shared descriptions |
| `strings_shell.xml` | E2 | `signin_*`, `setup_*`, `consent_*`, `menu_*`, `content_*` (default tiles, passages, read-back sentences) |
| `strings_speak.xml` | E3 | `speak_*`, `tiles_*`, `recenter_*` |
| `strings_calibration.xml`, `strings_facescan.xml` | E4 | `calibrate_*`, `wizard_*`, `result_*`, `brow_*`, `facescan_*`, `scan_*` |
| `strings_reading.xml` | E5 | `read_*`, `replay_*`, `history_*` |
| `strings_settings.xml` | E6 | `settings_*`, `privacy_*`, `about_*` |

**Names.** `<area>_<element>[_<state>]` in lower snake case. A test tag and a string name may differ; test tags are
fixed by section 2. Examples: `speak_strip_default`, `status_title_no_face`, `status_caption_not_checked`,
`help_layout_limited`, `confirm_erase_everything_title`, `notice_calibration_saved`,
`notice_calibration_backup_failed_detail`, `result_layout_keep_4`.

**Notices.** Each `NoticeKey` other than `DEV_LOG` has `notice_<key>` (headline) and, where 3.5 or 3.7 gives one,
`notice_<key>_detail`. Keys that are shown inline with different words have `notice_<key>_inline`.

**Arguments and plurals.** Positional arguments only (`%1$s`, `%1$d`). Plurals for: cloud items deleted, taps left
to developer mode, blinks, tiles. Names of calibrations, readings and tiles are passed as arguments, never
concatenated.

**Numbers and dates.** End-user numbers, percentages and dates follow the device locale ("d MMM yyyy" and
"d MMM, HH:mm" skeletons). Honesty words ("Not calibrated", "Not checked", "Not measured", "Simulator", "SIMULATED")
are single resources used everywhere, so one translation covers every screen.

**Text helpers.** Pure helpers that return end-user text (`enrollmentGuidanceText`, `enrollmentPhaseLabel`,
`enrollmentWarningLabel`, `enrollmentFailureText`, `enrollmentSecondaryLine`, `formatSectionDwellLabel`,
`selectionTimingSentence`, `trackingStatus`) take a `Resources` parameter. Their English values are the ones in this
spec, and where section 2 says "unchanged" they equal today's text, so the rewritten tests keep their expected
strings.

**Content the ViewModel owns.** The default tile labels and phrases, the three reading passages and the two
read-back sentences are resources (`content_*`). The shell's factory reads them once and passes them to the
ViewModel as `AppContent` (7.3 edit 12). Speech uses the phone's text-to-speech language, as today.

**Existing entries.**

| Name | New value |
|---|---|
| `app_name` | "Æye" (unchanged) |
| `auth_title` | "Æye" |
| `auth_subtitle` | "Speak and read with your eyes." |
| `sign_in_google` | "Sign in with Google" (unchanged) |
| `privacy_notice_title` | "How Æye uses your data" |
| `privacy_notice_body` | the full note of 2.2.2 |
| `cloud_telemetry_opt_in_title` | "Cloud backup (optional)" |
| `cloud_telemetry_status_opted_in` | "On. Calibrations and reading summaries are copied to a private space tied to your Google account." |
| `cloud_telemetry_status_opted_out` | "Off (default). Everything stays on this phone." |
| `delete_firestore_telemetry_button` | "Delete cloud copies" |
| `deleting_firestore_telemetry_label` | "Deleting…" |
| `confirm_delete_cloud_dialog_title` | "Delete the cloud copies?" |
| `confirm_delete_cloud_dialog_body` | "Removes every calibration and reading summary saved in your private cloud space. What is on this phone stays." (the path argument is dropped) |
| `confirm_delete_cloud_action` | "Delete cloud copies" |
| `cancel_action` | "Cancel" (unchanged) |
| `tts_unavailable_warning` | "Speech is off: no text-to-speech voice is installed." |
| `capture_gaze_sample_action` | "Capture now" |
| `nav_calibrate`, `nav_control`, `nav_attention`, `nav_cloud` | dropped with the tabs (**#6**) |
| `calibration_5_point_title`, `calibration_5_point_subtitle`, `calibration_mode_5_point_only`, `calibration_mode_5_plus_4`, `start_5_point_calibration` | dropped; replaced by the `calibrate_*` strings of 2.8.1 |

`ActivityOptimizationTest.FROZEN_USED_STRINGS` is regenerated from all six files at integration and keeps its rule:
every referenced string is present with its frozen value, and nothing unreferenced remains.

### 9.3 Order of work

| Step | Who | What | The app during this step |
|---|---|---|---|
| 0. Contracts | E1, E2, E6 | E1: tokens, the old colour names as `@Deprecated` aliases, and the signatures of all shared components. E2: `UiContracts.kt`, the ViewModel and store changes of section 7 with their tests, `AppRoutes.kt`. E6: empty `DevInline` composables. | The old screens still build and run. |
| 1. Screens in parallel | E1 to E6 | Each engineer builds their package against the contracts and fake state, rewrites their test classes and writes their new tests. Legacy files are not edited. | The old screens still run; the new ones are reachable only from tests. |
| 2. Integration | E2, with E1 | `MainActivity` switches to `MainShell`. All routes are registered. The eight legacy files and the colour aliases are deleted. `FROZEN_USED_STRINGS` is regenerated. Full unit suite, lint, assemble. | The new app. |
| 3. Checks | all | The manual checks of 8.4 on hardware. A walk-through with the product owner, with a helper and a user if possible. | Release candidate. |

A screen is done when its section of this spec is implemented, every test tag of that section exists, its strings
are resources, it is registered in both audits, and its tests pass.

### 9.4 Risks to watch

| Risk | Mitigation |
|---|---|
| Adding or removing the camera preview reconfigures the camera and drops frames. | `CameraPipelineHost` never changes use cases while a scan, a wizard or a recording runs. Removal of the preview is deferred until the app is idle for 2 s. The "Starting" status has a 4 s grace, so a short gap never shows as an error. Pinned by `CameraPipelineHostTest` and manual check 5. |
| The fonts have one Regular face, so Medium renders as Regular and Bold is synthesized. | Accepted for this release; hierarchy comes from size. No new font files, so `BuildthemeRegressionTest` holds. |
| Very small or split-screen windows cannot fit four 96 dp tiles. | The grid still shows 4 tiles at the available size and never scrolls. Gaze selection is not guaranteed in such a window; tap still works. `AccessibilityAuditTest` also runs at 320 × 480 so nothing clips. |
| Old and new helpers with the same name exist side by side during step 1. | They live in different packages, and the legacy files are deleted in step 2. `SourceScanTest` fails if a new file imports `ui.screens`. |
| A new ViewModel message is added without a mapping. | `NoticeMappingTest` fails the build (3.7). |
| Existing accounts are sent through setup once after the update. | The steps already done show "Already done" with "Keep and continue" (1.2). Nothing is recalculated or deleted. |

---

## 10. What you will notice

This section is for the product owner. It describes the change as a person using the app would see it.

**The first time.** After signing in, Æye walks a helper and the user through six short steps: a privacy page to
agree to, turning on the camera (with a picture so the face can be lined up), a 15-second face scan, a one-minute
calibration where the user looks at dots, an optional page to change what the tiles say, and a choice about backup.
Any step after the privacy page can be skipped and done later. The last page says what was done and what was
skipped, in plain words, and hands the phone to the user.

**Every day.** The app opens straight on the Speak screen. It shows the tiles, one line with the last thing that was
said, a small status in the corner ("Tracking", "No face", "Camera off"), and one Menu button. Nothing else. The
tiles are much larger than today: four big tiles to start with, nine once a full calibration has measured good
accuracy. While the user looks at a tile, a bar fills along its bottom edge. When it is full the tile speaks and
shows a tick.

**The pointer.** The mark that follows the eyes is now a thin ring with no numbers on it. It appears only on the
Speak screen, and as a small dot while a reading is being recorded. It no longer covers labels or text.

**Messages.** There is one place for messages. Only one shows at a time, in plain words, and each goes away by
itself. A message never pushes the tiles around while someone is looking at them.

**The Menu.** Everything a helper needs is behind the Menu button, one job per page: Read, Calibrate, Recenter, Face
scan, Tiles, Settings, Privacy & account, About. Speed and selection settings exist in one place only (today they
are in three).

**Reading.** Read shows one of three short everyday texts instead of technical text about the app. After "Finish and
save", the result page shows the time, the reading pace, blinks, and where on the page the user looked.

**Honest wording.** Where Æye has not measured something it says so: "Not calibrated", "Not checked", "Accuracy
limited", "Not measured". It never shows a number it does not have. Anything made with the built-in simulator is
labelled "Simulator" or "SIMULATED".

**Privacy.** Nothing leaves the phone unless backup is turned on. Sign out, "Delete cloud copies" and "Erase
everything" are together on one page, and each asks before it acts. On first use it is possible to sign out or erase
before agreeing to anything.

**What moved out of sight.** The engineering readouts (frame rate, camera picture, eye and head numbers, the
simulator, test tools) are now in a hidden developer mode. It is opened from About by tapping the version line seven
times.

**What is gone.** The four tabs at the bottom, the bar of numbers at the top of every screen, the second and third
copies of the speed settings, the device-name claims, and the technical vocabulary.

**What is exactly the same.** How selection works (holding a look for about a second, a long blink, or raised
eyebrows), every safety rule (nothing fires when the face is lost or the eyes are closed; normal blinks never
select), the calibration method, and everything already saved: calibrations, readings and edited tile phrases.

**After the update.** People who already use Æye see the first-time steps once. Steps they have already done say
"Already done" with a "Keep and continue" button, so nothing has to be repeated.

### 10.1 Decisions we need from you

1. **Reading needs a helper.** Read is opened from the Menu by touch, and a reading is started and stopped by touch.
   A user cannot open it by gaze. Is that acceptable, or should the user be able to open Read by looking?
2. **Wording.** The nine default tile phrases (2.10) and the three reading texts (2.5) are proposals. Approve them or
   supply your own.
3. **Recalibrate suggestion.** Today a tap on it starts calibration at once. Here it opens the Calibrate page, so the
   helper can get the user into position first. OK?
4. **Erase everything** now also returns the app to first-time setup, including the privacy page. OK?
5. **Developer mode** is unlocked by seven taps on the version line, applies to the whole phone and survives "Erase
   everything". Is that enough, or should it need a code?
6. **Quick calibration** (5 dots, accuracy not checked, never unlocks nine tiles) stays as a secondary button for
   helpers. Keep it or remove it?
7. **Sign-in.** A Google account stays required before first use, as today. Please confirm.
8. **Languages.** English only at first; all texts are prepared for translation. Which languages are needed next?

---

## Appendix A. Reviewer findings and where they are resolved

Three reviewers (J1, J2, J3) scored the three designs. Design A (speak-first) is the base of this spec. A.1 lists
every problem found in A and its fix. A.2 and A.3 list the problems found in B and C, because parts of both were
taken over; each row says why the problem did not come along. A.4 lists every idea the reviewers asked to graft.

### A.1 Findings against design A (the base)

| # | Finding | By | Resolution |
|---|---|---|---|
| A1 | "Delete cloud copies" was disabled when backup was off, so copies left in the cloud could only be removed by erasing everything. | J1, J2, J3 | 2.14 item 3: enabled whenever no deletion is running. 7.4: no precondition in the ViewModel. `PrivacyScreenTest`. |
| A2 | Consent was a wall: no sign-out and no erase before agreeing, also for existing users with data on the phone. | J1 | 2.2.1 `setup_sign_out_button`; 2.2.2 `consent_sign_out_button` and `consent_erase_button`; 1.4 Back on Welcome asks to sign out; 1.2 existing accounts. `SetupFlowTest`. |
| A3 | The layout line had two states. After a full, checked calibration that was not accurate enough it still said "run the full calibration to unlock 9"; the Tiles screen made the same promise. | J1, J2, J3 | Five distinct states everywhere: result card (2.8.3), status caption and help sheet (2.0), Menu card (2.4), overview (2.8.1). Tiles intro (2.10): "All 9 are shown when a full calibration measures good accuracy." |
| A4 | Long-press on a Speak tile opened the edit dialog: a hazard for slow or tremor taps and unreachable by Switch Access. | J1 | 2.3 Tile ▸ Touch: no long-press, no edit control. Editing only on the Tiles screen (2.10). 1.1 rule 6. `AccessibilityAuditTest`. |
| A5 | The reticle kept an 18 % filled ellipse and was drawn, ellipse included, over the Read passage. | J1, J2, J3 | Section 4: outlines only, never filled; a dot on Read while recording, hidden otherwise; clipped to the gaze content. |
| A6 | Developer homes that did not exist: the eye-openness number during a scan, session zone percentages, the natural / command blink split, the cluster count. | J1, J2, J3 | Developer slots on the screens where the data lives (2.16.3): `DevScanDetails`, `DevRecordingDetails`, `DevSessionDetails`. The zone values also feed the end-user line "Looked mostly at the top of the page" (2.7). |
| A7 | The map claimed the glare banner names the tracked eye; its copy did not. Single-eye tracking had no end-user surface. | J1, J2, J3 | `ONE_EYE` notice with the eye named in its detail (3.5); help sheet "Right now" (2.0); section 6 row 25. |
| A8 | Performance mode had no end-user signal outside a recording. | J2 | `PHONE_BUSY` notice, 6 s, quiet for 5 minutes (3.5, 3.4 rule 7); help sheet. |
| A9 | The Ready page could say "checked" for a calibration made from simulated frames. | J2 | 2.2.8: "Simulated. Not a real measurement."; status "Simulated calibration" (2.0); "SIMULATED" badge on the result card (2.8.3) from `cal.usedSimulatedFrames` (7.1). |
| A10 | No explicit Switch Access statement. | J2 | 1.1 rule 6; `AccessibilityAuditTest`; manual check 2 (8.4). |
| A11 | The user-visible "SIMULATED" badge used 14 sp. | J2, J3 | 5.2: labelSmall is 16 sp; no end-user text under 16 sp; `BuildthemeRegressionTest`. |
| A12 | No camera picture to help a helper frame the face outside developer mode. | J1, J2, J3 | Setup ▸ Camera with `CameraPreviewSurface` and "Face found" (2.2.3); positioning preview in the scan (2.9.2). |
| A13 | Setup had no phrases step and no backup choice. | J2 | 2.2.6 and 2.2.7. |
| A14 | Heaviest ViewModel change: the banner flow changed type across about 45 call sites and three test classes; a new overlay enum value; stop-and-save changed. | J1, J2, J3 | Section 7: the legacy flow is kept word for word and a notice channel is added beside it; no new overlay value (the overlay mode is developer-only); stop-and-save already selects the saved reading in today's code, so nothing changes there. The HUD gains four read-only fields. |
| A15 | Green acted as a second accent (progress bars, reticle arc, check targets, Material `secondary`). | J1, J2, J3 | 5.1: `secondary` is neutral; progress, target and focus use the accent; green appears only with an icon or word. Check dots differ by shape (2.8.2). |
| A16 | Wizard text and buttons changed side from dot to dot. | J3 | Kept, because it is what keeps text away from the target, and made safe: the band moves only between dots during the settling delay and ignores input for 300 ms after a move (2.8.2). |
| A17 | 200 dp of fixed chrome above the tiles (top bar, lane, strip). | J3 | Kept on purpose: status, banner and last phrase can then never hide one another. Tiles are still about 183 × 290 dp in the 4-tile layout (2.3). |
| A18 | Read is two touches away. | J3 | Kept: user decision 2 tucks reading away. Raised with the owner as question 1 of 10.1. |
| A19 | About 20 routes and 40 new files. | J1, J3 | Accepted: it follows from "one job per screen". 9.1 gives every file one owner; 9.3 keeps the old app running until integration. |

### A.2 Findings against design B (not carried over)

| # | Finding | By | Why it does not apply here |
|---|---|---|---|
| B1 | Stepper pills, progress map, raw counter and eye-openness were homed on a Developer screen "while the wizard or scan runs", a state that cannot occur. | J1, J2 | Developer slots sit inside the wizard and scan screens (2.16.3). The dots row with its accessible nodes is in the end-user wizard (2.8.2). |
| B2 | Manual capture became developer-only. | J1, J2 | `capture_gaze_point_button` and the current dot node are for every user (2.8.2). |
| B3 | A live "Try it" gaze tile on the settings screen, which also needed an unlisted ViewModel change. | J1, J2, J3 | Not adopted. Gaze fires tiles on Speak only (1.1 rule 1, 1.8). |
| B4 | The Speak grid could scroll, leaving tiles a gaze user cannot reach. | J1, J2, J3 | `SpeakGridLayout.resolve` never scrolls and falls back to 4 tiles (2.3). |
| B5 | "4 large tiles, calibration not checked" was shown for every 4-tile state. | J1, J2, J3 | Five distinct layout lines (see A3). |
| B6 | Blocking notices were sticky and could not be dismissed; they replaced the status word and starved other notices. | J1, J2, J3 | 3.3: every banner ends by itself and can be dismissed. 3.4 rule 6: results are never starved. The status block is a separate region (2.0, 3.8). |
| B7 | The banner sat inside the status region beside two buttons and could not fit its text. | J2, J3 | A reserved lane of its own (2.3, 3.1), a 40-character headline budget and `BannerCopyFitTest` (3.2). |
| B8 | One section said Reading opens from a gaze tile, another said it does not. | J1, J2, J3 | 1.1 rule 1 and 1.8: gaze never navigates. `tile_read_aloud` only speaks (2.10). |
| B9 | Without camera permission, Speak re-routed to a touch-only camera screen and tap-to-speak was lost. | J1, J2 | 1.2: the person stays on Speak with "Camera off"; tiles stay tappable (2.3). |
| B10 | Reading passages were placeholders. | J1, J2, J3 | Final texts in 2.5. |
| B11 | A "Re-centre now" row fired the capture while the helper, not the user, faced the phone. | J1 | `RecenterScreen` with instructions and a 3-second countdown (2.13). |
| B12 | "Skip setup" came before consent and the camera could bind without consent. | J2, J3 | Skip is offered from step 2 only (1.5, 2.2). The camera binds only after consent (1.2, 1.7). `SetupFlowTest`, `CameraPipelineHostTest`. |
| B13 | No skip at calibration; no way on when the camera is permanently denied or missing. | J2 | Every step has a way on: 2.2.3 "Continue without the camera for now", 2.2.4 and 2.2.5 "Skip for now". |
| B14 | The fixed top band and bottom bar overlapped the upper and lower dots. | J1, J2, J3 | `wizardBandPlacement` with a tested no-overlap rule (2.8.2). |
| B15 | Two blocking notices could hide the simulator label. | J3 | The simulator is a status, not a banner (1.1 rule 4, 2.0, 3.5). |
| B16 | The no-front-camera state was mapped but not specified. | J2, J3 | 2.2.3 state row; status "No camera" (2.0); `NO_FRONT_CAMERA` notice (3.5). |
| B17 | It claimed the ViewModel already blocks switching the simulator during a measurement. It does not. | J2 | 7.3 edit 2 adds that guard; the switch is also disabled on screen (2.16.1). |
| B18 | The per-dot table claimed 9 rows; the data may hold only the fit dots. | J3 | 2.8.3: one row per entry of `cal.pointSampleCounts`. |
| B19 | The spoken line was parsed out of a formatted string. | J3 | Structured `hud.lastSpoken` (7.1). |
| B20 | The "Eyes closed" status needed a threshold the UI did not have. | J3 | `hud.gazeInputState` (7.1) with the 800 ms grace in `trackingStatus` (2.0). |
| B21 | Back on Speak closed the app. | J1 | "Leave Æye?" confirmation (1.4). |
| B22 | Ten setup screens; a hand-written navigator; about 21 screens. | J1, J2 | Six numbered steps between Welcome and Ready, three of them skippable (1.5). Navigation Compose (1.3). |

### A.3 Findings against design C (not carried over)

| # | Finding | By | Why it does not apply here |
|---|---|---|---|
| C1 | The last spoken phrase was a 3-second notice that any higher notice dropped; with speech off the phrase was never shown. | J1, J2, J3 | A permanent spoken strip, separate from the banner lane (2.3). |
| C2 | The status line, with the simulator label, shared the single slot with notices and could be hidden. | J1, J2, J3 | Status block, banner lane and spoken strip are three regions (2.3). |
| C3 | A 196 dp strip at the bottom sat under the lower calibration dots. | J1, J2, J3 | `wizardBandPlacement` (2.8.2). |
| C4 | One settings sheet mixed speed, calibration, tiles, readings, erase, sign-out and the developer unlock. | J1, J2, J3 | Separate screens from one Menu (1.1 rule 5, 2.4). |
| C5 | The camera step had no skip; a first-run denial blocked the home screen; any user without permission was sent back to setup. | J1, J2 | 2.2.3 skip path; 1.2: a later revocation stays on Speak. |
| C6 | Save stayed enabled for unmeasured results. | J1, J3 | Save is disabled for "Not measured" (2.8.3); the ViewModel guard stays. |
| C7 | Long-press tile editing. | J1 | See A4. |
| C8 | 14 sp text for end-user labels; 48 dp chips. | J1, J2, J3 | 16 sp floor (5.2), 56 dp targets (5.4), both audited (8.2). |
| C9 | Default tiles left as "Affirm / Yes", "Read Summary" and so on. | J1, J2, J3 | New plain defaults (2.10), with a one-time upgrade that never touches edited tiles (7.5). |
| C10 | Invented "Good / Fair / Limited" bands at 8 % and 12 %. | J1, J2, J3 | The only classifier is the existing `shouldUseValidated3x3Grid` (7.4). "Good" means it qualifies for 9 tiles; "limited" means it does not. |
| C11 | A fit residual was shown as "±x % (estimate, not checked)". | J2, J3 | A fit residual is never shown as accuracy: rows say "Not checked. Accuracy unknown." (2.8.1) and the result card says "This is not an accuracy check." (2.8.3). |
| C12 | Capabilities 79 to 87 shared one row. | J2 | One row each (section 6). |
| C13 | Read was a top-level switch, not tucked away. | J2 | Menu ▸ Read (2.4). |
| C14 | Calibration dots stayed small; the camera picture stayed visible during the scan. | J2 | Larger target (2.8.2); the preview exists only in the positioning phase (2.9.2). |
| C15 | A third developer line was squeezed into the fixed 72 dp slot. | J1, J3 | The developer strip has its own reserved 40 dp region (2.16.2). |
| C16 | The privacy note could only be re-read by redoing setup. | J3 | `PrivacyNoteSheet` from Sign-in, Consent, Privacy and About (2.1, 2.2.2, 2.14, 2.15). |
| C17 | Contradictions about where the reticle shows during setup and about the gaze frame. | J3 | One table (4.2): hidden on every setup step. The root gaze frame is unchanged (1.7). |
| C18 | No hand-over step. | J2 | Ready page (2.2.8). |
| C19 | Attaching and detaching the preview rebinds the camera. | J3 | 9.4 first row: no use-case change during a measurement, deferred removal; `CameraPipelineHostTest`. |

### A.4 Ideas the reviewers asked to graft

| # | Idea | From | By | Where it is |
|---|---|---|---|---|
| G1 | Stroke-only reticle, dashed unfilled uncertainty outline, kept out of the chrome, nothing over the passage when idle or in replay. | B | J1, J2, J3 | Section 4. |
| G2 | Camera step with a live picture and "Face found"; a small positioning picture in the first scan phase only; a simple face guide instead of the full overlay. | B, C | J1, J2, J3 | 2.2.3, 2.9.2, `FaceGuideOverlay` (2.0). |
| G3 | Calibration result with distinct, honest outcomes, including "checked but limited" and "this is not an accuracy check". | B | J1, J2, J3 | 2.8.3 (five outcomes). |
| G4 | Two consent checkboxes; explicit backup choice, off by default; phrases step with "In 4-tile layout" marks; hand-over summary; "Skip setup" with a confirmation; sign-out confirmation on Back. | B | J1, J2, J3 | 2.2.2, 2.2.7, 2.2.6 and 2.10, 2.2.8, 2.2, 1.4. |
| G5 | Sign out on the consent step; erase clears the setup state. | C | J1 | 2.2.2; 1.5; 7.3 edit 4. |
| G6 | Keep the legacy banner flow word for word and add a plain-language notice channel with a history. | B, C | J1, J2, J3 | 7.1, 7.2 (`postNotice`), 3.7. |
| G7 | ViewModel-level guards: simulator and overlay switching ignored outside developer mode. | C | J1, J2, J3 | 1.6; 7.3 edits 1 to 3. |
| G8 | Selection-timing copy built from the detector constants; keep the copy regression test pointed at it. | C | J1, J2, J3 | `selectionTimingSentence` (2.0), `hud.selectionTimings` (7.1), `SelectionTimingCopyTest` (8.2). |
| G9 | One `GazeInputState` driving status and reticle, with the 900 ms no-face grace and an "Eyes closed" state. | C, B | J1, J2, J3 | 2.0, 4.2, 7.1. |
| G10 | Tile "Fired" state; a haptic tick per calibration dot and scan phase; check dots that differ by shape. | C | J1, J2, J3 | 2.3; 2.2, 2.8.2, 2.9.2; 2.8.2. |
| G11 | Stepper and accessible per-dot capture nodes in the end-user wizard, placed by A's band rule. | C, A | J1, J2, J3 | 2.8.2. |
| G12 | No long-press actions on the Speak tiles. | B | J1 | 2.3, 1.1 rule 6. |
| G13 | One-accent discipline: neutral `secondary`, lighter secondary text (at least 7 : 1), no layout animation on gaze screens, a semantics audit per screen. | B | J1, J2, J3 | 5.1, 5.6, 5.5, 8.2. |
| G14 | End-user notices for "one eye visible" and "tracking slowed". | B, C | J1, J2 | 3.5 (`ONE_EYE`, `PHONE_BUSY`). |
| G15 | Helper tracking card; a recenter entry outside the 9-tile grid; a "Recording m:ss, Stop" chip on Speak. | B | J1, J2, J3 | 2.4; 2.13 (as a screen with a countdown, see B11); 2.3. |
| G16 | Speech preview through the shell's speech engine, not the ViewModel. | B | J1, J3 | `LocalSpeechPreview` (1.7, 2.10, 2.11, 7.6). |
| G17 | Supplied everyday reading passages; "Quick recalibrate (5 dots, not checked)" as an explicit choice; fixed heights computed once from the font scale; a caption for the space fallback. | C | J1, J2, J3 | 2.5; 2.8.1 item 5; section 2 opening; "Screen fits 4 tiles" (2.0). |
| G18 | Helper intro with a checklist that shows what is done and resumes at the next open step. | B | J2 | 2.2.1; `nextSetupStep` (1.2). |
| G19 | Bind the camera only after consent is recorded. | B (as a fix) | J2 | 1.2, 1.7. |
| G20 | A layout line that includes "accuracy was limited". | C | J2 | 2.0 (`help_layout_text`, five cases). |
| G21 | Reticle clipped to the content pane; a dot on Read. | C | J2, J3 | 4.3, 4.4. |
| G22 | Pure start-route and notice-policy functions with exhaustive tests; the performance notice limited to once per 5 minutes; a later permission loss stays on the home screen. | C | J2, J3 | 1.2; 3.4, 3.5; 8.2. |
| G23 | Keep existing composable names (`AuthScreen`, `AuthenticatedMainWorkbench`), test tags and colour token names to limit test churn. | C | J2, J3 | **Partly adopted.** Test tags are kept wherever the element survives (listed through section 2). Colour names stay as deprecated aliases until integration (5.1). Composables are renamed (`SignInScreen`, `MainShell`), because the old screens must keep building beside the new ones during step 1 (9.3). |
| G24 | A's shell rules: separate banner lane; priority queue with minimum display time and same-key update; status table with a help sheet; simulator never success-coloured, own reticle shape and strip prefix; pure layout and policy functions; migration with "Keep"; progress bar on the tile; one semantics node per tile; band placed by target; replay opens after saving; eyebrow gauge with inline results; "Test the voice"; developer unlock with a confirmation and an unregistered route; honesty and touch-target audits; reduced motion. | A | J3 | 2.3, 3.1 to 3.4, 2.0, 4.3, 2.3, 1.2, 2.8.2, 2.5, 2.12, 2.11, 1.6, 8.2, 5.5. One change: banners no longer keep the slot until dismissed (3.3). |
| G25 | Role-and-label sign-in test instead of a frozen semantics tree; a source scan that confines the monospace font to the developer package. | B | J3 | 8.1 (`AuthOptimizationTest`), 8.2 (`SourceScanTest`). |
| G26 | Helper menu grouped by the helper's question, with re-entry to single steps. | B | J3 | 2.4: each Menu row opens the stand-alone screen for that step; "Run setup again" restarts the guided flow. |
