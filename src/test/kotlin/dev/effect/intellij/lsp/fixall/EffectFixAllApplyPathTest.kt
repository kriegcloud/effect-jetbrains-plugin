package dev.effect.intellij.lsp.fixall

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientDescriptor
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import com.intellij.platform.lsp.api.customization.LspIntentionAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.effect.intellij.lsp.EffectLspServerSupportProvider
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture

/**
 * Pins the platform contract the fix-all service relies on to write its merged edits: a synthetic
 * `CodeAction` handed to `LspIntentionAction` is applied by `invoke` only after `isAvailable` resolved
 * the URI-to-document map. Skipping `isAvailable` silently applies nothing on the 262 line.
 */
class EffectFixAllApplyPathTest : BasePlatformTestCase() {
    fun testMergedEditsAreAppliedOnlyAfterIsAvailableResolvedTheDocument() {
        val original = "const a = yield Effect.succeed(1)\nconst b = yield Effect.succeed(2)\n"
        val psiFile = myFixture.configureByText("index.ts", original)
        val virtualFile = psiFile.virtualFile
        val uri = "file:///fake/index.ts"
        val client = FakeLspClient(project, FakeDescriptor(project, uri, virtualFile))
        val action = CodeAction("Fix all 'Replace yield with yield*' problems in file").also { codeAction ->
            codeAction.kind = CodeActionKind.QuickFix
            codeAction.edit = WorkspaceEdit(
                mapOf(
                    uri to listOf(
                        TextEdit(Range(Position(0, 10), Position(0, 15)), "yield*"),
                        TextEdit(Range(Position(1, 10), Position(1, 15)), "yield*"),
                    ),
                ),
            )
        }
        val applier = LspIntentionAction(client, action)

        CommandProcessor.getInstance().executeCommand(project, { applier.invoke(project, myFixture.editor, psiFile) }, "fix all", null)
        assertEquals("invoke before isAvailable must stay a no-op on this platform line", original, myFixture.editor.document.text)

        assertTrue(applier.isAvailable(project, myFixture.editor, psiFile))
        CommandProcessor.getInstance().executeCommand(project, { applier.invoke(project, myFixture.editor, psiFile) }, "fix all", null)

        assertEquals("const a = yield* Effect.succeed(1)\nconst b = yield* Effect.succeed(2)\n", myFixture.editor.document.text)
    }

    fun testProductionSessionGroupsRetainedPassesAndRollbackIntoOneUndoStep() {
        val original = "const value = 0\n"
        val file = myFixture.configureByText("index.ts", original)
        val uri = "file:///fake/index.ts"
        val request = EffectFixAllRequest("rule:test", "test", "Fix it")
        val diagnostic = org.eclipse.lsp4j.Diagnostic().also {
            it.range = Range(Position(0, 14), Position(0, 15))
            it.message = "Fix value. effect(test)"
        }
        val error = org.eclipse.lsp4j.Diagnostic().also {
            it.range = diagnostic.range
            it.message = "New error"
            it.severity = org.eclipse.lsp4j.DiagnosticSeverity.Error
        }
        fun report(vararg diagnostics: org.eclipse.lsp4j.Diagnostic) =
            org.eclipse.lsp4j.DocumentDiagnosticReport(org.eclipse.lsp4j.RelatedFullDocumentDiagnosticReport(diagnostics.toList()))
        fun fix(value: String) = listOf(org.eclipse.lsp4j.jsonrpc.messages.Either.forRight<org.eclipse.lsp4j.Command, CodeAction>(
            CodeAction(request.fixTitle).also {
                it.diagnostics = listOf(diagnostic)
                it.edit = WorkspaceEdit(mapOf(uri to listOf(TextEdit(diagnostic.range, value))))
            },
        ))
        val responses = ArrayDeque<Any>(listOf(
            report(diagnostic), fix("1"), report(diagnostic), fix("2"), report(diagnostic),
            fix("3"), report(diagnostic, error), fix("3"), report(diagnostic, error),
        ))
        val client = FakeLspClient(project, FakeDescriptor(project, uri, file.virtualFile)) { responses.removeFirst() }
        val session = EffectFixAllInFileService(project).EditorFixAllSession(
            client, TextDocumentIdentifier(uri), myFixture.editor, file, request,
        )
        val future = com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(
            java.util.concurrent.Callable { runFixAllPasses(request, uri, session) },
        )
        val result = com.intellij.testFramework.PlatformTestUtil.waitForFuture(future, 10_000)
        assertEquals(2, result.appliedFixes)
        assertEquals(1, result.rejectedFixes)
        assertTrue(responses.isEmpty())
        val retained = session.currentText()
        assertEquals("const value = 2\n", session.currentText())
        val undo = com.intellij.openapi.command.undo.UndoManager.getInstance(project)
        val editor = com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        assertTrue(undo.isUndoAvailable(editor))
        undo.undo(editor)
        assertEquals(original, session.currentText())
        assertTrue(undo.isRedoAvailable(editor))
        undo.redo(editor)
        assertEquals(retained, session.currentText())
    }

    fun testProductionSessionRefusesStaleApplyAndRestore() {
        val file = myFixture.configureByText("index.ts", "const value = 0\n")
        val uri = "file:///fake/index.ts"
        val client = FakeLspClient(project, FakeDescriptor(project, uri, file.virtualFile))
        val session = EffectFixAllInFileService(project).EditorFixAllSession(
            client, TextDocumentIdentifier(uri), myFixture.editor, file,
            EffectFixAllRequest("rule:test", "test", "Fix it"),
        )
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.setText("user text")
        }
        listOf<() -> Unit>(
            { session.apply(listOf(TextEdit(Range(Position(0, 0), Position(0, 1)), "x"))) },
            { session.restore("stale text") },
        ).forEach { write ->
            try {
                write()
                fail("Expected stale snapshot refusal")
            } catch (_: EffectFixAllServerException) {
                assertEquals("user text", session.currentText())
            }
        }
    }

    fun testProductionSessionFailsClosedOnMissingResponsesAndCancellation() {
        val file = myFixture.configureByText("index.ts", "const value = 0\n")
        val uri = "file:///fake/index.ts"
        var cancel = false
        val client = FakeLspClient(project, FakeDescriptor(project, uri, file.virtualFile)) {
            if (cancel) throw com.intellij.openapi.progress.ProcessCanceledException()
            null
        }
        val session = EffectFixAllInFileService(project).EditorFixAllSession(
            client, TextDocumentIdentifier(uri), myFixture.editor, file,
            EffectFixAllRequest("rule:test", "test", "Fix it"),
        )
        listOf<() -> Unit>({ session.pullDiagnostics() }, { session.requestQuickFixes(emptyList()) }).forEach { request ->
            try {
                request()
                fail("Expected failed response")
            } catch (_: EffectFixAllServerException) {
                assertEquals("const value = 0\n", session.currentText())
            }
        }
        cancel = true
        try {
            session.pullDiagnostics()
            fail("Expected cancellation")
        } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
            assertEquals("const value = 0\n", session.currentText())
        }
    }

    fun testCancelledValidationRestoresOriginalAfterDelayedEdtDispatch() = checkCancelledRewrite(0, false)

    fun testCancelledValidationRetainsEarlierPassesInOneUndoStep() = checkCancelledRewrite(2, false)

    fun testCancellationImmediatelyAfterApplyRestoresValidatedSnapshot() = checkCancelledRewrite(2, true)

    private fun checkCancelledRewrite(retainedPasses: Int, cancelAfterApply: Boolean) {
        val original = "const value = 0\n"
        val file = myFixture.configureByText("index.ts", original)
        val uri = "file:///fake/index.ts"
        val request = EffectFixAllRequest("rule:test", "test", "Fix it")
        val diagnostic = org.eclipse.lsp4j.Diagnostic().also {
            it.range = Range(Position(0, 14), Position(0, 15))
            it.message = "Fix value. effect(test)"
        }
        val indicator = com.intellij.openapi.progress.EmptyProgressIndicator()
        val restoring = java.util.concurrent.CountDownLatch(1)
        var pulls = 0
        var fixes = 0
        fun cancelWithDelayedEdt() {
            // Queue an EDT blocker ahead of rollback, then keep it blocked beyond invokeAndWait's
            // cancellation polling interval. The rollback must survive this cancelled indicator.
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                check(restoring.await(5, java.util.concurrent.TimeUnit.SECONDS))
                Thread.sleep(80)
            }
            indicator.cancel()
        }
        val client = FakeLspClient(project, FakeDescriptor(project, uri, file.virtualFile)) {
            // Requests alternate between diagnostics and code actions.
            if (pulls == fixes) {
                pulls++
                if (fixes > retainedPasses) {
                    cancelWithDelayedEdt()
                    com.intellij.openapi.progress.ProgressManager.checkCanceled()
                }
                org.eclipse.lsp4j.DocumentDiagnosticReport(
                    org.eclipse.lsp4j.RelatedFullDocumentDiagnosticReport(listOf(diagnostic)),
                )
            } else {
                fixes++
                listOf(org.eclipse.lsp4j.jsonrpc.messages.Either.forRight<org.eclipse.lsp4j.Command, CodeAction>(
                    CodeAction(request.fixTitle).also {
                        it.diagnostics = listOf(diagnostic)
                        it.edit = WorkspaceEdit(mapOf(uri to listOf(TextEdit(diagnostic.range, fixes.toString()))))
                    },
                ))
            }
        }
        val production = EffectFixAllInFileService(project).EditorFixAllSession(
            client, TextDocumentIdentifier(uri), myFixture.editor, file, request,
        )
        val session = object : EffectFixAllSession by production {
            override fun apply(edits: List<TextEdit>) {
                production.apply(edits)
                if (cancelAfterApply && fixes > retainedPasses) cancelWithDelayedEdt()
            }

            override fun restore(text: String) {
                restoring.countDown()
                production.restore(text)
            }
        }
        val future = com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(
            java.util.concurrent.Callable {
                var cancelled = false
                com.intellij.openapi.progress.ProgressManager.getInstance().runProcess({
                    try {
                        runFixAllPasses(request, uri, session)
                    } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
                        cancelled = true
                    }
                }, indicator)
                cancelled
            },
        )
        assertTrue("Cancellation must propagate after rollback", com.intellij.testFramework.PlatformTestUtil.waitForFuture(future, 10_000))
        assertEquals("const value = $retainedPasses\n", production.currentText())
        if (retainedPasses > 0) {
            val undo = com.intellij.openapi.command.undo.UndoManager.getInstance(project)
            val editor = com.intellij.openapi.fileEditor.impl.text.TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
            assertTrue(undo.isUndoAvailable(editor))
            undo.undo(editor)
            assertEquals(original, production.currentText())
            undo.redo(editor)
            assertEquals("const value = $retainedPasses\n", production.currentText())
        }
    }

    private class FakeDescriptor(
        project: Project,
        private val fakeUri: String,
        private val file: VirtualFile,
    ) : ProjectWideLspClientDescriptor(project, "Fake Effect") {
        override fun isSupportedFile(file: VirtualFile): Boolean = true

        override fun createCommandLine(): GeneralCommandLine = GeneralCommandLine("true")

        override fun getFileUri(file: VirtualFile): String = if (file == this.file) fakeUri else super.getFileUri(file)

        override fun findFileByUri(uri: String): VirtualFile? = if (uri == fakeUri) file else super.findFileByUri(uri)
    }

    private class FakeLspClient(
        override val project: Project,
        override val descriptor: LspClientDescriptor,
        private val response: () -> Any? = { null },
    ) : LspClient {
        override val providerClass: Class<out LspIntegrationProvider> = EffectLspServerSupportProvider::class.java

        override val state: LspServerState = LspServerState.Running

        override val initializeResult: InitializeResult? = null

        override fun sendNotification(notificationExecutor: (LanguageServer) -> Unit) = Unit

        override suspend fun <Lsp4jResponse> sendRequest(
            requestExecutor: (LanguageServer) -> CompletableFuture<Lsp4jResponse>,
        ): Lsp4jResponse = error("not used")

        override fun <Lsp4jResponse> sendRequestSync(
            timeoutMs: Int,
            requestExecutor: (LanguageServer) -> CompletableFuture<Lsp4jResponse>,
        ): Lsp4jResponse? {
            @Suppress("UNCHECKED_CAST")
            return response() as Lsp4jResponse?
        }

        override fun getDocumentIdentifier(file: VirtualFile): TextDocumentIdentifier =
            TextDocumentIdentifier(descriptor.getFileUri(file))

        override fun getDocumentVersion(document: Document): Int = 0
    }
}
