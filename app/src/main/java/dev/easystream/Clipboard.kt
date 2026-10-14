package dev.easystream

import android.content.ClipData
import android.content.ClipboardManager

/** Clipboard reads/writes the system clipboard via the control service. */
object Clipboard {
    private fun mgr(): ClipboardManager? =
        ControlService.instance?.getSystemService(ClipboardManager::class.java)

    fun get(): String {
        val m = mgr() ?: return ""
        return m.primaryClip?.getItemAt(0)?.coerceToText(ControlService.instance)?.toString() ?: ""
    }

    fun set(text: String) {
        val m = mgr() ?: return
        m.setPrimaryClip(ClipData.newPlainText("a11y", text))
    }
}
