package org.minekot.inspections.loader.runtime

import java.util.*
import kotlin.test.*

/** Official Sigstore identity-policy boundary tests. */
class SigstoreRulesSignatureVerifierTest {
    /** Index digest is checked before parsing or trust-root access. */
    @Test
    fun `index digest mismatch fails before bundle verification`() {
        assertFailsWith<IllegalArgumentException> {
            SigstoreRulesSignatureVerifier().verifyIndex(
                "index".encodeToByteArray(),
                "not-a-bundle".encodeToByteArray(),
                "0".repeat(SHA_LENGTH),
            )
        }
    }

    /** Malformed bundles cannot produce durable verification receipts. */
    @Test
    fun `malformed official bundle fails closed`() {
        val payload = "payload".encodeToByteArray()
        assertFails {
            SigstoreRulesSignatureVerifier().verifyIndex(
                payload,
                "{}".encodeToByteArray(),
                payload.sha256(),
            )
        }
    }

    /** Sigstore Java receives the signed SHA-256 digest instead of unhashed payload bytes. */
    @Test
    fun `payload is converted to artifact digest`() {
        val digest = "payload".encodeToByteArray().sha256Bytes()

        assertEquals(
            "239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5",
            HexFormat.of().formatHex(digest),
        )
    }

    /** Workflow policy pins repository, workflow file, branch, and GitHub issuer. */
    @Test
    fun `official identity policy is exact`() {
        assertEquals("https://token.actions.githubusercontent.com", SigstoreRulesSignatureVerifier.ISSUER)
        assertEquals(
            "https://github.com/MineKotLang/minekot-rules/.github/workflows/release.yml@refs/heads/master",
            SigstoreRulesSignatureVerifier.SIGNER_IDENTITY,
        )
    }

    private companion object {
        const val SHA_LENGTH = 64
    }
}
