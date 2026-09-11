package org.minekot.inspections.loader.runtime

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** Adversarial archive tests for the pre-class-definition boundary. */
class RulesArtifactValidatorTest {
    /** A thin JAR with the exact signed provider is accepted. */
    @Test
    fun `accepts exact catalog provider`() {
        val bytes = archive(
            SERVICE to "${PROVIDER}\n".encodeToByteArray(),
            "org/minekot/rules/Catalog.class" to "class".encodeToByteArray(),
        )

        RulesArtifactValidator.validate(bytes, bytes.size.toLong(), bytes.sha256(), PROVIDER)
    }

    /** Shared API classes cannot be smuggled into the child loader. */
    @Test
    fun `rejects forbidden shared classes`() {
        val bytes = archive(
            SERVICE to "${PROVIDER}\n".encodeToByteArray(),
            "org/minekot/inspections/core/Fake.class" to byteArrayOf(1),
        )

        assertFailsWith<IllegalArgumentException> {
            RulesArtifactValidator.validate(bytes, bytes.size.toLong(), bytes.sha256(), PROVIDER)
        }
    }

    /** Traversal and nested archives are rejected before extraction or class loading. */
    @Test
    fun `rejects unsafe and nested entries`() {
        listOf("../escape.class", "nested/rules.jar").forEach { unsafeName ->
            val bytes = archive(
                SERVICE to "${PROVIDER}\n".encodeToByteArray(),
                unsafeName to byteArrayOf(1),
            )
            assertFailsWith<IllegalArgumentException> {
                RulesArtifactValidator.validate(bytes, bytes.size.toLong(), bytes.sha256(), PROVIDER)
            }
        }
    }

    /** Service metadata must name only the provider committed by the signed manifest. */
    @Test
    fun `rejects provider substitution`() {
        val bytes = archive(SERVICE to "attacker.Catalog\n".encodeToByteArray())

        assertFailsWith<IllegalArgumentException> {
            RulesArtifactValidator.validate(bytes, bytes.size.toLong(), bytes.sha256(), PROVIDER)
        }
    }

    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content)
                output.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private companion object {
        const val SERVICE = "META-INF/services/org.minekot.inspections.core.MineKotInspectionCatalog"
        const val PROVIDER = "org.minekot.rules.MineKotRulesCatalog"
    }
}
