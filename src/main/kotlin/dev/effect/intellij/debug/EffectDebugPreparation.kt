package dev.effect.intellij.debug

import java.util.concurrent.atomic.AtomicLong

/** Extraction runs off the EDT; detach and newer attachments expire queued completions. */
internal class EffectDebugPreparation(
    private val background: (() -> Unit) -> Unit,
    private val foreground: (() -> Unit) -> Unit,
) {
    private val generation = AtomicLong()

    fun invalidate() { generation.incrementAndGet() }

    fun start(prepare: () -> String, publish: (Result<String>) -> Unit) {
        val token = generation.incrementAndGet()
        background {
            val result = runCatching(prepare)
            foreground { if (generation.get() == token) publish(result) }
        }
    }
}
