package dev.effect.intellij.lsp.fixall

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspServer
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.platform.lsp.api.customization.LspIntentionAction
import com.intellij.psi.PsiFile
import dev.effect.intellij.EffectBundle
import dev.effect.intellij.core.logger
import dev.effect.intellij.lsp.EffectLspServerSupportProvider
import dev.effect.intellij.notifications.EffectNotificationService
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CodeActionTriggerKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DocumentDiagnosticParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit

/**
 * Applies one Effect quick fix to every diagnostic of its rule in a file.
 *
 * Flow (on the EDT, inside the intention command so the whole run is one undo step):
 * 1. pull the file's current diagnostics from the Effect language server (`textDocument/diagnostic`),
 * 2. keep the ones of the same rule that no `@effect-diagnostics` directive silences,
 * 3. ask for their quick fixes in a single `textDocument/codeAction` request,
 * 4. merge the matching, non-overlapping edits ([planFixAll]) and
 * 5. write them through the platform's own LSP edit application ([LspIntentionAction]).
 *
 * Steps 1–4 run under a cancellable modal progress; the write happens only if the document did not
 * change meanwhile.
 */
@Service(Service.Level.PROJECT)
class EffectFixAllInFileService(private val project: Project) {
    private val log = logger<EffectFixAllInFileService>()

    internal fun fixAllInFile(editor: Editor, psiFile: PsiFile, request: EffectFixAllRequest) {
        val virtualFile = psiFile.virtualFile ?: return
        val document = editor.document
        val notifications = ApplicationManager.getApplication().getService(EffectNotificationService::class.java)
        val title = EffectBundle.message("fix.all.in.file.notification.title")

        val server = findRunningServer(virtualFile)
        if (server == null) {
            LspServerManager.getInstance(project).startServersIfNeeded(EffectLspServerSupportProvider::class.java)
            notifications.warning(project, title, EffectBundle.message("fix.all.in.file.server.unavailable"))
            return
        }
        if (server.initializeResult?.capabilities?.diagnosticProvider == null) {
            notifications.warning(project, title, EffectBundle.message("fix.all.in.file.pull.diagnostics.unsupported"))
            return
        }

        val documentId = server.getDocumentIdentifier(virtualFile)
        val sourceText = document.text
        val modificationStampBefore = document.modificationStamp
        val lineIndex = EffectTextLineIndex(sourceText)
        val fullRange = fullDocumentRange(sourceText)

        val outcome = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<Outcome, RuntimeException> {
                    computeOutcome(server, documentId, request, sourceText, fullRange, lineIndex)
                },
                EffectBundle.message("fix.all.in.file.progress", request.fixTitle),
                true,
                project,
            )
        } catch (_: ProcessCanceledException) {
            return
        } catch (error: Exception) {
            log.warn("Effect fix-all failed for ${virtualFile.path}", error)
            notifications.error(project, title, EffectBundle.message("fix.all.in.file.failed", error.message ?: error.javaClass.simpleName))
            return
        }

        if (outcome.selectedDiagnostics == 0) {
            HintManager.getInstance().showInformationHint(
                editor,
                EffectBundle.message("fix.all.in.file.nothing.to.fix", request.ruleDisplayName),
            )
            return
        }

        val plan = outcome.plan
        if (plan.fixedDiagnostics == 0 || plan.edits.isEmpty()) {
            notifications.warning(project, title, nothingAppliedMessage(request, outcome.selectedDiagnostics, plan))
            return
        }

        if (document.modificationStamp != modificationStampBefore) {
            notifications.warning(project, title, EffectBundle.message("fix.all.in.file.document.changed"))
            return
        }

        val combined = CodeAction(EffectBundle.message("intention.fix.all.in.file.text", request.fixTitle)).also { action ->
            action.kind = CodeActionKind.QuickFix
            action.edit = WorkspaceEdit(mapOf(documentId.uri to plan.edits))
        }
        // The platform intention resolves its URI -> Document map inside isAvailable; invoke() is a
        // silent no-op until that ran (verified against the 262 bytecode), exactly as the IDE's own
        // intention flow calls isAvailable before invoke.
        val applier = LspIntentionAction(server as LspClient, combined)
        if (!applier.isAvailable(project, editor, psiFile)) {
            notifications.warning(project, title, EffectBundle.message("fix.all.in.file.not.applicable", request.fixTitle, outcome.selectedDiagnostics))
            return
        }
        applier.invoke(project, editor, psiFile)

        log.info(
            "Effect fix-all applied '${request.fixTitle}' to ${plan.fixedDiagnostics} of ${outcome.selectedDiagnostics} " +
                "'${request.ruleDisplayName}' diagnostics in ${virtualFile.name} " +
                "(unmatched=${plan.unmatchedDiagnostics}, overlapping=${plan.overlappingDiagnostics}, unsupported=${plan.unsupportedDiagnostics})",
        )

        if (plan.skippedDiagnostics > 0) {
            notifications.info(
                project,
                title,
                EffectBundle.message(
                    "fix.all.in.file.partial",
                    request.fixTitle,
                    plan.fixedDiagnostics,
                    outcome.selectedDiagnostics,
                    plan.overlappingDiagnostics,
                    plan.unmatchedDiagnostics,
                    plan.unsupportedDiagnostics,
                ),
            )
        } else {
            HintManager.getInstance().showInformationHint(
                editor,
                EffectBundle.message("fix.all.in.file.applied", request.fixTitle, plan.fixedDiagnostics),
            )
        }
    }

    private class Outcome(val selectedDiagnostics: Int, val plan: EffectFixAllPlan)

    private fun nothingAppliedMessage(request: EffectFixAllRequest, selected: Int, plan: EffectFixAllPlan): String =
        when {
            plan.unsupportedDiagnostics == 0 && plan.overlappingDiagnostics == 0 ->
                EffectBundle.message("fix.all.in.file.no.matching.fix", request.fixTitle, selected)
            plan.unmatchedDiagnostics == 0 && plan.overlappingDiagnostics == 0 ->
                EffectBundle.message("fix.all.in.file.not.applicable", request.fixTitle, selected)
            else ->
                EffectBundle.message(
                    "fix.all.in.file.nothing.applied",
                    request.fixTitle,
                    plan.unmatchedDiagnostics,
                    plan.unsupportedDiagnostics,
                    plan.overlappingDiagnostics,
                )
        }

    private fun computeOutcome(
        server: LspServer,
        documentId: TextDocumentIdentifier,
        request: EffectFixAllRequest,
        sourceText: String,
        fullRange: Range,
        lineIndex: EffectTextLineIndex,
    ): Outcome {
        val published = pullDiagnostics(server, documentId)
        val selected = selectFixAllDiagnostics(published, sourceText, request.ruleKey)
        if (selected.isEmpty()) {
            return Outcome(0, EffectFixAllPlan.EMPTY)
        }

        ProgressManager.checkCanceled()
        val codeActions = requestQuickFixes(server, documentId, fullRange, selected)
        val plan = planFixAll(request, documentId.uri, selected, codeActions, lineIndex::rangeOf)
        return Outcome(selected.size, plan)
    }

    /**
     * The platform's synchronous request helper reports timeouts, server errors, and a stopped server
     * as a `null` response instead of throwing, so `null` is turned into a failure here rather than
     * being mistaken for "no diagnostics" / "no fixes".
     */
    private fun pullDiagnostics(server: LspServer, documentId: TextDocumentIdentifier): List<Diagnostic> {
        val report = server.sendRequestSync(REQUEST_TIMEOUT_MS) { languageServer ->
            languageServer.textDocumentService.diagnostic(DocumentDiagnosticParams(documentId))
        } ?: throw EffectFixAllServerException(EffectBundle.message("fix.all.in.file.no.response", "textDocument/diagnostic"))
        return report
            .takeIf { it.isRelatedFullDocumentDiagnosticReport }
            ?.relatedFullDocumentDiagnosticReport
            ?.items
            .orEmpty()
    }

    private fun requestQuickFixes(
        server: LspServer,
        documentId: TextDocumentIdentifier,
        range: Range,
        diagnostics: List<Diagnostic>,
    ): List<CodeAction> {
        val context = CodeActionContext(diagnostics, listOf(CodeActionKind.QuickFix)).also { context ->
            context.triggerKind = CodeActionTriggerKind.Invoked
        }
        val response = server.sendRequestSync(REQUEST_TIMEOUT_MS) { languageServer ->
            languageServer.textDocumentService.codeAction(CodeActionParams(documentId, range, context))
        } ?: throw EffectFixAllServerException(EffectBundle.message("fix.all.in.file.no.response", "textDocument/codeAction"))
        return response.mapNotNull { either -> if (either.isRight) either.right else null }
    }

    private fun findRunningServer(file: VirtualFile): LspServer? =
        LspServerManager.getInstance(project)
            .getServersForProvider(EffectLspServerSupportProvider::class.java)
            .firstOrNull { server ->
                server.state == LspServerState.Running && server.descriptor.isSupportedFile(file)
            }

    private fun fullDocumentRange(text: String): Range {
        val lastLineStart = text.lastIndexOf('\n') + 1
        val lastLine = text.count { it == '\n' }
        return Range(Position(0, 0), Position(lastLine, text.length - lastLineStart))
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 30_000
    }
}

internal class EffectFixAllServerException(message: String) : RuntimeException(message)
