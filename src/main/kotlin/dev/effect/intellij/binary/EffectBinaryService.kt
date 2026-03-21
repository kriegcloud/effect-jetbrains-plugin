package dev.effect.intellij.binary

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.effect.intellij.core.EffectFileUtil
import dev.effect.intellij.core.EffectJson
import dev.effect.intellij.core.EffectPluginConstants
import dev.effect.intellij.core.logger
import dev.effect.intellij.settings.EffectApplicationStateService
import dev.effect.intellij.settings.EffectBinaryMode
import dev.effect.intellij.settings.EffectProjectSettingsService
import dev.effect.intellij.status.EffectStatusService
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.inputStream

@Service(Service.Level.APP)
class EffectBinaryService {
    private val log = logger<EffectBinaryService>()
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    internal var registryBaseUrl: String = DEFAULT_REGISTRY_BASE_URL

    fun resolve(project: Project): BinaryResolution = ensureAvailable(project)

    fun ensureAvailable(project: Project): BinaryResolution {
        val settings = EffectProjectSettingsService.getInstance(project).currentSettings()
        val status = EffectStatusService.getInstance(project)

        return when (settings.binaryMode) {
            EffectBinaryMode.MANUAL -> {
                val manualPath = parsePath(settings.manualBinaryPath, "Manual binary path")
                validateManualBinary(manualPath)
                BinaryResolution(
                    mode = EffectBinaryMode.MANUAL,
                    version = null,
                    packageName = null,
                    binaryPath = manualPath,
                    source = BinarySource.MANUAL,
                    cacheDirectory = null,
                )
            }

            EffectBinaryMode.LATEST,
            EffectBinaryMode.PINNED,
            -> {
                status.markResolvingBinary("Resolving @effect/tsgo")
                val platform = currentPlatformPackage()
                val version = when (settings.binaryMode) {
                    EffectBinaryMode.LATEST -> resolveLatestVersion()
                    EffectBinaryMode.PINNED -> settings.pinnedVersion
                    EffectBinaryMode.MANUAL -> error("unreachable")
                }.ifBlank {
                    throw EffectBinaryException("The configured @effect/tsgo version is blank.")
                }

                val cacheRoot = managedCacheRoot()
                val versionRoot = cacheRoot.resolve(version).resolve(platform.packageName)
                val binaryPath = versionRoot.resolve("package").resolve("lib").resolve(platform.binaryName)

                if (!binaryPath.exists()) {
                    downloadPackage(platform.packageName, version, versionRoot, binaryPath)
                }

                ensureExecutable(binaryPath)

                BinaryResolution(
                    mode = settings.binaryMode,
                    version = version,
                    packageName = platform.packageName,
                    binaryPath = binaryPath,
                    source = BinarySource.MANAGED_CACHE,
                    cacheDirectory = versionRoot,
                )
            }
        }
    }

    fun invalidate(project: Project) {
        val settings = EffectProjectSettingsService.getInstance(project).currentSettings()
        if (settings.binaryMode == EffectBinaryMode.MANUAL) {
            return
        }

        val cacheRoot = managedCacheRoot()
        val version = settings.pinnedVersion.takeIf { it.isNotBlank() }
        if (settings.binaryMode == EffectBinaryMode.PINNED && version != null) {
            cacheRoot.resolve(version).deleteRecursively()
        } else {
            cacheRoot.deleteRecursively()
        }
    }

    private fun validateManualBinary(path: Path) {
        require(path.toString().isNotBlank()) { "Manual binary path is blank." }
        require(Files.exists(path)) { "Manual binary path does not exist: $path" }
        require(Files.isRegularFile(path)) { "Manual binary path must point to a file: $path" }
        require(Files.isExecutable(path)) { "Manual binary path must be executable: $path" }
    }

    private fun managedCacheRoot(): Path {
        val state = EffectApplicationStateService.getInstance().currentState()
        val override = state.binaryCacheDirOverride.trim()
        return if (override.isNotBlank()) {
            EffectFileUtil.ensureDirectory(parsePath(override, "Binary cache override"))
        } else {
            EffectFileUtil.systemCacheDir(EffectPluginConstants.DEFAULT_BINARY_CACHE_DIR)
        }
    }

    private fun resolveLatestVersion(): String {
        val url = "${registryBaseUrl.trimEnd('/')}/${encodePackageName(BASE_PACKAGE_NAME)}"
        val response = sendStringRequest(HttpRequest.newBuilder(URI.create(url)).GET().build())
        val json = EffectJson.mapper.readTree(response.body())
        return json.path("dist-tags").path("latest").asText().ifBlank {
            throw EffectBinaryException("Could not resolve the latest version of $BASE_PACKAGE_NAME from npm.")
        }
    }

    private fun downloadPackage(packageName: String, version: String, versionRoot: Path, binaryPath: Path) {
        val metadataUrl = "${registryBaseUrl.trimEnd('/')}/${encodePackageName(packageName)}/$version"
        val metadataResponse = sendStringRequest(HttpRequest.newBuilder(URI.create(metadataUrl)).GET().build())
        val metadataJson = EffectJson.mapper.readTree(metadataResponse.body())
        val tarballUrl = metadataJson.path("dist").path("tarball").asText().ifBlank {
            throw EffectBinaryException("npm metadata for $packageName@$version did not include a tarball URL.")
        }

        val tempRoot = Files.createTempDirectory("effect-tsgo-download")
        val archivePath = tempRoot.resolve("package.tgz")
        val archiveResponse = sendFileRequest(HttpRequest.newBuilder(URI.create(tarballUrl)).GET().build(), archivePath)
        if (archiveResponse.statusCode() !in 200..299) {
            archivePath.deleteIfExists()
            throw EffectBinaryException("Failed to download $packageName@$version from npm: HTTP ${archiveResponse.statusCode()}")
        }

        try {
            extractArchive(archivePath, versionRoot)
        } catch (error: Exception) {
            versionRoot.deleteRecursively()
            throw EffectBinaryException("Failed to extract $packageName@$version: ${error.message}", error)
        } finally {
            tempRoot.deleteRecursively()
        }

        if (!binaryPath.exists()) {
            versionRoot.deleteRecursively()
            throw EffectBinaryException(
                "Downloaded $packageName@$version successfully, but the native binary was not found at $binaryPath.",
            )
        }
    }

    private fun extractArchive(archivePath: Path, destinationRoot: Path) {
        destinationRoot.deleteRecursively()
        Files.createDirectories(destinationRoot)

        archivePath.inputStream().use { rawInput ->
            GzipCompressorInputStream(BufferedInputStream(rawInput)).use { gzipInput ->
                TarArchiveInputStream(gzipInput).use { tarInput ->
                    while (true) {
                        val entry = tarInput.nextEntry ?: break
                        val entryPath = destinationRoot.resolve(entry.name).normalize()
                        if (!entryPath.startsWith(destinationRoot)) {
                            throw EffectBinaryException("Refusing to extract archive entry outside the cache root: ${entry.name}")
                        }

                        if (entry.isDirectory) {
                            Files.createDirectories(entryPath)
                            continue
                        }

                        Files.createDirectories(entryPath.parent)
                        Files.copy(tarInput, entryPath, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
    }

    private fun ensureExecutable(path: Path) {
        if (isWindows()) {
            return
        }

        try {
            val permissions = Files.getPosixFilePermissions(path).toMutableSet()
            permissions += PosixFilePermission.OWNER_EXECUTE
            permissions += PosixFilePermission.OWNER_READ
            permissions += PosixFilePermission.GROUP_EXECUTE
            permissions += PosixFilePermission.GROUP_READ
            Files.setPosixFilePermissions(path, permissions)
        } catch (error: UnsupportedOperationException) {
            val changed = path.toFile().setExecutable(true, false)
            if (!changed) {
                throw EffectBinaryException("Binary is not executable and could not be updated: $path")
            }
        }
    }

    private fun sendStringRequest(request: HttpRequest): HttpResponse<String> {
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (error: Exception) {
            throw EffectBinaryException("Could not reach npm while resolving @effect/tsgo: ${error.message}", error)
        }

        if (response.statusCode() !in 200..299) {
            throw EffectBinaryException("npm returned HTTP ${response.statusCode()} for ${request.uri()}")
        }

        return response
    }

    private fun sendFileRequest(request: HttpRequest, target: Path): HttpResponse<Path> {
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofFile(target))
        } catch (error: Exception) {
            throw EffectBinaryException("Could not download @effect/tsgo from npm: ${error.message}", error)
        }

        if (response.statusCode() !in 200..299) {
            throw EffectBinaryException("npm returned HTTP ${response.statusCode()} for ${request.uri()}")
        }

        return response
    }

    private fun currentPlatformPackage(): PlatformPackage {
        val osName = System.getProperty("os.name").lowercase()
        val archName = System.getProperty("os.arch").lowercase()

        val os = when {
            osName.contains("mac") -> "darwin"
            osName.contains("linux") -> "linux"
            osName.contains("win") -> "win32"
            else -> throw EffectBinaryException("Unsupported operating system for @effect/tsgo: $osName")
        }

        val arch = when {
            archName == "aarch64" || archName == "arm64" -> "arm64"
            archName == "x86_64" || archName == "amd64" -> "x64"
            archName.startsWith("arm") && os == "linux" -> "arm"
            else -> throw EffectBinaryException("Unsupported architecture for @effect/tsgo: $archName")
        }

        val binaryName = if (os == "win32") "tsgo.exe" else "tsgo"
        return PlatformPackage(
            packageName = "$BASE_PACKAGE_NAME-$os-$arch",
            binaryName = binaryName,
        )
    }

    private fun encodePackageName(packageName: String): String =
        URLEncoder.encode(packageName, StandardCharsets.UTF_8).replace("+", "%20")

    private fun Path.deleteRecursively() {
        if (!exists()) {
            return
        }
        Files.walk(this).use { paths ->
            paths
                .sorted(Comparator.reverseOrder())
                .forEach { path ->
                    try {
                        Files.deleteIfExists(path)
                    } catch (error: Exception) {
                        log.warn("Failed to delete $path", error)
                    }
                }
        }
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")

    private fun parsePath(raw: String, label: String): Path =
        try {
            Path.of(raw)
        } catch (error: InvalidPathException) {
            throw EffectBinaryException("$label is not a valid filesystem path: $raw", error)
        }

    companion object {
        private const val DEFAULT_REGISTRY_BASE_URL = "https://registry.npmjs.org"
        private const val BASE_PACKAGE_NAME = "@effect/tsgo"

        fun getInstance(): EffectBinaryService = ApplicationManager.getApplication().getService(EffectBinaryService::class.java)
    }
}

private data class PlatformPackage(
    val packageName: String,
    val binaryName: String,
)
