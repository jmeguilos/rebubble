// Top-level build file where you can add configuration options common to all sub-projects/modules.

// AGP 9's built-in Kotlin support ships with its own default Kotlin Gradle Plugin (KGP)
// version. Pin it explicitly to the Kotlin version declared in the version catalog so the
// Compose compiler / serialization compiler plugins below don't get applied against a
// mismatched Kotlin compiler. See https://kotl.in/gradle/agp-built-in-kotlin.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:${libs.versions.ksp.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt.android) apply false
}
