package dev.effect.intellij.status

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.EventDispatcher
import java.time.Instant
import java.util.EventListener

enum class EffectLspStatus {
    NOT_CONFIGURED,
    RESOLVING_BINARY,
    STARTING,
    RUNNING,
    RESTART_REQUIRED,
    ERROR,
}

data class EffectStatusSnapshot(
    val status: EffectLspStatus = EffectLspStatus.NOT_CONFIGURED,
    val detail: String? = null,
    val binaryPath: String? = null,
    val updatedAt: Instant = Instant.now(),
)

fun interface EffectStatusListener : EventListener {
    fun statusChanged(snapshot: EffectStatusSnapshot)
}

@Service(Service.Level.PROJECT)
class EffectStatusService {
    private val dispatcher = EventDispatcher.create(EffectStatusListener::class.java)
    @Volatile
    private var snapshot = EffectStatusSnapshot()

    fun currentSnapshot(): EffectStatusSnapshot = snapshot

    fun addListener(listener: EffectStatusListener) {
        dispatcher.addListener(listener)
    }

    fun update(status: EffectLspStatus, detail: String? = null, binaryPath: String? = null) {
        snapshot = EffectStatusSnapshot(status = status, detail = detail, binaryPath = binaryPath)
        dispatcher.multicaster.statusChanged(snapshot)
    }

    fun markResolvingBinary(detail: String? = null) = update(EffectLspStatus.RESOLVING_BINARY, detail = detail)

    fun markStarting(binaryPath: String? = null) = update(EffectLspStatus.STARTING, binaryPath = binaryPath)

    fun markRunning(binaryPath: String?) = update(EffectLspStatus.RUNNING, binaryPath = binaryPath)

    fun markRestartRequired(detail: String) = update(EffectLspStatus.RESTART_REQUIRED, detail = detail)

    fun requestRestart(detail: String) {
        if (snapshot.status in RESTARTABLE_STATUSES) {
            update(EffectLspStatus.RESTART_REQUIRED, detail = detail, binaryPath = snapshot.binaryPath)
        }
    }

    fun markError(detail: String) = update(EffectLspStatus.ERROR, detail = detail)

    fun markNotConfigured(detail: String = "Effect binary is not configured for this project.") {
        update(EffectLspStatus.NOT_CONFIGURED, detail = detail)
    }

    companion object {
        private val RESTARTABLE_STATUSES = setOf(
            EffectLspStatus.STARTING,
            EffectLspStatus.RUNNING,
            EffectLspStatus.RESTART_REQUIRED,
        )

        fun getInstance(project: Project): EffectStatusService = project.getService(EffectStatusService::class.java)
    }
}
