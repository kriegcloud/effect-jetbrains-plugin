package dev.effect.intellij.settings

import dev.effect.intellij.core.EffectJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectTsconfigSyncTest {
    private fun settings(
        inlays: Boolean? = null,
        mermaid: String = "",
        noExternal: Boolean? = null,
        depth: Int? = null,
        severity: Map<String, String> = emptyMap(),
    ) = EffectProjectSettings(
        lspInlays = inlays,
        lspMermaidProvider = mermaid,
        lspNoExternal = noExternal,
        lspLayerGraphFollowDepth = depth,
        lspDiagnosticSeverity = severity,
    )

    @Test
    fun addsPluginEntryWhenMissing() {
        val result = EffectTsconfigSync.apply(
            """{ "compilerOptions": { "strict": true } }""",
            settings(inlays = true, noExternal = false, severity = mapOf("schemaNumber" to "warning")),
        )!!
        assertTrue(result.changed)
        assertFalse(result.hadComments)

        val root = EffectJson.mapper.readTree(result.updatedJson)
        assertTrue(root.path("compilerOptions").path("strict").asBoolean())
        val plugins = root.path("compilerOptions").path("plugins")
        assertEquals(1, plugins.size())
        val entry = plugins[0]
        assertEquals("@effect/language-service", entry.path("name").asText())
        assertTrue(entry.path("inlays").asBoolean())
        assertFalse(entry.path("noExternal").asBoolean())
        assertEquals("warning", entry.path("diagnosticSeverity").path("schemaNumber").asText())
    }

    @Test
    fun updatesExistingEntryAdditivelyPreservingOtherKeysAndPlugins() {
        val input = """
            {
              "compilerOptions": {
                "plugins": [
                  { "name": "other-plugin", "foo": 1 },
                  { "name": "@effect/language-service", "inlays": true, "existing": "keep" }
                ]
              }
            }
        """.trimIndent()
        val result = EffectTsconfigSync.apply(
            input,
            settings(mermaid = "mermaid.com", severity = mapOf("redundantOrDie" to "error")),
        )!!

        val plugins = EffectJson.mapper.readTree(result.updatedJson).path("compilerOptions").path("plugins")
        assertEquals(2, plugins.size())
        assertTrue(plugins.any { it.path("name").asText() == "other-plugin" })
        val effect = plugins.first { it.path("name").asText() == "@effect/language-service" }
        assertEquals("mermaid.com", effect.path("mermaidProvider").asText())
        assertTrue(effect.path("inlays").asBoolean())
        assertEquals("keep", effect.path("existing").asText())
        assertEquals("error", effect.path("diagnosticSeverity").path("redundantOrDie").asText())
    }

    @Test
    fun returnsNullWhenNoTypedOptions() {
        assertNull(EffectTsconfigSync.apply("""{ "compilerOptions": {} }""", settings()))
    }

    @Test
    fun detectsCommentsForStrippingWarning() {
        val input = """
            {
              // a JSONC comment
              "compilerOptions": { "strict": true }
            }
        """.trimIndent()
        val result = EffectTsconfigSync.apply(input, settings(inlays = true))!!
        assertTrue(result.hadComments)
    }
}
