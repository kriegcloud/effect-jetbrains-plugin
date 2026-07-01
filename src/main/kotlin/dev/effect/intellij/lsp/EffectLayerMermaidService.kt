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
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.Inflater

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

        ApplicationManager.getApplication().executeOnPooledThread {
            // Preferred path: a server-side execute-command (not shipped by @effect/tsgo today, kept for
            // forward compatibility). Fallback: read the Mermaid graph from the Layer-declaration hover,
            // which @effect/tsgo emits as an encoded `mermaid.live` link when `noExternal` is false.
            val result = runCatching {
                if (commandPlan != null) {
                    requestMermaidViaCommand(server, commandPlan, file, line, character)
                } else {
                    requestMermaidViaHover(server, file, line, character)
                }
            }

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater

                val mermaidCode = result.getOrNull()
                if (!mermaidCode.isNullOrBlank()) {
                    openMermaidSource(file, mermaidCode)
                    return@invokeLater
                }

                val message = result.exceptionOrNull()?.message
                    ?: "No Effect Layer graph was found here. Place the caret on a Layer declaration name and make sure the Effect LSP has `noExternal` disabled so hover graph links are produced."
                notifications.warning(project, "Effect Mermaid graph unavailable", message)
            }
        }
    }

    private fun requestMermaidViaCommand(
        server: LspServer,
        commandPlan: MermaidCommandPlan,
        file: VirtualFile,
        line: Int,
        character: Int,
    ): String? {
        val request = mapOf(
            "path" to filePath(file),
            "line" to line,
            "character" to character,
        )
        val result = server.sendRequestSync(REQUEST_TIMEOUT_MS) { languageServer ->
            languageServer.workspaceService.executeCommand(
                ExecuteCommandParams(commandPlan.command, commandPlan.arguments(request)),
            )
        }
        return extractMermaidCode(result)
    }

    private fun requestMermaidViaHover(server: LspServer, file: VirtualFile, line: Int, character: Int): String? {
        val hover: Hover? = server.sendRequestSync(REQUEST_TIMEOUT_MS) { languageServer ->
            languageServer.textDocumentService.hover(
                HoverParams(TextDocumentIdentifier(documentUri(file)), Position(line, character)),
            )
        }
        val markdown = hover?.let(::extractHoverMarkdown) ?: return null
        return decodeMermaidFromHover(markdown)
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

    private fun documentUri(file: VirtualFile): String =
        if (file.isInLocalFileSystem) file.toNioPath().toUri().toString() else file.url

    private data class MermaidCommandPlan(
        val command: String,
        val arguments: (Map<String, Any>) -> List<Any>,
    )

    companion object {
        const val LAYER_MERMAID_TSSERVER_REQUEST = "_effectGetLayerMermaid"
        const val TSSERVER_REQUEST_COMMAND = "typescript.tsserverRequest"
        private const val REQUEST_TIMEOUT_MS = 10_000
        private const val PAKO_PREFIX = "pako:"

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

        fun extractHoverMarkdown(hover: Hover): String? {
            val contents = hover.contents ?: return null
            return when {
                // @effect/tsgo returns MarkupContent (markdown). The legacy list form carries plain
                // strings and deprecated MarkedString entries; keep the string parts and skip the rest.
                contents.isRight -> contents.right?.value
                contents.isLeft -> contents.left.orEmpty()
                    .mapNotNull { item -> if (item.isLeft) item.left else null }
                    .joinToString("\n\n")

                else -> null
            }?.takeIf(String::isNotBlank)
        }

        /**
         * `@effect/tsgo` renders the Layer graph as a `mermaid.live` hover link whose fragment is
         * `pako:<payload>`, where the payload is base64url(no padding) of a zlib-compressed
         * `{"code": "<mermaid>"}` document (see `internal/layergraph/mermaidurl.go`). Decode it back to
         * the raw Mermaid source so the graph can be previewed locally.
         */
        fun decodeMermaidFromHover(markdown: String): String? {
            val start = markdown.indexOf(PAKO_PREFIX)
            if (start < 0) return null
            val encoded = markdown.substring(start + PAKO_PREFIX.length)
                .takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
            if (encoded.isBlank()) return null

            return runCatching {
                val compressed = Base64.getUrlDecoder().decode(encoded)
                val inflated = inflateZlib(compressed)
                val node = EffectJson.mapper.readTree(inflated)
                node.path("code").asText().takeIf(String::isNotBlank)
            }.getOrNull()
        }

        private fun inflateZlib(data: ByteArray): ByteArray {
            val inflater = Inflater()
            inflater.setInput(data)
            val output = ByteArrayOutputStream(data.size * 4)
            val buffer = ByteArray(8192)
            try {
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count == 0 && inflater.needsInput()) break
                    output.write(buffer, 0, count)
                }
            } finally {
                inflater.end()
            }
            return output.toByteArray()
        }
    }
}
