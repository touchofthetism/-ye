# OculaGaze — Build Instructions for Google AI Studio

**How to use this:** Open Google AI Studio → **Build** → start a new app → paste the **Master Prompt** (Part 1) as your first message. Let it finish, test it, then send the **Follow-up Prompts** (Part 2) one at a time, testing after each. Part 3 is your on-phone acceptance checklist.

**One important translation:** AI Studio builds *web* apps, not native Android. That turns out to help this project: the web version of MediaPipe (Face Landmarker) provides **true iris landmarks and blink blendshapes built in** — the exact ingredient the original plan was missing. The math, calibration protocol, validation, and privacy design from the audited plan carry over unchanged. Test on your Pixel 10 Pro XL by opening the app's preview/deployed URL in Chrome on the phone and allowing camera access.

---

## Part 1 — Master Prompt (paste this first)

```
Build a web app called "OculaGaze" — a camera-based, region-level gaze interaction and attention analytics tool. It runs entirely in the browser on a phone's front camera. Be honest in all UI copy: this is coarse region-level gaze (large targets), never claim precision eye tracking.

STACK
- React + TypeScript + Tailwind (dark theme: background #0B0F17, surfaces #151D2E, primary cyan #00E5FF, success emerald #10B981, warning gradient amber #F59E0B to red #EF4444; tabular monospace numerals for all telemetry).
- @mediapipe/tasks-vision FaceLandmarker, running in a Web Worker if feasible, otherwise on the main thread with frame throttling. Use the GPU delegate first, fall back to CPU automatically if GPU init fails. Load the model from the standard MediaPipe CDN/storage URL.
- Storage abstraction with two backends behind one interface: (1) LOCAL: IndexedDB — always available, this is the default and must work with zero configuration; (2) CLOUD: Firebase Auth (Google sign-in) + Firestore under /users/{uid}/calibrations and /users/{uid}/sessions — only active if the user signs in AND turns on an explicit "Cloud Sync" toggle. If Firebase config is absent, hide/disable the cloud toggle with a note. Profiles must persist locally across sessions even when cloud sync is off.

CAMERA & INFERENCE
- getUserMedia front camera ("user" facing), request 1280x720. Adaptive quality: measure sustained inference FPS; if it drops below 12 FPS for more than 3 seconds, step down to 640x480 and show a "Performance mode" badge; if it recovers above 20 FPS, step back up. Never freeze the UI while adapting.
- Per frame, from FaceLandmarker output extract:
  - Iris centers (landmarks 473 right, 468 left) normalized within each eye's corner landmarks (left eye corners 33/133, right eye 362/263) → per-eye (gx, gy) in 0..1.
  - Blink: blendshape scores eyeBlinkLeft / eyeBlinkRight → per-eye open probability = 1 - blinkScore.
  - Head pose: yaw/pitch/roll derived from the facial transformation matrix.
  - Inter-ocular pixel distance (drift detection).
- PER-EYE FUSION (important): compute a per-eye quality weight from landmark presence and blink-score stability. Fuse the two eyes' (gx, gy) by weighted average. If one eye is missing or low quality, use the other eye alone and set a "single-eye mode" flag shown in diagnostics. Never average in a garbage eye.

CALIBRATION (5-point fit + 4-point held-out validation)
- Full-screen flow. Phase A: 5 targets (center, 4 corners). At each target: wait 400 ms (discard transition frames), then collect ~1.5 s of feature samples and take the median feature vector. Feature vector f = [1, gx, gy, yaw, pitch, roll].
- Fit: standardize features (store per-feature mean/std in the profile), then ridge regression to a 2x6 matrix W mapping f → (x, y) in normalized screen coords. Implement the small linear algebra (6x6 solve) directly; lambda = 1e-3 applied AFTER standardization.
- Phase B: 4 held-out validation targets at (0.28, 0.28), (0.72, 0.28), (0.28, 0.72), (0.72, 0.72). Compute per-point error, MEAN error, and MAX error. Report mean error primarily as % of screen width; also show approximate mm (computed from CSS size and devicePixelRatio, labeled "approx."). Recommend grid: if mean error > 12% → 2x2 grid; else 3x3.
- Save the profile: name, W (12 numbers), feature scaler (means/stds), validation stats, baseline inter-ocular distance, device label (navigator.userAgent), timestamp. Save to the storage layer.

LIVE GAZE & INTERACTION
- Gaze point = W applied to standardized live features. Draw it as a translucent CONFIDENCE CIRCLE whose radius equals the profile's mean validation error (not a pinpoint cursor). Smooth with a spring/lerp in a requestAnimationFrame loop decoupled from inference rate, so the reticle renders at full display refresh even when inference is slower.
- Blink state machine with hysteresis, exactly these windows: closure starts when fused open-probability < 0.45 and ends when it rises > 0.65. Duration 80–349 ms = NATURAL blink (counted in telemetry only). 350–800 ms = INTENTIONAL blink (fires the focused tile). Over 900 ms = rest/cancel (no fire). Show a filling "blink hold" progress indicator between 350–800 ms.
- Dwell trigger: reticle inside a tile for a configurable 800–1500 ms fills a radial ring and fires the tile.
- Two modes:
  1) AAC Control Deck: grid (2x2 or 3x3 per validation) of large phrase tiles (min 64dp-equivalent), fired by dwell or intentional blink; firing speaks the phrase via speechSynthesis, with visual ripple + navigator.vibrate feedback. Include a few default phrases and let the user edit tile text.
  2) Reading Lab: a multi-section article; each section registers its live bounding box (getBoundingClientRect, refreshed on scroll/resize). Accumulate per-section dwell ms and %, count natural vs intentional blinks, compute Effective WPM = passage words / active reading minutes, build fixation clusters with a simple dispersion-threshold clusterer over gaze samples, and render a Gaussian-splat heatmap on a canvas overlay plus a numbered cluster sequence. On stop, show the summary and save a session record.

DIAGNOSTICS HUD (persistent top pill + expandable panel)
- Live: inference FPS, face detected yes/no, single-eye mode flag, performance-mode badge, head-distance drift warning when inter-ocular distance deviates >15% from the calibration baseline, active profile name.

PRIVACY TAB
- Cloud Sync toggle (default OFF until user explicitly enables after sign-in), plain-language explanation that camera frames are processed on-device and never uploaded, and only derived numbers (matrices, session summaries) sync. List saved profiles and sessions with per-item delete, plus an "Erase all cloud data" button (disabled in local-only mode).

If any part of this is too large for one response, build the app shell + camera + calibration first and tell me what remains.
```

---

## Part 2 — Follow-up Prompts (send in order, after testing)

**Follow-up 1 — Session replay:**
```
Add session replay: in the History view, tapping a saved Reading Lab session re-renders its heatmap and numbered fixation-cluster sequence over a static copy of the article layout, with the session's per-section dwell %, effective WPM, and blink stats beside it.
```

**Follow-up 2 — PWA install:**
```
Make OculaGaze an installable PWA: web app manifest (name OculaGaze, dark theme color #0B0F17, standalone display), icons, and a service worker that caches the app shell and the MediaPipe model/WASM so the app loads offline after first use. Add an "Install" hint banner on Android Chrome when the install prompt is available.
```

**Follow-up 3 — Session thermal/perf guard:**
```
Add a long-session safeguard: track inference FPS over rolling 60-second windows during tracking sessions. If FPS degrades by more than 40% from the session's first-minute baseline (a sign of thermal throttling), show a non-blocking "device is warming up — accuracy may drop" notice and automatically enable Performance mode. Log the FPS curve into the saved session record.
```

**Follow-up 4 — Recalibration nudge:**
```
If, during a session, the live gaze points land outside the screen bounds or in a single corner for a sustained period (a sign the calibration geometry has drifted, e.g. the phone moved), show a gentle "Position changed — recalibrate?" prompt that opens the calibration flow without discarding the current session.
```

---

## Part 3 — Acceptance Checklist (test on the Pixel, in Chrome)

1. **Calibration completes** and shows a mean error % (and approx. mm) plus a grid recommendation. In good light, seated, phone steady on a desk: expect roughly 8–15%. Much worse = lighting or distance problem, not a code bug.
2. **Validation honesty:** the max-point error is shown, not just the mean.
3. **Natural blinks never fire tiles.** Blink normally for a minute in the AAC deck — zero activations. Then hold a blink ~half a second — the hold indicator fills and the tile fires and speaks.
4. **Dwell works:** stare at a tile ~1 second — ring fills, tile fires.
5. **Single-eye handling:** cover one eye briefly — diagnostics shows single-eye mode, reticle keeps working roughly.
6. **Sync-off privacy:** with Cloud Sync off, calibrate and run a session, close and reopen the app — profile and session are still there (local), and nothing was uploaded.
7. **Drift warning:** after calibrating, move the phone noticeably closer — the distance-drift warning appears.
8. **Long session:** run the Reading Lab for ~20 minutes — if the thermal notice (Follow-up 3) appears, that's the Tensor chip throttling; note the FPS curve in the saved session.

**Known limits to expect (not bugs):** dim light and glasses glare will widen the error circle; handheld walking use will drift; the app measures *regions*, so tiles smaller than the error circle will always feel unreliable — let the validation number choose the grid, not hope.
