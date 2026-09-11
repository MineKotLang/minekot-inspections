import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
}

val privateRuntimeJar = tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("private")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    filesMatching(listOf("META-INF/services/**", "META-INF/*.kotlin_module")) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    mergeServiceFiles()

    relocate("dev.sigstore", "org.minekot.inspections.loader.runtime.internal.sigstore")
    relocate("com.google", "org.minekot.inspections.loader.runtime.internal.google")
    relocate("io.grpc", "org.minekot.inspections.loader.runtime.internal.grpc")
    relocate("io.opencensus", "org.minekot.inspections.loader.runtime.internal.opencensus")
    relocate("io.perfmark", "org.minekot.inspections.loader.runtime.internal.perfmark")
    relocate("org.bouncycastle", "org.minekot.inspections.loader.runtime.internal.bouncycastle")
    relocate("org.apache", "org.minekot.inspections.loader.runtime.internal.apache")
    relocate("org.erdtman", "org.minekot.inspections.loader.runtime.internal.erdtman")
    relocate("org.jspecify", "org.minekot.inspections.loader.runtime.internal.jspecify")
    relocate("kotlinx.serialization", "org.minekot.inspections.loader.runtime.internal.serialization")

    dependencies {
        exclude(dependency("org.jetbrains.kotlin:.*"))
        exclude(dependency("org.jetbrains:annotations"))
        exclude(dependency("org.minekot.inspections:minekot-inspections-loader"))
    }
}

@Suppress("GradleDslConventions")
val verifyPrivateRuntimeJar = tasks.register("verifyPrivateRuntimeJar") {
    dependsOn(privateRuntimeJar)
    inputs.file(privateRuntimeJar.flatMap { task -> task.archiveFile })
    doLast {
        val forbiddenPrefixes = listOf(
            "dev/sigstore/",
            "com/google/",
            "io/grpc/",
            "kotlin/",
            "kotlinx/serialization/",
            "org/jetbrains/kotlin/",
        )
        val entries = ZipFile(inputs.files.singleFile).use { archive ->
            archive.entries().asSequence().map { entry -> entry.name }.toList()
        }
        forbiddenPrefixes.forEach { prefix ->
            check(entries.none { entry -> entry.startsWith(prefix) }) {
                "Private loader runtime contains unrelocated namespace ${prefix}."
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyPrivateRuntimeJar)
}

afterEvaluate {
    extensions.configure<PublishingExtension> {
        publications.named<MavenPublication>("mavenJava") {
            artifact(privateRuntimeJar)
        }
    }
}

dependencies {
    api(project(":minekot-inspections-loader"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sigstore.java)

    testImplementation(libs.kotlin.test)
}
