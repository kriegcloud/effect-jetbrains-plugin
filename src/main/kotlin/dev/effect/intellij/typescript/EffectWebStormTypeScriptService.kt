package dev.effect.intellij.typescript

import com.intellij.lang.typescript.compiler.TypeScriptCompilerSettings
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import dev.effect.intellij.binary.EffectBinaryService
import dev.effect.intellij.core.EffectFileUtil
import dev.effect.intellij.core.logger
import dev.effect.intellij.notifications.EffectNotificationService
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * Points WebStorm's own TypeScript service (Settings | Languages & Frameworks | TypeScript >
 * TypeScript) at tsrs, the Rust port of the TypeScript 7.1 compiler, so the user does not have to hunt
 * for a package path that WebStorm will accept.
 *
 * This only changes WebStorm's TypeScript setting; the Effect TSGO language server keeps using its
 * own binary selection.
 */
@Service(Service.Level.PROJECT)
class EffectWebStormTypeScriptService(private val project: Project) {
    data class CurrentState(val serviceDirectory: String?, val usesTsrs: Boolean, val candidates: List<TsrsCandidate>)

    /** Reads WebStorm's current TypeScript package and the tsrs installations available to this project. */
    fun currentState(): CurrentState {
        val platform = TsrsPlatform.current()
        val candidates = if (platform == null) emptyList() else findCandidates(platform)
        val settings = TypeScriptCompilerSettings.getSettings(project)
        val serviceDirectory = settings.typeScriptServiceDirectory?.takeIf(String::isNotBlank)
            ?.takeIf { settings.versionType == TypeScriptCompilerSettings.TypeScriptCompilerVersionType.SERVICE_DIRECTORY }
        val usesTsrs = serviceDirectory != null && platform != null &&
            runCatching { Path.of(FileUtil.toSystemDependentName(serviceDirectory)) }.getOrNull()
                ?.startsWith(packageCacheRoot()) == true
        return CurrentState(serviceDirectory, usesTsrs, candidates)
    }

    /** Finds tsrs, builds the WebStorm-shaped package, and switches the TypeScript setting, off the EDT. */
    fun useTsrsForWebStormTypeScript(onDone: (Result<TsrsCandidate>) -> Unit = {}) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Pointing WebStorm TypeScript at tsrs", false) {
            override fun run(indicator: ProgressIndicator) {
                val result = runCatching { prepare() }
                ApplicationManager.getApplication().invokeLater({
                    val applied = result.mapCatching { (candidate, packageDirectory) ->
                        apply(packageDirectory)
                        candidate
                    }
                    report(applied)
                    onDone(applied)
                }, project.disposed)
            }
        })
    }

    private fun prepare(): Pair<TsrsCandidate, Path> {
        val platform = TsrsPlatform.current()
            ?: throw IllegalStateException("tsrs has no build for this operating system and CPU.")
        val candidate = findCandidates(platform).firstOrNull()
            ?: throw IllegalStateException(
                "No tsrs installation was found. Add `tsrs` to the workspace (for example `pnpm add -D tsrs`), " +
                    "or let the Effect plugin download @effect/tsgo 0.57.0 or newer, which ships tsrs.",
            )
        ensureExecutable(candidate.executable)
        check(platform.isWindows || Files.isExecutable(candidate.executable)) {
            "${candidate.executable} is not executable and its permissions could not be changed."
        }
        val packageDirectory = EffectTsrsTypeScriptPackage.create(packageCacheRoot(), candidate, platform)
        return candidate to packageDirectory
    }

    private fun apply(packageDirectory: Path) {
        val settings = TypeScriptCompilerSettings.getSettings(project)
        settings.versionType = TypeScriptCompilerSettings.TypeScriptCompilerVersionType.SERVICE_DIRECTORY
        settings.typeScriptServiceDirectory = FileUtil.toSystemIndependentName(packageDirectory.toString())
        settings.useService = true
        restartWebStormTypeScriptService()
    }

    /**
     * Runs WebStorm's own `Restart TypeScript Service` action by id; the programmatic restarter behind
     * it is experimental API, the action id is stable across 2026.2 and 2026.3.
     */
    private fun restartWebStormTypeScriptService() {
        val action = ActionManager.getInstance().getAction(TYPESCRIPT_RESTART_ACTION_ID) ?: return
        val context = SimpleDataContext.getProjectContext(project)
        ActionUtil.invokeAction(action, context, ActionPlaces.UNKNOWN, null, null)
    }

    private fun report(result: Result<TsrsCandidate>) {
        val notifications = service<EffectNotificationService>()
        result.onSuccess { candidate ->
            val duplicateNote = if (candidate.effectPatched) {
                " This build embeds the Effect language service, so Effect diagnostics can appear twice (from " +
                    "WebStorm's TypeScript service and from Effect TSGO). Install the stock `tsrs` package in the " +
                    "workspace to avoid that."
            } else {
                ""
            }
            notifications.info(
                project,
                "WebStorm TypeScript now uses tsrs",
                "Settings | Languages & Frameworks | TypeScript now points at ${candidate.description} " +
                    "(${candidate.executable}).$duplicateNote",
            )
        }.onFailure { error ->
            logger<EffectWebStormTypeScriptService>().warn("Could not point WebStorm TypeScript at tsrs", error)
            notifications.warning(project, "Could not use tsrs for WebStorm TypeScript", error.message ?: error.toString())
        }
    }

    private fun findCandidates(platform: TsrsPlatform): List<TsrsCandidate> {
        val workspaceRoot = project.basePath?.takeIf(String::isNotBlank)?.let { Path.of(it) } ?: return emptyList()
        val managedCacheRoot = runCatching { EffectBinaryService.getInstance().managedPackageCacheRoot() }.getOrNull()
        return EffectTsrsLocator.find(workspaceRoot, managedCacheRoot, platform)
            // A stock build first: an Effect-patched one would duplicate Effect TSGO's diagnostics.
            .sortedBy { if (it.effectPatched) 1 else 0 }
    }

    private fun packageCacheRoot(): Path = EffectFileUtil.systemCacheDir(TYPESCRIPT_PACKAGE_CACHE_DIR)

    private fun ensureExecutable(path: Path) {
        if (TsrsPlatform.current()?.isWindows == true) return
        runCatching {
            val permissions = Files.getPosixFilePermissions(path).toMutableSet()
            if (permissions.add(PosixFilePermission.OWNER_EXECUTE)) {
                Files.setPosixFilePermissions(path, permissions)
            }
        }
    }

    companion object {
        const val TYPESCRIPT_PACKAGE_CACHE_DIR = "effect-tsgo/webstorm-typescript"
        private const val TYPESCRIPT_RESTART_ACTION_ID = "TypeScript.Restart.Service"

        fun getInstance(project: Project): EffectWebStormTypeScriptService = project.service()
    }
}
