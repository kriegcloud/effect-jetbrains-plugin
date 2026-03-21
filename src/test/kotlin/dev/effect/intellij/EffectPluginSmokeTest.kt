package dev.effect.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.effect.intellij.debug.EffectDebugBridgeService
import dev.effect.intellij.devtools.EffectDevToolsService
import dev.effect.intellij.lsp.EffectLspProjectService
import dev.effect.intellij.settings.EffectProjectSettingsService
import dev.effect.intellij.status.EffectStatusService
import java.nio.file.Files
import java.nio.file.Path

class EffectPluginSmokeTest : BasePlatformTestCase() {
    fun testProjectServicesAreRegistered() {
        assertNotNull(project.getService(EffectProjectSettingsService::class.java))
        assertNotNull(project.getService(EffectLspProjectService::class.java))
        assertNotNull(project.getService(EffectStatusService::class.java))
        assertNotNull(project.getService(EffectDevToolsService::class.java))
        assertNotNull(project.getService(EffectDebugBridgeService::class.java))
    }

    fun testFixturesExist() {
        val root = Path.of(testDataPath)
        assertTrue(Files.exists(root.resolve("fixtures/lsp/healthy-workspace/src/index.ts")))
        assertTrue(Files.exists(root.resolve("fixtures/devtools/metrics/empty.json")))
        assertTrue(Files.exists(root.resolve("fixtures/debug/context/empty.json")))
    }

    override fun getTestDataPath(): String = Path.of("src", "test", "testData").toAbsolutePath().toString()
}
