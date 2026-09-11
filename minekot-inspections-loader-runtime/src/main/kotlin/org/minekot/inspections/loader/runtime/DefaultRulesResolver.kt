package org.minekot.inspections.loader.runtime

import org.minekot.inspections.loader.*

/** Exact-release resolver for official MineKot GitHub assets. */
public class DefaultRulesResolver(
    private val transport: RulesHttpTransport,
    private val signatureVerifier: RulesSignatureVerifier,
    private val cache: ContentAddressedRulesCache,
    private val releaseBaseUrl: String = "https://github.com/MineKotLang/minekot-rules/releases/download",
) : RulesResolver {
    override fun resolve(
        reference: RulesReleaseReference,
        host: RulesHostDescriptor,
        offline: Boolean,
    ): ResolvedRulesArtifact {
        require(reference.version.matches(Regex("1\\.0\\.[0-9]+"))) { "Invalid rules version." }
        require(reference.manifestSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid manifest SHA-256." }
        if (offline) {
            val (manifestBytes, receipt) = cache.loadVerifiedMetadata(reference.manifestSha256)
                ?: error("Exact verified rules manifest is absent from offline cache.")
            val manifest = RulesManifestCodec.decode(manifestBytes)
            require(manifest.rulesVersion == reference.version) { "Cached manifest version differs from request." }
            require(manifest.compatibilityWith(host) == RulesCompatibility.Compatible) {
                "Cached rules release is incompatible with current host."
            }
            val lease = cache.leaseVerified(manifest.jarSha256, reference.manifestSha256)
                ?: error("Exact verified rules JAR is absent or corrupt in offline cache.")
            return ResolvedRulesArtifact(manifest, receipt, lease)
        }
        val tag = "v${reference.version}"
        val prefix = "${releaseBaseUrl}/${tag}/minekot-rules-${reference.version}"
        val manifestBytes = transport.successfulBody("${prefix}.manifest.json", MAXIMUM_MANIFEST_BYTES)
        require(manifestBytes.sha256() == reference.manifestSha256) { "Manifest digest changed for ${tag}." }
        val bundleBytes = transport.successfulBody("${prefix}.manifest.sigstore.json", MAXIMUM_BUNDLE_BYTES)
        val receipt = signatureVerifier.verify(manifestBytes, bundleBytes, reference.manifestSha256)
        val manifest = RulesManifestCodec.decode(manifestBytes)
        require(manifest.rulesVersion == reference.version) { "Requested and manifest versions differ." }
        when (val compatibility = manifest.compatibilityWith(host)) {
            RulesCompatibility.Compatible -> Unit
            is RulesCompatibility.Incompatible -> error(compatibility.reasons.joinToString(" "))
        }
        cache.leaseVerified(manifest.jarSha256, reference.manifestSha256)?.let { lease ->
            return ResolvedRulesArtifact(manifest, receipt, lease)
        }
        val jarBytes = transport.successfulBody("${releaseBaseUrl}/${tag}/${manifest.jarName}", manifest.jarSize)
        RulesArtifactValidator.validate(
            jarBytes,
            manifest.jarSize,
            manifest.jarSha256,
            manifest.catalogProvider,
        )
        return ResolvedRulesArtifact(manifest, receipt, cache.promote(jarBytes, manifestBytes, receipt))
    }

    private fun RulesHttpTransport.successfulBody(url: String, maximumBytes: Long): ByteArray {
        val response = this.get(RulesHttpRequest(url, maximumBytes))
        require(response.statusCode == HTTP_OK) { "Rules download failed with HTTP ${response.statusCode}." }
        require(response.body.size.toLong() <= maximumBytes) { "Rules response exceeds byte limit." }
        return response.body
    }

    private companion object {
        private const val MAXIMUM_MANIFEST_BYTES: Long = 128L * 1024L
        private const val MAXIMUM_BUNDLE_BYTES: Long = 1024L * 1024L
        private const val HTTP_OK: Int = 200
    }
}
