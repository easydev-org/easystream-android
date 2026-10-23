package dev.easystream

import java.util.concurrent.atomic.AtomicInteger

/**
 * Runtime video configuration: geometry, frame rate, bitrate and encoder.
 *
 * This is the Android side of the EST.md reconfiguration contract. The encoder
 * used to be created with literals (KEY_BIT_RATE = 12_000_000, fps = 30), so a
 * viewer could not trade quality for bandwidth at all.
 *
 * ANDROID IS THE HARD CASE. MediaProjection#createVirtualDisplay may be called
 * exactly ONCE per token: releasing the VirtualDisplay invalidates the token,
 * so a new geometry cannot be reached by reconfiguring in place. The pipeline
 * therefore tears down and re-creates the projection through ControlService,
 * which is why a reconfiguration also costs every viewer one IDR and a brief
 * gap rather than a seamless switch.
 *
 * Two geometries are kept apart: CAPTURE (the display) and OUTPUT (the encoder).
 * Every rule is a REFUSAL, never a clamp.
 */
object VideoConfig {
    /** Encoder codes, matching the EST.md video block. */
    const val ENC_UNKNOWN = 0
    const val ENC_X264 = 1
    const val ENC_MEDIACODEC = 5

    private const val MIN_BITRATE = 100_000
    private const val MAX_BITRATE = 50_000_000
    private const val MIN_DIM = 64
    private const val MAX_DIM = 7680
    private const val MIN_FPS = 1
    private const val MAX_FPS = 120

    @Volatile private var capW = 0
    @Volatile private var capH = 0
    @Volatile private var outW = 0
    @Volatile private var outH = 0
    @Volatile private var fps = 30
    @Volatile private var bitrate = 12_000_000
    @Volatile private var requestedEnc = 0
    @Volatile private var activeEnc = ENC_UNKNOWN
    /** Bumped on every accepted change so the pump notices cheaply. */
    private val generation = AtomicInteger(0)

    /** A validated change request, as parsed from a ConfigSet frame. */
    data class Patch(
        val width: Int? = null,
        val height: Int? = null,
        val fps: Int? = null,
        val bitrate: Int? = null,
        val encoder: Int? = null,
    )

    @Synchronized
    fun setCapture(w: Int, h: Int) {
        capW = w; capH = h
        if (outW == 0) { outW = w; outH = h }
    }

    val captureW: Int @Synchronized get() = capW
    val captureH: Int @Synchronized get() = capH
    val outputW: Int @Synchronized get() = outW
    val outputH: Int @Synchronized get() = outH
    val frameRate: Int @Synchronized get() = fps
    val bitRate: Int @Synchronized get() = bitrate
    val generationNow: Int get() = generation.get()
    val activeEncoder: Int @Synchronized get() = activeEnc

    @Synchronized
    fun setActiveEncoder(e: Int) { activeEnc = e }

    /**
     * Apply a patch, validating it against the capture source. Returns a
     * refusal reason and leaves the configuration untouched when any field is
     * unacceptable: a partial apply would leave the encoder and the Hello
     * describing different streams.
     */
    @Synchronized
    fun apply(p: Patch): String? {
        var w = outW
        var h = outH
        // Geometry is all-or-nothing: changing only width would distort the
        // aspect ratio, and a scaling client always sends both.
        if (p.width != null || p.height != null) {
            w = p.width ?: outW
            h = p.height ?: outH
            for (v in intArrayOf(w, h)) {
                if (v < MIN_DIM || v > MAX_DIM) return "size $v outside $MIN_DIM..$MAX_DIM"
                if (v % 2 != 0) return "size $v is odd (H.264 4:2:0 needs even)"
            }
            // Upscaling is refused rather than clamped.
            if (capW != 0 && capH != 0 && (w > capW || h > capH)) {
                return "size ${w}x${h} exceeds capture ${capW}x${capH}"
            }
        }
        val newFps = p.fps ?: fps
        if (newFps < MIN_FPS || newFps > MAX_FPS) return "fps $newFps outside $MIN_FPS..$MAX_FPS"
        val newBitrate = p.bitrate ?: bitrate
        if (newBitrate < MIN_BITRATE || newBitrate > MAX_BITRATE) {
            return "bitrate $newBitrate outside $MIN_BITRATE..$MAX_BITRATE"
        }
        p.encoder?.let { e ->
            if (e != 0 && e != ENC_X264 && e != ENC_MEDIACODEC) {
                return "encoder $e not available on android"
            }
        }
        outW = w; outH = h; fps = newFps; bitrate = newBitrate
        p.encoder?.let { requestedEnc = it }
        generation.incrementAndGet()
        return null
    }

    /** The Hello video block (EST.md offsets 15..23). */
    @Synchronized
    fun helloBlock(): ByteArray {
        val b = ByteArray(8)
        b[0] = 1 // hasVideoConfig
        b[1] = activeEnc.toByte()
        var flags = 1 // reconfiguration supported
        // MediaCodec is a platform encoder, so bit1 is always set.
        if (activeEnc == ENC_MEDIACODEC) flags = flags or 2
        b[2] = flags.toByte()
        b[3] = ((bitrate ushr 24) and 0xff).toByte()
        b[4] = ((bitrate ushr 16) and 0xff).toByte()
        b[5] = ((bitrate ushr 8) and 0xff).toByte()
        b[6] = (bitrate and 0xff).toByte()
        b[7] = minOf(255, fps * 2).toByte()
        return b
    }
}
