package org.minekot.inspections.loader.runtime

import org.minekot.inspections.loader.*
import java.time.Instant
import kotlin.concurrent.thread
import kotlin.io.path.*
import kotlin.test.*

/** Strict channel schema and durable-cache tests. */
class RulesChannelIndexCodecTest {
    /** Signed index schema rejects ordering, duplication, unknown fields, and invalid digests. */
    @Test
    fun `strict index rejects malformed signed state`() {
        assertFails { RulesChannelIndexCodec.decode(index(releases = releases("1.0.2", "1.0.1"))) }
        assertFails { RulesChannelIndexCodec.decode(index(releases = releases("1.0.1", "1.0.1"))) }
        assertFails { RulesChannelIndexCodec.decode(index(extraRootField = true)) }
        assertFails { RulesChannelIndexCodec.decode(index(manifestDigest = "not-a-digest")) }
    }

    /** Corrupt receipt invalidates cached bytes rather than trusting parsed index data. */
    @Test
    fun `channel cache rejects corrupt verification receipt`() {
        val root = createTempDirectory("minekot-channel-corrupt-receipt-test")
        val cache = RulesChannelIndexCache(root)
        val bytes = index()
        cache.promote(bytes, resolved(bytes, "etag"))
        root.resolve("channel/index.receipt").writeText("corrupt")

        assertNull(cache.load())
    }

    /** Concurrent channel promotions serialize across independent cache instances. */
    @Test
    fun `concurrent channel promotion remains atomic`() {
        val root = createTempDirectory("minekot-channel-concurrent-test")
        val first = index(sequence = 1)
        val second = index(sequence = 2, previousDigest = first.sha256())
        val writes = listOf(first, second).flatMap { bytes ->
            List(CONCURRENT_PROMOTIONS) {
                thread {
                    RulesChannelIndexCache(root).promote(bytes, resolved(bytes, bytes.sha256()))
                }
            }
        }

        writes.forEach(Thread::join)

        val loaded = assertNotNull(RulesChannelIndexCache(root).load())
        assertEquals(loaded.indexBytes.sha256(), loaded.resolved.receipt.indexSha256)
        assertTrue(loaded.resolved.index.sequence in 1L..2L)
        assertTrue(root.toFile().walkTopDown().none { file -> file.extension == "tmp" })
    }

    private fun resolved(bytes: ByteArray, etag: String): ResolvedRulesChannel = ResolvedRulesChannel(
        RulesChannelIndexCodec.decode(bytes),
        RulesIndexVerificationReceipt(
            bytes.sha256(),
            Instant.parse("2026-08-13T00:00:00Z"),
            "workflow",
            "issuer",
            "root",
        ),
        etag,
    )

    private fun index(
        sequence: Int = 1,
        previousDigest: String? = null,
        releases: String = releases("1.0.1"),
        manifestDigest: String = "a".repeat(SHA_LENGTH),
        extraRootField: Boolean = false,
    ): ByteArray {
        val previous = previousDigest?.let { digest -> "\"${digest}\"" } ?: "null"
        val extra = if (extraRootField) ",\"unexpected\":true" else ""
        return """
            {
              "schemaVersion":1,
              "sequence":${sequence},
              "previousIndexSha256":${previous},
              "generatedAt":"2026-08-13T00:00:00Z",
              "releases":${releases.replace(DEFAULT_DIGEST, manifestDigest)}${extra}
            }
        """.trimIndent().encodeToByteArray()
    }

    private fun releases(vararg versions: String): String = versions.joinToString(",", "[", "]") { version ->
        """
            {"version":"${version}","manifestSha256":"${DEFAULT_DIGEST}","prerelease":false,"withdrawn":false,
            "securityRevoked":false,"spiMajor":1,"minimumCoreVersion":"1.0.0",
            "maximumCoreVersionExclusive":"2.0.0","minimumJavaVersion":21,
            "testedHosts":[{"hostType":"IDEA","hostVersion":"2026.1.5","kotlinVersion":"2.4.20"}]}
        """.trimIndent()
    }

    private companion object {
        const val CONCURRENT_PROMOTIONS = 4
        const val SHA_LENGTH = 64
        val DEFAULT_DIGEST = "a".repeat(SHA_LENGTH)
    }
}
