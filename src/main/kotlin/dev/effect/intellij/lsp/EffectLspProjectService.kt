package dev.effect.intellij.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.effect.intellij.binary.EffectBinaryService
import dev.effect.intellij.binary.EffectBinaryException
import dev.effect.intellij.core.logger
import dev.effect.intellij.notifications.EffectNotificationService
import dev.effect.intellij.settings.EffectProjectSettingsService
import dev.effect.intellij.status.EffectStatusService

@Service(Service.Level.PROJECT)
class EffectLspProjectService(private val project: Project) {
    private val log = logger<EffectLspProjectService>()
    private val launchConfigurationLock = Any()
    @Volatile
    private var activeLaunchConfiguration: ActiveLaunchConfiguration? = null

    fun createLaunchConfiguration(): LspLaunchConfiguration = buildLaunchConfiguration()

    fun activeLaunchConfiguration(): LspLaunchConfiguration {
        val currentKey = currentLaunchConfigurationKey()
        activeLaunchConfiguration?.takeIf { it.key == currentKey }?.let { return it.configuration }

        return synchronized(launchConfigurationLock) {
            val refreshedKey = currentLaunchConfigurationKey()
            activeLaunchConfiguration?.takeIf { it.key == refreshedKey }?.configuration
                ?: buildLaunchConfiguration().also { configuration ->
                    activeLaunchConfiguration = ActiveLaunchConfiguration(
                        key = refreshedKey,
                        configuration = configuration,
                    )
                }
        }
    }

    fun clearActiveLaunchConfiguration() {
        synchronized(launchConfigurationLock) {
            activeLaunchConfiguration = null
        }
    }

    private fun buildLaunchConfiguration(): LspLaunchConfiguration {
        return try {
            val resolvedSettings = EffectProjectSettingsService.getInstance(project).resolve()
            val resolution = EffectBinaryService.getInstance().ensureAvailable(project)
            val commandLine = GeneralCommandLine(resolution.binaryPath.toString(), "--lsp", "--stdio")
                .withWorkDirectory(project.basePath)
            commandLine.withEnvironment(resolvedSettings.projectSettings.extraEnv)

            LspLaunchConfiguration(
                commandLine = commandLine,
                resolution = resolution,
                environment = resolvedSettings.projectSettings.extraEnv,
                initializationOptions = resolvedSettings.initializationOptions,
                workspaceConfiguration = resolvedSettings.workspaceConfiguration,
            )
        } catch (error: Exception) {
            val message = error.message ?: "Unknown Effect LSP startup failure."
            log.warn("Failed to build Effect LSP launch configuration", error)
            project.getService(EffectStatusService::class.java).markError(message)
            project.getService(EffectNotificationService::class.java).error(project, "Effect LSP startup failed", message)
            when (error) {
                is EffectBinaryException -> throw error
                else -> throw IllegalStateException(message, error)
            }
        }
    }

    fun restart(reason: String) {
        log.info("LSP restart requested: $reason")
        clearActiveLaunchConfiguration()
        EffectStatusService.getInstance(project).requestRestart(reason)
        LspServerManager.getInstance(project).stopAndRestartIfNeeded(EffectLspServerSupportProvider::class.java)
    }

    private fun currentLaunchConfigurationKey(): LaunchConfigurationKey {
        val settings = EffectProjectSettingsService.getInstance(project).currentSettings()
        return LaunchConfigurationKey(
            binaryMode = settings.binaryMode,
            pinnedVersion = settings.pinnedVersion,
            manualBinaryPath = settings.manualBinaryPath,
            extraEnv = settings.extraEnv,
            initializationOptionsJson = settings.initializationOptionsJson,
            workspaceConfigurationJson = settings.workspaceConfigurationJson,
        )
    }
}

private data class ActiveLaunchConfiguration(
    val key: LaunchConfigurationKey,
    val configuration: LspLaunchConfiguration,
)

private data class LaunchConfigurationKey(
    val binaryMode: dev.effect.intellij.settings.EffectBinaryMode,
    val pinnedVersion: String,
    val manualBinaryPath: String,
    val extraEnv: Map<String, String>,
    val initializationOptionsJson: String,
    val workspaceConfigurationJson: String,
)
