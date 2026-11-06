package dev.effect.intellij.lsp

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.ShowLogAction
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem
import dev.effect.intellij.core.EffectPluginConstants
import dev.effect.intellij.settings.EffectProjectSettingsConfigurable
import dev.effect.intellij.status.EffectLspStatus
import dev.effect.intellij.status.EffectStatusService

class EffectLspWidgetItem(
    lspClient: LspClient,
    currentFile: VirtualFile?,
) : LspClientWidgetItem(
    lspClient,
    currentFile,
    AllIcons.Toolwindows.ToolWindowMessages,
    EffectProjectSettingsConfigurable::class.java,
) {
    override val statusBarTooltip: String
        get() {
            val snapshot = EffectStatusService.getInstance(lspClient.project).currentSnapshot()
            return buildString {
                append("Effect @effect/tsgo")
                snapshot.detail?.let {
                    append(": ")
                    append(it)
                }
            }
        }

    override val isError: Boolean
        get() = EffectStatusService.getInstance(lspClient.project).currentSnapshot().status == EffectLspStatus.ERROR

    override fun createAdditionalInlineActions(): List<AnAction> =
        listOf(
            object : AnAction("Restart", "Restart the Effect language server", AllIcons.Actions.Restart) {
                override fun actionPerformed(event: AnActionEvent) {
                    lspClient.project.getService(EffectLspProjectService::class.java)
                        .restart("Effect LSP restart requested from the widget")
                }
            },
            object : AnAction("Settings", "Open Effect settings", AllIcons.General.Settings) {
                override fun actionPerformed(event: AnActionEvent) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(lspClient.project, EffectProjectSettingsConfigurable::class.java)
                }
            },
            object : AnAction("Logs", "Open the IDE log directory", AllIcons.Actions.MenuOpen) {
                override fun actionPerformed(event: AnActionEvent) {
                    ShowLogAction.showLog()
                }
            },
            object : AnAction("Dev Tools", "Focus Effect dev tools", AllIcons.Toolwindows.ToolWindowMessages) {
                override fun actionPerformed(event: AnActionEvent) {
                    ToolWindowManager.getInstance(lspClient.project)
                        .getToolWindow(EffectPluginConstants.TOOL_WINDOW_ID)
                        ?.show()
                }
            },
        )
}
