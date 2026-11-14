import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.time.Duration
import org.gradle.api.tasks.PathSensitivity

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.oculagaze.rxkmpq"
    minSdk = 24
    targetSdk = 36
    versionCode = 5
    versionName = "5.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      // R8 + resource shrinking: measured 55.5 MB -> ~41 MB release APK (57 MB -> ~6 MB of dex).
      // proguard-rules.pro keeps the Firestore-mapped models, ML Kit and Credential Manager classes.
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
  // No code reads a Gemini key; keep it out of BuildConfig so a key set in .env never ships in the APK.
  ignoreList.add("GEMINI_API_KEY")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  // implementation(libs.androidx.room.ktx)
  // implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  // implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  implementation(libs.firebase.firestore)
  implementation(libs.firebase.auth)
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.play.services)
  implementation(libs.googleid)
  implementation(libs.mlkit.face.detection)
  // implementation(libs.firebase.appcheck.recaptcha)
  // implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  // implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  // implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  // "ksp"(libs.androidx.room.compiler)
  // "ksp"(libs.moshi.kotlin.codegen)
}

// Opt-in performance benchmarks: ./gradlew :app:testDebugUnitTest -Pbenchmark --tests '*PipelineBenchmarkTest*'
// "-Pbenchmark" (empty) or "-Pbenchmark=true" enables them; "-Pbenchmark=false" or no property keeps them off.
val benchmarkEnabled: Boolean =
  providers.gradleProperty("benchmark").map { it.isEmpty() || it.equals("true", ignoreCase = true) }.getOrElse(false)

tasks.withType<Test> {
  systemProperty("oculagaze.benchmark", benchmarkEnabled.toString())
  failFast = true
  // Task-wide limit (not per test): a cold run downloads the Robolectric SDK 36 jar and makes emulator round trips.
  timeout.set(Duration.ofMinutes(if (benchmarkEnabled) 30 else 20))
  // EyeTrackingRepositoryRuleTest / RoundTripTest and regression.CloudEmulatorRegressionTest exercise firestore.rules
  // through the Firebase emulator, so the rules and emulator config are test inputs, and a cached or up-to-date result
  // must never stand in for a run. Gradle does not manage the emulator: when you run one yourself, restart it after
  // editing firestore.rules (its hot reload did not apply the new rules to the named database). CI starts a fresh
  // emulator per run with `firebase emulators:exec`.
  inputs.files(rootProject.file("firestore.rules"), rootProject.file("firebase.json"))
    .withPathSensitivity(PathSensitivity.RELATIVE)
    .withPropertyName("firebaseEmulatorConfig")
  outputs.cacheIf("emulator-backed tests depend on external emulator state") { false }
  outputs.upToDateWhen { false }
  testLogging {
    events("passed", "failed", "skipped")
  }
}
