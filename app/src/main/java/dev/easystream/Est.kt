package dev.easystream

import android.accessibilityservice.AccessibilityService
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Est is the Android easystream (`est://`) daemon — the SINGLE interface of
 * easystream (see EST.md). It runs INSIDE the emulator (the app process); the
 * Go worker talks to it over 127.0.0.1:48079 exactly like the macOS/Windows
 * daemons. One TCP connection multiplexes the H.264 video stream (0x02), input
 * (0x10-0x15), clipboard (0x30-0x32) and a11y queries (0x20-0x27, 0x40-0x41).
 *
 * Everything the worker needs lives here: capture+encode (MediaProjection ->
 * MediaCodec), the a11y tree (AccessibilityService), input (gestures) and the
 * clipboard — so the worker owns no a11y logic of its own.
 */
class Est(private val port: Int) {
    private var server: ServerSocket? = null
    @Volatile private var running = false

    companion object {
        private const val T_HELLO = 0x01
        /// Run-time reconfiguration (EST.md 0x08/0x09).
        private const val T_CONFIG_SET = 0x08
        private const val T_CONFIG_ACK = 0x09
        /// Ask for an IDR without changing anything (EST.md 0x0A).
        private const val T_KEYFRAME_REQ = 0x0A

        /// injectKeyByName is the HTTP entry point for /a11y/key: it maps an
        /// A11Y_HTTP key name to the same handling the est T_KEY frame uses.
        fun injectKeyByName(name: String) {
            val kc = Keys.code(name)
            if (kc != null) {
                if (EasyIme.instance?.sendKey(kc) == true) return
                runCatching { Runtime.getRuntime().exec(arrayOf("input", "keyevent", kc.toString())) }
                return
            }
            when (name.lowercase()) {
                "back", "systemback" ->
                    A11yService.instance?.systemKey(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                "systemhome" ->
                    A11yService.instance?.systemKey(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                "recents", "appswitch" ->
                    A11yService.instance?.systemKey(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
                "notifications" ->
                    A11yService.instance?.systemKey(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            }
        }
        private const val T_VIDEO = 0x02
        private const val T_AUTH = 0x03
        private const val T_AUTH_OK = 0x04
        private const val T_AUDIO = 0x05
        private const val T_AUDIO_REQ = 0x06
        private const val T_AUDIO_OFF = 0x07
        private const val T_MOVE = 0x10
        private const val T_BUTTON = 0x11
        private const val T_SCROLL = 0x12
        private const val T_KEY = 0x13
        private const val T_TEXT = 0x14
        private const val T_TOUCH = 0x15
        private const val T_APPS_REQ = 0x20
        private const val T_APPS_RESP = 0x21
        private const val T_TREE_REQ = 0x22
        private const val T_TREE_RESP = 0x23
        private const val T_FIND_REQ = 0x24
        private const val T_FIND_RESP = 0x25
        private const val T_ACT_REQ = 0x26
        private const val T_ACT_RESP = 0x27
        private const val T_CLIP_GET = 0x30
        private const val T_CLIP_DATA = 0x31
        private const val T_CLIP_SET = 0x32
        private const val T_SHOT_REQ = 0x40
        private const val T_SHOT_RESP = 0x41
        private const val T_PING = 0x50
        private const val T_PONG = 0x51
    }

    fun start() {
        running = true
        server = ServerSocket(port, 16, java.net.InetAddress.getByName("127.0.0.1"))
        thread(name = "est-accept") {
            while (running) {
                val sock = try { server?.accept() ?: break } catch (e: Exception) { break }
                thread(name = "est-conn") { handle(sock) }
            }
        }
        Video.start()
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    /// Live connections, so a reconfiguration can be announced to all of them.
    /// Hello is the only authoritative statement of geometry (EST.md): a client
    /// that stays connected through a ConfigSet has no other way to learn that
    /// the encoder is now emitting a different size.
    private val live = java.util.concurrent.CopyOnWriteArrayList<(Int, ByteArray) -> Unit>()

    /// Re-announce the current video configuration to every live connection.
    private fun broadcastHello() {
        val sz = Screen.encoderSizePublic(1)
        val devW = sz?.first ?: 1920
        val devH = sz?.second ?: 1080
        val payload = helloPayload(devW, devH)
        for (w in live) {
            try { w(T_HELLO, payload) } catch (_: Exception) {}
        }
    }

    private fun handle(sock: Socket) {
        val out = DataOutputStream(sock.getOutputStream())
        val wmu = Object()
        val closed = AtomicBoolean(false)
        fun writeFrame(typ: Int, payload: ByteArray) {
            synchronized(wmu) {
                try {
                    out.writeByte(typ); out.writeByte(0)
                    out.writeInt(payload.size); out.write(payload); out.flush()
                } catch (e: Exception) { closed.set(true) }
            }
        }

        // Register this connection's writer so broadcastHello() can reach it.
        val sink: (Int, ByteArray) -> Unit = { t, pl -> writeFrame(t, pl) }
        live.add(sink)
        try {
            // encoderSizePublic returns null until a MediaProjection grant
            // exists; destructuring null threw an NPE and killed the connection
            // before the (shared) video pipeline could deliver anything. Fall
            // back to the display size for the hello.
            //
            // The hello must report the DEVICE size (scale=1), NOT the encoder's
            // downscaled size: the viewer maps its pointer coordinates onto the
            // hello dimensions and the worker feeds them straight to
            // `input touchscreen`, which expects device pixels. Reporting
            // 960x540 made every tap land at half the intended position.
            val sz = Screen.encoderSizePublic(1)
            val w = sz?.first ?: 1920
            val h = sz?.second ?: 1080
            writeFrame(T_HELLO, helloPayload(w, h))

            // Subscribe to the SHARED capture+encode pipeline (one VirtualDisplay
            // for the whole process, fanned out to every connection).
            val unsub = Video.subscribe { au -> writeFrame(T_VIDEO, au) }
            // Audio: subscribe this connection to the shared Opus pipeline.
            // hasAudio is recomputed here because the Hello construction moved
            // into helloPayload, which reads it privately.
            val (hasAudioNow, _, _, _) = Audio.params()
            if (hasAudioNow) {
                Audio.subscribe(Screen.projection) { pkt ->
                    if (audioOn) writeFrame(T_AUDIO, pkt)
                }
            }
            try {
                val input = DataInputStream(sock.getInputStream())
                val hdr = ByteArray(6)
                while (!closed.get()) {
                    input.readFully(hdr)
                    val typ = hdr[0].toInt() and 0xff
                    val len = ((hdr[2].toInt() and 0xff) shl 24) or ((hdr[3].toInt() and 0xff) shl 16) or
                        ((hdr[4].toInt() and 0xff) shl 8) or (hdr[5].toInt() and 0xff)
                    val body = ByteArray(len)
                    if (len > 0) input.readFully(body)
                    dispatch(typ, body, ::writeFrame)
                }
            } finally {
                unsub()
            }
        } catch (e: Exception) {
            System.err.println("est conn: ${e.message}")
        } finally {
            live.remove(sink)
            closed.set(true)
            try { sock.close() } catch (_: Exception) {}
        }
    }

    /// Build a Hello for the CURRENT video configuration (EST.md).
    ///
    /// 27 bytes: the v1 core (7), the audio block (8), the video block at offset
    /// 15 (8) and - because Android is the one platform where the two differ -
    /// the encoded size at offset 23.
    ///
    /// Offsets 2..6 carry the DEVICE size and flags bit2 says so: this daemon's
    /// input path is `input touchscreen`, which takes device pixels, while the
    /// stream may be downscaled. A viewer that only knew the decoded size would
    /// land every tap at the wrong point.
    private fun helloPayload(devW: Int, devH: Int): ByteArray {
        val cfg = VideoConfig
        val encW = if (cfg.outputW != 0) cfg.outputW else devW
        val encH = if (cfg.outputH != 0) cfg.outputH else devH
        val hello = ByteArray(7 + 8 + 8 + 4)
        hello[0] = 1; hello[1] = 1
        hello[2] = ((devW shr 8) and 0xff).toByte(); hello[3] = (devW and 0xff).toByte()
        hello[4] = ((devH shr 8) and 0xff).toByte(); hello[5] = (devH and 0xff).toByte()
        hello[6] = cfg.frameRate.toByte()
        val (hasAudio, aRate, aCh, aMs) = Audio.params()
        hello[7] = if (hasAudio) 1 else 0
        hello[8] = 1
        hello[9] = ((aRate shr 24) and 0xff).toByte(); hello[10] = ((aRate shr 16) and 0xff).toByte()
        hello[11] = ((aRate shr 8) and 0xff).toByte(); hello[12] = (aRate and 0xff).toByte()
        hello[13] = aCh; hello[14] = aMs
        val vb = cfg.helloBlock()
        // bit2: offsets 2..6 are the INPUT size, not the decoded size.
        vb[2] = (vb[2].toInt() or 4).toByte()
        for (i in 0 until 8) hello[15 + i] = vb[i]
        hello[23] = ((encW shr 8) and 0xff).toByte(); hello[24] = (encW and 0xff).toByte()
        hello[25] = ((encH shr 8) and 0xff).toByte(); hello[26] = (encH and 0xff).toByte()
        return hello
    }

    private fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
    private fun i16(b: ByteArray, o: Int): Int = u16(b, o).let { if (it >= 0x8000) it - 0x10000 else it }
    private fun u32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o+1].toInt() and 0xff) shl 16) or ((b[o+2].toInt() and 0xff) shl 8) or (b[o+3].toInt() and 0xff)
    private fun p32(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    // Access mode for THIS connection; View unless a valid token arrives.
    @Volatile private var mode: Auth.Mode = if (Auth.isOpen) Auth.Mode.CONTROL else Auth.Mode.VIEW
    // Whether this connection wants Audio frames (turned on by AudioReq).
    @Volatile private var audioOn = false
    @Volatile private var lastX = 0f
    @Volatile private var lastY = 0f
    // Mouse press/drag: a click is a press+release at ~the same spot, a DRAG is
    // a press, a move, then a release. The old code fired a tap on button-down
    // and ignored the moves, so dragging (e.g. pulling the notification shade
    // down from the top) never became a swipe.
    @Volatile private var pressed = false
    @Volatile private var downX = 0f
    @Volatile private var downY = 0f

    private fun dispatch(typ: Int, b: ByteArray, write: (Int, ByteArray) -> Unit) {
        val svc = A11yService.instance
        if (typ == T_AUTH) {
            val tok = String(b, Charsets.UTF_8)
            mode = Auth.authorize(tok)
            write(T_AUTH_OK, byteArrayOf(mode.v.toByte()))
            android.util.Log.i("est", "auth -> $mode")
            return
        }
        if (typ == T_KEYFRAME_REQ) {
            // Not a mutation: no guest state changes, so a read-only viewer may
            // recover from a corrupt picture.
            LAST_PLI.set(System.currentTimeMillis())
            return
        }
        if (typ == T_CONFIG_SET) {
            val (patch, err) = parseConfig(b)
            if (err != null) {
                System.err.println("est: ConfigSet parse failed: $err")
                write(T_CONFIG_ACK, ackBody(false, err))
                return
            }
            val refusal = VideoConfig.apply(patch!!)
            if (refusal != null) {
                System.err.println("est: ConfigSet rejected: $refusal")
                write(T_CONFIG_ACK, ackBody(false, refusal))
                return
            }
            System.err.println(
                "est: ConfigSet ok -> ${VideoConfig.outputW}x${VideoConfig.outputH} " +
                "fps=${VideoConfig.frameRate} br=${VideoConfig.bitRate} gen=${VideoConfig.generationNow}")
            write(T_CONFIG_ACK, ackBody(true, ""))
            // Tell EVERY viewer, not just the one that asked: Hello is the only
            // authoritative statement of geometry, so a viewer that stays
            // connected would otherwise keep decoding at the old size.
            broadcastHello()
            // Android cannot resize the existing VirtualDisplay: MediaProjection
            // allows createVirtualDisplay exactly once per token, and releasing
            // it invalidates the token. The pipeline therefore restarts, which
            // re-creates the projection through ControlService.
            Video.restartForConfig()
            return
        }
        if (typ == T_AUDIO_REQ) { audioOn = true; return }
        if (typ == T_AUDIO_OFF) { audioOn = false; return }
        // View mode: drop every frame that would CHANGE the guest.
        if (mode == Auth.Mode.VIEW && Auth.isMutation(typ)) return
        when (typ) {
            // Mouse: a REAL pointer (hover + buttons + scroll).
            T_MOVE -> { lastX = u16(b, 0).toFloat(); lastY = u16(b, 2).toFloat() }
            T_BUTTON -> if (b.size >= 2) {
                if (b[1].toInt() != 0) {
                    pressed = true; downX = lastX; downY = lastY
                } else if (pressed) {
                    pressed = false
                    val dist = kotlin.math.hypot((lastX - downX).toDouble(), (lastY - downY).toDouble())
                    // A real drag (>=24px) is a swipe; anything smaller is a tap.
                    if (dist >= 24.0) svc?.swipeAt(downX, downY, lastX, lastY, 200)
                    else svc?.tapAt(lastX, lastY, 40)
                }
            }
            // Scroll: the wheel deltas are in PIXELS and can be small (a notch
            // is ~100), which is far too short to register as a swipe. Use the
            // wheel's OWN coordinates (not the last MOVE) and scale the delta
            // up, then run it as a flick in the OPPOSITE direction (scrolling
            // down moves the content up, i.e. the finger moves up).
            T_SCROLL -> {
                val wx = u16(b, 0).toFloat()
                val wy = u16(b, 2).toFloat()
                val dx = i16(b, 4)
                val dy = i16(b, 6)
                val gain = 3f
                val sx = wx + dx * gain
                val sy = wy + dy * gain
                svc?.swipeAt(wx, wy, sx, sy, 180)
            }
            // Touch: the real touchscreen (finger).
            T_TOUCH -> when (b[0].toInt() and 0xff) {
                0 -> svc?.touchDown(u16(b, 1).toFloat(), u16(b, 3).toFloat())
                1 -> svc?.touchMove(u16(b, 1).toFloat(), u16(b, 3).toFloat())
                2 -> svc?.touchUp()
            }
            T_KEY -> if (b.size >= 1) {
                val n = b[0].toInt()
                // Key: nameLen:u8, name[nameLen], char:u32 (LE), down:u8.
                if (b.size >= 1 + n + 4 + 1) {
                    val name = String(b, 1, n)
                    val ch = (b[1 + n].toInt() and 0xff) or ((b[2 + n].toInt() and 0xff) shl 8) or
                        ((b[3 + n].toInt() and 0xff) shl 16) or ((b[4 + n].toInt() and 0xff) shl 24)
                    if (b[1 + n + 4].toInt() != 0) injectKey(name, ch)
                }
            }
            // Type TEXT character by character through the IME path. The old
            // single ACTION_SET_TEXT on the first node REPLACED that node's whole
            // text (and needed a second keystroke to take effect), so typing
            // appeared to drop every other character.
            // Prefer the IME (commitText handles UTF-8 and never clobbers the
            // editor); fall back to per-character accessibility text setting.
            T_TEXT -> {
                val text = String(b, Charsets.UTF_8)
                if (text.isNotEmpty() && EasyIme.instance?.commitText(text) != true) {
                    for (ch in text) svc?.typeChar(ch)
                }
            }
            T_PING -> write(T_PONG, b)
            else -> dispatchA11y(typ, b, write)
        }
    }

    /// Parse a 0x08 ConfigSet body into a Patch.
    ///
    /// The cursor walks the frame strictly: every field knows its own length, and
    /// a frame that ends early is an error, not a partial patch. Truncation is
    /// the likeliest corruption here, and applying half of it would move the
    /// stream somewhere nobody asked for.
    private fun parseConfig(b: ByteArray): Pair<VideoConfig.Patch?, String?> {
        var i = 0
        fun need(n: Int) = i + n <= b.size
        var width: Int? = null
        var height: Int? = null
        var fps: Int? = null
        var bitrate: Int? = null
        var encoder: Int? = null

        if (!need(1)) return Pair(null, "truncated: hasWidth")
        if (b[i].toInt() != 0) {
            if (!need(3)) return Pair(null, "truncated: width")
            width = u16(b, i + 1)
        }
        i += 3
        if (!need(1)) return Pair(null, "truncated: hasHeight")
        if (b[i].toInt() != 0) {
            if (!need(3)) return Pair(null, "truncated: height")
            height = u16(b, i + 1)
        }
        i += 3
        if (!need(1)) return Pair(null, "truncated: hasFps")
        if (b[i].toInt() != 0) {
            if (!need(2)) return Pair(null, "truncated: fps")
            fps = b[i + 1].toInt() and 0xff
        }
        i += 2
        if (!need(1)) return Pair(null, "truncated: hasBitrate")
        if (b[i].toInt() != 0) {
            if (!need(5)) return Pair(null, "truncated: bitrate")
            bitrate = u32(b, i + 1)
        }
        i += 5
        if (!need(1)) return Pair(null, "truncated: hasEncoder")
        if (b[i].toInt() != 0) {
            if (!need(2)) return Pair(null, "truncated: encoder")
            val e = b[i + 1].toInt() and 0xff
            // 0xFF is the documented "let the server pick" value.
            encoder = if (e == 0xFF) 0 else e
        }
        return Pair(VideoConfig.Patch(width, height, fps, bitrate, encoder), null)
    }

    /// Build a 0x09 ConfigAck body: ok byte, then a length-prefixed UTF-8 reason.
    private fun ackBody(ok: Boolean, msg: String): ByteArray {
        val m = msg.toByteArray(Charsets.UTF_8).take(255).toByteArray()
        val out = ByteArray(2 + m.size)
        out[0] = if (ok) 1 else 0
        out[1] = m.size.toByte()
        m.copyInto(out, 2)
        return out
    }

    private fun dispatchA11y(typ: Int, b: ByteArray, write: (Int, ByteArray) -> Unit) {
        when (typ) {
            T_APPS_REQ -> {
                val id = u32(b, 0)
                val text = Commands.dispatch("/apps", emptyMap())
                write(T_APPS_RESP, p32(id) + text.toByteArray(Charsets.UTF_8))
            }
            T_TREE_REQ -> {
                val id = u32(b, 0); val depth = b[8].toInt() and 0xff
                val text = Commands.dispatch("/tree", mapOf("depth" to depth.toString()))
                write(T_TREE_RESP, p32(id) + p32(text.toByteArray(Charsets.UTF_8).size) + text.toByteArray(Charsets.UTF_8))
            }
            T_FIND_REQ -> {
                val id = u32(b, 0)
                val selLen = b[8].toInt() and 0xff
                val sel = String(b, 9, selLen)
                val out = b[9 + selLen].toInt() and 0xff
                val text = Commands.dispatch("/find", mapOf("sel" to sel, "o" to out.toString()))
                write(T_FIND_RESP, p32(id) + p32(text.toByteArray(Charsets.UTF_8).size) + text.toByteArray(Charsets.UTF_8))
            }
            T_ACT_REQ -> {
                val id = u32(b, 0)
                var o = 4
                val actLen = b[o].toInt() and 0xff; o += 1
                val act = String(b, o, actLen); o += actLen
                o += 4 // pid
                val selLen = b[o].toInt() and 0xff; o += 1
                val sel = String(b, o, selLen); o += selLen
                val hasVal = b[o].toInt() != 0; o += 1
                val text = if (hasVal) {
                    val valLen = b[o].toInt() and 0xff; o += 1
                    String(b, o, valLen)
                } else ""
                val ok = Commands.dispatch("/" + act, mapOf("sel" to sel, "text" to text))
                val msg = ok.toByteArray(Charsets.UTF_8)
                write(T_ACT_RESP, p32(id) + byteArrayOf(1, msg.size.toByte()) + msg)
            }
            T_CLIP_GET -> {
                val id = u32(b, 0)
                val text = Commands.dispatch("/clipboard-get", emptyMap())
                val bytes = text.toByteArray(Charsets.UTF_8)
                write(T_CLIP_DATA, p32(id) + p32(bytes.size) + bytes)
            }
            T_CLIP_SET -> {
                val id = u32(b, 0)
                val len = u32(b, 4)
                val text = String(b, 8, len)
                Commands.dispatch("/clipboard-set", mapOf("text" to text))
                write(T_CLIP_DATA, p32(id) + p32(0))
            }
            T_SHOT_REQ -> {
                val id = u32(b, 0)
                val png = Screen.screenshotBytes() ?: ByteArray(0)
                write(T_SHOT_RESP, p32(id) + p32(png.size) + png)
            }
        }
    }

    /**
     * injectKey maps a named key (or a single character typed with a modifier)
     * to an Android key event and injects it via `input keyevent`. The system
     * global actions (Back/Home/Recents) go through the a11y service instead.
     */
    private fun injectKey(name: String, ch: Int) {
        when (name) {
            // Android SYSTEM keys are explicit names — they must NOT steal the
            // normal PC keys. (Return used to be mapped to "recents", so pressing
            // Enter opened the task switcher.)
            "SystemBack", "Back" ->
                { A11yService.instance?.systemKey(AccessibilityService.GLOBAL_ACTION_BACK); return }
            "SystemHome" ->
                { A11yService.instance?.systemKey(AccessibilityService.GLOBAL_ACTION_HOME); return }
            "Recents", "AppSwitch" ->
                { A11yService.instance?.systemKey(AccessibilityService.GLOBAL_ACTION_RECENTS); return }
            "Notifications" ->
                { A11yService.instance?.systemKey(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); return }
        }
        // Modifiers produce no event of their own (their effect is applied by
        // the daemon when the following key is pressed).
        if (name == "Control" || name == "Alt" || name == "Shift" || name == "Meta") return
        val kc = Keys.code(name) ?: if (name.length == 1) Keys.code(name) else null
        if (kc != null) {
            execKey(kc)
        } else if (ch != 0 && Character.isLetterOrDigit(ch.toChar())) {
            execKey(Keys.code(ch.toChar().toString().uppercase()) ?: return)
        }
    }

    /**
     * execKey injects one key. `input keyevent` needs INJECT_EVENTS (shell /
     * system), which an app cannot hold, so route through our headless IME
     * (InputConnection.sendKeyEvent) — that is the only non-system way. Fall
     * back to `input` for the (unlikely) case where the IME is not yet bound.
     */
    private fun execKey(keycode: Int) {
        if (EasyIme.instance?.sendKey(keycode) == true) return
        try {
            Runtime.getRuntime().exec(arrayOf("input", "keyevent", keycode.toString()))
        } catch (_: Exception) {}
    }
}
