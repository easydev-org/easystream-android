package dev.easystream

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import android.util.Base64
import kotlin.concurrent.thread

/**
 * Http is the Android easystream daemon's HTTP surface (A11Y_HTTP.md):
 *
 *   GET /            the embedded easystream web UI
 *   GET /app.js      the web UI client
 *   GET /est/ws      WebSocket <-> est byte pipe (transparent)
 *   GET /a11y/<...>  the a11y API (delegates to Commands)
 *
 * The worker only forwards to this port; it never parses est or a11y payloads.
 */
class Http(private val ctx: android.content.Context, private val port: Int, private val estPort: Int) {
    private var server: ServerSocket? = null
    @Volatile private var running = false

    /** asset reads an embedded web UI file (app/src/main/assets/webui/). */
    private fun asset(name: String): String =
        runCatching { ctx.assets.open("webui/$name").readBytes().toString(Charsets.UTF_8) }.getOrElse { "" }
    private val webuiIndex by lazy { asset("index.html") }
    private val webuiJs by lazy { asset("app.js") }

    fun start() {
        running = true
        server = ServerSocket(port, 16, java.net.InetAddress.getByName("0.0.0.0"))
        thread(name = "http-accept") {
            while (running) {
                val sock = try { server?.accept() ?: break } catch (e: Exception) { break }
                thread(name = "http-conn") { runCatching { handle(sock) } }
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun handle(sock: Socket) {
        val input = BufferedInputStream(sock.getInputStream())
        val out = sock.getOutputStream()
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(" ")
        val url = if (parts.size >= 2) parts[1] else "/"
        // Drain headers.
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val idx = h.indexOf(':')
            if (idx > 0) headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
        }
        val path = url.substringBefore('?')
        val query = url.substringAfter('?', "")
        when {
            path == "/" || path == "/index.html" -> respond(out, 200, "text/html; charset=utf-8", webuiIndex)
            path == "/app.js" -> respond(out, 200, "application/javascript; charset=utf-8", webuiJs)
            path == "/health" -> respond(out, 200, "text/plain", "ok")
            path == "/est/ws" -> wsPipe(sock, input, out, headers)
            path.startsWith("/a11y/") -> a11yRoute(out, path, query)
            else -> respond(out, 404, "text/plain", "not found")
        }
    }

    private fun a11yRoute(out: OutputStream, path: String, query: String) {
        val params = parseQuery(query)
        val cmd = when (path) {
            "/a11y/apps" -> "/apps"
            "/a11y/tree" -> "/tree"
            "/a11y/find" -> "/find"
            "/a11y/action" -> when (params["action"]) {
                "press", "click", "activate" -> "/click"
                "swipe" -> "/swipe"
                "longpress" -> "/longpress"
                "key" -> "/key"
                "settext", "setvalue" -> "/settext"
                else -> "/click"
            }
            "/a11y/screenshot" -> "/screenshot"
            "/a11y/clipboard" -> if (params.containsKey("set")) "/clipboard-set" else "/clipboard-get"
            else -> null
        }
        if (cmd == null) {
            respond(out, 404, "text/plain", "unknown a11y path")
            return
        }
        val body = runCatching { Commands.dispatch(cmd, params) }.getOrElse { "error: ${it.message}" }
        respond(out, if (body.startsWith("error")) 500 else 200, "text/plain; charset=utf-8", body)
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val m = HashMap<String, String>()
        for (pair in query.split('&')) {
            val i = pair.indexOf('=')
            if (i > 0) {
                m[java.net.URLDecoder.decode(pair.substring(0, i), "UTF-8")] =
                    java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8")
            }
        }
        return m
    }

    private fun respond(out: OutputStream, status: Int, ctype: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
            "Content-Type: $ctype\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(bytes)
        out.flush()
    }

    // ── WebSocket (RFC 6455, server side, binary frames only) ───────────────

    private fun wsPipe(sock: Socket, input: BufferedInputStream, out: OutputStream, headers: Map<String, String>) {
        val key = headers["sec-websocket-key"] ?: return
        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()),
            Base64.NO_WRAP,
        )
        out.write(
            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(),
        )
        out.flush()

        val est = Socket("127.0.0.1", estPort)
        val wmu = Object()
        val readerThread = thread(name = "ws-est") {
            val buf = ByteArray(1 shl 16)
            val ins = est.getInputStream()
            try {
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    synchronized(wmu) { wsWriteBinary(out, buf, n) }
                }
            } catch (_: Exception) {}
            runCatching { est.close() }
            runCatching { sock.close() }
        }
        try {
            val estOut = est.getOutputStream()
            while (true) {
                val frame = wsRead(input) ?: break
                estOut.write(frame); estOut.flush()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { est.close() }
            readerThread.interrupt()
        }
    }

    private fun wsWriteBinary(out: OutputStream, payload: ByteArray, len: Int) {
        val hdr = ByteArray(if (len < 126) 2 else if (len <= 0xffff) 4 else 10)
        hdr[0] = 0x82.toByte()
        when {
            len < 126 -> hdr[1] = len.toByte()
            len <= 0xffff -> { hdr[1] = 126; hdr[2] = (len shr 8).toByte(); hdr[3] = len.toByte() }
            else -> {
                hdr[1] = 127
                for (i in 0 until 8) hdr[2 + i] = ((len.toLong() shr (8 * (7 - i))) and 0xff).toByte()
            }
        }
        out.write(hdr); out.write(payload, 0, len); out.flush()
    }

    /** wsRead returns the next binary payload, or null on close/EOF. */
    private fun wsRead(input: BufferedInputStream): ByteArray? {
        val b0 = input.read(); if (b0 < 0) return null
        val b1 = input.read(); if (b1 < 0) return null
        val opcode = b0 and 0x0f
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7f).toLong()
        if (len == 126L) len = ((input.read() shl 8) or input.read()).toLong()
        else if (len == 127L) {
            len = 0
            for (i in 0 until 8) len = (len shl 8) or input.read().toLong()
        }
        val mask = ByteArray(4)
        if (masked) readFully(input, mask)
        val payload = ByteArray(len.toInt())
        readFully(input, payload)
        if (masked) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
        return when (opcode) {
            0x2 -> payload
            0x8 -> null
            else -> wsRead(input) // ping/pong/text: skip
        }
    }

    private fun readFully(input: BufferedInputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n <= 0) throw java.io.EOFException()
            off += n
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

}

