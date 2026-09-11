package org.minekot.inspections.core

import kotlin.test.*

/** Boundary validation tests for pure corrections and options. */
class InspectionValidationTest {
    /** Matching source guards permit one atomic edit. */
    @Test
    fun `guarded correction applies atomically`() {
        val source = "val answer = 41"
        val plan = CorrectionPlan(
            id = "minekot.fix.answer",
            label = "Fix answer",
            fileId = "Example.kt",
            sourceSha256 = "digest",
            edits = listOf(TextEdit(ANSWER_START, ANSWER_END, "41", "42")),
        )

        assertEquals("val answer = 42", plan.applyTo(source, "digest"))
        assertFailsWith<IllegalArgumentException> { plan.applyTo(source, "stale") }
    }

    /** Unknown and out-of-range values fail before inspection execution. */
    @Test
    fun `unknown and out of range options fail`() {
        val descriptor = InspectionDescriptor(
            id = "minekot.test.option",
            aliases = emptySet(),
            displayName = "Option",
            description = "Tests option validation.",
            category = InspectionCategory.CORRECTNESS,
            defaultSeverity = RuleSeverity.WARNING,
            options = listOf(
                InspectionOptionDescriptor.IntegerOption(
                    key = "limit",
                    description = "Maximum count.",
                    defaultValue = InspectionOptionValue.IntegerValue(DEFAULT_LIMIT),
                    minimum = 1,
                    maximum = 20,
                ),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            descriptor.validateOptions(mapOf("unknown" to InspectionOptionValue.BooleanValue(true)))
        }
        assertFailsWith<IllegalArgumentException> {
            descriptor.validateOptions(mapOf("limit" to InspectionOptionValue.IntegerValue(INVALID_LIMIT)))
        }
    }

    /** Any overlap invalidates the complete correction plan. */
    @Test
    fun `overlapping edits reject complete correction`() {
        val source = "abcdef"
        val plan = CorrectionPlan(
            id = "minekot.fix.overlap",
            label = "Overlap",
            fileId = "Example.kt",
            sourceSha256 = "digest",
            edits = listOf(
                TextEdit(FIRST_START, FIRST_END, "bcd", "x"),
                TextEdit(SECOND_START, SECOND_END, "de", "y"),
            ),
        )

        assertFailsWith<IllegalArgumentException> { plan.applyTo(source, "digest") }
    }

    /** Catalog defaults reject unknown IDs, active replacements, and missing replacement targets. */
    @Test
    fun `catalog defaults fail closed`() {
        val descriptor = testDescriptor("minekot.test.primary")

        fun invalidCatalog(defaults: Map<String, InspectionRuleDefaults>): MineKotInspectionCatalog =
            object : MineKotInspectionCatalog, MineKotInspectionCatalogDefaults {
                override val spiMajor: Int = MINEKOT_INSPECTIONS_SPI_MAJOR
                override val inspections: List<InspectionDescriptor> = listOf(descriptor)
                override val ruleDefaults: Map<String, InspectionRuleDefaults> = defaults
                override fun createInspection(ruleId: String): MineKotInspection? = null
            }

        assertFailsWith<IllegalArgumentException> {
            invalidCatalog(mapOf("minekot.test.unknown" to InspectionRuleDefaults())).requireValidCatalog()
        }
        assertFailsWith<IllegalArgumentException> {
            invalidCatalog(
                mapOf(
                    descriptor.id to InspectionRuleDefaults(replacementRuleId = "minekot.test.replacement"),
                ),
            ).requireValidCatalog()
        }
        assertFailsWith<IllegalArgumentException> {
            invalidCatalog(
                mapOf(
                    descriptor.id to InspectionRuleDefaults(
                        lifecycle = InspectionRuleLifecycle.DEPRECATED,
                        replacementRuleId = "minekot.test.replacement",
                    ),
                ),
            ).requireValidCatalog()
        }
    }

    private fun testDescriptor(id: String): InspectionDescriptor = InspectionDescriptor(
        id = id,
        aliases = emptySet(),
        displayName = "Test",
        description = "Test descriptor.",
        category = InspectionCategory.CORRECTNESS,
        defaultSeverity = RuleSeverity.WARNING,
    )

    private companion object {
        const val ANSWER_START: Int = 13
        const val ANSWER_END: Int = 15
        const val DEFAULT_LIMIT: Long = 10
        const val INVALID_LIMIT: Long = 21
        const val FIRST_START: Int = 1
        const val FIRST_END: Int = 4
        const val SECOND_START: Int = 3
        const val SECOND_END: Int = 5
    }
}
