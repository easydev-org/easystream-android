package dev.easystream

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Commands implements the easystream command surface (SPEC.md) over the live
 * A11yService, returning the same text formats as the goa11y/xa11y CLI.
 */
object Commands {

    fun dispatch(path: String, p: Map<String, String>): String {
        val svc = A11yService.instance
        return when (path) {
            "/health" -> "ok"
            "/apps" -> apps(svc)
            "/tree" -> tree(svc, p)
            "/find" -> find(svc, p)
            "/click" -> click(svc, p)
            "/swipe" -> swipe(svc, p)
            "/longpress" -> longPress(svc, p)
            "/key" -> key(svc, p)
            "/settext" -> setText(svc, p)
            "/screenshot" -> Screen.screenshot(resolveOut(p["out"], "shot.png"))
            "/clipboard-get" -> Clipboard.get()
            "/clipboard-set" -> { Clipboard.set(p["text"] ?: ""); "ok" }
            "/encode" -> Screen.encode(
                resolveOut(p["out"], "stream.mp4"),
                p["frames"]?.toIntOrNull() ?: 30,
                p["fps"]?.toIntOrNull() ?: 10,
                p["scale"]?.toIntOrNull() ?: 1,
            )
            else -> "error: unknown path $path"
        }
    }

    /**
     * resolveOut maps a requested output path to a location the app can
     * actually write. App-scoped storage forbids /sdcard, so a bare filename
     * lands in the app's external files dir (world-readable via adb).
     */
    private fun resolveOut(requested: String?, fallback: String): String {
        val svc = ControlService.instance
        val dir = svc?.getExternalFilesDir(null) ?: svc?.filesDir
        val name = requested?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: fallback
        return java.io.File(dir, name).absolutePath
    }

    private fun apps(svc: A11yService?): String {
        // Android has no "app list" per se; the current window's package is the
        // active app. Report that plus the window title.
        if (svc == null) return "error: accessibility service not connected"
        val root = svc.roots(all = false).firstOrNull()
            ?: return "No applications found."
        val pkg = root.packageName?.toString() ?: "unknown"
        return "0\t$pkg\tfocused"
    }

    private fun tree(svc: A11yService?, p: Map<String, String>): String {
        if (svc == null) return "error: accessibility service not connected"
        val depth = p["depth"]?.toIntOrNull() ?: 999
        val roots = svc.roots(all = p["all"] == "1")
        if (roots.isEmpty()) return "No windows."
        val out = mutableListOf<String>()
        roots.forEach { TreeDump.render(it, depth, "", out) }
        return out.joinToString("\n")
    }

    private fun find(svc: A11yService?, p: Map<String, String>): String {
        if (svc == null) return "error: accessibility service not connected"
        val sel = Selector(p["sel"] ?: "*")
        val mode = p["o"] ?: "pretty"
        val hits = mutableListOf<AccessibilityNodeInfo>()
        for (root in svc.roots(all = true)) {
            svc.walk(root, 999).forEach { if (sel.matches(it)) hits.add(it) }
        }
        return hits.joinToString("\n") { n ->
            when (mode) {
                "bounds" -> {
                    val r = Selector.boundsOf(n)
                    "${r.left},${r.top},${r.width()},${r.height()}"
                }
                "center" -> {
                    val r = Selector.boundsOf(n)
                    "${r.exactCenterX().toInt()},${r.exactCenterY().toInt()}"
                }
                else -> "${Roles.of(n)} \"${Selector.text(n)}\""
            }
        }
    }

    private fun click(svc: A11yService?, p: Map<String, String>): String {
        if (svc == null) return "error: accessibility service not connected"
        val sel = p["sel"]
        if (sel != null) {
            return if (svc.clickNode(Selector(sel))) "ok" else "error: no match or not clickable"
        }
        val x = p["x"]?.toFloatOrNull() ?: return "error: need x,y or sel"
        val y = p["y"]?.toFloatOrNull() ?: return "error: need x,y or sel"
        return if (svc.tapAt(x, y)) "ok" else "error: gesture failed"
    }

    private fun swipe(svc: A11yService?, p: Map<String, String>): String {
        if (svc == null) return "error: accessibility service not connected"
        val x1 = p["x1"]?.toFloatOrNull() ?: return "error: need x1,y1,x2,y2"
        val y1 = p["y1"]?.toFloatOrNull() ?: return "error: need x1,y1,x2,y2"
        val x2 = p["x2"]?.toFloatOrNull() ?: return "error: need x1,y1,x2,y2"
        val y2 = p["y2"]?.toFloatOrNull() ?: return "error: need x1,y1,x2,y2"
        val ms = p["ms"]?.toLongOrNull() ?: 300L
        return if (svc.swipeAt(x1, y1, x2, y2, ms)) "ok" else "error: gesture failed"
    }

    private fun longPress(svc: A11yService?, p: Map<String, String>): String {
        if (svc == null) return "error: accessibility service not connected"
        val x = p["x"]?.toFloatOrNull() ?: return "error: need x,y"
        val y = p["y"]?.toFloatOrNull() ?: return "error: need x,y"
        val ms = p["ms"]?.toLongOrNull() ?: 600L
        return if (svc.longPress(x, y, ms)) "ok" else "error: gesture failed"
    }

    private fun key(svc: A11yService?, p: Map<String, String>): String {
        if (svc == null) return "error: accessibility service not connected"
        val name = p["name"] ?: return "error: need name"
        val global = when (name.lowercase()) {
            "back" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" ->
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> -1
        }
        if (global >= 0) return if (svc.systemKey(global)) "ok" else "error: global action failed"
        // Non-global keys need an IME; report the resolved code for the worker.
        val code = Keys.code(name) ?: return "error: unknown key $name"
        return "code=$code (inject via IME)"
    }

    private fun setText(svc: A11yService?, p: Map<String, String>): String {
        if (svc == null) return "error: accessibility service not connected"
        val sel = p["sel"] ?: return "error: need sel"
        val text = p["text"] ?: return "error: need text"
        return if (svc.setText(Selector(sel), text)) "ok" else "error: no match or not editable"
    }
}
