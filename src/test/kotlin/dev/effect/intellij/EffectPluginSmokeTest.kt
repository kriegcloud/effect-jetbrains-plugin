package dev.effect.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.effect.intellij.core.EffectPluginConstants
import dev.effect.intellij.debug.EffectDebugBridgeService
import dev.effect.intellij.debug.EffectDebugSnapshot
import dev.effect.intellij.debug.EffectInstrumentationService
import dev.effect.intellij.devtools.EffectDevToolsService
import dev.effect.intellij.lsp.EffectLayerMermaidService
import dev.effect.intellij.lsp.EffectLspProjectService
import dev.effect.intellij.settings.EffectProjectSettingsService
import dev.effect.intellij.status.EffectStatusService
import java.nio.file.Files
import java.nio.file.Path

class EffectPluginSmokeTest : BasePlatformTestCase() {
    fun testProjectServicesAreRegistered() {
        assertNotNull(project.getService(EffectProjectSettingsService::class.java))
        assertNotNull(project.getService(EffectLayerMermaidService::class.java))
        assertNotNull(project.getService(EffectLspProjectService::class.java))
        assertNotNull(project.getService(EffectStatusService::class.java))
        assertNotNull(project.getService(EffectDevToolsService::class.java))
        assertNotNull(project.getService(EffectDebugBridgeService::class.java))
        assertNotNull(EffectInstrumentationService.getInstance())
    }

    fun testFixturesExist() {
        val root = Path.of(testDataPath)
        assertTrue(Files.exists(root.resolve("fixtures/lsp/healthy-workspace/src/index.ts")))
        assertTrue(Files.exists(root.resolve("fixtures/devtools/metrics/empty.json")))
        assertTrue(Files.exists(root.resolve("fixtures/debug/context/empty.json")))
    }

    fun testSupportedScriptExtensionsMatchZedCoveragePlusNodeVariants() {
        assertTrue(EffectPluginConstants.SUPPORTED_TYPESCRIPT_EXTENSIONS.containsAll(listOf("ts", "tsx", "js", "jsx")))
        assertTrue(EffectPluginConstants.SUPPORTED_TYPESCRIPT_EXTENSIONS.containsAll(listOf("cts", "mts", "cjs", "mjs")))
    }

    fun testBundledInstrumentationCanBeMaterialized() {
        val instrumentationPath = EffectInstrumentationService.getInstance().ensureInstrumentationScript()

        assertTrue(Files.exists(instrumentationPath))
        val source = Files.readString(instrumentationPath)
        assertTrue(source.contains("effect/devtools/instrumentation"))
        assertTrue(source.contains("getFiberCurrentContextSnapshot"))
        assertTrue(source.contains("getAliveFibersSnapshot"))
    }

    fun testDebugSnapshotParserHandlesInstrumentationShape() {
        val snapshot = EffectDebugSnapshot.parse(
            """
            {
              "context": [
                {
                  "tag": "Database",
                  "value": {
                    "label": "Live",
                    "type": "Layer",
                    "summary": "Live { service }",
                    "children": [
                      { "label": "service", "type": "object", "summary": "object" }
                    ]
                  }
                }
              ],
              "spanStack": [
                {
                  "name": "HTTP GET",
                  "traceId": "trace",
                  "spanId": "span",
                  "stackIndex": 0,
                  "path": "/tmp/app.ts",
                  "line": 4,
                  "column": 8,
                  "attributes": [["method", "GET"]]
                }
              ],
              "fibers": [
                {
                  "id": "1",
                  "isCurrent": true,
                  "isInterruptible": true,
                  "isInterrupted": false,
                  "children": ["2"],
                  "lifeTimeMillis": 42,
                  "stack": []
                }
              ],
              "breakpoints": {
                "pauseOnDefects": true,
                "values": [{ "value": { "label": "Fiber Defect", "summary": "Error: boom" } }],
                "location": { "path": "/tmp/app.ts", "line": 4, "column": 8 }
              },
              "message": "ok"
            }
            """.trimIndent(),
        )

        assertEquals("Database", snapshot.context.single().tag)
        assertEquals("HTTP GET", snapshot.spanStack.single().name)
        assertEquals("1", snapshot.fibers.single().id)
        assertTrue(snapshot.fibers.single().isCurrent)
        assertTrue(snapshot.breakpoints.pauseOnDefects)
        assertEquals("/tmp/app.ts", snapshot.breakpoints.location?.path)
    }

    fun testMermaidResultExtractionMatchesVsCodeWrapperShape() {
        val result = mapOf(
            "success" to true,
            "body" to mapOf(
                "success" to true,
                "mermaidCode" to "flowchart TD\n  A --> B",
            ),
        )

        assertEquals("flowchart TD\n  A --> B", EffectLayerMermaidService.extractMermaidCode(result))
    }

    override fun getTestDataPath(): String = Path.of("src", "test", "testData").toAbsolutePath().toString()
}
