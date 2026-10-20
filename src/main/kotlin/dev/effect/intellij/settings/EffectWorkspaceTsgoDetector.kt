package dev.effect.intellij.settings

import dev.effect.intellij.core.EffectJson
import java.nio.file.Files
import java.nio.file.Path

/** An exact `@effect/tsgo` version found in the workspace, with the manifest it came from. */
data class DetectedTsgoVersion(
    val version: String,
    val packageName: String,
    val source: Path,
)

/**
 * Finds the `@effect/tsgo` versions a workspace already depends on, so `PINNED` mode can default
 * to the release the project's CLI and `tsc -b` use instead of asking for a hand-typed string.
 *
 * Detection reads package manifests only: `node_modules/@effect/tsgo` and the platform packages
 * (`@effect/tsgo-<os>-<arch>`) in every ancestor `node_modules`, nearest first, plus an exact
 * `@effect/tsgo` specifier in the workspace `package.json`. Ranges, tags, URLs, and file paths are
 * never turned into a version; the resolver fails closed on anything that is not an exact version.
 */
object EffectWorkspaceTsgoDetector {
    const val BASE_PACKAGE_NAME = "@effect/tsgo"

    private val EXACT_VERSION = Regex("""^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$""")
    private val EXACT_SPECIFIER = Regex("""^=?v?(\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?)$""")
    private val DEPENDENCY_SECTIONS = listOf("dependencies", "devDependencies", "optionalDependencies")

    /** `true` for an exact npm version such as `0.54.1` or `0.55.0-next.1`; `false` for ranges, paths, and URLs. */
    fun isExactVersion(text: String): Boolean = EXACT_VERSION.matches(text.trim())

    fun detect(workspaceRoot: Path): List<DetectedTsgoVersion> {
        val found = LinkedHashMap<String, DetectedTsgoVersion>()
        fun add(candidate: DetectedTsgoVersion?) {
            if (candidate != null) {
                found.putIfAbsent(candidate.version, candidate)
            }
        }

        for (nodeModulesRoot in nodeModulesRoots(workspaceRoot)) {
            val scope = nodeModulesRoot.resolve("@effect")
            if (!Files.isDirectory(scope)) {
                continue
            }
            add(fromPackageDirectory(scope.resolve("tsgo")))
            val platformPackages = runCatching {
                Files.newDirectoryStream(scope).use { entries ->
                    entries.filter { entry -> entry.fileName.toString().startsWith("tsgo-") }.sortedBy { it.fileName.toString() }
                }
            }.getOrDefault(emptyList())
            platformPackages.forEach { directory -> add(fromPackageDirectory(directory)) }
        }
        add(fromRootManifest(workspaceRoot.resolve("package.json")))
        return found.values.toList()
    }

    /**
     * Reads one `@effect/tsgo` or `@effect/tsgo-<platform>` package: [path] may be the package
     * directory or its `package.json`. Returns `null` for anything else, including non-exact versions.
     */
    fun fromPackageDirectory(path: Path): DetectedTsgoVersion? {
        val manifest = if (Files.isRegularFile(path)) path else path.resolve("package.json")
        if (manifest.fileName?.toString() != "package.json" || !Files.isRegularFile(manifest)) {
            return null
        }
        val json = runCatching { EffectJson.mapper.readTree(Files.readString(manifest)) }.getOrNull() ?: return null
        val name = json.path("name").asText().trim()
        val version = json.path("version").asText().trim()
        if ((name != BASE_PACKAGE_NAME && !name.startsWith("$BASE_PACKAGE_NAME-")) || !isExactVersion(version)) {
            return null
        }
        return DetectedTsgoVersion(version, name, manifest.toAbsolutePath().normalize())
    }

    private fun fromRootManifest(manifest: Path): DetectedTsgoVersion? {
        if (!Files.isRegularFile(manifest)) {
            return null
        }
        val json = runCatching { EffectJson.mapper.readTree(Files.readString(manifest)) }.getOrNull() ?: return null
        for (section in DEPENDENCY_SECTIONS) {
            val specifier = json.path(section).path(BASE_PACKAGE_NAME).asText().trim()
            val version = EXACT_SPECIFIER.matchEntire(specifier)?.groupValues?.get(1) ?: continue
            return DetectedTsgoVersion(version, BASE_PACKAGE_NAME, manifest.toAbsolutePath().normalize())
        }
        return null
    }

    private fun nodeModulesRoots(workspaceRoot: Path): Sequence<Path> =
        generateSequence(workspaceRoot.toAbsolutePath().normalize()) { current -> current.parent }
            .map { ancestor -> ancestor.resolve("node_modules") }
}
