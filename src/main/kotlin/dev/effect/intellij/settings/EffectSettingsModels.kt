package dev.effect.intellij.settings

import com.fasterxml.jackson.databind.JsonNode
import dev.effect.intellij.core.EffectPluginConstants

data class EffectProjectSettings(
    val binaryMode: EffectBinaryMode = EffectBinaryMode.LATEST,
    val pinnedVersion: String = "",
    val manualBinaryPath: String = "",
    val extraEnv: Map<String, String> = emptyMap(),
    val initializationOptionsJson: String = "",
    val workspaceConfigurationJson: String = "",
    val devToolsPort: Int = EffectPluginConstants.DEFAULT_DEV_TOOLS_PORT,
    val metricsPollIntervalMs: Int = EffectPluginConstants.DEFAULT_METRICS_POLL_INTERVAL_MS,
    val spanStackIgnoreList: List<String> = emptyList(),
    val injectNodeOptions: Boolean = false,
    val injectDebugConfigurationTypes: List<String> = listOf("Node.js"),
)

class EffectProjectSettingsState {
    var binaryMode: String = EffectBinaryMode.LATEST.name
    var pinnedVersion: String = ""
    var manualBinaryPath: String = ""
    var extraEnv: MutableMap<String, String> = linkedMapOf()
    var initializationOptionsJson: String = ""
    var workspaceConfigurationJson: String = ""
    var devToolsPort: Int = EffectPluginConstants.DEFAULT_DEV_TOOLS_PORT
    var metricsPollIntervalMs: Int = EffectPluginConstants.DEFAULT_METRICS_POLL_INTERVAL_MS
    var spanStackIgnoreList: MutableList<String> = mutableListOf()
    var injectNodeOptions: Boolean = false
    var injectDebugConfigurationTypes: MutableList<String> = mutableListOf("Node.js")
}

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
