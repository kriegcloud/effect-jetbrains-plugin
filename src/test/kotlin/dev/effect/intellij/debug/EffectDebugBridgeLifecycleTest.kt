package dev.effect.intellij.debug

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EffectDebugBridgeLifecycleTest : BasePlatformTestCase() {
    fun testBlockedPreparationReturnsImmediatelyAndDetachExpiresCompletion() {
        val background = mutableListOf<() -> Unit>()
        val foreground = mutableListOf<() -> Unit>()
        val preparation = EffectDebugPreparation({ background += it }, { foreground += it })
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val published = mutableListOf<String>()
        preparation.start({
            entered.countDown()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            "old"
        }) { published += it.getOrThrow() }
        assertEquals(1, background.size) // start returned before preparation runs.
        val thread = Thread(background.removeAt(0))
        thread.start()
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            preparation.invalidate()
        } finally { release.countDown() }
        thread.join(5000)
        assertFalse(thread.isAlive)
        foreground.removeAt(0)()
        assertEmpty(published)
        preparation.start({ "superseded" }) { published += it.getOrThrow() }
        background.removeAt(0)()
        preparation.start({ "latest" }) { published += it.getOrThrow() }
        background.removeAt(0)()
        foreground.forEach { it() }
        assertEquals(listOf("latest"), published)
    }

    fun testInstrumentationExtractionIsReused() {
        val service = EffectInstrumentationService()
        val first = service.ensureInstrumentationScript()
        val written = java.nio.file.Files.getLastModifiedTime(first)
        assertEquals(first, service.ensureInstrumentationScript())
        assertEquals(written, java.nio.file.Files.getLastModifiedTime(first))
        assertEquals(service.instrumentationSource(), java.nio.file.Files.readString(first))
    }

    fun testServiceDisposalExpiresRequestsAndDisposesListenerChild() {
        val service = EffectDebugBridgeService()
        val listener = Disposer.newDisposable("test debug session listener")
        Disposer.register(service, listener)
        val listenerField = EffectDebugBridgeService::class.java.getDeclaredField("sessionDisposable")
        listenerField.isAccessible = true
        listenerField.set(service, listener)
        val requestsField = EffectDebugBridgeService::class.java.getDeclaredField("requests")
        requestsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val requests = requestsField.get(service) as EffectDebugRequests<Any>
        requests.enqueue(Any())
        val token = requests.poll()!!.token
        val evaluation = EffectDebugEvaluation(token)
        Disposer.dispose(service)
        assertTrue(Disposer.isDisposed(listener))
        assertTrue(evaluation.future.isCancelled)
        assertFalse(evaluation.canEvaluate())
        requests.ifCurrent(token) { fail("Disposed service accepted a publication") }
        assertFalse(requests.enqueue(Any()))
        evaluation.close()
    }
}
