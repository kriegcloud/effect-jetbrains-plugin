package dev.effect.intellij.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import dev.effect.intellij.notifications.EffectNotificationService

/**
 * Writes the plugin's typed Effect language-service options into the workspace `tsconfig.json`
 * `@effect/language-service` plugin entry, which is the channel `@effect/tsgo` actually reads.
 */
@Service(Service.Level.PROJECT)
class EffectTsconfigSyncService(private val project: Project) {
    fun locateTsconfig(): VirtualFile? = project.guessProjectDir()?.findChild(TSCONFIG_FILE_NAME)

    fun sync() {
        val notifications = ApplicationManager.getApplication().getService(EffectNotificationService::class.java)

        val tsconfig = locateTsconfig()
        if (tsconfig == null || !tsconfig.isValid) {
            notifications.warning(
                project,
                "Effect: tsconfig.json not found",
                "Could not find tsconfig.json in the project root. Add one with an \"@effect/language-service\" plugin entry, then sync again.",
            )
            return
        }

        val original = runCatching { String(tsconfig.contentsToByteArray(), Charsets.UTF_8) }.getOrNull()
        if (original == null) {
            notifications.warning(project, "Effect: could not read tsconfig.json", "Failed to read ${tsconfig.path}.")
            return
        }

        val settings = EffectProjectSettingsService.getInstance(project).currentSettings()
        val result = EffectTsconfigSync.apply(original, settings)
        when {
            result == null -> notifications.info(
                project,
                "Effect: nothing to sync",
                "No typed Effect language-service options are set, or tsconfig.json is not a JSON object.",
            )

            !result.changed -> notifications.info(
                project,
                "Effect: tsconfig.json already up to date",
                "The \"@effect/language-service\" plugin entry already matches your Effect settings.",
            )

            else -> {
                WriteCommandAction.runWriteCommandAction(project, "Sync Effect Options To tsconfig.json", null, Runnable {
                    val document = FileDocumentManager.getInstance().getDocument(tsconfig)
                    if (document != null) {
                        document.setText(result.updatedJson)
                        FileDocumentManager.getInstance().saveDocument(document)
                    } else {
                        tsconfig.setBinaryContent(result.updatedJson.toByteArray(Charsets.UTF_8))
                    }
                })
                val commentNote = if (result.hadComments) " Comments in tsconfig.json were removed by the rewrite." else ""
                notifications.info(
                    project,
                    "Effect: synced options to tsconfig.json",
                    "Wrote your Effect language-service options into the \"@effect/language-service\" plugin entry.$commentNote " +
                        "Restart the Effect language server to apply.",
                )
            }
        }
    }

    companion object {
        private const val TSCONFIG_FILE_NAME = "tsconfig.json"

        fun getInstance(project: Project): EffectTsconfigSyncService =
            project.getService(EffectTsconfigSyncService::class.java)
    }
}
