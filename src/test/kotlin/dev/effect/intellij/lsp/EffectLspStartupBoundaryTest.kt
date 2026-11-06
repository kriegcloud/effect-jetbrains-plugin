package dev.effect.intellij.lsp

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.AbstractProgressIndicatorBase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import dev.effect.intellij.binary.EffectBinaryService
import dev.effect.intellij.core.EffectJson
import dev.effect.intellij.settings.EffectBinaryMode
import dev.effect.intellij.settings.EffectProjectSettings
import dev.effect.intellij.settings.EffectProjectSettingsService
import dev.effect.intellij.status.EffectLspStatus
import dev.effect.intellij.status.EffectStatusService
import dev.effect.intellij.status.EffectStatusListener
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import java.lang.reflect.InvocationTargetException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EffectLspStartupBoundaryTest : BasePlatformTestCase() {
    fun testInitializationOptionsPreserveJsonOnLspWire() {
        val binary = Files.createTempFile("effect-lsp-wire", "").also {
            Files.writeString(it, "echo test")
            it.toFile().setExecutable(true, false)
        }
        try {
            val json = """{"trace":"verbose","effect":{"inlays":true,"disabled":false,"depth":3,"ratio":1.25,"empty":null,"items":["text",7,false,null,{"nested":"<tag>"}]}}"""
            project.getService(EffectProjectSettingsService::class.java).updateSettings(
                EffectProjectSettings(
                    binaryMode = EffectBinaryMode.MANUAL,
                    manualBinaryPath = binary.toString(),
                    initializationOptionsJson = json,
                ),
            )
            val params = InitializeParams().apply {
                initializationOptions = EffectLspServerDescriptor(project).createInitializationOptions()
            }
            val message = RequestMessage().apply {
                setId(1)
                method = "initialize"
                this.params = params
            }
            val wire = MessageJsonHandler(emptyMap()) { it.disableHtmlEscaping() }.serialize(message)
            assertEquals(EffectJson.mapper.readTree(json), EffectJson.mapper.readTree(wire).path("params").path("initializationOptions"))
            for (internalField in listOf("_children", "_value", "_nodeFactory")) {
                assertFalse(wire, wire.contains(internalField))
            }
        } finally {
            Files.deleteIfExists(binary)
        }
    }

    fun testManagedHttpCancellationEscapesDescriptorWithoutStartupFailure() {
        assertManagedCancellation(ProcessCanceledException())
    }

    fun testCoroutineCancellationWhileWaitingForResolutionLockEscapesDescriptor() {
        assertManagedCancellation(CancellationException("cancelled startup"), waitForLock = true)
    }

    private fun assertManagedCancellation(cancellation: CancellationException, waitForLock: Boolean = false) {
        val requested = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requested.countDown()
            try {
                release.await(15, TimeUnit.SECONDS)
            } finally {
                exchange.close()
            }
        }
        server.start()
        val binaryService = EffectBinaryService.getInstance()
        val originalRegistry = binaryService.registryBaseUrl
        val resolutionLock = if (waitForLock) {
            val field = binaryService.javaClass.getDeclaredField("cacheLifecycleLock").apply { isAccessible = true }
            (field.get(binaryService) as java.util.concurrent.locks.ReentrantReadWriteLock).writeLock().also { it.lock() }
        } else null
        val notifications = java.util.concurrent.CopyOnWriteArrayList<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                notifications.add(notification)
            }
        })
        val statuses = java.util.concurrent.CopyOnWriteArrayList<EffectLspStatus>()
        EffectStatusService.getInstance(project).addListener(EffectStatusListener { statuses.add(it.status) })
        val indicator = object : AbstractProgressIndicatorBase() {
            override fun checkCanceled() {
                if (waitForLock) requested.countDown()
                if (isCanceled) throw cancellation
                super.checkCanceled()
            }
        }
        var worker: java.util.concurrent.Future<Throwable?>? = null
        try {
            binaryService.registryBaseUrl = "http://127.0.0.1:${server.address.port}"
            project.getService(EffectProjectSettingsService::class.java).updateSettings(
                EffectProjectSettings(binaryMode = EffectBinaryMode.LATEST),
            )
            val descriptor = EffectLspServerDescriptor(project)
            val startup = descriptor.javaClass.getDeclaredMethod("createCommandLine").apply { isAccessible = true }
            worker = ApplicationManager.getApplication().executeOnPooledThread(Callable {
                var thrown: Throwable? = null
                ProgressManager.getInstance().runProcess(Runnable {
                    try {
                        startup.invoke(descriptor)
                    } catch (error: InvocationTargetException) {
                        thrown = error.targetException
                    }
                }, indicator)
                thrown
            })
            assertTrue("Managed resolution did not reach cancellable wait", requested.await(10, TimeUnit.SECONDS))
            indicator.cancel()
            assertSame(cancellation, PlatformTestUtil.waitForFuture(worker, 10_000))
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertTrue("Unexpected startup failure notification", notifications.none { it.title == "Effect LSP startup failed" })
            assertFalse("Cancellation changed status to ERROR", statuses.contains(EffectLspStatus.ERROR))
        } finally {
            indicator.cancel()
            release.countDown()
            resolutionLock?.unlock()
            try {
                worker?.get(10, TimeUnit.SECONDS)
            } finally {
                server.stop(0)
                binaryService.registryBaseUrl = originalRegistry
                notifications.forEach { it.expire() }
            }
        }
    }
}
