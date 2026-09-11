package org.minekot.inspections.core

private val inspectionIdPattern: Regex = Regex("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+")
private val inspectionAliasPattern: Regex = Regex("(?:[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+|[A-Za-z][A-Za-z0-9]*)")

/** Validates catalog metadata and returns it unchanged. */
public fun MineKotInspectionCatalog.requireValidCatalog(): MineKotInspectionCatalog {
    require(this.spiMajor == MINEKOT_INSPECTIONS_SPI_MAJOR) {
        "Unsupported inspection SPI major ${this.spiMajor}."
    }
    val allIds = mutableSetOf<String>()
    this.inspections.forEach { descriptor ->
        descriptor.requireValidDescriptor()
        require(allIds.add(descriptor.id)) { "Duplicate inspection ID ${descriptor.id}." }
        descriptor.aliases.forEach { alias ->
            require(allIds.add(alias)) { "Duplicate inspection ID or alias ${alias}." }
        }
    }
    val defaults = (this as? MineKotInspectionCatalogDefaults)?.ruleDefaults.orEmpty()
    val canonicalIds = this.inspections.map(InspectionDescriptor::id).toSet()
    require(defaults.keys.all { ruleId -> ruleId in canonicalIds }) {
        "Catalog defaults reference unknown inspection IDs."
    }
    defaults.forEach { (ruleId, ruleDefaults) ->
        require(ruleDefaults.replacementRuleId != ruleId) {
            "Inspection ${ruleId} cannot replace itself."
        }
        require(
            ruleDefaults.lifecycle == InspectionRuleLifecycle.DEPRECATED || ruleDefaults.replacementRuleId == null,
        ) {
            "Active inspection ${ruleId} cannot declare a replacement."
        }
        require(ruleDefaults.replacementRuleId == null || ruleDefaults.replacementRuleId in canonicalIds) {
            "Inspection ${ruleId} references unknown replacement ${ruleDefaults.replacementRuleId}."
        }
    }
    return this
}

/** Validates descriptor identity and option schema. */
public fun InspectionDescriptor.requireValidDescriptor(): InspectionDescriptor {
    require(inspectionIdPattern.matches(this.id)) { "Invalid inspection ID ${this.id}." }
    this.aliases.forEach { alias ->
        require(inspectionAliasPattern.matches(alias)) { "Invalid inspection alias ${alias}." }
        require(alias != this.id) { "Inspection alias duplicates primary ID ${this.id}." }
    }
    require(this.displayName.isNotBlank()) { "Inspection ${this.id} has blank display name." }
    require(this.description.isNotBlank()) { "Inspection ${this.id} has blank description." }
    val optionKeys = mutableSetOf<String>()
    this.options.forEach { option ->
        require(optionKeys.add(option.key)) { "Inspection ${this.id} has duplicate option ${option.key}." }
        option.requireValidOption()
    }
    return this
}

/** Resolves defaults and strictly validates provided rule options. */
public fun InspectionDescriptor.validateOptions(
    configured: Map<String, InspectionOptionValue>,
): Map<String, InspectionOptionValue> {
    val schemas = this.options.associateBy(InspectionOptionDescriptor::key)
    val unknown = configured.keys - schemas.keys
    require(unknown.isEmpty()) { "Inspection ${this.id} has unknown options: ${unknown.sorted().joinToString()}." }
    return schemas.mapValues { (key, schema) ->
        val value = configured[key] ?: schema.defaultValue
        schema.requireValue(value)
        value
    }
}

/** Validates finding ranges, identity, and correction guards against source snapshot. */
public fun InspectionFinding.requireValidFinding(sourceText: String, sourceSha256: String): InspectionFinding {
    require(inspectionIdPattern.matches(this.ruleId)) { "Invalid finding rule ID ${this.ruleId}." }
    require(this.startOffset in 0..sourceText.length) { "Finding start offset is outside source." }
    require(this.endOffset in this.startOffset..sourceText.length) { "Finding end offset is outside source." }
    this.corrections.forEach { correction -> correction.requireValidCorrection(sourceText, sourceSha256) }
    return this
}

/** Applies complete correction atomically after all digest/range/text guards pass. */
public fun CorrectionPlan.applyTo(sourceText: String, sourceSha256: String): String {
    this.requireValidCorrection(sourceText, sourceSha256)
    val result = StringBuilder(sourceText)
    this.edits.sortedByDescending(TextEdit::startOffset).forEach { edit ->
        result.replace(edit.startOffset, edit.endOffset, edit.replacement)
    }
    return result.toString()
}

private fun InspectionOptionDescriptor.requireValidOption() {
    require(this.key.matches(Regex("[a-z][a-zA-Z0-9]*"))) { "Invalid option key ${this.key}." }
    require(this.description.isNotBlank()) { "Option ${this.key} has blank description." }
    if (this is InspectionOptionDescriptor.IntegerOption) {
        require(this.minimum == null || this.maximum == null || this.minimum <= this.maximum) {
            "Option ${this.key} has inverted bounds."
        }
    }
    if (this is InspectionOptionDescriptor.EnumOption) {
        require(this.allowedValues.isNotEmpty()) { "Enum option ${this.key} has no values." }
    }
    this.requireValue(this.defaultValue)
}

private fun InspectionOptionDescriptor.requireValue(value: InspectionOptionValue) {
    when (this) {
        is InspectionOptionDescriptor.BooleanOption -> require(value is InspectionOptionValue.BooleanValue)
        is InspectionOptionDescriptor.IntegerOption -> {
            require(value is InspectionOptionValue.IntegerValue)
            require(this.minimum == null || value.value >= this.minimum) { "Option ${this.key} is below minimum." }
            require(this.maximum == null || value.value <= this.maximum) { "Option ${this.key} is above maximum." }
        }
        is InspectionOptionDescriptor.StringOption -> require(value is InspectionOptionValue.StringValue)
        is InspectionOptionDescriptor.EnumOption -> {
            require(value is InspectionOptionValue.StringValue && value.value in this.allowedValues) {
                "Option ${this.key} is not an allowed enum value."
            }
        }
        is InspectionOptionDescriptor.ListOption -> {
            require(value is InspectionOptionValue.ListValue)
            require(value.value.all { element -> element.matches(this.elementType) }) {
                "Option ${this.key} contains an invalid list element."
            }
        }
    }
}

private fun InspectionOptionValue.matches(type: InspectionOptionType): Boolean =
    when (type) {
        InspectionOptionType.BOOLEAN -> this is InspectionOptionValue.BooleanValue
        InspectionOptionType.INTEGER -> this is InspectionOptionValue.IntegerValue
        InspectionOptionType.STRING -> this is InspectionOptionValue.StringValue
    }

private fun CorrectionPlan.requireValidCorrection(sourceText: String, sourceSha256: String) {
    require(this.sourceSha256 == sourceSha256) { "Correction ${this.id} targets stale source." }
    require(this.edits.isNotEmpty()) { "Correction ${this.id} has no edits." }
    val ordered = this.edits.sortedBy(TextEdit::startOffset)
    ordered.forEachIndexed { index, edit ->
        require(edit.startOffset in 0..sourceText.length) { "Correction edit starts outside source." }
        require(edit.endOffset in edit.startOffset..sourceText.length) { "Correction edit ends outside source." }
        require(sourceText.substring(edit.startOffset, edit.endOffset) == edit.expectedText) {
            "Correction ${this.id} expected text does not match source."
        }
        if (index > 0) {
            require(ordered[index - 1].endOffset <= edit.startOffset) { "Correction ${this.id} has overlapping edits." }
        }
    }
}
