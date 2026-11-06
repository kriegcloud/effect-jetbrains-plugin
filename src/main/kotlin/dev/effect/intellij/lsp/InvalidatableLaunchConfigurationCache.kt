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
        return synchronized(resolutionLock) { resolveCurrent(currentKey, resolve) }
    }

    private fun resolveCurrent(currentKey: () -> K, resolve: () -> V): V {
        while (true) {
            val refreshedKey = currentKey()
            val generation = state.get()
            if (generation.key == refreshedKey && generation.value != null) {
                return generation.value
            }
            val value = resolve()
            // A restart that cleared the cache while this resolution ran makes it stale: never hand an
            // invalidated configuration to the starting process; resolve again for the current settings.
            if (state.compareAndSet(generation, State(refreshedKey, value)) && currentKey() == refreshedKey) {
                return value
            }
        }
    }

    fun clear() {
        state.set(State())
    }
}
