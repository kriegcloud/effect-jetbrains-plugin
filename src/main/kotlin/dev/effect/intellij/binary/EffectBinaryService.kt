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
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.inputStream

@Service(Service.Level.APP)
class EffectBinaryService {
    private val log = logger<EffectBinaryService>()
    private val cacheLifecycleLock = ReentrantReadWriteLock()
    // Keep lock identities stable for the service lifetime so queued installers cannot split across replacement locks.
    private val versionRootLocks = ConcurrentHashMap<Path, ReentrantLock>()
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
            -> cacheLifecycleLock.readLock().withLock {
                status.markResolvingBinary("Resolving @effect/tsgo")
                val platform = currentPlatformPackage()
                val version = when (settings.binaryMode) {
                    EffectBinaryMode.LATEST -> {
                        status.markCheckingForUpdate("Checking latest @effect/tsgo version")
                        resolveLatestVersion()
                    }

                    EffectBinaryMode.PINNED -> settings.pinnedVersion
                    EffectBinaryMode.MANUAL -> error("unreachable")
                }.ifBlank {
                    throw EffectBinaryException("The configured @effect/tsgo version is blank.")
                }

                val cacheRoot = managedCacheRoot()
                val versionRoot = cacheRoot.resolve(version).resolve(platform.packageName)
                val binaryPath = versionRootLock(versionRoot).withLock {
                    var installed = false
                    fun reinstall() {
                        versionRoot.deleteRecursively()
                        status.markDownloadingBinary("Downloading ${platform.packageName}@$version")
                        installManagedPackage(platform, version, versionRoot)
                        installed = true
                    }

                    if (!isManagedInstallationHealthy(versionRoot, version, platform)) {
                        reinstall()
                    }

                    try {
                        selectManagedBinary(project, versionRoot.resolve("package").resolve("lib"), platform)
                    } catch (error: DamagedManagedPackageException) {
                        if (installed) {
                            throw EffectBinaryException(error.message.orEmpty(), error)
                        }
                        reinstall()
                        try {
                            selectManagedBinary(project, versionRoot.resolve("package").resolve("lib"), platform)
                        } catch (retryError: DamagedManagedPackageException) {
                            throw EffectBinaryException(retryError.message.orEmpty(), retryError)
                        }
                    }
                        .also(::ensureExecutable)
                }

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

        cacheLifecycleLock.writeLock().withLock {
            val cacheRoot = managedCacheRoot()
            val version = settings.pinnedVersion.takeIf { it.isNotBlank() }
            if (settings.binaryMode == EffectBinaryMode.PINNED && version != null) {
                cacheRoot.resolve(version).deleteRecursively()
            } else {
                cacheRoot.deleteRecursively()
            }
        }
    }

    private fun versionRootLock(versionRoot: Path): ReentrantLock =
        versionRootLocks.computeIfAbsent(versionRoot.toAbsolutePath().normalize()) { ReentrantLock() }

    private fun validateManualBinary(path: Path) {
        when {
            path.toString().isBlank() -> throw EffectBinaryException("Manual binary path is blank.")
            !path.isAbsolute -> throw EffectBinaryException("Manual binary path must be absolute: $path")
            !Files.exists(path) -> throw EffectBinaryException("Manual binary path does not exist: $path")
            !Files.isRegularFile(path) -> throw EffectBinaryException("Manual binary path must point to a file: $path")
            !Files.isExecutable(path) -> throw EffectBinaryException("Manual binary path must be executable: $path")
        }
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

    private fun installManagedPackage(platform: PlatformPackage, version: String, versionRoot: Path) {
        val packageName = platform.packageName
        val metadataUrl = "${registryBaseUrl.trimEnd('/')}/${encodePackageName(packageName)}/$version"
        val metadataResponse = sendStringRequest(HttpRequest.newBuilder(URI.create(metadataUrl)).GET().build())
        val metadataJson = EffectJson.mapper.readTree(metadataResponse.body())
        val tarballUrl = metadataJson.path("dist").path("tarball").asText().ifBlank {
            throw EffectBinaryException("npm metadata for $packageName@$version did not include a tarball URL.")
        }
        val integrity = metadataJson.path("dist").path("integrity").asText().ifBlank {
            throw EffectBinaryException("npm metadata for $packageName@$version did not include tarball integrity.")
        }

        val tempRoot = Files.createTempDirectory("effect-tsgo-download")
        val archivePath = tempRoot.resolve("package.tgz")
        val stagingRoot = versionRoot.resolveSibling("${versionRoot.fileName}.staging-${UUID.randomUUID()}")
        val archiveResponse = sendFileRequest(HttpRequest.newBuilder(URI.create(tarballUrl)).GET().build(), archivePath)
        if (archiveResponse.statusCode() !in 200..299) {
            archivePath.deleteIfExists()
            throw EffectBinaryException("Failed to download $packageName@$version from npm: HTTP ${archiveResponse.statusCode()}")
        }
        verifyArchiveIntegrity(packageName, version, archivePath, integrity)

        try {
            extractArchive(archivePath, stagingRoot)
            val stagedLib = stagingRoot.resolve("package").resolve("lib")
            val packagedBinaries = platform.packagedBinaryNames.map(stagedLib::resolve)
            if (packagedBinaries.none(Files::isRegularFile)) {
                throw EffectBinaryException(
                    "Downloaded $packageName@$version successfully, but no supported native binary was found in $stagedLib. " +
                        "Tried: ${packagedBinaries.joinToString()}.",
                )
            }
            writeInstallMarker(stagingRoot, version, platform)
            versionRoot.deleteRecursively()
            EffectFileUtil.atomicMove(stagingRoot, versionRoot)
        } catch (error: Exception) {
            if (error is EffectBinaryException) {
                throw error
            }
            throw EffectBinaryException("Failed to extract $packageName@$version: ${error.message}", error)
        } finally {
            tempRoot.deleteRecursively()
            stagingRoot.deleteRecursively()
        }
    }

    private fun writeInstallMarker(versionRoot: Path, version: String, platform: PlatformPackage) {
        val libRoot = versionRoot.resolve("package").resolve("lib")
        val marker = EffectJson.mapper.createObjectNode()
        marker.put("version", version)
        val files = marker.putObject("files")
        platform.packagedFileNames.forEach { fileName ->
            val file = libRoot.resolve(fileName)
            if (Files.isRegularFile(file)) {
                files.put(fileName, Files.size(file))
            }
        }
        Files.writeString(versionRoot.resolve(INSTALL_COMPLETE_MARKER), EffectJson.mapper.writeValueAsString(marker))
    }

    private fun isManagedInstallationHealthy(
        versionRoot: Path,
        version: String,
        platform: PlatformPackage,
    ): Boolean = runCatching {
        val markerPath = versionRoot.resolve(INSTALL_COMPLETE_MARKER)
        if (!Files.isRegularFile(markerPath)) {
            return@runCatching false
        }

        val marker = EffectJson.mapper.readTree(Files.readString(markerPath))
        val files = marker.path("files")
        if (marker.path("version").asText() != version || !files.isObject) {
            return@runCatching false
        }

        val installedFileNames = files.properties().asSequence().map { it.key }.toSet()
        if (installedFileNames.isEmpty() || installedFileNames.any { it !in platform.packagedFileNames }) {
            return@runCatching false
        }

        val libRoot = versionRoot.resolve("package").resolve("lib")
        if (installedFileNames.any { fileName ->
                val file = libRoot.resolve(fileName)
                !Files.isRegularFile(file) || Files.size(file) != files.path(fileName).asLong(-1)
            }
        ) {
            return@runCatching false
        }

        val installedModernBinaries = MODERN_BINARY_NAMES.filter { platform.executableName(it) in installedFileNames }
        if (installedModernBinaries.isNotEmpty()) {
            val manifest = if (UPSTREAM_MANIFEST_FILE_NAME in installedFileNames) readUpstreamManifest(libRoot) else null
            return@runCatching installedModernBinaries.all { binaryName ->
                val executableName = platform.executableName(binaryName)
                val adjacentMetadata = "$executableName.json" in installedFileNames &&
                    readBinaryMetadata(libRoot.resolve(executableName)) != null
                adjacentMetadata || manifest?.containsKey(binaryName) == true
            }
        }

        platform.executableName("tsgo") in installedFileNames
    }.getOrDefault(false)

    /**
     * Modern platform packages carry binaries for stable and nightly TypeScript together with
     * compatibility metadata: per-binary `<binary>.json` files up to `@effect/tsgo` 0.25.x, and a
     * single `upstream.json` profile manifest since 0.26.0. Select the binary built from the
     * workspace's exact TypeScript git head; older packages without metadata retain their
     * dedicated `tsgo` executable.
     */
    private fun selectManagedBinary(project: Project, libRoot: Path, platform: PlatformPackage): Path {
        val manifest = readUpstreamManifest(libRoot)
        val modernCandidates = MODERN_BINARY_NAMES
            .mapNotNull { binaryName ->
                val path = libRoot.resolve(platform.executableName(binaryName))
                if (Files.isRegularFile(path)) {
                    PackagedBinaryCandidate(path, readBinaryMetadata(path) ?: manifest?.get(binaryName))
                } else {
                    null
                }
            }

        if (modernCandidates.any { candidate -> candidate.metadata != null }) {
            val workspaceTypeScript = resolveWorkspaceTypeScript(project)
            modernCandidates.firstOrNull { candidate ->
                candidate.metadata?.tsGitHead == workspaceTypeScript.gitHead
            }?.let { return it.path }

            val tried = modernCandidates.joinToString(separator = "\n") { candidate ->
                val metadata = candidate.metadata
                if (metadata == null) {
                    "  ${candidate.path.fileName}: metadata missing or invalid"
                } else {
                    "  ${candidate.path.fileName}: TypeScript ${metadata.tsVersion}, gitHead ${metadata.tsGitHead}"
                }
            }
            throw EffectBinaryException(
                "No packaged @effect/tsgo binary matches ${workspaceTypeScript.packageName}@${workspaceTypeScript.version} " +
                    "gitHead ${workspaceTypeScript.gitHead}. Tried:\n$tried\n" +
                    "Install matching TypeScript and @effect/tsgo versions, or select a compatible executable in MANUAL mode.",
            )
        }

        val legacyBinary = libRoot.resolve(platform.executableName("tsgo"))
        if (legacyBinary.exists()) {
            return legacyBinary
        }

        if (modernCandidates.isNotEmpty()) {
            throw DamagedManagedPackageException(
                "The downloaded @effect/tsgo package contains ${modernCandidates.joinToString { it.path.fileName.toString() }}, " +
                    "but their TypeScript compatibility metadata (adjacent .json or upstream.json) is missing or invalid.",
            )
        }

        throw DamagedManagedPackageException("No supported @effect/tsgo executable was found in $libRoot.")
    }

    private fun readBinaryMetadata(binaryPath: Path): PackagedBinaryMetadata? {
        val metadataPath = binaryPath.resolveSibling("${binaryPath.fileName}.json")
        return runCatching {
            val json = EffectJson.mapper.readTree(Files.readString(metadataPath))
            val tsVersion = json.path("tsVersion").asText().trim()
            val tsGitHead = json.path("tsGitHead").asText().trim()
            require(tsVersion.isNotBlank() && tsGitHead.isNotBlank())
            PackagedBinaryMetadata(tsVersion, tsGitHead)
        }.getOrNull()
    }

    /**
     * Since 0.26.0, platform packages replace the per-binary metadata files with a single
     * `lib/upstream.json` profile manifest. Returns base binary name (`tsc`, `tsc-next`) to
     * metadata, or null when the manifest is absent or unusable.
     */
    private fun readUpstreamManifest(libRoot: Path): Map<String, PackagedBinaryMetadata>? {
        val manifestPath = libRoot.resolve(UPSTREAM_MANIFEST_FILE_NAME)
        return runCatching {
            val json = EffectJson.mapper.readTree(Files.readString(manifestPath))
            require(json.path("schemaVersion").asInt() == 2)
            val profiles = json.path("profiles")
            require(profiles.isArray)
            val entries = mutableMapOf<String, PackagedBinaryMetadata>()
            profiles.forEach { profile ->
                if (profile.path("kind").asText() != "ts") {
                    return@forEach
                }
                val binName = profile.path("binName").asText().trim()
                val tsVersion = profile.path("ts").path("npmVersion").asText().trim()
                val tsGitHead = profile.path("ts").path("gitHead").asText().trim()
                if (binName.isNotBlank() && tsVersion.isNotBlank() && tsGitHead.isNotBlank()) {
                    entries[binName] = PackagedBinaryMetadata(tsVersion, tsGitHead)
                }
            }
            require(entries.isNotEmpty())
            entries.toMap()
        }.getOrNull()
    }

    private fun resolveWorkspaceTypeScript(project: Project): WorkspaceTypeScript {
        val workspaceRoot = project.basePath?.takeIf(String::isNotBlank)?.let { parsePath(it, "Project workspace") }
            ?: throw EffectBinaryException(
                "Cannot select a compatible @effect/tsgo binary because the project has no workspace path.",
            )
        val packageNames = buildList {
            add("typescript")
            addAll(readTypeScriptAliases(workspaceRoot))
            add("@typescript/native")
            add("@typescript/native-preview")
        }.distinct()

        val missingGitHeads = mutableListOf<String>()
        for (nodeModulesRoot in nodeModulesRoots(workspaceRoot)) {
            for (packageName in packageNames) {
                val packageJson = nodeModulesRoot.resolve(packageName).resolve("package.json")
                val metadata = runCatching { EffectJson.mapper.readTree(Files.readString(packageJson)) }.getOrNull() ?: continue
                val version = metadata.path("version").asText().trim()
                if (!isNativeTypeScriptVersion(version)) {
                    continue
                }
                val gitHead = metadata.path("gitHead").asText().trim()
                if (gitHead.isBlank()) {
                    missingGitHeads += "$packageName@$version"
                    continue
                }
                return WorkspaceTypeScript(packageName, version, gitHead)
            }
        }

        val detail = if (missingGitHeads.isEmpty()) {
            "None of ${packageNames.joinToString()} is installed with a native TypeScript 7+ version."
        } else {
            "Missing gitHead metadata for ${missingGitHeads.joinToString()}."
        }
        throw EffectBinaryException(
            "Cannot select a compatible @effect/tsgo binary for $workspaceRoot. $detail " +
                "Install typescript >= 7 (or a native TypeScript alias), then retry.",
        )
    }

    private fun readTypeScriptAliases(workspaceRoot: Path): List<String> {
        val packageJson = workspaceRoot.resolve("package.json")
        val root = runCatching { EffectJson.mapper.readTree(Files.readString(packageJson)) }.getOrNull() ?: return emptyList()
        return TYPESCRIPT_DEPENDENCY_SECTIONS.flatMap { section ->
            val dependencies = root.path(section)
            if (!dependencies.isObject) {
                emptyList()
            } else {
                dependencies.properties().asSequence()
                    .filter { (_, version) -> version.asText().isTypeScriptAliasSpecifier() }
                    .map { (name, _) -> name }
                    .toList()
            }
        }
    }

    private fun nodeModulesRoots(workspaceRoot: Path): Sequence<Path> =
        generateSequence(workspaceRoot.toAbsolutePath().normalize()) { current -> current.parent }
            .map { ancestor -> ancestor.resolve("node_modules") }

    private fun String.isTypeScriptAliasSpecifier(): Boolean {
        val normalized = trim().lowercase()
        return normalized.startsWith("npm:typescript@") ||
            normalized.startsWith("npm:@typescript/native@") ||
            normalized.startsWith("npm:@typescript/native-preview@")
    }

    private fun isNativeTypeScriptVersion(version: String): Boolean =
        version.substringBefore('.').toIntOrNull()?.let { it >= 7 } == true

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

    private fun verifyArchiveIntegrity(packageName: String, version: String, archivePath: Path, integrity: String) {
        val accepted = integrity
            .splitToSequence(' ')
            .map(String::trim)
            .filter(String::isNotBlank)
            .any { token -> tokenMatchesArchive(token, archivePath) }

        if (!accepted) {
            archivePath.deleteIfExists()
            throw EffectBinaryException("Downloaded $packageName@$version did not match npm integrity metadata.")
        }
    }

    private fun tokenMatchesArchive(token: String, archivePath: Path): Boolean {
        val algorithm = token.substringBefore('-', missingDelimiterValue = "")
        val expected = token.substringAfter('-', missingDelimiterValue = "")
        if (algorithm.isBlank() || expected.isBlank()) {
            return false
        }

        val jdkAlgorithm = when (algorithm.lowercase()) {
            "sha512" -> "SHA-512"
            "sha384" -> "SHA-384"
            "sha256" -> "SHA-256"
            "sha1" -> "SHA-1"
            else -> return false
        }

        val digest = MessageDigest.getInstance(jdkAlgorithm)
        archivePath.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }

        return Base64.getEncoder().encodeToString(digest.digest()) == expected
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

        return PlatformPackage(
            packageName = "$BASE_PACKAGE_NAME-$os-$arch",
            executableSuffix = if (os == "win32") ".exe" else "",
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
        private const val INSTALL_COMPLETE_MARKER = ".install-complete"
        private val TYPESCRIPT_DEPENDENCY_SECTIONS = listOf(
            "dependencies",
            "devDependencies",
            "optionalDependencies",
            "peerDependencies",
        )

        fun getInstance(): EffectBinaryService = ApplicationManager.getApplication().getService(EffectBinaryService::class.java)
    }
}

private data class PlatformPackage(
    val packageName: String,
    val executableSuffix: String,
) {
    fun executableName(baseName: String): String = "$baseName$executableSuffix"

    val packagedBinaryNames: List<String>
        get() = SUPPORTED_BINARY_NAMES.map(::executableName)

    val packagedFileNames: Set<String>
        get() = (packagedBinaryNames.flatMap { binaryName -> listOf(binaryName, "$binaryName.json") } + UPSTREAM_MANIFEST_FILE_NAME)
            .toSet()
}

private data class PackagedBinaryMetadata(
    val tsVersion: String,
    val tsGitHead: String,
)

private data class PackagedBinaryCandidate(
    val path: Path,
    val metadata: PackagedBinaryMetadata?,
)

private data class WorkspaceTypeScript(
    val packageName: String,
    val version: String,
    val gitHead: String,
)

private class DamagedManagedPackageException(message: String) : RuntimeException(message)

private val MODERN_BINARY_NAMES = listOf("tsc", "tsc-next")
private val SUPPORTED_BINARY_NAMES = MODERN_BINARY_NAMES + "tsgo"
private const val UPSTREAM_MANIFEST_FILE_NAME = "upstream.json"
