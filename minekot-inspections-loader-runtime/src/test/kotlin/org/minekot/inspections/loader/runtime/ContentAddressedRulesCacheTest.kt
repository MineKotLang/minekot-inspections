package org.minekot.inspections.loader.runtime

import org.minekot.inspections.loader.RulesVerificationReceipt
import org.minekot.inspections.loader.RulesCachedGeneration
import java.time.Instant
import kotlin.concurrent.thread
import kotlin.io.path.*
import kotlin.test.*

/** Recovery tests for immutable cache promotion. */
class ContentAddressedRulesCacheTest {
    /** A receipt-less corrupt blob is replaced during a later verified promotion. */
    @Test
    fun `promotion replaces corrupt orphan blob`() {
        val root = createTempDirectory("minekot-rules-cache-test")
        val jar = "verified jar".encodeToByteArray()
        val manifest = "verified manifest".encodeToByteArray()
        val jarDigest = jar.sha256()
        val manifestDigest = manifest.sha256()
        val blob = root.resolve("blobs").resolve("${jarDigest}.jar")
        blob.parent.createDirectories()
        blob.writeBytes("corrupt".encodeToByteArray())
        val receipt = RulesVerificationReceipt(
            manifestSha256 = manifestDigest,
            jarSha256 = jarDigest,
            verifiedAt = Instant.parse("2026-08-13T00:00:00Z"),
            signerIdentity = SIGNER_IDENTITY,
            issuer = "https://token.actions.githubusercontent.com",
            trustedRootVersion = "test-root",
        )

        ContentAddressedRulesCache(root).promote(jar, manifest, receipt).use { lease ->
            assertContentEquals(jar, lease.jarPath.readBytes())
        }
    }

    /** Activation keeps current and replaced generations as durable LKG. */
    @Test
    fun `activation records current and last known good generations`() {
        val root = createTempDirectory("minekot-rules-activation-test")
        val cache = ContentAddressedRulesCache(root)
        val first = promote(cache, "first")
        val second = promote(cache, "second")

        cache.recordActivation("idea:project", first)
        val record = cache.recordActivation("idea:project", second)

        assertEquals(second, record.active)
        assertEquals(first, record.lastKnownGood)
        assertEquals(record, cache.loadActivation("idea:project"))
    }

    /** Cleanup honors leases shared by separate cache instances. */
    @Test
    fun `cleanup protects process wide leases`() {
        val root = createTempDirectory("minekot-rules-lease-test")
        val first = ContentAddressedRulesCache(root)
        val generation = promote(first, "leased")
        val lease = first.leaseVerified(generation.jarSha256, generation.manifestSha256)!!

        val deletedWhileLeased = ContentAddressedRulesCache(root).cleanup(
            now = Instant.parse("2100-01-01T00:00:00Z"),
            newestCount = 0,
        )
        assertEquals(0, deletedWhileLeased)
        assertTrue(lease.jarPath.isRegularFile())

        lease.close()
        val deletedAfterClose = ContentAddressedRulesCache(root).cleanup(
            now = Instant.parse("2100-01-01T00:00:00Z"),
            newestCount = 0,
        )
        assertEquals(1, deletedAfterClose)
        assertFalse(lease.jarPath.exists())
    }

    /** Receipt must bind exact requested manifest and JAR digests. */
    @Test
    fun `lease rejects receipt bound to another jar`() {
        val root = createTempDirectory("minekot-rules-receipt-test")
        val cache = ContentAddressedRulesCache(root)
        val generation = promote(cache, "bound")
        val receiptPath = root.resolve("receipts").resolve("${generation.manifestSha256}.receipt")
        val lines = receiptPath.readLines().toMutableList()
        lines[1] = "f".repeat(DIGEST_LENGTH)
        receiptPath.writeText(lines.joinToString("\n"))

        assertEquals(null, cache.leaseVerified(generation.jarSha256, generation.manifestSha256))
    }

    /** Failed activation quarantine removes generation from verified resolution. */
    @Test
    fun `failed activation quarantines complete generation`() {
        val root = createTempDirectory("minekot-rules-quarantine-test")
        val cache = ContentAddressedRulesCache(root)
        val generation = promote(cache, "failing")

        assertTrue(cache.quarantineGeneration(generation.jarSha256, generation.manifestSha256))
        assertEquals(null, cache.loadVerifiedMetadata(generation.manifestSha256))
        assertEquals(null, cache.leaseVerified(generation.jarSha256, generation.manifestSha256))
        assertEquals(QUARANTINED_GENERATION_FILE_COUNT, root.resolve("quarantine").listDirectoryEntries().size)
    }

    /** Independent cache instances coalesce concurrent promotion without overlapping file locks. */
    @Test
    fun `concurrent cache instances promote one verified generation`() {
        val root = createTempDirectory("minekot-rules-concurrent-cache-test")
        val jar = "concurrent jar".encodeToByteArray()
        val manifest = "concurrent manifest".encodeToByteArray()
        val receipt = receipt(jar, manifest)
        val promotions = List(CONCURRENT_PROMOTIONS) {
            thread {
                ContentAddressedRulesCache(root).promote(jar, manifest, receipt).close()
            }
        }

        promotions.forEach(Thread::join)

        ContentAddressedRulesCache(root).leaseVerified(receipt.jarSha256, receipt.manifestSha256)!!.use { lease ->
            assertContentEquals(jar, lease.jarPath.readBytes())
        }
        assertTrue(root.toFile().walkTopDown().none { file -> file.extension == "tmp" })
    }

    private fun promote(cache: ContentAddressedRulesCache, name: String): RulesCachedGeneration {
        val jar = "${name} jar".encodeToByteArray()
        val manifest = "${name} manifest".encodeToByteArray()
        val receipt = receipt(jar, manifest)
        cache.promote(jar, manifest, receipt).close()
        return RulesCachedGeneration(name, receipt.manifestSha256, receipt.jarSha256, receipt.verifiedAt)
    }

    private fun receipt(jar: ByteArray, manifest: ByteArray): RulesVerificationReceipt =
        RulesVerificationReceipt(
            manifest.sha256(),
            jar.sha256(),
            Instant.parse("2026-08-13T00:00:00Z"),
            SIGNER_IDENTITY,
            "https://token.actions.githubusercontent.com",
            "test-root",
        )

    private companion object {
        const val DIGEST_LENGTH = 64
        const val CONCURRENT_PROMOTIONS = 8
        const val QUARANTINED_GENERATION_FILE_COUNT = 3
        const val SIGNER_IDENTITY =
            "https://github.com/MineKotLang/minekot-rules/.github/workflows/release.yml@refs/heads/master"
    }
}
