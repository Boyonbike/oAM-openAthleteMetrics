// Top-level build file — plugin declarations only, apply false here.
// Each plugin is applied in the module that needs it (app/build.gradle.kts).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose)      apply false
    alias(libs.plugins.ksp)                 apply false
    alias(libs.plugins.hilt)                apply false
}
