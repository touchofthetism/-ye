<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/d0cf7547-6eb4-4be2-8887-5894b4be11fe

## Run Locally

**Prerequisites:**  [Android Studio](https://developer.android.com/studio)


1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. Optional: copy `.env.example` to `.env`. The app makes no Gemini calls, so no API key is needed; the build ignores `GEMINI_API_KEY`.
5. Remove this line from the app's `build.gradle.kts` file: `signingConfig = signingConfigs.getByName("debugConfig")`
6. Run the app on an emulator or physical device
7. If you have already published your app in AI Studio, please [request upload key reset](https://support.google.com/googleplay/android-developer/answer/9842756#zippy=%2Crequest-an-upload-key-reset) in Google Play Console.

## Enrollment (per-user face & eye scan)

Before calibrating, the Calibrate tab offers a ~15-second **face & eye enrollment scan** that personalizes blink and brow detection to the person instead of the fixed defaults (blink close 0.45 / open 0.65, command blink >= 350 ms, brow 0.34 / 0.42). The scan positions the face, measures the eyes open (and the natural blinks that happen meanwhile), the eyes closed, and an optional brow raise, then derives the thresholds (`domain/EnrollmentScan.kt`). A full-screen overlay (`ui/components/EnrollmentScanOverlay.kt`) gives one plain-English instruction at a time around a centred fixation dot; the result and a Re-scan / Clear control live in the Enrollment card of `ui/screens/CalibrationStudioScreen.kt`.

Honesty and privacy rules: the scan refuses simulated / emulator frames (it needs the live front camera), never invents a measurement (a skipped brow step is stored as "not measured"), and keeps only derived numbers (`EnrollmentBaseline`), on this device only (`local_enrollment_baseline_<uid>` in the on-device vault). No images are stored and nothing is synced to Firestore. Switching away from the Calibrate tab or starting the calibration wizard cancels a running scan.

## Tests and CI

- Unit and Robolectric tests: `gradle :app:testDebugUnitTest`. The Firestore repository tests need the Firebase emulators; run them with `npx firebase-tools emulators:exec --only firestore,auth --project demo-no-project "gradle :app:testDebugUnitTest"`. Without the emulators those tests are skipped.
- Firestore security rules: `npx firebase-tools emulators:exec --only firestore --project demo-no-project "node --test firestore.test.js"` (needs `@firebase/rules-unit-testing`).
- Benchmarks (opt-in): `gradle :app:testDebugUnitTest -Pbenchmark --tests 'com.example.benchmark.PipelineBenchmarkTest'`. Results are written to `app/build/benchmark/benchmark-results.json`.
- CI (`.github/workflows/android-apk.yml`) signs debug builds from the `DEBUG_KEYSTORE_BASE64` repository secret. Without it, CI uses a throwaway key that is never published. Google sign-in only works with a key whose SHA-1 is registered for the Firebase Android app. Never commit `debug.keystore`, `*.jks` or `.env`.
