package org.minekot.inspections.core

/** Current shared project-policy schema. */
public const val MINEKOT_INSPECTIONS_POLICY_SCHEMA_VERSION: Int = 1

/** Strict project-wide inspection policy keyed by stable rule identity.
 * @property schemaVersion parsed policy schema.
 * @property rules immutable configured rule overrides.
 */
public data class InspectionPolicy(
    public val schemaVersion: Int,
    public val rules: Map<String, InspectionRulePolicy>,
)

/** Project policy for one rule.
 * @property enabled whether host executes rule.
 * @property severity optional baseline severity override.
 * @property options configured option values before schema validation.
 */
public data class InspectionRulePolicy(
    public val enabled: Boolean = true,
    public val severity: RuleSeverity? = null,
    public val options: Map<String, InspectionOptionValue> = emptyMap(),
)

/** Fully validated rule policy consumed by adapters.
 * @property enabled whether host executes rule.
 * @property severity effective baseline severity.
 * @property options complete validated options including defaults.
 */
public data class ResolvedInspectionRulePolicy(
    public val enabled: Boolean,
    public val severity: RuleSeverity,
    public val options: Map<String, InspectionOptionValue>,
)

/** Host presentation mode applied after shared project baseline. */
public enum class InspectionMode {
    /** Preserve policy or descriptor severity. */
    BASELINE,

    /** Promote enabled findings to errors for repository maintainers. */
    MAINTAINER,

    /** Present findings as warnings or weak warnings for script authors. */
    SCRIPTER,
}

/** Applies host mode after shared policy severity. */
public fun RuleSeverity.forMode(mode: InspectionMode): RuleSeverity =
    when (mode) {
        InspectionMode.BASELINE -> this
        InspectionMode.MAINTAINER -> RuleSeverity.ERROR
        InspectionMode.SCRIPTER -> when (this) {
            RuleSeverity.INFO,
            RuleSeverity.WEAK_WARNING,
            -> RuleSeverity.WEAK_WARNING
            RuleSeverity.WARNING,
            RuleSeverity.ERROR,
            -> RuleSeverity.WARNING
        }
    }

/** Parses constrained MineKot policy YAML with strict keys and scalar types. */
public fun parseInspectionPolicy(source: String): InspectionPolicy = InspectionPolicyParser(source).parse()

/** Validates rule identities and option schemas, returning policies under canonical IDs. */
public fun InspectionPolicy.resolve(catalog: MineKotInspectionCatalog): Map<String, ResolvedInspectionRulePolicy> {
    require(this.schemaVersion == MINEKOT_INSPECTIONS_POLICY_SCHEMA_VERSION) {
        "Unsupported MineKot inspection policy schema ${this.schemaVersion}."
    }
    val descriptorsByIdentity = buildMap {
        catalog.inspections.forEach { descriptor ->
            put(descriptor.id, descriptor)
            descriptor.aliases.forEach { alias -> put(alias, descriptor) }
        }
    }
    val canonicalPolicies = mutableMapOf<String, InspectionRulePolicy>()
    this.rules.forEach { (configuredId, policy) ->
        val descriptor = requireNotNull(descriptorsByIdentity[configuredId]) {
            "Unknown MineKot inspection rule ${configuredId}."
        }
        require(canonicalPolicies.put(descriptor.id, policy) == null) {
            "MineKot inspection ${descriptor.id} is configured more than once through an alias."
        }
    }
    return catalog.inspections.associate { descriptor ->
        val configured = canonicalPolicies[descriptor.id]
        val defaults = (catalog as? MineKotInspectionCatalogDefaults)
            ?.ruleDefaults
            ?.get(descriptor.id)
            ?: InspectionRuleDefaults()
        descriptor.id to ResolvedInspectionRulePolicy(
            enabled = configured?.enabled ?: defaults.defaultEnabled,
            severity = configured?.severity ?: defaults.recommendedSeverity ?: descriptor.defaultSeverity,
            options = descriptor.validateOptions(configured?.options.orEmpty()),
        )
    }
}

private class InspectionPolicyParser(source: String) {
    private val lines: List<PolicyLine> = source.lineSequence()
        .mapIndexedNotNull(::parseLine)
        .toList()

    fun parse(): InspectionPolicy {
        var schemaVersion: Int? = null
        var rules: Map<String, InspectionRulePolicy>? = null
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            require(line.indent == 0) { line.error("Top-level keys must not be indented.") }
            when (line.key) {
                "schema-version" -> {
                    require(schemaVersion == null) { line.error("Duplicate schema-version key.") }
                    schemaVersion = line.requiredValue().toIntOrNull()
                    require(schemaVersion != null) { line.error("schema-version must be an integer.") }
                    index++
                }
                "rules" -> {
                    require(rules == null) { line.error("Duplicate rules key.") }
                    line.requireMapping()
                    val parsed = parseRules(index + 1)
                    rules = parsed.value
                    index = parsed.nextIndex
                }
                else -> throw IllegalArgumentException(line.error("Unknown top-level key ${line.key}."))
            }
        }
        return InspectionPolicy(
            schemaVersion = requireNotNull(schemaVersion) { "Missing schema-version key." },
            rules = requireNotNull(rules) { "Missing rules key." },
        )
    }

    private fun parseRules(startIndex: Int): Parsed<Map<String, InspectionRulePolicy>> {
        val rules = linkedMapOf<String, InspectionRulePolicy>()
        var index = startIndex
        while (index < lines.size && lines[index].indent > 0) {
            val line = lines[index]
            require(line.indent == RULE_INDENT) { line.error("Rule IDs must use two-space indentation.") }
            line.requireMapping()
            require(ruleIdentityPattern.matches(line.key)) { line.error("Invalid rule ID or alias ${line.key}.") }
            require(line.key !in rules) { line.error("Duplicate rule ${line.key}.") }
            val parsed = parseRule(index + 1)
            rules[line.key] = parsed.value
            index = parsed.nextIndex
        }
        return Parsed(rules, index)
    }

    private fun parseRule(startIndex: Int): Parsed<InspectionRulePolicy> {
        var enabled = true
        var severity: RuleSeverity? = null
        var options: Map<String, InspectionOptionValue>? = null
        val seen = mutableSetOf<String>()
        var index = startIndex
        while (index < lines.size && lines[index].indent > RULE_INDENT) {
            val line = lines[index]
            require(line.indent == RULE_PROPERTY_INDENT) {
                line.error("Rule properties must use four-space indentation.")
            }
            require(seen.add(line.key)) { line.error("Duplicate rule property ${line.key}.") }
            when (line.key) {
                "enabled" -> {
                    enabled = line.requiredValue().toStrictBoolean(line)
                    index++
                }
                "severity" -> {
                    severity = line.requiredValue().toSeverity(line)
                    index++
                }
                "options" -> {
                    line.requireMapping()
                    val parsed = parseOptions(index + 1)
                    options = parsed.value
                    index = parsed.nextIndex
                }
                else -> throw IllegalArgumentException(line.error("Unknown rule property ${line.key}."))
            }
        }
        return Parsed(InspectionRulePolicy(enabled, severity, options ?: emptyMap()), index)
    }

    private fun parseOptions(startIndex: Int): Parsed<Map<String, InspectionOptionValue>> {
        val options = linkedMapOf<String, InspectionOptionValue>()
        var index = startIndex
        while (index < lines.size && lines[index].indent > RULE_PROPERTY_INDENT) {
            val line = lines[index]
            require(line.indent == OPTION_INDENT) { line.error("Options must use six-space indentation.") }
            require(optionKeyPattern.matches(line.key)) { line.error("Invalid option key ${line.key}.") }
            require(line.key !in options) { line.error("Duplicate option ${line.key}.") }
            options[line.key] = line.requiredValue().toOptionValue(line)
            index++
        }
        return Parsed(options, index)
    }

    private fun parseLine(index: Int, raw: String): PolicyLine? {
        require('\t' !in raw) { "MineKot inspection policy line ${index + 1} contains a tab." }
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith('#')) return null
        require('#' !in trimmed) { "MineKot inspection policy line ${index + 1} uses an inline comment." }
        require('\'' !in trimmed) { "MineKot inspection policy line ${index + 1} uses a single quote." }
        val indent = raw.indexOfFirst { character -> character != ' ' }.let { first ->
            if (first < 0) raw.length else first
        }
        require(indent % INDENT_SIZE == 0) {
            "MineKot inspection policy line ${index + 1} must use two-space indentation."
        }
        val separator = trimmed.indexOf(':')
        require(separator > 0) { "MineKot inspection policy line ${index + 1} is not a mapping entry." }
        val key = trimmed.substring(0, separator).trim()
        val value = trimmed.substring(separator + 1).trim().ifEmpty { null }
        require(key.isNotBlank()) { "MineKot inspection policy line ${index + 1} has a blank key." }
        return PolicyLine(index + 1, indent, key, value)
    }

    private companion object {
        const val INDENT_SIZE = 2
        const val RULE_INDENT = 2
        const val RULE_PROPERTY_INDENT = 4
        const val OPTION_INDENT = 6
        val ruleIdentityPattern: Regex =
            Regex("(?:[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+|[A-Za-z][A-Za-z0-9]*)")
        val optionKeyPattern: Regex = Regex("[a-z][a-zA-Z0-9]*")
    }
}

private data class PolicyLine(
    val number: Int,
    val indent: Int,
    val key: String,
    val value: String?,
) {
    fun requireMapping() {
        require(value == null) { error("${key} must be a mapping.") }
    }

    fun requiredValue(): String = requireNotNull(value) { error("${key} requires a value.") }

    fun error(message: String): String = "MineKot inspection policy line ${number}: ${message}"
}

private data class Parsed<T>(val value: T, val nextIndex: Int)

private fun String.toStrictBoolean(line: PolicyLine): Boolean =
    when (this) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException(line.error("${line.key} must be true or false."))
    }

private fun String.toSeverity(line: PolicyLine): RuleSeverity =
    runCatching { RuleSeverity.valueOf(this) }
        .getOrElse { throw IllegalArgumentException(line.error("Unknown severity ${this}.")) }

private fun String.toOptionValue(line: PolicyLine): InspectionOptionValue {
    if (startsWith('[') || endsWith(']')) {
        require(startsWith('[') && endsWith(']')) { line.error("Malformed inline list.") }
        val content = substring(1, length - 1).trim()
        val values = if (content.isEmpty()) emptyList() else content.split(',').map { element ->
            element.trim().also { require(it.isNotEmpty()) { line.error("Inline list contains an empty value.") } }
                .toScalarOptionValue(line)
        }
        return InspectionOptionValue.ListValue(values)
    }
    return toScalarOptionValue(line)
}

private fun String.toScalarOptionValue(line: PolicyLine): InspectionOptionValue =
    when {
        this == "true" -> InspectionOptionValue.BooleanValue(true)
        this == "false" -> InspectionOptionValue.BooleanValue(false)
        matches(Regex("-?[0-9]+")) -> InspectionOptionValue.IntegerValue(toLong())
        startsWith('"') || endsWith('"') -> InspectionOptionValue.StringValue(unquote(line))
        isNotEmpty() -> InspectionOptionValue.StringValue(this)
        else -> throw IllegalArgumentException(line.error("Option value must not be empty."))
    }

private fun String.unquote(line: PolicyLine): String {
    require(length >= 2 && first() == '"' && last() == '"') { line.error("Malformed double-quoted string.") }
    return substring(1, length - 1)
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
}
