package org.minekot.inspections.loader

import java.time.Instant

/** One signed stable-channel index.
 * @property schemaVersion index schema version.
 * @property sequence monotonically increasing channel sequence.
 * @property previousIndexSha256 digest of the immediately previous index.
 * @property generatedAt index generation instant.
 * @property releases stable ordered release entries.
 */
public data class RulesChannelIndex(
    public val schemaVersion: Int,
    public val sequence: Long,
    public val previousIndexSha256: String?,
    public val generatedAt: Instant,
    public val releases: List<RulesChannelRelease>,
)

/** Compatibility and withdrawal metadata for one channel release.
 * @property version exact rules version.
 * @property manifestSha256 immutable signed-manifest digest.
 * @property prerelease whether release requires explicit host opt-in.
 * @property withdrawn ordinary compatibility withdrawal marker.
 * @property securityRevoked fail-closed security revocation marker.
 * @property spiMajor required inspection SPI major.
 * @property minimumCoreVersion inclusive supported core version.
 * @property maximumCoreVersionExclusive exclusive supported core version.
 * @property minimumJavaVersion minimum Java feature version.
 * @property testedHosts exact tested host tuples.
 */
public data class RulesChannelRelease(
    public val version: String,
    public val manifestSha256: String,
    public val prerelease: Boolean,
    public val withdrawn: Boolean,
    public val securityRevoked: Boolean,
    public val spiMajor: Int,
    public val minimumCoreVersion: String,
    public val maximumCoreVersionExclusive: String,
    public val minimumJavaVersion: Int,
    public val testedHosts: List<RulesHostTuple>,
)

/** Durable evidence for one verified signed channel index.
 * @property indexSha256 verified index digest.
 * @property verifiedAt verification instant.
 * @property signerIdentity verified workflow identity.
 * @property issuer verified OIDC issuer.
 * @property trustedRootVersion trusted-root identity.
 */
public data class RulesIndexVerificationReceipt(
    public val indexSha256: String,
    public val verifiedAt: Instant,
    public val signerIdentity: String,
    public val issuer: String,
    public val trustedRootVersion: String,
)

/** Verified channel resolution result.
 * @property index strict signed channel model.
 * @property receipt durable verification evidence.
 * @property etag optional HTTP validator retained with cache.
 */
public data class ResolvedRulesChannel(
    public val index: RulesChannelIndex,
    public val receipt: RulesIndexVerificationReceipt,
    public val etag: String?,
)

/** Resolves a verified stable-channel snapshot. */
public fun interface RulesChannelResolver {
    /** Resolves online-first or exact verified cached state when offline. */
    public fun resolve(host: RulesHostDescriptor, offline: Boolean): ResolvedRulesChannel
}

/** Signature boundary for detached stable-channel index bundles. */
public fun interface RulesIndexSignatureVerifier {
    /** Verifies exact index bytes and official publisher identity. */
    public fun verifyIndex(
        index: ByteArray,
        bundle: ByteArray,
        indexSha256: String,
    ): RulesIndexVerificationReceipt
}

/** Returns whether channel compatibility metadata admits this host. */
public fun RulesChannelRelease.isCompatibleWith(host: RulesHostDescriptor): Boolean =
    this.spiMajor == host.spiMajor &&
        host.javaVersion >= this.minimumJavaVersion &&
        compareChannelVersions(host.coreVersion, this.minimumCoreVersion) >= 0 &&
        compareChannelVersions(host.coreVersion, this.maximumCoreVersionExclusive) < 0 &&
        this.testedHosts.any { tuple ->
            tuple.hostType == host.hostType &&
                tuple.hostVersion == host.hostVersion &&
                tuple.kotlinVersion == host.kotlinVersion
        }

private fun compareChannelVersions(left: String, right: String): Int {
    val leftParts = left.substringBefore('-').split('.').map(String::toInt)
    val rightParts = right.substringBefore('-').split('.').map(String::toInt)
    val size = maxOf(leftParts.size, rightParts.size)
    repeat(size) { index ->
        val comparison = (leftParts.getOrNull(index) ?: 0).compareTo(rightParts.getOrNull(index) ?: 0)
        if (comparison != 0) return comparison
    }
    return 0
}
