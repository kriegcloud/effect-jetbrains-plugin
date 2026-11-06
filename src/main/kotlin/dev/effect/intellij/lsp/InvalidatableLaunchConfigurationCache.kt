package dev.effect.intellij.lsp

import java.util.concurrent.atomic.AtomicReference

/** Invalidation never waits for binary resolution; an old generation cannot repopulate the cache. */
internal class InvalidatableLaunchConfigurationCache<K, V> {
    private class State<K, V>(val key: K? = null, val value: V? = null)

    private val state = AtomicReference(State<K, V>())
    private val resolutionLock = Any()

    fun get(currentKey: () -> K, resolve: () -> V): V {
        val key = currentKey()
        state.get().let { if (it.key == key && it.value != null) return it.value }
        return synchronized(resolutionLock) {
            val refreshedKey = currentKey()
            val generation = state.get()
            if (generation.key == refreshedKey && generation.value != null) {
                generation.value
            } else {
                resolve().also { value ->
                    state.compareAndSet(generation, State(refreshedKey, value))
                }
            }
        }
    }

    fun clear() {
        state.set(State())
    }
}
