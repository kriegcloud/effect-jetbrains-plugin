package dev.effect.intellij.settings

import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.effect.intellij.core.EffectJson

class EffectTsconfigSyncTest : BasePlatformTestCase() {
    fun testAddsPluginEntryWhenMissing() {
        val (result, updatedJson) = apply(
            """{ "compilerOptions": { "strict": true } }""",
            settings(inlays = true, noExternal = false, severity = mapOf("schemaNumber" to "warning")),
        )
        assertTrue(result!!.changed)
        assertNull(result.errorMessage)

        val root = EffectJson.mapper.readTree(updatedJson)
        assertTrue(root.path("compilerOptions").path("strict").asBoolean())
        val plugins = root.path("compilerOptions").path("plugins")
        assertEquals(1, plugins.size())
        val entry = plugins[0]
        assertEquals("@effect/language-service", entry.path("name").asText())
        assertTrue(entry.path("inlays").asBoolean())
        assertFalse(entry.path("noExternal").asBoolean())
        assertEquals("warning", entry.path("diagnosticSeverity").path("schemaNumber").asText())
    }

    fun testCreatesMissingCompilerOptionsAndPluginsStructures() {
        val (result, updatedJson) = apply("""{ "extends": "./base.json" }""", settings(inlays = true))

        assertTrue(result!!.changed)
        val root = EffectJson.mapper.readTree(updatedJson)
        assertEquals("./base.json", root.path("extends").asText())
        assertTrue(
            root.path("compilerOptions")
                .path("plugins")[0]
                .path("inlays")
                .asBoolean(),
        )
    }

    fun testUpdatesExistingEntryAdditivelyPreservingOtherKeysAndPlugins() {
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
        val (result, updatedJson) = apply(
            input,
            settings(mermaid = "mermaid.com", severity = mapOf("redundantOrDie" to "error")),
        )

        assertTrue(result!!.changed)
        val plugins = EffectJson.mapper.readTree(updatedJson).path("compilerOptions").path("plugins")
        assertEquals(2, plugins.size())
        assertTrue(plugins.any { it.path("name").asText() == "other-plugin" })
        val effect = plugins.first { it.path("name").asText() == "@effect/language-service" }
        assertEquals("mermaid.com", effect.path("mermaidProvider").asText())
        assertTrue(effect.path("inlays").asBoolean())
        assertEquals("keep", effect.path("existing").asText())
        assertEquals("error", effect.path("diagnosticSeverity").path("redundantOrDie").asText())
    }

    fun testAppendsEffectEntryAfterUnrelatedPluginPreservingJsonc() {
        val input = """
            {
              "compilerOptions": {
                "plugins": [
                  // keep this unrelated plugin configuration
                  { "name": "other-plugin", "foo": 1, "enabled": true }
                ]
              }
            }
        """.trimIndent()
        val file = myFixture.configureByText("tsconfig.json", input) as JsonFile
        var result: EffectTsconfigSync.SyncResult? = null
        WriteCommandAction.runWriteCommandAction(project) {
            result = EffectTsconfigSync.apply(file, settings(inlays = true))
        }

        assertTrue(result!!.changed)
        assertNull(result!!.errorMessage)
        assertFalse(PsiTreeUtil.hasErrorElements(file))
        assertTrue(file.text.contains("// keep this unrelated plugin configuration"))

        val root = file.topLevelValue as JsonObject
        val compilerOptions = root.findProperty("compilerOptions")!!.value as JsonObject
        val plugins = compilerOptions.findProperty("plugins")!!.value as JsonArray
        assertEquals(2, plugins.valueList.size)

        val unrelated = plugins.valueList[0] as JsonObject
        assertEquals("other-plugin", (unrelated.findProperty("name")!!.value as JsonStringLiteral).value)
        assertEquals("1", unrelated.findProperty("foo")!!.value!!.text)
        assertEquals("true", unrelated.findProperty("enabled")!!.value!!.text)

        val effect = plugins.valueList[1] as JsonObject
        assertEquals(
            EffectTsconfigSync.EFFECT_PLUGIN_NAME,
            (effect.findProperty("name")!!.value as JsonStringLiteral).value,
        )
        assertEquals("true", effect.findProperty("inlays")!!.value!!.text)
    }

    fun testPreservesJsoncCommentsAndUnrelatedFormatting() {
        val input = """
            {
              // a JSONC comment
              "compilerOptions": {
                "strict": true,
                "plugins": [
                  { "name": "other-plugin", "custom": 1 },
                  {
                    "name": "@effect/language-service",
                    /* keep this manual option */
                    "manual": "keep"
                  }
                ]
              }
            }
        """.trimIndent()
        val (result, updatedJson) = apply(input, settings(inlays = true))

        assertTrue(result!!.changed)
        assertTrue(updatedJson.contains("// a JSONC comment"))
        assertTrue(updatedJson.contains("/* keep this manual option */"))
        assertTrue(updatedJson.contains("{ \"name\": \"other-plugin\", \"custom\": 1 }"))
        assertTrue(updatedJson.contains("\"manual\": \"keep\""))
    }

    fun testRejectsInvalidJsonWithoutMutation() {
        val input = """{ "compilerOptions": { broken } }"""
        val (result, updatedJson) = apply(input, settings(inlays = true))

        assertFalse(result!!.changed)
        assertNotNull(result.errorMessage)
        assertEquals(input, updatedJson)
    }

    fun testRejectsIncompatibleExistingStructuresWithoutMutation() {
        val input = """{ "compilerOptions": { "plugins": "manual" } }"""
        val (result, updatedJson) = apply(input, settings(inlays = true))

        assertFalse(result!!.changed)
        assertTrue(result.errorMessage!!.contains("plugins"))
        assertEquals(input, updatedJson)
    }

    fun testPreflightsNestedStructuresBeforeApplyingAnyChanges() {
        val input = """
            {
              "compilerOptions": {
                "plugins": [{
                  "name": "@effect/language-service",
                  "inlays": false,
                  "diagnosticSeverity": "manual"
                }]
              }
            }
        """.trimIndent()
        val (result, updatedJson) = apply(
            input,
            settings(inlays = true, severity = mapOf("schemaNumber" to "warning")),
        )

        assertFalse(result!!.changed)
        assertTrue(result.errorMessage!!.contains("diagnosticSeverity"))
        assertEquals(input, updatedJson)
    }

    fun testReturnsNullWhenNoTypedOptions() {
        val (result, updatedJson) = apply("""{ "compilerOptions": {} }""", settings())

        assertNull(result)
        assertEquals("""{ "compilerOptions": {} }""", updatedJson)
    }

    fun testReportsUnchangedWhenValuesAlreadyMatch() {
        val input = """
            {
              "compilerOptions": {
                "plugins": [{ "name": "@effect/language-service", "inlays": true }]
              }
            }
        """.trimIndent()
        val (result, updatedJson) = apply(input, settings(inlays = true))

        assertFalse(result!!.changed)
        assertEquals(input, updatedJson)
    }

    private fun apply(
        tsconfigJson: String,
        settings: EffectProjectSettings,
    ): Pair<EffectTsconfigSync.SyncResult?, String> {
        val file = myFixture.configureByText("tsconfig.json", tsconfigJson) as JsonFile
        var result: EffectTsconfigSync.SyncResult? = null
        WriteCommandAction.runWriteCommandAction(project) {
            result = EffectTsconfigSync.apply(file, settings)
        }
        return result to file.text
    }

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
}
