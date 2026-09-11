package org.minekot.inspections.core

/** Stable inspection API version understood by this runtime. */
public const val MINEKOT_INSPECTIONS_SPI_MAJOR: Int = 1

/** Logical grouping presented by inspection hosts. */
public enum class InspectionCategory {
    /** Formatting and source-layout policy. */
    CODE_STYLE,

    /** Threading, cancellation, and structured-concurrency policy. */
    CONCURRENCY,

    /** Semantic defect or invalid behavior. */
    CORRECTNESS,

    /** Runtime or build performance policy. */
    PERFORMANCE,

    /** Security-sensitive behavior. */
    SECURITY,
}

/** Host-neutral severity used by inspection findings and project policy. */
public enum class RuleSeverity {
    /** Informational diagnostic. */
    INFO,

    /** Low-visibility warning. */
    WEAK_WARNING,

    /** Standard warning. */
    WARNING,

    /** Build- or editor-blocking error. */
    ERROR,
}

/** Lifecycle state of one catalog rule. */
public enum class InspectionRuleLifecycle {
    /** Supported rule used for new configuration. */
    ACTIVE,

    /** Compatibility rule retained while consumers migrate to its replacement. */
    DEPRECATED,
}

/** Optional catalog-owned defaults for one rule.
 * @property defaultEnabled whether hosts run the rule when project policy omits it.
 * @property lifecycle compatibility lifecycle shown by hosts.
 * @property replacementRuleId stable replacement rule identity for deprecated rules.
 * @property recommendedSeverity catalog recommendation overriding descriptor severity when policy is absent.
 */
public data class InspectionRuleDefaults(
    public val defaultEnabled: Boolean = true,
    public val lifecycle: InspectionRuleLifecycle = InspectionRuleLifecycle.ACTIVE,
    public val replacementRuleId: String? = null,
    public val recommendedSeverity: RuleSeverity? = null,
)

/** Primitive configuration values permitted to cross the inspection ABI. */
public sealed interface InspectionOptionValue {
    /** Boolean option value.
     * @property value primitive value.
     */
    public data class BooleanValue(public val value: Boolean) : InspectionOptionValue

    /** Integer option value.
     * @property value primitive value.
     */
    public data class IntegerValue(public val value: Long) : InspectionOptionValue

    /** Text or enum option value.
     * @property value primitive value.
     */
    public data class StringValue(public val value: String) : InspectionOptionValue

    /** Ordered scalar-list option value.
     * @property value immutable ordered values.
     */
    public data class ListValue(public val value: List<InspectionOptionValue>) : InspectionOptionValue
}

/** Schema for one rule option. */
public sealed interface InspectionOptionDescriptor {
    /** Stable option key. */
    public val key: String

    /** Human-readable option description. */
    public val description: String

    /** Default value. */
    public val defaultValue: InspectionOptionValue

    /** Boolean option schema.
     * @property key stable configuration key.
     * @property description user-facing behavior.
     * @property defaultValue value used when absent.
     */
    public data class BooleanOption(
        override val key: String,
        override val description: String,
        override val defaultValue: InspectionOptionValue.BooleanValue,
    ) : InspectionOptionDescriptor

    /** Bounded integer option schema.
     * @property key stable configuration key.
     * @property description user-facing behavior.
     * @property defaultValue value used when absent.
     * @property minimum inclusive lower bound.
     * @property maximum inclusive upper bound.
     */
    public data class IntegerOption(
        override val key: String,
        override val description: String,
        override val defaultValue: InspectionOptionValue.IntegerValue,
        public val minimum: Long? = null,
        public val maximum: Long? = null,
    ) : InspectionOptionDescriptor

    /** String option schema.
     * @property key stable configuration key.
     * @property description user-facing behavior.
     * @property defaultValue value used when absent.
     */
    public data class StringOption(
        override val key: String,
        override val description: String,
        override val defaultValue: InspectionOptionValue.StringValue,
    ) : InspectionOptionDescriptor

    /** Enum option schema.
     * @property key stable configuration key.
     * @property description user-facing behavior.
     * @property defaultValue value used when absent.
     * @property allowedValues complete accepted values.
     */
    public data class EnumOption(
        override val key: String,
        override val description: String,
        override val defaultValue: InspectionOptionValue.StringValue,
        public val allowedValues: Set<String>,
    ) : InspectionOptionDescriptor

    /** Homogeneous scalar-list option schema.
     * @property key stable configuration key.
     * @property description user-facing behavior.
     * @property defaultValue value used when absent.
     * @property elementType required type for every entry.
     */
    public data class ListOption(
        override val key: String,
        override val description: String,
        override val defaultValue: InspectionOptionValue.ListValue,
        public val elementType: InspectionOptionType,
    ) : InspectionOptionDescriptor
}

/** Scalar element type accepted by list options. */
public enum class InspectionOptionType {
    /** Boolean list entries. */
    BOOLEAN,

    /** Integer list entries. */
    INTEGER,

    /** String list entries. */
    STRING,
}

/** Immutable metadata for one rule.
 * @property id permanent namespaced rule identity.
 * @property aliases deprecated identities accepted for migration.
 * @property displayName user-facing short name.
 * @property description user-facing behavior description.
 * @property category logical rule group.
 * @property defaultSeverity severity before policy overrides.
 * @property options declared configuration schema.
 */
public data class InspectionDescriptor(
    public val id: String,
    public val aliases: Set<String>,
    public val displayName: String,
    public val description: String,
    public val category: InspectionCategory,
    public val defaultSeverity: RuleSeverity,
    public val options: List<InspectionOptionDescriptor> = emptyList(),
)

/** One guarded source replacement using half-open offsets.
 * @property startOffset inclusive source offset.
 * @property endOffset exclusive source offset.
 * @property expectedText stale-source guard.
 * @property replacement replacement text.
 */
public data class TextEdit(
    public val startOffset: Int,
    public val endOffset: Int,
    public val expectedText: String,
    public val replacement: String,
)

/** Atomic correction plan. Hosts reject the complete plan if any guard fails.
 * @property id stable correction identity.
 * @property label user-facing action label.
 * @property fileId host-owned file identity.
 * @property sourceSha256 digest of the analyzed source snapshot.
 * @property edits ordered guarded edits.
 */
public data class CorrectionPlan(
    public val id: String,
    public val label: String,
    public val fileId: String,
    public val sourceSha256: String,
    public val edits: List<TextEdit>,
)

/** Host-neutral diagnostic emitted by a rule.
 * @property ruleId stable rule identity.
 * @property messageId stable message identity.
 * @property message rendered user-facing message.
 * @property messageArguments primitive rendering arguments.
 * @property startOffset inclusive source offset.
 * @property endOffset exclusive source offset.
 * @property severity host-neutral severity.
 * @property corrections optional guarded correction plans.
 */
public data class InspectionFinding(
    public val ruleId: String,
    public val messageId: String,
    public val message: String,
    public val messageArguments: List<String>,
    public val startOffset: Int,
    public val endOffset: Int,
    public val severity: RuleSeverity,
    public val corrections: List<CorrectionPlan> = emptyList(),
)
