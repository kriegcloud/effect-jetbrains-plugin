package dev.effect.intellij.lsp

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EffectLspCoreRegressionTest : BasePlatformTestCase() {
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

    fun testMermaidPositionUsesUtf16OffsetsWithTabsAndSupplementaryCharacters() {
        for (prefix in listOf("\t", "\uD83D\uDE00 ", "\t\uD83D\uDE00 ")) {
            for (tabSize in listOf(2, 8)) {
                myFixture.configureByText("layer.ts", "// previous line\n${prefix}Layer<caret>")
                myFixture.editor.settings.setTabSize(tabSize)
                val position = mermaidRequestPosition(myFixture.editor)
                assertEquals(1, position.line)
                assertEquals(prefix.length + "Layer".length, position.character)
            }
        }
    }

    fun testInvalidationOnEdtDoesNotWaitForResolutionOrRepublishOldGeneration() {
        val cache = InvalidatableLaunchConfigurationCache<String, String>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = ApplicationManager.getApplication().executeOnPooledThread(Callable {
            cache.get({ "settings" }) {
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Resolution was not released" }
                "old"
            }
        })
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            ApplicationManager.getApplication().assertIsDispatchThread()
            val before = System.nanoTime()
            cache.clear()
            assertTrue("Invalidation waited on binary resolution", System.nanoTime() - before < TimeUnit.SECONDS.toNanos(1))
            assertEquals(1L, release.count)
        } finally {
            release.countDown()
        }
        assertEquals("old", worker.get(5, TimeUnit.SECONDS))
        assertEquals("new", cache.get({ "settings" }) { "new" })
        assertEquals("new", cache.get({ "settings" }) { error("Expected cached restarted configuration") })
    }

    fun testMermaidOutputPreparationRunsOffEdtAndOpeningRunsOnEdt() {
        val source = myFixture.configureByText("layer.txt", "const Layer = 1").virtualFile
        val service = project.getService(EffectLayerMermaidService::class.java)
        val worker = ApplicationManager.getApplication().executeOnPooledThread(Callable {
            assertFalse(ApplicationManager.getApplication().isDispatchThread)
            service.prepareMermaidSource(source, "graph TD\nA --> B")
        })
        val output = PlatformTestUtil.waitForFuture(worker, 20_000)
        try {
            assertEquals("graph TD\nA --> B", String(output.contentsToByteArray(), Charsets.UTF_8))
            service.displayMermaidResult(Result.success(output))
            assertTrue(FileEditorManager.getInstance(project).isFileOpen(output))
        } finally {
            FileEditorManager.getInstance(project).closeFile(output)
            com.intellij.openapi.application.WriteAction.run<RuntimeException> { output.delete(this) }
        }
    }

    fun testMermaidPreparationRejectsEdtAndFailureProducesWarning() {
        val source = myFixture.configureByText("layer.txt", "const Layer = 1").virtualFile
        val service = project.getService(EffectLayerMermaidService::class.java)
        val failure = runCatching { service.prepareMermaidSource(source, "graph TD") }
        assertTrue(failure.exceptionOrNull() is IllegalStateException)
        var warning: Notification? = null
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                warning = notification
            }
        })
        service.displayMermaidResult(Result.failure(IllegalStateException("Output write failed")))
        assertEquals(NotificationType.WARNING, warning?.type)
        assertEquals("Output write failed", warning?.content)
        warning?.expire()
    }
}
