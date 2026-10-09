package dev.effect.intellij.lsp.fixall

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.effect.intellij.EffectBundle

/**
 * The "Fix all '…' problems in file" entry shown in a quick fix's submenu. Applying it is delegated to
 * [EffectFixAllInFileService], which talks to the Effect language server and writes the merged edits.
 */
internal class EffectFixAllInFileIntention(
    val request: EffectFixAllRequest,
) : IntentionAction, DumbAware {
    override fun getText(): String = EffectBundle.message("intention.fix.all.in.file.text", request.fixTitle)

    override fun getFamilyName(): String = EffectBundle.message("intention.fix.all.in.file.family")

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && file?.virtualFile != null

    override fun startInWriteAction(): Boolean = false

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file == null) {
            return
        }
        project.getService(EffectFixAllInFileService::class.java).fixAllInFile(editor, file, request)
    }

    override fun toString(): String = "EffectFixAllInFileIntention(${request.fixTitle})"
}
