package dev.effect.intellij.settings

import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.SystemInfo
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBSplitter
import com.intellij.util.ui.UIUtil
import dev.effect.intellij.core.EffectJson
import java.awt.Component
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.text.JTextComponent

class EffectSettingsValidationTest : BasePlatformTestCase() {
    fun testConfigurableIsNotModifiedBeforeComponentCreation() {
        val configurable = EffectProjectSettingsConfigurable(project)

        assertFalse(configurable.isModified)
    }

    fun testPinnedModeRequiresVersion() {
        val service = project.getService(EffectProjectSettingsService::class.java)

        val problems = service.validate(
            EffectProjectSettings(
                binaryMode = EffectBinaryMode.PINNED,
                pinnedVersion = "",
            ),
        )

        assertTrue(problems.any { it.field == "pinnedVersion" })
    }

    fun testPinnedModeRejectsPathsAndRangesButAcceptsExactVersions() {
        val service = project.getService(EffectProjectSettingsService::class.java)
        fun pinnedProblems(version: String) = service.validate(
            EffectProjectSettings(binaryMode = EffectBinaryMode.PINNED, pinnedVersion = version),
        ).filter { it.field == "pinnedVersion" }

        assertTrue(pinnedProblems("0.54.1").isEmpty())
        assertTrue(pinnedProblems("0.55.0-next.2").isEmpty())
        listOf("/home/me/node_modules/@effect/tsgo-linux-x64/lib/tsc", "^0.54.1", "latest").forEach { version ->
            val problems = pinnedProblems(version)
            assertEquals(version, 1, problems.size)
            assertEquals(SettingSeverity.ERROR, problems.single().severity)
            assertEquals(EffectProjectSettingsService.PINNED_VERSION_SHAPE_MESSAGE, problems.single().message)
        }
    }

    fun testJsonValidationReportsErrors() {
        val service = project.getService(EffectProjectSettingsService::class.java)

        val problems = service.validate(
            EffectProjectSettings(
                initializationOptionsJson = "{not-json}",
                workspaceConfigurationJson = "{still-not-json}",
            ),
        )

        assertTrue(problems.any { it.field == "initializationOptionsJson" })
        assertTrue(problems.any { it.field == "workspaceConfigurationJson" })
    }

    fun testJsonValidationRejectsNonObjectPayloads() {
        val service = project.getService(EffectProjectSettingsService::class.java)

        val problems = service.validate(
            EffectProjectSettings(
                initializationOptionsJson = """["not-an-object"]""",
                workspaceConfigurationJson = """"still-not-an-object"""",
            ),
        )

        assertTrue(problems.any { it.field == "initializationOptionsJson" && it.message.contains("JSON object") })
        assertTrue(problems.any { it.field == "workspaceConfigurationJson" && it.message.contains("JSON object") })
    }

    fun testTypedLspSettingsOverlayRawWorkspaceConfiguration() {
        val service = project.getService(EffectProjectSettingsService::class.java)

        val config = service.effectiveWorkspaceConfiguration(
            EffectProjectSettings(
                workspaceConfigurationJson = """{"effect":{"inlays":false,"mermaidProvider":"custom"},"other":true}""",
                lspInlays = true,
                lspMermaidProvider = "mermaid.live",
                lspNoExternal = true,
                lspLayerGraphFollowDepth = 2,
                lspDiagnosticSeverity = mapOf(
                    "schemaNumber" to "warning",
                    "redundantOrDie" to "suggestion",
                ),
            ),
        )

        assertNotNull(config)
        assertEquals(true, config!!.path("other").asBoolean())
        assertEquals(true, config.path("effect").path("inlays").asBoolean())
        assertEquals("mermaid.live", config.path("effect").path("mermaidProvider").asText())
        assertEquals(true, config.path("effect").path("noExternal").asBoolean())
        assertEquals(2, config.path("effect").path("layerGraphFollowDepth").asInt())
        assertEquals("warning", config.path("effect").path("diagnosticSeverity").path("schemaNumber").asText())
        assertEquals("suggestion", config.path("effect").path("diagnosticSeverity").path("redundantOrDie").asText())
    }

    fun testTypedLspSettingsValidation() {
        val service = project.getService(EffectProjectSettingsService::class.java)

        val problems = service.validate(
            EffectProjectSettings(
                workspaceConfigurationJson = """{"effect":{"inlays":false}}""",
                lspInlays = true,
                lspLayerGraphFollowDepth = -1,
                lspDiagnosticSeverity = mapOf("schemaNumber" to "loud"),
            ),
        )

        assertTrue(problems.any { it.field == "lspLayerGraphFollowDepth" })
        assertTrue(problems.any { it.field == "lspDiagnosticSeverity" })
        assertTrue(problems.any { it.severity == SettingSeverity.WARNING && it.message.contains("inlays") })
    }

    fun testConfigurableKeepsDefaultDebugConfigurationTypesWhenFieldIsCleared() {
        val service = project.getService(EffectProjectSettingsService::class.java)
        service.updateSettings(EffectProjectSettings(binaryMode = EffectBinaryMode.LATEST))
        val configurable = EffectProjectSettingsConfigurable(project)
        val component = configurable.createComponent()
        try {
            val debugTypesField = findTextComponents(component)
                .first { textComponent ->
                    val text = textComponent.text
                    text.contains("Node.js") && text.contains("JavaScript Debug")
                }

            debugTypesField.text = ""
            configurable.apply()

            assertEquals(DEFAULT_NODE_DEBUG_CONFIGURATION_TYPES, service.currentSettings().injectDebugConfigurationTypes)
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testSwitchingToPinnedModeDetectsWorkspaceTsgoVersion() {
        val workspaceRoot = java.nio.file.Path.of(requireNotNull(project.basePath))
        val tsgoManifest = workspaceRoot.resolve("node_modules/@effect/tsgo/package.json")
        Files.createDirectories(tsgoManifest.parent)
        Files.writeString(tsgoManifest, """{"name":"@effect/tsgo","version":"0.53.4"}""")
        val service = project.getService(EffectProjectSettingsService::class.java)
        service.updateSettings(EffectProjectSettings(binaryMode = EffectBinaryMode.LATEST))
        val configurable = EffectProjectSettingsConfigurable(project)
        val component = configurable.createComponent()
        try {
            val comboBoxes = collectComponents(component).filterIsInstance<ComboBox<*>>()
            val modeBox = comboBoxes.first { box -> (0 until box.itemCount).any { box.getItemAt(it) == EffectBinaryMode.PINNED } }
            val versionBox = comboBoxes.first { box -> (0 until box.itemCount).any { box.getItemAt(it) is PinnedVersionItem } }

            modeBox.selectedItem = EffectBinaryMode.PINNED
            configurable.apply()

            assertEquals("0.53.4", service.currentSettings().pinnedVersion)
            assertEquals(EffectBinaryMode.PINNED, service.currentSettings().binaryMode)
            val labels = (0 until versionBox.itemCount).map { (versionBox.getItemAt(it) as PinnedVersionItem).label }
            assertTrue(labels.toString(), labels.first().startsWith("0.53.4  —  @effect/tsgo"))
            assertEquals("Select…", labels.last())

            // A hand-typed version still wins over detection and is what gets saved.
            versionBox.editor.item = "0.54.1"
            configurable.apply()
            assertEquals("0.54.1", service.currentSettings().pinnedVersion)
        } finally {
            configurable.disposeUIResources()
            tsgoManifest.parent.parent.parent.toFile().deleteRecursively()
        }
    }

    fun testConfigurableUsesScrollableGroupedLayoutWithoutSplitter() {
        val service = project.getService(EffectProjectSettingsService::class.java)
        service.updateSettings(EffectProjectSettings(binaryMode = EffectBinaryMode.LATEST))
        val configurable = EffectProjectSettingsConfigurable(project)
        val component = configurable.createComponent()
        try {
            assertTrue(collectComponents(component).any { it is JScrollPane })
            assertFalse(collectComponents(component).any { it is JBSplitter })

            val visibleText = collectComponents(component)
                .filterIsInstance<JLabel>()
                .mapNotNull(JLabel::getText)
                .joinToString("\n")
            assertTrue(visibleText.contains("Binary"))
            assertTrue(visibleText.contains("Language Server"))
            assertTrue(visibleText.contains("Dev Tools"))
            assertTrue(visibleText.contains("Debugger"))
            assertTrue(collectComponents(component).filterIsInstance<JButton>().any { it.text == "Sync to tsconfig.json" })
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testBinaryAndAdvancedRowsUseConditionalVisibility() {
        val service = project.getService(EffectProjectSettingsService::class.java)
        service.updateSettings(EffectProjectSettings(binaryMode = EffectBinaryMode.LATEST))
        val configurable = EffectProjectSettingsConfigurable(project)
        val component = configurable.createComponent()
        try {
            val labels = collectComponents(component).filterIsInstance<JLabel>()
            val pinnedLabel = labels.first { it.text == "Pinned version" }
            val manualLabel = labels.first { it.text == "Manual binary path" }
            assertFalse(pinnedLabel.isVisible)
            assertFalse(manualLabel.isVisible)

            val binaryMode = collectComponents(component)
                .filterIsInstance<ComboBox<*>>()
                .first { combo -> combo.getItemAt(0) is EffectBinaryMode }
            binaryMode.selectedItem = EffectBinaryMode.PINNED
            assertTrue(pinnedLabel.isVisible)
            assertFalse(manualLabel.isVisible)

            assertFalse(labels.first { it.text == "Extra environment (KEY=VALUE per line)" }.isVisible)
            assertFalse(labels.first { it.text == "Run/debug configuration types" }.isVisible)
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testSettingsSyncValidatesAndUsesUnappliedFormSnapshot() {
        val tsconfig = myFixture.addFileToProject(
            "tsconfig.json",
            """{ "compilerOptions": { "plugins": [{ "name": "@effect/language-service", "inlays": false }] } }""",
        )
        val service = project.getService(EffectProjectSettingsService::class.java)
        service.updateSettings(EffectProjectSettings(binaryMode = EffectBinaryMode.LATEST, lspInlays = false))
        val configurable = EffectProjectSettingsConfigurable(project)
        val component = configurable.createComponent()
        try {
            val components = collectComponents(component)
            val labels = components.filterIsInstance<JLabel>()
            val inlays = labels.first { it.text == "Inlays" }.labelFor as ComboBox<*>
            val binaryMode = components
                .filterIsInstance<ComboBox<*>>()
                .first { combo -> combo.getItemAt(0) is EffectBinaryMode }
            val syncButton = components.filterIsInstance<JButton>().first { it.text == "Sync to tsconfig.json" }

            inlays.selectedItem = "true"
            binaryMode.selectedItem = EffectBinaryMode.PINNED
            syncButton.doClick()
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            assertFalse(
                EffectJson.mapper.readTree(tsconfig.text)
                    .path("compilerOptions").path("plugins")[0].path("inlays").asBoolean(),
            )

            binaryMode.selectedItem = EffectBinaryMode.LATEST
            clickIgnoringMissingTestNotificationGroup(syncButton)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            assertTrue(
                EffectJson.mapper.readTree(tsconfig.text)
                    .path("compilerOptions").path("plugins")[0].path("inlays").asBoolean(),
            )
            assertEquals(false, service.currentSettings().lspInlays)
        } finally {
            configurable.disposeUIResources()
        }
    }

    fun testManualModeAcceptsExistingExecutable() {
        val service = project.getService(EffectProjectSettingsService::class.java)
        val executable = Files.createTempFile("effect-manual", if (System.getProperty("os.name").contains("win", ignoreCase = true)) ".exe" else "")
        executable.toFile().setExecutable(true, false)

        val problems = service.validate(
            EffectProjectSettings(
                binaryMode = EffectBinaryMode.MANUAL,
                manualBinaryPath = executable.toString(),
            ),
        )

        assertEmpty(problems)
    }

    fun testManualModeRejectsNonExecutableFile() {
        if (SystemInfo.isWindows) return
        val service = project.getService(EffectProjectSettingsService::class.java)
        val manual = Files.createTempFile("effect-manual-non-exec", ".tmp")
        Files.writeString(manual, "manual")
        makeNonExecutable(manual)

        val problems = service.validate(
            EffectProjectSettings(
                binaryMode = EffectBinaryMode.MANUAL,
                manualBinaryPath = manual.toString(),
            ),
        )

        assertTrue(problems.any { it.field == "manualBinaryPath" && it.message.contains("executable") })
    }

    fun testManualModeRejectsInvalidFilesystemPath() {
        val service = project.getService(EffectProjectSettingsService::class.java)

        val problems = service.validate(
            EffectProjectSettings(
                binaryMode = EffectBinaryMode.MANUAL,
                manualBinaryPath = "\u0000invalid",
            ),
        )

        assertTrue(problems.any { it.field == "manualBinaryPath" && it.message.contains("valid filesystem path") })
    }

    fun testManualModeRejectsRelativePath() {
        val service = project.getService(EffectProjectSettingsService::class.java)

        val problems = service.validate(
            EffectProjectSettings(
                binaryMode = EffectBinaryMode.MANUAL,
                manualBinaryPath = "relative-tsgo",
            ),
        )

        assertTrue(problems.any { it.field == "manualBinaryPath" && it.message.contains("absolute") })
    }

    fun testInvalidPersistedBinaryModeFallsBackToManual() {
        val service = project.getService(EffectProjectSettingsService::class.java)
        service.loadState(EffectProjectSettingsState().also { it.binaryMode = "BROKEN" })

        assertEquals(EffectBinaryMode.MANUAL, service.currentSettings().binaryMode)
    }

    private fun findTextComponents(component: Component): List<JTextComponent> =
        collectComponents(component).filterIsInstance<JTextComponent>()

    private fun collectComponents(component: Component): List<Component> =
        UIUtil.uiTraverser(component).toList()

    private fun clickIgnoringMissingTestNotificationGroup(button: JButton) {
        try {
            button.doClick()
        } catch (error: NullPointerException) {
            // The lightweight platform fixture does not register plugin.xml notification groups.
            assertTrue(error.message.orEmpty().contains("NotificationGroup"))
        }
    }

    private fun makeNonExecutable(path: java.nio.file.Path) {
        if (System.getProperty("os.name").contains("win", ignoreCase = true)) {
            path.toFile().setExecutable(false, false)
            return
        }

        Files.setPosixFilePermissions(
            path,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ,
            ),
        )
    }
}
