package dev.effect.intellij.lsp.fixall

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.IntentionActionDelegate
import com.intellij.codeInsight.intention.IntentionActionWithOptions
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.effect.intellij.lsp.EffectLspDiagnosticsSupport
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either

class EffectFixAllCapableQuickFixTest : BasePlatformTestCase() {
    fun testCodemodFixOffersFixAllInFileOption() {
        val fix = EffectFixAllCapableQuickFix(FakeQuickFix("Replace yield* Effect.fail with yield*"), effectDiagnostic())

        val option = fix.options.single() as EffectFixAllInFileIntention

        assertEquals("Fix all 'Replace yield* Effect.fail with yield*' problems in file", option.text)
        assertEquals("rule:unnecessaryFailYieldableError", option.request.ruleKey)
        assertEquals("Replace yield* Effect.fail with yield*", option.request.fixTitle)
        assertFalse(option.startInWriteAction())
        assertEquals(IntentionActionWithOptions.CombiningPolicy.IntentionOptionsOnly, fix.combiningPolicy)
    }

    fun testDirectiveFixesAndUnresolvedPlaceholdersOfferNoOption() {
        val diagnostic = effectDiagnostic()

        assertEmpty(EffectFixAllCapableQuickFix(FakeQuickFix("Disable unnecessaryFailYieldableError for this line"), diagnostic).options)
        assertEmpty(EffectFixAllCapableQuickFix(FakeQuickFix("Disable unnecessaryFailYieldableError for entire file"), diagnostic).options)
        assertEmpty(EffectFixAllCapableQuickFix(FakeQuickFix(""), diagnostic).options)
    }

    fun testOptionResolvesLazilyFromTheDelegateTitle() {
        val delegate = FakeQuickFix("")
        val fix = EffectFixAllCapableQuickFix(delegate, effectDiagnostic())
        assertEmpty(fix.options)

        delegate.title = "Replace with Effect.map"

        assertEquals("Fix all 'Replace with Effect.map' problems in file", fix.options.single().text)
    }

    fun testWrapperDelegatesEverythingElseToTheQuickFix() {
        val delegate = FakeQuickFix("Replace with Effect.map")
        val fix = EffectFixAllCapableQuickFix(delegate, effectDiagnostic())

        assertEquals("Replace with Effect.map", fix.text)
        assertEquals("family", fix.familyName)
        assertTrue(fix.isAvailable(project, null, null))
        assertFalse(fix.startInWriteAction())
        fix.invoke(project, null, null)
        assertEquals(1, delegate.invocations)
        // The popup unwraps IntentionActionDelegate before looking for options, so the wrapper must not be one.
        assertFalse(IntentionActionDelegate::class.java.isInstance(fix))
        assertSame(fix, IntentionActionDelegate.unwrap(fix))
        assertTrue(fix is IntentionActionWithOptions)
    }

    fun testOrderingIsSymmetricWithForeignActionsAndFollowsTheDelegateOtherwise() {
        val lower = EffectFixAllCapableQuickFix(OrderedQuickFix("a", 0), effectDiagnostic())
        val higher = EffectFixAllCapableQuickFix(OrderedQuickFix("b", 2), effectDiagnostic())
        val foreign = FakeQuickFix("plain TypeScript fix")

        assertTrue(lower.compareTo(higher) < 0)
        assertTrue(higher.compareTo(lower) > 0)
        assertEquals(0, lower.compareTo(foreign))
        assertEquals(0, EffectFixAllCapableQuickFix(FakeQuickFix("no ordering"), effectDiagnostic()).compareTo(higher))
    }

    fun testOnlyEffectDiagnosticsGetWrapped() {
        val effectFixes = listOf<IntentionAction>(FakeQuickFix("a"), FakeQuickFix("b"))
        val typeScriptFixes = listOf<IntentionAction>(FakeQuickFix("Add import"))
        val typeScriptDiagnostic = Diagnostic(range(), "Cannot find name 'x'.").also { it.code = Either.forRight(2304) }
        val effectByCodeOnly = Diagnostic(range(), "no rule suffix").also { it.code = Either.forRight(377100) }

        val wrapped = EffectLspDiagnosticsSupport.withFixAllOptions(effectDiagnostic(), effectFixes)
        assertEquals(2, wrapped.size)
        assertTrue(wrapped.all { it is EffectFixAllCapableQuickFix })
        assertEquals(listOf("a", "b"), wrapped.map { it.text })

        assertTrue(EffectLspDiagnosticsSupport.withFixAllOptions(effectByCodeOnly, effectFixes).all { it is EffectFixAllCapableQuickFix })
        assertSame(typeScriptFixes, EffectLspDiagnosticsSupport.withFixAllOptions(typeScriptDiagnostic, typeScriptFixes))
        assertEmpty(EffectLspDiagnosticsSupport.withFixAllOptions(effectDiagnostic(), emptyList()))
    }

    private fun effectDiagnostic(): Diagnostic =
        Diagnostic(range(), "Yielding a failed Effect is unnecessary. effect(unnecessaryFailYieldableError)").also { diagnostic ->
            diagnostic.code = Either.forRight(377100)
            diagnostic.source = "effect"
        }

    private fun range(): Range = Range(Position(3, 9), Position(3, 32))

    private class OrderedQuickFix(title: String, private val index: Int) : IntentionAction, Comparable<IntentionAction> {
        private val inner = FakeQuickFix(title)

        override fun getText(): String = inner.text

        override fun getFamilyName(): String = inner.familyName

        override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = true

        override fun invoke(project: Project, editor: Editor?, file: PsiFile?) = Unit

        override fun startInWriteAction(): Boolean = false

        override fun compareTo(other: IntentionAction): Int =
            if (other is OrderedQuickFix) index.compareTo(other.index) else 0
    }

    private class FakeQuickFix(var title: String) : IntentionAction {
        var invocations = 0

        override fun getText(): String = title

        override fun getFamilyName(): String = "family"

        override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = true

        override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
            invocations += 1
        }

        override fun startInWriteAction(): Boolean = false
    }
}
