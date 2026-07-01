package dev.effect.intellij.devtools

import dev.effect.intellij.core.EffectJson

/**
 * Serializes a Dev Tools client's captured span tree to a shareable, pretty-printed JSON document.
 * Pure so it can be unit-tested without a live runtime.
 */
object EffectTracerExport {
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
