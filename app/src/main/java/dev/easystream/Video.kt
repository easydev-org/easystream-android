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
                    val r = streamAnnexB(fps = 30, scale = 1, stop = { false }) { au ->
                        if (hasIDR(au)) lastKey = au
                        for (s in subs) {
                            try { s(au) } catch (_: Exception) {}
                        }
                    }
                    System.err.println("est video: pipeline ended ($r)")
                } catch (e: Exception) {
                    System.err.println("est video: ${e.message}")
                }
                Thread.sleep(1000)   // a projection was replaced; retry
            }
        }
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
