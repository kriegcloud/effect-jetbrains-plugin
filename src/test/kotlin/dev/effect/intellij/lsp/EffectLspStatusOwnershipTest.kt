package dev.effect.intellij.lsp

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.effect.intellij.binary.EffectBinaryService
import dev.effect.intellij.settings.EffectApplicationStateService
import dev.effect.intellij.settings.EffectBinaryMode
import dev.effect.intellij.settings.EffectProjectSettings
import dev.effect.intellij.settings.EffectProjectSettingsService
import dev.effect.intellij.status.EffectLspStatus
import dev.effect.intellij.status.EffectStatusService
import com.intellij.execution.configurations.GeneralCommandLine
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.InitializeResult
import java.nio.file.Files
import java.nio.file.Path
import java.net.InetSocketAddress

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

    fun testLatestLaunchConfigurationIsReusedAcrossDescriptorCallbacks() {
        val binaryService = EffectBinaryService.getInstance()
        val originalRegistryBaseUrl = binaryService.registryBaseUrl
        val applicationStateService = EffectApplicationStateService.getInstance()
        val originalApplicationState = applicationStateService.currentState()
        val tempDir = Files.createTempDirectory("effect-lsp-descriptor")
        val platformPackage = currentPlatformPackage()
        val binaryName = currentBinaryName()
        val version = "1.2.3"
        val tarballName = "${platformPackage.substringAfter('/')}-${version}.tgz"
        val tarballPath = tempDir.resolve(tarballName)
        writeTarball(tarballPath, binaryName)

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try {
            server.createContext("/@effect/tsgo") { exchange ->
                respondJson(exchange, """{"dist-tags":{"latest":"$version"}}""")
            }
            server.createContext("/$platformPackage/$version") { exchange ->
                respondJson(
                    exchange,
                    """{"dist":{"tarball":"http://127.0.0.1:${server.address.port}/tarballs/$tarballName"}}""",
                )
            }
            server.createContext("/tarballs/$tarballName") { exchange ->
                val bytes = Files.readAllBytes(tarballPath)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()

            applicationStateService.loadState(
                originalApplicationState.copy(binaryCacheDirOverride = tempDir.resolve("cache").toString()),
            )
            binaryService.registryBaseUrl = "http://127.0.0.1:${server.address.port}"

            project.getService(EffectProjectSettingsService::class.java).updateSettings(
                EffectProjectSettings(
                    binaryMode = EffectBinaryMode.LATEST,
                    initializationOptionsJson = """{"feature":"enabled"}""",
                    workspaceConfigurationJson = """{"effect":{"inlays":true}}""",
                ),
            )

            val descriptor = EffectLspServerDescriptor(project)
            val commandLine = descriptor.javaClass.getDeclaredMethod("createCommandLine").let { method ->
                method.isAccessible = true
                method.invoke(descriptor) as GeneralCommandLine
            }

            server.stop(0)
            binaryService.registryBaseUrl = "http://127.0.0.1:1"

            val initializationOptions = descriptor.createInitializationOptions()
            val workspaceConfiguration = descriptor.getWorkspaceConfiguration(
                ConfigurationItem().apply { section = "effect" },
            )
            val nestedWorkspaceConfiguration = descriptor.getWorkspaceConfiguration(
                ConfigurationItem().apply { section = "effect.inlays" },
            )
            descriptor.lspServerListener.serverInitialized(InitializeResult())

            assertTrue(commandLine.exePath.endsWith(binaryName))
            assertEquals("""{"feature":"enabled"}""", initializationOptions.toString())
            assertEquals(mapOf("inlays" to true), workspaceConfiguration)
            assertEquals(true, nestedWorkspaceConfiguration)
            assertEquals(EffectLspStatus.RUNNING, project.getService(EffectStatusService::class.java).currentSnapshot().status)
        } finally {
            server.stop(0)
            binaryService.registryBaseUrl = originalRegistryBaseUrl
            applicationStateService.loadState(originalApplicationState)
        }
    }

    private fun createExecutableBinary(): Path =
        Files.createTempFile("effect-tsgo-test", if (System.getProperty("os.name").contains("Windows")) ".exe" else "").also { path ->
            Files.writeString(path, "echo test")
            path.toFile().setExecutable(true, false)
        }

    private fun currentPlatformPackage(): String {
        val osName = System.getProperty("os.name").lowercase()
        val archName = System.getProperty("os.arch").lowercase()
        val os = when {
            osName.contains("mac") -> "darwin"
            osName.contains("linux") -> "linux"
            else -> "win32"
        }
        val arch = when {
            archName == "aarch64" || archName == "arm64" -> "arm64"
            archName == "x86_64" || archName == "amd64" -> "x64"
            else -> "arm"
        }
        return "@effect/tsgo-$os-$arch"
    }

    private fun currentBinaryName(): String =
        if (System.getProperty("os.name").contains("Windows")) "tsgo.exe" else "tsgo"

    private fun writeTarball(path: Path, binaryName: String) {
        GzipCompressorOutputStream(Files.newOutputStream(path)).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                val entryName = "package/lib/$binaryName"
                val data = "binary".toByteArray()
                val entry = TarArchiveEntry(entryName)
                entry.size = data.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(data)
                tar.closeArchiveEntry()
                tar.finish()
            }
        }
    }

    private fun respondJson(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
