package dev.easystream

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.view.WindowManager
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Screen captures the display via MediaProjection and encodes H.264 via
 * MediaCodec — the Android native codec stack (counterpart of macOS
 * ScreenCaptureKit/VideoToolbox and Windows GDI/Media Foundation).
 *
 * MediaProjection permits exactly ONE VirtualDisplay per grant, so we create a
 * single ImageReader-backed display and never recreate it: screenshots read a
 * frame from the reader, and encode reads frames from the same reader and feeds
 * them to MediaCodec through INPUT BUFFERS (no second surface). Swapping the
 * display's surface between an ImageReader and a codec input surface is what
 * invalidates the projection, so we avoid it entirely.
 */
object Screen {
    @Volatile var projection: MediaProjection? = null

    @Volatile private var display: android.hardware.display.VirtualDisplay? = null
    @Volatile private var reader: ImageReader? = null
    @Volatile private var size: Triple<Int, Int, Int>? = null

    private fun metrics(): Triple<Int, Int, Int> {
        val svc = ControlService.instance ?: throw IllegalStateException("control service not running")
        val wm = svc.getSystemService(WindowManager::class.java)
        val w = wm.currentWindowMetrics.bounds.width()
        val h = wm.currentWindowMetrics.bounds.height()
        val dpi = svc.resources.displayMetrics.densityDpi
        return Triple(w, h, dpi)
    }

    /** ensureDisplay creates (once) the persistent ImageReader-backed display. */
    private fun ensureDisplay(): ImageReader {
        val (w, h, dpi) = metrics()
        val cur = reader
        if (cur != null && size == Triple(w, h, dpi)) return cur
        // Only ever create once per projection; a size change needs a new grant.
        if (display != null) return reader!!
        val mp = projection ?: throw IllegalStateException("no MediaProjection grant")
        // maxImages must exceed the number of buffers the producer may hold;
        // acquireLatestImage() drops stale ones, but a small cap makes repeated
        // captures time out when the queue is full.
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 8)
        val vd = mp.createVirtualDisplay(
            "a11y-capture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null,
        )
        display = vd
        reader = r
        size = Triple(w, h, dpi)
        return r
    }

    /**
     * encodeSize returns the size the encoder should use. A software encoder on
     * a headless emulator cannot keep up with a 1080x2400 screen at 10fps, so
     * callers may pass --w/--h (or the A11Y_ENC_SCALE env) to downscale. The
     * screen is captured at full size and sampled down while converting.
     */
    private fun encodeSize(scale: Int): Triple<Int, Int, Int> {
        val (w, h, dpi) = metrics()
        if (scale <= 1) return Triple(w, h, dpi)
        return Triple((w / scale) and 1.inv(), (h / scale) and 1.inv(), dpi)
    }

    /** encoderSizePublic exposes the encoder dimensions for the est Hello. */
    fun encoderSizePublic(scale: Int): Triple<Int, Int, Int> = encodeSize(scale)

    /** releaseDisplayPublic tears down the screenshot display (for the streamer). */
    fun releaseDisplayPublic() = releaseDisplay()

    /** hasCaptureDisplay reports whether a VirtualDisplay currently exists.
     *  The screenshot path waits on this before taking the projection over. */
    fun hasCaptureDisplay(): Boolean = display != null

    /** streaming is set by the video pipeline while it holds the projection's
     *  single VirtualDisplay. The screenshot path waits for it to clear. */
    @Volatile var streaming = false

    /** screenshotBytes captures one frame as PNG bytes (for the est shot reply). */
    fun screenshotBytes(): ByteArray? {
        // Prefer the accessibility service's takeScreenshot: it is independent
        // of MediaProjection, so it works WHILE the video pipeline owns the
        // projection's single VirtualDisplay (releasing that display would
        // invalidate the token — see A11yService.takeScreenshotPng).
        val svc = A11yService.instance
        android.util.Log.i("easystream", "screenshotBytes: svc=${svc != null} projection=${projection != null}")
        svc?.takeScreenshotPng()?.let {
            android.util.Log.i("easystream", "screenshotBytes: takeScreenshot -> ${it.size} bytes")
            return it
        }
        android.util.Log.i("easystream", "screenshotBytes: falling back to MediaProjection")
        // Fallback: the MediaProjection path. It needs the projection's single
        // VirtualDisplay, so pause the pipeline and wait for it to actually
        // release (Screen.streaming), then let it rebuild.
        if (projection == null) return null
        val wasStreaming = Video.isRunning()
        if (wasStreaming) {
            Video.stop()
            val deadline = System.currentTimeMillis() + 3000
            while (streaming && System.currentTimeMillis() < deadline) Thread.sleep(50)
        }
        val tmp = java.io.File.createTempFile("shot", ".png")
        return try {
            val r = screenshot(tmp.absolutePath)
            if (r.startsWith("error")) null else tmp.readBytes()
        } catch (_: Exception) {
            null
        } finally {
            tmp.delete()
            if (wasStreaming) Video.start()
        }
    }

    /** screenshot captures one frame and writes a PNG to [out]. */
    fun screenshot(out: String): String {
        if (projection == null) return "error: no MediaProjection grant"
        val (w, h, _) = metrics()
        val r = ensureDisplay()
        val img = awaitImage(r) ?: return "error: capture timeout"
        try {
            val bmp = imageToBitmap(img)
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            return "wrote $out (${w}x${h} @1x)"
        } finally { img.close() }
    }

    /** encode reads [frames] frames at [fps] and muxes H.264 to an MP4. */
    fun encode(out: String, frames: Int, fps: Int, scale: Int = 1): String {
        if (projection == null) return "error: no MediaProjection grant"
        val (w, h, _) = encodeSize(scale)

        val bitrate = 8_000_000
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // The VirtualDisplay is change-driven: a static screen emits NO
            // frames, so the stream would freeze until the next change. Ask the
            // encoder to re-emit the previous frame after this interval instead
            // of "nudging" the UI with a real swipe (which scrolled/perturbed
            // whatever the user was doing — it fought the notification shade).
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        // Surface mode: the encoder's input surface is fed by a VirtualDisplay,
        // so the compositor pushes frames directly with no CPU conversion. This
        // is the path the emulator's encoder reliably supports.
        val inputSurface = codec.createInputSurface()
        codec.start()

        val muxer = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val sink = MuxSink(muxer)
        val frameDurUs = 1_000_000L / fps

        // A projection allows only ONE VirtualDisplay; release the screenshot
        // display before creating the encoder's surface display.
        releaseDisplay()
        val vd = projection!!.createVirtualDisplay(
            "a11y-encode", w, h, metrics().third,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null,
        )
        try {
            // Run for the requested duration while continuously draining. The
            // VirtualDisplay drives the frame rate; we must keep pulling output
            // the whole time or the encoder's buffers back up.
            val captureEndsAt = System.currentTimeMillis() + (frames * 1000L / fps)
            while (System.currentTimeMillis() < captureEndsAt) {
                sink.drainBlocking(codec, 20_000)
            }
            // Surface mode ends via signalEndOfInputStream, then we drain until
            // the EOS flag actually arrives (a single timeout is NOT the end).
            codec.signalEndOfInputStream()
            val flushDeadline = System.currentTimeMillis() + 30_000
            while (!sink.ended && System.currentTimeMillis() < flushDeadline) {
                sink.drainBlocking(codec, 20_000)
            }
        } finally {
            vd.release()
            codec.stop()
            codec.release()
            inputSurface.release()
            sink.finish()
        }
        val bytes = java.io.File(out).length()
        return "encoded ${w}x${h} frames=$frames samples=${sink.written} bytes=$bytes -> $out"
    }

    /** releaseDisplay tears down the persistent capture display (if any). */
    private fun releaseDisplay() {
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        display = null
        reader = null
        size = null
    }

    /**
     * MuxSink owns the MP4 muxer and its lazy start. MediaCodec reports the
     * output format via INFO_OUTPUT_FORMAT_CHANGED, which MUST be handled
     * before any sample is written — missing it leaves the muxer unstarted and
     * the file empty. It also tracks the EOS flag.
     */
    private class MuxSink(private val muxer: MediaMuxer) {
        private var track = -1
        private var started = false
        var written = 0L
            private set
        private val info = MediaCodec.BufferInfo()
        @Volatile var ended = false
            private set

        private fun startIfNeeded(codec: MediaCodec) {
            if (started) return
            track = muxer.addTrack(codec.outputFormat)
            muxer.start()
            started = true
        }

        /** drainOnce handles exactly one dequeueOutputBuffer event. */
        fun drainOnce(codec: MediaCodec, timeoutUs: Long): Boolean {
            val idx = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    startIfNeeded(codec)
                    return true
                }
                idx >= 0 -> {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0) {
                        startIfNeeded(codec)
                        val buf = codec.getOutputBuffer(idx)
                        if (buf != null) {
                            muxer.writeSampleData(track, buf, info)
                            written++
                        }
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        ended = true
                        return false
                    }
                    return true
                }
                else -> return false // INFO_TRY_AGAIN_LATER
            }
        }

        /** drain pulls every currently-available output (non-blocking). */
        fun drain(codec: MediaCodec) {
            while (drainOnce(codec, 0)) { /* until TRY_AGAIN */ }
        }

        /**
         * drainBlocking waits up to [timeoutUs] per event and keeps pulling
         * until a dequeue times out — this is what actually frees input buffers
         * when the software encoder is slower than the producer.
         */
        fun drainBlocking(codec: MediaCodec, timeoutUs: Long) {
            while (drainOnce(codec, timeoutUs)) { /* until TRY_AGAIN or EOS */ }
        }

        fun finish() {
            if (started) muxer.stop()
            muxer.release()
        }
    }

    /**
     * queueFrame converts one RGBA Image to NV12 and queues it into the codec.
     * It blocks (draining output to free buffers) until an input buffer is
     * available, so no frame is silently dropped.
     */
    private fun queueFrame(codec: MediaCodec, img: Image, ptsUs: Long, sink: MuxSink, tw: Int, th: Int) {
        var idx = codec.dequeueInputBuffer(50_000)
        val deadline = System.currentTimeMillis() + 2000
        while (idx < 0 && System.currentTimeMillis() < deadline) {
            // Blocking drain: free output so the codec recycles input buffers.
            sink.drainBlocking(codec, 50_000)
            idx = codec.dequeueInputBuffer(50_000)
        }
        if (idx < 0) return
        val buf = codec.getInputBuffer(idx) ?: return
        val plane = img.planes[0]
        val rgba = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val sw = img.width
        val sh = img.height
        // Nearest-neighbour downscale from the captured frame to the target size.
        val stepX = if (tw > 0) sw.toDouble() / tw else 1.0
        val stepY = if (th > 0) sh.toDouble() / th else 1.0
        val ySize = tw * th
        buf.clear()
        // NV12: full-size Y plane, then interleaved UV at half resolution.
        val out = ByteArray(ySize + ySize / 2)
        val row = ByteArray(rowStride)
        var o = 0
        // Y plane.
        for (j in 0 until th) {
            val sy = (j * stepY).toInt().coerceIn(0, sh - 1)
            rgba.position(sy * rowStride)
            rgba.get(row, 0, minOf(rowStride, rgba.remaining()))
            for (i in 0 until tw) {
                val sx = (i * stepX).toInt().coerceIn(0, sw - 1)
                val r = row[sx * pixelStride].toInt() and 0xff
                val g = row[sx * pixelStride + 1].toInt() and 0xff
                val b = row[sx * pixelStride + 2].toInt() and 0xff
                out[o++] = clamp(((66 * r + 129 * g + 25 * b + 128) shr 8) + 16)
            }
        }
        // Interleaved UV, 2x2 subsampled.
        var uvOff = ySize
        var j = 0
        while (j < th) {
            val sy = (j * stepY).toInt().coerceIn(0, sh - 1)
            rgba.position(sy * rowStride)
            rgba.get(row, 0, minOf(rowStride, rgba.remaining()))
            var i = 0
            while (i < tw) {
                val sx = (i * stepX).toInt().coerceIn(0, sw - 1)
                val r = row[sx * pixelStride].toInt() and 0xff
                val g = row[sx * pixelStride + 1].toInt() and 0xff
                val b = row[sx * pixelStride + 2].toInt() and 0xff
                out[uvOff++] = clamp(((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128) // U
                out[uvOff++] = clamp(((112 * r - 94 * g - 18 * b + 128) shr 8) + 128)  // V
                i += 2
            }
            j += 2
        }
        buf.put(out)
        codec.queueInputBuffer(idx, 0, out.size, ptsUs, 0)
    }

    private fun awaitImage(r: ImageReader): Image? {
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            val img = r.acquireLatestImage()
            if (img != null) return img
            Thread.sleep(20)
        }
        return null
    }

    private fun imageToBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * img.width
        val bmp = Bitmap.createBitmap(
            img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888,
        )
        bmp.copyPixelsFromBuffer(plane.buffer)
        val cropped = Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
        if (cropped !== bmp) bmp.recycle()
        return cropped
    }

    private fun clamp(v: Int): Byte = (if (v < 0) 0 else if (v > 255) 255 else v).toByte()
}

/** Emits one Annex-B access unit per call. */
typealias AuSink = (ByteArray) -> Unit

/**
 * streamAnnexB runs the encoder until [stop] returns true, converting each
 * output buffer's AVCC NAL units to Annex-B and calling [sink]. Used by the est
 * daemon: the guest's hardware encoder produces H.264 straight for the viewer.
 *
 * The first output carries SPS/PPS as CODEC_CONFIG; MediaCodec (surface mode)
 * emits them once, so we buffer and prepend them to every IDR.
 */
fun streamAnnexB(fps: Int, scale: Int, stop: () -> Boolean, sink: AuSink): String {
    val mp = Screen.projection ?: return "error: no MediaProjection grant"
    // The VirtualDisplay size must equal the ENCODER's size (encodeSize(scale)),
    // exactly like the working encode()/MediaRecorder paths. A display at the
    // raw 1920x1080 screen size fed the encoder nothing.
    val (w, h, dpi) = Screen.encoderSizePublic(scale)
    val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, 12_000_000)
        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        // The VirtualDisplay is change-driven: a static screen emits NO frames.
        // Ask the encoder to re-emit the previous frame after this interval so
        // the stream stays alive WITHOUT poking the UI. (The old code called
        // touchDown/touchUp at the screen centre every ~20 ms, which fired a
        // real TAP on whatever was under the centre — that swallowed the user's
        // clicks and fought their drags.)
        setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / fps)
    }
    // Prefer the Codec2 encoder: c2.android.avc.encoder is markedly faster than
    // the OMX.google reference encoder on the emulator. Fall back to whatever
    // the platform picks if the name is unavailable.
    val codec = runCatching { MediaCodec.createByCodecName("c2.android.avc.encoder") }
        .getOrElse { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC) }
    System.err.println("est video: codec=${codec.name}")
    codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    val inputSurface = codec.createInputSurface()

    Screen.releaseDisplayPublic()
    val vd = try {
        // The VirtualDisplay must MATCH the primary display's size/density for
        // the mirror to actually contain the screen: with 960x540 (the encoder's
        // downscaled size) the compositor produced an empty display and the
        // encoder only ever emitted AUD-only frames. Capture at the real size;
        // the encoder downscales via its own input surface.
        mp.createVirtualDisplay(
            "a11y-stream", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null,
        )
    } catch (e: Exception) {
        System.err.println("est video: createVirtualDisplay failed: $e")
        codec.stop(); codec.release(); inputSurface.release()
        return "error: createVirtualDisplay: ${e.message}"
    }
    // Publish "the pipeline holds the projection's single VirtualDisplay" so
    // the screenshot path can wait for us to let go (and know when we are back).
    Screen.streaming = true
    // Start the encoder AFTER the display exists (MediaRecorder's order):
    // starting it first gave a display the encoder never pulled frames from.
    codec.start()
    System.err.println("est video: VirtualDisplay ${w}x${h} dpi=$dpi created")

    val info = MediaCodec.BufferInfo()
    var csd: ByteArray? = null
    // Force a keyframe periodically. A VirtualDisplay only emits a frame when
    // the screen CHANGES, so on a static screen no IDR is ever produced and a
    // newly connected viewer has nothing to decode from (it timed out with
    // "no first AU"). Requesting a sync frame + nudging the screen makes the
    // next encoded frame an IDR.
    var lastKeyReq = 0L
    val syncParams = android.os.Bundle().apply {
        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
    }
    try {
        var frames = 0
        var encodeNanos = 0L
        var lastReport = System.currentTimeMillis()
        while (!stop()) {
            val idx = codec.dequeueOutputBuffer(info, 20_000)
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
            if (idx < 0) {
                val now = System.currentTimeMillis()
                if (now - lastKeyReq > 1000) {
                    lastKeyReq = now
                    try { codec.setParameters(syncParams) } catch (_: Exception) {}
                }
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastReport >= 3000) {
                    System.err.println("est video: perf fps=$frames/3s avgEncodeUs=${if (frames > 0) encodeNanos / frames / 1000 else 0}")
                    frames = 0; encodeNanos = 0; lastReport = nowMs
                }
                continue
            }
            val t0 = System.nanoTime()
            val buf = codec.getOutputBuffer(idx)
            if (buf != null && info.size > 0) {
                val avcc = ByteArray(info.size)
                buf.get(avcc)
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    csd = annexB(avcc)
                } else {
                    val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    var au = annexB(avcc)
                    if (isKey && csd != null) {
                        val merged = ByteArray(csd!!.size + au.size)
                        System.arraycopy(csd!!, 0, merged, 0, csd!!.size)
                        System.arraycopy(au, 0, merged, csd!!.size, au.size)
                        au = merged
                    }
                    sink(au)
                }
            }
            encodeNanos += System.nanoTime() - t0
            frames++
            codec.releaseOutputBuffer(idx, false)
        }
    } finally {
        // Released: the screenshot path is now free to take the projection over.
        Screen.streaming = false
        vd.release()
        codec.stop(); codec.release(); inputSurface.release()
    }
    return "ok"
}

/** annexB converts AVCC (4-byte length-prefixed NAL units) to Annex-B. */
private fun annexB(avcc: ByteArray): ByteArray {
    // The emulator's encoder already emits Annex-B (00 00 00 01 ...). Feeding
    // that through the AVCC length-prefix parser below mangled every frame: it
    // read the start code as a 4-byte length (1) and then a bogus huge length,
    // so each AU collapsed to a few bytes (the "10-byte AUD" symptom).
    if (avcc.size >= 4 && avcc[0] == 0.toByte() && avcc[1] == 0.toByte() &&
        avcc[2] == 0.toByte() && avcc[3] == 1.toByte()) {
        return avcc
    }
    val out = java.io.ByteArrayOutputStream(avcc.size + 16)
    var i = 0
    while (i + 4 <= avcc.size) {
        var len = 0
        for (k in 0 until 4) len = (len shl 8) or (avcc[i + k].toInt() and 0xff)
        i += 4
        if (len <= 0 || i + len > avcc.size) break
        out.write(byteArrayOf(0, 0, 0, 1))
        out.write(avcc, i, len)
        i += len
    }
    return out.toByteArray()
}
