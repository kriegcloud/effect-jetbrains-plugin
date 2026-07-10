package dev.effect.intellij.settings

import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonElementGenerator
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import com.intellij.json.psi.JsonPsiUtil
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.json.psi.JsonValue
import com.intellij.psi.util.PsiTreeUtil

/**
 * Targeted JSON PSI merge for the Effect language-service entry in `tsconfig.json`.
 *
 * `@effect/tsgo` reads these options from `compilerOptions.plugins`, rather than LSP
 * `initializationOptions` or `workspace/configuration`. Updating individual PSI nodes keeps JSONC
 * comments, whitespace, unrelated compiler options, other plugins, and manually managed Effect
 * keys intact.
 */
object EffectTsconfigSync {
    const val EFFECT_PLUGIN_NAME = "@effect/language-service"

    data class SyncResult(
        val changed: Boolean,
        val errorMessage: String? = null,
    )

    /**
     * Adds or updates explicitly configured typed options. A `null` result means that the settings
     * contain no typed options to sync. Invalid JSON or incompatible tsconfig structures are
     * reported without changing the file.
     *
     * This method mutates PSI and must run inside a write action.
     */
    fun apply(tsconfigFile: JsonFile, settings: EffectProjectSettings): SyncResult? {
        if (!settings.hasSyncableOptions()) {
            return null
        }
        if (PsiTreeUtil.hasErrorElements(tsconfigFile)) {
            return SyncResult(changed = false, errorMessage = "tsconfig.json contains invalid JSON.")
        }

        val root = tsconfigFile.topLevelValue as? JsonObject
            ?: return SyncResult(changed = false, errorMessage = "tsconfig.json must contain a JSON object.")
        validateExistingStructure(root, settings)?.let { problem ->
            return SyncResult(changed = false, errorMessage = problem)
        }
        val editor = PsiEditor(root)

        return try {
            val compilerOptions = editor.objectProperty(root, "compilerOptions")
            val plugins = editor.arrayProperty(compilerOptions, "plugins")
            val entry = plugins.valueList
                .filterIsInstance<JsonObject>()
                .firstOrNull { candidate ->
                    (candidate.findProperty("name")?.value as? JsonStringLiteral)?.value == EFFECT_PLUGIN_NAME
                }
                ?: editor.appendObject(
                    plugins,
                    editor.generator.createObject(
                        "\"name\": ${editor.stringLiteral(EFFECT_PLUGIN_NAME)}",
                    ),
                )

            settings.lspInlays?.let { editor.setValue(entry, "inlays", it.toString()) }
            settings.lspMermaidProvider.trim().takeIf(String::isNotBlank)?.let { provider ->
                editor.setValue(entry, "mermaidProvider", editor.stringLiteral(provider))
            }
            settings.lspNoExternal?.let { editor.setValue(entry, "noExternal", it.toString()) }
            settings.lspLayerGraphFollowDepth?.let { editor.setValue(entry, "layerGraphFollowDepth", it.toString()) }
            if (settings.lspDiagnosticSeverity.isNotEmpty()) {
                val severity = editor.objectProperty(entry, "diagnosticSeverity")
                settings.lspDiagnosticSeverity.toSortedMap().forEach { (rule, value) ->
                    editor.setValue(severity, rule, editor.stringLiteral(value))
                }
            }

            SyncResult(changed = editor.changed)
        } catch (error: InvalidTsconfigStructure) {
            SyncResult(changed = false, errorMessage = error.message)
        }
    }

    private fun validateExistingStructure(root: JsonObject, settings: EffectProjectSettings): String? {
        val compilerOptionsValue = root.findProperty("compilerOptions")?.value ?: return null
        val compilerOptions = compilerOptionsValue as? JsonObject
            ?: return "tsconfig.json 'compilerOptions' must be a JSON object."
        val pluginsValue = compilerOptions.findProperty("plugins")?.value ?: return null
        val plugins = pluginsValue as? JsonArray
            ?: return "tsconfig.json 'plugins' must be a JSON array."
        if (settings.lspDiagnosticSeverity.isEmpty()) {
            return null
        }
        val entry = plugins.valueList
            .filterIsInstance<JsonObject>()
            .firstOrNull { candidate ->
                (candidate.findProperty("name")?.value as? JsonStringLiteral)?.value == EFFECT_PLUGIN_NAME
            }
            ?: return null
        val diagnosticSeverity = entry.findProperty("diagnosticSeverity")?.value ?: return null
        return if (diagnosticSeverity is JsonObject) {
            null
        } else {
            "tsconfig.json 'diagnosticSeverity' must be a JSON object."
        }
    }

    private class PsiEditor(private val root: JsonObject) {
        val generator = JsonElementGenerator(root.project)
        var changed: Boolean = false
            private set

        fun stringLiteral(value: String): String = generator.createStringLiteral(value).text

        fun objectProperty(parent: JsonObject, name: String): JsonObject {
            val existing = parent.findProperty(name)
            if (existing == null) {
                return (addProperty(parent, name, "{}").value as JsonObject)
            }
            return existing.value as? JsonObject
                ?: throw InvalidTsconfigStructure("tsconfig.json '$name' must be a JSON object.")
        }

        fun arrayProperty(parent: JsonObject, name: String): JsonArray {
            val existing = parent.findProperty(name)
            if (existing == null) {
                return (addProperty(parent, name, "[]").value as JsonArray)
            }
            return existing.value as? JsonArray
                ?: throw InvalidTsconfigStructure("tsconfig.json '$name' must be a JSON array.")
        }

        fun appendObject(parent: JsonArray, value: JsonObject): JsonObject {
            val lastValue = parent.valueList.lastOrNull()
            val inserted = if (lastValue == null) {
                parent.addAfter(value, parent.firstChild)
            } else {
                parent.addAfter(value, lastValue).also { added ->
                    parent.addBefore(generator.createComma(), added)
                }
            } as JsonObject
            changed = true
            return inserted
        }

        fun setValue(parent: JsonObject, name: String, valueText: String) {
            val existing = parent.findProperty(name)
            if (existing == null) {
                addProperty(parent, name, valueText)
                return
            }

            val currentValue = existing.value
            if (currentValue?.text == valueText) {
                return
            }
            currentValue?.replace(generator.createValue<JsonValue>(valueText))
                ?: existing.replace(generator.createProperty(name, valueText))
            changed = true
        }

        private fun addProperty(parent: JsonObject, name: String, valueText: String): JsonProperty {
            changed = true
            return JsonPsiUtil.addProperty(parent, generator.createProperty(name, valueText), false) as JsonProperty
        }
    }

    private class InvalidTsconfigStructure(message: String) : IllegalArgumentException(message)
}

private fun EffectProjectSettings.hasSyncableOptions(): Boolean =
    lspInlays != null ||
        lspMermaidProvider.isNotBlank() ||
        lspNoExternal != null ||
        lspLayerGraphFollowDepth != null ||
        lspDiagnosticSeverity.isNotEmpty()
