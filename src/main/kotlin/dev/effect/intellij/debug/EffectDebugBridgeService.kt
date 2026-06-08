package dev.effect.intellij.debug

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.EventDispatcher
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import dev.effect.intellij.core.EffectJson
import dev.effect.intellij.core.logger
import java.util.EventListener
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.Icon

data class DebugBridgeState(
    val attachedSessionName: String? = null,
    val attachedSessionType: String? = null,
    val instrumentationPath: String? = null,
    val guidance: String = "Attach a paused debug session to inspect Effect Context, Span Stack, Fibers, and pause-on-defect state.",
    val snapshot: EffectDebugSnapshot? = null,
    val error: String? = null,
    val refreshInProgress: Boolean = false,
)

fun interface EffectDebugBridgeListener : EventListener {
    fun stateChanged(state: DebugBridgeState)
}

@Service(Service.Level.PROJECT)
class EffectDebugBridgeService {
    private val log = logger<EffectDebugBridgeService>()
    private val dispatcher = EventDispatcher.create(EffectDebugBridgeListener::class.java)

    @Volatile
    private var state = DebugBridgeState()
    private var sessionDisposable: Disposable? = null

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
            updateState(
                DebugBridgeState(
                    guidance = "No active debug session is available. Start and pause a supported session, then attach it from Effect Dev Tools.",
                ),
            )
            return
        }

        disposeSessionListener()
        val instrumentationPath = EffectInstrumentationService.getInstance().ensureInstrumentationScript().toString()
        val baseState = DebugBridgeState(
            attachedSessionName = session.sessionName,
            attachedSessionType = session.debugProcess.javaClass.simpleName,
            instrumentationPath = instrumentationPath,
            guidance = buildString {
                appendLine("Attached to the active JetBrains debug session.")
                appendLine("Effect instrumentation is available at:")
                appendLine(instrumentationPath)
                append("Pause the session to refresh Effect runtime snapshots.")
            },
        )
        updateState(baseState)

        val listenerDisposable = Disposer.newDisposable("Effect debug bridge session listener")
        session.addSessionListener(
            object : XDebugSessionListener {
                override fun sessionPaused() {
                    refreshSnapshots(project)
                }

                override fun stackFrameChanged() {
                    refreshSnapshots(project)
                }

                override fun sessionResumed() {
                    updateState(currentState().copy(refreshInProgress = false))
                }

                override fun sessionStopped() {
                    detach(project)
                }
            },
            listenerDisposable,
        )
        sessionDisposable = listenerDisposable

        refreshSnapshots(project)
    }

    fun detach(project: Project) {
        disposeSessionListener()
        updateState(DebugBridgeState())
    }

    fun refreshSnapshots(project: Project) {
        val attachedSessionName = state.attachedSessionName ?: return
        val session = XDebuggerManager.getInstance(project).currentSession
        if (session == null || session.sessionName != attachedSessionName) {
            updateState(
                state.copy(
                    error = "The attached debug session is no longer active.",
                    refreshInProgress = false,
                ),
            )
            return
        }

        updateState(state.copy(refreshInProgress = true, error = null))
        ApplicationManager.getApplication().executeOnPooledThread {
            val nextState = runCatching {
                captureSnapshot(project, session)
            }.fold(
                onSuccess = { snapshot ->
                    currentState().copy(
                        snapshot = snapshot,
                        error = null,
                        refreshInProgress = false,
                    )
                },
                onFailure = { error ->
                    log.debug("Effect debug snapshot refresh failed", error)
                    currentState().copy(
                        error = error.message ?: error.javaClass.simpleName,
                        refreshInProgress = false,
                    )
                },
            )
            updateState(nextState)
        }
    }

    fun togglePauseOnDefects(project: Project) {
        evaluateControlExpression(project, "togglePauseOnDefects") {
            """
            (function() {
              var instrumentation = globalThis["effect/devtools/instrumentation"];
              if (!instrumentation) return JSON.stringify({ success: false, message: "Effect instrumentation is not installed." });
              var config = instrumentation.togglePauseOnDefects();
              return JSON.stringify({ success: true, config: config });
            })()
            """.trimIndent()
        }
    }

    fun interruptCurrentFiber(project: Project) {
        val fiberId = state.snapshot?.fibers
            ?.firstOrNull { it.isCurrent && it.isInterruptible && !it.isInterrupted }
            ?.id
        if (fiberId == null) {
            updateState(state.copy(error = "No interruptible current Effect fiber is available to interrupt."))
            return
        }
        interruptFiber(project, fiberId)
    }

    fun interruptFiber(project: Project, fiberId: String) {
        evaluateControlExpression(project, "interruptFiber") {
            """
            (function() {
              var instrumentation = globalThis["effect/devtools/instrumentation"];
              if (!instrumentation) return JSON.stringify({ success: false, message: "Effect instrumentation is not installed." });
              instrumentation.interruptFiber(${EffectJson.mapper.writeValueAsString(fiberId)});
              return JSON.stringify({ success: true });
            })()
            """.trimIndent()
        }
    }

    private fun evaluateControlExpression(project: Project, operation: String, expression: () -> String) {
        val session = currentAttachedSession(project) ?: return
        updateState(state.copy(refreshInProgress = true, error = null))
        ApplicationManager.getApplication().executeOnPooledThread {
            val nextState = runCatching {
                ensureInstrumentationInstalled(session)
                evaluateExpression(session, expression())
                captureSnapshot(project, session)
            }.fold(
                onSuccess = { snapshot -> currentState().copy(snapshot = snapshot, error = null, refreshInProgress = false) },
                onFailure = { error ->
                    log.debug("Effect debug $operation failed", error)
                    currentState().copy(error = error.message ?: error.javaClass.simpleName, refreshInProgress = false)
                },
            )
            updateState(nextState)
        }
    }

    private fun captureSnapshot(project: Project, session: XDebugSession): EffectDebugSnapshot {
        if (!session.isSuspended) {
            return currentState().snapshot?.copy(message = "Debug session is running. Pause it to refresh Effect snapshots.")
                ?: EffectDebugSnapshot(message = "Debug session is running. Pause it to refresh Effect snapshots.")
        }
        ensureInstrumentationInstalled(session)
        val raw = evaluateExpression(session, SNAPSHOT_EXPRESSION)
        return EffectDebugSnapshot.parse(decodeDebuggerString(raw))
    }

    private fun ensureInstrumentationInstalled(session: XDebugSession) {
        val source = EffectInstrumentationService.getInstance().instrumentationSource()
        evaluateExpression(
            session,
            """
            (function() {
              if (!(globalThis && globalThis["effect/devtools/instrumentation"])) {
            $source
              }
              return JSON.stringify({ success: !!globalThis["effect/devtools/instrumentation"] });
            })()
            """.trimIndent(),
        )
    }

    private fun currentAttachedSession(project: Project): XDebugSession? {
        val attachedSessionName = state.attachedSessionName
        val session = XDebuggerManager.getInstance(project).currentSession
        if (session == null || session.sessionName != attachedSessionName) {
            updateState(state.copy(error = "The attached debug session is no longer active."))
            return null
        }
        return session
    }

    private fun evaluateExpression(session: XDebugSession, expression: String): String {
        val future = CompletableFuture<String>()
        ApplicationManager.getApplication().invokeLater {
            try {
                val frame = session.currentStackFrame
                val evaluator = frame?.evaluator
                if (evaluator == null) {
                    future.completeExceptionally(IllegalStateException("The current stack frame does not support expression evaluation."))
                    return@invokeLater
                }
                evaluator.evaluate(
                    expression,
                    object : XDebuggerEvaluator.XEvaluationCallback {
                        override fun evaluated(value: XValue) {
                            value.computePresentation(CapturingValueNode(future), XValuePlace.TREE)
                        }

                        override fun errorOccurred(errorMessage: String) {
                            future.completeExceptionally(IllegalStateException(errorMessage))
                        }
                    },
                    session.currentPosition,
                )
            } catch (error: Throwable) {
                future.completeExceptionally(error)
            }
        }
        return future.get(5, TimeUnit.SECONDS)
    }

    private fun decodeDebuggerString(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            return EffectJson.mapper.readValue(trimmed, String::class.java)
        }
        return trimmed
    }

    private fun updateState(nextState: DebugBridgeState) {
        state = nextState
        dispatcher.multicaster.stateChanged(nextState)
    }

    private fun disposeSessionListener() {
        sessionDisposable?.let(Disposer::dispose)
        sessionDisposable = null
    }

    private class CapturingValueNode(
        private val future: CompletableFuture<String>,
    ) : XValueNode {
        override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) {
            future.complete(value)
        }

        override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
            val renderer = CapturingValueRenderer()
            presentation.renderValue(renderer)
            future.complete(renderer.value)
        }

        override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) = Unit
    }

    private class CapturingValueRenderer : XValuePresentation.XValueTextRenderer {
        private val builder = StringBuilder()
        val value: String
            get() = builder.toString()

        override fun renderValue(value: String) {
            builder.append(value)
        }

        override fun renderStringValue(value: String) {
            builder.append(value)
        }

        override fun renderNumericValue(value: String) {
            builder.append(value)
        }

        override fun renderKeywordValue(value: String) {
            builder.append(value)
        }

        override fun renderValue(value: String, key: com.intellij.openapi.editor.colors.TextAttributesKey) {
            builder.append(value)
        }

        override fun renderStringValue(value: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) {
            builder.append(value)
        }

        override fun renderComment(comment: String) {
            builder.append(comment)
        }

        override fun renderSpecialSymbol(symbol: String) {
            builder.append(symbol)
        }

        override fun renderError(error: String) {
            builder.append(error)
        }
    }

    companion object {
        private val SNAPSHOT_EXPRESSION = """
            (function() {
              var instrumentation = globalThis["effect/devtools/instrumentation"];
              if (!instrumentation) {
                return JSON.stringify({ message: "Effect instrumentation is not installed in this debug target." });
              }
              var currentFiber = globalThis["effect/FiberCurrent"];
              var breakpoints = instrumentation.getBreakpointSnapshot
                ? instrumentation.getBreakpointSnapshot()
                : { pauseOnDefects: !!(instrumentation.getAutoPauseConfig && instrumentation.getAutoPauseConfig().pauseOnDefects), values: [], location: null };
              return JSON.stringify({
                context: instrumentation.getFiberCurrentContextSnapshot ? instrumentation.getFiberCurrentContextSnapshot(currentFiber) : [],
                spanStack: instrumentation.getFiberCurrentSpanStackSnapshot ? instrumentation.getFiberCurrentSpanStackSnapshot(currentFiber, 64) : [],
                fibers: instrumentation.getAliveFibersSnapshot ? instrumentation.getAliveFibersSnapshot() : [],
                breakpoints: breakpoints,
                message: "Snapshot captured from the active debug frame."
              });
            })()
        """.trimIndent()
    }
}
