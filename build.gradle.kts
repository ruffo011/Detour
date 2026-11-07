// Root build file: declares plugin versions only. Each module applies what it needs.
// AGP 9 compiles Kotlin itself ("built-in Kotlin"); declaring kotlin-jvm here also pins
// the Kotlin Gradle plugin version that AGP uses.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
