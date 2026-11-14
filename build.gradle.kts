// Root build script: plugin declarations only. Every plugin is resolved from the
// version catalog and applied in the module that needs it (see app/build.gradle.kts).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
