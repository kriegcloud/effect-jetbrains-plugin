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
class EffectDebugBridgeService : Disposable {
    private val log = logger<EffectDebugBridgeService>()
    private val dispatcher = EventDispatcher.create(EffectDebugBridgeListener::class.java)

    @Volatile
    private var state = DebugBridgeState()
    private var sessionDisposable: Disposable? = null
    private var attachedSession: XDebugSession? = null
    private data class CaptureRequest(
        val project: Project,
        val session: XDebugSession,
        val frame: com.intellij.xdebugger.frame.XStackFrame?,
        val expression: String? = null,
    )
    private val requests = EffectDebugRequests<CaptureRequest>()
    private val preparation = EffectDebugPreparation(
        background = { ApplicationManager.getApplication().executeOnPooledThread(it) },
        foreground = { ApplicationManager.getApplication().invokeLater(it) },
    )
    private var worker: java.util.concurrent.Future<*>? = null
    @Volatile private var disposed = false

    fun currentState(): DebugBridgeState = state

    fun addListener(listener: EffectDebugBridgeListener, parentDisposable: Disposable) {
        dispatcher.addListener(listener, parentDisposable)
    }

    fun attachToSession(project: Project, sessionId: String) {
        if (disposed || project.isDisposed) return
        requests.invalidate()
        preparation.invalidate()
        disposeSessionListener()
        attachedSession = null
        val session = XDebuggerManager.getInstance(project).currentSession
        if (session == null || session.sessionName != sessionId) {
            updateState(
                DebugBridgeState(
                    guidance = "No active debug session is available. Start and pause a supported session, then attach it from Effect Dev Tools.",
                ),
            )
            return
        }

        attachedSession = session
        updateState(DebugBridgeState(
            attachedSessionName = session.sessionName,
            attachedSessionType = session.debugProcess.javaClass.simpleName,
            guidance = "Preparing Effect instrumentation for the active debug session.",
        ))

        val listenerDisposable = Disposer.newDisposable("Effect debug bridge session listener")
        Disposer.register(this, listenerDisposable)
        session.addSessionListener(
            object : XDebugSessionListener {
                override fun sessionPaused() {
                    refreshSnapshots(project)
                }

                override fun stackFrameChanged() {
                    refreshSnapshots(project)
                }

                override fun sessionResumed() {
                    requests.invalidate()
                    updateState(currentState().copy(snapshot = null, refreshInProgress = false))
                }

                override fun sessionStopped() {
                    detach(project)
                }
            },
            listenerDisposable,
        )
        sessionDisposable = listenerDisposable

        preparation.start({ EffectInstrumentationService.getInstance().ensureInstrumentationScript().toString() }) { result ->
            if (!disposed && !project.isDisposed && attachedSession === session) {
                result.fold(
                    onSuccess = { path ->
                        updateState(state.copy(
                            instrumentationPath = path,
                            guidance = "Effect instrumentation is available at:\n$path\nPause the session to refresh Effect runtime snapshots.",
                        ))
                        refreshSnapshots(project)
                    },
                    onFailure = { updateState(state.copy(error = it.message ?: it.javaClass.simpleName)) },
                )
            }
        }
    }

    fun detach(project: Project) {
        preparation.invalidate()
        requests.invalidate()
        attachedSession = null
        disposeSessionListener()
        updateState(DebugBridgeState())
    }

    fun refreshSnapshots(project: Project) {
        val session = currentAttachedSession(project) ?: return
        enqueueCapture(CaptureRequest(project, session, session.currentStackFrame))
    }

    private fun enqueueCapture(request: CaptureRequest) {
        if (disposed || request.project.isDisposed) return
        updateState(state.copy(snapshot = null, refreshInProgress = true, error = null))
        if (!requests.enqueue(request)) return
        worker = ApplicationManager.getApplication().executeOnPooledThread {
            while (!disposed) {
                val work = requests.poll() ?: break
                val result = runCatching {
                    work.value.expression?.let { expression ->
                        ensureInstrumentationInstalled(work.value.session, work.token, work.value.frame)
                        evaluateExpression(work.value.session, expression, work.token, work.value.frame)
                    }
                    captureSnapshot(work.value, work.token)
                }
                ApplicationManager.getApplication().invokeLater {
                    requests.ifCurrent(work.token) {
                        val request = work.value
                        if (!request.project.isDisposed && attachedSession === request.session &&
                            request.session.currentStackFrame === request.frame
                        ) {
                            result.fold(
                                onSuccess = { updateState(state.copy(snapshot = it, error = null, refreshInProgress = false)) },
                                onFailure = {
                                    log.debug("Effect debug request failed", it)
                                    updateState(state.copy(error = it.message ?: it.javaClass.simpleName, refreshInProgress = false))
                                },
                            )
                        }
                    }
                }
            }
        }
        if (disposed) worker?.cancel(true)
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
        if (!session.isSuspended) {
            updateState(state.copy(error = "Pause the debug session before $operation."))
            return
        }
        enqueueCapture(CaptureRequest(project, session, session.currentStackFrame, expression()))
    }

    private fun captureSnapshot(request: CaptureRequest, token: EffectDebugRequests.Token): EffectDebugSnapshot {
        if (!request.session.isSuspended) {
            return EffectDebugSnapshot(message = "Debug session is running. Pause it to refresh Effect snapshots.")
        }
        ensureInstrumentationInstalled(request.session, token, request.frame)
        val raw = evaluateExpression(request.session, SNAPSHOT_EXPRESSION, token, request.frame)
        return EffectDebugSnapshot.parse(decodeDebuggerString(raw))
    }

    private fun ensureInstrumentationInstalled(
        session: XDebugSession,
        token: EffectDebugRequests.Token,
        frame: com.intellij.xdebugger.frame.XStackFrame?,
    ) {
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
            token,
            frame,
        )
    }

    private fun currentAttachedSession(project: Project): XDebugSession? {
        if (disposed || project.isDisposed) return null
        val attachedSessionName = state.attachedSessionName ?: return null
        val session = XDebuggerManager.getInstance(project).currentSession
        if (session == null || session !== attachedSession || session.sessionName != attachedSessionName) {
            requests.invalidate()
            updateState(state.copy(error = "The attached debug session is no longer active."))
            return null
        }
        return session
    }

    private fun evaluateExpression(
        session: XDebugSession,
        expression: String,
        token: EffectDebugRequests.Token,
        frame: com.intellij.xdebugger.frame.XStackFrame?,
    ): String {
        val evaluation = EffectDebugEvaluation(token)
        val future = evaluation.future
        ApplicationManager.getApplication().invokeLater {
            try {
                if (!evaluation.canEvaluate() || disposed || session.project.isDisposed ||
                    attachedSession !== session || !session.isSuspended || session.currentStackFrame !== frame
                ) {
                    future.cancel(false)
                    return@invokeLater
                }
                val evaluator = frame?.evaluator
                if (evaluator == null) {
                    future.completeExceptionally(IllegalStateException("The current stack frame does not support expression evaluation."))
                    return@invokeLater
                }
                evaluator.evaluate(
                    expression,
                    object : XDebuggerEvaluator.XEvaluationCallback {
                        override fun evaluated(value: XValue) {
                            if (evaluation.canEvaluate()) value.computePresentation(CapturingValueNode(future), XValuePlace.TREE)
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
        return evaluation.use { future.get(5, TimeUnit.SECONDS) }
    }

    private fun decodeDebuggerString(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            return EffectJson.mapper.readValue(trimmed, String::class.java)
        }
        return trimmed
    }

    private fun updateState(nextState: DebugBridgeState) {
        if (disposed) return
        state = nextState
        dispatcher.multicaster.stateChanged(nextState)
    }

    private fun disposeSessionListener() {
        sessionDisposable?.let(Disposer::dispose)
        sessionDisposable = null
    }

    override fun dispose() {
        preparation.invalidate()
        disposed = true
        requests.dispose()
        worker?.cancel(true)
        attachedSession = null
        disposeSessionListener()
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
              var currentFiber = (instrumentation.getCurrentFiber ? instrumentation.getCurrentFiber() : null)
                || globalThis["~effect/Fiber/currentFiber"]
                || globalThis["effect/FiberCurrent"];
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
