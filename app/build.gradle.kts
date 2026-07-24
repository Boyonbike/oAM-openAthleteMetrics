plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)   // code-generator for Room + Hilt (replaces KAPT)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.athletedata.openAthleteMetrics"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.athletedata.openAthleteMetrics"
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

    // Without this, Robolectric's simulated AssetManager sees none of AGP's merged
    // resources/assets for unit tests (needed by MigrationTestHelper's schema-json lookup below).
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        compose = true
        buildConfig = true  // exposes BuildConfig.DEBUG for the seeder guard
    }

    // Exposes the exported Room schema JSONs (app/schemas) to Robolectric-based unit tests so
    // MigrationTestHelper can validate a real MIGRATION_x_y against them. Robolectric's simulated
    // AssetManager (via testOptions.unitTests.isIncludeAndroidResources above) only sees assets
    // merged into the *debug* variant itself (per AGP's generated test_config.properties -- there
    // is no separate "unit test only" merged-assets set) -- so this must be the `debug` source
    // set, not `test`. Scoped to `debug` (not `main`), these never ship in a release build.
    sourceSets {
        getByName("debug") {
            assets.srcDirs("$projectDir/schemas")
        }
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

    // ── Glance (home-screen widgets) ──────────────────────────────────────
    // Compose-like API for building AppWidgets, used instead of the older
    // RemoteViews/AppWidgetProvider API.
    implementation(libs.androidx.glance.appwidget)

    // ── Timber ─────────────────────────────────────────────────────────────
    implementation(libs.timber)

    // ── Chicory (pure-Java WASM interpreter) ───────────────────────────────
    // Used for driver sandboxing. Pure-Java — no native .so required.
    implementation(libs.chicory.runtime)

    // ── kotlinx.serialization ──────────────────────────────────────────────
    // Used to deserialise driver manifest JSON files into Kotlin data classes.
    implementation(libs.kotlinx.serialization.json)

    // ── Vico charts ────────────────────────────────────────────────────────
    // Compose-native charting library.
    // compose:    core chart composables
    // compose-m3: Material 3 theming integration
    // core:       shared data models (pulled transitively, explicit for clarity)
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)
    implementation(libs.vico.core)

    // ── Reorderable (drag-and-drop for LazyVerticalGrid) ──────────────────
    implementation(libs.reorderable)

    // ── Testing ────────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
    testImplementation(libs.robolectric)
    // Migration testing: MigrationTestHelper (room-testing) + InstrumentationRegistry (test:core),
    // the latter supported by Robolectric under plain JVM unit tests.
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    // WorkManager testing: TestListenableWorkerBuilder / WorkManagerTestInitHelper, for
    // exercising MetricStatsRecomputeWorker/DailySummaryWorker synchronously in Robolectric.
    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
