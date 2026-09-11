package org.minekot.inspections.loader.runtime

import kotlinx.serialization.json.*
import org.minekot.inspections.loader.*
import java.time.Instant

/** Strict JSON codec for signed rules manifests. */
public object RulesManifestCodec {
    private val json: Json = Json { ignoreUnknownKeys = false }

    /** Parses strict manifest JSON and rejects unknown or missing fields. */
    public fun decode(bytes: ByteArray): RulesManifest {
        val objectValue = json.parseToJsonElement(bytes.decodeToString()).jsonObject
        objectValue.requireExactKeys(
            "schemaVersion", "rulesVersion", "tag", "commitSha", "publishedAt", "jarName", "jarSize",
            "jarSha256", "spiMajor", "minimumCoreVersion", "maximumCoreVersionExclusive", "minimumJavaVersion",
            "kotlinPsiBaseline", "testedHosts", "catalogProvider", "configurationSchemaVersion",
        )
        return RulesManifest(
            schemaVersion = objectValue.int("schemaVersion"),
            rulesVersion = objectValue.string("rulesVersion"),
            tag = objectValue.string("tag"),
            commitSha = objectValue.string("commitSha"),
            publishedAt = Instant.parse(objectValue.string("publishedAt")),
            jarName = objectValue.string("jarName"),
            jarSize = objectValue.long("jarSize"),
            jarSha256 = objectValue.string("jarSha256").requireSha256("jarSha256"),
            spiMajor = objectValue.int("spiMajor"),
            minimumCoreVersion = objectValue.string("minimumCoreVersion"),
            maximumCoreVersionExclusive = objectValue.string("maximumCoreVersionExclusive"),
            minimumJavaVersion = objectValue.int("minimumJavaVersion"),
            kotlinPsiBaseline = objectValue.string("kotlinPsiBaseline"),
            testedHosts = objectValue.getValue("testedHosts").jsonArray.map { item ->
                val host = item.jsonObject
                host.requireExactKeys("hostType", "hostVersion", "kotlinVersion")
                RulesHostTuple(
                    hostType = RulesHostType.valueOf(host.string("hostType")),
                    hostVersion = host.string("hostVersion"),
                    kotlinVersion = host.string("kotlinVersion"),
                )
            },
            catalogProvider = objectValue.string("catalogProvider"),
            configurationSchemaVersion = objectValue.int("configurationSchemaVersion"),
        ).also { manifest ->
            require(manifest.schemaVersion == 1) { "Unsupported manifest schema ${manifest.schemaVersion}." }
            require(manifest.tag == "v${manifest.rulesVersion}") { "Manifest tag and rules version disagree." }
            require(manifest.commitSha.matches(Regex("[0-9a-f]{40}"))) { "Invalid commit SHA." }
            require(manifest.jarSize in 1..MAXIMUM_RULES_JAR_BYTES) { "Invalid rules JAR size." }
            require(manifest.catalogProvider.matches(Regex("[A-Za-z_$][A-Za-z0-9_$.]*"))) {
                "Invalid catalog provider."
            }
        }
    }

    /** Maximum accepted official rules artifact size. */
    public const val MAXIMUM_RULES_JAR_BYTES: Long = 16L * 1024L * 1024L
}

private fun JsonObject.requireExactKeys(vararg expected: String) {
    val expectedKeys = expected.toSet()
    require(this.keys == expectedKeys) {
        "JSON keys differ. Missing=${expectedKeys - this.keys}, unknown=${this.keys - expectedKeys}."
    }
}

private fun JsonObject.string(key: String): String = this.getValue(key).jsonPrimitive.content

private fun JsonObject.int(key: String): Int = this.getValue(key).jsonPrimitive.int

private fun JsonObject.long(key: String): Long = this.getValue(key).jsonPrimitive.long

private fun String.requireSha256(field: String): String {
    require(this.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 in ${field}." }
    return this
}
