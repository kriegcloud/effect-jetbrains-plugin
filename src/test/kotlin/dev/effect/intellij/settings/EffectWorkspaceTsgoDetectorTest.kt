package dev.effect.intellij.settings

import java.nio.file.Files
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EffectWorkspaceTsgoDetectorTest {
    private lateinit var root: Path

    @Before
    fun setUp() {
        root = Files.createTempDirectory("effect-tsgo-detector")
    }

    @After
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun exactVersionShapeRejectsRangesPathsAndUrls() {
        listOf("0.54.1", "0.55.0-next.1", " 0.54.1 ").forEach { assertTrue(it, EffectWorkspaceTsgoDetector.isExactVersion(it)) }
        listOf("", "^0.54.1", "~0.54", "latest", "/home/me/node_modules/@effect/tsgo-linux-x64/lib/tsc", "npm:@effect/tsgo@0.54.1", "0.54")
            .forEach { assertFalse(it, EffectWorkspaceTsgoDetector.isExactVersion(it)) }
    }

    @Test
    fun detectsNearestNodeModulesFirstThenPlatformPackagesThenRootManifest() {
        val workspace = root.resolve("monorepo").resolve("apps").resolve("web")
        writeManifest(root.resolve("monorepo/node_modules/@effect/tsgo"), "@effect/tsgo", "0.53.4")
        writeManifest(root.resolve("monorepo/node_modules/@effect/tsgo-linux-x64"), "@effect/tsgo-linux-x64", "0.53.4")
        writeManifest(workspace.resolve("node_modules/@effect/tsgo-linux-x64"), "@effect/tsgo-linux-x64", "0.54.1")
        Files.createDirectories(workspace)
        Files.writeString(workspace.resolve("package.json"), """{"name":"web","devDependencies":{"@effect/tsgo":"0.52.0"}}""")

        val detected = EffectWorkspaceTsgoDetector.detect(workspace)

        assertEquals(listOf("0.54.1", "0.53.4", "0.52.0"), detected.map { it.version })
        assertEquals("@effect/tsgo-linux-x64", detected[0].packageName)
        assertEquals(workspace.resolve("node_modules/@effect/tsgo-linux-x64/package.json").toAbsolutePath().normalize(), detected[0].source)
        assertEquals("@effect/tsgo", detected[1].packageName)
        assertEquals(workspace.resolve("package.json").toAbsolutePath().normalize(), detected[2].source)
    }

    @Test
    fun rootManifestRangeAndWorkspaceSpecifiersAreNotVersions() {
        Files.writeString(root.resolve("package.json"), """{"devDependencies":{"@effect/tsgo":"^0.54.1"}}""")
        assertTrue(EffectWorkspaceTsgoDetector.detect(root).isEmpty())

        Files.writeString(root.resolve("package.json"), """{"dependencies":{"@effect/tsgo":"workspace:*"}}""")
        assertTrue(EffectWorkspaceTsgoDetector.detect(root).isEmpty())

        Files.writeString(root.resolve("package.json"), """{"optionalDependencies":{"@effect/tsgo":"=0.54.1"}}""")
        assertEquals(listOf("0.54.1"), EffectWorkspaceTsgoDetector.detect(root).map { it.version })
    }

    @Test
    fun fromPackageDirectoryAcceptsDirectoryOrManifestAndRejectsOtherPackages() {
        val tsgo = writeManifest(root.resolve("node_modules/@effect/tsgo"), "@effect/tsgo", "0.54.1")
        val platform = writeManifest(root.resolve("node_modules/@effect/tsgo-darwin-arm64"), "@effect/tsgo-darwin-arm64", "0.54.1")
        writeManifest(root.resolve("node_modules/typescript"), "typescript", "7.0.2")
        writeManifest(root.resolve("node_modules/@effect/tsgo-bad"), "@effect/tsgo-bad", "^1.0.0")

        assertEquals("0.54.1", EffectWorkspaceTsgoDetector.fromPackageDirectory(tsgo.parent)?.version)
        assertEquals("0.54.1", EffectWorkspaceTsgoDetector.fromPackageDirectory(tsgo)?.version)
        assertEquals("@effect/tsgo-darwin-arm64", EffectWorkspaceTsgoDetector.fromPackageDirectory(platform.parent)?.packageName)
        assertNull(EffectWorkspaceTsgoDetector.fromPackageDirectory(root.resolve("node_modules/typescript")))
        assertNull(EffectWorkspaceTsgoDetector.fromPackageDirectory(root.resolve("node_modules/@effect/tsgo-bad")))
        assertNull(EffectWorkspaceTsgoDetector.fromPackageDirectory(root.resolve("node_modules/@effect/tsgo-linux-x64/lib/tsc")))
        assertNull(EffectWorkspaceTsgoDetector.fromPackageDirectory(root.resolve("missing")))
    }

    private fun writeManifest(directory: Path, name: String, version: String): Path {
        Files.createDirectories(directory)
        val manifest = directory.resolve("package.json")
        Files.writeString(manifest, """{"name":"$name","version":"$version"}""")
        return manifest
    }
}
