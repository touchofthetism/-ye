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

## Tests and CI

- Unit and Robolectric tests: `gradle :app:testDebugUnitTest`. The Firestore repository tests need the Firebase emulators; run them with `npx firebase-tools emulators:exec --only firestore,auth --project demo-no-project "gradle :app:testDebugUnitTest"`. Without the emulators those tests are skipped.
- Firestore security rules: `npx firebase-tools emulators:exec --only firestore --project demo-no-project "node --test firestore.test.js"` (needs `@firebase/rules-unit-testing`).
- Benchmarks (opt-in): `gradle :app:testDebugUnitTest -Pbenchmark --tests 'com.example.benchmark.PipelineBenchmarkTest'`. Results are written to `app/build/benchmark/benchmark-results.json`.
- CI (`.github/workflows/android-apk.yml`) signs debug builds from the `DEBUG_KEYSTORE_BASE64` repository secret. Without it, CI uses a throwaway key that is never published. Google sign-in only works with a key whose SHA-1 is registered for the Firebase Android app. Never commit `debug.keystore`, `*.jks` or `.env`.
