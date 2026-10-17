# easystream-android

The **Android** easystream daemon: an APK whose accessibility service + control
service own the screen (MediaProjection/MediaCodec), input (AccessibilityService
gestures) and the accessibility tree, and expose:

* **est://** on `127.0.0.1:48079` — the multiplexed video/input/clipboard/a11y
  stream the `agent-worker` forwards (see `EST.md` in `easydev-org/easystream`).
* **HTTP** on `0.0.0.0:48078` — the web UI + the `/a11y/*` API (see `A11Y_HTTP.md`).

## Build

```sh
gradle assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
```

Package id: `dev.easystream`.

## Audio (system audio capture)

`Hello.hasAudio` is 1 when audio capture is available, and `0x05` Opus frames
carry 20 ms packets to viewers that send `0x06 AudioReq` (see `EST.md`).

Capture uses **`AudioPlaybackCapture`** (Android 10+): the control service
builds an `AudioRecord` from an `AudioPlaybackCaptureConfiguration` on the
`MediaProjection` token, so it records the audio other apps are playing.
Encoding uses the platform **`MediaCodec` Opus encoder** — no bundled codec.

Requirements:

* the `RECORD_AUDIO` permission (declared in the manifest),
* a granted **MediaProjection** (the same one used for screen capture), and
* on Android 14+ an app that has *not* opted out of playback capture.

The emulator exposes a real audio device, so this path works in the sandbox: a
viewer receives continuous `0x05` packets (≈540 per 10 s). If the projection is
not granted the daemon advertises `hasAudio = 0`.

## Keyboard input (IME)

Injecting keys needs an `InputMethodService`: `input keyevent` requires
`INJECT_EVENTS` (shell/system only), but an IME may send events through its
`InputConnection`. The bundled headless IME (`EasyIme`) must therefore be both
**enabled and selected**, otherwise typing falls back to a slow per-character
`ACTION_SET_TEXT` (dropping characters) and Backspace does nothing. The app does
this on launch; a sandbox that starts the service another way can do it with:

```sh
adb shell ime enable dev.easystream/.EasyIme
adb shell ime set    dev.easystream/.EasyIme
```
