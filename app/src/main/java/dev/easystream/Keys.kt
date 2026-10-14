package dev.easystream

import android.view.KeyEvent

/**
 * Keys maps goa11y named keys to Android key codes, mirroring the
 * goa11y/xa11y keymap. Input injection itself is done by the IME path
 * (InputMethodService) or, when available, by the accessibility service's
 * gesture for text; named keys are delivered via the injected IME.
 */
object Keys {
    fun code(name: String): Int? {
        val n = name.lowercase()
        return named[n] ?: single(n)
    }

    private fun single(n: String): Int? {
        if (n.length == 1) {
            val c = n[0]
            return when {
                c in 'a'..'z' -> KeyEvent.KEYCODE_A + (c - 'a')
                c in 'A'..'Z' -> KeyEvent.KEYCODE_A + (c - 'A')
                c in '0'..'9' -> KeyEvent.KEYCODE_0 + (c - '0')
                else -> null
            }
        }
        return null
    }

    private val named = mapOf(
        "return" to KeyEvent.KEYCODE_ENTER,
        "enter" to KeyEvent.KEYCODE_ENTER,
        "escape" to KeyEvent.KEYCODE_ESCAPE,
        "esc" to KeyEvent.KEYCODE_ESCAPE,
        "tab" to KeyEvent.KEYCODE_TAB,
        "space" to KeyEvent.KEYCODE_SPACE,
        "backspace" to KeyEvent.KEYCODE_DEL,
        "delete" to KeyEvent.KEYCODE_FORWARD_DEL,
        "up" to KeyEvent.KEYCODE_DPAD_UP,
        "down" to KeyEvent.KEYCODE_DPAD_DOWN,
        "left" to KeyEvent.KEYCODE_DPAD_LEFT,
        "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "home" to KeyEvent.KEYCODE_MOVE_HOME,
        "end" to KeyEvent.KEYCODE_MOVE_END,
        "pageup" to KeyEvent.KEYCODE_PAGE_UP,
        "pagedown" to KeyEvent.KEYCODE_PAGE_DOWN,
        "back" to KeyEvent.KEYCODE_BACK,
        "menu" to KeyEvent.KEYCODE_MENU,
        "volume_up" to KeyEvent.KEYCODE_VOLUME_UP,
        "volume_down" to KeyEvent.KEYCODE_VOLUME_DOWN,
        "volume_mute" to KeyEvent.KEYCODE_VOLUME_MUTE,
        "power" to KeyEvent.KEYCODE_POWER,
        "camera" to KeyEvent.KEYCODE_CAMERA,
        "f1" to KeyEvent.KEYCODE_F1,
        "f2" to KeyEvent.KEYCODE_F2,
        "f3" to KeyEvent.KEYCODE_F3,
        "f4" to KeyEvent.KEYCODE_F4,
        "f5" to KeyEvent.KEYCODE_F5,
        "f6" to KeyEvent.KEYCODE_F6,
        "f7" to KeyEvent.KEYCODE_F7,
        "f8" to KeyEvent.KEYCODE_F8,
        "f9" to KeyEvent.KEYCODE_F9,
        "f10" to KeyEvent.KEYCODE_F10,
        "f11" to KeyEvent.KEYCODE_F11,
        "f12" to KeyEvent.KEYCODE_F12,
    )
}
