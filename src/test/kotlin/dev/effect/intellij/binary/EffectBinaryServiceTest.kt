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

private const val CURRENT_TSGO_VERSION = "0.27.1"
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

    fun testLatestModeSupportsAliasedNativeTypeScriptHoistedAtAncestor() {
        val aliasName = "effect-tsgo-hoisted-native-${System.nanoTime()}"
        val hoistedPackageRoot = requireNotNull(workspaceRoot().parent)
            .resolve("node_modules")
            .resolve(aliasName)
        writeWorkspaceRootPackage(
            """{"devDependencies":{"$aliasName":"npm:typescript@7.0.0"}}""",
        )
        writePackage(hoistedPackageRoot.resolve("package.json"), "typescript", "7.0.0", STABLE_TYPESCRIPT_HEAD)
        registerModernLatestEndpoints(CURRENT_TSGO_VERSION)

        try {
            val resolution = resolveLatest()

            assertEquals(stableBinaryName, resolution.binaryPath.fileName.toString())
        } finally {
            deleteRecursively(hoistedPackageRoot)
        }
    }

    fun testLatestModeRepairsDamagedCachedInstallationOnce() {
        val metadataRequests = AtomicInteger(0)
        val tarballRequests = AtomicInteger(0)
        writeWorkspacePackage("typescript", "7.0.0", STABLE_TYPESCRIPT_HEAD)
        registerModernLatestEndpoints(CURRENT_TSGO_VERSION, metadataRequests, tarballRequests)

        val initial = resolveLatest()
        val marker = requireNotNull(initial.cacheDirectory).resolve(".install-complete")
        assertTrue(Files.exists(marker))
        Files.delete(initial.binaryPath.resolveSibling("$stableBinaryName.json"))

        val repaired = resolveLatest()

        assertEquals(stableBinaryName, repaired.binaryPath.fileName.toString())
        assertTrue(Files.exists(repaired.binaryPath.resolveSibling("$stableBinaryName.json")))
        assertEquals(2, metadataRequests.get())
        assertEquals(2, tarballRequests.get())
    }

    fun testLatestModeSelectsStableBinaryFromUpstreamManifest() {
        writeWorkspacePackage("typescript", "7.0.0", STABLE_TYPESCRIPT_HEAD)
        registerManifestLatestEndpoints(CURRENT_TSGO_VERSION)

        val resolution = resolveLatest()

        assertEquals(stableBinaryName, resolution.binaryPath.fileName.toString())
        assertTrue(Files.exists(resolution.binaryPath.resolveSibling("upstream.json")))
        assertFalse(Files.exists(resolution.binaryPath.resolveSibling("$stableBinaryName.json")))
    }

    fun testLatestModeSelectsNextBinaryFromUpstreamManifest() {
        writeWorkspacePackage("typescript", "7.1.0-dev.20260710.1", NEXT_TYPESCRIPT_HEAD)
        registerManifestLatestEndpoints(CURRENT_TSGO_VERSION)

        val resolution = resolveLatest()

        assertEquals(nextBinaryName, resolution.binaryPath.fileName.toString())
    }

    fun testLatestModeReusesHealthyManifestInstallation() {
        val metadataRequests = AtomicInteger(0)
        val tarballRequests = AtomicInteger(0)
        writeWorkspacePackage("typescript", "7.0.0", STABLE_TYPESCRIPT_HEAD)
        registerManifestLatestEndpoints(CURRENT_TSGO_VERSION, metadataRequests, tarballRequests)

        resolveLatest()
        val again = resolveLatest()

        assertEquals(stableBinaryName, again.binaryPath.fileName.toString())
        assertEquals(1, metadataRequests.get())
        assertEquals(1, tarballRequests.get())
    }

    fun testLatestModeRepairsManifestInstallationWhenManifestDeleted() {
        val metadataRequests = AtomicInteger(0)
        val tarballRequests = AtomicInteger(0)
        writeWorkspacePackage("typescript", "7.0.0", STABLE_TYPESCRIPT_HEAD)
        registerManifestLatestEndpoints(CURRENT_TSGO_VERSION, metadataRequests, tarballRequests)

        val initial = resolveLatest()
        Files.delete(initial.binaryPath.resolveSibling("upstream.json"))

        val repaired = resolveLatest()

        assertEquals(stableBinaryName, repaired.binaryPath.fileName.toString())
        assertTrue(Files.exists(repaired.binaryPath.resolveSibling("upstream.json")))
        assertEquals(2, metadataRequests.get())
        assertEquals(2, tarballRequests.get())
    }

    fun testLatestModeRejectsManifestPackageWhenWorkspaceTypeScriptDoesNotMatch() {
        writeWorkspacePackage("typescript", "7.0.0", "unmatched-head")
        registerManifestLatestEndpoints(CURRENT_TSGO_VERSION)

        try {
            resolveLatest()
            fail("Expected a manifest package with no matching TypeScript gitHead to be rejected")
        } catch (error: EffectBinaryException) {
            assertTrue(error.message?.contains("unmatched-head") == true)
            assertTrue(error.message?.contains(STABLE_TYPESCRIPT_HEAD) == true)
            assertTrue(error.message?.contains(NEXT_TYPESCRIPT_HEAD) == true)
        }
    }

    fun testLatestModeRejectsModernBinaryWhenWorkspaceTypeScriptDoesNotMatch() {
        val metadataRequests = AtomicInteger(0)
        val tarballRequests = AtomicInteger(0)
        writeWorkspacePackage("typescript", "7.0.0", "unmatched-head")
        registerModernLatestEndpoints(CURRENT_TSGO_VERSION, metadataRequests, tarballRequests)

        repeat(2) {
            try {
                resolveLatest()
                fail("Expected a modern package with no matching TypeScript gitHead to be rejected")
            } catch (error: EffectBinaryException) {
                assertTrue(error.message?.contains("unmatched-head") == true)
                assertTrue(error.message?.contains("$stableBinaryName") == true)
                assertTrue(error.message?.contains("$nextBinaryName") == true)
            }
        }
        assertEquals(1, metadataRequests.get())
        assertEquals(1, tarballRequests.get())
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
        if (SystemInfo.isWindows) return
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

    fun testConcurrentManagedResolutionDownloadsDistinctVersionsInParallel() {
        val versions = listOf("4.5.7", "4.5.8")
        val cacheRoot = tempDir.resolve("parallel-managed-cache")
        val tarballRequests = AtomicInteger(0)
        val firstDownloadStarted = CountDownLatch(1)
        val bothDownloadsStarted = CountDownLatch(versions.size)
        val releaseDownloads = CountDownLatch(1)

        versions.forEach { version ->
            val tarballName = "${platformPackage.substringAfter('/')}-${version}.tgz"
            val tarballPath = tempDir.resolve(tarballName)
            writeLegacyTarball(tarballPath)
            server.createContext("/$platformPackage/$version") { exchange ->
                respondJson(exchange, metadataJson(version, tarballName, tarballPath))
            }
            server.createContext("/tarballs/$tarballName") { exchange ->
                tarballRequests.incrementAndGet()
                bothDownloadsStarted.countDown()
                if (version == versions.first()) {
                    firstDownloadStarted.countDown()
                }
                releaseDownloads.await(15, TimeUnit.SECONDS)
                val bytes = Files.readAllBytes(tarballPath)
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }

        val binaryService = EffectBinaryService.getInstance()
        binaryService.registryBaseUrl = "http://127.0.0.1:${server.address.port}"
        val applicationStateService = EffectApplicationStateService.getInstance()
        val originalApplicationState = applicationStateService.currentState()
        applicationStateService.loadState(originalApplicationState.copy(binaryCacheDirOverride = cacheRoot.toString()))
        project.getService(EffectProjectSettingsService::class.java).updateSettings(
            EffectProjectSettings(binaryMode = EffectBinaryMode.PINNED, pinnedVersion = versions.first()),
        )

        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<BinaryResolution> {
                binaryService.ensureAvailable(project)
            }
            assertTrue("First pinned version did not start downloading", firstDownloadStarted.await(5, TimeUnit.SECONDS))

            project.getService(EffectProjectSettingsService::class.java).updateSettings(
                EffectProjectSettings(binaryMode = EffectBinaryMode.PINNED, pinnedVersion = versions.last()),
            )
            val second = executor.submit<BinaryResolution> {
                binaryService.ensureAvailable(project)
            }

            assertTrue(
                "Downloads for distinct pinned versions should overlap",
                bothDownloadsStarted.await(5, TimeUnit.SECONDS),
            )
            releaseDownloads.countDown()

            val completed = listOf(first, second).map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(versions.toSet(), completed.mapNotNull(BinaryResolution::version).toSet())
            assertEquals(versions.size, tarballRequests.get())
        } finally {
            releaseDownloads.countDown()
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

    private fun registerModernLatestEndpoints(
        version: String,
        metadataRequests: AtomicInteger? = null,
        tarballRequests: AtomicInteger? = null,
    ) {
        server.createContext("/@effect/tsgo") { exchange ->
            respondJson(exchange, """{"dist-tags":{"latest":"$version"}}""")
        }
        registerModernPinnedEndpoint(version, metadataRequests, tarballRequests)
    }

    private fun registerManifestLatestEndpoints(
        version: String,
        metadataRequests: AtomicInteger? = null,
        tarballRequests: AtomicInteger? = null,
    ) {
        server.createContext("/@effect/tsgo") { exchange ->
            respondJson(exchange, """{"dist-tags":{"latest":"$version"}}""")
        }
        registerPackageEndpoint(version, ::writeManifestTarball, metadataRequests, tarballRequests)
    }

    private fun registerPinnedEndpoint(version: String) {
        registerPackageEndpoint(version, ::writeLegacyTarball)
    }

    private fun registerModernPinnedEndpoint(
        version: String,
        metadataRequests: AtomicInteger? = null,
        tarballRequests: AtomicInteger? = null,
    ) {
        registerPackageEndpoint(version, ::writeModernTarball, metadataRequests, tarballRequests)
    }

    private fun registerPackageEndpoint(
        version: String,
        writeArchive: (Path) -> Unit,
        metadataRequests: AtomicInteger? = null,
        tarballRequests: AtomicInteger? = null,
    ) {
        val tarballName = "${platformPackage.substringAfter('/')}-${version}.tgz"
        val tarballPath = tempDir.resolve(tarballName)
        writeArchive(tarballPath)

        server.createContext("/$platformPackage/$version") { exchange ->
            metadataRequests?.incrementAndGet()
            respondJson(exchange, metadataJson(version, tarballName, tarballPath))
        }
        server.createContext("/tarballs/$tarballName") { exchange ->
            tarballRequests?.incrementAndGet()
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

    private fun writeManifestTarball(path: Path) {
        writeTarball(
            path,
            mapOf(
                "package/lib/$stableBinaryName" to "stable-binary",
                "package/lib/$nextBinaryName" to "next-binary",
                "package/lib/upstream.json" to
                    """
                    {"schemaVersion":2,"profiles":[
                      {"kind":"ts","name":"next","ts":{"npmVersion":"7.1.0-dev.20260710.1","gitHead":"$NEXT_TYPESCRIPT_HEAD"},"binName":"tsc-next"},
                      {"kind":"ts","name":"latest","ts":{"npmVersion":"7.0.0","gitHead":"$STABLE_TYPESCRIPT_HEAD"},"binName":"tsc"},
                      {"kind":"oxlint","name":"oxlint","ts":{"npmVersion":"7.0.0","gitHead":"$STABLE_TYPESCRIPT_HEAD"},"tsgolint":{"npmVersion":"7.0.2001","gitHead":"tsgolint-head"},"oxlint":{"npmVersion":"1.77.0","gitHead":"oxlint-head"}}
                    ]}
                    """.trimIndent(),
                "package/lib/tsgolint" to "tsgolint-binary",
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
        writePackage(packageJson, actualName, version, gitHead)
    }

    private fun writePackage(packageJson: Path, actualName: String, version: String, gitHead: String) {
        Files.createDirectories(packageJson.parent)
        Files.writeString(
            packageJson,
            """{"name":"$actualName","version":"$version","gitHead":"$gitHead"}""",
        )
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
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
