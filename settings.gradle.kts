// -------===={ Project Configuration }====-------

rootProject.name = "minekot-inspections"

enableFeaturePreview("STABLE_CONFIGURATION_CACHE")
enableFeaturePreview("GROOVY_COMPILATION_AVOIDANCE")

// -------===={ Subprojects }====-------

include("minekot-inspections-core")
include("minekot-inspections-detekt")
include("minekot-inspections-idea")
include("minekot-inspections-loader")
include("minekot-inspections-loader-runtime")

// -------===={ Plugin Management }====-------

pluginManagement {
    repositories {
        maven("https://maven2.minekot.org/releases/")
        maven("https://maven2.minekot.org/snapshots/")
        gradlePluginPortal()
        mavenLocal()
        mavenCentral()
    }
}

// -------===={ Plugins }====-------

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
    id("com.gradle.develocity") version "4.3.2"
}

// -------===={ Plugin Configuration }====-------

develocity {
    buildScan {
        termsOfUseUrl = "https://gradle.com/help/legal-terms-of-use"
        termsOfUseAgree = "yes"
        publishing.onlyIf { true }
    }
}
