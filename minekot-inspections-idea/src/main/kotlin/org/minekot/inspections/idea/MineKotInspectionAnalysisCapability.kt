package org.minekot.inspections.idea

import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.*
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.jetbrains.kotlin.lexer.KtTokens
import org.minekot.inspections.core.*

internal fun KtCallExpression.resolveInspectionCall(): ResolvedInspectionCall? =
    analyze(this) {
        val symbol = resolveToCall()?.singleFunctionCallOrNull()?.symbol ?: return@analyze null
        val callableId = if (symbol is KaConstructorSymbol) {
            symbol.containingClassId?.asSingleFqName()?.asString()?.plus(".<init>")
        } else {
            symbol.callableId?.asSingleFqName()?.asString()
        } ?: return@analyze null
        ResolvedInspectionCall(callableId)
    }

internal fun KtCallExpression.resolveInspectionCallDetails(): ResolvedInspectionCallDetails? =
    resolveInspectionCall()?.let { call -> ResolvedInspectionCallDetails(call.callableId) }

internal fun KtExpression.inspectionExpressionFacts(): InspectionExpressionFacts {
    val enclosingCalls = parents.filterIsInstance<KtCallExpression>()
        .mapNotNull { call -> call.resolveInspectionCall() }
        .map(ResolvedInspectionCall::callableId)
        .toSet()
    val mainThreadContext = enclosingCalls.any(mainThreadHandlerIds::contains) ||
            parents.filterIsInstance<KtNamedFunction>().any { function ->
                function.annotationEntries.any { annotation -> annotation.shortName?.asString() == "MainThread" }
            }
    val suspendContext = enclosingCalls.any(suspendHandlerIds::contains) ||
            parents.filterIsInstance<KtNamedFunction>().any { function ->
                function.hasModifier(KtTokens.SUSPEND_KEYWORD)
            }
    return InspectionExpressionFacts(
        suspendContext = suspendContext,
        mainThreadContext = mainThreadContext,
    )
}

internal fun KtNameReferenceExpression.isImplicitOuterReceiverReference(): Boolean =
    analyze(this) {
        val call = (parent as? KtCallExpression)
            ?.takeIf { it.calleeExpression == this@isImplicitOuterReceiverReference }
            ?.resolveToCall()?.singleFunctionCallOrNull()
            ?: resolveToCall()?.singleVariableAccessCall()
            ?: return@analyze false
        val receiver = (
            call.partiallyAppliedSymbol.dispatchReceiver ?: call.partiallyAppliedSymbol.extensionReceiver
        ).unwrapSmartCast() ?: return@analyze false
        val receiverSymbol = (receiver as? KaImplicitReceiverValue)?.symbol as? KaClassSymbol ?: return@analyze false
        if (receiverSymbol.classKind == KaClassKind.COMPANION_OBJECT) return@analyze false
        val nearestLambda = parents.filterIsInstance<KtLambdaExpression>().firstOrNull() ?: return@analyze false
        if (nearestLambda.functionLiteral.symbol.receiverParameter != null) return@analyze false
        if (call.symbol.callableId?.classId != receiverSymbol.classId) return@analyze false
        val receiverPsi = receiverSymbol.psi
        receiverPsi == null || !PsiTreeUtil.isAncestor(nearestLambda, receiverPsi, false)
    }

private fun KaReceiverValue?.unwrapSmartCast(): KaReceiverValue? = when (this) {
    is KaSmartCastedReceiverValue -> original.unwrapSmartCast()
    else -> this
}

private val mainThreadHandlerIds: Set<String> = setOf(
    "org.minekot.api.dsl.listen",
    "org.minekot.api.dsl.listenOnce",
    "org.minekot.api.dsl.on",
)
private val suspendHandlerIds: Set<String> = mainThreadHandlerIds
