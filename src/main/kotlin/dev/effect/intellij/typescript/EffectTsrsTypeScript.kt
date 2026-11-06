package dev.effect.intellij.typescript

import com.intellij.openapi.util.SystemInfo
import dev.effect.intellij.core.EffectJson
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Where a tsrs executable was found, in the order [EffectTsrsLocator] prefers them. */
enum class TsrsSource(val label: String) {
    WORKSPACE_TSRS_PACKAGE("workspace tsrs package"),
    WORKSPACE_EFFECT_TSGO_PACKAGE("workspace @effect/tsgo package"),
    MANAGED_EFFECT_TSGO_CACHE("Effect plugin managed @effect/tsgo cache"),
}

/**
 * One usable tsrs (Rust port of the TypeScript 7 compiler) executable.
 *
 * [effectPatched] is true when the executable embeds the Effect language service. WebStorm's own
 * TypeScript service would then report Effect diagnostics a second time, next to the Effect TSGO
 * server, so a stock sibling (`tsrs.original`, left behind by `effect-tsgo patch --tsrs`) is preferred.
 */
data class TsrsCandidate(
    val executable: Path,
    val typeScriptVersion: String,
    val tsrsVersion: String,
    val source: TsrsSource,
    val effectPatched: Boolean,
) {
    val description: String
        get() = "tsrs $tsrsVersion (TypeScript $typeScriptVersion${if (effectPatched) ", Effect-patched" else ""}) from ${source.label}"
}

/** Host naming used by both the `@typescript/typescript-<os>-<arch>` and `@tsrs/<os>-<arch>[-libc]` packages. */
data class TsrsPlatform(val os: String, val arch: String, val musl: Boolean = false) {
    val isWindows: Boolean get() = os == "win32"
    val executableName: String get() = if (isWindows) "tsrs.exe" else "tsrs"
    val typeScriptExecutableName: String get() = if (isWindows) "tsc.exe" else "tsc"

    companion object {
        /** musl hosts ship the musl dynamic loader (`/lib/ld-musl-<arch>.so.1`) instead of glibc's. */
        private fun isMuslHost(): Boolean =
            runCatching { Path.of("/lib").listDirectoryEntries("ld-musl-*").isNotEmpty() }.getOrDefault(false)

        fun current(): TsrsPlatform? {
            val os = when {
                SystemInfo.isLinux -> "linux"
                SystemInfo.isMac -> "darwin"
                SystemInfo.isWindows -> "win32"
                else -> return null
            }
            val arch = when (System.getProperty("os.arch").lowercase()) {
                "amd64", "x86_64" -> "x64"
                "aarch64", "arm64" -> "arm64"
                "arm" -> "arm"
                else -> return null
            }
            return TsrsPlatform(os, arch, musl = os == "linux" && isMuslHost())
        }
    }
}

/**
 * Finds tsrs executables for a workspace without running them: the `@tsrs/<platform>` package a
 * project installs through `tsrs`, the `artifacts/tsrs/<version>/tsrs` build shipped inside the
 * `@effect/tsgo-<platform>` package, and the same artifact in the plugin's managed package cache.
 */
object EffectTsrsLocator {
    private val tsrsVersionPattern = Regex("""-ts(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)$""")

    fun find(workspaceRoot: Path, managedCacheRoot: Path?, platform: TsrsPlatform): List<TsrsCandidate> = buildList {
        for (nodeModules in nodeModulesRoots(workspaceRoot)) {
            addAll(fromTsrsPackages(nodeModules.resolve("@tsrs"), platform))
        }
        for (nodeModules in nodeModulesRoots(workspaceRoot)) {
            fromArtifactsRoot(
                nodeModules.resolve("@effect").resolve("tsgo-${platform.os}-${platform.arch}").resolve("artifacts").resolve("tsrs"),
                platform,
                TsrsSource.WORKSPACE_EFFECT_TSGO_PACKAGE,
            )?.let(::add)
        }
        if (managedCacheRoot != null) {
            addAll(fromManagedCache(managedCacheRoot, platform))
        }
    }.distinctBy { it.executable.toAbsolutePath().normalize() }

    /** `0.22.0-ts7.1.0-beta` → `7.1.0-beta`. */
    fun typeScriptVersionOf(tsrsVersion: String): String? = tsrsVersionPattern.find(tsrsVersion.trim())?.groupValues?.get(1)

    private fun nodeModulesRoots(workspaceRoot: Path): Sequence<Path> =
        generateSequence(workspaceRoot.toAbsolutePath().normalize()) { it.parent }
            .map { it.resolve("node_modules") }
            .filter { it.isDirectory() }

    private fun fromTsrsPackages(scopeRoot: Path, platform: TsrsPlatform): List<TsrsCandidate> {
        if (!scopeRoot.isDirectory()) return emptyList()
        val prefix = "${platform.os}-${platform.arch}"
        return safeList(scopeRoot)
            .filter { it.isDirectory() && (it.name == prefix || it.name.startsWith("$prefix-")) }
            // The build matching the host C library first: glibc normally, musl on musl hosts (Alpine).
            .sortedBy { if (it.name.contains("musl") == platform.musl) 0 else 1 }
            .mapNotNull { packageRoot ->
                val tsrsVersion = readVersion(packageRoot.resolve("package.json")) ?: return@mapNotNull null
                val typeScriptVersion = typeScriptVersionOf(tsrsVersion) ?: return@mapNotNull null
                val executable = packageRoot.resolve(platform.executableName)
                val original = packageRoot.resolve("${platform.executableName}.original")
                when {
                    original.isRegularFile() ->
                        TsrsCandidate(original, typeScriptVersion, tsrsVersion, TsrsSource.WORKSPACE_TSRS_PACKAGE, effectPatched = false)
                    executable.isRegularFile() ->
                        TsrsCandidate(executable, typeScriptVersion, tsrsVersion, TsrsSource.WORKSPACE_TSRS_PACKAGE, effectPatched = false)
                    else -> null
                }
            }
    }

    private fun fromArtifactsRoot(artifactsRoot: Path, platform: TsrsPlatform, source: TsrsSource): TsrsCandidate? {
        if (!artifactsRoot.isDirectory()) return null
        return safeList(artifactsRoot)
            .filter { it.isDirectory() && typeScriptVersionOf(it.name) != null }
            .sortedWith(compareByDescending(versionOrder) { it.name })
            .firstNotNullOfOrNull { versionRoot ->
                val executable = versionRoot.resolve(platform.executableName)
                if (!executable.isRegularFile()) return@firstNotNullOfOrNull null
                TsrsCandidate(executable, typeScriptVersionOf(versionRoot.name)!!, versionRoot.name, source, effectPatched = true)
            }
    }

    private fun fromManagedCache(cacheRoot: Path, platform: TsrsPlatform): List<TsrsCandidate> {
        if (!cacheRoot.isDirectory()) return emptyList()
        val packageName = "@effect/tsgo-${platform.os}-${platform.arch}"
        return safeList(cacheRoot)
            .filter { it.isDirectory() }
            .sortedWith(compareByDescending(versionOrder) { it.name })
            .mapNotNull { versionRoot ->
                val packageRoot = versionRoot.resolve(packageName)
                listOf(packageRoot.resolve("package"), packageRoot)
                    .firstNotNullOfOrNull { root ->
                        fromArtifactsRoot(root.resolve("artifacts").resolve("tsrs"), platform, TsrsSource.MANAGED_EFFECT_TSGO_CACHE)
                    }
            }
    }

    /** Orders `0.9.0` before `0.67.4` (numeric release segments), unlike a plain string sort. */
    internal val versionOrder: Comparator<String> = Comparator { left, right ->
        val leftParts = left.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val rightParts = right.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        (0 until maxOf(leftParts.size, rightParts.size))
            .map { (leftParts.getOrNull(it) ?: 0).compareTo(rightParts.getOrNull(it) ?: 0) }
            .firstOrNull { it != 0 }
            ?: left.compareTo(right)
    }

    private fun readVersion(packageJson: Path): String? =
        runCatching { EffectJson.mapper.readTree(Files.readString(packageJson)).path("version").asText().trim() }
            .getOrNull()
            ?.takeIf(String::isNotBlank)

    private fun safeList(directory: Path): List<Path> = runCatching { directory.listDirectoryEntries() }.getOrDefault(emptyList())
}

/**
 * Lays out a TypeScript 7 package that WebStorm's native TypeScript service accepts as its
 * "TypeScript" package, with tsrs as the compiler.
 *
 * WebStorm 2026.2+ only recognizes a `typescript` package with major version 7 or newer whose
 * `@typescript/typescript-<os>-<arch>` sibling holds the executable in `lib/` (named `tsc`, or `tsgo`).
 * It launches that executable as `<lib>/tsc --lsp --stdio`, which tsrs serves. The package is
 * generated under the plugin's system cache so the workspace's `node_modules` are never modified;
 * `lib/tsc` and the standard library files link back to the tsrs installation (or are copied where
 * links are unavailable). The directory name is derived from the executable path so re-applying is
 * idempotent.
 */
object EffectTsrsTypeScriptPackage {
    /** Publishes NIO writes to WebStorm's VFS consumer before its package setting is changed. Run off EDT. */
    fun prepare(cacheRoot: Path, candidate: TsrsCandidate, platform: TsrsPlatform): Path {
        val directory = create(cacheRoot, candidate, platform)
        com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshNioFiles(listOf(cacheRoot), false, true, null)
        com.intellij.lang.typescript.lsp.invalidateTsGoPackageInfo()
        return directory
    }

    fun packageDirectory(cacheRoot: Path, candidate: TsrsCandidate): Path =
        packageRoot(cacheRoot, candidate).resolve("node_modules").resolve("typescript")

    private fun packageRoot(cacheRoot: Path, candidate: TsrsCandidate): Path =
        cacheRoot.resolve(shortHash(candidate.executable.toAbsolutePath().normalize().toString()))

    /**
     * Builds the package in a staging directory beside the active one and swaps it in only once it is
     * complete, so a failed rebuild never leaves WebStorm pointing at a half-written package.
     */
    fun create(cacheRoot: Path, candidate: TsrsCandidate, platform: TsrsPlatform): Path {
        val activeRoot = packageRoot(cacheRoot, candidate)
        // The Tools action and the settings button, in any open project, may build the same package.
        synchronized(lockFor(activeRoot)) {
            val stagingRoot = activeRoot.resolveSibling("${activeRoot.fileName}.staging-${System.nanoTime()}")
            val backupRoot = activeRoot.resolveSibling("${activeRoot.fileName}.backup-${System.nanoTime()}")
            try {
                writePackage(stagingRoot, candidate, platform)
                val hadActive = Files.exists(activeRoot)
                if (hadActive) {
                    Files.move(activeRoot, backupRoot)
                }
                try {
                    Files.move(stagingRoot, activeRoot)
                } catch (error: Exception) {
                    // Put the previous package back so WebStorm's configured path keeps working.
                    if (hadActive && !Files.exists(activeRoot)) {
                        Files.move(backupRoot, activeRoot)
                    }
                    throw error
                }
            } finally {
                listOf(stagingRoot, backupRoot).filter(Files::exists).forEach { it.toFile().deleteRecursively() }
            }
        }
        return packageDirectory(cacheRoot, candidate)
    }

    private val locks = java.util.concurrent.ConcurrentHashMap<Path, Any>()

    private fun lockFor(root: Path): Any = locks.computeIfAbsent(root.toAbsolutePath().normalize()) { Any() }

    private fun writePackage(root: Path, candidate: TsrsCandidate, platform: TsrsPlatform) {
        val nodeModules = root.resolve("node_modules")
        val typeScriptPackage = nodeModules.resolve("typescript")
        val platformPackageName = "typescript-${platform.os}-${platform.arch}"
        val platformPackage = nodeModules.resolve("@typescript").resolve(platformPackageName)
        val lib = platformPackage.resolve("lib")
        Files.createDirectories(typeScriptPackage)
        Files.createDirectories(lib)

        val version = candidate.typeScriptVersion
        val marker = "Generated by the Effect TSGO plugin from ${candidate.executable}"
        Files.writeString(
            typeScriptPackage.resolve("package.json"),
            EffectJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                linkedMapOf(
                    "name" to "typescript",
                    "version" to version,
                    "description" to marker,
                    "bin" to mapOf("tsc" to "./bin/tsc"),
                ),
            ),
        )
        Files.writeString(
            platformPackage.resolve("package.json"),
            EffectJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                linkedMapOf("name" to "@typescript/$platformPackageName", "version" to version, "description" to marker),
            ),
        )

        link(lib.resolve(platform.typeScriptExecutableName), candidate.executable)
        val standardLibraryRoot = candidate.executable.parent
        runCatching { standardLibraryRoot.listDirectoryEntries("lib*.d.ts") }.getOrDefault(emptyList())
            .filter { it.isRegularFile() }
            .forEach { link(lib.resolve(it.name), it) }
    }

    /** The executable WebStorm will start for [typeScriptPackage], if it was generated by [create]. */
    fun linkedExecutable(typeScriptPackage: Path, platform: TsrsPlatform): Path? {
        val executable = typeScriptPackage.parent.resolve("@typescript").resolve("typescript-${platform.os}-${platform.arch}")
            .resolve("lib").resolve(platform.typeScriptExecutableName)
        return executable.takeIf { Files.exists(it) }
    }

    private fun link(target: Path, source: Path) {
        val absoluteSource = source.toAbsolutePath().normalize()
        try {
            Files.createSymbolicLink(target, absoluteSource)
        } catch (_: Exception) {
            Files.copy(absoluteSource, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        }
    }

    private fun shortHash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
}
