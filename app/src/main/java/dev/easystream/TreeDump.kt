package dev.easystream

import android.view.accessibility.AccessibilityNodeInfo

/**
 * TreeDump renders an indented accessibility tree with box-drawing connectors,
 * matching xa11y's `tree` output.
 */
object TreeDump {
    fun render(root: AccessibilityNodeInfo, depth: Int, prefix: String, out: MutableList<String>) {
        val name = Selector.text(root)
        val label = Roles.of(root) + if (name.isEmpty()) "" else " \"$name\""
        out.add(prefix + label)
        if (depth <= 0) return
        val kids = (0 until root.childCount).mapNotNull { root.getChild(it) }
        val childBase = when {
            prefix.endsWith("├── ") -> prefix.dropLast(4) + "│   "
            prefix.endsWith("└── ") -> prefix.dropLast(4) + "    "
            else -> prefix
        }
        kids.forEachIndexed { i, k ->
            val branch = if (i == kids.size - 1) "└── " else "├── "
            render(k, depth - 1, childBase + branch, out)
        }
    }
}
