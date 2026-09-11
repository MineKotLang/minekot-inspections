package org.minekot.inspections.idea

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.*

internal fun isMineKotInspectionSuppressed(ruleIds: Set<String>, element: KtElement): Boolean =
    generateSequence(element as PsiElement?) { current -> current.parent }
        .filterIsInstance<KtAnnotated>()
        .flatMap { annotated -> annotated.annotationEntries.asSequence() }
        .filter { annotation -> annotation.shortName?.asString() == "Suppress" }
        .flatMap { annotation -> annotation.valueArguments.asSequence() }
        .mapNotNull { argument -> argument.getArgumentExpression()?.text?.removeSurrounding("\"") }
        .any(ruleIds::contains)
