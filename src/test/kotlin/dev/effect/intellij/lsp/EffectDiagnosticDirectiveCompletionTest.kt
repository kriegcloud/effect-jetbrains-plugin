package dev.effect.intellij.lsp

import dev.effect.intellij.lsp.EffectDiagnosticDirectiveCompletionContributor.Companion.completionFor
import dev.effect.intellij.lsp.EffectDiagnosticDirectiveCompletionContributor.DirectiveCompletionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectDiagnosticDirectiveCompletionTest {
    @Test
    fun returnsNullOutsideDirective() {
        assertNull(completionFor("const x = value"))
        assertNull(completionFor("// an ordinary comment"))
    }

    @Test
    fun completesRuleNameAfterMarker() {
        val completion = completionFor("// @effect-diagnostics-next-line float")!!
        assertEquals(DirectiveCompletionKind.RULE, completion.kind)
        assertEquals("float", completion.prefix)
    }

    @Test
    fun completesRuleNameWithEmptyPrefix() {
        val completion = completionFor("// @effect-diagnostics ")!!
        assertEquals(DirectiveCompletionKind.RULE, completion.kind)
        assertEquals("", completion.prefix)
    }

    @Test
    fun completesSeverityAfterColon() {
        val completion = completionFor("// @effect-diagnostics floatingEffect:warn")!!
        assertEquals(DirectiveCompletionKind.SEVERITY, completion.kind)
        assertEquals("warn", completion.prefix)
    }

    @Test
    fun completesSkipFileSeverity() {
        val completion = completionFor("/** @effect-diagnostics floatingEffect:skip-")!!
        assertEquals(DirectiveCompletionKind.SEVERITY, completion.kind)
        assertEquals("skip-", completion.prefix)
    }

    @Test
    fun completesSecondRuleAfterPriorSeverity() {
        val completion = completionFor("// @effect-diagnostics floatingEffect:off schema")!!
        assertEquals(DirectiveCompletionKind.RULE, completion.kind)
        assertEquals("schema", completion.prefix)
    }

    @Test
    fun exposesKnownRulesAndSeverities() {
        assertTrue(EffectDiagnosticDirectiveCompletionContributor.RULE_NAMES.contains("catchToIgnore"))
        assertTrue(EffectDiagnosticDirectiveCompletionContributor.RULE_NAMES.contains("flatMapToMap"))
        assertTrue(EffectDiagnosticDirectiveCompletionContributor.RULE_NAMES.contains("schemaNumber"))
        assertTrue(EffectDiagnosticDirectiveCompletionContributor.RULE_NAMES.contains("newSchemaClass"))
        assertTrue(EffectDiagnosticDirectiveCompletionContributor.RULE_NAMES.contains("missingPipeableSignature"))
        assertTrue(EffectDiagnosticDirectiveCompletionContributor.RULE_NAMES.contains("preferSchemaTypeProperty"))
        assertTrue(EffectDiagnosticDirectiveCompletionContributor.RULE_NAMES.contains("schemaOpaqueInstanceMember"))
        assertTrue(EffectDiagnosticDirectiveCompletionContributor.RULE_NAMES.contains("syncToSucceed"))
        assertTrue(EffectDiagnosticDirectiveCompletionContributor.RULE_NAMES.contains("abortControllerInEffect"))
        assertTrue(EffectDiagnosticDirectiveCompletionContributor.RULE_NAMES.contains("catchChainToFirstSuccessOf"))
        assertTrue(EffectDiagnosticDirectiveCompletionContributor.RULE_NAMES.contains("catchTagToCatchReason"))
        assertTrue(EffectDiagnosticDirectiveCompletionContributor.RULE_NAMES.contains("floatingEffectInVitest"))
        assertTrue(EffectDiagnosticDirectiveCompletionContributor.RULE_NAMES.contains("preferTypedSchemaDecoder"))
        assertTrue(EffectDiagnosticDirectiveCompletionContributor.RULE_NAMES.contains("preferUnsafeConstructor"))
        assertTrue(EffectDiagnosticDirectiveCompletionContributor.RULE_NAMES.contains("promiseInEffectSuccess"))
        assertTrue(EffectDiagnosticDirectiveCompletionContributor.RULE_NAMES.contains("schemaLiteralNonFinite"))
        assertFalse(EffectDiagnosticDirectiveCompletionContributor.RULE_NAMES.contains("setInterval"))
        assertFalse(EffectDiagnosticDirectiveCompletionContributor.RULE_NAMES.contains("setTimeout"))
        assertEquals(
            listOf("off", "warning", "error", "suggestion", "message", "skip-file"),
            EffectDiagnosticDirectiveCompletionContributor.SEVERITIES,
        )
    }
}
