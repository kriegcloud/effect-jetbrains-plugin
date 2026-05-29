package dev.effect.intellij.debug

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import dev.effect.intellij.core.EffectFileUtil
import dev.effect.intellij.core.EffectPluginConstants
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Service(Service.Level.APP)
class EffectInstrumentationService {
    private val lock = Any()

    fun ensureInstrumentationScript(): Path =
        synchronized(lock) {
            val target = EffectFileUtil.systemCacheDir(EffectPluginConstants.DEFAULT_BINARY_CACHE_DIR)
                .resolve("instrumentation")
                .resolve("instrumentation.global.js")
            Files.createDirectories(target.parent)
            javaClass.classLoader.getResourceAsStream(INSTRUMENTATION_RESOURCE).use { input ->
                requireNotNull(input) { "Bundled Effect instrumentation resource is missing: $INSTRUMENTATION_RESOURCE" }
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
            }
            target
        }

    fun instrumentationSource(): String =
        javaClass.classLoader.getResourceAsStream(INSTRUMENTATION_RESOURCE).use { input ->
            requireNotNull(input) { "Bundled Effect instrumentation resource is missing: $INSTRUMENTATION_RESOURCE" }
            input.bufferedReader().readText()
        }

    companion object {
        private const val INSTRUMENTATION_RESOURCE = "dev/effect/intellij/instrumentation/instrumentation.global.js"

        fun getInstance(): EffectInstrumentationService =
            ApplicationManager.getApplication().getService(EffectInstrumentationService::class.java)
    }
}
