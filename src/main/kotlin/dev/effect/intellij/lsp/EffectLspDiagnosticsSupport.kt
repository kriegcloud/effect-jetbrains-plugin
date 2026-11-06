package dev.effect.intellij.lsp

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
import com.intellij.psi.PsiDocumentManager
import dev.effect.intellij.lsp.fixall.EffectFixAllCapableQuickFix
import dev.effect.intellij.lsp.fixall.EffectFixAllInFileQuickFix
import dev.effect.intellij.lsp.fixall.isEffectDiagnostic
import org.eclipse.lsp4j.Diagnostic

internal class EffectLspDiagnosticsSupport : LspDiagnosticsSupport() {
    override fun createAnnotation(
        holder: AnnotationHolder,
        diagnostic: Diagnostic,
        textRange: TextRange,
        quickFixes: List<IntentionAction>,
    ) {
        val fixes = withFixAllOptions(diagnostic, quickFixes)
        val file = holder.currentAnnotationSession.file
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
        if (document == null) {
            super.createAnnotation(holder, diagnostic, textRange, fixes)
            return
        }

        val severity = diagnostic.effectDiagnosticRuleName()?.let { ruleName ->
            effectDiagnosticDirectives(document, file.name).effectiveSeverity(ruleName, diagnosticLine(document, textRange))
        }

        when (severity) {
            EffectDiagnosticDirectiveSeverity.OFF,
            EffectDiagnosticDirectiveSeverity.SKIP_FILE,
            -> return
            EffectDiagnosticDirectiveSeverity.ERROR,
            EffectDiagnosticDirectiveSeverity.WARNING,
            EffectDiagnosticDirectiveSeverity.SUGGESTION,
            EffectDiagnosticDirectiveSeverity.MESSAGE,
            -> super.createAnnotation(holder, diagnostic.withEffectDirectiveSeverity(severity), textRange, fixes)
            null -> super.createAnnotation(holder, diagnostic, textRange, fixes)
        }
    }

    private fun diagnosticLine(document: Document, textRange: TextRange): Int {
        if (document.textLength == 0) {
            return 0
        }
        return document.getLineNumber(textRange.startOffset.coerceIn(0, document.textLength))
    }

    internal companion object {
        /**
         * Gives every quick fix of an Effect diagnostic a "Fix all '…' problems in file" companion: a
         * submenu entry on the fix itself, plus a top-level entry listed right after it in the Alt+Enter
         * popup. Both resolve lazily from the fix's title and drop out for directive-insertion fixes.
         * Non-Effect diagnostics (plain TypeScript errors served by the same `@effect/tsgo` process) keep
         * the platform's quick fixes untouched; the server already offers its own combined fixes there.
         */
        fun withFixAllOptions(diagnostic: Diagnostic, quickFixes: List<IntentionAction>): List<IntentionAction> =
            if (quickFixes.isNotEmpty() && diagnostic.isEffectDiagnostic()) {
                quickFixes.flatMap { quickFix ->
                    listOf(EffectFixAllCapableQuickFix(quickFix, diagnostic), EffectFixAllInFileQuickFix(quickFix, diagnostic))
                }
            } else {
                quickFixes
            }
    }
}
