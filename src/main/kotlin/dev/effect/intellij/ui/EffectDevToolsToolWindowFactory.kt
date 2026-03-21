package dev.effect.intellij.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.xdebugger.XDebuggerManager
import dev.effect.intellij.debug.DebugBridgeState
import dev.effect.intellij.debug.EffectDebugBridgeService
import dev.effect.intellij.devtools.DevToolsRuntimeState
import dev.effect.intellij.devtools.EffectDevToolsService
import dev.effect.intellij.devtools.RuntimeClientSnapshot
import dev.effect.intellij.devtools.RuntimeDetailEntry
import dev.effect.intellij.devtools.RuntimeMetricSnapshot
import dev.effect.intellij.devtools.RuntimeSpanEventSnapshot
import dev.effect.intellij.devtools.RuntimeSpanSnapshot
import dev.effect.intellij.settings.EffectProjectSettingsConfigurable
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JTree
import javax.swing.event.TreeSelectionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class EffectDevToolsToolWindowPanel(private val project: Project) {
    private val devToolsService = project.getService(EffectDevToolsService::class.java)
    private val debugBridgeService = project.getService(EffectDebugBridgeService::class.java)

    val component: JComponent = SimpleToolWindowPanel(true, true).apply {
        setToolbar(
            ActionManager.getInstance().createActionToolbar(
                "EffectDevToolsToolbar",
                DefaultActionGroup(
                    StartRuntimeAction(project),
                    StopRuntimeAction(project),
                    RestartRuntimeAction(project),
                    SelectActiveClientAction(project),
                    OpenSettingsAction(project),
                    ResetMetricsAction(project),
                    ResetTracerAction(project),
                    AttachDebugAction(project),
                ),
                true,
            ).component,
        )
        setContent(buildContent())
    }

    private fun buildContent(): JComponent {
        val clientsPanel = ClientsTabPanel(project, devToolsService)
        val metricsPanel = MetricsTabPanel(devToolsService)
        val tracerPanel = TracerTabPanel(devToolsService)
        val debugPanel = DebugTabGroup(debugBridgeService)

        devToolsService.addListener { state ->
            onEdt {
                clientsPanel.refresh(state)
                metricsPanel.refresh(state)
                tracerPanel.refresh(state)
            }
        }
        debugBridgeService.addListener { state ->
            onEdt {
                debugPanel.refresh(state)
            }
        }

        val initialRuntimeState = devToolsService.currentState()
        clientsPanel.refresh(initialRuntimeState)
        metricsPanel.refresh(initialRuntimeState)
        tracerPanel.refresh(initialRuntimeState)
        debugPanel.refresh(debugBridgeService.currentState())

        return JTabbedPane().apply {
            addTab("Clients", clientsPanel.component)
            addTab("Metrics", metricsPanel.component)
            addTab("Tracer", tracerPanel.component)
            addTab("Debug", debugPanel.component)
        }
    }

    private fun onEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(block)
    }
}

private class ClientsTabPanel(
    private val project: Project,
    private val devToolsService: EffectDevToolsService,
) {
    private val statusLabel = JBLabel()
    private val clientsModel = CollectionListModel<RuntimeClientSnapshot>()
    private val clientsList = JBList(clientsModel).apply {
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean,
            ): Component {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                if (value is RuntimeClientSnapshot) {
                    text = buildString {
                        append(value.name)
                        append("  ")
                        append(value.remoteAddress)
                        value.metricsSummary?.let {
                            append("  ")
                            append(it)
                        }
                    }
                }
                return component
            }
        }
    }
    private val detailArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    val component: JComponent = JBSplitter(false, 0.35f).apply {
        firstComponent = JPanel(BorderLayout()).apply {
            add(statusLabel, BorderLayout.NORTH)
            add(JBScrollPane(clientsList), BorderLayout.CENTER)
        }
        secondComponent = JBScrollPane(detailArea)
        preferredSize = Dimension(700, 500)
    }

    init {
        clientsList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val selected = clientsList.selectedValue
                devToolsService.selectActiveClient(clientId = selected?.id)
                detailArea.text = selected?.formatClientDetails() ?: "Select a runtime client to inspect details."
            }
        }
    }

    fun refresh(state: DevToolsRuntimeState) {
        statusLabel.text = when {
            state.error != null -> state.error
            state.running -> "Runtime server listening on localhost:${state.port}"
            else -> "Runtime server is stopped. Start it to accept Effect Dev Tools clients."
        }

        clientsModel.replaceAll(state.clients)
        val selected = state.clients.firstOrNull { it.id == state.activeClientId }
        if (selected != null) {
            clientsList.setSelectedValue(selected, true)
            detailArea.text = selected.formatClientDetails()
        } else {
            detailArea.text = "No runtime clients connected."
        }
    }
}

private class MetricsTabPanel(
    private val devToolsService: EffectDevToolsService,
) {
    private val statusLabel = JBLabel()
    private val treeRoot = DefaultMutableTreeNode("Metrics")
    private val treeModel = DefaultTreeModel(treeRoot)
    private val tree = JTree(treeModel).apply {
        isRootVisible = false
        expandsSelectedPaths = true
    }
    private val detailArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        text = "Select a metric to inspect its details."
    }

    val component: JComponent = JPanel(BorderLayout()).apply {
        add(statusLabel, BorderLayout.NORTH)
        add(
            JBSplitter(false, 0.5f).apply {
                firstComponent = JBScrollPane(tree)
                secondComponent = JBScrollPane(detailArea)
            },
            BorderLayout.CENTER,
        )
    }

    init {
        tree.addTreeSelectionListener(TreeSelectionListener {
            val userObject = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject
            detailArea.text = when (userObject) {
                is RuntimeMetricSnapshot -> userObject.formatMetricDetails()
                is RuntimeDetailEntry -> "${userObject.key}: ${userObject.value}"
                else -> "Select a metric to inspect its details."
            }
        })
    }

    fun refresh(state: DevToolsRuntimeState) {
        val activeClient = state.clients.firstOrNull { it.id == state.activeClientId }
        statusLabel.text = when {
            !state.running -> "Runtime server is stopped."
            activeClient == null -> "No active runtime client selected."
            activeClient.metrics.isEmpty() -> "No metrics published yet for ${activeClient.name}."
            else -> "Showing metrics for ${activeClient.name}."
        }

        rebuildTree(treeRoot, treeModel) {
            activeClient?.metrics?.forEach { metric ->
                val metricNode = DefaultMutableTreeNode(metric)
                metric.tagsWithoutUnit.forEach { tag ->
                    metricNode.add(DefaultMutableTreeNode(RuntimeDetailEntry(tag.key, tag.value)))
                }
                metric.details.forEach { detail -> metricNode.add(DefaultMutableTreeNode(detail)) }
                treeRoot.add(metricNode)
            }
        }
        expandAll(tree)
    }
}

private class TracerTabPanel(
    private val devToolsService: EffectDevToolsService,
) {
    private val statusLabel = JBLabel()
    private val treeRoot = DefaultMutableTreeNode("Tracer")
    private val treeModel = DefaultTreeModel(treeRoot)
    private val tree = JTree(treeModel).apply {
        isRootVisible = false
        expandsSelectedPaths = true
    }
    private val detailArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        text = "Select a span or span event to inspect its details."
    }

    val component: JComponent = JPanel(BorderLayout()).apply {
        add(statusLabel, BorderLayout.NORTH)
        add(
            JBSplitter(false, 0.5f).apply {
                firstComponent = JBScrollPane(tree)
                secondComponent = JBScrollPane(detailArea)
            },
            BorderLayout.CENTER,
        )
    }

    init {
        tree.addTreeSelectionListener(TreeSelectionListener {
            val userObject = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject
            detailArea.text = when (userObject) {
                is RuntimeSpanSnapshot -> userObject.formatSpanDetails()
                is RuntimeSpanEventSnapshot -> userObject.formatSpanEventDetails()
                is RuntimeDetailEntry -> "${userObject.key}: ${userObject.value}"
                else -> "Select a span or span event to inspect its details."
            }
        })
    }

    fun refresh(state: DevToolsRuntimeState) {
        val activeClient = state.clients.firstOrNull { it.id == state.activeClientId }
        statusLabel.text = when {
            !state.running -> "Runtime server is stopped."
            activeClient == null -> "No active runtime client selected."
            activeClient.rootSpans.isEmpty() -> "No span activity published yet for ${activeClient.name}."
            else -> "Showing tracer data for ${activeClient.name}."
        }

        rebuildTree(treeRoot, treeModel) {
            activeClient?.rootSpans?.forEach { span ->
                treeRoot.add(buildSpanNode(span))
            }
        }
        expandAll(tree)
    }

    private fun buildSpanNode(span: RuntimeSpanSnapshot): DefaultMutableTreeNode {
        val node = DefaultMutableTreeNode(span)
        span.events.forEach { event -> node.add(DefaultMutableTreeNode(event)) }
        span.children.forEach { child -> node.add(buildSpanNode(child)) }
        return node
    }
}

private class DebugTabGroup(
    private val debugBridgeService: EffectDebugBridgeService,
) {
    private val contextPanel = DebugStatePanel("Context")
    private val spanStackPanel = DebugStatePanel("Span Stack")
    private val fibersPanel = DebugStatePanel("Fibers")
    private val breakpointsPanel = DebugStatePanel("Breakpoints")

    val component: JComponent = JTabbedPane().apply {
        addTab("Context", contextPanel.component)
        addTab("Span Stack", spanStackPanel.component)
        addTab("Fibers", fibersPanel.component)
        addTab("Breakpoints", breakpointsPanel.component)
    }

    fun refresh(state: DebugBridgeState) {
        listOf(contextPanel, spanStackPanel, fibersPanel, breakpointsPanel).forEach { panel ->
            panel.refresh(state)
        }
    }
}

private class DebugStatePanel(private val title: String) {
    private val header = JBLabel()
    private val body = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    val component: JComponent = JPanel(BorderLayout()).apply {
        add(header, BorderLayout.NORTH)
        add(JBScrollPane(body), BorderLayout.CENTER)
    }

    fun refresh(state: DebugBridgeState) {
        header.text = if (state.attachedSessionName == null) {
            "$title: no debug session attached"
        } else {
            "$title: attached to ${state.attachedSessionName} (${state.attachedSessionType ?: "unknown"})"
        }
        body.text = state.guidance
    }
}

private class StartRuntimeAction(private val project: Project) : AnAction("Start Runtime") {
    override fun actionPerformed(event: AnActionEvent) {
        project.getService(EffectDevToolsService::class.java).startServer()
    }
}

private class StopRuntimeAction(private val project: Project) : AnAction("Stop Runtime") {
    override fun actionPerformed(event: AnActionEvent) {
        project.getService(EffectDevToolsService::class.java).stopServer()
    }
}

private class RestartRuntimeAction(private val project: Project) : AnAction("Restart Runtime") {
    override fun actionPerformed(event: AnActionEvent) {
        project.getService(EffectDevToolsService::class.java).restartServer()
    }
}

private class SelectActiveClientAction(private val project: Project) : AnAction("Select Client") {
    override fun actionPerformed(event: AnActionEvent) {
        val service = project.getService(EffectDevToolsService::class.java)
        val state = service.currentState()
        if (state.clients.isEmpty()) {
            service.selectActiveClient(clientId = null)
            return
        }
        val currentIndex = state.clients.indexOfFirst { it.id == state.activeClientId }
        val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % state.clients.size
        service.selectActiveClient(clientId = state.clients[nextIndex].id)
    }
}

private class OpenSettingsAction(private val project: Project) : AnAction("Open Settings") {
    override fun actionPerformed(event: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, EffectProjectSettingsConfigurable::class.java)
    }
}

private class ResetMetricsAction(private val project: Project) : AnAction("Reset Metrics") {
    override fun actionPerformed(event: AnActionEvent) {
        project.getService(EffectDevToolsService::class.java).resetMetrics()
    }
}

private class ResetTracerAction(private val project: Project) : AnAction("Reset Tracer") {
    override fun actionPerformed(event: AnActionEvent) {
        project.getService(EffectDevToolsService::class.java).resetTracer()
    }
}

private class AttachDebugAction(private val project: Project) : AnAction("Attach Debug") {
    override fun actionPerformed(event: AnActionEvent) {
        val session = XDebuggerManager.getInstance(project).currentSession
        val bridge = project.getService(EffectDebugBridgeService::class.java)
        if (session == null) {
            bridge.detach(project)
            return
        }
        bridge.attachToSession(project, session.sessionName)
    }
}

private fun RuntimeClientSnapshot.formatClientDetails(): String = buildString {
    appendLine(name)
    appendLine(remoteAddress)
    appendLine("Connected: $connectedAt")
    appendLine("Last seen: $lastSeenAt")
    appendLine("Metrics: ${metricsSummary ?: "none"}")
    appendLine("Tracer: ${tracerSummary ?: "none"}")
}

private fun RuntimeMetricSnapshot.formatMetricDetails(): String = buildString {
    appendLine("$kind ${name.ifBlank { "<unnamed>" }}")
    description?.let(::appendLine)
    appendLine(summary)
    if (tagsWithoutUnit.isNotEmpty()) {
        appendLine()
        appendLine("Tags")
        tagsWithoutUnit.forEach { tag -> appendLine("${tag.key}: ${tag.value}") }
    }
    if (details.isNotEmpty()) {
        appendLine()
        details.forEach { detail -> appendLine("${detail.key}: ${detail.value}") }
    }
}

private fun RuntimeSpanSnapshot.formatSpanDetails(): String = buildString {
    appendLine(name)
    appendLine("spanId: $spanId")
    appendLine("traceId: $traceId")
    appendLine("status: $status")
    appendLine("sampled: $sampled")
    if (details.isNotEmpty()) {
        appendLine()
        details.forEach { detail -> appendLine("${detail.key}: ${detail.value}") }
    }
}

private fun RuntimeSpanEventSnapshot.formatSpanEventDetails(): String = buildString {
    appendLine(name)
    appendLine("startTime: $startTime")
    if (details.isNotEmpty()) {
        appendLine()
        details.forEach { detail -> appendLine("${detail.key}: ${detail.value}") }
    }
}

private fun rebuildTree(root: DefaultMutableTreeNode, model: DefaultTreeModel, fill: () -> Unit) {
    root.removeAllChildren()
    fill()
    model.reload()
}

private fun expandAll(tree: JTree) {
    repeat(tree.rowCount) { row -> tree.expandRow(row) }
}
