package dev.effect.intellij.lsp

import com.fasterxml.jackson.databind.JsonNode
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import dev.effect.intellij.core.EffectJson
import dev.effect.intellij.core.EffectPluginConstants
import dev.effect.intellij.notifications.EffectNotificationService
import org.eclipse.lsp4j.ExecuteCommandParams
import java.nio.file.Files
import java.nio.file.Path

@Service(Service.Level.PROJECT)
class EffectLayerMermaidService(private val project: Project) {
    fun showLayerMermaid(file: VirtualFile, line: Int, character: Int) {
        val notifications = ApplicationManager.getApplication().getService(EffectNotificationService::class.java)
        val server = findRunningServer(file)
        if (server == null) {
            LspServerManager.getInstance(project).startServersIfNeeded(EffectLspServerSupportProvider::class.java)
            notifications.warning(
                project,
                "Effect Mermaid graph unavailable",
                "Effect LSP is not running for this file yet. Open or focus the file again once the Effect language server is running.",
            )
            return
        }

        val commandPlan = commandPlan(server)
        if (commandPlan == null) {
            notifications.warning(
                project,
                "Effect Mermaid graph unavailable",
                "The current Effect LSP does not advertise a Mermaid graph command. Hover Mermaid links still work; the local graph action is ready for the server-side custom request.",
            )
            return
        }

        val request = mapOf(
            "path" to filePath(file),
            "line" to line,
            "character" to character,
        )

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching {
                server.sendRequestSync(REQUEST_TIMEOUT_MS) { languageServer ->
                    languageServer.workspaceService.executeCommand(
                        ExecuteCommandParams(commandPlan.command, commandPlan.arguments(request)),
                    )
                }
            }

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater

                val mermaidCode = result.getOrNull()?.let(::extractMermaidCode)
                if (!mermaidCode.isNullOrBlank()) {
                    openMermaidSource(file, mermaidCode)
                    return@invokeLater
                }

                val message = result.exceptionOrNull()?.message
                    ?: "The server did not return Mermaid source for this position."
                notifications.warning(project, "Effect Mermaid graph unavailable", message)
            }
        }
    }

    private fun findRunningServer(file: VirtualFile): LspServer? =
        LspServerManager.getInstance(project)
            .getServersForProvider(EffectLspServerSupportProvider::class.java)
            .firstOrNull { server ->
                server.state == LspServerState.Running && server.descriptor.isSupportedFile(file)
            }

    private fun commandPlan(server: LspServer): MermaidCommandPlan? {
        val commands = server.initializeResult?.capabilities?.executeCommandProvider?.commands.orEmpty()
        return when {
            TSSERVER_REQUEST_COMMAND in commands -> MermaidCommandPlan(TSSERVER_REQUEST_COMMAND) { request ->
                listOf(
                    LAYER_MERMAID_TSSERVER_REQUEST,
                    request,
                    mapOf("isAsync" to true, "lowPriority" to true),
                )
            }

            LAYER_MERMAID_TSSERVER_REQUEST in commands -> MermaidCommandPlan(LAYER_MERMAID_TSSERVER_REQUEST) { request ->
                listOf(request)
            }

            else -> null
        }
    }

    private fun openMermaidSource(sourceFile: VirtualFile, mermaidCode: String) {
        val mermaidDir = Path.of(PathManager.getSystemPath(), EffectPluginConstants.DEFAULT_BINARY_CACHE_DIR, "mermaid")
        Files.createDirectories(mermaidDir)

        val baseName = sourceFile.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "layer" }
        val mermaidPath = mermaidDir.resolve("$baseName-${System.currentTimeMillis()}.mmd")
        Files.writeString(mermaidPath, mermaidCode)

        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(mermaidPath)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(mermaidPath.toString())
            ?: return

        FileEditorManager.getInstance(project).openFile(virtualFile, true)
    }

    private fun filePath(file: VirtualFile): String =
        if (file.isInLocalFileSystem) file.path else file.url

    private data class MermaidCommandPlan(
        val command: String,
        val arguments: (Map<String, Any>) -> List<Any>,
    )

    companion object {
        const val LAYER_MERMAID_TSSERVER_REQUEST = "_effectGetLayerMermaid"
        const val TSSERVER_REQUEST_COMMAND = "typescript.tsserverRequest"
        private const val REQUEST_TIMEOUT_MS = 10_000

        fun extractMermaidCode(result: Any?): String? {
            val node = result?.let { EffectJson.mapper.valueToTree<JsonNode>(it) } ?: return null
            return extractMermaidCode(node)
        }

        private fun extractMermaidCode(node: JsonNode): String? =
            when {
                node.isTextual -> node.asText().takeIf(String::isNotBlank)
                node.hasNonNull("mermaidCode") -> node.path("mermaidCode").asText().takeIf(String::isNotBlank)
                node.hasNonNull("mermaid") -> node.path("mermaid").asText().takeIf(String::isNotBlank)
                node.hasNonNull("code") -> node.path("code").asText().takeIf(String::isNotBlank)
                node.hasNonNull("body") -> extractMermaidCode(node.path("body"))
                else -> null
            }
    }
}
