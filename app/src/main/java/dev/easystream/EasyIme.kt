package dev.easystream

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.widget.TextView

/**
 * EasyIme is a HEADLESS input method. The est daemon cannot inject keys any
 * other way: `input keyevent` needs android.permission.INJECT_EVENTS (shell /
 * system only), so an ordinary app is denied
 * ("Permission Denial: injectInputEvent() ... requires INJECT_EVENTS").
 *
 * An InputMethodService, however, IS allowed to send key events to the focused
 * editor through its InputConnection — no special permission. The daemon binds
 * to this service and routes every KEY/TEXT frame here, which makes Backspace,
 * the arrow keys, Enter, Tab, F-keys and normal text all work.
 */
class EasyIme : InputMethodService() {

    companion object {
        @Volatile var instance: EasyIme? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** A zero-height view: the IME must exist but never cover the screen. */
    override fun onCreateInputView(): View {
        val v = TextView(this)
        v.height = 0
        return v
    }

    /** sendKey injects one key down+up into the focused editor. */
    fun sendKey(keycode: Int): Boolean {
        val ic = currentInputConnection ?: return false
        val now = android.os.SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keycode, 0)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keycode, 0)
        ic.sendKeyEvent(down)
        ic.sendKeyEvent(up)
        return true
    }

    /** commitText types arbitrary text (unicode-safe, unlike keycodes). */
    fun commitText(text: String): Boolean {
        val ic = currentInputConnection ?: return false
        ic.commitText(text, 1)
        return true
    }
}
