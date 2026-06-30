package dev.effect.intellij.lsp

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
import com.intellij.psi.PsiDocumentManager
import org.eclipse.lsp4j.Diagnostic

internal class EffectLspDiagnosticsSupport : LspDiagnosticsSupport() {
    override fun createAnnotation(
        holder: AnnotationHolder,
        diagnostic: Diagnostic,
        textRange: TextRange,
        quickFixes: List<IntentionAction>,
    ) {
        val file = holder.currentAnnotationSession.file
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
        if (document == null) {
            super.createAnnotation(holder, diagnostic, textRange, quickFixes)
            return
        }

        val severity = effectDirectiveSeverityForDiagnostic(
            sourceText = document.text,
            diagnosticLine = diagnosticLine(document, textRange),
            diagnostic = diagnostic,
        )

        when (severity) {
            EffectDiagnosticDirectiveSeverity.OFF,
            EffectDiagnosticDirectiveSeverity.SKIP_FILE,
            -> return
            EffectDiagnosticDirectiveSeverity.ERROR,
            EffectDiagnosticDirectiveSeverity.WARNING,
            EffectDiagnosticDirectiveSeverity.SUGGESTION,
            EffectDiagnosticDirectiveSeverity.MESSAGE,
            -> super.createAnnotation(holder, diagnostic.withEffectDirectiveSeverity(severity), textRange, quickFixes)
            null -> super.createAnnotation(holder, diagnostic, textRange, quickFixes)
        }
    }

    private fun diagnosticLine(document: Document, textRange: TextRange): Int {
        if (document.textLength == 0) {
            return 0
        }
        return document.getLineNumber(textRange.startOffset.coerceIn(0, document.textLength))
    }
}
