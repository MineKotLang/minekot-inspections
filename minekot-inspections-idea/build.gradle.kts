import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.tasks.GenerateModuleMetadata

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform)
}

repositories {
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":minekot-inspections-core"))
    intellijPlatform {
        intellijIdea(libs.versions.idea.version.get())
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.vintage.engine)
}

afterEvaluate {
    extensions.configure<PublishingExtension> {
        publications.named<MavenPublication>("mavenJava") {
            setArtifacts(emptyList<Any>())
            artifact(tasks.named("jar")) {
                classifier = null
            }
            artifact(tasks.named("sourcesJar"))
            artifact(tasks.named("javadocJar"))
        }
    }
}

// IntelliJ Platform contributes a classifier-only Java variant. Maven consumers need the adapter JAR as the
// canonical artifact, so this publication intentionally uses its POM instead of misleading Gradle variant metadata.
tasks.withType<GenerateModuleMetadata>().configureEach {
    enabled = false
}
