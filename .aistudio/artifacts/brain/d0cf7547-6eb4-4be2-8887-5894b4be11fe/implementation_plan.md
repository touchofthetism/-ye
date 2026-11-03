# OculaGaze — Region-Level Gaze Interaction, Validated Calibration & Section Attention Analytics

OculaGaze uses an Android device's front-facing camera to deliver coarse region-level gaze estimation, duration-gated blink triggers for in-app hands-free communication/reading, and layout-measured section attention analytics—backed by a 5-point calibration + 4-point held-out validation workflow and user-controlled Cloud Firestore sync.

## User Review & Critical Decisions (Revised per Technical Audit)

> [!IMPORTANT]
> Every architectural overclaim identified in the technical audit has been corrected in this revised blueprint:

- **Audit Fix 1 — Explicit Vision Pipeline & Honest Granularity**: Uses **ML Kit Face Detection (`com.google.mlkit:face-detection`) combined with real-time Eye-ROI luminance-weighted pupil/iris centroid extraction** (computing normalized iris center $(g_x, g_y)$ relative to medial/lateral eye contour corners alongside 3D head Euler angles $\text{yaw}, \text{pitch}, \text{roll}$). Claims and UI targets are explicitly scoped to **coarse region/zone accuracy (~10–15% screen width, suitable for 2×3 / 3×3 large interactive tiles and paragraph-level reading zones)**, with real-time diagnostics for **eyeglass specular glare/occlusion** and **head-distance drift**.
- **Audit Fix 2 — Cross-Coupled 2×6 Affine Regression Matrix**: Replaces the naive diagonal `sensitivityX/Y` scale-and-shift with a full **12-parameter cross-coupled affine regression matrix** ($\mathbf{W} \in \mathbb{R}^{2 \times 6}$) mapping the 6D feature vector $\mathbf{f} = [1, g_x, g_y, \text{yaw}, \text{pitch}, \text{roll}]^T$ to normalized screen coordinates $(x_s, y_s)$ via Ridge-regularized Ordinary Least Squares (OLS), properly modeling head yaw/roll coupling into vertical and horizontal gaze estimates. Profiles are tagged with `deviceModel` and `screenAspectRatio` so calibration matrices are never blindly applied across incompatible camera geometries.
- **Audit Fix 3 — 5-Point Fit + 4-Point Held-Out Validation Protocol**: Calibration is split into **5 fitting targets** (center + 4 corners) followed immediately by **4 held-out validation targets** (inner quadrant centroids). Accuracy is reported honestly as **Mean Euclidean Validation Error in millimeters (`meanErrorMm`) and normalized screen percentage (`meanErrorPct`)**, plus a recommended target grid density (e.g., 2×2 vs. 3×3 tiles).
- **Audit Fix 4 — Hysteresis & Duration-Gated Intentional Blink Detection**: Separates involuntary natural blinks (100–250 ms) from intentional command blinks using a dual-threshold Schmitt trigger (`closeThreshold = 0.25`, `openThreshold = 0.55`) and a **long-blink duration gate (`350 ms – 800 ms`)**. Natural blinks increment fatigue/blink-rate telemetry without triggering UI actions; only sustained intentional blinks within the time window fire tile selection.
- **Audit Fix 5 — Layout-Measured Section Dwell & Centroid Sequence**: Replaces ungrounded "saccade scanpaths" and word-level WPM claims with **Compose `onGloballyPositioned` section bounding-box dwell tracking**, **passage reading duration vs. known word count (`effectiveWordsPerMinute`)**, and a **coarse fixation centroid sequence** sampled at camera cadence (15–30 Hz).
- **Audit Fix 6 — Cohesive Biometric Privacy, Opt-In Sync & One-Tap Deletion**: Raw camera frames are processed strictly in volatile RAM and discarded immediately. Because derived gaze coordinates and blink telemetry are sensitive behavioral data, the app provides an explicit **Cloud Sync Consent Toggle** (defaulting to user choice after sign-in, with local-only execution available anytime) and **one-tap "Delete All Cloud Telemetry" / per-record deletion** in the Cloud History & Privacy tab.
- **Audit Fix 7 — Strictly In-App Hands-Free Scope**: Hands-free control is explicitly scoped to an **In-App AAC Communication & Reader Control Deck** (speaking customizable phrase cards via Android `TextToSpeech` and auto-scrolling/paging in-app reading passages) rather than claiming OS-wide `AccessibilityService` control.

---

## 1. Overview & Core Concept

- **What It Does**: OculaGaze processes front-camera frames on-device to extract eye-contour iris centroids and head pose angles, fits a cross-coupled calibration model validated against held-out screen targets, enables hands-free interaction with large in-app communication and reader tiles via dwell or intentional long-blinks, and records section-level screen attention distributions.
- **Target Audience / Persona**: Users exploring camera-based hands-free AAC communication boards, UX testers evaluating coarse screen-zone attention (top/middle/bottom and card-level dwell), and readers monitoring blink-rate regularity during screen reading.
- **Key Value**: Provides an honest, transparent, and mathematically grounded mobile eye-tracking workbench on standard Android hardware—showing users the actual physical validation error (in mm) of their front-camera setup and adapting UI target sizes to match measured sensor precision.

---

## 2. User Experience & Visual Design

### Key User Flows
1. **Authentication & Biometric Privacy Gate**: Users sign in with Google via Jetpack Credential Manager. A clear **Telemetry Privacy & Sync Banner** explains that camera frames never leave RAM, while allowing the user to toggle whether derived calibration matrices and session summaries sync to Cloud Firestore or remain strictly in-memory for the current session.
2. **Camera Diagnostics & Eyeglass / Pose Readiness HUD**: Before calibration or tracking, a live diagnostics bar evaluates:
   - **Face & Inter-Ocular Stability**: Warns if the device distance changes significantly from the calibration baseline (measured via inter-ocular pixel distance $d_{\text{eye}}$).
   - **Eyeglass Glare / Landmark Confidence**: Detects high-intensity specular reflections inside the eye contour ROI or low eye-open confidence and advises adjusting screen brightness or head tilt.
   - **Explicit Emulator Simulation Toggle**: Clearly labeled `"Simulator Mode (Touch / Gyro Proxy)"` for browser-streamed emulators lacking a physical camera feed, ensuring simulated data is never conflated with real optical tracking.
3. **5-Point Calibration + 4-Point Held-Out Validation Flow**:
   - **Phase A (Fit — 5 Points)**: User fixates on Center, Top-Left, Top-Right, Bottom-Left, and Bottom-Right targets (collecting 25 frames per point after a 400 ms settling delay to discard transition frames). Solves the $2 \times 6$ regularized least-squares regression matrix $\mathbf{W}$.
   - **Phase B (Validation — 4 Held-Out Points)**: User fixates on 4 intermediate quadrant targets not used during fitting. The engine computes the root-mean-square and mean Euclidean distance between predicted gaze $(\hat{x}, \hat{y})$ and target coordinates, converting normalized screen error into physical millimeters using `DisplayMetrics` (`xdpi`, `ydpi`).
   - **Result Summary**: Displays `Mean Error: e.g., 14.2 mm (11.8% screen)` and recommends an adaptive grid layout (`2×2 High-Margin Grid` or `3×3 Standard Grid`), saving the device-tagged profile to Firestore.
4. **In-App Hands-Free AAC & Reader Control Deck**:
   - Displays a coarse, stabilized **Region Gaze Reticle** (whose radius dynamically reflects the profile's validation error circle) over an adaptive grid of large interactive tiles (64dp–120dp minimum targets).
   - **Trigger Modes**:
     - *Dwell Trigger*: Hovering within a tile's bounding box fills a radial progress ring (`800 ms – 1500 ms` configurable).
     - *Intentional Long-Blink Trigger*: Holding a blink for `350 ms – 800 ms` (tracked via hysteresis thresholds `close < 0.25`, `reopen > 0.55`) triggers the currently focused tile, while natural `< 250 ms` blinks are ignored for control and counted only in the live blink-rate monitor.
   - **Actions**: Speaks AAC communication phrases aloud using Android `TextToSpeech` (TTS), increments/decrements in-app counters, or scrolls/flips structured reading cards.
5. **Section Attention Heatmap & Reading Telemetry Lab**:
   - Users read a structured multi-section article whose paragraph cards report their exact screen bounding boxes via Compose `Modifier.onGloballyPositioned`.
   - At 15–30 Hz camera frame rate, each smoothed gaze sample is tested against the layout bounding boxes to accumulate **exact per-section dwell time (ms and %)** and coarse Gaussian density splats.
   - Upon completion, the view overlays a **Coarse Region Density Heatmap** and **Numbered Fixation Cluster Sequence** (clustered via dispersion threshold rather than claiming millisecond saccade capture), reports **Effective Passage Reading Rate** (total passage words $\div$ active gaze time on passage), and logs blink-rate shifts across sections.
6. **Cloud History, Device Profile Manager & Data Erasure Tab**:
   - Lists saved `CalibrationProfile` records (highlighting which profiles match the current `Build.MODEL` and orientation) and historical `TrackingSession` reports.
   - Includes per-item delete buttons and a prominent **"Erase All Cloud Telemetry"** action that deletes all user documents in `/users/{uid}/calibrations` and `/users/{uid}/sessions` inside a batch/coroutine operation.

### Visual Identity & Theme
- **Aesthetic Direction**: *Optical Calibration Instrument* — dark, low-glare interface designed to minimize corneal reflection on eyeglasses while keeping large gaze targets unmistakably clear.
- **Color Palette & Mood**:
  - `--background`: Deep Matte Obsidian (`#0B0F17`) to reduce screen glare on lenses, paired with Elevated Slate (`#151D2E`) surfaces.
  - `--primary`: Optical Cyan (`#00E5FF`) for the error-radius gaze reticle, active tile borders, and calibration targets.
  - `--secondary`: Validation Emerald (`#10B981`) for verified held-out accuracy and intentional long-blink lock indicators.
  - `--accent`: Thermal Amber-to-Coral (`#F59E0B` → `#EF4444`) for section dwell heatmaps and glare/posture drift warnings.
- **Typography & Hierarchy**: High-legibility sans-serif body typography for reading passages, bold geometric headings for large gaze tiles, and tabular monospace figures for millimeter error, blink duration (ms), and section dwell percentages.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1 — Hybrid Eye-ROI Pupil Centroid + Head Euler Feature Vector with $2 \times 6$ Ridge Regression**
  - *Chosen Approach*: Extract face contours and head Euler angles ($\text{yaw}, \text{pitch}, \text{roll}$) via ML Kit Face Detection (`PERFORMANCE_MODE_FAST`, `CONTOUR_MODE_ALL`, `CLASSIFICATION_MODE_ALL`), crop the left and right eye bounding polygons from the YUV/luminance plane, compute the dark-iris/pupil center of mass relative to the eye corner anchors $(g_x, g_y)$ plus inter-ocular distance $d_{\text{eye}}$, and project $\mathbf{f} = [1, g_x, g_y, \text{yaw}, \text{pitch}, \text{roll}]^T$ through a regularized $2 \times 6$ affine matrix $\mathbf{W}$.
  - *Why*: Pure head pose fails when the user moves their eyes without turning their head, while diagonal scale-and-shift fails when head rotation cross-couples into both axes. A 6D feature vector with full affine cross-terms captures both eye-in-orbit shifts and head-pose compensation within the realistic limits of a front RGB camera.
- **Decision 2 — Held-Out Validation & Error-Radius Reticle**
  - *Chosen Approach*: Render the gaze cursor not as a deceptive 2px pinpoint, but as a translucent **Confidence Circle** whose radius scales with the profile's measured held-out validation error (`meanErrorMm`).
  - *Why*: Visually communicates sensor uncertainty to the user and matches the physical size of selectable tiles.
- **Decision 3 — Google Sign-In + Firestore with Explicit Consent & Erasure**
  - *Chosen Approach*: Gate cloud access behind Google Sign-In (`CredentialManager` + `GetSignInWithGoogleOption`), scope all data under `/users/{userId}/...`, enforce strict Firestore security rules, and provide an in-app Cloud Sync opt-in/pause switch plus full cloud deletion.
  - *Why*: Delivers multi-device persistence while respecting the sensitivity of biometric-derived gaze and blink telemetry.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### Architecture & Component Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        MainActivity (Edge-to-Edge)                          │
│              Auth Gate (Google Sign-In via CredentialManager)               │
└───────────────────┬─────────────────────────────────────────┬───────────────┘
                    │ Unauthenticated                         │ Authenticated
                    ▼                                         ▼
        ┌───────────────────────┐                 ┌───────────────────────────┐
        │      AuthScreen       │                 │     MainAppScaffold       │
        │ (GoogleSignInButton + │                 │ (Diagnostics HUD Bar +    │
        │  Auto Sign-In Check)  │                 │  Bottom Navigation Bar)   │
        └───────────────────────┘                 └─────────────┬─────────────┘
                                                                │
       ┌──────────────────────────┬─────────────────────────────┼──────────────────────────┐
       ▼                          ▼                             ▼                          ▼
┌──────────────┐          ┌───────────────┐             ┌───────────────┐          ┌───────────────┐
│ Calibration  │          │ In-App Gaze   │             │ Section       │          │ Cloud History │
│ & Validation │          │ Control Deck  │             │ Attention Lab │          │ & Privacy Tab │
│              │          │               │             │               │          │               │
│ • 5-Pt Fit + │          │ • Error-Radius│             │ • Layout Box  │          │ • Device-Tag  │
│   4-Pt Test  │          │   Reticle HUD │             │   Dwell Meter │          │   Profiles    │
│ • 2x6 Ridge  │          │ • Hysteresis  │             │ • Coarse Zone │          │ • Sync Toggle │
│   Regression │          │   Long-Blink  │             │   Heatmap &   │          │   & One-Tap   │
│ • Error (mm) │          │   & TTS Tiles │             │   Cluster Path│          │   Cloud Wipe  │
└──────┬───────┘          └───────┬───────┘             └───────┬───────┘          └───────┬───────┘
       └──────────────────────────┴──────────────┬──────────────┴──────────────────────────┘
                                                 ▼
                                 ┌───────────────────────────────┐
                                 │     EyeTrackingViewModel      │
                                 │ • 2x6 Matrix Projection & EMA │
                                 │ • Blink Hysteresis State Mach │
                                 │ • Section Bounding-Box HitTest│
                                 └───────┬───────────────┬───────┘
                                         │               │
                      ┌──────────────────┘               └──────────────────┐
                      ▼                                                     ▼
      ┌───────────────────────────────┐                     ┌───────────────────────────────┐
      │   CameraGazeAnalyzerEngine    │                     │     EyeTrackingRepository     │
      │ • CameraX YUV ImageAnalysis   │                     │ • Firestore Custom DB Instance│
      │ • ML Kit Face Contours + Pose │                     │ • /users/{uid}/calibrations   │
      │ • Eye-ROI Iris Centroid &     │                     │ • /users/{uid}/sessions       │
      │   Glare/Distance Diagnostics  │                     │ • Batch Delete / Cloud Erasure│
      └───────────────────────────────┘                     └───────────────────────────────┘
```

### Data Model & State

- **`CalibrationProfile`** (`/users/{userId}/calibrations/{profileId}`):
  - `id`: Document ID (`@DocumentId`)
  - `userId`: Owner UID (`String`, validated against `request.auth.uid`)
  - `name`: Profile label (`String`, e.g., `"Desk Portrait"`)
  - `deviceModel`: Android device identifier (`String`, e.g., `Build.MODEL`) to warn on cross-device geometry mismatch
  - `matrixCoeffs`: 12-element `List<Double>` representing the flattened $2 \times 6$ regression matrix $\mathbf{W}$ mapping $[1, g_x, g_y, \text{yaw}, \text{pitch}, \text{roll}]^T \mapsto (x_s, y_s)$
  - `baselineEyeDistancePx`: Reference inter-ocular distance (`Double`) captured during calibration to detect head-distance drift
  - `meanErrorMm`: Held-out 4-point validation error in millimeters (`Double`)
  - `meanErrorPct`: Held-out 4-point validation error as normalized screen percentage (`Double`)
  - `closeBlinkThreshold`: Lower eye-open probability threshold (`Double`, default `0.25`)
  - `openBlinkThreshold`: Upper hysteresis recovery threshold (`Double`, default `0.55`)
  - `intentionalBlinkMinMs`: Minimum closure duration in ms for command blink (`Long`, default `350L`)
  - `isActive`: Boolean flag indicating active profile (`Boolean`)
  - `createdAt`, `updatedAt`: Nullable `@ServerTimestamp Timestamp?`

- **`TrackingSession`** (`/users/{userId}/sessions/{sessionId}`):
  - `id`: Document ID (`@DocumentId`)
  - `userId`: Owner UID (`String`)
  - `title`: Session title (`String`)
  - `deviceModel`: Device model (`String`)
  - `contentType`: `"READING_SECTIONS"` or `"AAC_CONTROL_DECK"` (`String`)
  - `durationSeconds`: Active session duration (`Int`)
  - `naturalBlinkCount`: Involuntary blinks (`100–300 ms`) counted (`Int`)
  - `intentionalBlinkCount`: Command blinks (`350–800 ms`) triggered (`Int`)
  - `blinksPerMinute`: Computed natural blink rate (`Double`)
  - `effectiveWordsPerMinute`: Passage word count divided by active reading minutes (`Int`)
  - `fixationClusterCount`: Number of dispersion-clustered fixation centroids (`Int`)
  - `topZonePercentage`, `middleZonePercentage`, `bottomZonePercentage`: Screen third dwell distribution (`Int`, summing to 100)
  - `sectionDwellJson`: Serialized JSON map of passage section IDs to dwell milliseconds and percentages (`String`)
  - `heatmapClustersJson`: Serialized JSON list of coarse fixation cluster centroids `(x, y, dwellWeightMs)` (`String`)
  - `createdAt`, `updatedAt`: Nullable `@ServerTimestamp Timestamp?`

### Interactive Component & State Mapping
1. **Calibration & Validation Mathematical Pipeline**:
   - During the 5 fitting targets, the app collects feature vectors $\mathbf{f}_i = [1, g_{x,i}, g_{y,i}, \text{yaw}_i, \text{pitch}_i, \text{roll}_i]^T$ and target screen points $\mathbf{y}_i = [x_{s,i}, y_{s,i}]^T$.
   - Computes $\mathbf{W} = \mathbf{Y}\mathbf{F}^T(\mathbf{F}\mathbf{F}^T + \lambda \mathbf{I})^{-1}$ with Ridge regularization $\lambda = 10^{-3}$ so collinear posture samples never cause singular matrix inversion.
   - Immediately transitions to the 4 held-out validation points $(0.28, 0.28), (0.72, 0.28), (0.28, 0.72), (0.72, 0.72)$, measuring physical millimeter error via screen DPI and storing `meanErrorMm` and `meanErrorPct`.
2. **Blink Hysteresis & Duration State Machine**:
   - Tracks combined eye-open probability $p_{\text{open}} = \frac{1}{2}(p_L + p_R)$.
   - Transitions `OPEN` $\rightarrow$ `CLOSING` when $p_{\text{open}} < 0.25$ at timestamp $t_0$.
   - While closed, emits live closure duration $\Delta t = t - t_0$ so the HUD shows a filling blink-hold bar once $\Delta t \ge 350\text{ ms}$.
   - When $p_{\text{open}} > 0.55$ (hysteresis recovery):
     - If $80\text{ ms} \le \Delta t < 350\text{ ms}$: classifies as **Natural Blink** (increments blink-rate telemetry only).
     - If $350\text{ ms} \le \Delta t \le 900\text{ ms}$: classifies as **Intentional Command Blink** (fires focused tile action).
     - If $\Delta t > 900\text{ ms}$: classifies as extended eye closure / rest (cancels trigger to prevent accidental activation).
3. **Privacy & Cloud Erasure Controls**:
   - Toggling `cloudSyncEnabled = false` keeps new calibration matrices and session analytics strictly in local ViewModel state without writing to Firestore.
   - Triggering `deleteAllCloudData()` queries `/users/{uid}/calibrations` and `/users/{uid}/sessions` and deletes every document with structured error handling (`handleFirestoreError`).
