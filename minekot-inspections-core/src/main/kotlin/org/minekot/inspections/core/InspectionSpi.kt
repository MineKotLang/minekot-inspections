package org.minekot.inspections.core

import org.jetbrains.kotlin.psi.*
import java.util.*

/** Immutable host capabilities available to a rule. */
public interface InspectionCapabilities {
    /** Returns whether named optional capability is available. */
    public fun supports(capabilityId: String): Boolean
}

/** Pure resolved-call identity returned by a host-owned analysis implementation.
 * @property callableId fully qualified callable or constructor identity.
 */
public data class ResolvedInspectionCall(public val callableId: String)

/** Host-neutral semantic details for one resolved call.
 * @property callableId fully qualified callable or constructor identity.
 * @property dispatchReceiverTypeId fully qualified dispatch-receiver type when present.
 * @property extensionReceiverTypeId fully qualified extension-receiver type when present.
 * @property returnTypeId fully qualified return type when available.
 * @property parameterNames ordered declared parameter names.
 * @property parameterTypeIds ordered fully qualified parameter types.
 * @property annotationIds fully qualified annotation identities on resolved callable.
 */
public data class ResolvedInspectionCallDetails(
    public val callableId: String,
    public val dispatchReceiverTypeId: String? = null,
    public val extensionReceiverTypeId: String? = null,
    public val returnTypeId: String? = null,
    public val parameterNames: List<String> = emptyList(),
    public val parameterTypeIds: List<String> = emptyList(),
    public val annotationIds: Set<String> = emptySet(),
)

/** Host-neutral semantic details for one expression.
 * @property actualTypeId fully qualified expression type when available.
 * @property expectedTypeId fully qualified expected type when available.
 * @property constantValue rendered compile-time constant when available.
 * @property suspendContext whether expression executes inside a suspend-compatible context.
 * @property mainThreadContext whether host proves expression executes on platform main thread.
 */
public data class InspectionExpressionFacts(
    public val actualTypeId: String? = null,
    public val expectedTypeId: String? = null,
    public val constantValue: String? = null,
    public val suspendContext: Boolean = false,
    public val mainThreadContext: Boolean = false,
)

/** Optional host capability for resolving calls without leaking analysis objects across the SPI. */
public interface ResolvedCallCapability : InspectionCapabilities {
    /** Resolves one call while the host-owned PSI read action is active. */
    public fun resolveCall(expression: KtCallExpression): ResolvedInspectionCall?
}

/** Optional richer call-resolution capability. */
public interface ResolvedCallDetailsCapability : ResolvedCallCapability {
    /** Resolves immutable call details while host-owned analysis remains active. */
    public fun resolveCallDetails(expression: KtCallExpression): ResolvedInspectionCallDetails?
}

/** Optional immutable expression-analysis capability. */
public interface ExpressionFactsCapability : InspectionCapabilities {
    /** Resolves expression facts while host-owned analysis remains active. */
    public fun expressionFacts(expression: KtExpression): InspectionExpressionFacts?
}

/** Optional host capability for checking replacement availability on active analysis classpath. */
public interface SymbolAvailabilityCapability : InspectionCapabilities {
    /** Returns whether fully qualified callable or class identity is available to analyzed source. */
    public fun isSymbolAvailable(symbolId: String): Boolean
}

/** Optional host capability for classifying implicit outer-receiver references. */
public interface ExplicitOuterReceiverCapability : ResolvedCallCapability {
    /** Returns whether an unqualified reference resolves to the enclosing class or object instance. */
    public fun isImplicitOuterReceiver(expression: KtNameReferenceExpression): Boolean
}

/** Element-scoped suppression query owned by host adapter. */
public fun interface InspectionSuppressionResolver {
    /** Returns whether rule is suppressed at element or an enclosing scope. */
    public fun isSuppressed(ruleId: String, element: KtElement): Boolean
}

/** Per-file analysis input. PSI must not escape session lifetime.
 * @property file Kotlin PSI root owned by the host.
 * @property fileId stable host file identity.
 * @property sourceText immutable analyzed snapshot.
 * @property sourceSha256 digest of the analyzed snapshot.
 * @property options validated immutable option values.
 * @property suppressionResolver element-scoped suppression boundary.
 * @property capabilities optional host behavior boundary.
 */
public data class InspectionContext(
    public val file: KtFile,
    public val fileId: String,
    public val sourceText: String,
    public val sourceSha256: String,
    public val options: Map<String, InspectionOptionValue>,
    public val suppressionResolver: InspectionSuppressionResolver,
    public val capabilities: InspectionCapabilities,
)

/** Finding sink. Implementations must deep-copy findings before returning from analysis. */
public fun interface InspectionReporter {
    /** Reports one host-neutral finding. */
    public fun report(finding: InspectionFinding)
}

/** One isolated per-file rule session. */
public interface MineKotInspectionSession : AutoCloseable {
    /** Creates visitor for this session. */
    public fun createVisitor(reporter: InspectionReporter): KtVisitorVoid

    /** Releases any session-local state. */
    override fun close(): Unit = Unit
}

/** Factory for isolated rule sessions. */
public fun interface MineKotInspection {
    /** Creates one session for one file analysis. */
    public fun createSession(context: InspectionContext): MineKotInspectionSession
}

/** Artifact-level catalog discovered exactly once per rules generation. */
public interface MineKotInspectionCatalog {
    /** SPI major required by this catalog. */
    public val spiMajor: Int

    /** Immutable rule metadata. */
    public val inspections: List<InspectionDescriptor>

    /** Creates rule implementation lazily by stable ID. */
    public fun createInspection(ruleId: String): MineKotInspection?
}

/** Optional additive catalog capability for lifecycle and safe activation defaults. */
public interface MineKotInspectionCatalogDefaults {
    /** Defaults keyed only by canonical rule ID. Omitted rules use [InspectionRuleDefaults]. */
    public val ruleDefaults: Map<String, InspectionRuleDefaults>
}

/** Discovers exactly one validated catalog from classloader. */
public fun loadMineKotInspectionCatalog(classLoader: ClassLoader): MineKotInspectionCatalog {
    val catalogs = ServiceLoader.load(MineKotInspectionCatalog::class.java, classLoader).toList()
    require(catalogs.size == 1) { "Expected exactly one MineKot inspection catalog, found ${catalogs.size}." }
    return catalogs.single().requireValidCatalog()
}
