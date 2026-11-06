package dev.effect.intellij.lsp.fixall

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.effect.intellij.EffectBundle
import org.eclipse.lsp4j.Diagnostic

/**
 * A top-level "Fix all '…' problems in file" entry listed directly in the Alt+Enter popup next to the
 * Effect codemod fix it is paired with, so file-wide application does not depend on the user finding
 * the fix's submenu.
 *
 * The paired fix is one of the platform's lazy LSP placeholders whose title is empty until its
 * `textDocument/codeAction` round trip ran. [isAvailable] therefore asks the paired fix first (which
 * resolves the placeholder) and only then decides from its resolved title; directive-insertion fixes
 * (`Disable <rule> for …`) and unresolved placeholders never produce an entry.
 */
internal class EffectFixAllInFileQuickFix(
    val paired: IntentionAction,
    val diagnostic: Diagnostic,
) : IntentionAction, DumbAware {
    private fun request(): EffectFixAllRequest? = effectFixAllRequest(diagnostic, paired.text)

    override fun getText(): String =
        request()?.let { EffectBundle.message("intention.fix.all.in.file.text", it.fixTitle) } ?: familyName

    override fun getFamilyName(): String = EffectBundle.message("intention.fix.all.in.file.family")

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && file?.virtualFile != null && paired.isAvailable(project, editor, file) && request() != null

    override fun startInWriteAction(): Boolean = false

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val request = request() ?: return
        if (editor == null || file == null) {
            return
        }
        project.getService(EffectFixAllInFileService::class.java).fixAllInFile(editor, file, request)
    }

    override fun toString(): String = "EffectFixAllInFileQuickFix(${paired.text})"
}
