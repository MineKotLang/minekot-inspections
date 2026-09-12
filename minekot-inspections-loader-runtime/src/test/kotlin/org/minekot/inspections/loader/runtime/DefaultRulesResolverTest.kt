package org.minekot.inspections.loader.runtime

import org.minekot.inspections.loader.*
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.test.*

/** Exact release network, cache, and compatibility tests. */
class DefaultRulesResolverTest {
    /** Verified online resolution promotes bytes for exact zero-network offline reuse. */
    @Test
    fun `online promotion is reusable offline`() {
        val root = createTempDirectory("minekot-exact-resolver-test")
        val jar = rulesJar()
        val manifest = manifest(jar)
        val reference = RulesReleaseReference(VERSION, manifest.sha256())
        val onlineTransport = QueueTransport(manifest, BUNDLE, jar)

        DefaultRulesResolver(onlineTransport, verifier(jar), ContentAddressedRulesCache(root), RELEASE_BASE)
            .resolve(reference, HOST, offline = false)
            .lease.close()
        val offline = DefaultRulesResolver(
            RulesHttpTransport { error("Offline resolution performed network access.") },
            verifier(jar),
            ContentAddressedRulesCache(root),
            RELEASE_BASE,
        ).resolve(reference, HOST, offline = true)

        offline.lease.use { lease -> assertContentEquals(jar, lease.jarPath.readBytes()) }
        assertEquals(FULL_RESOLUTION_REQUEST_COUNT, onlineTransport.requestCount)
    }

    /** Host incompatibility fails before untrusted JAR bytes are requested. */
    @Test
    fun `incompatible host rejects before jar download`() {
        val jar = rulesJar()
        val manifest = manifest(jar)
        val transport = QueueTransport(manifest, BUNDLE)
        val incompatible = HOST.copy(kotlinVersion = "0.0.0")

        assertFailsWith<IllegalStateException> {
            DefaultRulesResolver(
                transport,
                verifier(jar),
                ContentAddressedRulesCache(createTempDirectory("minekot-incompatible-test")),
                RELEASE_BASE,
            ).resolve(RulesReleaseReference(VERSION, manifest.sha256()), incompatible, offline = false)
        }
        assertEquals(MANIFEST_RESOLUTION_REQUEST_COUNT, transport.requestCount)
    }

    /** Corrupt cached blob is quarantined and redownloaded once during online resolution. */
    @Test
    fun `corrupt cached jar is redownloaded`() {
        val root = createTempDirectory("minekot-corrupt-redownload-test")
        val cache = ContentAddressedRulesCache(root)
        val jar = rulesJar()
        val manifest = manifest(jar)
        val reference = RulesReleaseReference(VERSION, manifest.sha256())
        DefaultRulesResolver(QueueTransport(manifest, BUNDLE, jar), verifier(jar), cache, RELEASE_BASE)
            .resolve(reference, HOST, offline = false)
            .lease.close()
        root.resolve("blobs").resolve("${jar.sha256()}.jar").writeText("corrupt")
        val retry = QueueTransport(manifest, BUNDLE, jar)

        DefaultRulesResolver(retry, verifier(jar), cache, RELEASE_BASE)
            .resolve(reference, HOST, offline = false)
            .lease.use { lease -> assertContentEquals(jar, lease.jarPath.readBytes()) }

        assertEquals(FULL_RESOLUTION_REQUEST_COUNT, retry.requestCount)
        assertTrue(root.resolve("quarantine").listDirectoryEntries().isNotEmpty())
    }

    /** Manifest bytes cannot change behind an exact project lock. */
    @Test
    fun `pinned manifest digest mismatch fails before signature and jar`() {
        val jar = rulesJar()
        val manifest = manifest(jar)
        val transport = QueueTransport(manifest)

        assertFailsWith<IllegalArgumentException> {
            DefaultRulesResolver(
                transport,
                RulesSignatureVerifier { _, _, _ -> error("Signature verification must not run.") },
                ContentAddressedRulesCache(createTempDirectory("minekot-digest-mismatch-test")),
                RELEASE_BASE,
            ).resolve(RulesReleaseReference(VERSION, "f".repeat(SHA_LENGTH)), HOST, offline = false)
        }
        assertEquals(1, transport.requestCount)
    }

    /** Signature failure prevents manifest compatibility and JAR requests. */
    @Test
    fun `signature verification failure stops resolution`() {
        val jar = rulesJar()
        val manifest = manifest(jar)
        val transport = QueueTransport(manifest, BUNDLE)

        assertFailsWith<IllegalStateException> {
            DefaultRulesResolver(
                transport,
                RulesSignatureVerifier { _, _, _ -> error("invalid signature") },
                ContentAddressedRulesCache(createTempDirectory("minekot-signature-failure-test")),
                RELEASE_BASE,
            ).resolve(RulesReleaseReference(VERSION, manifest.sha256()), HOST, offline = false)
        }
        assertEquals(MANIFEST_RESOLUTION_REQUEST_COUNT, transport.requestCount)
    }

    /** HTTP and bounded-body failures do not promote partial artifacts. */
    @Test
    fun `failed jar response leaves cache inactive`() {
        val root = createTempDirectory("minekot-truncated-response-test")
        val jar = rulesJar()
        val manifest = manifest(jar)
        val pending = mutableListOf(
            RulesHttpResponse(HTTP_OK, manifest),
            RulesHttpResponse(HTTP_OK, BUNDLE),
            RulesHttpResponse(HTTP_FAILURE, byteArrayOf()),
        )

        assertFailsWith<IllegalArgumentException> {
            DefaultRulesResolver(
                RulesHttpTransport { pending.removeFirst() },
                verifier(jar),
                ContentAddressedRulesCache(root),
                RELEASE_BASE,
            ).resolve(RulesReleaseReference(VERSION, manifest.sha256()), HOST, offline = false)
        }
        assertTrue(!root.resolve("blobs").exists() || root.resolve("blobs").listDirectoryEntries().isEmpty())
    }

    private fun verifier(jar: ByteArray): RulesSignatureVerifier = RulesSignatureVerifier { _, _, manifestDigest ->
        RulesVerificationReceipt(
            manifestDigest,
            jar.sha256(),
            Instant.parse("2026-08-13T00:00:00Z"),
            "workflow",
            "issuer",
            "root",
        )
    }

    private fun rulesJar(): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(CATALOG_SERVICE))
            zip.write(CATALOG_PROVIDER.encodeToByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("org/minekot/rules/Rule.class"))
            zip.write(byteArrayOf(0))
            zip.closeEntry()
        }
        output.toByteArray()
    }

    private fun manifest(jar: ByteArray): ByteArray =
        """
        {
          "schemaVersion":1,
          "rulesVersion":"${VERSION}",
          "tag":"v${VERSION}",
          "commitSha":"0123456789abcdef0123456789abcdef01234567",
          "publishedAt":"2026-08-13T00:00:00Z",
          "jarName":"minekot-rules-${VERSION}.jar",
          "jarSize":${jar.size},
          "jarSha256":"${jar.sha256()}",
          "spiMajor":1,
          "minimumCoreVersion":"1.0.0",
          "maximumCoreVersionExclusive":"2.0.0",
          "minimumJavaVersion":21,
          "kotlinPsiBaseline":"2.4.20",
          "testedHosts":[{"hostType":"IDEA","hostVersion":"2026.1.5","kotlinVersion":"2.4.20"}],
          "catalogProvider":"${CATALOG_PROVIDER}",
          "configurationSchemaVersion":1
        }
        """.trimIndent().encodeToByteArray()

    private class QueueTransport(vararg responses: ByteArray) : RulesHttpTransport {
        private val responses = responses.toMutableList()
        var requestCount: Int = 0
            private set

        override fun get(request: RulesHttpRequest): RulesHttpResponse {
            requestCount++
            return RulesHttpResponse(HTTP_OK, responses.removeFirst())
        }
    }

    private companion object {
        const val VERSION = "1.0.7"
        const val RELEASE_BASE = "https://example.invalid/releases"
        const val CATALOG_PROVIDER = "org.minekot.rules.MineKotRulesCatalog"
        const val CATALOG_SERVICE = "META-INF/services/org.minekot.inspections.core.MineKotInspectionCatalog"
        const val HTTP_OK = 200
        const val HTTP_FAILURE = 503
        const val SHA_LENGTH = 64
        const val MANIFEST_RESOLUTION_REQUEST_COUNT = 2
        const val FULL_RESOLUTION_REQUEST_COUNT = 3
        val BUNDLE: ByteArray = "bundle".encodeToByteArray()
        val HOST = RulesHostDescriptor(RulesHostType.IDEA, "2026.1.5", "2.4.20", "1.0.0", 21, 1)
    }
}
