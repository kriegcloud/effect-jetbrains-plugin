package dev.effect.intellij.devtools

import dev.effect.intellij.core.EffectJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EffectTracerExportTest {
    @Test
    fun tracerExportWorkRunsOffEdtAndWritesValidJson() {
        val directory = java.nio.file.Files.createTempDirectory("effect-export-test")
        val client = RuntimeClientSnapshot("id", "client", "local", Instant.EPOCH, Instant.EPOCH)
        try {
            val file = EffectTracerExport.write(client, directory)
            assertEquals("id", EffectJson.mapper.readTree(java.nio.file.Files.readString(file)).path("client").path("id").asText())
            var refusedEdt = false
            javax.swing.SwingUtilities.invokeAndWait {
                try { EffectTracerExport.write(client, directory) }
                catch (expected: IllegalStateException) { refusedEdt = true }
            }
            assertTrue(refusedEdt)
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun unwritableDestinationReportsWriteFailure() {
        val file = java.nio.file.Files.createTempFile("effect-export-blocked", ".tmp")
        try {
            val client = RuntimeClientSnapshot("id", "client", "local", Instant.EPOCH, Instant.EPOCH)
            try {
                EffectTracerExport.write(client, file)
                throw AssertionError("Expected a write failure when the directory is a regular file")
            } catch (expected: java.io.IOException) {
                assertTrue(expected.message.orEmpty().contains(file.fileName.toString()))
            }
        } finally { java.nio.file.Files.deleteIfExists(file) }
    }

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
