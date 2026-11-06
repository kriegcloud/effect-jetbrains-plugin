package dev.effect.intellij.schema

import dev.effect.intellij.core.EffectJson
import junit.framework.TestCase

class EffectOptionSchemaTest : TestCase() {
    private val schema = EffectOptionSchema(javaClass.getResourceAsStream(EffectOptionSchema.RESOURCE)!!.use { EffectJson.mapper.readTree(it) })

    fun testPublishedOptionsAndDiscriminatorAreAccepted() {
        assertEquals(emptyList<String>(), warnings("""{"name":"@effect/language-service","inlays":true,"effectFn":["span"],"diagnosticSeverity":{"schemaNumber":"warning"}}"""))
        assertEquals(emptyList<String>(), warnings("""{"noExternal":false,"layerGraphFollowDepth":2,"importAliases":{"effect":"Effect"}}"""))
    }

    fun testUnknownNamesAndMistypedOptions() {
        val warnings = warnings("""{"inlay":true,"inlays":"true","layerGraphFollowDepth":1.5,"noExternal":null}""")
        assertEquals(4, warnings.size)
        assertTrue(warnings.any { it.contains("inlay: unknown option") })
        assertTrue(warnings.any { it.contains("inlays: expected") })
        assertTrue(warnings.any { it.contains("layerGraphFollowDepth: expected") })
        assertTrue(warnings.any { it.contains("noExternal: expected") })
    }

    fun testNestedRefsEnumsArrayItemsAndBounds() {
        val warnings = warnings("""{"diagnosticSeverity":{"schemaNumber":"fatal","inventedRule":123},"effectFn":["bogus"],"allowedDuplicatedPackages":[1],"layerGraphFollowDepth":-1}""")
        assertTrue(warnings.any { it.contains("diagnosticSeverity.schemaNumber") })
        assertTrue(warnings.any { it.contains("diagnosticSeverity.inventedRule") })
        assertTrue(warnings.any { it.contains("effectFn[0]") })
        assertTrue(warnings.any { it.contains("allowedDuplicatedPackages[0]") })
        assertTrue(warnings.any { it.contains("layerGraphFollowDepth") })
    }

    fun testCustomWorkspaceSchemaAndLocalReferences() {
        val local = EffectOptionSchema(EffectJson.mapper.readTree("""{"definitions":{"flag":{"type":"boolean"}},"properties":{"name":{"const":"@effect/language-service"},"newFlag":{"${'$'}ref":"#/definitions/flag"}},"additionalProperties":false,"type":"object"}"""))
        assertTrue(local.warnings(EffectJson.mapper.readTree("""{"newFlag":true}""")).isEmpty())
        assertTrue(local.warnings(EffectJson.mapper.readTree("""{"newFlag":1}""")).single().contains("expected"))
    }

    fun testJsoncPluginEntryCanBeValidated() {
        assertTrue(schema.warnings(EffectOptionSchema.parseOptions("""{"name":"@effect/language-service", /* keep */ "inlays":true,}""")).isEmpty())
    }

    private fun warnings(text: String) = schema.warnings(EffectJson.mapper.readTree(text))
}
