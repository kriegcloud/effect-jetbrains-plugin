package dev.effect.intellij.lsp

import com.intellij.execution.ExecutionException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.intellij.platform.lsp.api.LspServerListener
import dev.effect.intellij.core.EffectPluginConstants
import dev.effect.intellij.core.EffectJson
import dev.effect.intellij.status.EffectStatusService
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.InitializeResult

class EffectLspServerDescriptor(project: Project) : ProjectWideLspServerDescriptor(project, "Effect Tsgo") {
    override fun isSupportedFile(file: VirtualFile): Boolean =
        file.extension in EffectPluginConstants.SUPPORTED_TYPESCRIPT_EXTENSIONS

    @Throws(ExecutionException::class)
    override fun createCommandLine() =
        try {
            project.getService(EffectLspProjectService::class.java)
                .activeLaunchConfiguration()
                .also { launch ->
                    project.getService(EffectStatusService::class.java).markStarting(launch.resolution.binaryPath.toString())
                }
                .commandLine
        } catch (error: Exception) {
            project.getService(EffectStatusService::class.java).markError(error.message ?: "Failed to start Effect LSP")
            throw ExecutionException(error.message, error)
        }

    override fun createInitializationOptions() =
        project.getService(EffectLspProjectService::class.java)
            .activeLaunchConfiguration()
            .initializationOptions

    override fun getWorkspaceConfiguration(item: ConfigurationItem): Any? {
        val workspaceConfiguration = project.getService(EffectLspProjectService::class.java)
            .activeLaunchConfiguration()
            .workspaceConfiguration
            ?: return null

        val section = item.section
        val node = if (section.isNullOrBlank()) {
            workspaceConfiguration
        } else {
            section.split('.').fold(workspaceConfiguration) { current, part -> current.path(part) }
        }

        return if (node.isMissingNode || node.isNull) null else EffectJson.mapper.convertValue(node, Any::class.java)
    }

    override val lspServerListener: LspServerListener = object : LspServerListener {
        override fun serverInitialized(params: InitializeResult) {
            project.getService(EffectStatusService::class.java)
                .markRunning(project.getService(EffectLspProjectService::class.java).activeLaunchConfiguration().resolution.binaryPath.toString())
        }

        override fun serverStopped(shutdownNormally: Boolean) {
            project.getService(EffectLspProjectService::class.java).clearActiveLaunchConfiguration()
            val status = project.getService(EffectStatusService::class.java)
            if (shutdownNormally) {
                status.markRestartRequired("Effect LSP stopped and can be restarted.")
            } else {
                status.markError("Effect LSP stopped unexpectedly.")
            }
        }
    }
}
