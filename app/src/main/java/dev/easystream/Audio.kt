package dev.easystream

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Build
import java.nio.ByteBuffer

/**
 * Audio captures SYSTEM playback with AudioPlaybackCapture (Android 10+) and
 * encodes it to Opus with MediaCodec — no third-party library.
 *
 * One pipeline serves the whole process (like Video); every est connection that
 * turns audio on subscribes to the same encoded packets.
 */
object Audio {
    const val RATE = 48_000
    const val CHANNELS = 2
    const val FRAME_MS = 20

    private var started = false
    private var rec: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var sink: ((ByteArray) -> Unit)? = null
    @Volatile private var running = false

    /** params reports the audio parameters advertised in Hello. */
    fun params(): AudioParams {
        // Present when the OS supports playback capture (API 29+).
        val ok = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        return AudioParams(ok, RATE, CHANNELS.toByte(), FRAME_MS.toByte())
    }

    data class AudioParams(val present: Boolean, val rate: Int, val channels: Byte, val ms: Byte)

    /** start begins capture once; `mp` is the MediaProjection grant. */
    @SuppressLint("MissingPermission")
    fun subscribe(mp: MediaProjection?, sink: (ByteArray) -> Unit) {
        this.sink = sink
        if (started) return
        started = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || mp == null) {
            android.util.Log.w("audio", "playback capture unavailable")
            return
        }
        Thread {
            try {
                val cfg = AudioPlaybackCaptureConfiguration.Builder(mp)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val fmt = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
                val minBuf = AudioRecord.getMinBufferSize(
                    RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                val r = AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(minBuf * 2)
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build()
                rec = r
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, CHANNELS)
                format.setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
                val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
                c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                c.start()
                codec = c
                running = true
                r.startRecording()
                android.util.Log.i("audio", "playback capture started")
                loop(r, c)
            } catch (e: Exception) {
                android.util.Log.w("audio", "capture failed: $e")
                running = false
            }
        }.apply { name = "audio" }.start()
    }

    private fun loop(r: AudioRecord, c: MediaCodec) {
        val pcm = ByteArray(FRAME_MS * RATE / 1000 * CHANNELS * 2)  // one 20 ms frame
        val info = MediaCodec.BufferInfo()
        while (running) {
            val n = r.read(pcm, 0, pcm.size)
            if (n <= 0) continue
            val inIdx = c.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                val ib: ByteBuffer = c.getInputBuffer(inIdx)!!
                ib.clear(); ib.put(pcm, 0, n)
                c.queueInputBuffer(inIdx, 0, n, System.nanoTime() / 1000, 0)
            }
            var outIdx = c.dequeueOutputBuffer(info, 0)
            while (outIdx >= 0) {
                if (info.size > 0) {
                    val ob: ByteBuffer = c.getOutputBuffer(outIdx)!!
                    val out = ByteArray(info.size)
                    ob.position(info.offset); ob.limit(info.offset + info.size); ob.get(out)
                    sink?.invoke(out)
                }
                c.releaseOutputBuffer(outIdx, false)
                outIdx = c.dequeueOutputBuffer(info, 0)
            }
        }
    }

    fun stop() {
        running = false
        runCatching { rec?.stop() }; runCatching { rec?.release() }; rec = null
        runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
        started = false
    }
}
