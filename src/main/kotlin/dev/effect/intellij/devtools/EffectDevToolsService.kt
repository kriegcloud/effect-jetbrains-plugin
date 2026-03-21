package dev.effect.intellij.devtools

import com.fasterxml.jackson.databind.JsonNode
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.EventDispatcher
import com.intellij.util.concurrency.AppExecutorUtil
import dev.effect.intellij.core.EffectJson
import dev.effect.intellij.core.EffectPluginConstants
import dev.effect.intellij.core.logger
import dev.effect.intellij.settings.EffectProjectSettingsService
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.text.DecimalFormat
import java.time.Instant
import java.util.EventListener
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

fun interface EffectDevToolsListener : EventListener {
    fun stateChanged(state: DevToolsRuntimeState)
}

@Service(Service.Level.PROJECT)
class EffectDevToolsService(private val project: Project) : Disposable {
    private val log = logger<EffectDevToolsService>()
    private val dispatcher = EventDispatcher.create(EffectDevToolsListener::class.java)
    private val lock = Any()
    private val nextClientId = AtomicInteger(1)
    private val clients = linkedMapOf<String, RuntimeClientState>()
    private val connectionToClientId = linkedMapOf<WebSocket, String>()
    private val messageBuffers = linkedMapOf<WebSocket, StringBuilder>()

    @Volatile
    private var runtimeState = DevToolsRuntimeState(port = EffectPluginConstants.DEFAULT_DEV_TOOLS_PORT)

    @Volatile
    private var activeClientId: String? = null

    @Volatile
    private var running = false

    @Volatile
    private var port = EffectPluginConstants.DEFAULT_DEV_TOOLS_PORT

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var server: RuntimeWebSocketServer? = null

    @Volatile
    private var metricsPollFuture: ScheduledFuture<*>? = null

    private val metricNumberFormat = DecimalFormat("#,##0.##")

    fun currentState(): DevToolsRuntimeState = runtimeState

    fun addListener(listener: EffectDevToolsListener) {
        dispatcher.addListener(listener)
    }

    fun startServer(project: Project = this.project) {
        val settings = EffectProjectSettingsService.getInstance(project).currentSettings()

        synchronized(lock) {
            if (running && port == settings.devToolsPort && server != null) {
                return
            }
        }

        stopServer(project)

        val socketAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), settings.devToolsPort)
        val candidate = RuntimeWebSocketServer(socketAddress)
        candidate.isReuseAddr = true
        candidate.connectionLostTimeout = 15

        synchronized(lock) {
            port = settings.devToolsPort
            running = true
            lastError = null
            server = candidate
            publishLocked()
        }

        try {
            candidate.start()
            scheduleMetricsPolling(settings.metricsPollIntervalMs)
        } catch (error: Exception) {
            setRuntimeError("Failed to start Effect Dev Tools runtime server on port ${settings.devToolsPort}: ${error.message}", shutdown = true)
        }
    }

    fun stopServer(project: Project = this.project) {
        metricsPollFuture?.cancel(false)
        metricsPollFuture = null

        val currentServer = synchronized(lock) {
            val existing = server
            running = false
            server = null
            clients.clear()
            connectionToClientId.clear()
            messageBuffers.clear()
            activeClientId = null
            publishLocked()
            existing
        }

        try {
            currentServer?.stop(500)
        } catch (error: Exception) {
            log.warn("Failed to stop Effect Dev Tools runtime server", error)
        }
    }

    fun restartServer() {
        stopServer()
        startServer()
    }

    fun selectActiveClient(project: Project = this.project, clientId: String?) {
        synchronized(lock) {
            activeClientId = clientId?.takeIf { clients.containsKey(it) }
            publishLocked()
        }
    }

    fun resetMetrics(project: Project = this.project) {
        synchronized(lock) {
            val activeId = activeClientId ?: return
            val client = clients[activeId] ?: return
            client.metrics = emptyList()
            publishLocked()
        }
    }

    fun resetTracer(project: Project = this.project) {
        synchronized(lock) {
            val activeId = activeClientId ?: return
            val client = clients[activeId] ?: return
            client.spans.clear()
            client.pendingEvents.clear()
            publishLocked()
        }
    }

    override fun dispose() {
        stopServer()
    }

    private fun scheduleMetricsPolling(intervalMs: Int) {
        metricsPollFuture?.cancel(false)
        metricsPollFuture = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            {
                try {
                    requestMetrics()
                } catch (error: Exception) {
                    log.warn("Failed to request Effect Dev Tools metrics", error)
                }
            },
            intervalMs.toLong(),
            intervalMs.toLong(),
            TimeUnit.MILLISECONDS,
        )
    }

    private fun requestMetrics() {
        val targets = synchronized(lock) {
            if (!running) {
                return
            }
            val preferred = activeClientId?.let(clients::get)
            when {
                preferred != null -> listOf(preferred.connection)
                else -> clients.values.map(RuntimeClientState::connection)
            }
        }

        targets.forEach { connection ->
            if (connection.isOpen) {
                sendProtocolMessage(connection, """{"_tag":"MetricsRequest"}""")
            }
        }
    }

    private fun onClientOpen(connection: WebSocket) {
        val clientId = "client-${nextClientId.getAndIncrement()}"
        val client = RuntimeClientState(
            id = clientId,
            connection = connection,
            name = "Client #${nextClientId.get() - 1}",
            remoteAddress = connection.remoteSocketAddress?.toString().orEmpty(),
            connectedAt = Instant.now(),
            lastSeenAt = Instant.now(),
        )

        synchronized(lock) {
            clients[clientId] = client
            connectionToClientId[connection] = clientId
            messageBuffers[connection] = StringBuilder()
            if (activeClientId == null) {
                activeClientId = clientId
            }
            publishLocked()
        }
    }

    private fun onClientClosed(connection: WebSocket) {
        synchronized(lock) {
            val clientId = connectionToClientId.remove(connection) ?: return
            clients.remove(clientId)
            messageBuffers.remove(connection)
            if (activeClientId == clientId) {
                activeClientId = clients.keys.firstOrNull()
            }
            publishLocked()
        }
    }

    private fun onServerError(connection: WebSocket?, error: Exception) {
        if (connection == null) {
            setRuntimeError("Effect Dev Tools runtime server error: ${error.message}", shutdown = true)
            return
        }

        val clientId = synchronized(lock) { connectionToClientId[connection] }
        setRuntimeError("Runtime client ${clientId ?: "unknown"} error: ${error.message}", shutdown = false)
    }

    private fun onClientMessage(connection: WebSocket, chunk: String) {
        val clientId = synchronized(lock) { connectionToClientId[connection] } ?: return
        val lines = synchronized(lock) {
            val buffer = messageBuffers.getOrPut(connection) { StringBuilder() }
            buffer.append(chunk)
            collectCompleteLines(buffer)
        }
        lines.forEach { line -> handleProtocolLine(clientId, line) }
    }

    private fun handleProtocolLine(clientId: String, line: String) {
        if (line.isBlank()) {
            return
        }

        val root = try {
            EffectJson.mapper.readTree(line)
        } catch (error: Exception) {
            setRuntimeError("Malformed runtime payload from $clientId: ${error.message}", shutdown = false)
            return
        }

        when (root.path("_tag").asText()) {
            "Ping" -> sendPong(clientId)
            "MetricsSnapshot" -> updateMetrics(clientId, root)
            "Span" -> updateSpan(clientId, root)
            "SpanEvent" -> updateSpanEvent(clientId, root)
            else -> setRuntimeError("Unsupported runtime payload from $clientId: ${root.path("_tag").asText("<missing>")}", shutdown = false)
        }
    }

    private fun sendPong(clientId: String) {
        val connection = synchronized(lock) { clients[clientId]?.connection } ?: return
        if (connection.isOpen) {
            sendProtocolMessage(connection, """{"_tag":"Pong"}""")
        }
    }

    private fun sendProtocolMessage(connection: WebSocket, payload: String) {
        connection.send("$payload\n")
    }

    private fun updateMetrics(clientId: String, root: JsonNode) {
        synchronized(lock) {
            val client = clients[clientId] ?: return
            client.lastSeenAt = Instant.now()
            client.metrics = root.path("metrics").map(::parseMetric)
            publishLocked()
        }
    }

    private fun updateSpan(clientId: String, root: JsonNode) {
        synchronized(lock) {
            val client = clients[clientId] ?: return
            client.lastSeenAt = Instant.now()
            val spanId = root.path("spanId").asText()
            val state = client.spans.getOrPut(spanId) { MutableSpanState(spanId = spanId) }
            state.traceId = root.path("traceId").asText()
            state.name = root.path("name").asText()
            state.sampled = root.path("sampled").asBoolean(false)
            state.status = parseSpanStatus(root.path("status"))

            val attributes = parseEntries(root.path("attributes"), prefix = "attr.")
            val parentNode = root.path("parent")
            val parentValue = if (parentNode.path("_tag").asText() == "Some") parentNode.path("value") else null
            state.parentSpanId = parentValue
                ?.takeIf { it.path("_tag").asText() == "Span" }
                ?.path("spanId")
                ?.asText()
                ?.ifBlank { null }

            val externalParent = parentValue
                ?.takeIf { it.path("_tag").asText() == "ExternalSpan" }
                ?.let { "external:${it.path("spanId").asText()}" }

            state.details = buildList {
                add(RuntimeDetailEntry("traceId", state.traceId))
                add(RuntimeDetailEntry("sampled", state.sampled.toString()))
                add(RuntimeDetailEntry("status", state.status))
                state.parentSpanId?.let { add(RuntimeDetailEntry("parentSpanId", it)) }
                externalParent?.let { add(RuntimeDetailEntry("parent", it)) }
                addAll(attributes)
            }

            client.pendingEvents.remove(spanId)?.let(state.events::addAll)
            rebuildChildLinks(client)
            publishLocked()
        }
    }

    private fun updateSpanEvent(clientId: String, root: JsonNode) {
        synchronized(lock) {
            val client = clients[clientId] ?: return
            client.lastSeenAt = Instant.now()
            val spanId = root.path("spanId").asText()
            val event = RuntimeSpanEventSnapshot(
                name = root.path("name").asText(),
                startTime = root.path("startTime").asText(),
                details = parseEntries(root.path("attributes"), prefix = "attr."),
            )
            val span = client.spans[spanId]
            if (span != null) {
                span.events += event
            } else {
                client.pendingEvents.getOrPut(spanId) { mutableListOf() } += event
            }
            publishLocked()
        }
    }

    private fun parseMetric(node: JsonNode): RuntimeMetricSnapshot {
        val kind = node.path("_tag").asText("Metric")
        val name = node.path("name").asText(kind)
        val description = node.path("description").takeIf { !it.isMissingNode && !it.isNull }?.asText()
        val tags = parseTags(node.path("tags"))
        val unitSuffix = unitSuffix(tags)
        val details = mutableListOf<RuntimeDetailEntry>()
        val summary = when (kind) {
            "Counter" -> {
                "${formattedScalar(node.path("state").path("count"))}$unitSuffix"
            }

            "Gauge" -> {
                "${formattedScalar(node.path("state").path("value"))}$unitSuffix"
            }

            "Histogram" -> {
                val state = node.path("state")
                details += RuntimeDetailEntry("Count", formattedScalar(state.path("count")))
                details += RuntimeDetailEntry("Sum", "${formattedScalar(state.path("sum"))}$unitSuffix")
                details += RuntimeDetailEntry("Min", "${formattedScalar(state.path("min"))}$unitSuffix")
                details += RuntimeDetailEntry("Max", "${formattedScalar(state.path("max"))}$unitSuffix")
                histogramMeanSummary(state.path("buckets"), state.path("count"), unitSuffix)
            }

            "Summary" -> {
                val state = node.path("state")
                state.path("quantiles").forEach { quantile ->
                    val quantileKey = quantile.path(0).takeIf { !it.isMissingNode }?.asDouble()?.let { "p${(it * 100).toInt()}" }
                    if (quantileKey != null) {
                        details += RuntimeDetailEntry(quantileKey, "${optionScalarText(quantile.path(1))}$unitSuffix")
                    }
                }
                details += RuntimeDetailEntry("Count", formattedScalar(state.path("count")))
                details += RuntimeDetailEntry("Sum", "${formattedScalar(state.path("sum"))}$unitSuffix")
                details += RuntimeDetailEntry("Min", "${formattedScalar(state.path("min"))}$unitSuffix")
                details += RuntimeDetailEntry("Max", "${formattedScalar(state.path("max"))}$unitSuffix")
                summaryQuantileDescription(state.path("quantiles"), unitSuffix)
            }

            "Frequency" -> {
                val occurrences = node.path("state").path("occurrences").fields().asSequence().map { it.key to it.value }.toList()
                    .sortedBy { it.first }
                occurrences.forEach { (key, value) ->
                    details += RuntimeDetailEntry(key, "${formattedScalar(value)}$unitSuffix")
                }
                "${occurrences.size} value(s)"
            }

            else -> "Unsupported metric payload"
        }

        return RuntimeMetricSnapshot(
            name = name,
            kind = kind,
            summary = summary,
            description = description,
            tags = tags,
            details = details,
        )
    }

    private fun parseTags(node: JsonNode): List<RuntimeMetricTagSnapshot> =
        when {
            node.isMissingNode || node.isNull -> emptyList()
            node.isArray -> node.mapNotNull { tag ->
                val key = tag.path("key").asText().ifBlank { null } ?: return@mapNotNull null
                RuntimeMetricTagSnapshot(key = key, value = scalarText(tag.path("value")))
            }

            node.isObject -> node.fields().asSequence().map { (key, value) ->
                RuntimeMetricTagSnapshot(key = key, value = scalarText(value))
            }.toList()

            else -> emptyList()
        }

    private fun unitSuffix(tags: List<RuntimeMetricTagSnapshot>): String =
        tags.firstOrNull { it.key == "unit" || it.key == "time_unit" }?.value?.let { " $it" }.orEmpty()

    private fun summaryQuantileDescription(quantiles: JsonNode, unitSuffix: String): String {
        if (!quantiles.isArray || quantiles.size() == 0) {
            return "No quantiles"
        }

        val middleIndex = ceil(quantiles.size() / 2.0).toInt().coerceAtMost(quantiles.size() - 1)
        val middleEntry = quantiles[middleIndex] ?: return "No quantiles"
        val quantile = middleEntry.path(0).takeIf { !it.isMissingNode }?.asDouble()
        val label = quantile?.let { "p${(it * 100).toInt()}" } ?: "quantile"
        return "${optionScalarText(middleEntry.path(1))}$unitSuffix ($label)"
    }

    private fun histogramMeanSummary(buckets: JsonNode, countNode: JsonNode, unitSuffix: String): String {
        val totalCount = countNode.asDouble(0.0)
        if (totalCount <= 0 || !buckets.isArray) {
            return "0$unitSuffix (mean)"
        }

        var previousBoundary = 0.0
        var previousAccumulated: Double? = null
        var multiplied = 0.0

        buckets.forEach { bucket ->
            val boundary = bucket.path(0).doubleValueOrNull()
            val accumulated = bucket.path(1).doubleValueOrNull()
            if (boundary == null || accumulated == null || !boundary.isFinite() || !accumulated.isFinite()) {
                return@forEach
            }

            val bucketCount = if (previousAccumulated == null) accumulated else accumulated - previousAccumulated!!
            val midpoint = (boundary + previousBoundary) / 2
            multiplied += midpoint * bucketCount
            previousBoundary = boundary
            previousAccumulated = accumulated
        }

        val mean = if (multiplied == 0.0) 0.0 else multiplied / totalCount
        return "${formatNumber(mean)}$unitSuffix (mean)"
    }

    private fun parseSpanStatus(node: JsonNode): String =
        when (node.path("_tag").asText()) {
            "Ended" -> "Ended @ ${scalarText(node.path("endTime"))}"
            "Started" -> "Started @ ${scalarText(node.path("startTime"))}"
            else -> "Unknown"
        }

    private fun parseEntries(node: JsonNode, prefix: String = ""): List<RuntimeDetailEntry> =
        when {
            node.isMissingNode || node.isNull -> emptyList()
            node.isArray -> node.mapNotNull { entry ->
                when {
                    entry.isArray && entry.size() >= 2 -> RuntimeDetailEntry("$prefix${scalarText(entry[0])}", scalarText(entry[1]))
                    else -> null
                }
            }

            node.isObject -> parseObject(node, prefix)
            else -> emptyList()
        }

    private fun parseObject(node: JsonNode, prefix: String = ""): List<RuntimeDetailEntry> =
        node.fields().asSequence().map { (key, value) -> RuntimeDetailEntry("$prefix$key", scalarText(value)) }.toList()

    private fun formattedScalar(node: JsonNode?): String =
        node?.doubleValueOrNull()?.let(::formatNumber) ?: scalarText(node)

    private fun optionScalarText(node: JsonNode?): String =
        when (node?.path("_tag")?.asText()) {
            "Some" -> formattedScalar(node.path("value"))
            "None" -> "0"
            else -> formattedScalar(node)
        }

    private fun scalarText(node: JsonNode?): String =
        when {
            node == null || node.isMissingNode || node.isNull -> "null"
            node.isValueNode -> node.asText()
            else -> node.toString()
        }

    private fun JsonNode.doubleValueOrNull(): Double? =
        when {
            isMissingNode || isNull -> null
            isNumber -> asDouble()
            isTextual -> asText().toDoubleOrNull()
            else -> null
        }

    private fun formatNumber(value: Double): String =
        if (value.isFinite()) {
            metricNumberFormat.format(value)
        } else {
            value.toString()
        }

    private fun rebuildChildLinks(client: RuntimeClientState) {
        client.spans.values.forEach { it.children.clear() }
        client.spans.values.forEach { span ->
            val parentId = span.parentSpanId ?: return@forEach
            client.spans[parentId]?.children?.add(span.spanId)
        }
    }

    private fun collectCompleteLines(buffer: StringBuilder): List<String> {
        val lines = mutableListOf<String>()
        var newlineIndex = buffer.indexOf("\n")
        while (newlineIndex >= 0) {
            lines += buffer.substring(0, newlineIndex).trim()
            buffer.delete(0, newlineIndex + 1)
            newlineIndex = buffer.indexOf("\n")
        }
        return lines
    }

    private fun setRuntimeError(message: String, shutdown: Boolean) {
        log.warn(message)
        synchronized(lock) {
            lastError = message
            if (shutdown) {
                running = false
                server = null
            }
            publishLocked()
        }
    }

    private fun publishLocked() {
        val snapshots = clients.values.map { it.toSnapshot() }.sortedBy { it.name }
        val activeId = activeClientId.takeIf { id -> snapshots.any { it.id == id } } ?: snapshots.firstOrNull()?.id
        activeClientId = activeId
        runtimeState = DevToolsRuntimeState(
            running = running,
            port = port,
            error = lastError,
            clients = snapshots,
            activeClientId = activeId,
        )
        dispatcher.multicaster.stateChanged(runtimeState)
    }

    private fun RuntimeClientState.toSnapshot(): RuntimeClientSnapshot =
        RuntimeClientSnapshot(
            id = id,
            name = name,
            remoteAddress = remoteAddress,
            connectedAt = connectedAt,
            lastSeenAt = lastSeenAt,
            metrics = metrics,
            rootSpans = buildRootSpans(this),
        )

    private fun buildRootSpans(client: RuntimeClientState): List<RuntimeSpanSnapshot> =
        client.spans.values
            .filter { it.parentSpanId == null || !client.spans.containsKey(it.parentSpanId) }
            .map { buildSpanSnapshot(client, it.spanId, linkedSetOf()) }
            .sortedBy { it.name }

    private fun buildSpanSnapshot(
        client: RuntimeClientState,
        spanId: String,
        visited: LinkedHashSet<String>,
    ): RuntimeSpanSnapshot {
        val state = client.spans[spanId] ?: return RuntimeSpanSnapshot(
            spanId = spanId,
            traceId = "",
            name = spanId,
            status = "Missing",
            sampled = false,
            details = emptyList(),
            events = emptyList(),
            children = emptyList(),
        )

        if (!visited.add(spanId)) {
            return RuntimeSpanSnapshot(
                spanId = state.spanId,
                traceId = state.traceId,
                name = state.name,
                status = "${state.status} (cycle)",
                sampled = state.sampled,
                details = state.details,
                events = state.events.toList(),
                children = emptyList(),
            )
        }

        val children = state.children
            .map { childId -> buildSpanSnapshot(client, childId, LinkedHashSet(visited)) }
            .sortedBy { it.name }

        return RuntimeSpanSnapshot(
            spanId = state.spanId,
            traceId = state.traceId,
            name = state.name,
            status = state.status,
            sampled = state.sampled,
            details = state.details,
            events = state.events.toList(),
            children = children,
        )
    }

    private inner class RuntimeWebSocketServer(address: InetSocketAddress) : WebSocketServer(address) {
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            onClientOpen(conn)
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            onClientClosed(conn)
        }

        override fun onMessage(conn: WebSocket, message: String) {
            onClientMessage(conn, message)
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            onServerError(conn, ex)
        }

        override fun onStart() {
            log.info("Effect Dev Tools runtime server listening on $address")
        }
    }
}

private data class RuntimeClientState(
    val id: String,
    val connection: WebSocket,
    val name: String,
    val remoteAddress: String,
    val connectedAt: Instant,
    var lastSeenAt: Instant,
    var metrics: List<RuntimeMetricSnapshot> = emptyList(),
    val spans: LinkedHashMap<String, MutableSpanState> = linkedMapOf(),
    val pendingEvents: LinkedHashMap<String, MutableList<RuntimeSpanEventSnapshot>> = linkedMapOf(),
)

private data class MutableSpanState(
    val spanId: String,
    var traceId: String = "",
    var name: String = spanId,
    var sampled: Boolean = false,
    var status: String = "Unknown",
    var parentSpanId: String? = null,
    var details: List<RuntimeDetailEntry> = emptyList(),
    val events: MutableList<RuntimeSpanEventSnapshot> = mutableListOf(),
    val children: LinkedHashSet<String> = linkedSetOf(),
)
