package org.minekot.inspections.loader.runtime

import org.minekot.inspections.loader.*

/** Online-first resolver for the official signed stable-channel index. */
public class DefaultRulesChannelResolver(
    private val transport: RulesHttpTransport,
    private val signatureVerifier: RulesIndexSignatureVerifier,
    private val cache: RulesChannelIndexCache,
    private val channelBaseUrl: String =
        "https://github.com/MineKotLang/minekot-rules/releases/download/channel-stable",
) : RulesChannelResolver {
    override fun resolve(host: RulesHostDescriptor, offline: Boolean): ResolvedRulesChannel {
        val cached = cache.load()
        if (offline) return cached?.resolved ?: error("Verified stable-channel index is absent from offline cache.")
        val response = transport.get(
            RulesHttpRequest(
                "${channelBaseUrl}/minekot-rules-index.json",
                MAXIMUM_INDEX_BYTES,
                cached?.resolved?.etag,
            ),
        )
        if (response.statusCode == HTTP_NOT_MODIFIED) {
            return cached?.resolved ?: error("Channel returned 304 without a verified cached index.")
        }
        require(response.statusCode == HTTP_OK) {
            val retry = response.retryAfterSeconds?.let { seconds -> " Retry after ${seconds}s." }.orEmpty()
            "Stable-channel download failed with HTTP ${response.statusCode}.${retry}"
        }
        require(response.body.size.toLong() <= MAXIMUM_INDEX_BYTES) { "Channel index exceeds byte limit." }
        val bundle = transport.get(
            RulesHttpRequest(
                "${channelBaseUrl}/minekot-rules-index.sigstore.json",
                MAXIMUM_BUNDLE_BYTES,
            ),
        )
        require(bundle.statusCode == HTTP_OK && bundle.body.size.toLong() <= MAXIMUM_BUNDLE_BYTES) {
            "Stable-channel signature bundle download failed."
        }
        val digest = response.body.sha256()
        val receipt = signatureVerifier.verifyIndex(response.body, bundle.body, digest)
        val index = RulesChannelIndexCodec.decode(response.body)
        validateTransition(cached, index.sequence, index.previousIndexSha256, index.releases, digest)
        val resolved = ResolvedRulesChannel(index, receipt, response.etag)
        cache.promote(response.body, resolved)
        return resolved
    }

    private fun validateTransition(
        cached: CachedRulesChannel?,
        sequence: Long,
        previousDigest: String?,
        releases: List<RulesChannelRelease>,
        newDigest: String,
    ) {
        if (cached == null) return
        val previous = cached.resolved.index
        require(sequence >= previous.sequence) { "Stable-channel sequence rolled back." }
        if (sequence == previous.sequence) {
            require(newDigest == cached.resolved.receipt.indexSha256) { "Stable-channel sequence was replaced." }
            return
        }
        require(previousDigest == cached.resolved.receipt.indexSha256) { "Stable-channel digest chain broke." }
        val newByVersion = releases.associateBy(RulesChannelRelease::version)
        previous.releases.forEach { old ->
            newByVersion[old.version]?.let { current ->
                require(current.manifestSha256 == old.manifestSha256) { "Release manifest digest was replaced." }
                require(!old.securityRevoked || current.securityRevoked) { "Security revocation was removed." }
            }
        }
    }

    private companion object {
        const val MAXIMUM_INDEX_BYTES = 1024L * 1024L
        const val MAXIMUM_BUNDLE_BYTES = 1024L * 1024L
        const val HTTP_OK = 200
        const val HTTP_NOT_MODIFIED = 304
    }
}
