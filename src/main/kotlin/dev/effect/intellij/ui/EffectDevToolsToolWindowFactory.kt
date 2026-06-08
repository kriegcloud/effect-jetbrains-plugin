package dev.effect.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.xdebugger.XDebuggerManager
import dev.effect.intellij.debug.DebugBridgeState
import dev.effect.intellij.debug.EffectDebugBridgeService
import dev.effect.intellij.debug.EffectDebugTreeEntry
import dev.effect.intellij.debug.EffectDebugTreeModels
import dev.effect.intellij.debug.EffectSourceLocation
import dev.effect.intellij.devtools.DevToolsRuntimeState
import dev.effect.intellij.devtools.EffectDevToolsService
import dev.effect.intellij.devtools.RuntimeClientSnapshot
import dev.effect.intellij.devtools.RuntimeDetailEntry
import dev.effect.intellij.devtools.RuntimeMetricSnapshot
import dev.effect.intellij.devtools.RuntimeSpanEventSnapshot
import dev.effect.intellij.devtools.RuntimeSpanSnapshot
import dev.effect.intellij.notifications.EffectNotificationService
import dev.effect.intellij.settings.EffectProjectSettingsConfigurable
import dev.effect.intellij.settings.EffectProjectSettingsService
import dev.effect.intellij.webview.EffectWebTracerSupport
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JToolBar
import javax.swing.JTree
import javax.swing.event.TreeSelectionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class EffectDevToolsToolWindowPanel(private val project: Project) : Disposable {
    private val devToolsService = project.getService(EffectDevToolsService::class.java)
    private val debugBridgeService = project.getService(EffectDebugBridgeService::class.java)
    private var browserTracerPanel: Disposable? = null
    @Volatile
    private var disposed = false

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
                    RefreshDebugSnapshotsAction(project),
                    TogglePauseOnDefectsAction(project),
                    InterruptCurrentFiberAction(project),
                ),
                true,
            ).component,
        )
        setContent(buildContent())
    }

    private fun buildContent(): JComponent {
        val clientsPanel = ClientsTabPanel(project, devToolsService)
        val metricsPanel = MetricsTabPanel(devToolsService)
        val tracerPanel = TracerTabPanel(project, devToolsService)
        val webTracerPanel = EffectWebTracerSupport.createPanelOrNull()
        browserTracerPanel = webTracerPanel
        val debugPanel = DebugTabGroup(project, debugBridgeService)

        devToolsService.addListener({ state ->
            onEdt {
                clientsPanel.refresh(state)
                metricsPanel.refresh(state)
                tracerPanel.refresh(state)
                webTracerPanel?.refresh(state)
            }
        }, this)
        debugBridgeService.addListener({ state ->
            onEdt {
                debugPanel.refresh(state)
            }
        }, this)

        val initialRuntimeState = devToolsService.currentState()
        clientsPanel.refresh(initialRuntimeState)
        metricsPanel.refresh(initialRuntimeState)
        tracerPanel.refresh(initialRuntimeState)
        webTracerPanel?.refresh(initialRuntimeState)
        debugPanel.refresh(debugBridgeService.currentState())

        return JTabbedPane().apply {
            addTab("Clients", clientsPanel.component)
            addTab("Metrics", metricsPanel.component)
            addTab("Tracer", tracerPanel.component)
            webTracerPanel?.let { addTab("Tracer Web", it.component) }
            addTab("Debug", debugPanel.component)
        }
    }

    private fun onEdt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed && !disposed) {
                block()
            }
        }
    }

    override fun dispose() {
        disposed = true
        browserTracerPanel?.dispose()
        browserTracerPanel = null
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
    private val project: Project,
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
    private val copyButton = JButton("Copy")
    private val revealButton = JButton("Reveal Source")

    val component: JComponent = JPanel(BorderLayout()).apply {
        add(statusLabel, BorderLayout.NORTH)
        add(
            JToolBar().apply {
                isFloatable = false
                add(copyButton)
                add(revealButton)
            },
            BorderLayout.SOUTH,
        )
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
            updateSelectionActions()
        })
        copyButton.addActionListener {
            runtimeSelectedText()?.let { CopyPasteManager.getInstance().setContents(StringSelection(it)) }
        }
        revealButton.addActionListener {
            runtimeSelectedLocation()?.let { revealSource(project, it) }
        }
        updateSelectionActions()
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
        updateSelectionActions()
    }

    private fun buildSpanNode(span: RuntimeSpanSnapshot): DefaultMutableTreeNode {
        val node = DefaultMutableTreeNode(span)
        span.events.forEach { event -> node.add(DefaultMutableTreeNode(event)) }
        span.children.forEach { child -> node.add(buildSpanNode(child)) }
        return node
    }

    private fun selectedUserObject(): Any? =
        (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject

    private fun updateSelectionActions() {
        detailArea.text = runtimeSelectedText() ?: "Select a span or span event to inspect its details."
        copyButton.isEnabled = runtimeSelectedText() != null
        revealButton.isEnabled = runtimeSelectedLocation() != null
    }

    private fun runtimeSelectedText(): String? =
        when (val userObject = selectedUserObject()) {
            is RuntimeSpanSnapshot -> userObject.formatSpanDetails()
            is RuntimeSpanEventSnapshot -> userObject.formatSpanEventDetails()
            is RuntimeDetailEntry -> "${userObject.key}: ${userObject.value}"
            else -> null
        }

    private fun runtimeSelectedLocation(): EffectSourceLocation? =
        when (val userObject = selectedUserObject()) {
            is RuntimeSpanSnapshot -> userObject.details.toSourceLocation()
            is RuntimeSpanEventSnapshot -> userObject.details.toSourceLocation()
            else -> null
        }
}

private class DebugTabGroup(
    private val project: Project,
    private val debugBridgeService: EffectDebugBridgeService,
) {
    private val contextPanel = DebugTreePanel(project, "Context")
    private val spanStackPanel = DebugTreePanel(
        project,
        "Span Stack",
        extraActionLabel = "Toggle Ignore",
        extraAction = {
            spanStackIgnoreEnabled = !spanStackIgnoreEnabled
            refresh(lastState)
        },
    )
    private val fibersPanel = DebugTreePanel(
        project,
        "Fibers",
        extraActionLabel = "Interrupt Selected",
        extraAction = { entry ->
            entry?.fiberId?.let { debugBridgeService.interruptFiber(project, it) }
        },
    )
    private val breakpointsPanel = DebugTreePanel(project, "Breakpoints")
    private var spanStackIgnoreEnabled = true
    private var lastState = debugBridgeService.currentState()

    val component: JComponent = JTabbedPane().apply {
        addTab("Context", contextPanel.component)
        addTab("Span Stack", spanStackPanel.component)
        addTab("Fibers", fibersPanel.component)
        addTab("Breakpoints", breakpointsPanel.component)
    }

    fun refresh(state: DebugBridgeState) {
        lastState = state
        val ignoreList = EffectProjectSettingsService.getInstance(project).currentSettings().spanStackIgnoreList
        contextPanel.refresh(state, EffectDebugTreeModels.contextEntries(state))
        spanStackPanel.refresh(
            state,
            EffectDebugTreeModels.spanStackEntries(
                state = state,
                ignoreList = ignoreList,
                ignoreEnabled = spanStackIgnoreEnabled,
            ),
            suffix = if (spanStackIgnoreEnabled) "ignore list enabled" else "ignore list disabled",
        )
        fibersPanel.refresh(state, EffectDebugTreeModels.fiberEntries(state))
        breakpointsPanel.refresh(state, EffectDebugTreeModels.breakpointEntries(state))
    }
}

private class DebugTreePanel(
    private val project: Project,
    private val title: String,
    extraActionLabel: String? = null,
    private val extraAction: (EffectDebugTreeEntry?) -> Unit = {},
) {
    private val header = JBLabel()
    private val treeRoot = DefaultMutableTreeNode(title)
    private val treeModel = DefaultTreeModel(treeRoot)
    private val tree = JTree(treeModel).apply {
        isRootVisible = false
        expandsSelectedPaths = true
    }
    private val detailArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }
    private val copyButton = JButton("Copy")
    private val revealButton = JButton("Reveal Source")
    private val extraButton = extraActionLabel?.let(::JButton)

    val component: JComponent = JPanel(BorderLayout()).apply {
        add(header, BorderLayout.NORTH)
        add(
            JToolBar().apply {
                isFloatable = false
                add(copyButton)
                add(revealButton)
                extraButton?.let(::add)
            },
            BorderLayout.SOUTH,
        )
        add(
            JBSplitter(false, 0.5f).apply {
                firstComponent = JBScrollPane(tree)
                secondComponent = JBScrollPane(detailArea)
            },
            BorderLayout.CENTER,
        )
    }

    init {
        tree.addTreeSelectionListener(TreeSelectionListener { updateSelectionActions() })
        copyButton.addActionListener {
            selectedEntry()?.let { entry ->
                CopyPasteManager.getInstance().setContents(StringSelection(entry.copyText))
            }
        }
        revealButton.addActionListener {
            selectedEntry()?.location?.let { revealSource(project, it) }
        }
        extraButton?.addActionListener {
            extraAction(selectedEntry())
        }
        updateSelectionActions()
    }

    fun refresh(state: DebugBridgeState, entries: List<EffectDebugTreeEntry>, suffix: String? = null) {
        val baseHeader = if (state.attachedSessionName == null) {
            "$title: no debug session attached"
        } else if (state.refreshInProgress) {
            "$title: refreshing ${state.attachedSessionName}"
        } else {
            "$title: attached to ${state.attachedSessionName} (${state.attachedSessionType ?: "unknown"})"
        }
        header.text = listOfNotNull(baseHeader, suffix).joinToString(" - ")
        rebuildTree(treeRoot, treeModel) {
            entries.forEach { treeRoot.add(it.toTreeNode()) }
        }
        expandAll(tree)
        if (tree.selectionPath == null && tree.rowCount > 0) {
            tree.setSelectionRow(0)
        }
        state.error?.let { detailArea.text = it }
        updateSelectionActions()
    }

    private fun selectedEntry(): EffectDebugTreeEntry? =
        (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? EffectDebugTreeEntry

    private fun updateSelectionActions() {
        val selected = selectedEntry()
        detailArea.text = selected?.detail ?: "Select an entry to inspect details."
        copyButton.isEnabled = selected != null
        revealButton.isEnabled = selected?.location != null
        extraButton?.isEnabled = selected != null
    }

    private fun EffectDebugTreeEntry.toTreeNode(): DefaultMutableTreeNode =
        DefaultMutableTreeNode(this).also { node ->
            children.forEach { node.add(it.toTreeNode()) }
        }

}

private class StartRuntimeAction(private val project: Project) : AnAction(
    "Start Runtime",
    "Start the local Effect Dev Tools runtime server",
    AllIcons.Actions.Execute,
) {
    override fun actionPerformed(event: AnActionEvent) {
        project.getService(EffectDevToolsService::class.java).startServer()
    }
}

private class StopRuntimeAction(private val project: Project) : AnAction(
    "Stop Runtime",
    "Stop the local Effect Dev Tools runtime server",
    AllIcons.Run.Stop,
) {
    override fun actionPerformed(event: AnActionEvent) {
        project.getService(EffectDevToolsService::class.java).stopServer()
    }
}

private class RestartRuntimeAction(private val project: Project) : AnAction(
    "Restart Runtime",
    "Restart the local Effect Dev Tools runtime server",
    AllIcons.Run.Restart,
) {
    override fun actionPerformed(event: AnActionEvent) {
        project.getService(EffectDevToolsService::class.java).restartServer()
    }
}

private class SelectActiveClientAction(private val project: Project) : AnAction(
    "Select Client",
    "Switch the active runtime client",
    AllIcons.Nodes.Target,
) {
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

private class OpenSettingsAction(private val project: Project) : AnAction(
    "Open Settings",
    "Open project-scoped Effect settings",
    AllIcons.General.Settings,
) {
    override fun actionPerformed(event: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, EffectProjectSettingsConfigurable::class.java)
    }
}

private class ResetMetricsAction(private val project: Project) : AnAction(
    "Reset Metrics",
    "Clear the current metrics snapshot for the active client",
    AllIcons.Actions.Refresh,
) {
    override fun actionPerformed(event: AnActionEvent) {
        project.getService(EffectDevToolsService::class.java).resetMetrics()
    }
}

private class ResetTracerAction(private val project: Project) : AnAction(
    "Reset Tracer",
    "Clear the current tracer snapshot for the active client",
    AllIcons.General.Reset,
) {
    override fun actionPerformed(event: AnActionEvent) {
        project.getService(EffectDevToolsService::class.java).resetTracer()
    }
}

private class AttachDebugAction(private val project: Project) : AnAction(
    "Attach Debug",
    "Attach the current JetBrains debug session to Effect Dev Tools",
    AllIcons.Debugger.AttachToProcess,
) {
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

private class RefreshDebugSnapshotsAction(private val project: Project) : AnAction(
    "Refresh Debug",
    "Refresh Effect debug snapshots from the attached session",
    AllIcons.Actions.Refresh,
) {
    override fun actionPerformed(event: AnActionEvent) {
        project.getService(EffectDebugBridgeService::class.java).refreshSnapshots(project)
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = project.getService(EffectDebugBridgeService::class.java)
            .currentState()
            .attachedSessionName != null
    }
}

private class TogglePauseOnDefectsAction(private val project: Project) : AnAction(
    "Pause on Defects",
    "Toggle Effect pause-on-defect instrumentation",
    AllIcons.Actions.Execute,
) {
    override fun actionPerformed(event: AnActionEvent) {
        project.getService(EffectDebugBridgeService::class.java).togglePauseOnDefects(project)
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = project.getService(EffectDebugBridgeService::class.java)
            .currentState()
            .attachedSessionName != null
    }
}

private class InterruptCurrentFiberAction(private val project: Project) : AnAction(
    "Interrupt Fiber",
    "Interrupt the current Effect fiber in the attached session",
    AllIcons.Run.Stop,
) {
    override fun actionPerformed(event: AnActionEvent) {
        project.getService(EffectDebugBridgeService::class.java).interruptCurrentFiber(project)
    }

    override fun update(event: AnActionEvent) {
        val state = project.getService(EffectDebugBridgeService::class.java).currentState()
        event.presentation.isEnabled = state.snapshot?.fibers?.any { it.isCurrent } == true
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

private fun List<RuntimeDetailEntry>.toSourceLocation(): EffectSourceLocation? {
    val byKey = associateBy { it.key.lowercase() }
    val path = listOf("path", "file", "source.path", "source.file", "attr.path", "attr.file")
        .firstNotNullOfOrNull { key -> byKey[key]?.value?.takeIf(String::isNotBlank) }
        ?: return null
    val line = listOf("line", "source.line", "attr.line")
        .firstNotNullOfOrNull { key -> byKey[key]?.value?.toIntOrNull() }
        ?: 0
    val column = listOf("column", "col", "source.column", "source.col", "attr.column", "attr.col")
        .firstNotNullOfOrNull { key -> byKey[key]?.value?.toIntOrNull() }
        ?: 0
    return EffectSourceLocation(path, line, column)
}

private fun revealSource(project: Project, location: EffectSourceLocation) {
    val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(location.path)
    if (file == null) {
        ApplicationManager.getApplication().getService(EffectNotificationService::class.java)
            .warning(project, "Effect source unavailable", "Could not find ${location.path}.")
        return
    }
    OpenFileDescriptor(project, file, location.line.coerceAtLeast(0), location.column.coerceAtLeast(0)).navigate(true)
}

private fun rebuildTree(root: DefaultMutableTreeNode, model: DefaultTreeModel, fill: () -> Unit) {
    root.removeAllChildren()
    fill()
    model.reload()
}

private fun expandAll(tree: JTree) {
    repeat(tree.rowCount) { row -> tree.expandRow(row) }
}
