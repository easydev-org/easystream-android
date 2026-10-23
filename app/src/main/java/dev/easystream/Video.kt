package dev.easystream

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Video is the SINGLE capture+encode pipeline, shared by every est connection.
 *
 * A MediaProjection instance may call createVirtualDisplay only ONCE, so a
 * per-connection encoder both failed outright ("don't take multiple captures by
 * invoking MediaProjection#createVirtualDisplay multiple times on the same
 * instance") and made the daemon's cost scale with the number of viewers. This
 * starts one pipeline and fans each access unit out to every subscriber.
 */
object Video {
    private val subs = CopyOnWriteArrayList<(ByteArray) -> Unit>()
    @Volatile private var started = false
    /// Set by restartForConfig: the pipeline must drop the current projection and
    /// build a new one at the current VideoConfig geometry.
    private val restart = java.util.concurrent.atomic.AtomicBoolean(false)
    // The last IDR access unit (already carries SPS/PPS). Replayed to a new
    // subscriber so it can decode immediately: a VirtualDisplay only emits a
    // frame when the screen CHANGES, so a late joiner on a static screen would
    // otherwise wait forever ("stream unavailable").
    @Volatile private var lastKey: ByteArray? = null

    private fun hasIDR(au: ByteArray): Boolean {
        var i = 0
        while (i + 4 < au.size) {
            if (au[i] == 0.toByte() && au[i+1] == 0.toByte() && au[i+2] == 1.toByte()) {
                if ((au[i+3].toInt() and 0x1f) == 5) return true
                i += 3
            } else i++
        }
        return false
    }

    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        thread(name = "est-video") {
            // Wait for the MediaProjection grant (ControlService sets it), then
            // run ONE encoder for the life of the process.
            while (true) {
                // Wait for the projection to be READY (onCapturedContentResize),
                // not merely granted: creating the VirtualDisplay while the
                // consent flow was still finishing produced a display the
                // encoder got no frames from.
                if (Screen.projection == null) { Thread.sleep(200); continue }
                // Give the consent flow a moment to finish before creating the
                // VirtualDisplay (the working MediaRecorder path waited ~1.5 s).
                Thread.sleep(1500)
                try {
                    // scale = 1: encode at the FULL device resolution (1920x1080)
                    // so the streamed frame and the input coordinate space match.
                    // With scale = 2 the video was 960x540 and every tap landed at
                    // half the intended position.
                    // Geometry and bitrate come from VideoConfig. scale = 1 (the
                    // device size) is the CAPTURE size; the presets ask for an
                    // OUTPUT size, and on Android the VirtualDisplay must be
                    // created at exactly that size, so it is passed explicitly.
                    val (devW, devH, devDpi) = Screen.encoderSizePublic(1) ?: Triple(1920, 1080, 320)
                    VideoConfig.setCapture(devW, devH)
                    val (ow, oh) = (VideoConfig.outputW to VideoConfig.outputH)
                    val encW = if (ow != 0) ow else devW
                    val encH = if (oh != 0) oh else devH
                    VideoConfig.setActiveEncoder(VideoConfig.ENC_MEDIACODEC)
                    System.err.println(
                        "est video: encoder opened " + encW + "x" + encH +
                        " fps=" + VideoConfig.frameRate + " br=" + VideoConfig.bitRate +
                        " gen=" + VideoConfig.generationNow)
                    restart.set(false)
                    val r = streamAnnexBAt(
                        encW, encH, devDpi,
                        VideoConfig.frameRate, VideoConfig.bitRate,
                        stop = { restart.get() },
                    ) { au ->
                        if (hasIDR(au)) lastKey = au
                        for (s in subs) {
                            try { s(au) } catch (_: Exception) {}
                        }
                    }
                    System.err.println("est video: pipeline ended ($r)")
                } catch (e: Exception) {
                    System.err.println("est video: ${e.message}")
                }
                // Either the projection was replaced, or restartForConfig asked
                // for a new geometry. Screen.releaseDisplayPublic() in
                // streamAnnexBAt has already freed the VirtualDisplay; the loop
                // re-creates it at the CURRENT VideoConfig size on the next pass.
                System.err.println("est video: rebuilding pipeline (restart=" + restart.get() + ")")
                Thread.sleep(600)
            }
        }
    }

    /// restartForConfig asks the pipeline to rebuild at the new geometry.
    ///
    /// Android cannot do this in place: MediaProjection#createVirtualDisplay may
    /// be called exactly once per token and releasing the VirtualDisplay
    /// invalidates the token, so a new size means a new projection. The loop in
    /// start() re-creates it on its next iteration; `generation` is how it
    /// notices, and it also means the caller does not have to synchronise with
    /// the encoder thread.
    fun restartForConfig() {
        restart.set(true)
    }

    /** subscribe adds a sink; the returned lambda unsubscribes. */
    fun subscribe(sink: (ByteArray) -> Unit): () -> Unit {
        // Replay the most recent keyframe so this viewer can start decoding now.
        lastKey?.let { k -> try { sink(k) } catch (_: Exception) {} }
        subs.add(sink)
        start()
        return { subs.remove(sink) }
    }
}
