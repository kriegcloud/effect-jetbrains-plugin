package dev.effect.intellij.debug

import junit.framework.TestCase

class EffectDebugRequestsTest : TestCase() {
    fun testDebugRefreshCoalescesRequests() {
        val requests = EffectDebugRequests<String>()
        assertTrue(requests.enqueue("frame A"))
        val first = requests.poll()!!
        repeat(100) { assertFalse(requests.enqueue("frame $it")) }
        assertFalse(first.token.isValid())
        assertEquals("frame 99", requests.poll()!!.value)
        assertNull(requests.poll())
        assertTrue(requests.enqueue("next worker"))
    }

    fun testObsoleteDebugRefreshCannotPublish() {
        val requests = EffectDebugRequests<String>()
        requests.enqueue("session A")
        val a = requests.poll()!!
        requests.invalidate() // detach/resume
        requests.enqueue("session B")
        val b = requests.poll()!!
        val publications = mutableListOf<String>()
        requests.ifCurrent(b.token) { publications.add(b.value) }
        requests.ifCurrent(a.token) { publications.add(a.value) }
        assertEquals(listOf("session B"), publications)
        requests.enqueue("new frame")
        requests.ifCurrent(b.token) { fail("Old frame published") }
        requests.invalidate()
        requests.ifCurrent(requests.poll()?.token ?: b.token) { fail("Detached request published") }
    }

    fun testDelayedEvaluationExpiresBeforeEdtDispatch() {
        val token = EffectDebugRequests.Token()
        var now = 0L
        val evaluation = EffectDebugEvaluation(token, timeoutNanos = 5_000_000_000) { now }
        val queuedEdt = { if (evaluation.canEvaluate()) fail("Expired evaluator was called") }
        now = 5_000_000_001
        queuedEdt()
        evaluation.close()
        assertTrue(evaluation.future.isCancelled)
        assertFalse(evaluation.future.complete("late callback"))
    }

    fun testInvalidationAndDisposalCancelPendingEvaluations() {
        val requests = EffectDebugRequests<String>()
        requests.enqueue("session")
        val token = requests.poll()!!.token
        val evaluation = EffectDebugEvaluation(token)
        assertTrue(evaluation.canEvaluate())
        requests.dispose()
        assertTrue(evaluation.future.isCancelled)
        assertFalse(evaluation.canEvaluate())
        requests.ifCurrent(token) { fail("Disposed service published") }
        assertFalse(requests.enqueue("after disposal"))
        assertNull(requests.poll())
        evaluation.close()
    }
}
