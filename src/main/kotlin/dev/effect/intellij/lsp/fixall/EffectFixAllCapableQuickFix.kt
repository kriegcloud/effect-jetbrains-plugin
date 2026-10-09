package dev.effect.intellij.lsp.fixall

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.IntentionActionWithOptions
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.Diagnostic

/**
 * Wraps one LSP quick fix of an Effect diagnostic so the intention popup's "more" submenu offers
 * "Fix all '…' problems in file" next to it.
 *
 * The platform hands [EffectLspDiagnosticsSupport][dev.effect.intellij.lsp.EffectLspDiagnosticsSupport]
 * lazy quick-fix placeholders whose titles resolve on the first `textDocument/codeAction` round trip,
 * so the option is computed on demand from the resolved title rather than at annotation time. Nothing
 * else about the fix changes: text, availability, preview, and invocation go straight to the wrapped
 * action.
 *
 * This deliberately does not implement `IntentionActionDelegate`: the popup unwraps delegates before it
 * looks for `IntentionActionWithOptions`, which would hide the submenu entry.
 */
internal class EffectFixAllCapableQuickFix(
    val delegate: IntentionAction,
    val diagnostic: Diagnostic,
) : IntentionAction, IntentionActionWithOptions, DumbAware, Comparable<IntentionAction> {
    override fun getText(): String = delegate.text

    override fun getFamilyName(): String = delegate.familyName

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        delegate.isAvailable(project, editor, file)

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        delegate.invoke(project, editor, file)
    }

    override fun startInWriteAction(): Boolean = delegate.startInWriteAction()

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        delegate.generatePreview(project, editor, file)

    override fun getElementToMakeWritable(currentFile: PsiFile): PsiElement? =
        delegate.getElementToMakeWritable(currentFile)

    override fun getOptions(): List<IntentionAction> {
        val request = effectFixAllRequest(diagnostic, delegate.text) ?: return emptyList()
        return listOf(EffectFixAllInFileIntention(request))
    }

    /**
     * The popup consults the action's own options when this policy is set, independent of the
     * `HighlightDisplayKey` the highlighting pass attached to the fix. LSP quick fixes never had
     * inspection-profile options to combine with.
     */
    override fun getCombiningPolicy(): IntentionActionWithOptions.CombiningPolicy =
        IntentionActionWithOptions.CombiningPolicy.IntentionOptionsOnly

    /**
     * Keeps the platform's relative order between fixes of one diagnostic (its wrappers compare by
     * index) and stays symmetric with unwrapped LSP fixes, which report `0` for foreign classes.
     */
    override fun compareTo(other: IntentionAction): Int {
        val otherDelegate = (other as? EffectFixAllCapableQuickFix)?.delegate ?: return 0
        @Suppress("UNCHECKED_CAST")
        val comparable = delegate as? Comparable<IntentionAction> ?: return 0
        return comparable.compareTo(otherDelegate)
    }

    override fun toString(): String = "EffectFixAllCapableQuickFix(${delegate.text})"
}
