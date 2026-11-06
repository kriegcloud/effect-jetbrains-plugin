package dev.effect.intellij.devtools

import dev.effect.intellij.core.EffectJson
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities

/**
 * Serializes a Dev Tools client's captured span tree to a shareable, pretty-printed JSON document.
 * Pure so it can be unit-tested without a live runtime.
 */
object EffectTracerExport {
    internal fun write(client: RuntimeClientSnapshot, directory: Path, checkCancelled: () -> Unit = {}): Path {
        check(!SwingUtilities.isEventDispatchThread()) { "Tracer export must run outside the EDT" }
        checkCancelled()
        val json = toJson(client)
        Files.createDirectories(directory)
        checkCancelled()
        val target = Files.createTempFile(directory, "effect-tracer-", ".json")
        try {
            Files.writeString(target, json)
            return target
        } catch (error: Exception) {
            Files.deleteIfExists(target)
            throw error
        }
    }

    fun toJson(client: RuntimeClientSnapshot): String {
        val payload = linkedMapOf<String, Any?>(
            "client" to linkedMapOf(
                "id" to client.id,
                "name" to client.name,
                "remoteAddress" to client.remoteAddress,
                "connectedAt" to client.connectedAt.toString(),
                "lastSeenAt" to client.lastSeenAt.toString(),
            ),
            "rootSpans" to client.rootSpans,
        )
        return EffectJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload)
    }
}
