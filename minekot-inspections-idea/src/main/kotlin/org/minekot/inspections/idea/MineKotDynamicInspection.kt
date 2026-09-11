package org.minekot.inspections.idea

import com.intellij.codeInspection.*
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.psi.*
import org.minekot.inspections.core.*

/** Static IntelliJ inspection bridge dispatching active project rules generation. */
public class MineKotDynamicInspection : LocalInspectionTool() {
    override fun getID(): String = "MineKotDynamic"

    override fun getShortName(): String = "MineKotDynamic"

    override fun getDisplayName(): String = MineKotIdeaBundle.message("inspection.dynamic.name")

    override fun getGroupDisplayName(): String = MineKotIdeaBundle.message("inspection.dynamic.group")

    override fun getStaticDescription(): String = MineKotIdeaBundle.message("inspection.dynamic.description")

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                if (file is KtFile) analyze(file, holder)
            }
        }

    @Suppress("ForbiddenTryCatch")
    private fun analyze(file: KtFile, holder: ProblemsHolder) {
        val generation = MineKotIdeaInspectionRegistry.active(holder.project) ?: return
        generation.acquire()?.use {
            val source = file.text
            val digest = source.sha256()
            generation.catalog.inspections.forEach { descriptor ->
                val policy = generation.policies.getValue(descriptor.id)
                if (!policy.enabled) return@forEach
                if (generation.isQuarantined(descriptor.id)) return@forEach
                val inspection = generation.catalog.createInspection(descriptor.id) ?: return@forEach
                val context = InspectionContext(
                    file = file,
                    fileId = file.virtualFile?.path ?: file.name,
                    sourceText = source,
                    sourceSha256 = digest,
                    options = policy.options,
                    suppressionResolver = InspectionSuppressionResolver { requestedId, element ->
                        val acceptedIds = if (requestedId == descriptor.id) {
                            descriptor.aliases + descriptor.id
                        } else {
                            setOf(requestedId)
                        }
                        isMineKotInspectionSuppressed(acceptedIds, element)
                    },
                    capabilities = object :
                        ExplicitOuterReceiverCapability,
                        ResolvedCallDetailsCapability,
                        ExpressionFactsCapability,
                        SymbolAvailabilityCapability {
                        override fun supports(capabilityId: String): Boolean = capabilityId == "idea"

                        override fun resolveCall(expression: KtCallExpression): ResolvedInspectionCall? =
                            expression.resolveInspectionCall()

                        override fun resolveCallDetails(expression: KtCallExpression): ResolvedInspectionCallDetails? =
                            expression.resolveInspectionCallDetails()

                        override fun expressionFacts(expression: KtExpression): InspectionExpressionFacts =
                            expression.inspectionExpressionFacts()

                        override fun isSymbolAvailable(symbolId: String): Boolean {
                            val facadeId = symbolFacadeIds[symbolId] ?: return false
                            return JavaPsiFacade.getInstance(holder.project)
                                .findClass(facadeId, GlobalSearchScope.allScope(holder.project)) != null
                        }

                        override fun isImplicitOuterReceiver(expression: KtNameReferenceExpression): Boolean =
                            expression.isImplicitOuterReceiverReference()
                    },
                )
                try {
                    inspection.createSession(context).use { session ->
                        file.accept(
                            session.createVisitor { finding ->
                                val copied = finding.copy(
                                    messageArguments = finding.messageArguments.toList(),
                                    corrections = finding.corrections.map { correction ->
                                        correction.copy(edits = correction.edits.toList())
                                    },
                                ).requireValidFinding(source, digest)
                                register(holder, file, copied.copy(severity = policy.severity.forMode(generation.mode)))
                            },
                        )
                    }
                    generation.recordSuccess(descriptor.id)
                } catch (failure: Throwable) {
                    failure.rethrowWhenFatal()
                    generation.recordFailure(descriptor.id)
                }
            }
        }
    }

    private fun register(holder: ProblemsHolder, file: KtFile, finding: InspectionFinding) {
        holder.registerProblem(
            file,
            finding.message,
            finding.severity.highlightType(),
            TextRange(finding.startOffset, finding.endOffset),
            *finding.corrections.map(::MineKotDeclarativeQuickFix).toTypedArray(),
        )
    }
}

private val symbolFacadeIds: Map<String, String> = mapOf(
    "org.minekot.kotlin.coroutines.mineKotIo" to
            "org.minekot.kotlin.coroutines.MineKotDispatchersKt",
    "org.minekot.kotlin.coroutines.runMineKotCatchingCancellable" to
            "org.minekot.kotlin.coroutines.MineKotCoroutineResultsKt",
)
