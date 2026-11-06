package dev.effect.intellij.devtools

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.LocalFileSystem
import dev.effect.intellij.core.EffectPluginConstants
import dev.effect.intellij.notifications.EffectNotificationService
import java.nio.file.Path

class EffectExportTracerAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val notifications = ApplicationManager.getApplication().getService(EffectNotificationService::class.java)

        val state = project.getService(EffectDevToolsService::class.java).currentState()
        val client = state.clients.firstOrNull { it.id == state.activeClientId }
        if (client == null || client.rootSpans.isEmpty()) {
            notifications.warning(
                project,
                "Effect: no trace to export",
                "Connect an instrumented Effect app and capture spans in Effect Dev Tools before exporting.",
            )
            return
        }

        object : Task.Backgroundable(project, "Exporting Effect tracer", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val directory = Path.of(PathManager.getSystemPath(), EffectPluginConstants.DEFAULT_BINARY_CACHE_DIR, "tracer")
                    val target = EffectTracerExport.write(client, directory, indicator::checkCanceled)
                    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            file?.let { OpenFileDescriptor(project, it).navigate(true) }
                            notifications.info(project, "Effect: tracer exported", "Wrote the active span tree to $target.")
                        }
                    }
                } catch (cancelled: ProcessCanceledException) {
                    throw cancelled
                } catch (error: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) notifications.warning(
                            project, "Effect: tracer export failed",
                            "Could not write the tracer export. Check available disk space and cache directory permissions. ${error.message}",
                        )
                    }
                }
            }
        }.queue()
    }
}
