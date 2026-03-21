package dev.effect.intellij.binary

import dev.effect.intellij.settings.EffectBinaryMode
import java.nio.file.Path

enum class BinarySource {
    MANUAL,
    MANAGED_CACHE,
}

data class BinaryResolution(
    val mode: EffectBinaryMode,
    val version: String?,
    val packageName: String?,
    val binaryPath: Path,
    val source: BinarySource,
    val cacheDirectory: Path?,
)

class EffectBinaryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
