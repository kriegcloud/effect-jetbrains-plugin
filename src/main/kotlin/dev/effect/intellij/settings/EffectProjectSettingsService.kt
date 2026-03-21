package dev.effect.intellij.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.effect.intellij.core.EffectJson
import dev.effect.intellij.core.EffectPluginConstants
import dev.effect.intellij.status.EffectStatusService
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

@Service(Service.Level.PROJECT)
@State(
    name = "dev.effect.intellij.settings.EffectProjectSettingsService",
    storages = [Storage(value = EffectPluginConstants.SETTINGS_STORAGE_FILE, roamingType = RoamingType.DISABLED)],
    category = SettingsCategory.TOOLS,
)
class EffectProjectSettingsService(private val project: Project) : PersistentStateComponent<EffectProjectSettingsState> {
    private var state = EffectProjectSettingsState()

    override fun getState(): EffectProjectSettingsState = state

    override fun loadState(state: EffectProjectSettingsState) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    fun currentSettings(): EffectProjectSettings = state.toModel()

    fun updateSettings(settings: EffectProjectSettings) {
        val previous = currentSettings()
        state = settings.toState()
        if (previous.lspRelevantView() != settings.lspRelevantView()) {
            project.getService(EffectStatusService::class.java)
                .requestRestart("Effect language server settings changed")
        }
    }

    fun resolve(): ResolvedEffectSettings {
        val settings = currentSettings()
        val problems = validate(settings).filter { it.severity == SettingSeverity.ERROR }
        require(problems.isEmpty()) {
            problems.joinToString(separator = "\n") { problem -> "${problem.field}: ${problem.message}" }
        }

        return ResolvedEffectSettings(
            projectSettings = settings,
            initializationOptions = EffectJson.parseObjectOrNull(settings.initializationOptionsJson),
            workspaceConfiguration = EffectJson.parseObjectOrNull(settings.workspaceConfigurationJson),
        )
    }

    fun validate(settings: EffectProjectSettings = currentSettings()): List<SettingProblem> {
        val problems = mutableListOf<SettingProblem>()

        if (settings.binaryMode == EffectBinaryMode.PINNED && settings.pinnedVersion.isBlank()) {
            problems += SettingProblem("pinnedVersion", "Pinned mode requires a package version.")
        }

        if (settings.binaryMode == EffectBinaryMode.MANUAL) {
            if (settings.manualBinaryPath.isBlank()) {
                problems += SettingProblem("manualBinaryPath", "Manual mode requires an executable path.")
            } else {
                try {
                    val manualPath = Path.of(settings.manualBinaryPath)
                    when {
                        !Files.exists(manualPath) -> problems += SettingProblem("manualBinaryPath", "The manual binary path does not exist.")
                        !Files.isRegularFile(manualPath) -> problems += SettingProblem("manualBinaryPath", "The manual binary path must point to a file.")
                        !Files.isExecutable(manualPath) -> problems += SettingProblem("manualBinaryPath", "The manual binary path must be executable.")
                    }
                } catch (_: InvalidPathException) {
                    problems += SettingProblem("manualBinaryPath", "The manual binary path is not a valid filesystem path.")
                }
            }
        }

        parseEnvProblems(settings.extraEnv, problems)
        validateJson("initializationOptionsJson", settings.initializationOptionsJson, problems)
        validateJson("workspaceConfigurationJson", settings.workspaceConfigurationJson, problems)

        if (settings.devToolsPort !in 1..65535) {
            problems += SettingProblem("devToolsPort", "Dev Tools port must be between 1 and 65535.")
        }
        if (settings.metricsPollIntervalMs !in 50..60_000) {
            problems += SettingProblem("metricsPollIntervalMs", "Metrics poll interval must be between 50ms and 60000ms.")
        }

        return problems
    }

    private fun parseEnvProblems(extraEnv: Map<String, String>, problems: MutableList<SettingProblem>) {
        extraEnv.keys
            .filter { it.isBlank() }
            .forEach { problems += SettingProblem("extraEnv", "Environment variable keys must not be blank.") }
    }

    private fun validateJson(field: String, raw: String, problems: MutableList<SettingProblem>) {
        if (raw.isBlank()) {
            return
        }

        try {
            EffectJson.parseObjectOrNull(raw)
        } catch (error: Exception) {
            problems += SettingProblem(field, "Invalid JSON: ${error.message}")
        }
    }

    companion object {
        fun getInstance(project: Project): EffectProjectSettingsService = project.getService(EffectProjectSettingsService::class.java)
    }
}

private fun EffectProjectSettingsState.toModel(): EffectProjectSettings =
    EffectProjectSettings(
        binaryMode = EffectBinaryMode.valueOf(binaryMode),
        pinnedVersion = pinnedVersion.trim(),
        manualBinaryPath = manualBinaryPath.trim(),
        extraEnv = extraEnv.toMap(),
        initializationOptionsJson = initializationOptionsJson.trim(),
        workspaceConfigurationJson = workspaceConfigurationJson.trim(),
        devToolsPort = devToolsPort,
        metricsPollIntervalMs = metricsPollIntervalMs,
        spanStackIgnoreList = spanStackIgnoreList.map(String::trim).filter(String::isNotBlank),
        injectNodeOptions = injectNodeOptions,
        injectDebugConfigurationTypes = injectDebugConfigurationTypes.map(String::trim).filter(String::isNotBlank),
    )

private fun EffectProjectSettings.toState(): EffectProjectSettingsState =
    EffectProjectSettingsState().also { state ->
        state.binaryMode = binaryMode.name
        state.pinnedVersion = pinnedVersion
        state.manualBinaryPath = manualBinaryPath
        state.extraEnv = extraEnv.toMutableMap()
        state.initializationOptionsJson = initializationOptionsJson
        state.workspaceConfigurationJson = workspaceConfigurationJson
        state.devToolsPort = devToolsPort
        state.metricsPollIntervalMs = metricsPollIntervalMs
        state.spanStackIgnoreList = spanStackIgnoreList.toMutableList()
        state.injectNodeOptions = injectNodeOptions
        state.injectDebugConfigurationTypes = injectDebugConfigurationTypes.toMutableList()
    }

private data class LspRelevantSettingsView(
    val binaryMode: EffectBinaryMode,
    val pinnedVersion: String,
    val manualBinaryPath: String,
    val extraEnv: Map<String, String>,
    val initializationOptionsJson: String,
    val workspaceConfigurationJson: String,
)

private fun EffectProjectSettings.lspRelevantView(): LspRelevantSettingsView =
    LspRelevantSettingsView(
        binaryMode = binaryMode,
        pinnedVersion = pinnedVersion,
        manualBinaryPath = manualBinaryPath,
        extraEnv = extraEnv,
        initializationOptionsJson = initializationOptionsJson,
        workspaceConfigurationJson = workspaceConfigurationJson,
    )
