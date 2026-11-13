package dev.effect.intellij.lsp

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.psi.PsiFile
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent

/**
 * Makes the platform apply the Effect server's diagnostics to the editor again, for a decision in
 * [EffectLspDiagnosticsSupport] that had to be made before the project could answer it.
 *
 * How diagnostics reach the editor differs between the supported platform lines:
 *
 * - 2026.2 (262) collects them in a highlighting pass, which the daemon runs again when asked to.
 * - 2026.3 (263) applies them when the server sends them and has no public way to do so again. The
 *   server is therefore told that the file may have changed (`workspace/didChangeWatchedFiles`).
 *   `@effect/tsgo` answers with `workspace/diagnostic/refresh`, on which the platform requests the
 *   diagnostics of the open files and applies them (`watched-files-refresh` in the real-binary
 *   verifier records that answer).
 */
internal object EffectLspDiagnosticsRefresh {
    /** Call on the EDT. */
    fun reapply(file: PsiFile) {
        val project = file.project
        val virtualFile = file.viewProvider.virtualFile
        if (project.isDisposed || !file.isValid) {
            return
        }
        DaemonCodeAnalyzer.getInstance(project).restart(file, "Effect diagnostics can be decided now")
        LspClientManager.getInstance(project)
            .getClients(EffectLspServerSupportProvider::class.java)
            .filter { it.state == LspServerState.Running && it.descriptor.isSupportedFile(virtualFile) }
            .forEach { client ->
                val changed = FileEvent(client.getDocumentIdentifier(virtualFile).uri, FileChangeType.Changed)
                client.sendNotification { server ->
                    server.workspaceService.didChangeWatchedFiles(DidChangeWatchedFilesParams(listOf(changed)))
                }
            }
    }
}
