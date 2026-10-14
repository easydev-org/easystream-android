package dev.easystream

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Selector is the goa11y CSS-ish matcher over AccessibilityNodeInfo:
 * comma-separated clauses, each a `>` chain of `role`/`*` segments with
 * optional `[attr op "v"]` filters. On Android "role" is matched against the
 * derived class-name role (e.g. button, edit_text).
 */
class Selector(text: String) {
    private data class Seg(
        val role: String?,
        val attrs: List<Triple<String, String, String>>,
    )

    private val group: List<List<Seg>> = text.split(',').map { clause ->
        clause.split('>').map { parseSimple(it.trim()) }
    }

    private fun parseSimple(s: String): Seg {
        var head = s
        val attrs = mutableListOf<Triple<String, String, String>>()
        while (true) {
            val lb = head.indexOf('[')
            val rb = head.indexOf(']')
            if (lb < 0 || rb <= lb) break
            parseAttr(head.substring(lb + 1, rb))?.let { attrs.add(it) }
            head = head.substring(rb + 1)
        }
        val r = head.trim()
        val role = if (r.isEmpty() || r == "*") null else r
        return Seg(role, attrs)
    }

    private fun parseAttr(s: String): Triple<String, String, String>? {
        for (op in listOf("*=", "^=", "$=", "=")) {
            val i = s.indexOf(op)
            if (i < 0) continue
            val attr = s.substring(0, i).trim()
            val v = s.substring(i + op.length).trim().trim('"', '\'')
            return Triple(attr, op, v)
        }
        return null
    }

    fun matches(n: AccessibilityNodeInfo): Boolean =
        group.any { chain -> matchChain(chain, n) }

    private fun matchChain(segs: List<Seg>, n: AccessibilityNodeInfo): Boolean {
        if (segs.isEmpty()) return false
        if (!matchSimple(segs.last(), n)) return false
        var cur = n.parent
        var i = segs.size - 2
        while (i >= 0 && cur != null) {
            if (matchSimple(segs[i], cur)) i--
            cur = cur.parent
        }
        return i < 0
    }

    private fun matchSimple(seg: Seg, n: AccessibilityNodeInfo): Boolean {
        if (seg.role != null && Roles.of(n) != seg.role) return false
        for ((attr, op, value) in seg.attrs) {
            val v = when (attr) {
                "title", "name" -> text(n)
                "value" -> valueOf(n)
                "desc", "description" -> n.contentDescription?.toString() ?: ""
                "id", "resource-id" -> n.viewIdResourceName ?: ""
                else -> ""
            }
            val ok = when (op) {
                "=" -> v == value
                "*=" -> v.contains(value)
                "^=" -> v.startsWith(value)
                "$=" -> v.endsWith(value)
                else -> false
            }
            if (!ok) return false
        }
        return true
    }

    companion object {
        /** text prefers text, then content description (what a user "sees"). */
        fun text(n: AccessibilityNodeInfo): String =
            n.text?.toString()?.takeIf { it.isNotEmpty() }
                ?: n.contentDescription?.toString() ?: ""

        /** valueOf reads the value text (EditText content). */
        fun valueOf(n: AccessibilityNodeInfo): String =
            if (n.isEditable) n.text?.toString() ?: "" else n.text?.toString() ?: ""

        /** boundsOf returns the screen rect. */
        fun boundsOf(n: AccessibilityNodeInfo): Rect {
            val r = Rect()
            n.getBoundsInScreen(r)
            return r
        }
    }
}
