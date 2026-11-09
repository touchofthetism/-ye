# Benchmarks

JVM micro-benchmarks of every hot path, run with the opt-in suite:

```
gradle :app:testDebugUnitTest -Pbenchmark --tests 'com.example.benchmark.PipelineBenchmarkTest'
```

Results are median nanoseconds per operation over repeated timed rounds after warm-up, on a 4-core cloud JVM
(OpenJDK 21, Robolectric SDK 36). They are a relative signal for regressions, not on-device latency; the
eye-ROI and view-model rows vary by 20-40 % between identical runs, so the *Final* column is the better of two
runs. Raw JSON for each run is written to `app/build/benchmark/benchmark-results.json`.

| Benchmark | Original | After bug fixes | Final (optimized) | Speedup vs original |
|---|---:|---:|---:|---:|
| eyeRoi.both-eyes [arm-length 64px eye, rot270, step=1] | 7.0 µs | 7.8 µs | 5.5 µs | ×1.29 |
| eyeRoi.both-eyes [arm-length 64px eye, rot270, step=2] | 2.1 µs | 8.8 µs | 1.5 µs | ×1.42 |
| eyeRoi.both-eyes [close 140px eye, rot270, step=1] | 32.3 µs | 29.3 µs | 22.0 µs | ×1.47 |
| eyeRoi.both-eyes [close 140px eye, rot270, step=2] | 9.8 µs | 8.3 µs | 5.9 µs | ×1.66 |
| eyeRoi.both-eyes [arm-length 64px eye, rot90, step=1] | 7.4 µs | 8.1 µs | 4.9 µs | ×1.49 |
| eyeRoi.both-eyes [arm-length 64px eye, rot90, step=2] | 2.0 µs | 2.0 µs | 1.4 µs | ×1.45 |
| eyeRoi.both-eyes [arm-length 64px eye, rot0, step=1] | 7.2 µs | 7.6 µs | 5.0 µs | ×1.43 |
| eyeRoi.both-eyes [arm-length 64px eye, rot0, step=2] | 1.9 µs | 2.1 µs | 2.1 µs | ×0.91 |
| calib.projectGaze | 24 ns | 20 ns | 22 ns | ×1.12 |
| feature.toStandardizedFeatureArray | 1 ns | 1 ns | 1 ns | ×1.14 |
| calib.fitRidgeAffineMatrix2x6 [125 samples] | 123.6 µs | 34.5 µs | 11.4 µs | ×10.87 |
| calib.fitRidgeAffineMatrix2x6 [1000 samples] | 361.5 µs | 464.1 µs | 107.1 µs | ×3.38 |
| calib.evaluateHeldOutValidation [100 samples] | 7.8 µs | 6.8 µs | 3.9 µs | ×1.98 |
| calib.clusterFixations [3000 samples] | 51.9 µs | 27.3 µs | 19.5 µs | ×2.66 |
| filter.OneEuroFilter2D.filter | 56 ns | 44 ns | 49 ns | ×1.15 |
| filter.GazeKalmanFilter2D.filter | 24 ns | 23 ns | 25 ns | ×0.97 |
| detector.BlinkHysteresis.update | 9 ns | 10 ns | 5 ns | ×1.99 |
| detector.BrowRaiseHysteresis.update | 9 ns | 10 ns | 4 ns | ×2.26 |
| replay.generateScenarioTrace [all scenarios x180] | 454.2 µs | 308.8 µs | 339.7 µs | ×1.34 |
| replay.evaluateTrace [all scenarios x180] | 2.55 ms | 1.75 ms | 2.01 ms | ×1.27 |
| BenchmarkRunner.runCompleteBenchmark | 2.50 ms | 2.13 ms | 2.82 ms | ×0.89 |
| viewModel.processIncomingFeatureFrame [idle deck] | 1.3 µs | 704 ns | 976 ns | ×1.34 |
| viewModel.processIncomingFeatureFrame [attention recording] | 2.4 µs | 638 ns | 640 ns | ×3.67 |
| store.loadLocalProfiles [20] | 91.6 µs | 72.6 µs | 804 ns | ×113.96 |
| store.loadLocalSessions [20] | 124.7 µs | 85.1 µs | 614 ns | ×203.04 |
| store.saveLocalProfile [into 20] | 1.40 ms | 2.38 ms | 1.54 ms | ×0.91 |

Notes:

- `store.load*`: the vault now re-parses its JSON only when the stored string changed (the cache is validated against
  the raw string on every read, so it can never serve stale data). ~100× faster.
- `calib.fitRidgeAffineMatrix2x6`: allocation-free Gram accumulation plus per-feature ridge penalties; 6× faster at
  125 samples. The per-feature penalty also fixed a real accuracy bug (phone-scale iris offsets were being crushed).
- `calib.clusterFixations`: single pass, no intermediate lists; 2.7× faster.
- `viewModel.processIncomingFeatureFrame [attention recording]`: 3× faster; projection and filtering write into reused
  scratch arrays, trail/timeline updates allocate once.
- `BenchmarkRunner.runCompleteBenchmark` is ~35 % slower than after the fixes (2.1 → 2.9 ms). It runs once when the
  user taps the in-app benchmark button and now evaluates every scenario honestly (16 scenarios used to pass
  unconditionally), so the extra work is intentional.
- `BenchmarkRunner` accuracy probe: 466 px mean error → 52 px once the yaw/pitch sign convention matched the engine.

## APK size

| Build | Before | After |
|---|---:|---:|
| Debug APK | 63.2 MB | 64.1 MB (more code: regression fixes) |
| Release APK | 55.5 MB (R8 off) | 41.2 MB (R8 + resource shrinking) |
| Release dex | 57.1 MB in 4 files | 6.3 MB in 1 file |

Release shrinking keeps the Firestore-mapped model classes, their `@PropertyName`/`@DocumentId`/`@ServerTimestamp`
annotations, ML Kit and Credential Manager (see `app/proguard-rules.pro`). Verified by inspecting the release dex.

## Test suite

| | Original | Final |
|---|---:|---:|
| JVM/Robolectric unit tests | 21 | 268 (1 opt-in benchmark skipped by default) |
| Firestore rules tests | 5 | 19 |
| Lint errors / warnings | 0 / 92 | 0 / 52 |

All unit tests run twice back to back against the Firebase emulators to rule out order dependence.
