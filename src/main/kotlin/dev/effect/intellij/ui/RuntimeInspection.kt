package dev.effect.intellij.ui

import dev.effect.intellij.devtools.*
import dev.effect.intellij.debug.EffectDebugTreeEntry
import javax.swing.tree.DefaultTreeModel
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
        fun resolve(keys: List<Any>): TreePath? {
            var node = root
            if (keys.firstOrNull() != nodeIdentity(root)) return null
            for (key in keys.drop(1)) {
                node = node.children().asSequence().filterIsInstance<DefaultMutableTreeNode>()
                    .firstOrNull { nodeIdentity(it) == key } ?: return null
            }
            return TreePath(node.path)
        }
        if (!initialized) {
            tree.expandPath(TreePath(root.path))
            // Initial display opens only the top level, independent of retained tree size.
        } else {
            expanded.sortedBy { it.size }.forEach { keys -> resolve(keys)?.let(tree::expandPath) }
        }
        val selection = selected?.let(::resolve)
        if (selection != null) tree.selectionPath = selection
        else if (tree.rowCount > 0) tree.setSelectionRow(0)
    }

    companion object {
        fun capture(tree: JTree): RuntimeTreeInspection {
            val root = tree.model.root as DefaultMutableTreeNode
            val expanded = tree.getExpandedDescendants(TreePath(root.path))?.asSequence()
                ?.map(::identity)?.toSet().orEmpty()
            return RuntimeTreeInspection(tree.selectionPath?.let(::identity), expanded, root.childCount > 0)
        }

        private fun identity(path: TreePath): List<Any> = path.path.map { nodeIdentity(it as DefaultMutableTreeNode) }

        private fun nodeIdentity(node: DefaultMutableTreeNode): Any = when (val value = node.userObject) {
            is RuntimeSpanSnapshot -> value.traceId to value.spanId
            is RuntimeSpanEventSnapshot -> value.name to value.startTime
            is RuntimeMetricSnapshot -> Triple(value.name, value.kind, value.tags)
            is RuntimeDetailEntry -> value.key
            is EffectDebugTreeEntry -> value.inspectionKey
            else -> value.toString()
        }
    }
}

/** Reuse unchanged branches and notify Swing only for changed nodes or child lists. */
internal fun reconcileSpanNodes(parent: DefaultMutableTreeNode, spans: List<RuntimeSpanSnapshot>, model: DefaultTreeModel) {
    fun reconcile(node: DefaultMutableTreeNode, values: List<Any>) {
        val old = node.children().asSequence().filterIsInstance<DefaultMutableTreeNode>().toList()
        fun key(value: Any): Any = when (value) {
            is RuntimeSpanSnapshot -> value.traceId to value.spanId
            is RuntimeSpanEventSnapshot -> value.name to value.startTime
            else -> value
        }
        // Queues also preserve duplicate event identities in protocol order.
        val available = old.groupBy { key(it.userObject) }.mapValues { java.util.ArrayDeque(it.value) }
        val children = values.map { value ->
            val child = available[key(value)]?.pollFirst() ?: DefaultMutableTreeNode()
            if (child.userObject != value) {
                child.userObject = value
                if (value is RuntimeSpanSnapshot) reconcile(child, value.events + value.children)
                model.nodeChanged(child)
            }
            child
        }
        if (old != children) {
            node.removeAllChildren()
            children.forEach(node::add)
            model.nodeStructureChanged(node)
        }
    }
    reconcile(parent, spans)
}
