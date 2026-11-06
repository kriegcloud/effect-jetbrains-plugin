package dev.effect.intellij.schema

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import dev.effect.intellij.core.EffectJson
import dev.effect.intellij.settings.EffectBinaryMode
import dev.effect.intellij.settings.EffectProjectSettings
import dev.effect.intellij.settings.EffectProjectSettingsService
import dev.effect.intellij.settings.EffectTsconfigSync
import dev.effect.intellij.settings.SettingSeverity

class EffectJsonSchemaProviderTest : BasePlatformTestCase() {
    fun testNearestAncestorAndBundledFallbackForBothConfigNames() {
        val rootSchema = myFixture.addFileToProject("node_modules/@effect/tsgo/schema.json", "{} ").virtualFile
        val nestedSchema = myFixture.addFileToProject("packages/a/node_modules/@effect/tsgo/schema.json", "{} ").virtualFile
        val rootConfig = myFixture.addFileToProject("tsconfig.json", "{}").virtualFile
        val nestedConfig = myFixture.addFileToProject("packages/a/src/jsconfig.json", "{}").virtualFile
        val otherConfig = myFixture.addFileToProject("packages/b/tsconfig.json", "{}").virtualFile
        val providers = EffectJsonSchemaProviderFactory().getProviders(project)
        assertEquals(rootSchema, providers.single { it.isAvailable(rootConfig) }.schemaFile)
        assertEquals(nestedSchema, providers.single { it.isAvailable(nestedConfig) }.schemaFile)
        assertEquals(rootSchema, providers.single { it.isAvailable(otherConfig) }.schemaFile)
        WriteCommandAction.runWriteCommandAction(project) { rootSchema.delete(this) }
        assertEquals("tsgo-0.67.4.schema.json", providers.single { it.isAvailable(rootConfig) }.schemaFile!!.name)
    }

    fun testCompletionComposesWithOrdinaryCompilerOptionsAndOtherPlugins() {
        JsonSchemaProviderFactory.EP_NAME.point.registerExtension(EffectJsonSchemaProviderFactory(), testRootDisposable)
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("tsconfig.json", """{"compilerOptions":{"plugins":[{"name":"@effect/language-service", "<caret>"}]}}""").virtualFile)
        JsonSchemaService.Impl.get(project).reset()
        myFixture.completeBasic()
        assertTrue(myFixture.lookupElementStrings.orEmpty().contains("inlays"))
        assertTrue(myFixture.lookupElementStrings.orEmpty().contains("layerGraphFollowDepth"))
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("nested/tsconfig.json", """{"compilerOptions":{"<caret>"}}""").virtualFile)
        JsonSchemaService.Impl.get(project).reset()
        myFixture.completeBasic()
        assertTrue(myFixture.lookupElementStrings.orEmpty().contains("strict"))
    }

    fun testCompletionUsesInstalledWorkspaceSchema() {
        myFixture.addFileToProject("node_modules/@effect/tsgo/schema.json", """{
            "type":"object", "properties":{"compilerOptions":{"type":"object", "properties":{
                "plugins":{"type":"array", "items":{"type":"object", "properties":{
                    "name":{"const":"@effect/language-service"}, "workspaceOnly":{"type":"boolean"}
                }}}
            }}}
        }""")
        JsonSchemaProviderFactory.EP_NAME.point.registerExtension(EffectJsonSchemaProviderFactory(), testRootDisposable)
        myFixture.configureFromExistingVirtualFile(myFixture.addFileToProject("tsconfig.json",
            """{"compilerOptions":{"plugins":[{"name":"@effect/language-service", "<caret>"}]}}""").virtualFile)
        JsonSchemaService.Impl.get(project).reset()
        myFixture.completeBasic()
        assertTrue(myFixture.lookupElementStrings.orEmpty().contains("workspaceOnly"))
        assertFalse(myFixture.lookupElementStrings.orEmpty().contains("inlays"))
    }

    fun testEditorValidationKeepsOtherPluginsAndCompilerOptions() {
        JsonSchemaProviderFactory.EP_NAME.point.registerExtension(EffectJsonSchemaProviderFactory(), testRootDisposable)
        myFixture.enableInspections(com.jetbrains.jsonSchema.impl.inspections.JsonSchemaComplianceInspection())
        val file = myFixture.addFileToProject("jsconfig.json", """{
            "compilerOptions": {"strict": "bad", "plugins": [
                {"name": "other-plugin", "custom": true},
                {"name": "@effect/language-service", "inlays": "bad", "typo": true}
            ]}
        }""")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        JsonSchemaService.Impl.get(project).reset()
        val warnings = myFixture.doHighlighting().filter { it.description != null }
        assertTrue(warnings.toString(), warnings.count { it.description.contains("boolean") } >= 2)
        assertTrue(warnings.toString(), warnings.any { it.description.contains("typo") })
        assertFalse(warnings.toString(), warnings.any { it.description.contains("custom") })
    }

    fun testRawSettingsAndSyncReportAdvisoryWarnings() {
        val service = project.getService(EffectSchemaService::class.java)
        service.preload()
        com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Schema loading", {
            service.warnings(EffectJson.mapper.readTree("{}")).isEmpty()
        }, 20)
        val problems = EffectProjectSettingsService.getInstance(project).validate(EffectProjectSettings(
            binaryMode = EffectBinaryMode.LATEST,
            initializationOptionsJson = """{"inlays":"wrong","typo":true}""",
            workspaceConfigurationJson = """{"otherServer":{"anything":true},"effect":{"noExternal":123}}""",
        ))
        assertEquals(3, problems.size)
        assertTrue(problems.all { it.severity == SettingSeverity.WARNING })
        val config = myFixture.addFileToProject("tsconfig.json", """{"compilerOptions":{"plugins":[{"name":"@effect/language-service", "unknown":true, "inlays":"bad",}]}}""") as com.intellij.json.psi.JsonFile
        WriteCommandAction.runWriteCommandAction(project) {
            val result = EffectTsconfigSync.apply(config, EffectProjectSettings(lspNoExternal = true))!!
            assertTrue(result.changed)
            assertNull(result.errorMessage)
            assertEquals(2, result.warnings.size)
            assertTrue(config.text.contains("unknown"))
        }
    }
}
