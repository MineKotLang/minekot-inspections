package org.minekot.inspections.idea

import org.minekot.inspections.core.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private const val RULE_FAILURE_LIMIT: Int = 3

/** One immutable project-scoped rules generation.
 * @property version exact rules release version.
 * @property catalog generation-owned catalog.
 * @property policies validated policy snapshot keyed by canonical rule ID.
 * @property mode project presentation mode applied after baseline policy.
 */
public class MineKotIdeaInspectionGeneration(
    public val version: String,
    catalog: MineKotInspectionCatalog,
    public val policies: Map<String, ResolvedInspectionRulePolicy> = InspectionPolicy(
        MINEKOT_INSPECTIONS_POLICY_SCHEMA_VERSION,
        emptyMap(),
    ).resolve(catalog),
    public val mode: InspectionMode = InspectionMode.BASELINE,
    private val closeAction: () -> Unit,
) : AutoCloseable {
    private val catalogReference: AtomicReference<MineKotInspectionCatalog?> = AtomicReference(catalog)
    private val analyses: AtomicInteger = AtomicInteger()
    private val retired: AtomicBoolean = AtomicBoolean()
    private val closed: AtomicBoolean = AtomicBoolean()
    private val ruleFailures: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap()
    private val quarantinedRules: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Generation catalog while generation resources remain live. */
    public val catalog: MineKotInspectionCatalog
        get() = checkNotNull(catalogReference.get()) { "MineKot rules generation is closed." }

    /** Acquires generation for one analysis, or null after retirement. */
    public fun acquire(): AutoCloseable? {
        if (retired.get()) return null
        analyses.incrementAndGet()
        if (retired.get()) {
            release()
            return null
        }
        return AutoCloseable(::release)
    }

    /** Clears transient failures after one successful analysis. */
    internal fun recordSuccess(ruleId: String) {
        ruleFailures.remove(ruleId)
    }

    /** Records a rule failure and quarantines it after repeated crashes. */
    internal fun recordFailure(ruleId: String) {
        val failures = ruleFailures.computeIfAbsent(ruleId) { AtomicInteger() }.incrementAndGet()
        if (failures >= RULE_FAILURE_LIMIT) quarantinedRules.add(ruleId)
    }

    /** Returns whether a repeatedly crashing rule is disabled for this generation. */
    internal fun isQuarantined(ruleId: String): Boolean = ruleId in quarantinedRules

    override fun close() {
        retired.set(true)
        closeWhenDrained()
    }

    private fun release() {
        analyses.decrementAndGet()
        closeWhenDrained()
    }

    private fun closeWhenDrained() {
        if (retired.get() && analyses.get() == 0 && closed.compareAndSet(false, true)) {
            catalogReference.set(null)
            ruleFailures.clear()
            quarantinedRules.clear()
            closeAction()
        }
    }
}
