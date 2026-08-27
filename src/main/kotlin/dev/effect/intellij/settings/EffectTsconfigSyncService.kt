package dev.effect.intellij.settings

import com.intellij.json.psi.JsonFile
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import dev.effect.intellij.notifications.EffectNotificationService

/** Writes typed Effect language-service options into the workspace `tsconfig.json`. */
@Service(Service.Level.PROJECT)
class EffectTsconfigSyncService(private val project: Project) {
    fun locateTsconfig(): VirtualFile? = project.guessProjectDir()?.findChild(TSCONFIG_FILE_NAME)

    /** Tools-menu entry point: sync the settings that have already been applied. */
    fun sync() {
        sync(EffectProjectSettingsService.getInstance(project).currentSettings())
    }

    /** Settings-page entry point: sync an explicit, possibly not-yet-applied form snapshot. */
    fun sync(settings: EffectProjectSettings) {
        val application = ApplicationManager.getApplication()
        val notifications = application.getService(EffectNotificationService::class.java)
        val tsconfig = locateTsconfig()
        if (tsconfig == null || !tsconfig.isValid) {
            notifications.warning(
                project,
                "Effect: tsconfig.json not found",
                "Could not find tsconfig.json in the project root. Add one with an \"@effect/language-service\" plugin entry, then sync again.",
            )
            return
        }

        val documentManager = FileDocumentManager.getInstance()
        val documentAndPsi: Pair<Document, JsonFile>? = application.runReadAction(Computable {
            val document = documentManager.getDocument(tsconfig) ?: return@Computable null
            val psiFile = PsiManager.getInstance(project).findFile(tsconfig) as? JsonFile
                ?: return@Computable null
            document to psiFile
        })
        if (documentAndPsi == null) {
            notifications.warning(project, "Effect: could not read tsconfig.json", "Failed to open ${tsconfig.path} as JSON.")
            return
        }
        val (document, psiFile) = documentAndPsi

        var result: EffectTsconfigSync.SyncResult? = null
        WriteCommandAction.runWriteCommandAction(project, "Sync Effect Options To tsconfig.json", null, Runnable {
            PsiDocumentManager.getInstance(project).commitDocument(document)
            result = EffectTsconfigSync.apply(psiFile, settings)
            if (result?.changed == true) {
                PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(document)
                documentManager.saveDocument(document)
            }
        })

        when {
            result == null -> notifications.info(
                project,
                "Effect: nothing to sync",
                "No typed Effect language-service options are set.",
            )

            result?.errorMessage != null -> notifications.warning(
                project,
                "Effect: could not sync tsconfig.json",
                result?.errorMessage.orEmpty(),
            )

            result?.changed == false -> notifications.info(
                project,
                "Effect: tsconfig.json already up to date",
                "The \"@effect/language-service\" plugin entry already matches your Effect settings.",
            )

            else -> notifications.info(
                project,
                "Effect: synced options to tsconfig.json",
                "Wrote your Effect language-service options into the \"@effect/language-service\" plugin entry. " +
                    "Restart the Effect language server to apply.",
            )
        }
    }

    companion object {
        private const val TSCONFIG_FILE_NAME = "tsconfig.json"

        fun getInstance(project: Project): EffectTsconfigSyncService =
            project.getService(EffectTsconfigSyncService::class.java)
    }
}
