package dev.easystream

/**
 * Access control: two modes, chosen by the token the client presents.
 *
 *  - VIEW (the DEFAULT): read-only. Video, a11y reads, screenshots and
 *    clipboard READS work with NO token. Every frame that would CHANGE the
 *    guest (input, a11y action, clipboard write) is dropped.
 *  - CONTROL: everything VIEW can do PLUS input, a11y actions and clipboard
 *    writes. Requires the control token.
 *
 * Tokens come from the environment:
 *   EAZYSTREAM_CONTROL_TOKEN  grants CONTROL
 *   EAZYSTREAM_VIEW_TOKEN     (optional) an explicit read-only token
 *
 * Behaviour:
 *   * no token at all          -> VIEW (read-only), always allowed
 *   * the control token        -> CONTROL
 *   * anything else            -> VIEW
 *   * NEITHER token configured -> CONTROL for everyone (auth off)
 */
object Auth {
    enum class Mode(val v: Int) { VIEW(0), CONTROL(1) }

    // The token is injected at runtime (MainActivity reads it from the launch
    // Intent) because an APK's System.getenv never sees `am start`'s env, and
    // `am start --es controlToken` is how the sandbox entrypoint passes it.
    // getenv stays as a fallback for a debuggable build launched from a shell.
    @Volatile private var injectedControl: String? = null
    @Volatile private var injectedView: String? = null

    /** install records the tokens the app was launched with. Call once, early. */
    fun install(control: String?, view: String?) {
        injectedControl = control?.takeIf { it.isNotEmpty() }
        injectedView = view?.takeIf { it.isNotEmpty() }
    }

    private val control: String?
        get() = injectedControl ?: System.getenv("EAZYSTREAM_CONTROL_TOKEN")?.takeIf { it.isNotEmpty() }
    private val view: String?
        get() = injectedView ?: System.getenv("EAZYSTREAM_VIEW_TOKEN")?.takeIf { it.isNotEmpty() }

    /** True when no token is configured at all (auth disabled). */
    val isOpen: Boolean get() = control == null && view == null

    /** authorize maps a presented token to a Mode; it NEVER fails (view default). */
    fun authorize(token: String?): Mode {
        if (isOpen) return Mode.CONTROL
        val t = token ?: ""
        // Read once into a local: `control` has a custom getter, so Kotlin
        // cannot smart-cast it (and reading it twice could race install()).
        val c = control
        if (c != null && ctEq(t, c)) return Mode.CONTROL
        return Mode.VIEW
    }

    /** isMutation reports whether an est frame type CHANGES the guest. */
    fun isMutation(typ: Int): Boolean = when (typ) {
        0x10, 0x11, 0x12, 0x13, 0x14, 0x15,  // pointer + keyboard + touch
        0x26,                                 // a11y ActionReq
        0x32 -> true                          // ClipSet
        else -> false
    }

    /** constant-time equality (avoids leaking the token via timing). */
    private fun ctEq(a: String, b: String): Boolean {
        val x = a.toByteArray(); val y = b.toByteArray()
        if (x.size != y.size) return false
        var d = 0
        for (i in x.indices) d = d or (x[i].toInt() xor y[i].toInt())
        return d == 0
    }

    /** tokenFrom extracts the token: 2nd Sec-WebSocket-Protocol item, else query token. */
    fun tokenFrom(headers: Map<String, String>, query: String): String? {
        headers["sec-websocket-protocol"]?.split(",")?.forEach {
            val p = it.trim()
            if (p.isNotEmpty() && p != "est") return p
        }
        for (kv in query.split("&")) {
            if (kv.startsWith("token=")) {
                val v = kv.substring("token=".length)
                if (v.isNotEmpty()) return v
            }
        }
        return null
    }

    /** a11yIsMutation reports whether an a11y request CHANGES the guest. */
    fun a11yIsMutation(path: String, query: String): Boolean = when (path) {
        "/a11y/action", "/a11y/click", "/a11y/type", "/a11y/key",
        "/a11y/scroll", "/a11y/drag" -> true
        "/a11y/clipboard" -> query.split("&").any { it.startsWith("set=") }
        else -> false
    }
}
