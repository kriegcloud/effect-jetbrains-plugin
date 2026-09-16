package dev.effect.intellij.lsp

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.Either

class EffectDiagnosticDirectivesTest : BasePlatformTestCase() {
    fun testDescriptorUsesEffectDiagnosticsCustomizer() {
        val customizer = EffectLspServerDescriptor(project).lspCustomization.diagnosticsCustomizer

        assertTrue(customizer is EffectLspDiagnosticsSupport)
    }

    fun testNextLineDirectiveSuppressesStrictEffectProvide() {
        val source = """
            import { Effect, Layer } from "effect"

            export const program = Effect.void.pipe(
              // @effect-diagnostics-next-line strictEffectProvide:off
              Effect.provide(Layer.empty)
            )
        """.trimIndent()

        val severity = effectDirectiveSeverityForDiagnostic(
            sourceText = source,
            diagnosticLine = source.lineOf("Effect.provide"),
            diagnostic = effectDiagnostic("effect(strictEffectProvide)"),
        )

        assertEquals(EffectDiagnosticDirectiveSeverity.OFF, severity)
    }

    fun testBlockCommentAndPrefixedRuleNamesSuppressDiagnostics() {
        val source = """
            import { Effect } from "effect"

            /** @effect-diagnostics-next-line effect/tryCatchInEffectGen:off */
            Effect.tryPromise(() => Promise.resolve(1))
        """.trimIndent()

        val severity = effectDirectiveSeverityForDiagnostic(
            sourceText = source,
            diagnosticLine = source.lineOf("Effect.tryPromise"),
            diagnostic = effectDiagnostic("effect(tryCatchInEffectGen)"),
        )

        assertEquals(EffectDiagnosticDirectiveSeverity.OFF, severity)
    }

    fun testWildcardSectionCanBeReenabledByLaterRuleDirective() {
        val source = """
            // @effect-diagnostics *:off
            Effect.succeed("hidden")
            // @effect-diagnostics strictEffectProvide:warning
            Effect.provide(Layer.empty)
        """.trimIndent()

        val hiddenSeverity = effectDirectiveSeverityForDiagnostic(
            sourceText = source,
            diagnosticLine = source.lineOf("hidden"),
            diagnostic = effectDiagnostic("effect(strictEffectProvide)"),
        )
        val reenabledSeverity = effectDirectiveSeverityForDiagnostic(
            sourceText = source,
            diagnosticLine = source.lineOf("Effect.provide"),
            diagnostic = effectDiagnostic("effect(strictEffectProvide)"),
        )

        assertEquals(EffectDiagnosticDirectiveSeverity.OFF, hiddenSeverity)
        assertEquals(EffectDiagnosticDirectiveSeverity.WARNING, reenabledSeverity)
    }

    fun testSkipFileSuppressesWholeFile() {
        val source = """
            import { Effect } from "effect"

            /** @effect-diagnostics floatingEffect:skip-file missingEffectError:skip-file */
            Effect.succeed("first")
            Effect.succeed("second")
        """.trimIndent()

        val firstSeverity = effectDirectiveSeverityForDiagnostic(
            sourceText = source,
            diagnosticLine = source.lineOf("first"),
            diagnostic = effectDiagnostic("effect(floatingEffect)"),
        )
        val secondSeverity = effectDirectiveSeverityForDiagnostic(
            sourceText = source,
            diagnosticLine = source.lineOf("second"),
            diagnostic = effectDiagnostic("effect(missingEffectError)"),
        )

        assertEquals(EffectDiagnosticDirectiveSeverity.SKIP_FILE, firstSeverity)
        assertEquals(EffectDiagnosticDirectiveSeverity.SKIP_FILE, secondSeverity)
    }

    fun testNonEffectDiagnosticsAreIgnoredByDirectiveFilter() {
        val severity = effectDirectiveSeverityForDiagnostic(
            sourceText = "// @effect-diagnostics-next-line *:off\nconst value = 1",
            diagnosticLine = 1,
            diagnostic = Diagnostic().also { it.message = "Type '1' is not assignable to type '2'." },
        )

        assertNull(severity)
    }


    fun testDiagnosticMessageTextHandlesEveryLsp4jShape() {
        assertNull(EffectLsp4jDiagnosticMessage.textOf(null))
        assertEquals("effect(foo)", EffectLsp4jDiagnosticMessage.textOf("effect(foo)"))
        assertEquals("effect(foo)", EffectLsp4jDiagnosticMessage.textOf(Either.forLeft<String, MarkupContent>("effect(foo)")))
        assertEquals(
            "Prefer decodeSync. effect(preferTypedSchemaDecoder)",
            EffectLsp4jDiagnosticMessage.textOf(
                Either.forRight<String, MarkupContent>(MarkupContent("markdown", "Prefer decodeSync. effect(preferTypedSchemaDecoder)")),
            ),
        )
        assertEquals("plain", EffectLsp4jDiagnosticMessage.textOf(MarkupContent("plaintext", "plain")))
    }

    fun testDiagnosticMessageIsReadReflectivelyFromTheRunningLsp4j() {
        val diagnostic = Diagnostic().also { it.message = "Use Effect.forEach instead. effect(allOfMapToForEach)" }

        assertEquals("Use Effect.forEach instead. effect(allOfMapToForEach)", EffectLsp4jDiagnosticMessage.text(diagnostic))
        assertEquals("allOfMapToForEach", diagnostic.effectDiagnosticRuleName())
    }

    fun testSeverityOverrideCopiesTheMessageThroughTheCompatibilityAccessor() {
        val diagnostic = effectDiagnostic("effect(strictEffectProvide)").also {
            it.range = Range(Position(0, 0), Position(0, 1))
        }

        val adjusted = diagnostic.withEffectDirectiveSeverity(EffectDiagnosticDirectiveSeverity.ERROR)

        assertEquals(DiagnosticSeverity.Error, adjusted.severity)
        assertEquals("Effect diagnostic. effect(strictEffectProvide)", EffectLsp4jDiagnosticMessage.text(adjusted))
        assertEquals("strictEffectProvide", adjusted.effectDiagnosticRuleName())
    }
    private fun effectDiagnostic(messageSuffix: String): Diagnostic =
        Diagnostic().also { diagnostic ->
            diagnostic.message = "Effect diagnostic. $messageSuffix"
        }

    private fun String.lineOf(needle: String): Int {
        val index = indexOf(needle)
        assertTrue("Expected source to contain '$needle'", index >= 0)
        return substring(0, index).count { it == '\n' }
    }
}
