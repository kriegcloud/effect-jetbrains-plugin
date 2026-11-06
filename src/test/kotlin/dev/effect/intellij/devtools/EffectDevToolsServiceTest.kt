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

    fun testEndedSpanExposesFailureOutcomeFromExit() {
        val freePort = ServerSocket(0).use { it.localPort }
        project.getService(EffectProjectSettingsService::class.java).updateSettings(
            EffectProjectSettings(devToolsPort = freePort),
        )

        val service = project.getService(EffectDevToolsService::class.java)
        service.startServer()

        val client = createConnectedClient(freePort)

        try {
            client.send(loadFixture("fixtures/devtools/tracer/failing-span.json") + "\n")

            waitForCondition {
                val state = service.currentState()
                val activeClient = state.clients.firstOrNull { it.id == state.activeClientId }
                activeClient != null &&
                    activeClient.rootSpans.size == 1 &&
                    activeClient.rootSpans.first().status.contains("failure")
            }

            val state = service.currentState()
            val span = state.clients.first { it.id == state.activeClientId }.rootSpans.first()
            assertTrue(span.status.startsWith("Ended"))
            assertTrue(span.status.contains("failure: RuntimeException: boom"))
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

    fun testStoppingRuntimeServerClearsStaleErrorState() {
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

            service.stopServer()

            val state = service.currentState()
            assertFalse(state.running)
            assertNull(state.error)
            assertEmpty(state.clients)
        } finally {
            client.closeBlocking()
            service.stopServer()
        }
    }

    fun testRuntimeLifecycleRequestsRunOffEdtAndDoNotOverlap() {
        val service = project.getService(EffectDevToolsService::class.java)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val onEdt = java.util.concurrent.atomic.AtomicBoolean(true)
        val secondStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        service.requestLifecycle {
            onEdt.set(javax.swing.SwingUtilities.isEventDispatchThread())
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            service.requestLifecycle {
                secondStarted.set(true)
                finished.countDown()
            }
            assertFalse(onEdt.get())
            assertFalse(secondStarted.get())
        } finally { release.countDown() }
        assertTrue(finished.await(5, TimeUnit.SECONDS))
    }

    fun testHistogramUsesExactSumIncludingOverflow() = withRuntime { service, client ->
        client.send("""{"_tag":"MetricsSnapshot","metrics":[{"id":"single","type":"Histogram","state":{"sum":9,"count":1,"buckets":[[10,1]]}},{"id":"overflow","type":"Histogram","state":{"sum":1009,"count":2,"buckets":[[10,1],["Infinity",2]]}}]}""" + "\n")
        waitForCondition { service.currentState().clients.firstOrNull()?.metrics?.size == 2 }
        assertEquals(listOf("9 (mean)", "504.5 (mean)"), service.currentState().clients.first().metrics.map { it.summary })
    }

    fun testTraceRetentionIsBoundedAndMetricsReuseTraceSnapshot() = withRuntime { service, client ->
        val publications = java.util.concurrent.atomic.AtomicInteger()
        service.addListener({ publications.incrementAndGet() }, testRootDisposable)
        val count = 10_000
        client.send((0 until count).joinToString("\n", postfix = "\n") { id ->
            """{"_tag":"Span","spanId":"s$id","traceId":"t$id","name":"span$id","status":{"_tag":"Ended","endTime":"1"}}"""
        })
        waitForCondition { service.currentState().clients.firstOrNull()?.droppedSpans == count - EffectDevToolsService.MAX_RETAINED_SPANS }
        val retained = service.currentState().clients.first()
        assertEquals(EffectDevToolsService.MAX_RETAINED_SPANS, retained.rootSpans.size)
        assertEquals(1, publications.get())
        client.send("""{"_tag":"MetricsSnapshot","metrics":[{"id":"counter","type":"Counter","state":{"count":1}}]}""" + "\n")
        waitForCondition { service.currentState().clients.first().metrics.isNotEmpty() }
        assertSame(retained.rootSpans, service.currentState().clients.first().rootSpans)
    }

    fun testOrphanEventsAndPerSpanEventsAreBounded() = withRuntime { service, client ->
        val ids = EffectDevToolsService.MAX_PENDING_EVENT_IDS + 2
        val events = EffectDevToolsService.MAX_EVENTS_PER_SPAN + 3
        client.send((0 until ids).joinToString("\n", postfix = "\n") { id ->
            """{"_tag":"SpanEvent","spanId":"s$id","name":"orphan","startTime":"0"}"""
        } + (0 until events).joinToString("\n", postfix = "\n") { id ->
            """{"_tag":"SpanEvent","spanId":"target","name":"e$id","startTime":"$id"}"""
        } + """{"_tag":"Span","spanId":"target","traceId":"trace","name":"target"}""" + "\n")
        waitForCondition { service.currentState().clients.firstOrNull()?.rootSpans?.isNotEmpty() == true }
        val snapshot = service.currentState().clients.first()
        assertEquals(EffectDevToolsService.MAX_EVENTS_PER_SPAN, snapshot.rootSpans.single().events.size)
        assertEquals("e3", snapshot.rootSpans.single().events.first().name)
        assertEquals(6, snapshot.droppedEvents)
    }

    fun testOversizedUnterminatedLineClosesClient() = withRuntime { service, client ->
        client.send("x".repeat(EffectDevToolsService.MAX_PROTOCOL_LINE_CHARS + 1))
        waitForCondition { service.currentState().error?.contains("protocol line limit") == true }
        waitForCondition { service.currentState().clients.isEmpty() }
        assertTrue(service.currentState().running)
    }

    private fun withRuntime(test: (EffectDevToolsService, WebSocketClient) -> Unit) {
        val port = ServerSocket(0).use { it.localPort }
        project.getService(EffectProjectSettingsService::class.java).updateSettings(
            EffectProjectSettings(devToolsPort = port, metricsPollIntervalMs = 60_000),
        )
        val service = project.getService(EffectDevToolsService::class.java)
        service.startServer()
        val client = createConnectedClient(port)
        try {
            waitForCondition { service.currentState().clients.size == 1 }
            test(service, client)
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
