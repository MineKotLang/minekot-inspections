package org.minekot.inspections.idea

import org.minekot.inspections.core.InspectionDescriptor
import org.minekot.inspections.core.MineKotInspection
import org.minekot.inspections.core.MineKotInspectionCatalog
import kotlin.test.*

/** Lifecycle tests for project-scoped classloader generations. */
class MineKotIdeaInspectionGenerationTest {
    /** Repeated retirement closes the classloader lease exactly once. */
    @Test
    fun `close is idempotent`() {
        var closes = 0
        val generation = MineKotIdeaInspectionGeneration("1.0.1", EmptyCatalog) { closes++ }

        generation.close()
        generation.close()

        assertEquals(1, closes)
        assertFailsWith<IllegalStateException> { generation.catalog }
    }

    /** Retirement waits for active analysis before closing generation resources. */
    @Test
    fun `retirement drains active analysis`() {
        var closes = 0
        val generation = MineKotIdeaInspectionGeneration("1.0.1", EmptyCatalog) { closes++ }
        val analysis = generation.acquire()!!

        generation.close()
        assertEquals(0, closes)
        assertEquals(null, generation.acquire())

        analysis.close()
        assertEquals(1, closes)
        assertFailsWith<IllegalStateException> { generation.catalog }
    }

    /** Three consecutive crashes quarantine only that rule; a success clears a partial streak. */
    @Test
    fun `repeated failures quarantine rule`() {
        val generation = MineKotIdeaInspectionGeneration("1.0.1", EmptyCatalog) {}

        repeat(PARTIAL_FAILURE_COUNT) { generation.recordFailure("minekot.test.rule") }
        assertFalse(generation.isQuarantined("minekot.test.rule"))
        generation.recordSuccess("minekot.test.rule")
        repeat(QUARANTINE_FAILURE_COUNT) { generation.recordFailure("minekot.test.rule") }

        assertTrue(generation.isQuarantined("minekot.test.rule"))
        assertFalse(generation.isQuarantined("minekot.test.other"))
    }

    private object EmptyCatalog : MineKotInspectionCatalog {
        override val spiMajor: Int = 1
        override val inspections: List<InspectionDescriptor> = emptyList()
        override fun createInspection(ruleId: String): MineKotInspection? = null
    }

    private companion object {
        const val PARTIAL_FAILURE_COUNT: Int = 2
        const val QUARANTINE_FAILURE_COUNT: Int = 3
    }
}
