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
