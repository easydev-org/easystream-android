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
        private const val T_VIDEO = 0x02
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
            val hello = ByteArray(7)
            hello[0] = 1; hello[1] = 1
            hello[2] = ((w shr 8) and 0xff).toByte(); hello[3] = (w and 0xff).toByte()
            hello[4] = ((h shr 8) and 0xff).toByte(); hello[5] = (h and 0xff).toByte()
            hello[6] = 30
            writeFrame(T_HELLO, hello)

            // Subscribe to the SHARED capture+encode pipeline (one VirtualDisplay
            // for the whole process, fanned out to every connection).
            val unsub = Video.subscribe { au -> writeFrame(T_VIDEO, au) }
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
            closed.set(true)
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
    private fun i16(b: ByteArray, o: Int): Int = u16(b, o).let { if (it >= 0x8000) it - 0x10000 else it }
    private fun u32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o+1].toInt() and 0xff) shl 16) or ((b[o+2].toInt() and 0xff) shl 8) or (b[o+3].toInt() and 0xff)
    private fun p32(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    @Volatile private var lastX = 0f
    @Volatile private var lastY = 0f

    private fun dispatch(typ: Int, b: ByteArray, write: (Int, ByteArray) -> Unit) {
        val svc = A11yService.instance
        when (typ) {
            // Mouse: a REAL pointer (hover + buttons + scroll).
            T_MOVE -> { lastX = u16(b, 0).toFloat(); lastY = u16(b, 2).toFloat() }
            T_BUTTON -> if (b.size >= 2 && b[1].toInt() != 0) svc?.tapAt(lastX, lastY, 40)
            T_SCROLL -> { val dx = i16(b, 4); val dy = i16(b, 6); svc?.swipeAt(lastX, lastY, lastX + dx, lastY + dy, 120) }
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
            T_TEXT -> svc?.setText(Selector(""), String(b))
            T_PING -> write(T_PONG, b)
            else -> dispatchA11y(typ, b, write)
        }
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
            "Back" -> { A11yService.instance?.systemKey(AccessibilityService.GLOBAL_ACTION_BACK); return }
            "Home" -> { A11yService.instance?.systemKey(AccessibilityService.GLOBAL_ACTION_HOME); return }
            "Return", "Meta" -> { A11yService.instance?.systemKey(AccessibilityService.GLOBAL_ACTION_RECENTS); return }
        }
        if (name == "Control" || name == "Alt" || name == "Shift" || name == "Meta") return // modifiers need no event of their own
        val kc = Keys.code(name) ?: if (name.length == 1) Keys.code(name) else null
        if (kc != null) {
            execKey(kc)
        } else if (ch != 0 && Character.isLetterOrDigit(ch.toChar())) {
            execKey(Keys.code(ch.toChar().toString().uppercase()) ?: return)
        }
    }

    private fun execKey(keycode: Int) {
        try {
            Runtime.getRuntime().exec(arrayOf("input", "keyevent", keycode.toString()))
        } catch (_: Exception) {}
    }
}
