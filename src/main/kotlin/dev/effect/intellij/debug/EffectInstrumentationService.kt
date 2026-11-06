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
    private var extracted: Path? = null
    private val source: String by lazy { loadInstrumentationSource() }

    fun ensureInstrumentationScript(): Path =
        synchronized(lock) {
            extracted?.takeIf(Files::isRegularFile)?.let { return@synchronized it }
            val target = EffectFileUtil.systemCacheDir(EffectPluginConstants.DEFAULT_BINARY_CACHE_DIR)
                .resolve("instrumentation")
                .resolve("instrumentation.global.js")
            Files.createDirectories(target.parent)
            javaClass.classLoader.getResourceAsStream(INSTRUMENTATION_RESOURCE).use { input ->
                requireNotNull(input) { "Bundled Effect instrumentation resource is missing: $INSTRUMENTATION_RESOURCE" }
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
            }
            extracted = target
            target
        }

    fun instrumentationSource(): String = source

    private fun loadInstrumentationSource(): String =
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
