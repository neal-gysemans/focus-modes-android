// Root build file: declare plugins without applying them so the version catalog
// is the single place versions live.
//
// Note the absence of org.jetbrains.kotlin.android — AGP 9 has built-in Kotlin
// support and rejects the standalone Kotlin Android plugin outright.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
