package org.minekot.inspections.idea

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.kotlin.psi.KtVisitorVoid
import org.minekot.inspections.core.*

/** IntelliJ code-insight parity tests for dynamic inspection bridge. */
class MineKotDynamicInspectionTest : BasePlatformTestCase() {
    override fun tearDown() {
        MineKotIdeaInspectionRegistry.deactivate(project)
        super.tearDown()
    }

    /** Inspection settings and batch reports receive full static tool documentation. */
    fun testInspectionDescriptionIsPackaged() {
        val resource = checkNotNull(javaClass.classLoader.getResource("inspectionDescriptions/MineKotDynamic.html"))
        val packagedDescription = resource.readText()
        val platformDescription = checkNotNull(MineKotDynamicInspection().staticDescription)

        assertTrue(packagedDescription.contains("verified rules generation"))
        assertTrue(platformDescription.contains("stable MineKot rule ID"))
    }

    /** Pure finding becomes IDEA diagnostic and complete guarded quick fix. */
    fun testDiagnosticAndQuickFix() {
        activate()
        myFixture.enableInspections(MineKotDynamicInspection())
        myFixture.configureByText("Example.kt", "fun <caret>bad() = Unit\n")

        val highlights = myFixture.doHighlighting()

        val mineKotHighlight = highlights.single { info -> info.description == MESSAGE }
        assertEquals(HighlightSeverity.WARNING, mineKotHighlight.severity)
        val action = myFixture.findSingleIntention(FIX_LABEL)
        assertEquals("fun good() = Unit\n", myFixture.getIntentionPreviewText(action))
        myFixture.checkResult("fun bad() = Unit\n")
        myFixture.launchAction(action)
        myFixture.checkResult("fun good() = Unit\n")
    }

    /** Guarded quick fix refuses source changed after diagnostic creation. */
    fun testStaleQuickFixIsRejected() {
        activate()
        myFixture.enableInspections(MineKotDynamicInspection())
        myFixture.configureByText("Example.kt", "fun <caret>bad() = Unit\n")
        myFixture.doHighlighting()
        val action = myFixture.findSingleIntention(FIX_LABEL)
        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(myFixture.editor.document.textLength, "// changed\n")
        }

        assertThrows(IllegalArgumentException::class.java) { myFixture.launchAction(action) }
        myFixture.checkResult("fun bad() = Unit\n// changed\n")
    }

    /** Deprecated alias suppression maps to canonical rule identity. */
    fun testAliasSuppression() {
        activate()
        myFixture.enableInspections(MineKotDynamicInspection())
        myFixture.configureByText(
            "Example.kt",
            "@Suppress(\"${RULE_ALIAS}\")\nfun bad() = Unit\n",
        )

        assertTrue(myFixture.doHighlighting().none { info -> info.description == MESSAGE })
    }

    /** Canonical stable ID suppression works at enclosing declaration scope. */
    fun testCanonicalSuppression() {
        activate()
        myFixture.enableInspections(MineKotDynamicInspection())
        myFixture.configureByText(
            "Example.kt",
            "@Suppress(\"${RULE_ID}\")\nclass Service { fun bad() = Unit }\n",
        )

        assertTrue(myFixture.doHighlighting().none { info -> info.description == MESSAGE })
    }

    /** Maintainer presentation mode upgrades rule warnings to IDEA errors. */
    fun testMaintainerModeRendersErrorSeverity() {
        activate(InspectionMode.MAINTAINER)
        myFixture.enableInspections(MineKotDynamicInspection())
        myFixture.configureByText("Example.kt", "fun bad() = Unit\n")

        val highlight = myFixture.doHighlighting().single { info -> info.description == MESSAGE }

        assertEquals(HighlightSeverity.ERROR, highlight.severity)
    }

    /** Repeated project swaps retire each generation exactly once. */
    fun testHotSwapRetiresPreviousGenerations() {
        var firstCloses = 0
        var secondCloses = 0
        MineKotIdeaInspectionRegistry.activate(
            project,
            MineKotIdeaInspectionGeneration("1.0.1", Catalog) { firstCloses++ },
        )
        MineKotIdeaInspectionRegistry.activate(
            project,
            MineKotIdeaInspectionGeneration("1.0.2", Catalog) { secondCloses++ },
        )

        assertEquals(1, firstCloses)
        assertEquals("1.0.2", MineKotIdeaInspectionRegistry.active(project)?.version)
        MineKotIdeaInspectionRegistry.deactivate(project)
        assertEquals(1, secondCloses)
    }

    private fun activate(mode: InspectionMode = InspectionMode.BASELINE) {
        MineKotIdeaInspectionRegistry.activate(
            project,
            MineKotIdeaInspectionGeneration("1.0.1", Catalog, mode = mode) {},
        )
    }

    private object Catalog : MineKotInspectionCatalog {
        override val spiMajor: Int = MINEKOT_INSPECTIONS_SPI_MAJOR
        override val inspections: List<InspectionDescriptor> = listOf(DESCRIPTOR)

        override fun createInspection(ruleId: String): MineKotInspection? =
            if (ruleId == RULE_ID) Inspection else null
    }

    private object Inspection : MineKotInspection {
        override fun createSession(context: InspectionContext): MineKotInspectionSession =
            object : MineKotInspectionSession {
                override fun createVisitor(reporter: InspectionReporter): KtVisitorVoid =
                    object : KtTreeVisitorVoid() {
                        override fun visitNamedFunction(function: KtNamedFunction) {
                            val name = function.nameIdentifier ?: return
                            val suppressed = context.suppressionResolver.isSuppressed(RULE_ID, function)
                            if (name.text != "bad" || suppressed) return
                            reporter.report(
                                InspectionFinding(
                                    RULE_ID,
                                    "rename",
                                    MESSAGE,
                                    emptyList(),
                                    name.textRange.startOffset,
                                    name.textRange.endOffset,
                                    RuleSeverity.WARNING,
                                    listOf(
                                        CorrectionPlan(
                                            "${RULE_ID}.rename",
                                            FIX_LABEL,
                                            context.fileId,
                                            context.sourceSha256,
                                            listOf(
                                                TextEdit(
                                                    name.textRange.startOffset,
                                                    name.textRange.endOffset,
                                                    "bad",
                                                    "good",
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            )
                        }
                    }
            }
    }

    private companion object {
        const val RULE_ID = "minekot.test.rename"
        const val RULE_ALIAS = "LegacyRename"
        const val MESSAGE = "Rename bad function."
        const val FIX_LABEL = "Rename to good"
        val DESCRIPTOR = InspectionDescriptor(
            RULE_ID,
            setOf(RULE_ALIAS),
            "Rename bad",
            MESSAGE,
            InspectionCategory.CORRECTNESS,
            RuleSeverity.WARNING,
        )
    }
}
