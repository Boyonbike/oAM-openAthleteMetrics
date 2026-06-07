plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)   // code-generator for Room + Hilt (replaces KAPT)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.athletedata.app"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.athletedata.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // AGP 9+ automatically propagates this Java target to the Kotlin JVM target
    }

    buildFeatures {
        compose = true
        buildConfig = true  // exposes BuildConfig.DEBUG for the seeder guard
    }
}

dependencies {

    // ── Compose BOM ────────────────────────────────────────────────────────
    // All androidx.compose.* artifacts below pick their version from this BOM.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)

    // ── AndroidX core ──────────────────────────────────────────────────────
    implementation(libs.androidx.core.ktx)

    // ── Lifecycle / ViewModel ──────────────────────────────────────────────
    // lifecycle-runtime-ktx: repeatOnLifecycle, lifecycleScope
    // viewmodel-compose:     viewModel() composable, LocalViewModelStoreOwner
    // runtime-compose:       collectAsStateWithLifecycle()
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // ── Navigation ─────────────────────────────────────────────────────────
    // Compose-native screen navigation with back-stack management.
    implementation(libs.androidx.navigation.compose)

    // ── Room ───────────────────────────────────────────────────────────────
    // room-runtime: core database engine
    // room-ktx:     suspend + Flow query support
    // room-compiler: generates DAO implementations at build time (KSP)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // ── Hilt ───────────────────────────────────────────────────────────────
    // hilt-android:              @HiltAndroidApp, @AndroidEntryPoint, @HiltViewModel
    // hilt-compiler:             generates injection code at build time (KSP)
    // hilt-navigation-compose:   hiltViewModel() composable integration
    // hilt-work:                 @HiltWorker runtime + HiltWorkerFactory
    // androidx.hilt-compiler:    generates @HiltWorker injection code (separate from hilt-compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // ── Coroutines ─────────────────────────────────────────────────────────
    // Async primitives used throughout: Flow, suspend, Dispatchers.IO
    implementation(libs.kotlinx.coroutines.android)

    // ── DataStore ──────────────────────────────────────────────────────────
    // Type-safe key-value store backed by Protobuf files. Used for theme prefs.
    implementation(libs.androidx.datastore.preferences)

    // ── WorkManager ────────────────────────────────────────────────────────
    // Deferred, constraint-aware background work. Used for DailySummaryWorker.
    implementation(libs.androidx.work.runtime.ktx)

    // ── Timber ─────────────────────────────────────────────────────────────
    implementation(libs.timber)

    // ── Vico charts ────────────────────────────────────────────────────────
    // Compose-native charting library.
    // compose:    core chart composables
    // compose-m3: Material 3 theming integration
    // core:       shared data models (pulled transitively, explicit for clarity)
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)
    implementation(libs.vico.core)

    // ── Testing ────────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
