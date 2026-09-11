package org.minekot.inspections.core

import kotlin.test.*

/** Shared policy parsing and catalog validation tests. */
class InspectionPolicyTest {
    /** Policy resolves aliases, defaults, severity, and typed options. */
    @Test
    fun `policy resolves against catalog`() {
        val policy = parseInspectionPolicy(
            """
            # Shared MineKot inspection policy
            schema-version: 1
            rules:
              LegacyRule:
                enabled: false
                severity: ERROR
                options:
                  limit: 42
                  names: [ alpha, beta ]
            """.trimIndent(),
        )

        val resolved = policy.resolve(TestCatalog).getValue(DESCRIPTOR.id)

        assertFalse(resolved.enabled)
        assertEquals(RuleSeverity.ERROR, resolved.severity)
        assertEquals(InspectionOptionValue.IntegerValue(CONFIGURED_LIMIT), resolved.options["limit"])
        assertEquals(
            InspectionOptionValue.ListValue(
                listOf(
                    InspectionOptionValue.StringValue("alpha"),
                    InspectionOptionValue.StringValue("beta"),
                ),
            ),
            resolved.options["names"],
        )
    }

    /** Unknown structure, rules, options, and option types fail closed. */
    @Test
    fun `policy rejects unknown and malformed values`() {
        assertFailsWith<IllegalArgumentException> {
            parseInspectionPolicy("schema-version: 1\nrules:\n  rule.example:\n    mystery: true")
        }
        assertFailsWith<IllegalArgumentException> {
            parseInspectionPolicy("schema-version: 1\nrules:\n  rule.unknown:\n    enabled: true").resolve(TestCatalog)
        }
        assertFailsWith<IllegalArgumentException> {
            parseInspectionPolicy(
                "schema-version: 1\nrules:\n  rule.example:\n    options:\n      unknown: true",
            ).resolve(TestCatalog)
        }
        assertFailsWith<IllegalArgumentException> {
            parseInspectionPolicy(
                "schema-version: 1\nrules:\n  rule.example:\n    options:\n      limit: wrong",
            ).resolve(TestCatalog)
        }
    }

    /** Duplicate canonical and alias configuration cannot overwrite policy. */
    @Test
    fun `policy rejects duplicate alias configuration`() {
        val policy = parseInspectionPolicy(
            """
            schema-version: 1
            rules:
              rule.example:
                enabled: true
              LegacyRule:
                enabled: false
            """.trimIndent(),
        )

        assertFailsWith<IllegalArgumentException> { policy.resolve(TestCatalog) }
    }

    /** Omitted project entries use additive catalog activation and severity defaults. */
    @Test
    fun `policy honors catalog defaults`() {
        val policy = InspectionPolicy(MINEKOT_INSPECTIONS_POLICY_SCHEMA_VERSION, emptyMap())

        val resolved = policy.resolve(DefaultedCatalog).getValue(DESCRIPTOR.id)

        assertFalse(resolved.enabled)
        assertEquals(RuleSeverity.WEAK_WARNING, resolved.severity)
        assertTrue(resolved.options.isNotEmpty())
    }

    private object TestCatalog : MineKotInspectionCatalog {
        override val spiMajor: Int = MINEKOT_INSPECTIONS_SPI_MAJOR
        override val inspections: List<InspectionDescriptor> = listOf(DESCRIPTOR)
        override fun createInspection(ruleId: String): MineKotInspection? = null
    }

    private object DefaultedCatalog : MineKotInspectionCatalog, MineKotInspectionCatalogDefaults {
        override val spiMajor: Int = MINEKOT_INSPECTIONS_SPI_MAJOR
        override val inspections: List<InspectionDescriptor> = listOf(DESCRIPTOR)
        override val ruleDefaults: Map<String, InspectionRuleDefaults> = mapOf(
            DESCRIPTOR.id to InspectionRuleDefaults(
                defaultEnabled = false,
                recommendedSeverity = RuleSeverity.WEAK_WARNING,
            ),
        )

        override fun createInspection(ruleId: String): MineKotInspection? = null
    }

    private companion object {
        const val CONFIGURED_LIMIT = 42L
        val DESCRIPTOR: InspectionDescriptor = InspectionDescriptor(
            id = "rule.example",
            aliases = setOf("LegacyRule"),
            displayName = "Example",
            description = "Example descriptor.",
            category = InspectionCategory.CORRECTNESS,
            defaultSeverity = RuleSeverity.WARNING,
            options = listOf(
                InspectionOptionDescriptor.IntegerOption(
                    key = "limit",
                    description = "Bounded example.",
                    defaultValue = InspectionOptionValue.IntegerValue(1),
                    minimum = 0,
                    maximum = 100,
                ),
                InspectionOptionDescriptor.ListOption(
                    key = "names",
                    description = "Example names.",
                    defaultValue = InspectionOptionValue.ListValue(emptyList()),
                    elementType = InspectionOptionType.STRING,
                ),
            ),
        )
    }
}
