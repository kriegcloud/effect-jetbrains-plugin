package dev.effect.intellij.lsp.fixall

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.platform.lsp.api.customization.LspIntentionAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import dev.effect.intellij.EffectBundle
import dev.effect.intellij.core.logger
import dev.effect.intellij.lsp.EffectLspServerSupportProvider
import dev.effect.intellij.lsp.NEW_SCHEMA_CLASS_RULE
import dev.effect.intellij.lsp.effectDiagnosticRuleName
import dev.effect.intellij.lsp.isUnsatisfiableNewSchemaClass
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
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit

/**
 * Applies one Effect quick fix to every diagnostic of its rule in a file.
 *
 * [runFixAllPasses] repeats, under a cancellable modal progress: pull the file's diagnostics
 * (`textDocument/diagnostic`), keep the rule's sites that the editor shows and no `@effect-diagnostics`
 * directive silences,
 * request their quick fixes in one `textDocument/codeAction` call, merge the matching non-overlapping
 * edits ([planFixAll]), write them through the platform's own LSP edit application
 * ([LspIntentionAction]), and pull again to undo any pass that introduced new errors.
 */
@Service(Service.Level.PROJECT)
class EffectFixAllInFileService(private val project: Project) {
    private val log = logger<EffectFixAllInFileService>()

    internal fun fixAllInFile(editor: Editor, psiFile: PsiFile, request: EffectFixAllRequest) {
        val virtualFile = psiFile.virtualFile ?: return
        val notifications = ApplicationManager.getApplication().getService(EffectNotificationService::class.java)
        val title = EffectBundle.message("fix.all.in.file.notification.title")

        val server = findRunningServer(virtualFile)
        if (server == null) {
            LspClientManager.getInstance(project).startClientsIfNeeded(EffectLspServerSupportProvider::class.java)
            notifications.warning(project, title, EffectBundle.message("fix.all.in.file.server.unavailable"))
            return
        }
        if (server.initializeResult?.capabilities?.diagnosticProvider == null) {
            notifications.warning(project, title, EffectBundle.message("fix.all.in.file.pull.diagnostics.unsupported"))
            return
        }

        val documentId = server.getDocumentIdentifier(virtualFile)
        val session = EditorFixAllSession(server, documentId, editor, psiFile, request)

        val result = try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable<EffectFixAllRunResult, RuntimeException> {
                    runFixAllPasses(request, documentId.uri, session)
                },
                EffectBundle.message("fix.all.in.file.progress", request.fixTitle),
                true,
                project,
            )
        } catch (_: ProcessCanceledException) {
            // Passes already applied stay; they are one undo step.
            return
        } catch (error: Exception) {
            log.warn("Effect fix-all failed for ${virtualFile.path}", error)
            notifications.error(project, title, EffectBundle.message("fix.all.in.file.failed", error.message ?: error.javaClass.simpleName))
            return
        }

        log.info(
            "Effect fix-all '${request.fixTitle}' on '${request.ruleDisplayName}' in ${virtualFile.name}: " +
                "initial=${result.initialDiagnostics}, applied=${result.appliedFixes}, remaining=${result.remainingDiagnostics}, " +
                "rejected=${result.rejectedFixes}, passes=${result.passes}",
        )

        when {
            result.initialDiagnostics == 0 ->
                HintManager.getInstance().showInformationHint(
                    editor,
                    EffectBundle.message("fix.all.in.file.nothing.to.fix", request.ruleDisplayName),
                )
            result.appliedFixes == 0 && result.rejectedFixes > 0 ->
                notifications.warning(
                    project,
                    title,
                    EffectBundle.message("fix.all.in.file.all.rejected", request.fixTitle, result.rejectedFixes),
                )
            result.appliedFixes == 0 ->
                notifications.warning(
                    project,
                    title,
                    nothingAppliedMessage(request, result.initialDiagnostics, result.lastEmptyPlan ?: EffectFixAllPlan.EMPTY),
                )
            result.remainingDiagnostics > 0 || result.rejectedFixes > 0 ->
                notifications.info(
                    project,
                    title,
                    EffectBundle.message(
                        "fix.all.in.file.partial",
                        request.fixTitle,
                        result.appliedFixes,
                        result.remainingDiagnostics,
                        result.rejectedFixes,
                    ),
                )
            else ->
                HintManager.getInstance().showInformationHint(
                    editor,
                    EffectBundle.message("fix.all.in.file.applied", request.fixTitle, result.appliedFixes),
                )
        }
    }

    /**
     * Talks to the server from the progress thread and changes the document on the EDT. Every change runs
     * in a command of one shared group, so the whole run (including rolled-back passes) is one undo step.
     */
    internal inner class EditorFixAllSession(
        private val server: LspClient,
        private val documentId: TextDocumentIdentifier,
        private val editor: Editor,
        private val psiFile: PsiFile,
        private val request: EffectFixAllRequest,
    ) : EffectFixAllSession {
        private val document = editor.document
        private val commandGroup = Any()
        private val commandName = EffectBundle.message("intention.fix.all.in.file.text", request.fixTitle)
        private var expectedStamp = document.modificationStamp

        override fun currentText(): String = ApplicationManager.getApplication().runReadAction(Computable { document.text })

        override fun pullDiagnostics(): List<Diagnostic> =
            pullDiagnostics(server, documentId).let { diagnostics ->
                ApplicationManager.getApplication().runReadAction(
                    Computable { withoutUnsatisfiableDiagnostics(diagnostics, psiFile, document.text) },
                )
            }

        override fun requestQuickFixes(diagnostics: List<Diagnostic>): List<CodeAction> {
            val text = currentText()
            return requestQuickFixes(server, documentId, fullDocumentRange(text), diagnostics)
        }

        override fun apply(edits: List<TextEdit>) = onEdt {
            val combined = CodeAction(commandName).also { action ->
                action.kind = CodeActionKind.QuickFix
                action.edit = WorkspaceEdit(mapOf(documentId.uri to edits))
            }
            // The platform intention resolves its URI -> Document map inside isAvailable; invoke() is a
            // silent no-op until that ran (verified against the 262 bytecode), exactly as the IDE's own
            // intention flow calls isAvailable before invoke.
            val applier = LspIntentionAction(server, combined)
            if (!applier.isAvailable(project, editor, psiFile)) {
                throw EffectFixAllServerException(
                    EffectBundle.message("fix.all.in.file.not.applicable", request.fixTitle, edits.size),
                )
            }
            command { applier.invoke(project, editor, psiFile) }
        }

        override fun restore(text: String) = onEdt {
            command { WriteAction.run<RuntimeException> { document.setText(text) } }
        }

        private fun command(action: () -> Unit) {
            CommandProcessor.getInstance().executeCommand(project, action, commandName, commandGroup)
        }

        /** Refuses to write over edits the user made while the run was in progress. */
        private fun onEdt(action: () -> Unit) {
            // Complete dispatch and stamp bookkeeping even when the progress indicator is cancelled.
            // Otherwise invokeAndWait may discard a queued rollback (or abandon a queued apply).
            ProgressManager.getInstance().executeNonCancelableSection {
                ApplicationManager.getApplication().invokeAndWait({
                    if (document.modificationStamp != expectedStamp) {
                        throw EffectFixAllServerException(EffectBundle.message("fix.all.in.file.document.changed"))
                    }
                    try {
                        action()
                        PsiDocumentManager.getInstance(project).commitDocument(document)
                    } finally {
                        // An applier can fail after writing; allow the caller to restore that partial pass.
                        expectedStamp = document.modificationStamp
                    }
                }, ModalityState.defaultModalityState())
            }
        }
    }

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

    /**
     * The platform's synchronous request helper reports timeouts, server errors, and a stopped server
     * as a `null` response instead of throwing, so `null` is turned into a failure here rather than
     * being mistaken for "no diagnostics" / "no fixes".
     */
    private fun pullDiagnostics(server: LspClient, documentId: TextDocumentIdentifier): List<Diagnostic> {
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
        server: LspClient,
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

    private fun findRunningServer(file: VirtualFile): LspClient? =
        LspClientManager.getInstance(project)
            .getClients(EffectLspServerSupportProvider::class.java)
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

/**
 * Leaves out the diagnostics the annotator never shows because no rewrite can satisfy them (see
 * [isUnsatisfiableNewSchemaClass]), so "Fix all" repairs exactly the sites the editor marks. Call under
 * read access with [text] being the document text the diagnostics were pulled for.
 */
internal fun withoutUnsatisfiableDiagnostics(diagnostics: List<Diagnostic>, psiFile: PsiFile, text: String): List<Diagnostic> {
    if (diagnostics.none { it.effectDiagnosticRuleName() == NEW_SCHEMA_CLASS_RULE }) {
        return diagnostics
    }
    val lineIndex = EffectTextLineIndex(text)
    return diagnostics.filterNot { diagnostic ->
        val range = diagnostic.range?.let(lineIndex::rangeOf)
        range != null && diagnostic.isUnsatisfiableNewSchemaClass(psiFile, range)
    }
}
