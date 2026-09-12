package org.minekot.inspections.detekt

import com.intellij.openapi.util.Disposer
import dev.detekt.api.Config
import dev.detekt.api.RuleName
import org.jetbrains.kotlin.CoreEnvironmentDeprecation
import org.jetbrains.kotlin.K1Deprecation
import org.jetbrains.kotlin.cli.create
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.minekot.inspections.core.*
import kotlin.test.*

/** Detekt boundary and fixture-catalog parity tests. */
class MineKotDetektRuleSetProviderTest {
    /** Namespaced SPI IDs are encoded into Detekt's restrictive identifier grammar. */
    @Test
    fun `namespaced rule id is accepted by detekt`() {
        assertEquals(DETEKT_RULE_ID, RuleName(DESCRIPTOR.detektRuleId()).value)
    }

    /** Pure fixture finding retains message and primitive range through Detekt mapping. */
    @Test
    fun `fixture catalog maps exact finding into detekt`() {
        val ruleSet = MineKotDetektRuleSetProvider().instance()
        val rule = ruleSet.rules.getValue(RuleName(DETEKT_RULE_ID)).invoke(Config.empty)

        val finding = lint(rule, SOURCE).single()

        assertEquals(MESSAGE, finding.message)
        assertEquals(SOURCE.indexOf("bad"), finding.entity.location.text.start)
        assertEquals(SOURCE.indexOf("bad") + "bad".length, finding.entity.location.text.end)
    }

    @OptIn(CompilerConfiguration.Internals::class, CoreEnvironmentDeprecation::class, K1Deprecation::class)
    private fun lint(rule: dev.detekt.api.Rule, source: String): List<dev.detekt.api.Finding> {
        val disposable = Disposer.newDisposable()
        return try {
            val environment = KotlinCoreEnvironment.createForProduction(
                disposable,
                CompilerConfiguration.create().apply {
                    put(CommonConfigurationKeys.MODULE_NAME, "detekt-fixture")
                },
                EnvironmentConfigFiles.JVM_CONFIG_FILES,
            )
            val file = KtPsiFactory(environment.project, false).createFile("Fixture.kt", source)
            rule.visitFile(file, LanguageVersionSettingsImpl.DEFAULT)
        } finally {
            Disposer.dispose(disposable)
        }
    }
}

/** Service-loaded catalog shared by Detekt adapter fixtures. */
class DetektFixtureCatalog : MineKotInspectionCatalog {
    override val spiMajor: Int = MINEKOT_INSPECTIONS_SPI_MAJOR
    override val inspections: List<InspectionDescriptor> = listOf(DESCRIPTOR)

    override fun createInspection(ruleId: String): MineKotInspection? =
        FixtureInspection.takeIf { ruleId == RULE_ID }
}

private object FixtureInspection : MineKotInspection {
    override fun createSession(context: InspectionContext): MineKotInspectionSession =
        object : MineKotInspectionSession {
            override fun createVisitor(reporter: InspectionReporter): KtVisitorVoid =
                object : KtTreeVisitorVoid() {
                    override fun visitNamedFunction(function: KtNamedFunction) {
                        val name = function.nameIdentifier?.takeIf { identifier -> identifier.text == "bad" } ?: return
                        reporter.report(
                            InspectionFinding(
                                RULE_ID,
                                "rename",
                                MESSAGE,
                                emptyList(),
                                name.textRange.startOffset,
                                name.textRange.endOffset,
                                RuleSeverity.WARNING,
                            ),
                        )
                    }
                }
        }
}

private const val RULE_ID = "minekot.test.rename"
private const val DETEKT_RULE_ID = "minekot-test-rename"
private const val MESSAGE = "Rename bad function."
private const val SOURCE = "fun bad() = Unit\n"
private val DESCRIPTOR = InspectionDescriptor(
    RULE_ID,
    emptySet(),
    "Rename bad",
    MESSAGE,
    InspectionCategory.CORRECTNESS,
    RuleSeverity.WARNING,
)
