package dev.effect.intellij.debug

import com.fasterxml.jackson.databind.JsonNode
import dev.effect.intellij.core.EffectJson

data class EffectDebugValueSnapshot(
    val label: String,
    val type: String? = null,
    val summary: String? = null,
    val children: List<EffectDebugValueSnapshot> = emptyList(),
)

data class EffectDebugContextEntry(
    val tag: String,
    val value: EffectDebugValueSnapshot,
)

data class EffectDebugSpanEntry(
    val name: String,
    val traceId: String? = null,
    val spanId: String? = null,
    val stackIndex: Int? = null,
    val path: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val attributes: List<String> = emptyList(),
)

data class EffectDebugFiberEntry(
    val id: String,
    val isCurrent: Boolean = false,
    val isInterruptible: Boolean = false,
    val isInterrupted: Boolean = false,
    val children: List<String> = emptyList(),
    val startTimeMillis: Long? = null,
    val lifeTimeMillis: Long? = null,
    val stack: List<EffectDebugSpanEntry> = emptyList(),
)

data class EffectDebugBreakpointLocation(
    val path: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)

data class EffectDebugBreakpointsSnapshot(
    val pauseOnDefects: Boolean = false,
    val values: List<EffectDebugValueSnapshot> = emptyList(),
    val location: EffectDebugBreakpointLocation? = null,
)

data class EffectDebugSnapshot(
    val context: List<EffectDebugContextEntry> = emptyList(),
    val spanStack: List<EffectDebugSpanEntry> = emptyList(),
    val fibers: List<EffectDebugFiberEntry> = emptyList(),
    val breakpoints: EffectDebugBreakpointsSnapshot = EffectDebugBreakpointsSnapshot(),
    val message: String? = null,
) {
    companion object {
        fun parse(raw: String): EffectDebugSnapshot {
            val root = EffectJson.mapper.readTree(raw)
            return EffectDebugSnapshot(
                context = root.childArray("context").map(::parseContextEntry),
                spanStack = root.childArray("spanStack").map(::parseSpanEntry),
                fibers = root.childArray("fibers").map(::parseFiberEntry),
                breakpoints = parseBreakpoints(root.get("breakpoints")),
                message = root.stringOrNull("message"),
            )
        }

        private fun parseContextEntry(node: JsonNode): EffectDebugContextEntry =
            EffectDebugContextEntry(
                tag = node.stringOrNull("tag") ?: "<unknown>",
                value = parseValue(node.get("value")),
            )

        private fun parseSpanEntry(node: JsonNode): EffectDebugSpanEntry =
            EffectDebugSpanEntry(
                name = node.stringOrNull("name") ?: "<unnamed>",
                traceId = node.stringOrNull("traceId"),
                spanId = node.stringOrNull("spanId"),
                stackIndex = node.intOrNull("stackIndex"),
                path = node.stringOrNull("path"),
                line = node.intOrNull("line"),
                column = node.intOrNull("column"),
                attributes = node.childArray("attributes").map(::compactJson),
            )

        private fun parseFiberEntry(node: JsonNode): EffectDebugFiberEntry =
            EffectDebugFiberEntry(
                id = node.stringOrNull("id") ?: "<unknown>",
                isCurrent = node.booleanOrFalse("isCurrent"),
                isInterruptible = node.booleanOrFalse("isInterruptible"),
                isInterrupted = node.booleanOrFalse("isInterrupted"),
                children = node.childArray("children").map { it.asText() },
                startTimeMillis = node.longOrNull("startTimeMillis"),
                lifeTimeMillis = node.longOrNull("lifeTimeMillis"),
                stack = node.childArray("stack").map(::parseSpanEntry),
            )

        private fun parseBreakpoints(node: JsonNode?): EffectDebugBreakpointsSnapshot {
            if (node == null || node.isNull) {
                return EffectDebugBreakpointsSnapshot()
            }
            return EffectDebugBreakpointsSnapshot(
                pauseOnDefects = node.booleanOrFalse("pauseOnDefects"),
                values = node.childArray("values").map { parseValue(it.get("value") ?: it) },
                location = parseLocation(node.get("location")),
            )
        }

        private fun parseLocation(node: JsonNode?): EffectDebugBreakpointLocation? {
            if (node == null || node.isNull || node.isMissingNode) {
                return null
            }
            return EffectDebugBreakpointLocation(
                path = node.stringOrNull("path"),
                line = node.intOrNull("line"),
                column = node.intOrNull("column"),
            )
        }

        private fun parseValue(node: JsonNode?): EffectDebugValueSnapshot {
            if (node == null || node.isNull || node.isMissingNode) {
                return EffectDebugValueSnapshot(label = "null", type = "null", summary = "null")
            }
            if (!node.isObject) {
                return EffectDebugValueSnapshot(label = node.asText(), type = node.nodeType.name.lowercase(), summary = node.asText())
            }
            return EffectDebugValueSnapshot(
                label = node.stringOrNull("label") ?: node.stringOrNull("summary") ?: "<value>",
                type = node.stringOrNull("type"),
                summary = node.stringOrNull("summary"),
                children = node.childArray("children").map(::parseValue),
            )
        }

        private fun compactJson(node: JsonNode): String =
            when {
                node.isTextual -> node.asText()
                else -> EffectJson.mapper.writeValueAsString(node)
            }

        private fun JsonNode.childArray(fieldName: String): List<JsonNode> {
            val child = get(fieldName) ?: return emptyList()
            if (!child.isArray) {
                return emptyList()
            }
            val out = mutableListOf<JsonNode>()
            child.elements().forEachRemaining(out::add)
            return out
        }

        private fun JsonNode.stringOrNull(fieldName: String): String? {
            val child = get(fieldName) ?: return null
            return when {
                child.isNull || child.isMissingNode -> null
                child.isTextual -> child.asText()
                child.isNumber || child.isBoolean -> child.asText()
                else -> compactJson(child)
            }
        }

        private fun JsonNode.intOrNull(fieldName: String): Int? {
            val child = get(fieldName) ?: return null
            return if (child.isNumber) child.asInt() else child.asText().toIntOrNull()
        }

        private fun JsonNode.longOrNull(fieldName: String): Long? {
            val child = get(fieldName) ?: return null
            return if (child.isNumber) child.asLong() else child.asText().toLongOrNull()
        }

        private fun JsonNode.booleanOrFalse(fieldName: String): Boolean =
            get(fieldName)?.asBoolean(false) ?: false
    }
}
