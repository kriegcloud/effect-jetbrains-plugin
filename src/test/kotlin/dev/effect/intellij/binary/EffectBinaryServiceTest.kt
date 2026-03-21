package dev.effect.intellij.binary

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.effect.intellij.settings.EffectBinaryMode
import dev.effect.intellij.settings.EffectProjectSettings
import dev.effect.intellij.settings.EffectProjectSettingsService
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.ExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EffectBinaryServiceTest : BasePlatformTestCase() {
    private lateinit var server: HttpServer
    private lateinit var serverExecutor: ExecutorService
    private lateinit var tempDir: Path
    private lateinit var platformPackage: String
    private lateinit var binaryName: String
    private lateinit var originalRegistryBaseUrl: String

    override fun setUp() {
        super.setUp()
        tempDir = Files.createTempDirectory("effect-binary-test")
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
        platformPackage = "@effect/tsgo-$os-$arch"
        binaryName = if (os == "win32") "tsgo.exe" else "tsgo"
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverExecutor = Executors.newCachedThreadPool()
        server.executor = serverExecutor
        server.start()
        originalRegistryBaseUrl = EffectBinaryService.getInstance().registryBaseUrl
    }

    override fun tearDown() {
        try {
            server.stop(0)
            serverExecutor.shutdownNow()
            EffectBinaryService.getInstance().registryBaseUrl = originalRegistryBaseUrl
        } finally {
            super.tearDown()
        }
    }

    fun testLatestModeDownloadsManagedBinary() {
        registerLatestEndpoints("1.2.3")

        val binaryService = EffectBinaryService.getInstance()
        binaryService.registryBaseUrl = "http://127.0.0.1:${server.address.port}"

        project.getService(EffectProjectSettingsService::class.java).updateSettings(
            EffectProjectSettings(binaryMode = EffectBinaryMode.LATEST),
        )

        val resolution = binaryService.ensureAvailable(project)
        assertEquals(EffectBinaryMode.LATEST, resolution.mode)
        assertEquals("1.2.3", resolution.version)
        assertTrue(Files.exists(resolution.binaryPath))
    }

    fun testPinnedModeUsesConfiguredVersion() {
        registerPinnedEndpoint("9.9.9")

        val binaryService = EffectBinaryService.getInstance()
        binaryService.registryBaseUrl = "http://127.0.0.1:${server.address.port}"

        project.getService(EffectProjectSettingsService::class.java).updateSettings(
            EffectProjectSettings(
                binaryMode = EffectBinaryMode.PINNED,
                pinnedVersion = "9.9.9",
            ),
        )

        val resolution = binaryService.ensureAvailable(project)
        assertEquals(EffectBinaryMode.PINNED, resolution.mode)
        assertEquals("9.9.9", resolution.version)
        assertTrue(Files.exists(resolution.binaryPath))
    }

    fun testManualModeUsesProvidedBinary() {
        val manual = Files.createTempFile(tempDir, "manual", if (binaryName.endsWith(".exe")) ".exe" else "")
        Files.writeString(manual, "manual")
        manual.toFile().setExecutable(true, false)

        project.getService(EffectProjectSettingsService::class.java).updateSettings(
            EffectProjectSettings(
                binaryMode = EffectBinaryMode.MANUAL,
                manualBinaryPath = manual.toString(),
            ),
        )

        val resolution = EffectBinaryService.getInstance().ensureAvailable(project)
        assertEquals(BinarySource.MANUAL, resolution.source)
        assertEquals(manual, resolution.binaryPath)
    }

    fun testManualModeRejectsNonExecutableBinaryWithoutMutatingIt() {
        val manual = Files.createTempFile(tempDir, "manual-non-exec", ".tmp")
        Files.writeString(manual, "manual")
        makeNonExecutable(manual)

        project.getService(EffectProjectSettingsService::class.java).updateSettings(
            EffectProjectSettings(
                binaryMode = EffectBinaryMode.MANUAL,
                manualBinaryPath = manual.toString(),
            ),
        )

        try {
            EffectBinaryService.getInstance().ensureAvailable(project)
            fail("Expected manual mode to reject a non-executable binary")
        } catch (error: EffectBinaryException) {
            assertTrue(error.message?.contains("executable") == true)
        }

        assertFalse(Files.isExecutable(manual))
    }

    fun testManualModeRejectsInvalidFilesystemPath() {
        project.getService(EffectProjectSettingsService::class.java).updateSettings(
            EffectProjectSettings(
                binaryMode = EffectBinaryMode.MANUAL,
                manualBinaryPath = "\u0000invalid",
            ),
        )

        try {
            EffectBinaryService.getInstance().ensureAvailable(project)
            fail("Expected manual mode to reject an invalid filesystem path")
        } catch (error: EffectBinaryException) {
            assertTrue(error.message?.contains("valid filesystem path") == true)
        }
    }

    fun testConcurrentManagedResolutionInstallsIntoCacheOnce() {
        val version = "4.5.6"
        val tarballName = "${platformPackage.substringAfter('/')}-${version}.tgz"
        val tarballPath = tempDir.resolve(tarballName)
        val cacheRoot = tempDir.resolve("managed-cache")
        val metadataRequests = AtomicInteger(0)
        val tarballRequests = AtomicInteger(0)
        val startGate = CountDownLatch(1)
        writeTarball(tarballPath)

        server.createContext("/$platformPackage/$version") { exchange ->
            metadataRequests.incrementAndGet()
            respondJson(
                exchange,
                """{"dist":{"tarball":"http://127.0.0.1:${server.address.port}/tarballs/$tarballName"}}""",
            )
        }
        server.createContext("/tarballs/$tarballName") { exchange ->
            tarballRequests.incrementAndGet()
            Thread.sleep(250)
            val bytes = Files.readAllBytes(tarballPath)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        val binaryService = EffectBinaryService.getInstance()
        binaryService.registryBaseUrl = "http://127.0.0.1:${server.address.port}"
        val applicationStateService = dev.effect.intellij.settings.EffectApplicationStateService.getInstance()
        val originalApplicationState = applicationStateService.currentState()
        applicationStateService.loadState(originalApplicationState.copy(binaryCacheDirOverride = cacheRoot.toString()))

        project.getService(EffectProjectSettingsService::class.java).updateSettings(
            EffectProjectSettings(
                binaryMode = EffectBinaryMode.PINNED,
                pinnedVersion = version,
            ),
        )

        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<BinaryResolution> {
                startGate.await(5, TimeUnit.SECONDS)
                binaryService.ensureAvailable(project)
            }
            val second = executor.submit<BinaryResolution> {
                startGate.await(5, TimeUnit.SECONDS)
                binaryService.ensureAvailable(project)
            }
            startGate.countDown()

            val firstResolution = first.get(10, TimeUnit.SECONDS)
            val secondResolution = second.get(10, TimeUnit.SECONDS)

            assertEquals(firstResolution.binaryPath, secondResolution.binaryPath)
            assertTrue(Files.exists(firstResolution.binaryPath))
            assertEquals(1, metadataRequests.get())
            assertEquals(1, tarballRequests.get())
        } finally {
            executor.shutdownNow()
            applicationStateService.loadState(originalApplicationState)
        }
    }

    private fun registerLatestEndpoints(version: String) {
        server.createContext("/@effect/tsgo") { exchange ->
            respondJson(exchange, """{"dist-tags":{"latest":"$version"}}""")
        }
        registerPinnedEndpoint(version)
    }

    private fun registerPinnedEndpoint(version: String) {
        val tarballName = "${platformPackage.substringAfter('/')}-${version}.tgz"
        val tarballPath = tempDir.resolve(tarballName)
        writeTarball(tarballPath)

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
    }

    private fun writeTarball(path: Path) {
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

    private fun makeNonExecutable(path: Path) {
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
