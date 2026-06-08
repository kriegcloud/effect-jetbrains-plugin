package dev.effect.intellij

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.effect.intellij.debug.DebugBridgeState
import dev.effect.intellij.core.EffectPluginConstants
import dev.effect.intellij.debug.EffectDebugBridgeService
import dev.effect.intellij.debug.EffectDebugSnapshot
import dev.effect.intellij.debug.EffectDebugTreeModels
import dev.effect.intellij.debug.EffectDebugTreeEntry
import dev.effect.intellij.debug.EffectInstrumentationService
import dev.effect.intellij.devtools.EffectDevToolsService
import dev.effect.intellij.lsp.EffectLayerMermaidService
import dev.effect.intellij.lsp.EffectLspProjectService
import dev.effect.intellij.settings.EffectProjectSettingsService
import dev.effect.intellij.status.EffectStatusService
import java.awt.Component
import java.awt.Container
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.text.JTextComponent

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

    fun testDebugTreeModelsExposeInteractiveMetadata() {
        val snapshot = EffectDebugSnapshot.parse(
            """
            {
              "context": [],
              "spanStack": [
                {
                  "name": "HTTP GET",
                  "traceId": "trace",
                  "spanId": "span",
                  "stackIndex": 0,
                  "path": "/tmp/app.ts",
                  "line": 4,
                  "column": 8,
                  "attributes": []
                },
                {
                  "name": "internal-span",
                  "traceId": "trace",
                  "spanId": "ignored",
                  "stackIndex": 1,
                  "attributes": []
                }
              ],
              "fibers": [
                {
                  "id": "1",
                  "isCurrent": true,
                  "isInterruptible": true,
                  "isInterrupted": false,
                  "children": [],
                  "lifeTimeMillis": 42,
                  "stack": []
                }
              ],
              "breakpoints": {
                "pauseOnDefects": true,
                "values": [],
                "location": { "path": "/tmp/app.ts", "line": 4, "column": 8 }
              }
            }
            """.trimIndent(),
        )
        val state = DebugBridgeState(attachedSessionName = "node", snapshot = snapshot)

        val spans = EffectDebugTreeModels.spanStackEntries(state, ignoreList = listOf("internal-*"), ignoreEnabled = true)
        val fibers = EffectDebugTreeModels.fiberEntries(state)
        val breakpoints = EffectDebugTreeModels.breakpointEntries(state)

        assertEquals(listOf("HTTP GET"), spans.map { it.title })
        assertEquals("/tmp/app.ts", spans.single().location?.path)
        assertEquals("1", fibers.first { it.title.contains("Fiber") }.fiberId)
        assertEquals("/tmp/app.ts", breakpoints.first { it.title == "Last pause location" }.location?.path)
    }

    fun testDebugTreePanelKeepsRefreshErrorsVisible() {
        val panelClass = Class.forName("dev.effect.intellij.ui.DebugTreePanel")
        val noOpAction: (EffectDebugTreeEntry?) -> Unit = {}
        val constructor = panelClass.getDeclaredConstructor(
            com.intellij.openapi.project.Project::class.java,
            String::class.java,
            String::class.java,
            Function1::class.java,
        ).also { it.isAccessible = true }
        val panel = constructor.newInstance(
            project,
            "Context",
            null,
            noOpAction,
        )
        val refresh = panelClass.getDeclaredMethod(
            "refresh",
            DebugBridgeState::class.java,
            List::class.java,
            String::class.java,
        ).also { it.isAccessible = true }
        refresh.invoke(
            panel,
            DebugBridgeState(attachedSessionName = "node", error = "Snapshot refresh failed"),
            listOf(EffectDebugTreeEntry("Context entry", detail = "Context detail")),
            null,
        )
        val component = panelClass.getDeclaredMethod("getComponent")
            .also { it.isAccessible = true }
            .invoke(panel) as JComponent

        assertTrue(findTextComponents(component).any { it.text == "Snapshot refresh failed" })
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

    private fun findTextComponents(component: Component): List<JTextComponent> =
        buildList {
            collectTextComponents(component, this)
        }

    private fun collectTextComponents(component: Component, result: MutableList<JTextComponent>) {
        if (component is JTextComponent) {
            result += component
        }
        if (component is Container) {
            component.components.forEach { child -> collectTextComponents(child, result) }
        }
    }

    override fun getTestDataPath(): String = Path.of("src", "test", "testData").toAbsolutePath().toString()
}
