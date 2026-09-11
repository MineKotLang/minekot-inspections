import org.minekot.toolchain.MineKotToolchainExtension
import org.gradle.api.component.AdhocComponentWithVariants
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import java.util.jar.JarFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.minekot.toolchain)
    alias(libs.plugins.binary.compatibility.validator)
}

group = project.findProperty("group") ?: "org.minekot.inspections"
version = project.findProperty("version") ?: "1.0.0-SNAPSHOT"

val projectJavaVersion = 21
val mavenPublicationProjects = setOf(
    ":minekot-inspections-core",
    ":minekot-inspections-detekt",
    ":minekot-inspections-idea",
    ":minekot-inspections-loader",
    ":minekot-inspections-loader-runtime",
)
val mavenPublicationDescriptions = mapOf(
    ":minekot-inspections-core" to "Host-neutral MineKot inspection SPI and guarded correction model.",
    ":minekot-inspections-detekt" to "Detekt adapter for dynamically loaded MineKot inspections.",
    ":minekot-inspections-idea" to "IntelliJ Platform adapter for dynamically loaded MineKot inspections.",
    ":minekot-inspections-loader" to "PSI-free contracts for resolving and activating MineKot rule releases.",
    ":minekot-inspections-loader-runtime" to
        "Verified release transport, cache, and Sigstore runtime for MineKot rules.",
)
val stagedMavenRepository = layout.buildDirectory.dir("staged-maven")
val stagedPublicationVersion = version.toString()

@Suppress("GradleDslConventions")
val cleanStagedMavenRepository = tasks.register<Delete>("cleanStagedMavenRepository") {
    group = "publishing"
    description = "Removes stale files from the local staged Maven repository."
    delete(stagedMavenRepository)
}

@Suppress("GradleDslConventions")
val cleanFinalArtifacts = tasks.register<Delete>("cleanFinalArtifacts") {
    description = "Cleans the final directory with a backup of the previous state"
    val finalFile = layout.projectDirectory.dir("final").asFile
    val backupFile = layout.projectDirectory.dir("final_bak").asFile

    doFirst {
        if (finalFile.listFiles()?.isEmpty() != false) return@doFirst

        backupFile.deleteRecursively()
        finalFile.copyRecursively(backupFile, overwrite = true)
    }
    delete(finalFile)
}

@Suppress("AvoidApplyPluginMethod", "GradleDslConventions")
allprojects {
    version = rootProject.version
    val libs = rootProject.libs
    extra["projectJavaVersion"] = projectJavaVersion

    apply(plugin = "org.minekot.toolchain")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    if (path == ":minekot-inspections-core" || path == ":minekot-inspections-loader-runtime") {
        apply(plugin = "jacoco")
    }

    if (path in mavenPublicationProjects) {
        extensions.configure<KotlinJvmProjectExtension> {
            explicitApi()
        }
    }

    repositories {
        mavenCentral()
    }

    pluginManager.withPlugin("org.minekot.toolchain") {
        println("Applying MineKot Toolchain to ${this@allprojects.path}")
        extensions.configure<MineKotToolchainExtension> {
            toolchainVersion = libs.versions.minekot.toolchain
            build {
                javaVersion = projectJavaVersion
                allWarningsAsErrors = true
            }
            publishing {
                enabled = (path in mavenPublicationProjects)
                minekotRepository = (path in mavenPublicationProjects)
                releasesUrl = "https://maven2.minekot.org/releases"
                snapshotsUrl = "https://maven2.minekot.org/snapshots"
                if (path in mavenPublicationProjects) {
                    staticRepositoryDirectory = stagedMavenRepository
                }
            }
            shadow {
                enabled = false
                classifier = "all"
                mergeServiceFiles = true
            }
            reflection { enabled = false }
            serialization { enabled = false }
            io { enabled = false }
            coroutines { enabled = false }
            atomic { enabled = false }
            adventure { enabled = false }
            testing { enabled = (path != ":minekot-inspections-idea") }
            lint {
                enabled = true
                configFile = rootProject.layout.projectDirectory.file("config/detekt/minekot.yml")
            }
            if (this@allprojects == rootProject) {
                ciCd {
                    enabled = true
                    publicationProjects = mavenPublicationProjects.sorted()
                }
            }
        }
    }

    components.withType<AdhocComponentWithVariants>().configureEach {
        configurations.findByName("shadowRuntimeElements")?.let { shadowElements ->
            withVariantsFromConfiguration(shadowElements) { skip() }
        }
    }

    afterEvaluate {
        extensions.findByType<org.gradle.api.publish.PublishingExtension>()?.publications
            ?.withType<MavenPublication>()
            ?.configureEach {
                pom {
                    name.set(project.name)
                    description.set(mavenPublicationDescriptions[path])
                    url.set("https://github.com/MineKotLang/minekot-inspections")
                    licenses {
                        license {
                            name.set("Apache License 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/MineKotLang/minekot-inspections.git")
                        developerConnection.set("scm:git:ssh://git@github.com/MineKotLang/minekot-inspections.git")
                        url.set("https://github.com/MineKotLang/minekot-inspections")
                    }
                    developers {
                        developer {
                            id.set("MineKotLang")
                            name.set("MineKot")
                            url.set("https://github.com/MineKotLang")
                        }
                    }
                }
            }

        configurations.findByName("implementation")?.dependencies?.removeAll { dependency ->
            dependency.group == "org.minekot"
        }
    }

    tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>().configureEach {
        enabled = project.path == ":minekot-inspections-loader-runtime"
    }

    tasks {
        if (this@allprojects != rootProject) {
            named("writeMineKotCodestyle") {
                enabled = false
            }
            named("writeMineKotProjectFiles") {
                enabled = false
            }
        }

        withType<Test>().configureEach {
            jvmArgs("-Xshare:off")
            systemProperty("minekot.rootDir", rootProject.projectDir.absolutePath)
        }

        if (path == ":minekot-inspections-core" || path == ":minekot-inspections-loader-runtime") {
            named<JacocoReport>("jacocoTestReport") {
                dependsOn("test")
                reports {
                    html.required.set(true)
                    xml.required.set(true)
                }
            }
            named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
                dependsOn("test")
                violationRules {
                    rule {
                        limit {
                            minimum = "0.50".toBigDecimal()
                        }
                    }
                }
            }
            named("check") {
                dependsOn("jacocoTestReport", "jacocoTestCoverageVerification")
            }
        }

        withType<Jar>().configureEach {
            isPreserveFileTimestamps = false
            isReproducibleFileOrder = true
            if (name == "jar") {
                mustRunAfter(":cleanFinalArtifacts")
                destinationDirectory.set(rootProject.layout.projectDirectory.dir("final"))
                archiveFileName.set("${project.name}-${project.version}.jar")
            }
            if (name == "sourcesJar") {
                mustRunAfter(":cleanFinalArtifacts")
                destinationDirectory.set(rootProject.layout.projectDirectory.dir("final"))
                archiveFileName.set("${project.name}-${project.version}-sources.jar")
            }
        }

        withType<Task>().configureEach {
            if (name == "apiCheck") {
                mustRunAfter("apiDump")
            }
            if (name == "build") {
                dependsOn(":cleanFinalArtifacts")
            }
            if (name == "publishMavenJavaPublicationToStaticRepository") {
                mustRunAfter(":cleanStagedMavenRepository")
            }
        }
    }
}

@Suppress("GradleDslConventions")
val verifyInspectionPublications = tasks.register("verifyInspectionPublications") {
    group = "verification"
    description = "Stages and validates every supported MineKot inspections Maven publication."
    dependsOn("stageMineKotPublication")

    doLast {
        val repository = stagedMavenRepository.get().asFile
        val versionDirectoryName = stagedPublicationVersion
        mavenPublicationProjects.forEach { projectPath ->
            val artifactId = projectPath.removePrefix(":")
            val versionDirectory = repository.resolve(
                "org/minekot/inspections/${artifactId}/${versionDirectoryName}",
            )
            val publishedFiles = versionDirectory.listFiles()?.filter { file ->
                file.isFile && file.extension !in checksumExtensions
            }.orEmpty()
            requiredPublicationSuffixes.forEach { suffix ->
                check(publishedFiles.any { file -> file.name.isPublicationArtifact(suffix) }) {
                    "Publication '${artifactId}' is missing its '${suffix}' artifact."
                }
            }
        }

        val ideaArtifactId = "minekot-inspections-idea"
        val ideaDirectory = repository.resolve(
            "org/minekot/inspections/${ideaArtifactId}/${versionDirectoryName}",
        )
        val ideaJar = ideaDirectory.listFiles().orEmpty().single { file ->
            file.name.isPublicationArtifact(".jar")
        }
        JarFile(ideaJar).use { archive ->
            check(archive.getEntry("org/minekot/inspections/idea/MineKotDynamicInspection.class") != null) {
                "IDEA publication does not contain the dynamic inspection adapter."
            }
        }

        val runtimeArtifactId = "minekot-inspections-loader-runtime"
        val runtimeDirectory = repository.resolve(
            "org/minekot/inspections/${runtimeArtifactId}/${versionDirectoryName}",
        )
        val runtimeJar = runtimeDirectory.listFiles().orEmpty().single { file ->
            file.name.endsWith("-private.jar")
        }
        JarFile(runtimeJar).use { archive ->
            val entries = archive.entries().asSequence().map { entry -> entry.name }.toList()
            check(entries.any { entry ->
                entry.startsWith("org/minekot/inspections/loader/runtime/internal/sigstore/")
            },) {
                "Loader runtime publication does not contain relocated Sigstore classes."
            }
            check(entries.none { entry -> entry.startsWith("dev/sigstore/") }) {
                "Loader runtime publication contains unrelocated Sigstore classes."
            }
            check(entries.none { entry -> entry.startsWith("kotlin/") }) {
                "Loader runtime publication bundles Kotlin classes."
            }
        }
        val thinRuntimeJar = runtimeDirectory.listFiles().orEmpty().single { file ->
            file.name.isPublicationArtifact(".jar")
        }
        check(runtimeJar.length() > thinRuntimeJar.length()) {
            "Loader runtime private artifact is not larger than its thin main artifact."
        }
    }
}

tasks.matching { task -> task.name == "stageMineKotPublication" }.configureEach {
    dependsOn(cleanStagedMavenRepository)
}

tasks.named("check") {
    dependsOn(verifyInspectionPublications)
}

val buildWorkflowFile = layout.projectDirectory.file(".github/workflows/build.yml")
val releaseWorkflowFile = layout.projectDirectory.file(".github/workflows/release.yml")

@Suppress("GradleDslConventions")
val verifyReleaseWorkflow = tasks.register("verifyReleaseWorkflow") {
    group = "verification"
    description = "Verifies protected release context, credentials, and immutable GitHub Action references."
    inputs.files(buildWorkflowFile, releaseWorkflowFile)

    doLast {
        val workflowFiles = inputs.files.sortedBy { file -> file.name }
        val releaseWorkflow = workflowFiles.single { file -> file.name == "release.yml" }.readText()
        val actionReferences = Regex("uses: [^@\\s]+@([^\\s]+)")
            .let { pattern ->
                workflowFiles.flatMap { file ->
                    pattern.findAll(file.readText()).map { match -> match.groupValues[1] }.toList()
                }
            }
            .toList()
        check(actionReferences.isNotEmpty()) {
            "Release workflow does not use any actions."
        }
        check(actionReferences.all { reference -> reference.matches(Regex("[0-9a-f]{40}")) }) {
            "Release workflow actions must use immutable 40-character commit SHAs."
        }
        requiredReleaseWorkflowFragments.forEach { fragment ->
            check(fragment in releaseWorkflow) {
                "Release workflow is missing required contract '${fragment}'."
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyReleaseWorkflow)
}

val checksumExtensions = setOf("md5", "sha1", "sha256", "sha512")
val requiredPublicationSuffixes = listOf(".pom", ".jar", "-sources.jar", "-javadoc.jar")

fun String.isPublicationArtifact(suffix: String): Boolean =
    endsWith(suffix) &&
        (suffix != ".jar" || excludedMainJarClassifiers.none { classifier ->
            contains("-${classifier}.jar")
        })

val excludedMainJarClassifiers = listOf("sources", "javadoc", "base", "private")
val requiredReleaseWorkflowFragments = listOf(
    "environment: production",
    "MINEKOT_MAVEN_USERNAME",
    "MINEKOT_MAVEN_PASSWORD",
    "git rev-parse origin/master",
    "publishMineKotPublication",
    "cmp \"${'$'}{local_directory}/${'$'}{file}\"",
)
