package org.minekot.inspections.loader

/** Evaluates manifest against exact supported host tuple and baseline bounds. */
public fun RulesManifest.compatibilityWith(host: RulesHostDescriptor): RulesCompatibility {
    val reasons = buildList {
        if (this@compatibilityWith.spiMajor != host.spiMajor) add("SPI major mismatch.")
        if (host.javaVersion < this@compatibilityWith.minimumJavaVersion) add("Java version is below minimum.")
        if (compareVersions(host.coreVersion, this@compatibilityWith.minimumCoreVersion) < 0) {
            add("Core version is below supported range.")
        }
        if (compareVersions(host.coreVersion, this@compatibilityWith.maximumCoreVersionExclusive) >= 0) {
            add("Core version is above supported range.")
        }
        if (this@compatibilityWith.testedHosts.none { tuple ->
                tuple.hostType == host.hostType &&
                    tuple.hostVersion == host.hostVersion &&
                    tuple.kotlinVersion == host.kotlinVersion
            }
        ) {
            add("Host tuple is not tested by this rules release.")
        }
    }
    return if (reasons.isEmpty()) RulesCompatibility.Compatible else RulesCompatibility.Incompatible(reasons)
}

private fun compareVersions(left: String, right: String): Int {
    val leftParts = left.substringBefore('-').split('.').map(String::toInt)
    val rightParts = right.substringBefore('-').split('.').map(String::toInt)
    val size = maxOf(leftParts.size, rightParts.size)
    repeat(size) { index ->
        val comparison = (leftParts.getOrNull(index) ?: 0).compareTo(rightParts.getOrNull(index) ?: 0)
        if (comparison != 0) return comparison
    }
    return 0
}
