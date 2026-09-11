package org.minekot.inspections.idea

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.progress.ProcessCanceledException
import org.minekot.inspections.core.RuleSeverity
import java.security.MessageDigest

internal fun RuleSeverity.highlightType(): ProblemHighlightType =
    when (this) {
        RuleSeverity.INFO -> ProblemHighlightType.INFORMATION
        RuleSeverity.WEAK_WARNING -> ProblemHighlightType.WEAK_WARNING
        RuleSeverity.WARNING -> ProblemHighlightType.GENERIC_ERROR_OR_WARNING
        RuleSeverity.ERROR -> ProblemHighlightType.GENERIC_ERROR
    }

internal fun String.sha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

internal fun Throwable.rethrowWhenFatal() {
    if (this is ProcessCanceledException || this is VirtualMachineError || this is LinkageError) throw this
}
