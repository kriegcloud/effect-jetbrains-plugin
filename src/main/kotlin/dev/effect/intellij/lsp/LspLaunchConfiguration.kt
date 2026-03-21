package dev.effect.intellij.lsp

import com.fasterxml.jackson.databind.JsonNode
import com.intellij.execution.configurations.GeneralCommandLine
import dev.effect.intellij.binary.BinaryResolution

data class LspLaunchConfiguration(
    val commandLine: GeneralCommandLine,
    val resolution: BinaryResolution,
    val environment: Map<String, String>,
    val initializationOptions: JsonNode?,
    val workspaceConfiguration: JsonNode?,
)
