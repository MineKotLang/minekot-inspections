package org.minekot.inspections.idea

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

/** Localized text owned by the reusable IntelliJ inspection adapter. */
internal object MineKotIdeaBundle : DynamicBundle(MINEKOT_IDEA_BUNDLE) {
    /** Resolves one adapter message without exposing resource-bundle implementation details. */
    fun message(
        @PropertyKey(resourceBundle = MINEKOT_IDEA_BUNDLE) key: String,
        vararg params: Any,
    ): String = getMessage(key, *params)
}

private const val MINEKOT_IDEA_BUNDLE = "messages.MineKotInspectionsIdeaBundle"
