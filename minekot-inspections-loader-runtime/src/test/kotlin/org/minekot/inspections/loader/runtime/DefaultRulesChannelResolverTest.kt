package org.minekot.inspections.loader.runtime

import org.minekot.inspections.loader.*
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Signed-channel transition and conditional-request tests. */
class DefaultRulesChannelResolverTest {
    /** A 304 reuses only the previously verified cached index. */
    @Test
    fun `conditional response reuses verified cache`() {
        val cache = RulesChannelIndexCache(createTempDirectory("minekot-channel-test"))
        val first = index(sequence = 1, previousDigest = null, manifestDigest = "1".repeat(SHA_LENGTH))
        resolver(cache, response(first), response("bundle".encodeToByteArray())).resolve(HOST, offline = false)

        val cached = resolver(cache, RulesHttpResponse(HTTP_NOT_MODIFIED, byteArrayOf())).resolve(HOST, offline = false)

        assertEquals(1, cached.index.sequence)
        assertEquals("etag", cached.etag)
    }

    /** Reusing a sequence number with different signed bytes fails closed. */
    @Test
    fun `same sequence replacement is rejected`() {
        val cache = RulesChannelIndexCache(createTempDirectory("minekot-channel-test"))
        val first = index(sequence = 1, previousDigest = null, manifestDigest = "1".repeat(SHA_LENGTH))
        resolver(cache, response(first), response("bundle".encodeToByteArray())).resolve(HOST, offline = false)
        val replacement = index(sequence = 1, previousDigest = null, manifestDigest = "2".repeat(SHA_LENGTH))

        assertFailsWith<IllegalArgumentException> {
            resolver(cache, response(replacement), response("bundle".encodeToByteArray()))
                .resolve(HOST, offline = false)
        }
    }

    /** A later index cannot replace a manifest digest for an existing version. */
    @Test
    fun `same tag manifest replacement is rejected`() {
        val cache = RulesChannelIndexCache(createTempDirectory("minekot-channel-test"))
        val first = index(sequence = 1, previousDigest = null, manifestDigest = "1".repeat(SHA_LENGTH))
        val firstResolved = resolver(cache, response(first), response("bundle".encodeToByteArray()))
            .resolve(HOST, offline = false)
        val replacement = index(
            sequence = 2,
            previousDigest = firstResolved.receipt.indexSha256,
            manifestDigest = "2".repeat(SHA_LENGTH),
        )

        assertFailsWith<IllegalArgumentException> {
            resolver(cache, response(replacement), response("bundle".encodeToByteArray()))
                .resolve(HOST, offline = false)
        }
    }

    /** A skipped sequence must still authenticate its immediate cached predecessor. */
    @Test
    fun `skipped sequence with broken digest chain is rejected`() {
        val cache = RulesChannelIndexCache(createTempDirectory("minekot-channel-test"))
        val first = index(sequence = 1, previousDigest = null, manifestDigest = "1".repeat(SHA_LENGTH))
        resolver(cache, response(first), response("bundle".encodeToByteArray())).resolve(HOST, offline = false)
        val skipped = index(
            sequence = 3,
            previousDigest = "f".repeat(SHA_LENGTH),
            manifestDigest = "1".repeat(SHA_LENGTH),
        )

        assertFailsWith<IllegalArgumentException> {
            resolver(cache, response(skipped), response("bundle".encodeToByteArray()))
                .resolve(HOST, offline = false)
        }
    }

    private fun resolver(
        cache: RulesChannelIndexCache,
        vararg responses: RulesHttpResponse,
    ): DefaultRulesChannelResolver {
        val pending = responses.toMutableList()
        val transport = RulesHttpTransport { pending.removeFirst() }
        val verifier = RulesIndexSignatureVerifier { _, _, digest ->
            RulesIndexVerificationReceipt(
                digest,
                Instant.parse("2026-08-13T00:00:00Z"),
                "workflow",
                "issuer",
                "root",
            )
        }
        return DefaultRulesChannelResolver(transport, verifier, cache, "https://example.invalid/channel")
    }

    private fun response(body: ByteArray): RulesHttpResponse = RulesHttpResponse(HTTP_OK, body, etag = "etag")

    private fun index(sequence: Int, previousDigest: String?, manifestDigest: String): ByteArray {
        val previous = previousDigest?.let { digest -> "\"${digest}\"" } ?: "null"
        return """
            {
              "schemaVersion":1,
              "sequence":${sequence},
              "previousIndexSha256":${previous},
              "generatedAt":"2026-08-13T00:00:00Z",
              "releases":[{
                "version":"1.0.1",
                "manifestSha256":"${manifestDigest}",
                "prerelease":false,
                "withdrawn":false,
                "securityRevoked":false,
                "spiMajor":1,
                "minimumCoreVersion":"1.0.0",
                "maximumCoreVersionExclusive":"2.0.0",
                "minimumJavaVersion":21,
                "testedHosts":[{"hostType":"IDEA","hostVersion":"2025.3.5","kotlinVersion":"2.4.10"}]
              }]
            }
        """.trimIndent().encodeToByteArray()
    }

    private companion object {
        const val SHA_LENGTH = 64
        const val HTTP_OK = 200
        const val HTTP_NOT_MODIFIED = 304
        val HOST = RulesHostDescriptor(RulesHostType.IDEA, "2025.3.5", "2.4.10", "1.0.0", 21, 1)
    }
}
