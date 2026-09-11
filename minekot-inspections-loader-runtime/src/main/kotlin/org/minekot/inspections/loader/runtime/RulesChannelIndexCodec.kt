package org.minekot.inspections.loader.runtime

import kotlinx.serialization.json.*
import org.minekot.inspections.loader.RulesChannelIndex
import org.minekot.inspections.loader.RulesChannelRelease
import org.minekot.inspections.loader.RulesHostTuple
import org.minekot.inspections.loader.RulesHostType
import java.time.Instant

/** Strict codec for the signed stable-channel index. */
public object RulesChannelIndexCodec {
    private val json: Json = Json { ignoreUnknownKeys = false }

    /** Decodes an index without accepting missing or unknown signed fields. */
    public fun decode(bytes: ByteArray): RulesChannelIndex {
        val root = json.parseToJsonElement(bytes.decodeToString()).jsonObject
        root.requireChannelKeys("schemaVersion", "sequence", "previousIndexSha256", "generatedAt", "releases")
        val index = RulesChannelIndex(
            schemaVersion = root.channelInt("schemaVersion"),
            sequence = root.getValue("sequence").jsonPrimitive.long,
            previousIndexSha256 = root.getValue("previousIndexSha256").jsonPrimitive.contentOrNull,
            generatedAt = Instant.parse(root.channelString("generatedAt")),
            releases = root.getValue("releases").jsonArray.map { value -> decodeRelease(value.jsonObject) },
        )
        require(index.schemaVersion == SUPPORTED_SCHEMA) { "Unsupported channel index schema." }
        require(index.sequence > 0) { "Channel index sequence must be positive." }
        index.previousIndexSha256?.requireChannelSha256()
        require(index.releases.map(RulesChannelRelease::version).distinct().size == index.releases.size) {
            "Channel index contains duplicate versions."
        }
        require(index.releases == index.releases.sortedWith(RELEASE_COMPARATOR)) {
            "Channel index releases are not stably ordered."
        }
        return index
    }

    private fun decodeRelease(value: JsonObject): RulesChannelRelease {
        value.requireChannelKeys(
            "version",
            "manifestSha256",
            "prerelease",
            "withdrawn",
            "securityRevoked",
            "spiMajor",
            "minimumCoreVersion",
            "maximumCoreVersionExclusive",
            "minimumJavaVersion",
            "testedHosts",
        )
        val version = value.channelString("version")
        require(version.matches(Regex("1\\.0\\.[0-9]+"))) { "Invalid channel release version." }
        return RulesChannelRelease(
            version = version,
            manifestSha256 = value.channelString("manifestSha256").requireChannelSha256(),
            prerelease = value.getValue("prerelease").jsonPrimitive.content.toBooleanStrict(),
            withdrawn = value.getValue("withdrawn").jsonPrimitive.content.toBooleanStrict(),
            securityRevoked = value.getValue("securityRevoked").jsonPrimitive.content.toBooleanStrict(),
            spiMajor = value.channelInt("spiMajor"),
            minimumCoreVersion = value.channelString("minimumCoreVersion"),
            maximumCoreVersionExclusive = value.channelString("maximumCoreVersionExclusive"),
            minimumJavaVersion = value.channelInt("minimumJavaVersion"),
            testedHosts = value.getValue("testedHosts").jsonArray.map { hostValue ->
                val host = hostValue.jsonObject
                host.requireChannelKeys("hostType", "hostVersion", "kotlinVersion")
                RulesHostTuple(
                    RulesHostType.valueOf(host.channelString("hostType")),
                    host.channelString("hostVersion"),
                    host.channelString("kotlinVersion"),
                )
            },
        )
    }

    private val RELEASE_COMPARATOR: Comparator<RulesChannelRelease> = Comparator { left, right ->
        compareReleaseVersions(left.version, right.version)
    }

    private const val SUPPORTED_SCHEMA = 1
}

private fun compareReleaseVersions(left: String, right: String): Int {
    val leftParts = left.split('.').map(String::toInt)
    val rightParts = right.split('.').map(String::toInt)
    repeat(maxOf(leftParts.size, rightParts.size)) { index ->
        val result = (leftParts.getOrNull(index) ?: 0).compareTo(rightParts.getOrNull(index) ?: 0)
        if (result != 0) return result
    }
    return 0
}

private fun JsonObject.requireChannelKeys(vararg expected: String) {
    require(keys == expected.toSet()) { "Channel index keys differ from schema." }
}

private fun JsonObject.channelString(key: String): String = getValue(key).jsonPrimitive.content

private fun JsonObject.channelInt(key: String): Int = getValue(key).jsonPrimitive.int

private fun String.requireChannelSha256(): String {
    require(matches(Regex("[0-9a-f]{64}"))) { "Invalid channel SHA-256." }
    return this
}
