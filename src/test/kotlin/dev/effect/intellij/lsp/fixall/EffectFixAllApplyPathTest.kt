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
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
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

    private class FakeDescriptor(
        project: Project,
        private val fakeUri: String,
        private val file: VirtualFile,
    ) : ProjectWideLspServerDescriptor(project, "Fake Effect") {
        override fun isSupportedFile(file: VirtualFile): Boolean = true

        override fun createCommandLine(): GeneralCommandLine = GeneralCommandLine("true")

        override fun getFileUri(file: VirtualFile): String = if (file == this.file) fakeUri else super.getFileUri(file)

        override fun findFileByUri(uri: String): VirtualFile? = if (uri == fakeUri) file else super.findFileByUri(uri)
    }

    private class FakeLspClient(
        override val project: Project,
        override val descriptor: LspClientDescriptor,
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
        ): Lsp4jResponse? = null

        override fun getDocumentIdentifier(file: VirtualFile): TextDocumentIdentifier =
            TextDocumentIdentifier(descriptor.getFileUri(file))

        override fun getDocumentVersion(document: Document): Int = 0
    }
}
