package dev.effect.intellij.debug

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** One worker drains the latest request; superseded work cannot evaluate or publish. */
internal class EffectDebugRequests<T> {
    internal class Token {
        private var valid = true
        private val evaluations = mutableSetOf<CompletableFuture<String>>()

        @Synchronized fun invalidate() {
            valid = false
            evaluations.forEach { it.cancel(false) }
            evaluations.clear()
        }

        @Synchronized fun isValid(): Boolean = valid

        @Synchronized fun register(future: CompletableFuture<String>) {
            if (valid) evaluations.add(future) else future.cancel(false)
        }

        @Synchronized fun unregister(future: CompletableFuture<String>) {
            evaluations.remove(future)
        }
    }

    data class Request<T>(val token: Token, val value: T)
    private var current: Token? = null
    private var pending: Request<T>? = null
    private var running = false
    private var disposed = false

    @Synchronized fun enqueue(value: T): Boolean {
        if (disposed) return false
        invalidate()
        val token = Token()
        current = token
        pending = Request(token, value)
        if (running) return false
        running = true
        return true
    }

    @Synchronized fun poll(): Request<T>? {
        val request = pending
        pending = null
        if (request == null) running = false
        return request
    }

    @Synchronized fun invalidate() {
        current?.invalidate()
        current = null
        pending = null
    }

    @Synchronized fun ifCurrent(token: Token, action: () -> Unit) {
        if (!disposed && current === token && token.isValid()) action()
    }

    @Synchronized fun dispose() {
        disposed = true
        invalidate()
    }
}

/** The deadline is checked by the EDT too, even if the waiting worker has not yet timed out. */
internal class EffectDebugEvaluation(
    private val token: EffectDebugRequests.Token,
    timeoutNanos: Long = TimeUnit.SECONDS.toNanos(5),
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    val future = CompletableFuture<String>()
    private val deadline = nanoTime() + timeoutNanos

    init { token.register(future) }

    fun canEvaluate(): Boolean = !future.isDone && token.isValid() && nanoTime() < deadline

    override fun close() {
        future.cancel(false)
        token.unregister(future)
    }
}
