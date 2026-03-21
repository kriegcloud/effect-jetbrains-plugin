package dev.effect.intellij.core

object EffectPluginConstants {
    const val PLUGIN_ID = "dev.effect.jetbrains"
    const val TOOL_WINDOW_ID = "Effect Dev Tools"
    const val SETTINGS_STORAGE_FILE = "effect.intellij.xml"
    const val APPLICATION_STORAGE_FILE = "effect.intellij.application.xml"
    const val DEFAULT_DEV_TOOLS_PORT = 34437
    const val DEFAULT_METRICS_POLL_INTERVAL_MS = 500
    const val DEFAULT_BINARY_CACHE_DIR = "effect-tsgo"
    val SUPPORTED_TYPESCRIPT_EXTENSIONS = setOf("ts", "tsx", "cts", "mts")
}
