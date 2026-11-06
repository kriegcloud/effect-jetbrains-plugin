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
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import java.nio.file.Path
import com.jetbrains.jsonSchema.ide.JsonSchemaService
import dev.effect.intellij.core.EffectJson
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Background schema loading keeps Settings Apply and tsconfig write actions free of disk I/O.
 * Until loading completes callers receive an explicit advisory warning, never a hard error.
 */
@Service(Service.Level.PROJECT)
class EffectSchemaService(private val project: Project, private val coroutineScope: CoroutineScope) : Disposable {
    private val schemas = ConcurrentHashMap<String, CompletableFuture<EffectOptionSchema>>()
    private val watching = AtomicBoolean()
    private val resetPending = AtomicBoolean()
    @Volatile private var configPaths: List<String> = emptyList()
    @Volatile private var schemaRoots: List<String> = project.basePath?.let { base ->
        generateSequence(Path.of(base).toAbsolutePath().normalize()) { it.parent }
            .map { it.resolve("node_modules/@effect/tsgo").toString().replace('\\', '/') }.toList()
    }.orEmpty()

    init {
        request(null)
    }

    fun watchSchemaAssociations() {
        if (!watching.compareAndSet(false, true)) return
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                associationsChanged(events)
            }
        })
    }

    internal fun associationsChanged(events: List<VFileEvent>, roots: () -> List<String> = ::projectRootPaths) {
        val projectRoots = roots()
        if (events.none { affectsAssociations(it, projectRoots) }) return
        schemas.keys.removeIf { it != BUNDLED }
        // Merge VFS deliveries from package installation; never reset under the write action.
        if (resetPending.compareAndSet(false, true)) {
            // The injected service scope cancels pending resets when the service is disposed.
            coroutineScope.launch {
                delay(100.milliseconds)
                resetPending.set(false)
                if (!project.isDisposed) JsonSchemaService.Impl.get(project).reset()
            }
        }
    }

    internal fun observeConfigs(files: List<VirtualFile>) {
        configPaths = files.map { it.path }
        schemaRoots = (schemaRoots + files.flatMap { config ->
            generateSequence(config.parent) { it.parent }
                .map { "${it.path}/node_modules/@effect/tsgo" }.toList()
        }).distinct()
    }

    private fun projectRootPaths(): List<String> =
        com.intellij.openapi.roots.ProjectRootManager.getInstance(project).contentRoots.map { it.path } +
            listOfNotNull(project.basePath?.replace('\\', '/'))

    internal fun affectsAssociations(event: VFileEvent): Boolean = affectsAssociations(event, projectRootPaths())

    private fun affectsAssociations(event: VFileEvent, projectRoots: List<String>): Boolean {
        val paths = buildList {
            add(event.path)
            if (event is VFilePropertyChangeEvent && event.isRename) add(event.oldPath)
            if (event is VFileMoveEvent) add("${event.oldParent.path}/${event.file.name}")
        }
        return paths.any { path ->
            val inProject = projectRoots.any { path == it || path.startsWith("$it/") }
            // Config contents do not change the provider list. Directory moves/removals do.
            val configChange = event !is VFileContentChangeEvent &&
                inProject &&
                (path.endsWith("/tsconfig.json") || path.endsWith("/jsconfig.json") ||
                    (event.file?.isDirectory == true &&
                        (event is VFileMoveEvent || configPaths.any { it.startsWith("$path/") })))
            val schemaChange = schemaRoots.any { root ->
                path == "$root/schema.json" ||
                    (event !is VFileContentChangeEvent && (root == path || root.startsWith("$path/")))
            } || (inProject && path.endsWith("/node_modules/@effect/tsgo/schema.json"))
            configChange || schemaChange
        }
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
