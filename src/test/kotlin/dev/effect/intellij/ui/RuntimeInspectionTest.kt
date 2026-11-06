package dev.effect.intellij.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.effect.intellij.devtools.*
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

    private fun client(id: String) = RuntimeClientSnapshot(id, id, "local", Instant.EPOCH, Instant.EPOCH)
    private fun span(id: String) = RuntimeSpanSnapshot(id, "trace", id, "Started", true, emptyList(), emptyList(), emptyList())
    private fun metric(id: String) = RuntimeMetricSnapshot(id, "Counter", "1", null, emptyList(), emptyList())
}
