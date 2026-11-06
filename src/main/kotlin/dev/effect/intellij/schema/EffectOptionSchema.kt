package dev.effect.intellij.schema

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** Local, advisory validation of the option fragment in the published tsconfig schema.
 * Remote references are deliberately never resolved. This is not a general JSON Schema engine;
 * the IDE's JSON Schema engine handles complete tsconfig validation and completion.
 */
class EffectOptionSchema(private val document: JsonNode) {
    private val options: JsonNode? = findOptions(document)

    fun warnings(value: JsonNode): List<String> {
        val schema = options ?: return listOf("The selected tsgo schema has no Effect language-service option definition.")
        return validate(value, schema, "effect", 0)
    }

    private fun validate(value: JsonNode, schema: JsonNode, path: String, depth: Int): List<String> {
        if (depth > 64) return listOf("$path: schema nesting is too deep to validate.")
        val warnings = mutableListOf<String>()
        schema.get($$"$ref")?.asText()?.let { ref ->
            if (ref.startsWith("#/")) {
                val target = document.at(ref.removePrefix("#"))
                if (!target.isMissingNode) warnings += validate(value, target, path, depth + 1)
            }
        }
        schema.get("allOf")?.forEach { warnings += validate(value, it, path, depth + 1) }
        for (keyword in listOf("anyOf", "oneOf")) {
            schema.get(keyword)?.let { alternatives ->
                if (alternatives.none { validate(value, it, path, depth + 1).isEmpty() }) {
                    warnings += "$path: value does not match any allowed schema alternative."
                }
            }
        }
        val type = schema.get("type")
        if (type != null && (if (type.isArray) type.none { matchesType(value, it.asText()) } else !matchesType(value, type.asText()))) {
            return warnings + "$path: expected $type."
        }
        schema.get("enum")?.let { if (it.none { allowed -> allowed == value }) warnings += "$path: expected one of $it." }
        schema.get("const")?.let { if (it != value) warnings += "$path: expected $it." }
        schema.get("not")?.let { if (validate(value, it, path, depth + 1).isEmpty()) warnings += "$path: value is excluded by the schema." }
        if (value.isObject) {
            val properties = schema.path("properties")
            val additional = schema.get("additionalProperties")
            value.properties().forEach { (key, child) ->
                val definition = properties.get(key)
                when {
                    definition != null -> warnings += validate(child, definition, "$path.$key", depth + 1)
                    additional?.isBoolean == true && !additional.asBoolean() -> warnings += "$path.$key: unknown option."
                    additional?.isObject == true -> warnings += validate(child, additional, "$path.$key", depth + 1)
                }
            }
            schema.get("required")?.forEach { if (!value.has(it.asText())) warnings += "$path.${it.asText()}: required option is missing." }
        }
        if (value.isArray) {
            schema.get("items")?.takeIf { it.isObject }?.let { item ->
                value.forEachIndexed { index, child -> warnings += validate(child, item, "$path[$index]", depth + 1) }
            }
            if (schema.path("uniqueItems").asBoolean() && value.toList().distinct().size != value.size()) {
                warnings += "$path: array items must be unique."
            }
        }
        if (value.isNumber) {
            schema.get("minimum")?.let { if (value.decimalValue() < it.decimalValue()) warnings += "$path: must be at least $it." }
            schema.get("maximum")?.let { if (value.decimalValue() > it.decimalValue()) warnings += "$path: must be at most $it." }
        }
        return warnings.distinct()
    }

    private fun matchesType(value: JsonNode, type: String): Boolean = when (type) {
        "object" -> value.isObject
        "array" -> value.isArray
        "string" -> value.isTextual
        "boolean" -> value.isBoolean
        "integer" -> value.isNumber && value.decimalValue().stripTrailingZeros().scale() <= 0
        "number" -> value.isNumber
        "null" -> value.isNull
        else -> true
    }

    private fun findOptions(node: JsonNode): JsonNode? {
        if (node.isObject && node.path("properties").path("name").path("const").asText() == PLUGIN_NAME) {
            // The escape hatches carry options without the tsconfig plugin discriminator.
            return node.deepCopy<ObjectNode>().also { it.remove("required") }
        }
        if (node.isContainerNode) node.forEach { child -> findOptions(child)?.let { return it } }
        return null
    }

    companion object {
        private val jsonc = com.fasterxml.jackson.databind.json.JsonMapper.builder()
            .enable(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build()

        fun parseOptions(text: String): JsonNode = jsonc.readTree(text)

        const val PLUGIN_NAME = "@effect/language-service"
        const val RESOURCE = "/schemas/tsgo-0.67.4.schema.json"
    }
}
