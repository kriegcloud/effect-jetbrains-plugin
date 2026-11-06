package dev.effect.intellij.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem
import dev.effect.intellij.core.EffectPluginConstants

class EffectLspServerSupportProvider : LspIntegrationProvider {
    override fun fileOpened(project: Project, file: VirtualFile, clientStarter: LspIntegrationProvider.LspClientStarter) {
        if (file.extension in EffectPluginConstants.SUPPORTED_TYPESCRIPT_EXTENSIONS) {
            clientStarter.ensureClientStarted(EffectLspServerDescriptor(project))
        }
    }

    override fun createWidgetItem(lspClient: LspClient, currentFile: VirtualFile?): LspClientWidgetItem =
        EffectLspWidgetItem(lspClient, currentFile)
}
