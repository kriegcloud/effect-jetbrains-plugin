package dev.effect.intellij.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

object EffectJson {
    val mapper: ObjectMapper = ObjectMapper()

    fun parseObjectOrNull(raw: String): JsonNode? {
        if (raw.isBlank()) {
            return null
        }
        val node = mapper.readTree(raw)
        require(node.isObject) { "Expected a JSON object." }
        return node
    }

    fun emptyObject(): ObjectNode = mapper.createObjectNode()
}
