package dev.effect.intellij.schema

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import dev.effect.intellij.core.EffectJson
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Background schema loading keeps Settings Apply and tsconfig write actions free of disk I/O.
 * Until loading completes callers receive an explicit advisory warning, never a hard error.
 */
@Service(Service.Level.PROJECT)
class EffectSchemaService(private val project: Project) : Disposable {
    private val schemas = ConcurrentHashMap<String, CompletableFuture<EffectOptionSchema>>()
    private val watching = AtomicBoolean()

    init {
        request(null)
    }

    fun watchSchemaAssociations() {
        if (!watching.compareAndSet(false, true)) return
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.path.endsWith("/schema.json") || it.path.endsWith("/tsconfig.json") ||
                        it.path.endsWith("/jsconfig.json") || it.file?.isDirectory == true }) {
                    schemas.keys.removeIf { it != BUNDLED }
                    // Run outside VFS event delivery / write actions. The JSON service caches factories.
                    ApplicationManager.getApplication().executeOnPooledThread {
                        if (!project.isDisposed) JsonSchemaService.Impl.get(project).reset()
                    }
                }
            }
        })
    }

    fun warnings(value: com.fasterxml.jackson.databind.JsonNode, directory: VirtualFile? = project.guessProjectDir()): List<String> {
        val file = EffectSchemaFiles.nearest(directory)
        val future = request(file)
        if (future.isCompletedExceptionally) return listOf("Could not load the selected Effect option schema; check the workspace package installation.")
        val schema = future.getNow(null)
        return schema?.warnings(value) ?: listOf("Effect option schema is loading; validate again to see option warnings.")
    }

    fun preload(directory: VirtualFile? = project.guessProjectDir(), onReady: (() -> Unit)? = null) {
        watchSchemaAssociations()
        val future = request(EffectSchemaFiles.nearest(directory))
        if (onReady != null) future.whenComplete { _, _ -> if (!project.isDisposed) onReady() }
    }

    private fun request(file: VirtualFile?): CompletableFuture<EffectOptionSchema> {
        val key = file?.let { "${it.url}:${it.modificationStamp}" } ?: BUNDLED
        val future = CompletableFuture<EffectOptionSchema>()
        schemas.putIfAbsent(key, future)?.let { return it }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val document = if (file == null) {
                    EffectSchemaService::class.java.getResourceAsStream(EffectOptionSchema.RESOURCE)!!.use { EffectJson.mapper.readTree(it) }
                } else {
                    EffectJson.mapper.readTree(file.contentsToByteArray())
                }
                future.complete(EffectOptionSchema(document))
            } catch (error: java.io.IOException) {
                future.completeExceptionally(error)
            }
        }
        return future
    }

    override fun dispose() = Unit

    companion object { private const val BUNDLED = "bundled:0.67.4" }
}
