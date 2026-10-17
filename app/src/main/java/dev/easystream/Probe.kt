package dev.easystream

/**
 * Capability probe.
 *
 * Mirrors the Linux daemon's /capabilities endpoint (see easydev-org/easystream
 * A11Y_HTTP.md) so a viewer or the Go worker can ask "which path did each
 * capability resolve to?" without reading logs.
 *
 * Most answers on Android are fixed, but the AUDIO one depends on the OS version
 * (AudioPlaybackCapture needs API 29+), which is why it is reported here rather
 * than hard-coded.
 */
object Probe {
    /**
     * toJSON renders the same shape the Linux daemon emits. The audio answer
     * comes from Audio.audioPath() so there is one source of truth.
     */
    fun toJSON(): String {
        val notes = listOf(
            "android: MediaProjection/MediaCodec + AccessibilityService + ClipboardManager",
            "input: AccessibilityService gestures + the bundled headless IME",
            "audio: AudioPlaybackCapture + MediaCodec Opus (API 29+)",
        ).joinToString(",") { "\"" + it.replace("\"", "'") + "\"" }
        return "{\"session\":\"Android\",\"capture\":\"MediaProjection\"," +
            "\"input\":\"AccessibilityService\",\"a11y\":\"AccessibilityService\"," +
            "\"clipboard\":\"ClipboardManager\",\"audio\":\"${Audio.audioPath()}\"," +
            "\"notes\":[$notes]}"
    }
}
