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
                    }
                    override fun onCapturedContentResize(width: Int, height: Int) {
                        System.err.println("est: captured content resize ${width}x$height")
                    }
                }, null)
                Screen.projection = mp
                System.err.println("est: MediaProjection set")
            }
        }
        if (est == null) {
            est = Est(PORT).also { it.start() }
        }
        if (http == null) {
            http = Http(applicationContext, HTTP_PORT, PORT).also { it.start() }
        }
        return START_STICKY
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
