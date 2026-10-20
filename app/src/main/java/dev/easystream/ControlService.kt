package dev.easystream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder

/**
 * ControlService holds the MediaProjection grant and runs the local HTTP
 * control server as a foreground service, so the bridge survives while the app
 * is backgrounded.
 */
class ControlService : Service() {
    companion object {
        const val ACTION_START = "dev.easystream.START"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val PORT = 48079
        const val HTTP_PORT = 48078

        @Volatile var instance: ControlService? = null
            private set
    }

    // Set while a re-acquire thread is in flight, so a burst of onStop callbacks
    // cannot queue up several activity launches.
    @Volatile private var reacquiring = false

    // The est daemon runs INSIDE the emulator; the Go worker talks to it over
    // 127.0.0.1:PORT exactly like the macOS/Windows daemons.
    private var est: Est? = null
    private var http: Http? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        startForegroundCompat()
        if (intent?.action == ACTION_START) {
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            @Suppress("DEPRECATION")
            val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            if (data != null) {
                val mpm = getSystemService(MediaProjectionManager::class.java)
                val mp: MediaProjection = mpm.getMediaProjection(code, data)
                mp.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        System.err.println("est: MediaProjection onStop")
                        Screen.projection = null
                        // The projection can be revoked at any time — the system
                        // does it when the app leaves the foreground, and it also
                        // happens on a configuration change or when a screen
                        // recorder takes over. Nothing in the daemon used to bring
                        // it back, so the consequences were silent and confusing:
                        // the process stayed up, est kept listening on 48079/48078,
                        // /capabilities still answered, the WS handshake succeeded
                        // and Hello arrived — and then not a single video frame, for
                        // ever. The panel showed a black desktop with no error
                        // anywhere, because from its point of view the stream was
                        // fine.
                        //
                        // Re-request the grant. It has to go through MainActivity
                        // because createScreenCaptureIntent() needs an Activity for
                        // its result, and this is a Service. `autoGrant` is passed
                        // so MainActivity asks immediately without waiting for a tap;
                        // with PROJECT_MEDIA allowed via appops the whole consent is
                        // automatic (see MainActivity.requestProjection).
                        scheduleReacquire()
                    }
                    override fun onCapturedContentResize(width: Int, height: Int) {
                        System.err.println("est: captured content resize ${width}x$height")
                    }
                }, null)
                Screen.projection = mp
                System.err.println("est: MediaProjection set")
            } else {
                // START_STICKY restart: the system recreates the service with a
                // null intent (or with our action but no result data), so there is
                // no projection grant to install. Everything else still comes up —
                // est and the HTTP server bind 48079/48078, /capabilities answers,
                // the WS handshake succeeds — and then the stream carries zero
                // video frames for ever, with no error anywhere. Observed exactly
                // that: process 4821 logged "MediaProjection set" and
                // "video: perf fps=55", then the process was replaced by 6008,
                // which only ever logged "est conn: null".
                //
                // So ask for a new grant. This path does not go through
                // MediaProjection.Callback.onStop, which is why hooking onStop
                // alone was not enough.
                System.err.println("est: no projection grant on (re)start; re-acquiring")
                scheduleReacquire()
            }
        }
        if (est == null) {
            est = Est(PORT).also { it.start() }
        }
        if (http == null) {
            http = Http(applicationContext, HTTP_PORT, PORT).also { it.start() }
        }
        // Belt and braces: whatever route brought us here (ACTION_START with no
        // data, a sticky restart with a null intent, a plain startService), the
        // service is useless without a projection. If none is installed, ask.
        if (Screen.projection == null) {
            scheduleReacquire()
        }
        return START_STICKY
    }

    /**
     * scheduleReacquire asks MainActivity for a fresh screen-capture grant.
     *
     * createScreenCaptureIntent() must be launched from an Activity (it returns
     * its result there), so a Service cannot do it: it has to bring MainActivity
     * up with `autoGrant`, which makes the activity request the projection
     * immediately and hand the result back to this service.
     *
     * Retried a few times with a growing delay rather than once. A revocation
     * often arrives while the system is busy with the very transition that caused
     * it (app going background, a recorder taking over), and a single immediate
     * attempt tends to fail for that reason alone.
     *
     * Guarded so a storm of onStop callbacks cannot pile up a queue of launches.
     */
    private fun scheduleReacquire() {
        if (reacquiring) {
            System.err.println("est: projection re-acquire already pending")
            return
        }
        reacquiring = true
        Thread {
            for (attempt in 1..5) {
                try {
                    Thread.sleep(if (attempt == 1) 1200L else 4000L)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                // Someone else already got us a projection; stop trying.
                if (Screen.projection != null) {
                    reacquiring = false
                    System.err.println("est: projection re-acquired (attempt $attempt)")
                    return@Thread
                }
                System.err.println("est: re-acquiring projection (attempt $attempt)")
                try {
                    val i = Intent(this, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        )
                        putExtra("autoGrant", true)
                    }
                    startActivity(i)
                } catch (e: Exception) {
                    System.err.println("est: re-acquire launch failed: ${e.message}")
                }
            }
            reacquiring = false
        }.apply { isDaemon = true }.start()
    }

    private fun startForegroundCompat() {
        val channelId = "a11y"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "a11y bridge", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notif: Notification = Notification.Builder(this, channelId)
            .setContentTitle("a11y bridge")
            .setContentText("Local control on 127.0.0.1:$PORT")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                1, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(1, notif)
        }
    }

    override fun onDestroy() {
        est?.stop()
        est = null
        if (instance === this) instance = null
        super.onDestroy()
    }
}
