package dev.effect.intellij.lsp

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import dev.effect.intellij.core.EffectPluginConstants

class EffectShowLayerMermaidAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val position = editor.caretModel.primaryCaret.logicalPosition

        project.getService(EffectLayerMermaidService::class.java)
            .showLayerMermaid(file, position.line, position.column)
    }

    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        event.presentation.isEnabledAndVisible = event.project != null &&
            event.getData(CommonDataKeys.EDITOR) != null &&
            file?.extension in EffectPluginConstants.SUPPORTED_TYPESCRIPT_EXTENSIONS
    }
}
