package dev.effect.intellij.debug

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class EffectDebugBridgeLifecycleTest : BasePlatformTestCase() {
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
