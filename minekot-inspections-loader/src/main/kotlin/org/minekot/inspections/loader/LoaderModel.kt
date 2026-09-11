package org.minekot.inspections.loader

import java.nio.file.Path
import java.time.Instant

/** Immutable reference to one exact rules release.
 * @property version exact release version without tag prefix.
 * @property manifestSha256 pinned signed-manifest digest.
 */
public data class RulesReleaseReference(
    public val version: String,
    public val manifestSha256: String,
)

/** One tested host tuple declared by rules manifest.
 * @property hostType adapter family.
 * @property hostVersion exact host version.
 * @property kotlinVersion exact Kotlin PSI version.
 */
public data class RulesHostTuple(
    public val hostType: RulesHostType,
    public val hostVersion: String,
    public val kotlinVersion: String,
)

/** Supported dynamic-rules host. */
public enum class RulesHostType {
    /** Gradle or command-line Detekt host. */
    DETEKT,

    /** IntelliJ Platform host. */
    IDEA,
}

/** Current host identity used for compatibility gating.
 * @property hostType adapter family.
 * @property hostVersion exact host version.
 * @property kotlinVersion exact Kotlin PSI version.
 * @property coreVersion bundled core SPI version.
 * @property javaVersion current Java feature version.
 * @property spiMajor supported SPI major.
 */
public data class RulesHostDescriptor(
    public val hostType: RulesHostType,
    public val hostVersion: String,
    public val kotlinVersion: String,
    public val coreVersion: String,
    public val javaVersion: Int,
    public val spiMajor: Int,
)

/** Strict release manifest model.
 * @property schemaVersion signed manifest schema.
 * @property rulesVersion exact release version.
 * @property tag immutable Git tag.
 * @property commitSha full source commit SHA.
 * @property publishedAt release publication instant.
 * @property jarName exact release asset name.
 * @property jarSize exact compressed byte count.
 * @property jarSha256 exact artifact digest.
 * @property spiMajor required inspection SPI major.
 * @property minimumCoreVersion inclusive supported core version.
 * @property maximumCoreVersionExclusive exclusive supported core version.
 * @property minimumJavaVersion minimum Java feature version.
 * @property kotlinPsiBaseline rules compilation baseline.
 * @property testedHosts exact tested host tuples.
 * @property catalogProvider signed service provider class name.
 * @property configurationSchemaVersion shared policy schema version.
 */
public data class RulesManifest(
    public val schemaVersion: Int,
    public val rulesVersion: String,
    public val tag: String,
    public val commitSha: String,
    public val publishedAt: Instant,
    public val jarName: String,
    public val jarSize: Long,
    public val jarSha256: String,
    public val spiMajor: Int,
    public val minimumCoreVersion: String,
    public val maximumCoreVersionExclusive: String,
    public val minimumJavaVersion: Int,
    public val kotlinPsiBaseline: String,
    public val testedHosts: List<RulesHostTuple>,
    public val catalogProvider: String,
    public val configurationSchemaVersion: Int,
)

/** Result of compatibility evaluation before class definition. */
public sealed interface RulesCompatibility {
    /** Manifest may load in host. */
    public data object Compatible : RulesCompatibility

    /** Manifest must not load in host.
     * @property reasons complete incompatibility explanations.
     */
    public data class Incompatible(public val reasons: List<String>) : RulesCompatibility
}

/** Durable evidence that artifact passed configured trust policy.
 * @property manifestSha256 verified manifest digest.
 * @property jarSha256 verified artifact digest.
 * @property verifiedAt verification instant.
 * @property signerIdentity verified certificate identity.
 * @property issuer verified OIDC issuer.
 * @property trustedRootVersion trusted-root identity used by verifier.
 */
public data class RulesVerificationReceipt(
    public val manifestSha256: String,
    public val jarSha256: String,
    public val verifiedAt: Instant,
    public val signerIdentity: String,
    public val issuer: String,
    public val trustedRootVersion: String,
)

/** One durable verified rules generation used by a host scope.
 * @property version exact rules version.
 * @property manifestSha256 verified manifest digest.
 * @property jarSha256 verified JAR digest.
 * @property activatedAt latest successful activation instant.
 */
public data class RulesCachedGeneration(
    public val version: String,
    public val manifestSha256: String,
    public val jarSha256: String,
    public val activatedAt: Instant,
)

/** Durable active and last-known-good state for one host or project scope.
 * @property scope stable host-owned scope identity.
 * @property active current activated generation.
 * @property lastKnownGood most recent successfully replaced generation.
 */
public data class RulesActivationRecord(
    public val scope: String,
    public val active: RulesCachedGeneration,
    public val lastKnownGood: RulesCachedGeneration?,
)

/** Lease protecting one verified rules blob from cache eviction. */
public interface RulesCacheLease : AutoCloseable {
    /** Verified JAR path. */
    public val jarPath: Path
}

/** Result returned by exact artifact resolver.
 * @property manifest verified strict manifest.
 * @property receipt durable verification evidence.
 * @property lease live cache lease owned by caller.
 */
public data class ResolvedRulesArtifact(
    public val manifest: RulesManifest,
    public val receipt: RulesVerificationReceipt,
    public val lease: RulesCacheLease,
)

/** Resolves one exact signed rules release. */
public fun interface RulesResolver {
    /** Resolves and leases exact release or fails closed. */
    public fun resolve(
        reference: RulesReleaseReference,
        host: RulesHostDescriptor,
        offline: Boolean,
    ): ResolvedRulesArtifact
}

/** Host-provided HTTP boundary. */
public fun interface RulesHttpTransport {
    /** Fetches URL with byte limit and optional conditional request metadata. */
    public fun get(request: RulesHttpRequest): RulesHttpResponse
}

/** Bounded HTTP request.
 * @property url exact asset URL.
 * @property maximumBytes hard response-size limit.
 * @property etag optional conditional-request validator.
 */
public data class RulesHttpRequest(
    public val url: String,
    public val maximumBytes: Long,
    public val etag: String? = null,
)

/** HTTP response detached from any client library.
 * @property statusCode HTTP response status.
 * @property body bounded response bytes.
 * @property etag returned cache validator.
 * @property retryAfterSeconds server-requested retry delay.
 */
public data class RulesHttpResponse(
    public val statusCode: Int,
    public val body: ByteArray,
    public val etag: String? = null,
    public val retryAfterSeconds: Long? = null,
)

/** Verifies detached Sigstore bundle and publisher identity. */
public fun interface RulesSignatureVerifier {
    /** Verifies manifest bytes and returns durable trust receipt. */
    public fun verify(manifest: ByteArray, bundle: ByteArray, manifestSha256: String): RulesVerificationReceipt
}
