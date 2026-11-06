package dev.effect.intellij.lsp

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EffectDiagnosticDirectivePlatformCompletionTest : BasePlatformTestCase() {
    fun testBlankFirstLineEmptyDocumentAndNormalFirstLine() {
        listOf("<caret>\nconst value = 1", "<caret>", "const val<caret> = 1").forEach {
            myFixture.configureByText("index.ts", it)
            myFixture.complete(CompletionType.BASIC)
        }
    }

    fun testDirectiveCompletionOnlyInComments() {
        listOf(
            "const text = \"@effect-diagnostics floatingEffect:wa<caret>\"",
            "const text = `\n// @effect-diagnostics floatingEffect:wa<caret>\n`",
            "const text = /@effect-diagnostics floatingEffect:wa<caret>/",
            "/* @effect-diagnostics floatingEffect:off */ const wa<caret> = 1",
            "/* @effect-diagnostics floatingEffect:off */<caret>",
        ).forEach {
            myFixture.configureByText("index.ts", it)
            val items = myFixture.complete(CompletionType.BASIC).orEmpty()
            assertFalse(it, items.any { item -> item.lookupString == "warning" || item.lookupString == "floatingEffect" })
        }
        listOf("// @effect-diagnostics floatingEffect:<caret>", "/**\n * @effect-diagnostics floatingEffect:<caret>\n */").forEach {
            myFixture.configureByText("index.ts", it)
            val items = myFixture.complete(CompletionType.BASIC).orEmpty()
            assertTrue(it, items.any { item -> item.lookupString == "warning" })
        }
    }

    fun testDirectiveCompletionReadsOnlyCurrentLine() {
        myFixture.configureByText("index.ts", "const value = 1\n".repeat(10_000) + "// @effect-diagnostics floatingEffect:<caret>")
        val items = myFixture.complete(CompletionType.BASIC).orEmpty()
        assertTrue(items.any { it.lookupString == "warning" })
    }
}
