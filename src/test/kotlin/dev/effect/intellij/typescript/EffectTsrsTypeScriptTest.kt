package dev.effect.intellij.typescript

import com.intellij.lang.typescript.lsp.TypeScriptGoPackageInfo
import com.intellij.lang.typescript.lsp.getTypeScriptGoPackageInfo
import com.intellij.lang.typescript.lsp.invalidateTsGoPackageInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class EffectTsrsTypeScriptTest : BasePlatformTestCase() {
    private lateinit var root: Path
    private val platform = TsrsPlatform("linux", "x64")

    override fun setUp() {
        super.setUp()
        // Canonical path: macOS temp dirs resolve through /private/var, which VFS root checks see.
        root = Files.createTempDirectory("effect-tsrs-test").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, root.toString())
    }

    override fun tearDown() {
        try {
            FileUtil.delete(root)
        } finally {
            super.tearDown()
        }
    }

    fun testTypeScriptVersionComesFromTheTsrsVersionSuffix() {
        assertEquals("7.1.0-beta", EffectTsrsLocator.typeScriptVersionOf("0.22.0-ts7.1.0-beta"))
        assertEquals("7.1.0", EffectTsrsLocator.typeScriptVersionOf("0.30.0-ts7.1.0"))
        assertNull(EffectTsrsLocator.typeScriptVersionOf("0.22.0"))
        assertTrue(EffectTsrsLocator.versionOrder.compare("0.67.4", "0.9.0") > 0)
    }

    fun testPrefersTheStockOriginalOverAPatchedWorkspaceTsrs() {
        val packageRoot = tsrsPackage(root.resolve("workspace/node_modules/@tsrs/linux-x64-gnu"))
        Files.writeString(packageRoot.resolve("tsrs.original"), "stock")

        val candidate = EffectTsrsLocator.find(root.resolve("workspace"), null, platform).single()

        assertEquals(packageRoot.resolve("tsrs.original"), candidate.executable)
        assertEquals("7.1.0-beta", candidate.typeScriptVersion)
        assertEquals(TsrsSource.WORKSPACE_TSRS_PACKAGE, candidate.source)
        assertFalse(candidate.effectPatched)
    }

    fun testFindsWorkspacePackagesBeforeTheEffectTsgoArtifactAndTheManagedCache() {
        val workspace = root.resolve("workspace")
        tsrsPackage(workspace.resolve("node_modules/@tsrs/linux-x64-musl"))
        tsrsPackage(workspace.resolve("node_modules/@tsrs/linux-x64-gnu"))
        tsrsPackage(workspace.resolve("node_modules/@tsrs/darwin-arm64"))
        artifact(workspace.resolve("node_modules/@effect/tsgo-linux-x64/artifacts/tsrs/0.22.0-ts7.1.0-beta"))
        val cache = root.resolve("cache")
        artifact(cache.resolve("0.9.0/@effect/tsgo-linux-x64/package/artifacts/tsrs/0.10.0-ts7.1.0-dev"))
        artifact(cache.resolve("0.67.4/@effect/tsgo-linux-x64/package/artifacts/tsrs/0.22.0-ts7.1.0-beta"))

        val candidates = EffectTsrsLocator.find(workspace, cache, platform)

        assertEquals(
            listOf(
                "@tsrs/linux-x64-gnu" to TsrsSource.WORKSPACE_TSRS_PACKAGE,
                "@tsrs/linux-x64-musl" to TsrsSource.WORKSPACE_TSRS_PACKAGE,
                "tsgo-linux-x64" to TsrsSource.WORKSPACE_EFFECT_TSGO_PACKAGE,
                "0.67.4" to TsrsSource.MANAGED_EFFECT_TSGO_CACHE,
                "0.9.0" to TsrsSource.MANAGED_EFFECT_TSGO_CACHE,
            ),
            candidates.map { candidate ->
                val path = candidate.executable.toString().replace('\\', '/')
                val marker = listOf("@tsrs/linux-x64-gnu", "@tsrs/linux-x64-musl", "/0.67.4/", "/0.9.0/", "tsgo-linux-x64").first { path.contains(it) }.trim('/')
                marker to candidate.source
            },
        )
        assertTrue(candidates.filter { it.source != TsrsSource.WORKSPACE_TSRS_PACKAGE }.all { it.effectPatched })
    }

    fun testPrefersTheMuslBuildOnMuslHosts() {
        val workspace = root.resolve("workspace")
        tsrsPackage(workspace.resolve("node_modules/@tsrs/linux-x64-gnu"))
        tsrsPackage(workspace.resolve("node_modules/@tsrs/linux-x64-musl"))

        val candidate = EffectTsrsLocator.find(workspace, null, TsrsPlatform("linux", "x64", musl = true)).first()

        assertTrue(candidate.executable.toString().replace('\\', '/').contains("@tsrs/linux-x64-musl"))
    }

    fun testRebuildingReplacesThePackageWithoutLeavingStagingDirectories() {
        val first = artifact(root.resolve("tsrs-install"))
        val candidate = TsrsCandidate(first.resolve("tsrs"), "7.1.0-beta", "0.22.0-ts7.1.0-beta", TsrsSource.WORKSPACE_TSRS_PACKAGE, false)
        val generated = root.resolve("generated")

        EffectTsrsTypeScriptPackage.create(generated, candidate, platform)
        Files.delete(first.resolve("lib.es5.d.ts"))
        val packageDirectory = EffectTsrsTypeScriptPackage.create(generated, candidate, platform)

        val lib = EffectTsrsTypeScriptPackage.linkedExecutable(packageDirectory, platform)!!.parent
        assertFalse(Files.exists(lib.resolve("lib.es5.d.ts")))
        assertTrue(Files.exists(lib.resolve("lib.d.ts")))
        assertEquals(1, Files.list(generated).use { it.count() })
    }

    fun testGeneratedPackageIsRecognizedByWebStormsTypeScriptGoDetection() {
        val tsrsRoot = artifact(root.resolve("tsrs-install"))
        val candidate = TsrsCandidate(tsrsRoot.resolve("tsrs"), "7.1.0-beta", "0.22.0-ts7.1.0-beta", TsrsSource.WORKSPACE_TSRS_PACKAGE, false)
        val current = TsrsPlatform.current() ?: return

        val packageDirectory = EffectTsrsTypeScriptPackage.create(root.resolve("generated"), candidate, current)
        // Re-running is idempotent and replaces the previous links.
        assertEquals(packageDirectory, EffectTsrsTypeScriptPackage.create(root.resolve("generated"), candidate, current))

        val linked = EffectTsrsTypeScriptPackage.linkedExecutable(packageDirectory, current)!!
        assertEquals("tsrs-binary", linked.readText())
        assertEquals("lib", linked.resolveSibling("lib.d.ts").readText())
        assertTrue(packageDirectory.resolve("package.json").readText().contains("\"version\" : \"7.1.0-beta\""))

        LocalFileSystem.getInstance().refreshNioFiles(listOf(root), false, true, null)
        invalidateTsGoPackageInfo()
        val info = getTypeScriptGoPackageInfo(FileUtil.toSystemIndependentName(packageDirectory.toString()))
        assertNotNull("WebStorm did not recognize the generated package as TypeScript 7", info)
        info as TypeScriptGoPackageInfo
        assertEquals(current.typeScriptExecutableName, info.binaryExecutable.name)
        assertEquals("7.1.0-beta", info.version?.rawVersion)
        assertEquals(
            linked.toRealPath(),
            Path.of(info.binaryExecutable.path).toRealPath(),
        )
    }

    private fun tsrsPackage(packageRoot: Path): Path {
        Files.createDirectories(packageRoot)
        Files.writeString(packageRoot.resolve("package.json"), """{"name":"@tsrs/${packageRoot.fileName}","version":"0.22.0-ts7.1.0-beta"}""")
        Files.writeString(packageRoot.resolve("tsrs"), "patched")
        return packageRoot
    }

    private fun artifact(versionRoot: Path): Path {
        Files.createDirectories(versionRoot)
        Files.writeString(versionRoot.resolve("tsrs"), "tsrs-binary")
        Files.writeString(versionRoot.resolve("lib.d.ts"), "lib")
        Files.writeString(versionRoot.resolve("lib.es5.d.ts"), "es5")
        return versionRoot
    }
}
