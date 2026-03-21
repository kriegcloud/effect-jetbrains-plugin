package dev.effect.intellij.debug

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.EventDispatcher
import com.intellij.xdebugger.XDebuggerManager
import java.util.EventListener

data class DebugBridgeState(
    val attachedSessionName: String? = null,
    val attachedSessionType: String? = null,
    val guidance: String = "Attach a paused debug session to review setup guidance. Automatic instrumentation injection and live Effect debug snapshots are not implemented yet.",
)

fun interface EffectDebugBridgeListener : EventListener {
    fun stateChanged(state: DebugBridgeState)
}

@Service(Service.Level.PROJECT)
class EffectDebugBridgeService {
    private val dispatcher = EventDispatcher.create(EffectDebugBridgeListener::class.java)
    @Volatile
    private var state = DebugBridgeState()

    fun currentState(): DebugBridgeState = state

    fun addListener(listener: EffectDebugBridgeListener) {
        dispatcher.addListener(listener)
    }

    fun addListener(listener: EffectDebugBridgeListener, parentDisposable: Disposable) {
        dispatcher.addListener(listener, parentDisposable)
    }

    fun attachToSession(project: Project, sessionId: String) {
        val session = XDebuggerManager.getInstance(project).currentSession
        if (session == null || session.sessionName != sessionId) {
            state = DebugBridgeState(
                guidance = "No active debug session is available. Start and pause a supported session, then attach it from Effect Dev Tools. Automatic instrumentation injection is not implemented yet.",
            )
            dispatcher.multicaster.stateChanged(state)
            return
        }

        state = DebugBridgeState(
            attachedSessionName = session.sessionName,
            attachedSessionType = session.debugProcess.javaClass.simpleName,
            guidance = buildString {
                appendLine("Attached to the active JetBrains debug session.")
                appendLine("Automatic debugger instrumentation injection is not implemented in this plugin yet.")
                append("Live Effect runtime snapshots for Context, Span Stack, Fibers, and Breakpoints remain deferred until the debugger bridge is completed.")
            },
        )
        dispatcher.multicaster.stateChanged(state)
    }

    fun detach(project: Project) {
        state = DebugBridgeState()
        dispatcher.multicaster.stateChanged(state)
    }

    fun refreshSnapshots(project: Project) {
        val attachedSessionName = state.attachedSessionName ?: return
        attachToSession(project, attachedSessionName)
    }
}
