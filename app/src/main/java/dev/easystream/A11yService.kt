package dev.easystream

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A11yService is the accessibility backend: it exposes the live
 * AccessibilityNodeInfo tree and performs actions/gestures. Only a
 * manifest-declared AccessibilityService can do this — which is exactly why
 * Android needs a native implementation.
 */
class A11yService : AccessibilityService() {

    companion object {
        @Volatile var instance: A11yService? = null
            private set
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** The root of the active window, or all windows when [all] is true. */
    fun roots(all: Boolean): List<AccessibilityNodeInfo> {
        if (all) return windows.mapNotNull { it.root }
        return listOfNotNull(rootInActiveWindow)
    }

    // ── gestures ──────────────────────────────────────────────────────────

    /** tapAt performs a single tap at screen (x,y). */
    fun tapAt(x: Float, y: Float, durationMs: Long = 40): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return gesture(path, 0, durationMs)
    }

    /** swipeAt performs a drag from (x1,y1) to (x2,y2). */
    fun swipeAt(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return gesture(path, 0, durationMs)
    }

    /** longPress holds at (x,y) for durationMs. */
    fun longPress(x: Float, y: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return gesture(path, 0, durationMs)
    }

    // ── native touch (finger): down -> move* -> up as ONE gesture ──────────
    // The viewer's finger drives the real touchscreen (dispatchGesture), while
    // its mouse drives the pointer (adb `input mouse`) — both coexist.
    private var touchPath: Path? = null
    private var touchStartMs = 0L

    fun touchDown(x: Float, y: Float): Boolean {
        touchPath = Path().apply { moveTo(x, y) }
        touchStartMs = SystemClock.uptimeMillis()
        return true
    }

    fun touchMove(x: Float, y: Float): Boolean {
        val p = touchPath ?: return false
        p.lineTo(x, y)
        return true
    }

    fun touchUp(): Boolean {
        val p = touchPath ?: return false
        touchPath = null
        val dur = (SystemClock.uptimeMillis() - touchStartMs).coerceIn(40L, 60_000L)
        val stroke = GestureDescription.StrokeDescription(p, 0, dur)
        val desc = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        var ok = false
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(g: GestureDescription?) { ok = false; latch.countDown() }
        }
        if (!dispatchGesture(desc, cb, null)) return false
        latch.await(2, TimeUnit.SECONDS)
        return ok
    }

    private fun gesture(path: Path, startMs: Long, durationMs: Long): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, startMs, durationMs)
        val desc = GestureDescription.Builder().addStroke(stroke).build()
        val latch = CountDownLatch(1)
        var ok = false
        val dispatched = dispatchGesture(desc, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(g: GestureDescription?) { ok = false; latch.countDown() }
        }, null)
        if (!dispatched) return false
        latch.await(3, TimeUnit.SECONDS)
        return ok
    }

    // ── node actions ──────────────────────────────────────────────────────

    /** clickNode clicks the first node matching [sel] using ACTION_CLICK. */
    fun clickNode(sel: Selector): Boolean {
        val n = first(sel) ?: return false
        return performClick(n)
    }

    private fun performClick(n: AccessibilityNodeInfo): Boolean {
        // Walk up to the nearest clickable ancestor (labels often sit under one).
        var cur: AccessibilityNodeInfo? = n
        while (cur != null) {
            if (cur.isClickable) {
                return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            cur = cur.parent
        }
        // Fall back to a gesture at the node's centre.
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.width() > 0 && r.height() > 0) {
            return tapAt(r.exactCenterX(), r.exactCenterY())
        }
        return false
    }

    /** setText sets text on the first editable node matching [sel]. */
    fun setText(sel: Selector, text: String): Boolean {
        val n = first(sel) ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** focusNode focuses the first node matching [sel]. */
    fun focusNode(sel: Selector): Boolean {
        val n = first(sel) ?: return false
        return n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    /** first does a depth-first search for the first node matching [sel]. */
    fun first(sel: Selector): AccessibilityNodeInfo? {
        for (root in roots(all = true)) {
            val hit = search(root, sel)
            if (hit != null) return hit
        }
        return null
    }

    private fun search(root: AccessibilityNodeInfo, sel: Selector): AccessibilityNodeInfo? {
        if (sel.matches(root)) return root
        for (i in 0 until root.childCount) {
            val c = root.getChild(i) ?: continue
            val hit = search(c, sel)
            if (hit != null) return hit
        }
        return null
    }

    /** walk visits [root] and all descendants (bounded by [depth]). */
    fun walk(root: AccessibilityNodeInfo, depth: Int): Sequence<AccessibilityNodeInfo> = sequence {
        yield(root)
        if (depth <= 0) return@sequence
        for (i in 0 until root.childCount) {
            val c = root.getChild(i) ?: continue
            yieldAll(walk(c, depth - 1))
        }
    }

    /** systemKey triggers a global action (BACK/HOME/RECENTS/NOTIFICATIONS). */
    fun systemKey(action: Int): Boolean = performGlobalAction(action)
}
