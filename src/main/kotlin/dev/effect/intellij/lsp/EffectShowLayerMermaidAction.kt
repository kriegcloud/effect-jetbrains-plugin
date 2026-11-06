package dev.effect.intellij.lsp

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import dev.effect.intellij.core.EffectPluginConstants
import org.eclipse.lsp4j.Position

class EffectShowLayerMermaidAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val position = mermaidRequestPosition(editor)

        project.getService(EffectLayerMermaidService::class.java)
            .showLayerMermaid(file, position.line, position.character)
    }

    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        event.presentation.isEnabledAndVisible = event.project != null &&
            event.getData(CommonDataKeys.EDITOR) != null &&
            file?.extension in EffectPluginConstants.SUPPORTED_TYPESCRIPT_EXTENSIONS
    }
}

/** LSP character positions count UTF-16 code units, independently of editor tab/display columns. */
internal fun mermaidRequestPosition(editor: Editor): Position {
    val offset = editor.caretModel.primaryCaret.offset
    val document = editor.document
    val line = document.getLineNumber(offset)
    return Position(line, offset - document.getLineStartOffset(line))
}
