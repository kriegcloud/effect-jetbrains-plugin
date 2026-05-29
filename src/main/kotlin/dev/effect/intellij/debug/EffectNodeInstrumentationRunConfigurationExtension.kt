package dev.effect.intellij.debug

import com.intellij.execution.ExecutionException
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.target.value.TargetValue
import com.intellij.javascript.nodejs.execution.AbstractNodeTargetRunProfile
import com.intellij.javascript.nodejs.execution.NodeTargetRun
import com.intellij.javascript.nodejs.execution.runConfiguration.AbstractNodeRunConfigurationExtension
import com.intellij.javascript.nodejs.execution.runConfiguration.NodeRunConfigurationLaunchSession
import dev.effect.intellij.settings.EffectProjectSettingsService

class EffectNodeInstrumentationRunConfigurationExtension : AbstractNodeRunConfigurationExtension() {
    override fun isApplicableFor(configuration: AbstractNodeTargetRunProfile): Boolean = true

    override fun shouldExtendMainEditor(): Boolean = false

    override fun getEditorTitle(): String = "Effect"

    @Throws(ExecutionException::class)
    override fun createLaunchSession(
        configuration: AbstractNodeTargetRunProfile,
        environment: ExecutionEnvironment,
    ): NodeRunConfigurationLaunchSession? {
        val settings = EffectProjectSettingsService.getInstance(configuration.project).currentSettings()
        if (!settings.injectNodeOptions || !matchesConfiguredType(configuration, settings.injectDebugConfigurationTypes)) {
            return null
        }

        return object : NodeRunConfigurationLaunchSession() {
            @Throws(ExecutionException::class)
            override fun addNodeOptionsTo(targetRun: NodeTargetRun) {
                val scriptPath = EffectInstrumentationService.getInstance()
                    .ensureInstrumentationScript()
                    .toString()
                targetRun.addNodeOptions(
                    false,
                    TargetValue.fixed("--require"),
                    TargetValue.fixed(scriptPath),
                )
            }
        }
    }

    private fun matchesConfiguredType(
        configuration: AbstractNodeTargetRunProfile,
        configuredTypes: List<String>,
    ): Boolean {
        val normalizedConfiguredTypes = configuredTypes
            .map { it.trim().lowercase() }
            .filter(String::isNotBlank)
            .toSet()
        if (normalizedConfiguredTypes.isEmpty() || "*" in normalizedConfiguredTypes) {
            return true
        }

        val factory = configuration.factory ?: return false
        val type = factory.type
        val candidates = setOfNotNull(
            type.id,
            type.displayName,
            factory.id,
            factory.name,
            configuration.javaClass.simpleName,
        ).map { it.lowercase() }

        return candidates.any { candidate -> candidate in normalizedConfiguredTypes }
    }
}
