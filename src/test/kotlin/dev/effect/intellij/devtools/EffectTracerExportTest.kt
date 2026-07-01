package dev.effect.intellij.devtools

import dev.effect.intellij.core.EffectJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EffectTracerExportTest {
    @Test
    fun serializesClientSpanTreeToJson() {
        val child = RuntimeSpanSnapshot(
            spanId = "s2",
            traceId = "trace",
            name = "child",
            status = "Ended (failure: RuntimeException: boom) @ 100",
            sampled = true,
            details = listOf(RuntimeDetailEntry("attr.phase", "test")),
            events = emptyList(),
            children = emptyList(),
        )
        val root = RuntimeSpanSnapshot(
            spanId = "s1",
            traceId = "trace",
            name = "root",
            status = "Started @ 0",
            sampled = true,
            details = emptyList(),
            events = emptyList(),
            children = listOf(child),
        )
        val client = RuntimeClientSnapshot(
            id = "c1",
            name = "Client #1",
            remoteAddress = "127.0.0.1:5000",
            connectedAt = Instant.EPOCH,
            lastSeenAt = Instant.EPOCH,
            metrics = emptyList(),
            rootSpans = listOf(root),
        )

        val node = EffectJson.mapper.readTree(EffectTracerExport.toJson(client))
        assertEquals("c1", node.path("client").path("id").asText())
        assertEquals("root", node.path("rootSpans")[0].path("name").asText())
        assertEquals("child", node.path("rootSpans")[0].path("children")[0].path("name").asText())
        assertTrue(node.path("rootSpans")[0].path("children")[0].path("status").asText().contains("failure"))
    }
}
