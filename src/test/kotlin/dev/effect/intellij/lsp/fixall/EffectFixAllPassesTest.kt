package dev.effect.intellij.lsp.fixall

import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EffectFixAllPassesTest {
    private val request = EffectFixAllRequest("rule:bRule", "bRule", "Fix it")

    @Test
    fun fixesEverySiteInOnePass() {
        val session = FakeSession("B B B") { "ok" }

        val result = runFixAllPasses(request, URI, session)

        assertEquals("ok ok ok", session.text)
        assertEquals(EffectFixAllRunResult(3, 3, 0, 0, null, 1), result)
    }

    @Test
    fun repeatsUntilRewritesStopExposingNewSites() {
        // `B2` rewrites to `B1`, `B1` to `B`, `B` to `ok`: a nested chain only one level of which is
        // fixable per pass, like `unnecessaryPipeChain` after its parent is flattened.
        val session = FakeSession("B2 B") { site -> site.removeSuffix("1").removeSuffix("2").let { if (site == "B") "ok" else if (site == "B2") "B1" else it } }

        val result = runFixAllPasses(request, URI, session)

        assertEquals("ok ok", session.text)
        assertEquals(4, result.appliedFixes)
        assertEquals(0, result.remainingDiagnostics)
        assertEquals(3, result.passes)
    }

    @Test
    fun retriesOneAtATimeWhenAMergedBatchCollidesAndSkipsTheFixThatStillCollides() {
        // Each fix alone hoists `dup`; two of them in one scope declare it twice (`yieldInCallArgument`).
        val session = FakeSession("B B") { "dup" }

        val result = runFixAllPasses(request, URI, session)

        assertEquals("dup B", session.text)
        assertEquals(1, result.appliedFixes)
        assertEquals(1, result.rejectedFixes)
        assertEquals(1, result.remainingDiagnostics)
        assertEquals(2, session.restores.size)
        assertNull(result.lastEmptyPlan)
    }

    @Test
    fun leavesTheFileUnchangedWhenEveryFixIntroducesAnError() {
        val session = FakeSession("B B") { "broken" }

        val result = runFixAllPasses(request, URI, session)

        assertEquals("B B", session.text)
        assertEquals(0, result.appliedFixes)
        assertEquals(2, result.rejectedFixes)
        assertEquals(2, result.remainingDiagnostics)
    }

    @Test
    fun keepsPreExistingErrorsOutOfTheComparison() {
        val session = FakeSession("broken B") { "ok" }

        val result = runFixAllPasses(request, URI, session)

        assertEquals("broken ok", session.text)
        assertEquals(1, result.appliedFixes)
        assertEquals(0, result.rejectedFixes)
    }

    @Test
    fun reportsThePlanWhenNoSiteOffersTheChosenFix() {
        val session = FakeSession("B B", title = "Another fix") { "ok" }

        val result = runFixAllPasses(request, URI, session)

        assertEquals("B B", session.text)
        assertEquals(0, result.appliedFixes)
        assertNotNull(result.lastEmptyPlan)
        assertEquals(2, result.lastEmptyPlan!!.unmatchedDiagnostics)
    }

    @Test
    fun reportsNothingWhenTheRuleHasNoDiagnostics() {
        val session = FakeSession("ok") { "ok" }

        assertEquals(EffectFixAllRunResult(0, 0, 0, 0, null, 0), runFixAllPasses(request, URI, session))
    }

    /**
     * A one-line document. `B`, `B1`, `B2` … are `bRule` sites whose fix replaces the token with
     * [rewrite]; `broken` and every `dup` after the first are TypeScript errors.
     */
    private class FakeSession(
        var text: String,
        private val title: String = "Fix it",
        private val rewrite: (String) -> String,
    ) : EffectFixAllSession {
        val restores = mutableListOf<String>()

        override fun currentText(): String = text

        override fun pullDiagnostics(): List<Diagnostic> {
            val sites = Regex("""\bB\d*\b""").findAll(text).map { match ->
                Diagnostic(range(match.range), "Simplify this. effect(bRule)").also {
                    it.code = Either.forRight(377100)
                    it.severity = DiagnosticSeverity.Warning
                }
            }
            val broken = Regex("""\bbroken\b""").findAll(text).map { match ->
                Diagnostic(range(match.range), "Cannot find name 'broken'.").also {
                    it.code = Either.forRight(2304)
                    it.severity = DiagnosticSeverity.Error
                }
            }
            val duplicates = Regex("""\bdup\b""").findAll(text).drop(1).map { match ->
                Diagnostic(range(match.range), "Cannot redeclare block-scoped variable 'dup'.").also {
                    it.code = Either.forRight(2451)
                    it.severity = DiagnosticSeverity.Error
                }
            }
            return (sites + broken + duplicates).toList()
        }

        override fun requestQuickFixes(diagnostics: List<Diagnostic>): List<CodeAction> =
            diagnostics.map { diagnostic ->
                val start = diagnostic.range.start.character
                val site = text.substring(start, diagnostic.range.end.character)
                CodeAction(title).also { action ->
                    action.kind = CodeActionKind.QuickFix
                    action.diagnostics = listOf(diagnostic)
                    action.edit = WorkspaceEdit(mapOf(URI to listOf(TextEdit(diagnostic.range, rewrite(site)))))
                }
            }

        override fun apply(edits: List<TextEdit>) {
            var result = text
            for (edit in edits.sortedByDescending { it.range.start.character }) {
                result = result.substring(0, edit.range.start.character) + edit.newText + result.substring(edit.range.end.character)
            }
            text = result
        }

        override fun restore(text: String) {
            restores += text
            this.text = text
        }

        private fun range(offsets: IntRange) = Range(Position(0, offsets.first), Position(0, offsets.last + 1))
    }

    private companion object {
        const val URI = "file:///workspace/src/index.ts"
    }
}
