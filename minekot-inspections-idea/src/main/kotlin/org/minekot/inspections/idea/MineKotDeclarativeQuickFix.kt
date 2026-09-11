package org.minekot.inspections.idea

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInspection.*
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import org.minekot.inspections.core.CorrectionPlan
import org.minekot.inspections.core.applyTo

/** Applies complete guarded correction plans to real or preview documents. */
internal class MineKotDeclarativeQuickFix(private val correction: CorrectionPlan) : LocalQuickFix {
    override fun getFamilyName(): String = correction.label

    override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo {
        val document = previewDescriptor.psiElement.containingFile.viewProvider.document
            ?: return IntentionPreviewInfo.EMPTY
        val source = document.text
        document.replaceString(0, document.textLength, correction.applyTo(source, source.sha256()))
        return IntentionPreviewInfo.DIFF
    }

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = descriptor.psiElement.containingFile
        val document = file.viewProvider.document ?: return
        val source = document.text
        val corrected = correction.applyTo(source, source.sha256())
        document.replaceString(0, document.textLength, corrected)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}
