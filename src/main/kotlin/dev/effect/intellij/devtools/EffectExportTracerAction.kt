package dev.effect.intellij.devtools

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.LocalFileSystem
import dev.effect.intellij.core.EffectPluginConstants
import dev.effect.intellij.notifications.EffectNotificationService
import java.nio.file.Files
import java.nio.file.Path

class EffectExportTracerAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val notifications = ApplicationManager.getApplication().getService(EffectNotificationService::class.java)

        val json = project.getService(EffectDevToolsService::class.java).exportActiveTracer()
        if (json == null) {
            notifications.warning(
                project,
                "Effect: no trace to export",
                "Connect an instrumented Effect app and capture spans in Effect Dev Tools before exporting.",
            )
            return
        }

        val directory = Path.of(PathManager.getSystemPath(), EffectPluginConstants.DEFAULT_BINARY_CACHE_DIR, "tracer")
        Files.createDirectories(directory)
        val target = directory.resolve("effect-tracer-${System.currentTimeMillis()}.json")
        Files.writeString(target, json)

        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)?.let { file ->
            FileEditorManager.getInstance(project).openFile(file, true)
        }
        notifications.info(project, "Effect: tracer exported", "Wrote the active span tree to $target.")
    }
}
