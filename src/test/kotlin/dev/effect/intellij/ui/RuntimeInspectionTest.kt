package dev.effect.intellij.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.effect.intellij.devtools.*
import dev.effect.intellij.debug.*
import javax.swing.tree.DefaultTreeModel
import java.time.Instant
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

class RuntimeInspectionTest : BasePlatformTestCase() {
    fun testRuntimeRefreshDoesNotPublishSelectionChanges() {
        val selected = mutableListOf<String?>()
        val panel = ClientsTabPanel(project, project.getService(EffectDevToolsService::class.java)) { selected += it }
        val clients = listOf(client("one"), client("two"))
        val state = DevToolsRuntimeState(running = true, clients = clients, activeClientId = "two")
        panel.refresh(state)
        panel.refresh(state)
        panel.refresh(state)
        assertEmpty(selected)
        assertEquals("two", panel.clientsList.selectedValue.id)
        panel.clientsList.selectedIndex = 0
        assertEquals(listOf("one"), selected)
    }

    fun testClientSelectionReturnsWhileIngestionHoldsServiceLock() {
        val service = project.getService(EffectDevToolsService::class.java)
        val panel = ClientsTabPanel(project, service)
        panel.refresh(DevToolsRuntimeState(clients = listOf(client("one"), client("two")), activeClientId = "one"))
        val lock = EffectDevToolsService::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(service)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.CountDownLatch(1)
        val holder = Thread {
            synchronized(lock) {
                entered.countDown()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            released.countDown()
        }
        holder.start()
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            panel.clientsList.selectedIndex = 1
            assertEquals(1L, released.count) // The EDT returned before the ingestion lock was released.
        } finally { release.countDown() }
        holder.join(5000)
        assertFalse(holder.isAlive)
    }

    fun testBurstKeepsOnePendingRenderAndLatestState() {
        val queue = mutableListOf<() -> Unit>()
        val rendered = mutableListOf<DevToolsRuntimeState>()
        val refresh = LatestRuntimeRefresh({ queue += it }, { rendered += it })
        repeat(10_000) { refresh.submit(DevToolsRuntimeState(port = it)) }
        assertEquals(1, queue.size)
        queue.removeAt(0)()
        assertEquals(listOf(9999), rendered.map { it.port })
    }

    fun testRuntimeRefreshPreservesSelectedSpanAndExpansion() {
        val panel = TracerTabPanel(project, project.getService(EffectDevToolsService::class.java))
        val first = span("first")
        val second = span("second").copy(children = listOf(span("child")))
        val client = client("one").copy(rootSpans = listOf(first, second))
        val state = DevToolsRuntimeState(running = true, clients = listOf(client), activeClientId = "one")
        panel.refresh(state)
        val root = panel.tree.model.root as DefaultMutableTreeNode
        val path = TreePath((root.getChildAt(1) as DefaultMutableTreeNode).path)
        panel.tree.selectionPath = path
        panel.tree.collapsePath(path)
        panel.refresh(state.copy(clients = listOf(client.copy(metrics = listOf(metric("poll"))))))
        assertSame(root.getChildAt(1), panel.tree.lastSelectedPathComponent)
        panel.refresh(state.copy(clients = listOf(client.copy(rootSpans = listOf(first.copy(status = "Ended"), second)))))
        val selected = panel.tree.lastSelectedPathComponent as DefaultMutableTreeNode
        assertEquals("second", (selected.userObject as RuntimeSpanSnapshot).spanId)
        assertFalse(panel.tree.isExpanded(TreePath(selected.path)))
    }

    fun testMetricSelectionSurvivesChangedValues() {
        val panel = MetricsTabPanel(project.getService(EffectDevToolsService::class.java))
        val client = client("one").copy(metrics = listOf(metric("a"), metric("b")))
        val state = DevToolsRuntimeState(running = true, clients = listOf(client), activeClientId = "one")
        panel.refresh(state)
        panel.tree.setSelectionRow(1)
        panel.refresh(state.copy(clients = listOf(client.copy(metrics = listOf(metric("a"), metric("b").copy(summary = "2"))))))
        val selected = panel.tree.lastSelectedPathComponent as DefaultMutableTreeNode
        assertEquals("b", (selected.userObject as RuntimeMetricSnapshot).name)
    }

    fun testDebugSelectionsSurviveEquivalentRefreshAndIgnoreToggle() {
        val group = DebugTabGroup(project, project.getService(EffectDebugBridgeService::class.java))
        val snapshot = EffectDebugSnapshot(
            context = listOf(EffectDebugContextEntry("Service", EffectDebugValueSnapshot("service", children = listOf(
                EffectDebugValueSnapshot("nested", children = listOf(EffectDebugValueSnapshot("count", summary = "1"))),
                EffectDebugValueSnapshot("other", children = listOf(EffectDebugValueSnapshot("child"))),
            )))),
            fibers = listOf(EffectDebugFiberEntry("42", isInterruptible = true)),
        )
        val state = DebugBridgeState(attachedSessionName = "test", snapshot = snapshot)
        group.refresh(state)
        val context = group.contextPanel.tree
        val root = context.model.root as DefaultMutableTreeNode
        val service = root.getChildAt(0) as DefaultMutableTreeNode
        val nested = service.getChildAt(0) as DefaultMutableTreeNode
        val other = service.getChildAt(1) as DefaultMutableTreeNode
        context.expandPath(TreePath(nested.path))
        context.collapsePath(TreePath(other.path))
        context.selectionPath = TreePath((nested.getChildAt(0) as DefaultMutableTreeNode).path)
        group.fibersPanel.tree.setSelectionRow(0)
        group.refresh(state.copy(refreshInProgress = true, snapshot = null))
        group.refresh(state.copy(snapshot = snapshot.copy(
            fibers = listOf(EffectDebugFiberEntry("42", isCurrent = true, isInterruptible = true)),
        )))
        val selected = context.lastSelectedPathComponent as DefaultMutableTreeNode
        assertEquals("count", (selected.userObject as EffectDebugTreeEntry).inspectionKey)
        assertEquals("42", ((group.fibersPanel.tree.lastSelectedPathComponent as DefaultMutableTreeNode).userObject as EffectDebugTreeEntry).inspectionKey)
        assertTrue(context.isExpanded(TreePath((selected.parent as DefaultMutableTreeNode).path)))
        val rebuiltService = (root.getChildAt(0) as DefaultMutableTreeNode)
        assertFalse(context.isExpanded(TreePath((rebuiltService.getChildAt(1) as DefaultMutableTreeNode).path)))
        val fiberNode = group.fibersPanel.tree.lastSelectedPathComponent
        group.toggleIgnore()
        assertSame(selected, context.lastSelectedPathComponent)
        assertSame(fiberNode, group.fibersPanel.tree.lastSelectedPathComponent)
    }

    fun testRetainedLimitTracerUpdateReusesUnchangedBranches() {
        val events = (0 until 100).map { RuntimeSpanEventSnapshot("event$it", "$it", emptyList()) }
        val spans = (0 until 2000).map { span("s$it").copy(events = events) }
        val root = DefaultMutableTreeNode("root")
        val model = DefaultTreeModel(root)
        val fullStart = System.nanoTime()
        reconcileSpanNodes(root, spans, model)
        val fullNanos = System.nanoTime() - fullStart
        val untouched = root.getChildAt(1999) as DefaultMutableTreeNode
        val untouchedEvent = untouched.getChildAt(99)
        // This unchanged branch must never be enumerated on the next update.
        val guard = object : DefaultMutableTreeNode(spans[1998]) {
            override fun children(): java.util.Enumeration<javax.swing.tree.TreeNode> {
                error("Visited an unchanged collapsed branch")
            }
        }
        root.remove(1998)
        root.insert(guard, 1998)
        val updated = spans.toMutableList()
        updated[0] = spans[0].copy(events = events.drop(1) + RuntimeSpanEventSnapshot("new", "100", emptyList()))
        val updateStart = System.nanoTime()
        reconcileSpanNodes(root, updated, model)
        val updateNanos = System.nanoTime() - updateStart
        assertSame(untouched, root.getChildAt(1999))
        assertSame(untouchedEvent, untouched.getChildAt(99))
        assertSame(guard, root.getChildAt(1998))
        println("Retained-limit EDT tree construction=${fullNanos / 1_000_000.0}ms; one-event update=${updateNanos / 1_000_000.0}ms")
    }

    private fun client(id: String) = RuntimeClientSnapshot(id, id, "local", Instant.EPOCH, Instant.EPOCH)
    private fun span(id: String) = RuntimeSpanSnapshot(id, "trace", id, "Started", true, emptyList(), emptyList(), emptyList())
    private fun metric(id: String) = RuntimeMetricSnapshot(id, "Counter", "1", null, emptyList(), emptyList())
}
