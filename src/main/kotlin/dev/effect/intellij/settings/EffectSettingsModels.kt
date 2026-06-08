package dev.effect.intellij.settings

import com.fasterxml.jackson.databind.JsonNode
import dev.effect.intellij.core.EffectPluginConstants

data class EffectProjectSettings(
    val binaryMode: EffectBinaryMode = EffectBinaryMode.MANUAL,
    val pinnedVersion: String = "",
    val manualBinaryPath: String = "",
    val extraEnv: Map<String, String> = emptyMap(),
    val initializationOptionsJson: String = "",
    val workspaceConfigurationJson: String = "",
    val lspInlays: Boolean? = null,
    val lspMermaidProvider: String = "",
    val lspNoExternal: Boolean? = null,
    val lspLayerGraphFollowDepth: Int? = null,
    val lspDiagnosticSeverity: Map<String, String> = emptyMap(),
    val devToolsPort: Int = EffectPluginConstants.DEFAULT_DEV_TOOLS_PORT,
    val metricsPollIntervalMs: Int = EffectPluginConstants.DEFAULT_METRICS_POLL_INTERVAL_MS,
    val spanStackIgnoreList: List<String> = emptyList(),
    val injectNodeOptions: Boolean = false,
    val injectDebugConfigurationTypes: List<String> = DEFAULT_NODE_DEBUG_CONFIGURATION_TYPES,
)

class EffectProjectSettingsState {
    var binaryMode: String = EffectBinaryMode.MANUAL.name
    var pinnedVersion: String = ""
    var manualBinaryPath: String = ""
    var extraEnv: MutableMap<String, String> = linkedMapOf()
    var initializationOptionsJson: String = ""
    var workspaceConfigurationJson: String = ""
    var lspInlays: String = ""
    var lspMermaidProvider: String = ""
    var lspNoExternal: String = ""
    var lspLayerGraphFollowDepth: String = ""
    var lspDiagnosticSeverity: MutableMap<String, String> = linkedMapOf()
    var devToolsPort: Int = EffectPluginConstants.DEFAULT_DEV_TOOLS_PORT
    var metricsPollIntervalMs: Int = EffectPluginConstants.DEFAULT_METRICS_POLL_INTERVAL_MS
    var spanStackIgnoreList: MutableList<String> = mutableListOf()
    var injectNodeOptions: Boolean = false
    var injectDebugConfigurationTypes: MutableList<String> = DEFAULT_NODE_DEBUG_CONFIGURATION_TYPES.toMutableList()
}

val DEFAULT_NODE_DEBUG_CONFIGURATION_TYPES: List<String> = listOf(
    "Node.js",
    "Node.js Remote Debug",
    "JavaScript Debug",
)

data class EffectApplicationState(
    var binaryCacheDirOverride: String = "",
    var preferredTracerMode: String = "SWING",
    var showAdvancedTracerWhenAvailable: Boolean = false,
)

data class ResolvedEffectSettings(
    val projectSettings: EffectProjectSettings,
    val initializationOptions: JsonNode?,
    val workspaceConfiguration: JsonNode?,
)

enum class SettingSeverity {
    ERROR,
    WARNING,
}

data class SettingProblem(
    val field: String,
    val message: String,
    val severity: SettingSeverity = SettingSeverity.ERROR,
)
