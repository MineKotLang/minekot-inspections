package org.minekot.inspections.loader.runtime

import dev.sigstore.KeylessVerifier
import dev.sigstore.VerificationOptions
import dev.sigstore.bundle.Bundle
import dev.sigstore.strings.StringMatcher
import org.minekot.inspections.loader.*
import java.io.StringReader
import java.time.Instant

/** Verifies official rules manifests against MineKot GitHub Actions identity. */
public class SigstoreRulesSignatureVerifier(
    private val trustedRootVersion: String = "sigstore-public-current",
) : RulesSignatureVerifier, RulesIndexSignatureVerifier {
    override fun verify(
        manifest: ByteArray,
        bundle: ByteArray,
        manifestSha256: String,
    ): RulesVerificationReceipt {
        verifyPayload(manifest, bundle)
        return RulesVerificationReceipt(
            manifestSha256 = manifestSha256,
            jarSha256 = RulesManifestCodec.decode(manifest).jarSha256,
            verifiedAt = Instant.now(),
            signerIdentity = SIGNER_IDENTITY,
            issuer = ISSUER,
            trustedRootVersion = trustedRootVersion,
        )
    }

    /** Verifies one signed stable-channel index under the same official workflow identity. */
    override fun verifyIndex(
        index: ByteArray,
        bundle: ByteArray,
        indexSha256: String,
    ): RulesIndexVerificationReceipt {
        require(index.sha256() == indexSha256) { "Channel index digest mismatch." }
        verifyPayload(index, bundle)
        return RulesIndexVerificationReceipt(
            indexSha256 = indexSha256,
            verifiedAt = Instant.now(),
            signerIdentity = SIGNER_IDENTITY,
            issuer = ISSUER,
            trustedRootVersion = trustedRootVersion,
        )
    }

    private fun verifyPayload(payload: ByteArray, bundle: ByteArray) {
        val parsedBundle = Bundle.from(StringReader(bundle.decodeToString()))
        val matcher = VerificationOptions.CertificateMatcher.fulcio()
            .subjectAlternativeName(StringMatcher.string(SIGNER_IDENTITY))
            .issuer(StringMatcher.string(ISSUER))
            .build()
        val options = VerificationOptions.builder().addCertificateMatchers(matcher).build()
        KeylessVerifier.builder().sigstorePublicDefaults().build().verify(payload, parsedBundle, options)
    }

    /** Pinned official workflow identity policy. */
    public companion object {
        /** Required GitHub Actions OIDC issuer. */
        public const val ISSUER: String = "https://token.actions.githubusercontent.com"

        /** Required repository, workflow, branch, and owner identity. */
        public const val SIGNER_IDENTITY: String =
            "https://github.com/MineKotLang/minekot-rules/.github/workflows/release.yml@refs/heads/master"
    }
}
