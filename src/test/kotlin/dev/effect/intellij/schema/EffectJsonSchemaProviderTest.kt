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
    override fun setUp() {
        super.setUp()
        // Mermaid output lives under the IDE system directory and the bundled schema resolves from the
        // build's resources directory; Windows CI (8.3 short temp paths) does not list either among the
        // default allowed VFS roots, so allow both, raw and canonical.
        val roots = listOf(
            java.nio.file.Path.of(com.intellij.openapi.application.PathManager.getSystemPath()),
            java.nio.file.Path.of(System.getProperty("user.dir")),
        ).flatMap { root -> listOf(root.toAbsolutePath().toString(), runCatching { root.toRealPath().toString() }.getOrDefault(root.toString())) }
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, *roots.distinct().toTypedArray())
    }

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

    fun testMovingExternalConfigDirectoryIntoProjectRefreshesProviders() {
        val config = myFixture.addFileToProject("tsconfig.json", "{}").virtualFile
        val external = java.nio.file.Files.createTempDirectory("effect-external-config")
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, external.toString())
        try {
            java.nio.file.Files.createDirectories(external.resolve("content"))
            val destination = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(external.resolve("content"))!!
            com.intellij.testFramework.PsiTestUtil.addContentRoot(module, destination)
            java.nio.file.Files.createDirectories(external.resolve("new-package"))
            java.nio.file.Files.writeString(external.resolve("new-package/tsconfig.json"), "{}")
            val directory = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(external.resolve("new-package"))!!
            assertFalse(com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).isInContent(directory))
            JsonSchemaProviderFactory.EP_NAME.point.registerExtension(EffectJsonSchemaProviderFactory(), testRootDisposable)
            val json = JsonSchemaService.Impl.get(project)
            assertTrue(json.getSchemaFilesForFile(config).any { it.name == "tsgo-0.67.4.schema.json" })
            WriteCommandAction.runWriteCommandAction(project) { directory.move(this, destination) }
            com.intellij.testFramework.IndexingTestUtil.waitUntilIndexesAreReady(project)
            val imported = directory.findChild("tsconfig.json")!!
            awaitSchema { json.getSchemaFilesForFile(imported).any { it.name == "tsgo-0.67.4.schema.json" } }
        } finally {
            java.nio.file.Files.deleteIfExists(external.resolve("new-package/tsconfig.json"))
            java.nio.file.Files.deleteIfExists(external.resolve("new-package"))
            external.toFile().deleteRecursively()
        }
    }

    fun testUnrelatedEventBatchAcquiresRootsOnceWithoutReset() {
        val config = myFixture.addFileToProject("tsconfig.json", "{}").virtualFile
        // Own the dispatch queue so an unrelated delivery cannot hide a reset behind a
        // delayed coroutine. Nothing runs until this test explicitly drains the queue,
        // even if the worker is held for longer than the old 300 ms observation window.
        val queued = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                queued.add(block)
            }
        }
        val job = kotlinx.coroutines.SupervisorJob()
        val service = EffectSchemaService(project, kotlinx.coroutines.CoroutineScope(job + dispatcher))
        val json = JsonSchemaService.Impl.get(project)
        // The factory can also have initialized the project's watched service during
        // fixture creation. Settle its actual job before observing global resets.
        val watched = project.getService(EffectSchemaService::class.java)
        awaitSchema { watched.associationResetJob?.isCompleted != false }
        val resets = java.util.concurrent.atomic.AtomicInteger()
        val onReset = Runnable { resets.incrementAndGet() }
        json.registerResetAction(onReset)
        try {
            var acquisitions = 0
            val events = List(2000) {
                com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent(this, config,
                    config.modificationStamp, config.modificationStamp + 1, false)
            }
            service.associationsChanged(events) { acquisitions++; listOf(config.parent.path) }
            assertEquals(1, acquisitions)
            assertTrue("Unrelated events must not enqueue a reset coroutine", queued.isEmpty())
            assertEquals(0, job.children.count())
            assertEquals(0, resets.get())

            // Positive control: a relevant event must enqueue work, and draining that
            // same scope must complete an observable reset before we assert the count.
            val relevant = com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent(
                this, config.parent, "jsconfig.json", false, null, null, null)
            service.associationsChanged(listOf(relevant)) { listOf(config.parent.path) }
            assertFalse("Relevant events must enqueue a reset coroutine", queued.isEmpty())
            awaitSchema {
                queued.poll()?.run()
                job.children.none()
            }
            assertEquals(1, resets.get())
        } finally {
            json.unregisterResetAction(onReset)
            job.cancel()
            while (true) (queued.poll() ?: break).run()
            service.dispose()
        }
    }

    fun testRenameConfigAwayRemovesEffectSchema() {
        JsonSchemaProviderFactory.EP_NAME.point.registerExtension(EffectJsonSchemaProviderFactory(), testRootDisposable)
        val config = myFixture.addFileToProject("tsconfig.json", "{}").virtualFile
        val json = JsonSchemaService.Impl.get(project)
        assertTrue(json.getSchemaFilesForFile(config).any { it.name == "tsgo-0.67.4.schema.json" })
        val provider = EffectJsonSchemaProviderFactory().getProviders(project).single { it.isAvailable(config) }
        WriteCommandAction.runWriteCommandAction(project) { config.rename(this, "notes.json") }
        assertFalse(provider.isAvailable(config))
        awaitSchema { json.getSchemaFilesForFile(config).none { it.name == "tsgo-0.67.4.schema.json" } }
    }

    fun testRenameWorkspaceSchemaAwayUsesBundledSchema() {
        val schema = myFixture.addFileToProject("node_modules/@effect/tsgo/schema.json", "{}").virtualFile
        val config = myFixture.addFileToProject("tsconfig.json", "{}").virtualFile
        JsonSchemaProviderFactory.EP_NAME.point.registerExtension(EffectJsonSchemaProviderFactory(), testRootDisposable)
        val json = JsonSchemaService.Impl.get(project)
        assertContainsElements(json.getSchemaFilesForFile(config), schema)
        WriteCommandAction.runWriteCommandAction(project) { schema.rename(this, "old-schema.json") }
        awaitSchema { json.getSchemaFilesForFile(config).any { it.name == "tsgo-0.67.4.schema.json" } }
    }

    fun testWorkspaceSchemaChangesRefreshCompletionWithoutManualReset() {
        JsonSchemaProviderFactory.EP_NAME.point.registerExtension(EffectJsonSchemaProviderFactory(), testRootDisposable)
        val config = myFixture.addFileToProject("tsconfig.json", "{}").virtualFile
        fun options(): List<String> {
            myFixture.configureFromExistingVirtualFile(config)
            WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.setText("""{"compilerOptions":{"plugins":[{"name":"@effect/language-service", ""}]}}""") }
            myFixture.editor.caretModel.moveToOffset(myFixture.editor.document.text.indexOf("\"\"}") + 1)
            com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
            myFixture.completeBasic()
            val result = myFixture.lookupElementStrings.orEmpty().toList()
            com.intellij.codeInsight.lookup.LookupManager.getInstance(project).hideActiveLookup()
            return result
        }
        assertContainsElements(options(), "inlays")
        fun schema(option: String) = """{"type":"object","properties":{"compilerOptions":{"type":"object","properties":{
            "plugins":{"type":"array","items":{"type":"object","additionalProperties":false,"properties":{
            "name":{"const":"@effect/language-service"},"$option":{"type":"boolean"}}}}}}}}"""
        val workspace = myFixture.addFileToProject("node_modules/@effect/tsgo/schema.json", schema("installedOption")).virtualFile
        val json = JsonSchemaService.Impl.get(project)
        awaitSchema { json.getSchemaFilesForFile(config).contains(workspace) }
        assertContainsElements(options(), "installedOption")
        val resets = java.util.concurrent.atomic.AtomicInteger()
        val onReset = Runnable { resets.incrementAndGet() }
        json.registerResetAction(onReset)
        com.intellij.openapi.util.Disposer.register(testRootDisposable) { json.unregisterResetAction(onReset) }
        WriteCommandAction.runWriteCommandAction(project) { workspace.setBinaryContent(schema("updatedOption").toByteArray()) }
        awaitSchema { resets.get() > 0 }
        assertContainsElements(options(), "updatedOption")
        val advisory = project.getService(EffectSchemaService::class.java)
        awaitSchema { advisory.warnings(EffectJson.mapper.readTree("""{"updatedOption":"bad"}"""), config.parent)
            .any { it.contains("boolean") } }
        WriteCommandAction.runWriteCommandAction(project) { workspace.delete(this) }
        awaitSchema { json.getSchemaFilesForFile(config).any { it.name == "tsgo-0.67.4.schema.json" } }
        assertContainsElements(options(), "inlays")
    }

    fun testRemovingNearestPackageDirectoryUsesAncestorSchema() {
        val ancestor = myFixture.addFileToProject("node_modules/@effect/tsgo/schema.json", "{}").virtualFile
        val nearest = myFixture.addFileToProject("nested/node_modules/@effect/tsgo/schema.json", "{}").virtualFile
        val config = myFixture.addFileToProject("nested/tsconfig.json", "{}").virtualFile
        JsonSchemaProviderFactory.EP_NAME.point.registerExtension(EffectJsonSchemaProviderFactory(), testRootDisposable)
        val json = JsonSchemaService.Impl.get(project)
        assertContainsElements(json.getSchemaFilesForFile(config), nearest)
        val resets = java.util.concurrent.atomic.AtomicInteger()
        val onReset = Runnable { resets.incrementAndGet() }
        json.registerResetAction(onReset)
        try {
            WriteCommandAction.runWriteCommandAction(project) { nearest.parent.delete(this) }
            awaitSchema { resets.get() > 0 }
            assertContainsElements(json.getSchemaFilesForFile(config), ancestor)
        } finally {
            json.unregisterResetAction(onReset)
        }
    }

    fun testSchemaInvalidationsAreScopedAndCoalesced() {
        val schema = myFixture.addFileToProject("node_modules/@effect/tsgo/schema.json", "{}").virtualFile
        val config = myFixture.addFileToProject("tsconfig.json", "{}").virtualFile
        JsonSchemaProviderFactory.EP_NAME.point.registerExtension(EffectJsonSchemaProviderFactory(), testRootDisposable)
        val json = JsonSchemaService.Impl.get(project)
        json.getSchemaFilesForFile(config)
        val service = project.getService(EffectSchemaService::class.java)
        val unrelated = com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent(
            this, config.parent, "unrelated-directory", true, null, null, null)
        assertFalse(service.affectsAssociations(unrelated))
        val saves = com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent(this, config, config.modificationStamp, config.modificationStamp + 1, false)
        assertFalse(service.affectsAssociations(saves))
        val otherProject = com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent(
            this, config.parent.parent, "unrelated-project", true, null, null, null)
        assertFalse(service.affectsAssociations(otherProject))
        val resets = java.util.concurrent.atomic.AtomicInteger()
        val onReset = Runnable { resets.incrementAndGet() }
        json.registerResetAction(onReset)
        try {
            val event = com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent(this, schema, schema.modificationStamp, schema.modificationStamp + 1, false)
            val publisher = project.messageBus.syncPublisher(com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES)
            WriteCommandAction.runWriteCommandAction(project) { repeat(5) { publisher.after(listOf(event)) } }
            awaitSchema { resets.get() > 0 }
            assertEquals(1, resets.get())
        } finally {
            json.unregisterResetAction(onReset)
        }
    }

    private fun awaitSchema(condition: () -> Boolean) {
        com.intellij.testFramework.PlatformTestUtil.waitWithEventsDispatching("Automatic schema refresh", condition, 20)
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
