package dev.effect.intellij.devtools

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.effect.intellij.core.EffectJson
import dev.effect.intellij.settings.EffectProjectSettings
import dev.effect.intellij.settings.EffectProjectSettingsService
import junit.framework.AssertionFailedError
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.nio.file.Files
import java.nio.file.Path
import java.net.ServerSocket
import java.net.URI
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EffectDevToolsServiceTest : BasePlatformTestCase() {
    fun testRuntimeServerTracksClientMetricsAndSpansWithReferenceProtocolPayloads() {
        val freePort = ServerSocket(0).use { it.localPort }
        project.getService(EffectProjectSettingsService::class.java).updateSettings(
            EffectProjectSettings(devToolsPort = freePort),
        )

        val service = project.getService(EffectDevToolsService::class.java)
        service.startServer()

        val client = createConnectedClient(freePort)

        try {
            client.send(loadFixture("fixtures/devtools/metrics/reference.json") + "\n")
            client.send(loadFixture("fixtures/devtools/tracer/empty.json") + "\n")
            client.send("""{"_tag":"SpanEvent","traceId":"trace","spanId":"root","name":"started","startTime":"2","attributes":{"phase":"test"}}""" + "\n")

            waitForCondition {
                val state = service.currentState()
                val activeClient = state.clients.firstOrNull { it.id == state.activeClientId }
                activeClient != null &&
                    activeClient.metrics.size == 5 &&
                    activeClient.rootSpans.size == 1 &&
                    activeClient.rootSpans.first().events.size == 1
            }

            val state = service.currentState()
            val activeClient = state.clients.first { it.id == state.activeClientId }
            val requestCount = activeClient.metrics.first { it.name == "requests_total" }
            val latency = activeClient.metrics.first { it.name == "request_latency" }
            val summary = activeClient.metrics.first { it.name == "db_latency" }
            val frequency = activeClient.metrics.first { it.name == "request_outcomes" }

            assertEquals("12 requests", requestCount.summary)
            assertEquals(listOf("service"), requestCount.tagsWithoutUnit.map(RuntimeMetricTagSnapshot::key))
            assertEquals("17 ms (mean)", latency.summary)
            assertEquals("20 ms (p90)", summary.summary)
            assertEquals(listOf("error" to "1", "ok" to "9"), frequency.details.map { it.key to it.value })
            assertEquals("root", activeClient.rootSpans.first().name)
        } finally {
            client.closeBlocking()
            service.stopServer()
        }
    }

    fun testRuntimeServerPollsMetricsWithNewlineDelimitedMessages() {
        val freePort = ServerSocket(0).use { it.localPort }
        project.getService(EffectProjectSettingsService::class.java).updateSettings(
            EffectProjectSettings(
                devToolsPort = freePort,
                metricsPollIntervalMs = 50,
            ),
        )

        val service = project.getService(EffectDevToolsService::class.java)
        service.startServer()

        val messageLatch = CountDownLatch(1)
        val messages = Collections.synchronizedList(mutableListOf<String>())
        val client = createConnectedClient(
            freePort,
            onMessage = { message ->
                messages += message
                messageLatch.countDown()
            },
        )

        try {
            assertTrue(messageLatch.await(5, TimeUnit.SECONDS))
            assertEquals("{\"_tag\":\"MetricsRequest\"}\n", messages.first())
        } finally {
            client.closeBlocking()
            service.stopServer()
        }
    }

    fun testRuntimeServerRepliesToPingWithNewlineDelimitedPong() {
        val freePort = ServerSocket(0).use { it.localPort }
        project.getService(EffectProjectSettingsService::class.java).updateSettings(
            EffectProjectSettings(
                devToolsPort = freePort,
                metricsPollIntervalMs = 60_000,
            ),
        )

        val service = project.getService(EffectDevToolsService::class.java)
        service.startServer()

        val messageLatch = CountDownLatch(1)
        val messages = Collections.synchronizedList(mutableListOf<String>())
        val client = createConnectedClient(
            freePort,
            onMessage = { message ->
                messages += message
                messageLatch.countDown()
            },
        )

        try {
            client.send("""{"_tag":"Ping"}""" + "\n")
            assertTrue(messageLatch.await(5, TimeUnit.SECONDS))
            assertEquals("{\"_tag\":\"Pong\"}\n", messages.first())
        } finally {
            client.closeBlocking()
            service.stopServer()
        }
    }

    fun testRuntimeServerReportsMalformedPayloadsWithoutShuttingDown() {
        val freePort = ServerSocket(0).use { it.localPort }
        project.getService(EffectProjectSettingsService::class.java).updateSettings(
            EffectProjectSettings(devToolsPort = freePort),
        )

        val service = project.getService(EffectDevToolsService::class.java)
        service.startServer()

        val client = createConnectedClient(freePort)

        try {
            client.send("""{"_tag":"MetricsSnapshot","metrics":[""" + "\n")

            waitForCondition {
                service.currentState().error?.contains("Malformed runtime payload") == true
            }

            val state = service.currentState()
            assertTrue(state.running)
            assertNotNull(state.error)
        } finally {
            client.closeBlocking()
            service.stopServer()
        }
    }

    override fun getTestDataPath(): String = Path.of("src", "test", "testData").toAbsolutePath().toString()

    private fun waitForCondition(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition()) {
                return
            }
            Thread.sleep(50)
        }
        fail("Condition was not met within ${timeoutMs}ms")
    }

    private fun loadFixture(relativePath: String): String =
        EffectJson.mapper.readTree(Files.readString(Path.of(testDataPath).resolve(relativePath))).toString()

    private fun createClient(
        port: Int,
        connected: CountDownLatch,
        onMessage: (String) -> Unit = {},
    ): WebSocketClient =
        object : WebSocketClient(URI("ws://127.0.0.1:$port")) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                connected.countDown()
            }

            override fun onMessage(message: String?) {
                message?.let(onMessage)
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) = Unit

            override fun onError(ex: Exception?) = Unit
        }

    private fun createConnectedClient(
        port: Int,
        onMessage: (String) -> Unit = {},
    ): WebSocketClient {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val connected = CountDownLatch(1)
            val client = createClient(
                port = port,
                connected = connected,
                onMessage = onMessage,
            )
            try {
                if (client.connectBlocking(1, TimeUnit.SECONDS) && connected.await(1, TimeUnit.SECONDS)) {
                    return client
                }
            } finally {
                if (!client.isOpen) {
                    client.closeBlocking()
                }
            }
            Thread.sleep(100)
        }
        throw AssertionFailedError("Failed to connect runtime test client within 5000ms")
    }
}
