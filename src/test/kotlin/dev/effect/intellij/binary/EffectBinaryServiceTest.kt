package dev.effect.intellij.binary

import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.effect.intellij.settings.EffectApplicationState
import dev.effect.intellij.settings.EffectApplicationStateService
import dev.effect.intellij.settings.EffectBinaryMode
import dev.effect.intellij.settings.EffectProjectSettings
import dev.effect.intellij.settings.EffectProjectSettingsService
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assume.assumeFalse
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val CURRENT_TSGO_VERSION = "0.19.0"
private const val STABLE_TYPESCRIPT_HEAD = "stable-typescript-head"
private const val NEXT_TYPESCRIPT_HEAD = "next-typescript-head"

class EffectBinaryServiceTest : BasePlatformTestCase() {
    private lateinit var server: HttpServer
    private lateinit var serverExecutor: ExecutorService
    private lateinit var tempDir: Path
    private lateinit var platformPackage: String
    private lateinit var binaryName: String
    private lateinit var stableBinaryName: String
    private lateinit var nextBinaryName: String
    private lateinit var originalRegistryBaseUrl: String
    private lateinit var originalApplicationState: EffectApplicationState

    override fun setUp() {
        super.setUp()
        tempDir = Files.createTempDirectory("effect-binary-test")
        resetWorkspacePackages()
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
        stableBinaryName = if (os == "win32") "tsc.exe" else "tsc"
        nextBinaryName = if (os == "win32") "tsc-next.exe" else "tsc-next"
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        serverExecutor = Executors.newCachedThreadPool()
        server.executor = serverExecutor
        server.start()
        originalRegistryBaseUrl = EffectBinaryService.getInstance().registryBaseUrl
        val applicationStateService = EffectApplicationStateService.getInstance()
        originalApplicationState = applicationStateService.currentState()
        applicationStateService.loadState(originalApplicationState.copy(binaryCacheDirOverride = tempDir.resolve("cache").toString()))
    }

    override fun tearDown() {
        try {
            server.stop(0)
            serverExecutor.shutdownNow()
            EffectBinaryService.getInstance().registryBaseUrl = originalRegistryBaseUrl
            EffectApplicationStateService.getInstance().loadState(originalApplicationState)
        } finally {
            super.tearDown()
        }
    }

    fun testLatestModeDownloadsManagedBinary() {
        registerLatestEndpoints(CURRENT_TSGO_VERSION)

        val binaryService = EffectBinaryService.getInstance()
        binaryService.registryBaseUrl = "http://127.0.0.1:${server.address.port}"

        project.getService(EffectProjectSettingsService::class.java).updateSettings(
            EffectProjectSettings(binaryMode = EffectBinaryMode.LATEST),
        )

        val resolution = binaryService.ensureAvailable(project)
        assertEquals(EffectBinaryMode.LATEST, resolution.mode)
        assertEquals(CURRENT_TSGO_VERSION, resolution.version)
        assertEquals(platformPackage, resolution.packageName)
        assertTrue(resolution.binaryPath.toString().contains(CURRENT_TSGO_VERSION))
        assertTrue(Files.exists(resolution.binaryPath))
    }

    fun testLatestModeSelectsStableBinaryMatchingWorkspaceTypeScript() {
        writeWorkspacePackage("typescript", "7.0.0", STABLE_TYPESCRIPT_HEAD)
        registerModernLatestEndpoints(CURRENT_TSGO_VERSION)

        val resolution = resolveLatest()

        assertEquals(stableBinaryName, resolution.binaryPath.fileName.toString())
        assertTrue(Files.exists(resolution.binaryPath.resolveSibling("$stableBinaryName.json")))
    }

    fun testLatestModeSelectsNextBinaryMatchingWorkspaceTypeScript() {
        writeWorkspacePackage("typescript", "7.1.0-dev.20260710.1", NEXT_TYPESCRIPT_HEAD)
        registerModernLatestEndpoints(CURRENT_TSGO_VERSION)

        val resolution = resolveLatest()

        assertEquals(nextBinaryName, resolution.binaryPath.fileName.toString())
    }

    fun testLatestModeFallsBackToNativePreviewWhenTypescriptIsNotNative() {
        writeWorkspacePackage("typescript", "6.0.3", "typescript-six-head")
        writeWorkspacePackage("@typescript/native-preview", "7.1.0-dev.20260710.1", NEXT_TYPESCRIPT_HEAD)
        registerModernLatestEndpoints(CURRENT_TSGO_VERSION)

        val resolution = resolveLatest()

        assertEquals(nextBinaryName, resolution.binaryPath.fileName.toString())
    }

    fun testLatestModeSupportsAliasedNativeTypeScriptPackages() {
        writeWorkspaceRootPackage(
            """{"devDependencies":{"custom-native":"npm:typescript@7.0.0"}}""",
        )
        writeWorkspacePackage("custom-native", "7.0.0", STABLE_TYPESCRIPT_HEAD, actualName = "typescript")
        registerModernLatestEndpoints(CURRENT_TSGO_VERSION)

        val resolution = resolveLatest()

        assertEquals(stableBinaryName, resolution.binaryPath.fileName.toString())
    }

    fun testLatestModeRejectsModernBinaryWhenWorkspaceTypeScriptDoesNotMatch() {
        writeWorkspacePackage("typescript", "7.0.0", "unmatched-head")
        registerModernLatestEndpoints(CURRENT_TSGO_VERSION)

        try {
            resolveLatest()
            fail("Expected a modern package with no matching TypeScript gitHead to be rejected")
        } catch (error: EffectBinaryException) {
            assertTrue(error.message?.contains("unmatched-head") == true)
            assertTrue(error.message?.contains("$stableBinaryName") == true)
            assertTrue(error.message?.contains("$nextBinaryName") == true)
        }
    }

    fun testLatestModeRejectsModernBinaryWhenNoNativeTypeScriptIsInstalled() {
        registerModernLatestEndpoints(CURRENT_TSGO_VERSION)

        try {
            resolveLatest()
            fail("Expected modern binary selection to require a native TypeScript workspace package")
        } catch (error: EffectBinaryException) {
            assertTrue(error.message?.contains("Install typescript >= 7") == true)
        }
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

    fun testManagedDownloadRejectsIntegrityMismatch() {
        val version = "8.8.8"
        val tarballName = "${platformPackage.substringAfter('/')}-${version}.tgz"
        val tarballPath = tempDir.resolve(tarballName)
        val cacheRoot = tempDir.resolve("bad-integrity-cache")
        writeLegacyTarball(tarballPath)

        server.createContext("/$platformPackage/$version") { exchange ->
            respondJson(
                exchange,
                """{"dist":{"tarball":"http://127.0.0.1:${server.address.port}/tarballs/$tarballName","integrity":"${badIntegrity()}"}}""",
            )
        }
        server.createContext("/tarballs/$tarballName") { exchange ->
            val bytes = Files.readAllBytes(tarballPath)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        val binaryService = EffectBinaryService.getInstance()
        binaryService.registryBaseUrl = "http://127.0.0.1:${server.address.port}"
        val applicationStateService = EffectApplicationStateService.getInstance()
        val originalApplicationState = applicationStateService.currentState()
        applicationStateService.loadState(originalApplicationState.copy(binaryCacheDirOverride = cacheRoot.toString()))

        project.getService(EffectProjectSettingsService::class.java).updateSettings(
            EffectProjectSettings(
                binaryMode = EffectBinaryMode.PINNED,
                pinnedVersion = version,
            ),
        )

        try {
            binaryService.ensureAvailable(project)
            fail("Expected managed download to reject mismatched npm integrity metadata")
        } catch (error: EffectBinaryException) {
            assertTrue(error.message?.contains("integrity") == true)
        } finally {
            applicationStateService.loadState(originalApplicationState)
        }

        assertFalse(Files.exists(cacheRoot.resolve(version)))
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
        assumeFalse("Windows does not expose POSIX executable permission semantics", SystemInfo.isWindows)
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

    fun testManualModeRejectsRelativePath() {
        project.getService(EffectProjectSettingsService::class.java).updateSettings(
            EffectProjectSettings(
                binaryMode = EffectBinaryMode.MANUAL,
                manualBinaryPath = "relative-tsgo",
            ),
        )

        try {
            EffectBinaryService.getInstance().ensureAvailable(project)
            fail("Expected manual mode to reject a relative binary path")
        } catch (error: EffectBinaryException) {
            assertTrue(error.message?.contains("absolute") == true)
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
        writeLegacyTarball(tarballPath)

        server.createContext("/$platformPackage/$version") { exchange ->
            metadataRequests.incrementAndGet()
            respondJson(
                exchange,
                metadataJson(version, tarballName, tarballPath),
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
        val applicationStateService = EffectApplicationStateService.getInstance()
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

    private fun registerModernLatestEndpoints(version: String) {
        server.createContext("/@effect/tsgo") { exchange ->
            respondJson(exchange, """{"dist-tags":{"latest":"$version"}}""")
        }
        registerModernPinnedEndpoint(version)
    }

    private fun registerPinnedEndpoint(version: String) {
        registerPackageEndpoint(version, ::writeLegacyTarball)
    }

    private fun registerModernPinnedEndpoint(version: String) {
        registerPackageEndpoint(version, ::writeModernTarball)
    }

    private fun registerPackageEndpoint(version: String, writeArchive: (Path) -> Unit) {
        val tarballName = "${platformPackage.substringAfter('/')}-${version}.tgz"
        val tarballPath = tempDir.resolve(tarballName)
        writeArchive(tarballPath)

        server.createContext("/$platformPackage/$version") { exchange ->
            respondJson(exchange, metadataJson(version, tarballName, tarballPath))
        }
        server.createContext("/tarballs/$tarballName") { exchange ->
            val bytes = Files.readAllBytes(tarballPath)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun writeLegacyTarball(path: Path) {
        writeTarball(path, mapOf("package/lib/$binaryName" to "binary"))
    }

    private fun writeModernTarball(path: Path) {
        writeTarball(
            path,
            mapOf(
                "package/lib/$stableBinaryName" to "stable-binary",
                "package/lib/$stableBinaryName.json" to
                    """{"tsVersion":"7.0.0","tsGitHead":"$STABLE_TYPESCRIPT_HEAD"}""",
                "package/lib/$nextBinaryName" to "next-binary",
                "package/lib/$nextBinaryName.json" to
                    """{"tsVersion":"7.1.0-dev.20260710.1","tsGitHead":"$NEXT_TYPESCRIPT_HEAD"}""",
            ),
        )
    }

    private fun writeTarball(path: Path, entries: Map<String, String>) {
        GzipCompressorOutputStream(Files.newOutputStream(path)).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                entries.forEach { (entryName, contents) ->
                    val data = contents.toByteArray()
                    val entry = TarArchiveEntry(entryName)
                    entry.size = data.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(data)
                    tar.closeArchiveEntry()
                }
                tar.finish()
            }
        }
    }

    private fun resolveLatest(): BinaryResolution {
        val binaryService = EffectBinaryService.getInstance()
        binaryService.registryBaseUrl = "http://127.0.0.1:${server.address.port}"
        project.getService(EffectProjectSettingsService::class.java).updateSettings(
            EffectProjectSettings(binaryMode = EffectBinaryMode.LATEST),
        )
        return binaryService.ensureAvailable(project)
    }

    private fun writeWorkspaceRootPackage(json: String) {
        val workspaceRoot = workspaceRoot()
        Files.createDirectories(workspaceRoot)
        Files.writeString(workspaceRoot.resolve("package.json"), json)
    }

    private fun writeWorkspacePackage(packageName: String, version: String, gitHead: String, actualName: String = packageName) {
        val workspaceRoot = workspaceRoot()
        val packageJson = workspaceRoot.resolve("node_modules").resolve(packageName).resolve("package.json")
        Files.createDirectories(packageJson.parent)
        Files.writeString(
            packageJson,
            """{"name":"$actualName","version":"$version","gitHead":"$gitHead"}""",
        )
    }

    private fun resetWorkspacePackages() {
        val workspaceRoot = workspaceRoot()
        listOf(workspaceRoot.resolve("node_modules"), workspaceRoot.resolve("package.json")).forEach { path ->
            if (!Files.exists(path)) return@forEach
            if (Files.isDirectory(path)) {
                Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            } else {
                Files.deleteIfExists(path)
            }
        }
    }

    private fun workspaceRoot(): Path = Path.of(requireNotNull(project.basePath))

    private fun metadataJson(version: String, tarballName: String, tarballPath: Path): String =
        """{"version":"$version","dist":{"tarball":"http://127.0.0.1:${server.address.port}/tarballs/$tarballName","integrity":"${integrity(tarballPath)}"}}"""

    private fun integrity(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-512").digest(Files.readAllBytes(path))
        return "sha512-${Base64.getEncoder().encodeToString(digest)}"
    }

    private fun badIntegrity(): String =
        "sha512-${Base64.getEncoder().encodeToString(ByteArray(64))}"

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
