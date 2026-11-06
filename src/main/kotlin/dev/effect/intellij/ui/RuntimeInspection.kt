package dev.effect.intellij.ui

import dev.effect.intellij.devtools.*
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreePath

/** One queued callback retains only the latest immutable state during a burst. */
internal class LatestRuntimeRefresh(
    private val schedule: (() -> Unit) -> Unit,
    private val render: (DevToolsRuntimeState) -> Unit,
) {
    private var pending: DevToolsRuntimeState? = null
    private var scheduled = false

    fun submit(state: DevToolsRuntimeState) {
        synchronized(this) {
            pending = state
            if (scheduled) return
            scheduled = true
        }
        schedule {
            val latest = synchronized(this) {
                val value = pending
                pending = null
                scheduled = false
                value
            }
            latest?.let(render)
        }
    }
}

/** Paths use protocol identity rather than snapshot equality or display row numbers. */
internal class RuntimeTreeInspection private constructor(
    private val selected: List<Any>?,
    private val expanded: Set<List<Any>>,
    private val initialized: Boolean,
) {
    fun restore(tree: JTree) {
        val root = tree.model.root as DefaultMutableTreeNode
        val nodes = root.depthFirstEnumeration().asSequence().filterIsInstance<DefaultMutableTreeNode>().toList()
        nodes.forEach { node ->
            val path = TreePath(node.path)
            if (identity(path) in expanded || !initialized) tree.expandPath(path)
        }
        val selection = nodes.firstOrNull { identity(TreePath(it.path)) == selected }
        if (selection != null) tree.selectionPath = TreePath(selection.path)
        else if (tree.rowCount > 0) tree.setSelectionRow(0)
    }

    companion object {
        fun capture(tree: JTree): RuntimeTreeInspection {
            val root = tree.model.root as DefaultMutableTreeNode
            val expanded = tree.getExpandedDescendants(TreePath(root.path))?.asSequence()
                ?.map(::identity)?.toSet().orEmpty()
            return RuntimeTreeInspection(tree.selectionPath?.let(::identity), expanded, root.childCount > 0)
        }

        private fun identity(path: TreePath): List<Any> = path.path.map { element ->
            when (val value = (element as DefaultMutableTreeNode).userObject) {
                is RuntimeSpanSnapshot -> value.traceId to value.spanId
                is RuntimeSpanEventSnapshot -> value.name to value.startTime
                is RuntimeMetricSnapshot -> Triple(value.name, value.kind, value.tags)
                is RuntimeDetailEntry -> value.key
                else -> value.toString()
            }
        }
    }
}
