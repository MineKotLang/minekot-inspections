package org.minekot.inspections.loader.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Strict manifest-schema tests. */
class RulesManifestCodecTest {
    /** Signed fields decode without accepting implicit defaults. */
    @Test
    fun `decodes complete schema`() {
        assertEquals("1.0.7", RulesManifestCodec.decode(manifest()).rulesVersion)
    }

    /** Unknown keys fail closed so signed semantics cannot drift silently. */
    @Test
    fun `rejects unknown fields`() {
        val bytes = manifest().decodeToString().replaceFirst("{", "{\"unknown\":true,").encodeToByteArray()
        assertFailsWith<IllegalArgumentException> { RulesManifestCodec.decode(bytes) }
    }

    private fun manifest(): ByteArray =
        """
        {
          "schemaVersion":1,
          "rulesVersion":"1.0.7",
          "tag":"v1.0.7",
          "commitSha":"0123456789abcdef0123456789abcdef01234567",
          "publishedAt":"2026-08-13T00:00:00Z",
          "jarName":"minekot-rules-1.0.7.jar",
          "jarSize":${TEST_JAR_SIZE},
          "jarSha256":"${"0".repeat(SHA256_HEX_LENGTH)}",
          "spiMajor":1,
          "minimumCoreVersion":"1.0.0",
          "maximumCoreVersionExclusive":"2.0.0",
          "minimumJavaVersion":21,
          "kotlinPsiBaseline":"2.4.20",
          "testedHosts":[{"hostType":"IDEA","hostVersion":"2026.1.5","kotlinVersion":"2.4.20"}],
          "catalogProvider":"org.minekot.rules.MineKotRulesCatalog",
          "configurationSchemaVersion":1
        }
        """.trimIndent().encodeToByteArray()

    private companion object {
        const val TEST_JAR_SIZE: Int = 42
        const val SHA256_HEX_LENGTH: Int = 64
    }
}
