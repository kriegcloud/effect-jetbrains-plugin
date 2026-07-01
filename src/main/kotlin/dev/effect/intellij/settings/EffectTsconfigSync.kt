package dev.effect.intellij.settings

import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Pure merge logic that writes the plugin's typed Effect language-service options into a
 * `tsconfig.json` `compilerOptions.plugins` `@effect/language-service` entry.
 *
 * This exists because `@effect/tsgo` reads Effect options only from the tsconfig plugin entry
 * (`program.Options().Effect` / `ParseFromPlugins`), not from LSP `initializationOptions` or
 * `workspace/configuration`. The merge is additive: it adds or updates keys that are explicitly set
 * in the IDE settings and never removes keys a user placed in tsconfig by hand.
 */
object EffectTsconfigSync {
    const val EFFECT_PLUGIN_NAME = "@effect/language-service"

    private val reader: JsonMapper = JsonMapper.builder()
        .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
        .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
        .build()

    data class SyncResult(
        val updatedJson: String,
        val changed: Boolean,
        val hadComments: Boolean,
    )

    /**
     * Returns the updated tsconfig text, or `null` when the input is not a JSON object or there are no
     * typed options to write. Comments and exact formatting are not preserved (the file is
     * re-serialized), so callers should warn when [SyncResult.hadComments] is true.
     */
    fun apply(tsconfigJson: String, settings: EffectProjectSettings): SyncResult? {
        if (!settings.hasSyncableOptions()) {
            return null
        }
        val hadComments = containsJsonComment(tsconfigJson)
        val root = runCatching { reader.readTree(tsconfigJson) }.getOrNull() as? ObjectNode ?: return null
        val before = root.toString()

        val compilerOptions = (root.get("compilerOptions") as? ObjectNode) ?: root.putObject("compilerOptions")
        val plugins = (compilerOptions.get("plugins") as? ArrayNode) ?: compilerOptions.putArray("plugins")
        val entry = plugins.firstOrNull { it.isObject && it.path("name").asText() == EFFECT_PLUGIN_NAME } as? ObjectNode
            ?: reader.createObjectNode().put("name", EFFECT_PLUGIN_NAME).also(plugins::add)

        settings.lspInlays?.let { entry.put("inlays", it) }
        settings.lspMermaidProvider.trim().takeIf(String::isNotBlank)?.let { entry.put("mermaidProvider", it) }
        settings.lspNoExternal?.let { entry.put("noExternal", it) }
        settings.lspLayerGraphFollowDepth?.let { entry.put("layerGraphFollowDepth", it) }
        if (settings.lspDiagnosticSeverity.isNotEmpty()) {
            val severity = (entry.get("diagnosticSeverity") as? ObjectNode) ?: entry.putObject("diagnosticSeverity")
            settings.lspDiagnosticSeverity.toSortedMap().forEach { (rule, value) -> severity.put(rule, value) }
        }

        val updated = reader.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n"
        return SyncResult(updatedJson = updated, changed = root.toString() != before, hadComments = hadComments)
    }

    /** Heuristic scan for `//` or block comments outside of JSON string literals. */
    private fun containsJsonComment(text: String): Boolean {
        var index = 0
        var inString = false
        var escaped = false
        while (index < text.length) {
            val c = text[index]
            when {
                inString -> when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }

                c == '"' -> inString = true
                c == '/' && index + 1 < text.length && (text[index + 1] == '/' || text[index + 1] == '*') -> return true
            }
            index++
        }
        return false
    }
}

private fun EffectProjectSettings.hasSyncableOptions(): Boolean =
    lspInlays != null ||
        lspMermaidProvider.isNotBlank() ||
        lspNoExternal != null ||
        lspLayerGraphFollowDepth != null ||
        lspDiagnosticSeverity.isNotEmpty()
