package dev.effect.intellij.devtools

import java.time.Instant

data class RuntimeDetailEntry(
    val key: String,
    val value: String,
) {
    override fun toString(): String = "$key: $value"
}

data class RuntimeMetricTagSnapshot(
    val key: String,
    val value: String,
) {
    override fun toString(): String = "$key: $value"
}

data class RuntimeMetricSnapshot(
    val name: String,
    val kind: String,
    val summary: String,
    val description: String?,
    val tags: List<RuntimeMetricTagSnapshot>,
    val details: List<RuntimeDetailEntry>,
) {
    val tagsWithoutUnit: List<RuntimeMetricTagSnapshot>
        get() = tags.filterNot { it.key == "unit" || it.key == "time_unit" }

    val unitSuffix: String
        get() = tags.firstOrNull { it.key == "unit" || it.key == "time_unit" }?.value?.let { " $it" }.orEmpty()

    override fun toString(): String =
        if (summary.isBlank()) {
            name
        } else {
            "$name - $summary"
        }
}

data class RuntimeSpanEventSnapshot(
    val name: String,
    val startTime: String,
    val details: List<RuntimeDetailEntry>,
) {
    override fun toString(): String = name
}

data class RuntimeSpanSnapshot(
    val spanId: String,
    val traceId: String,
    val name: String,
    val status: String,
    val sampled: Boolean,
    val details: List<RuntimeDetailEntry>,
    val events: List<RuntimeSpanEventSnapshot>,
    val children: List<RuntimeSpanSnapshot>,
) {
    override fun toString(): String = name.ifBlank { spanId }
}

data class DevToolsRuntimeState(
    val running: Boolean = false,
    val port: Int = 0,
    val error: String? = null,
    val clients: List<RuntimeClientSnapshot> = emptyList(),
    val activeClientId: String? = null,
)

data class RuntimeClientSnapshot(
    val id: String,
    val name: String,
    val remoteAddress: String,
    val connectedAt: Instant,
    val lastSeenAt: Instant,
    val metrics: List<RuntimeMetricSnapshot> = emptyList(),
    val rootSpans: List<RuntimeSpanSnapshot> = emptyList(),
) {
    val metricsSummary: String?
        get() = metrics.takeIf { it.isNotEmpty() }?.size?.let { "$it metric(s)" }

    val tracerSummary: String?
        get() = rootSpans.takeIf { it.isNotEmpty() }?.size?.let { "$it span root(s)" }
}
