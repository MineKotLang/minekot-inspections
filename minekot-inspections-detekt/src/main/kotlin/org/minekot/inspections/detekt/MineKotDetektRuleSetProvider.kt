@file:Suppress("ResolvedApiPreference")

package org.minekot.inspections.detekt

import dev.detekt.api.*
import dev.detekt.api.internal.AutoCorrectable
import dev.detekt.api.modifiedText
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.singleFunctionCallOrNull
import org.jetbrains.kotlin.analysis.api.resolution.symbol
import org.jetbrains.kotlin.analysis.api.resolution.*
import org.jetbrains.kotlin.analysis.api.symbols.KaClassKind
import org.jetbrains.kotlin.analysis.api.symbols.KaClassSymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaConstructorSymbol
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.minekot.inspections.core.*
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Detekt entry point adapting one dynamically supplied MineKot catalog. */
public class MineKotDetektRuleSetProvider : RuleSetProvider {
    override val ruleSetId: RuleSetId = RuleSetId("minekot")

    override fun instance(): RuleSet {
        val classLoader = Thread.currentThread().contextClassLoader ?: this::class.java.classLoader
        val catalog = loadMineKotInspectionCatalog(classLoader)
        return RuleSet(
            ruleSetId,
            catalog.inspections.associate { descriptor ->
                RuleName(descriptor.detektRuleId()) to { config: Config ->
                    MineKotDetektRule(
                        descriptor,
                        requireNotNull(catalog.createInspection(descriptor.id)),
                        catalog,
                        config,
                    )
                }
            },
        )
    }
}

@AutoCorrectable(since = "1.0.0")
private class MineKotDetektRule(
    private val descriptor: InspectionDescriptor,
    private val inspection: MineKotInspection,
    private val catalog: MineKotInspectionCatalog,
    config: Config,
) : Rule(config, descriptor.description) {
    override val ruleName: RuleName = RuleName(descriptor.detektRuleId())
    private val correctionPlans: MutableList<CorrectionPlan> = mutableListOf()

    override fun preVisit(root: KtFile) {
        correctionPlans.clear()
    }

    override fun visit(root: KtFile) {
        val policy = root.inspectionPolicies(catalog).getValue(descriptor.id)
        if (!policy.enabled) return
        val source = root.text
        val context = InspectionContext(
            file = root,
            fileId = root.stableFilePath(),
            sourceText = source,
            sourceSha256 = source.sha256(),
            options = descriptor.validateOptions(policy.options + config.mineKotOptions(descriptor)),
            suppressionResolver = InspectionSuppressionResolver { _, _ -> false },
            capabilities = object :
                ExplicitOuterReceiverCapability,
                ResolvedCallDetailsCapability,
                ExpressionFactsCapability,
                SymbolAvailabilityCapability {
                override fun supports(capabilityId: String): Boolean = capabilityId == "detekt"

                override fun resolveCall(expression: KtCallExpression): ResolvedInspectionCall? =
                    expression.resolveInspectionCall()

                override fun resolveCallDetails(expression: KtCallExpression): ResolvedInspectionCallDetails? =
                    expression.resolveInspectionCall()?.let { call ->
                        ResolvedInspectionCallDetails(call.callableId)
                    }

                override fun expressionFacts(expression: KtExpression): InspectionExpressionFacts =
                    expression.inspectionExpressionFacts()

                override fun isSymbolAvailable(symbolId: String): Boolean =
                    symbolClassResources[symbolId]?.let { resource ->
                        Thread.currentThread().contextClassLoader?.getResource(resource) != null
                    } == true

                override fun isImplicitOuterReceiver(expression: KtNameReferenceExpression): Boolean =
                    expression.isImplicitOuterReceiverReference()
            },
        )
        inspection.createSession(context).use { session ->
            root.accept(
                session.createVisitor { finding ->
                    finding.requireValidFinding(source, context.sourceSha256)
                    finding.corrections.firstOrNull()?.let(correctionPlans::add)
                    this@MineKotDetektRule.report(
                        Finding(
                            entity = finding.toEntity(root, source),
                            message = finding.message,
                        ),
                    )
                },
            )
        }
    }

    override fun postVisit(root: KtFile) {
        if (!autoCorrect || correctionPlans.isEmpty() || root.modifiedText != null) return
        val source = root.text
        val uniquePlans = correctionPlans.distinct()
        val edits = uniquePlans.flatMap(CorrectionPlan::edits).distinct()
            .sortedWith(compareBy(TextEdit::startOffset, TextEdit::endOffset, TextEdit::replacement))
        require(
            edits.zipWithNext().all { (first, second) -> !first.conflictsWith(second) },
        ) {
            "Conflicting MineKot corrections for ${root.stableFilePath()}: " +
                    uniquePlans.joinToString { it.id }
        }
        edits.forEach { edit ->
            require(source.substring(edit.startOffset, edit.endOffset) == edit.expectedText) {
                "Stale MineKot correction ${edit.startOffset}..${edit.endOffset} for ${root.stableFilePath()}."
            }
        }
        root.modifiedText = edits.sortedByDescending(TextEdit::startOffset).fold(source) { corrected, edit ->
            corrected.replaceRange(edit.startOffset, edit.endOffset, edit.replacement)
        }
    }
}

private fun TextEdit.conflictsWith(other: TextEdit): Boolean =
    startOffset < other.endOffset && other.startOffset < endOffset ||
            startOffset == other.startOffset && (startOffset == endOffset || other.startOffset == other.endOffset)

private fun KtCallExpression.resolveInspectionCall(): ResolvedInspectionCall? =
    analyze(this) {
        val symbol = resolveToCall()?.singleFunctionCallOrNull()?.symbol ?: return@analyze null
        val callableId = if (symbol is KaConstructorSymbol) {
            symbol.containingClassId?.asSingleFqName()?.asString()?.plus(".<init>")
        } else {
            symbol.callableId?.asSingleFqName()?.asString()
        } ?: return@analyze null
        ResolvedInspectionCall(callableId)
    }

private fun KtNameReferenceExpression.isImplicitOuterReceiverReference(): Boolean =
    analyze(this) {
        val call = (parent as? KtCallExpression)
            ?.takeIf { it.calleeExpression == this@isImplicitOuterReceiverReference }
            ?.resolveToCall()?.singleFunctionCallOrNull()
            ?: resolveToCall()?.singleVariableAccessCall()
            ?: return@analyze false
        val receiver = (call.dispatchReceiver ?: call.extensionReceiver).unwrapSmartCast() ?: return@analyze false
        val receiverSymbol = (receiver as? KaImplicitReceiverValue)?.symbol as? KaClassSymbol ?: return@analyze false
        if (receiverSymbol.classKind == KaClassKind.COMPANION_OBJECT) return@analyze false
        val nearestLambda = parents.filterIsInstance<KtLambdaExpression>().firstOrNull() ?: return@analyze false
        if (nearestLambda.functionLiteral.symbol.receiverParameter != null) return@analyze false
        if (call.symbol.callableId?.classId != receiverSymbol.classId) return@analyze false
        val receiverPsi = receiverSymbol.psi
        receiverPsi == null || !PsiTreeUtil.isAncestor(nearestLambda, receiverPsi, false)
    }

private fun KtExpression.inspectionExpressionFacts(): InspectionExpressionFacts {
    val enclosingCalls = parents.filterIsInstance<KtCallExpression>()
        .mapNotNull { call -> call.resolveInspectionCall() }
        .map(ResolvedInspectionCall::callableId)
        .toSet()
    return InspectionExpressionFacts(
        suspendContext = enclosingCalls.any(suspendHandlerIds::contains) ||
                parents.filterIsInstance<KtNamedFunction>().any { function ->
                    function.hasModifier(KtTokens.SUSPEND_KEYWORD)
                },
        mainThreadContext = enclosingCalls.any(mainThreadHandlerIds::contains) ||
                parents.filterIsInstance<KtNamedFunction>().any { function ->
                    function.annotationEntries.any { annotation -> annotation.shortName?.asString() == "MainThread" }
                },
    )
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
private val symbolClassResources: Map<String, String> = mapOf(
    "org.minekot.kotlin.coroutines.mineKotIo" to
            "org/minekot/kotlin/coroutines/MineKotDispatchersKt.class",
    "org.minekot.kotlin.coroutines.runMineKotCatchingCancellable" to
            "org/minekot/kotlin/coroutines/MineKotCoroutineResultsKt.class",
)

private val detektConfigurationKeys: Set<String> = setOf(
    Config.ACTIVE_KEY,
    Config.ALIASES_KEY,
    Config.AUTO_CORRECT_KEY,
    Config.EXCLUDES_KEY,
    Config.IGNORE_ANNOTATED_KEY,
    Config.INCLUDES_KEY,
    Config.SEVERITY_KEY,
)

internal fun InspectionDescriptor.detektRuleId(): String = this.id.replace('.', '-')

private fun Config.mineKotOptions(descriptor: InspectionDescriptor): Map<String, InspectionOptionValue> {
    val schemas = descriptor.options.associateBy(InspectionOptionDescriptor::key)
    val unknown = this.subConfigKeys() - schemas.keys - detektConfigurationKeys
    require(unknown.isEmpty()) {
        "Inspection ${descriptor.id} has unknown Detekt options: ${unknown.sorted().joinToString()}."
    }
    return schemas.mapNotNull { (key, schema) ->
        val value = this.valueOrNull<Any>(key) ?: return@mapNotNull null
        key to value.toInspectionOptionValue(schema)
    }.toMap()
}

private fun Any.toInspectionOptionValue(schema: InspectionOptionDescriptor): InspectionOptionValue =
    when (schema) {
        is InspectionOptionDescriptor.BooleanOption -> InspectionOptionValue.BooleanValue(this as Boolean)
        is InspectionOptionDescriptor.IntegerOption -> InspectionOptionValue.IntegerValue((this as Number).toLong())
        is InspectionOptionDescriptor.StringOption,
        is InspectionOptionDescriptor.EnumOption,
        -> InspectionOptionValue.StringValue(this as String)
        is InspectionOptionDescriptor.ListOption -> InspectionOptionValue.ListValue(
            (this as List<*>).map { element ->
                requireNotNull(element) { "Option ${schema.key} contains null." }
                    .toScalarInspectionOptionValue(schema.elementType)
            },
        )
    }

private fun Any.toScalarInspectionOptionValue(type: InspectionOptionType): InspectionOptionValue =
    when (type) {
        InspectionOptionType.BOOLEAN -> InspectionOptionValue.BooleanValue(this as Boolean)
        InspectionOptionType.INTEGER -> InspectionOptionValue.IntegerValue((this as Number).toLong())
        InspectionOptionType.STRING -> InspectionOptionValue.StringValue(this as String)
    }

private fun InspectionFinding.toEntity(root: KtFile, source: String): Entity {
    val location = Location(
        source = source.sourceLocationAt(this.startOffset),
        endSource = source.sourceLocationAt(this.endOffset),
        text = TextLocation(this.startOffset, this.endOffset),
        path = Path.of(root.stableFilePath()),
    )
    return Entity.from(root, location)
}

private fun String.sourceLocationAt(offset: Int): SourceLocation {
    require(offset in 0..this.length) { "Source offset is outside source." }
    val preceding = this.substring(0, offset)
    val lineStart = preceding.lastIndexOf('\n') + 1
    val line = preceding.count { character -> character == '\n' } + 1
    return SourceLocation(line, offset - lineStart + 1)
}

private fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

private fun KtFile.inspectionPolicies(
    catalog: MineKotInspectionCatalog,
): Map<String, ResolvedInspectionRulePolicy> {
    val policyPath = policySearchRoots().firstNotNullOfOrNull { root ->
        generateSequence(root) { path -> path.parent }
            .map { path -> path.resolve("config").resolve("minekot-inspections.yml") }
            .firstOrNull(java.nio.file.Files::isRegularFile)
    }
    val policy = policyPath?.let { path ->
        val modifiedAt = java.nio.file.Files.getLastModifiedTime(path).toMillis()
        policyCache.compute(path) { _, cached ->
            cached?.takeIf { snapshot -> snapshot.modifiedAt == modifiedAt }
                ?: PolicySnapshot(modifiedAt, parseInspectionPolicy(java.nio.file.Files.readString(path)))
        }!!.policy
    }
        ?: InspectionPolicy(MINEKOT_INSPECTIONS_POLICY_SCHEMA_VERSION, emptyMap())
    return policy.resolve(catalog)
}

private data class PolicySnapshot(val modifiedAt: Long, val policy: InspectionPolicy)

private val policyCache: ConcurrentHashMap<Path, PolicySnapshot> = ConcurrentHashMap()

private fun KtFile.policySearchRoots(): Sequence<Path> = sequence {
    virtualFile?.path?.takeIf(String::isNotBlank)?.let { virtualPath ->
        val path = Path.of(virtualPath).toAbsolutePath().normalize()
        val directory = if (java.nio.file.Files.isDirectory(path)) path else path.parent
        if (directory != null) yield(directory)
    }
}

private fun KtFile.stableFilePath(): String =
    virtualFile?.path?.takeIf(String::isNotBlank) ?: name.ifBlank { "memory.kt" }
