package dev.easystream

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Roles maps an AccessibilityNodeInfo's class name to the goa11y/xa11y role
 * vocabulary (lower_snake_case) so `tree` / `find` output matches the other
 * platforms.
 */
object Roles {
    fun of(n: AccessibilityNodeInfo): String {
        val cls = n.className?.toString() ?: return "unknown"
        val simple = cls.substringAfterLast('.')
        return map[simple] ?: camelToSnake(simple)
    }

    private val map = mapOf(
        "Button" to "button",
        "ImageButton" to "button",
        "TextView" to "text",
        "EditText" to "edit_text",
        "AutoCompleteTextView" to "edit_text",
        "ImageView" to "image",
        "CheckBox" to "check_box",
        "RadioButton" to "radio_button",
        "Switch" to "switch",
        "ToggleButton" to "toggle_button",
        "SeekBar" to "slider",
        "ProgressBar" to "progress_bar",
        "RecyclerView" to "list",
        "ListView" to "list",
        "GridView" to "grid",
        "ScrollView" to "scroll_view",
        "HorizontalScrollView" to "scroll_view",
        "ViewPager" to "pager",
        "WebView" to "web_view",
        "FrameLayout" to "frame",
        "LinearLayout" to "linear_layout",
        "RelativeLayout" to "relative_layout",
        "ConstraintLayout" to "constraint_layout",
        "View" to "view",
        "ViewGroup" to "view_group",
        "SurfaceView" to "surface",
        "TextureView" to "surface",
    )

    private fun camelToSnake(s: String): String {
        val sb = StringBuilder()
        s.forEachIndexed { i, c ->
            if (c.isUpperCase()) {
                if (i > 0) sb.append('_')
                sb.append(c.lowercaseChar())
            } else sb.append(c)
        }
        return sb.toString()
    }
}
