package dev.effect.intellij.lsp.fixall

import com.intellij.openapi.util.TextRange
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CreateFile
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ResourceOperation
import org.eclipse.lsp4j.TextDocumentEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectFixAllPlannerTest {
    private val uri = "file:///workspace/src/index.ts"
    private val fixTitle = "Replace yield* Effect.fail with yield*"
    private val rule = "unnecessaryFailYieldableError"

    private val source = """
        import { Effect } from "effect"

        export const a = Effect.gen(function* () {
          return yield* Effect.fail(new Boom())
        })
        export const b = Effect.gen(function* () {
          return yield* Effect.fail(new Boom())
        })
        export const c = Effect.gen(function* () {
          return yield* Effect.fail(new Boom())
        })
    """.trimIndent()

    private val lineIndex = EffectTextLineIndex(source)

    @Test
    fun selectsDiagnosticsOfTheRequestedRuleInDocumentOrder() {
        val later = diagnostic(line = 9, rule = rule)
        val earlier = diagnostic(line = 3, rule = rule)
        val otherRule = diagnostic(line = 6, rule = "floatingEffect")
        val duplicate = diagnostic(line = 3, rule = rule)

        val selected = selectFixAllDiagnostics(listOf(later, otherRule, earlier, duplicate), source, "rule:$rule")

        assertEquals(listOf(3, 9), selected.map { it.range.start.line })
    }

    @Test
    fun selectionHonoursDirectivesThatSilenceAProblem() {
        val silenced = """
            import { Effect } from "effect"

            // @effect-diagnostics-next-line unnecessaryFailYieldableError:off
            export const a = yield* Effect.fail(new Boom())
            export const b = yield* Effect.fail(new Boom())
        """.trimIndent()

        val selected = selectFixAllDiagnostics(
            listOf(diagnostic(line = 3, rule = rule), diagnostic(line = 4, rule = rule)),
            silenced,
            "rule:$rule",
        )

        assertEquals(listOf(4), selected.map { it.range.start.line })
    }

    @Test
    fun selectionHonoursSkipFileDirectives() {
        val skipped = "/** @effect-diagnostics $rule:skip-file */\nexport const a = yield* Effect.fail(new Boom())\n"

        assertTrue(selectFixAllDiagnostics(listOf(diagnostic(line = 1, rule = rule)), skipped, "rule:$rule").isEmpty())
    }

    @Test
    fun selectionFallsBackToTheEffectCodeWhenTheMessageHasNoRuleSuffix() {
        val codeOnly = Diagnostic(range(2, 0, 2, 5), "Effect diagnostic without a rule suffix").also { it.code = Either.forRight(377321) }
        val otherCode = Diagnostic(range(3, 0, 3, 5), "Another one").also { it.code = Either.forRight(377322) }

        val selected = selectFixAllDiagnostics(listOf(codeOnly, otherCode), source, "code:377321")

        assertEquals(listOf(codeOnly), selected)
    }

    @Test
    fun plansOneEditPerDiagnosticWhenTitlesMatchExactly() {
        val diagnostics = listOf(diagnostic(line = 3), diagnostic(line = 6), diagnostic(line = 9))
        val actions = diagnostics.flatMap { diagnostic ->
            listOf(
                codemod(fixTitle, diagnostic, edit(diagnostic.range.start.line, 9, 32, "yield* new Boom()")),
                codemod("Disable $rule for this line", diagnostic, edit(diagnostic.range.start.line, 0, 0, "// @effect-diagnostics-next-line\n")),
                codemod("Disable $rule for entire file", diagnostic, edit(0, 0, 0, "/** @effect-diagnostics $rule:off */\n")),
            )
        }

        val plan = planFixAll(request(diagnostics.first()), uri, diagnostics, actions, lineIndex::rangeOf)

        assertEquals(3, plan.fixedDiagnostics)
        assertEquals(0, plan.skippedDiagnostics)
        assertEquals(listOf("yield* new Boom()", "yield* new Boom()", "yield* new Boom()"), plan.edits.map { it.newText })
    }

    @Test
    fun fallsBackToTheOnlyCodemodWhenTitlesEmbedSiteSpecificNames() {
        val first = diagnostic(line = 3, rule = "redundantSchemaTagIdentifier")
        val second = diagnostic(line = 6, rule = "redundantSchemaTagIdentifier")
        val actions = listOf(
            codemod("Remove redundant identifier 'a'", first, edit(3, 2, 10, "")),
            codemod("Disable redundantSchemaTagIdentifier for this line", first, edit(3, 0, 0, "//")),
            codemod("Remove redundant identifier 'b'", second, edit(6, 2, 10, "")),
            codemod("Disable redundantSchemaTagIdentifier for entire file", second, edit(0, 0, 0, "//")),
        )

        val plan = planFixAll(
            request(first, fixTitle = "Remove redundant identifier 'a'", rule = "redundantSchemaTagIdentifier"),
            uri,
            listOf(first, second),
            actions,
            lineIndex::rangeOf,
        )

        assertEquals(2, plan.fixedDiagnostics)
        assertEquals(0, plan.unmatchedDiagnostics)
    }

    @Test
    fun matchesTitlesThatDifferOnlyInsideQuotes() {
        val first = diagnostic(line = 3, rule = "effectFnOpportunity")
        val second = diagnostic(line = 6, rule = "effectFnOpportunity")
        val actions = listOf(
            codemod("Convert to Effect.fn(\"first\")", first, edit(3, 2, 10, "fn-first")),
            codemod("Convert to Effect.fn (no span)", first, edit(3, 2, 10, "no-span")),
            codemod("Convert to Effect.fn(\"second\")", second, edit(6, 2, 10, "fn-second")),
            codemod("Convert to Effect.fn (no span)", second, edit(6, 2, 10, "no-span")),
        )

        val plan = planFixAll(
            request(first, fixTitle = "Convert to Effect.fn(\"first\")", rule = "effectFnOpportunity"),
            uri,
            listOf(first, second),
            actions,
            lineIndex::rangeOf,
        )

        assertEquals(2, plan.fixedDiagnostics)
        assertEquals(listOf("fn-first", "fn-second"), plan.edits.map { it.newText })
    }

    @Test
    fun neverAppliesADifferentRewriteVariantOfTheSameRule() {
        val catchAllSite = diagnostic(line = 3, rule = "missingEffectError")
        val taggedSite = diagnostic(line = 6, rule = "missingEffectError")
        val actions = listOf(
            codemod("Catch all errors with Effect.catch/catchAll", catchAllSite, edit(3, 2, 10, "catch")),
            codemod("Catch unexpected errors with Effect.catchTag", taggedSite, edit(6, 2, 10, "catchTag")),
        )

        val plan = planFixAll(
            request(catchAllSite, fixTitle = "Catch all errors with Effect.catch/catchAll", rule = "missingEffectError"),
            uri,
            listOf(catchAllSite, taggedSite),
            actions,
            lineIndex::rangeOf,
        )

        assertEquals(1, plan.fixedDiagnostics)
        assertEquals(1, plan.unmatchedDiagnostics)
        assertEquals(listOf("catch"), plan.edits.map { it.newText })
    }

    @Test
    fun titleShapeBlanksOnlyQuotedSegments() {
        assertEquals(fixTitleShape("Remove redundant identifier 'a'"), fixTitleShape("Remove redundant identifier 'b'"))
        assertEquals(fixTitleShape("Convert to Effect.fn(\"x\")"), fixTitleShape(" Convert to Effect.fn(\"longer name\") "))
        assertFalse(fixTitleShape("Replace with Effect.ignore") == fixTitleShape("Replace with Effect.ignoreCause"))
        assertFalse(fixTitleShape("Replace with 'typeof A.Type'") == fixTitleShape("Replace with typeof X.Type"))
    }

    @Test
    fun mergesADeleteAndAnInsertionAtTheSameOffsetInsideOneFix() {
        // unnecessaryArrowBlock / multipleEffectProvide emit [insert@P, delete P..E] as two edits.
        val first = diagnostic(line = 3, rule = "unnecessaryArrowBlock")
        val second = diagnostic(line = 6, rule = "unnecessaryArrowBlock")
        val actions = listOf(
            codemod("Use a concise arrow body", first, edit(3, 2, 2, "(x)"), edit(3, 2, 20, "")),
            codemod("Use a concise arrow body", second, edit(6, 2, 20, ""), edit(6, 2, 2, "(y)")),
        )

        val plan = planFixAll(
            request(first, fixTitle = "Use a concise arrow body", rule = "unnecessaryArrowBlock"),
            uri,
            listOf(first, second),
            actions,
            lineIndex::rangeOf,
        )

        assertEquals(2, plan.fixedDiagnostics)
        assertEquals(0, plan.skippedDiagnostics)
        assertEquals(2, plan.edits.size)
        assertEquals(listOf("(x)", "(y)"), plan.edits.map { it.newText })
        assertEquals(listOf(range(3, 2, 3, 20), range(6, 2, 6, 20)), plan.edits.map { it.range })
    }

    @Test
    fun mergesSeveralInsertionsAtOneOffsetInServerOrder() {
        // layerMergeAllWithDependencies: delete the argument, then insert ".pipe(Layer.provideMerge(x)"
        // and ")" separately at the call's end.
        val first = diagnostic(line = 3, rule = "layerMergeAllWithDependencies")
        val actions = listOf(
            codemod(
                "Move layer to Layer.provideMerge",
                first,
                edit(3, 4, 9, ""),
                edit(3, 20, 20, ".pipe(Layer.provideMerge(x)"),
                edit(3, 20, 20, ")"),
            ),
        )

        val plan = planFixAll(
            request(first, fixTitle = "Move layer to Layer.provideMerge", rule = "layerMergeAllWithDependencies"),
            uri,
            listOf(first),
            actions,
            lineIndex::rangeOf,
        )

        assertEquals(1, plan.fixedDiagnostics)
        assertEquals(listOf("" to range(3, 4, 3, 9), ".pipe(Layer.provideMerge(x))" to range(3, 20, 3, 20)), plan.edits.map { it.newText to it.range })
    }

    @Test
    fun rejectsMalformedEditSetsInsideOneFix() {
        val twoReplacements = diagnostic(line = 3)
        val overlappingPair = diagnostic(line = 6)
        val actions = listOf(
            codemod(fixTitle, twoReplacements, edit(3, 2, 8, "a"), edit(3, 2, 10, "b")),
            codemod(fixTitle, overlappingPair, edit(6, 2, 10, "a"), edit(6, 5, 12, "b")),
        )

        val plan = planFixAll(request(twoReplacements), uri, listOf(twoReplacements, overlappingPair), actions, lineIndex::rangeOf)

        assertEquals(0, plan.fixedDiagnostics)
        assertEquals(2, plan.unsupportedDiagnostics)
    }

    @Test
    fun ambiguousShapeMatchesAreSkippedInsteadOfGuessed() {
        // effectFn with both inferred-span and suggested-span enabled offers two same-shape titles per site.
        val origin = diagnostic(line = 3, rule = "effectFnOpportunity")
        val other = diagnostic(line = 6, rule = "effectFnOpportunity")
        val actions = listOf(
            codemod("Convert to Effect.fn(\"Users.load\")", origin, edit(3, 2, 10, "inferred-origin")),
            codemod("Convert to Effect.fn(\"load\")", origin, edit(3, 2, 10, "suggested-origin")),
            codemod("Convert to Effect.fn(\"Users.save\")", other, edit(6, 2, 10, "inferred-other")),
            codemod("Convert to Effect.fn(\"save\")", other, edit(6, 2, 10, "suggested-other")),
        )

        val plan = planFixAll(
            request(origin, fixTitle = "Convert to Effect.fn(\"load\")", rule = "effectFnOpportunity"),
            uri,
            listOf(origin, other),
            actions,
            lineIndex::rangeOf,
        )

        assertEquals(1, plan.fixedDiagnostics)
        assertEquals(1, plan.unmatchedDiagnostics)
        assertEquals(listOf("suggested-origin"), plan.edits.map { it.newText })
    }

    @Test
    fun leavesOverlappingFixesForALaterRun() {
        val outer = diagnostic(line = 3, startCharacter = 2, endLine = 4, endCharacter = 2)
        val inner = diagnostic(line = 3, startCharacter = 9, endLine = 3, endCharacter = 30)
        val actions = listOf(
            codemod(fixTitle, outer, edit(3, 2, 38, "rewritten outer")),
            codemod(fixTitle, inner, edit(3, 9, 30, "rewritten inner")),
        )

        val plan = planFixAll(request(outer), uri, listOf(outer, inner), actions, lineIndex::rangeOf)

        assertEquals(1, plan.fixedDiagnostics)
        assertEquals(1, plan.overlappingDiagnostics)
        assertEquals(listOf("rewritten outer"), plan.edits.map { it.newText })
    }

    @Test
    fun treatsEditsOfDifferentFixesAnchoredAtTheSameOffsetAsAConflict() {
        val first = diagnostic(line = 3)
        val second = diagnostic(line = 6)
        val actions = listOf(
            codemod(fixTitle, first, edit(0, 0, 0, "import { Boom } from \"./boom\"\n")),
            codemod(fixTitle, second, edit(0, 0, 0, "import { Boom } from \"./boom\"\n")),
        )

        val plan = planFixAll(request(first), uri, listOf(first, second), actions, lineIndex::rangeOf)

        assertEquals(1, plan.fixedDiagnostics)
        assertEquals(1, plan.overlappingDiagnostics)
    }

    @Test
    fun adjacentEditsDoNotConflict() {
        val first = diagnostic(line = 3)
        val second = diagnostic(line = 6)
        val actions = listOf(
            codemod(fixTitle, first, edit(3, 2, 9, "a")),
            codemod(fixTitle, second, edit(3, 9, 12, "b")),
        )

        val plan = planFixAll(request(first), uri, listOf(first, second), actions, lineIndex::rangeOf)

        assertEquals(2, plan.fixedDiagnostics)
    }

    @Test
    fun skipsFixesThatTouchOtherDocumentsOrCreateFiles() {
        val first = diagnostic(line = 3)
        val second = diagnostic(line = 6)
        val third = diagnostic(line = 9)
        val foreign = codemod(fixTitle, second, edit(6, 2, 9, "x")).also { action ->
            action.edit = WorkspaceEdit(mapOf("file:///workspace/src/other.ts" to listOf(edit(0, 0, 0, "x"))))
        }
        val resourceOperation = codemod(fixTitle, third, edit(9, 2, 9, "x")).also { action ->
            action.edit = WorkspaceEdit(listOf(Either.forRight<TextDocumentEdit, ResourceOperation>(CreateFile("file:///workspace/src/new.ts"))))
        }
        val actions = listOf(codemod(fixTitle, first, edit(3, 2, 9, "ok")), foreign, resourceOperation)

        val plan = planFixAll(request(first), uri, listOf(first, second, third), actions, lineIndex::rangeOf)

        assertEquals(1, plan.fixedDiagnostics)
        assertEquals(2, plan.unsupportedDiagnostics)
    }

    @Test
    fun readsEditsFromTheDocumentChangesShape() {
        val first = diagnostic(line = 3)
        val action = CodeAction(fixTitle).also { action ->
            action.kind = CodeActionKind.QuickFix
            action.diagnostics = listOf(first)
            action.edit = WorkspaceEdit(
                listOf(
                    Either.forLeft<TextDocumentEdit, ResourceOperation>(
                        TextDocumentEdit(VersionedTextDocumentIdentifier(uri, 1), listOf(edit(3, 2, 9, "ok"))),
                    ),
                ),
            )
        }

        val plan = planFixAll(request(first), uri, listOf(first), listOf(action), lineIndex::rangeOf)

        assertEquals(1, plan.fixedDiagnostics)
        assertEquals(listOf("ok"), plan.edits.map { it.newText })
    }

    @Test
    fun prefersDocumentChangesWhenAServerSendsBothShapes() {
        val first = diagnostic(line = 3)
        val action = codemod(fixTitle, first, edit(3, 2, 9, "from-changes")).also { action ->
            action.edit = WorkspaceEdit(mapOf(uri to listOf(edit(3, 2, 9, "from-changes")))).also { workspaceEdit ->
                workspaceEdit.documentChanges = listOf(
                    Either.forLeft<TextDocumentEdit, ResourceOperation>(
                        TextDocumentEdit(VersionedTextDocumentIdentifier(uri, 1), listOf(edit(3, 2, 9, "from-document-changes"))),
                    ),
                )
            }
        }

        val plan = planFixAll(request(first), uri, listOf(first), listOf(action), lineIndex::rangeOf)

        assertEquals(1, plan.fixedDiagnostics)
        assertEquals(listOf("from-document-changes"), plan.edits.map { it.newText })
    }

    @Test
    fun ignoresRefactorKindsAndOutOfRangeEdits() {
        val first = diagnostic(line = 3)
        val second = diagnostic(line = 6)
        val refactor = codemod(fixTitle, first, edit(3, 2, 9, "x")).also { it.kind = CodeActionKind.RefactorRewrite }
        val outOfRange = codemod(fixTitle, second, edit(60, 0, 1, "x"))

        val plan = planFixAll(request(first), uri, listOf(first, second), listOf(refactor, outOfRange), lineIndex::rangeOf)

        assertEquals(0, plan.fixedDiagnostics)
        assertEquals(1, plan.unmatchedDiagnostics)
        assertEquals(1, plan.unsupportedDiagnostics)
    }

    @Test
    fun lineIndexMapsPositionsLikeTheLspDocumentMapping() {
        val index = EffectTextLineIndex("ab\ncd\n")

        assertEquals(TextRange(0, 2), index.rangeOf(range(0, 0, 0, 2)))
        assertEquals(TextRange(3, 5), index.rangeOf(range(1, 0, 1, 2)))
        assertEquals(TextRange(6, 6), index.rangeOf(range(2, 0, 2, 0)))
        assertNull(index.rangeOf(range(0, 0, 0, 3)))
        assertNull(index.rangeOf(range(3, 0, 3, 0)))
        assertNull(index.rangeOf(range(1, 2, 1, 0)))
    }

    @Test
    fun requestIsOnlyBuiltForEffectCodemodFixes() {
        val effect = diagnostic(line = 3)
        val plainTypeScript = Diagnostic(range(3, 0, 3, 5), "Cannot find name 'x'.").also { it.code = Either.forRight(2304) }

        assertNull(effectFixAllRequest(effect, "Disable $rule for this line"))
        assertNull(effectFixAllRequest(effect, "Disable $rule for entire file"))
        assertNull(effectFixAllRequest(effect, ""))
        assertNull(effectFixAllRequest(effect, null))
        assertNull(effectFixAllRequest(plainTypeScript, "Add import from \"./x\""))

        val request = effectFixAllRequest(effect, " $fixTitle ")!!
        assertEquals("rule:$rule", request.ruleKey)
        assertEquals(rule, request.ruleDisplayName)
        assertEquals(fixTitle, request.fixTitle)
        assertFalse(isEffectCodemodFixTitle("Disable floatingEffect for entire file"))
        assertTrue(isEffectCodemodFixTitle("Disable the thing"))
    }

    private fun request(
        @Suppress("UNUSED_PARAMETER") trigger: Diagnostic,
        fixTitle: String = this.fixTitle,
        rule: String = this.rule,
    ): EffectFixAllRequest = EffectFixAllRequest("rule:$rule", rule, fixTitle)

    private fun diagnostic(
        line: Int,
        rule: String = this.rule,
        startCharacter: Int = 9,
        endLine: Int = line,
        endCharacter: Int = 32,
        code: Int = 377100,
    ): Diagnostic =
        Diagnostic(range(line, startCharacter, endLine, endCharacter), "Effect problem. effect($rule)").also { diagnostic ->
            diagnostic.code = Either.forRight(code)
            diagnostic.source = "effect"
        }

    private fun codemod(title: String, diagnostic: Diagnostic, vararg edits: TextEdit): CodeAction =
        CodeAction(title).also { action ->
            action.kind = CodeActionKind.QuickFix
            action.diagnostics = listOf(diagnostic)
            action.edit = WorkspaceEdit(mapOf(uri to edits.toList()))
        }

    private fun edit(line: Int, start: Int, end: Int, newText: String): TextEdit =
        TextEdit(range(line, start, line, end), newText)

    private fun range(startLine: Int, startCharacter: Int, endLine: Int, endCharacter: Int): Range =
        Range(Position(startLine, startCharacter), Position(endLine, endCharacter))
}
