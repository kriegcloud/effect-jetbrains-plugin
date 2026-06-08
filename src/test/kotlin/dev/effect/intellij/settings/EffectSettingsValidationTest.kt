package dev.effect.intellij.settings

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

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

    fun testInvalidPersistedBinaryModeFallsBackToLatest() {
        val service = project.getService(EffectProjectSettingsService::class.java)
        service.loadState(EffectProjectSettingsState().also { it.binaryMode = "BROKEN" })

        assertEquals(EffectBinaryMode.MANUAL, service.currentSettings().binaryMode)
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
