package org.minekot.inspections.idea

import com.intellij.openapi.project.Project
import java.util.*

/** Project-scoped generation registry used by static inspection bridge. */
public object MineKotIdeaInspectionRegistry {
    private val generations: MutableMap<Project, MineKotIdeaInspectionGeneration> = WeakHashMap()

    /** Returns currently active generation for project. */
    public fun active(project: Project): MineKotIdeaInspectionGeneration? = synchronized(generations) {
        generations[project]
    }

    /** Atomically activates generation and retires previous one. */
    public fun activate(project: Project, generation: MineKotIdeaInspectionGeneration) {
        val retired = synchronized(generations) { generations.put(project, generation) }
        retired?.close()
    }

    /** Removes and retires project generation. */
    public fun deactivate(project: Project) {
        val retired = synchronized(generations) { generations.remove(project) }
        retired?.close()
    }
}
