package dev.effect.intellij.lsp

import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EffectLayerMermaidDecodeTest {
    // Produced exactly like `@effect/tsgo`'s internal/layergraph/mermaidurl.go:
    // base64url(no padding) of zlib(BestCompression) of {"code": <diagram>}.
    private val encoded =
        "eNqrVkrOT0lVslJKL0osyFAIcYnJU1BwjHYsKPBJrEwtilXQ1bVTcIp2SSxJTEosTo0FS4MFnaOdE5MzUmOVagHj4RUa"
    private val expectedDiagram = "graph TD\n  A[AppLayer] --> B[Database]\n  A --> C[Cache]"

    @Test
    fun decodesPakoHoverLinkToMermaidSource() {
        val markdown =
            "**AppLayer**\n\n[Show full graph](https://mermaid.live/edit#pako:$encoded) - " +
                "[Show outline](https://mermaid.live/edit#pako:$encoded)\n\n"
        assertEquals(expectedDiagram, EffectLayerMermaidService.decodeMermaidFromHover(markdown))
    }

    @Test
    fun returnsNullWhenNoPakoLinkIsPresent() {
        assertNull(EffectLayerMermaidService.decodeMermaidFromHover("Layer AppLayer with no external graph link."))
    }

    @Test
    fun extractsMarkdownFromMarkupContentHover() {
        val hover = Hover(MarkupContent("markdown", "Effect **Layer** graph"))
        assertEquals("Effect **Layer** graph", EffectLayerMermaidService.extractHoverMarkdown(hover))
    }
}
