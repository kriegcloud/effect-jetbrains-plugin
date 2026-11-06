package dev.effect.intellij.typescript

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction

/** Tools menu entry for [EffectWebStormTypeScriptService.useTsrsForWebStormTypeScript]. */
class EffectUseTsrsForTypeScriptAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        EffectWebStormTypeScriptService.getInstance(project).useTsrsForWebStormTypeScript()
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = event.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
