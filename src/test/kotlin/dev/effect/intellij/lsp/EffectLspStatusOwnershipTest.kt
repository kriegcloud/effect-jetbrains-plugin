package dev.effect.intellij.lsp

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.effect.intellij.binary.EffectBinaryService
import dev.effect.intellij.settings.EffectBinaryMode
import dev.effect.intellij.settings.EffectProjectSettings
import dev.effect.intellij.settings.EffectProjectSettingsService
import dev.effect.intellij.status.EffectLspStatus
import dev.effect.intellij.status.EffectStatusService
import com.intellij.execution.configurations.GeneralCommandLine
import org.eclipse.lsp4j.InitializeResult
import java.nio.file.Files
import java.nio.file.Path

class EffectLspStatusOwnershipTest : BasePlatformTestCase() {
    fun testBinaryResolutionDoesNotSetRunningBeforeLspInitialization() {
        val binary = createExecutableBinary()
        project.getService(EffectProjectSettingsService::class.java).updateSettings(
            EffectProjectSettings(
                binaryMode = EffectBinaryMode.MANUAL,
                manualBinaryPath = binary.toString(),
            ),
        )

        EffectBinaryService.getInstance().ensureAvailable(project)

        assertEquals(EffectLspStatus.NOT_CONFIGURED, project.getService(EffectStatusService::class.java).currentSnapshot().status)
    }

    fun testDescriptorStartupAndInitializationDriveStatusLifecycle() {
        val binary = createExecutableBinary()
        project.getService(EffectProjectSettingsService::class.java).updateSettings(
            EffectProjectSettings(
                binaryMode = EffectBinaryMode.MANUAL,
                manualBinaryPath = binary.toString(),
            ),
        )

        val descriptor = EffectLspServerDescriptor(project)
        val commandLine = descriptor.javaClass.getDeclaredMethod("createCommandLine").let { method ->
            method.isAccessible = true
            method.invoke(descriptor) as GeneralCommandLine
        }
        val status = project.getService(EffectStatusService::class.java).currentSnapshot()

        assertEquals(binary.toString(), commandLine.exePath)
        assertEquals(EffectLspStatus.STARTING, status.status)
        assertEquals(binary.toString(), status.binaryPath)

        descriptor.lspServerListener.serverInitialized(InitializeResult())

        assertEquals(EffectLspStatus.RUNNING, project.getService(EffectStatusService::class.java).currentSnapshot().status)
    }

    fun testLspSettingChangesOnlyRequestRestartForLspFields() {
        val binary = createExecutableBinary()
        val settingsService = project.getService(EffectProjectSettingsService::class.java)
        val statusService = project.getService(EffectStatusService::class.java)
        val baseSettings = EffectProjectSettings(
            binaryMode = EffectBinaryMode.MANUAL,
            manualBinaryPath = binary.toString(),
            workspaceConfigurationJson = """{"effect":{"inlays":true}}""",
        )
        settingsService.updateSettings(baseSettings)

        statusService.markRunning(binary.toString())
        settingsService.updateSettings(baseSettings.copy(workspaceConfigurationJson = """{"effect":{"inlays":false}}"""))
        assertEquals(EffectLspStatus.RESTART_REQUIRED, statusService.currentSnapshot().status)
        assertEquals("Effect language server settings changed", statusService.currentSnapshot().detail)

        val lspSettings = settingsService.currentSettings()
        statusService.markRunning(binary.toString())
        settingsService.updateSettings(lspSettings.copy(devToolsPort = 45_000))
        assertEquals(EffectLspStatus.RUNNING, statusService.currentSnapshot().status)
    }

    fun testInactiveProjectsDoNotShowRestartRequiredOnSettingsSave() {
        val binary = createExecutableBinary()
        val settingsService = project.getService(EffectProjectSettingsService::class.java)
        val statusService = project.getService(EffectStatusService::class.java)
        val baseSettings = EffectProjectSettings(
            binaryMode = EffectBinaryMode.MANUAL,
            manualBinaryPath = binary.toString(),
        )
        settingsService.updateSettings(baseSettings)

        statusService.markNotConfigured()
        settingsService.updateSettings(baseSettings.copy(initializationOptionsJson = """{"trace":"verbose"}"""))

        assertEquals(EffectLspStatus.NOT_CONFIGURED, statusService.currentSnapshot().status)
    }

    fun testInvalidLaunchConfigurationMarksError() {
        project.getService(EffectProjectSettingsService::class.java).updateSettings(
            EffectProjectSettings(
                binaryMode = EffectBinaryMode.MANUAL,
                manualBinaryPath = "/definitely/not/a/real/effect-tsgo",
            ),
        )

        try {
            project.getService(EffectLspProjectService::class.java).createLaunchConfiguration()
            fail("Expected launch configuration creation to fail")
        } catch (_: Exception) {
            assertEquals(EffectLspStatus.ERROR, project.getService(EffectStatusService::class.java).currentSnapshot().status)
        }
    }

    private fun createExecutableBinary(): Path =
        Files.createTempFile("effect-tsgo-test", if (System.getProperty("os.name").contains("Windows")) ".exe" else "").also { path ->
            Files.writeString(path, "echo test")
            path.toFile().setExecutable(true, false)
        }
}
