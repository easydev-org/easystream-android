package dev.easystream

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * MainActivity requests the two grants the bridge needs and starts the control
 * service. It is deliberately minimal: an app that runs headless once the
 * grants are in place.
 */
class MainActivity : ComponentActivity() {
    // startActivityForResult/onActivityResult are DEPRECATED and no longer
    // deliver a result on Android 14+, so the projection data never arrived and
    // ControlService never got a MediaProjection (the stream stayed empty).
    // registerForActivityResult is the supported path.
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val i = Intent(this, ControlService::class.java).apply {
                action = ControlService.ACTION_START
                putExtra(ControlService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ControlService.EXTRA_RESULT_DATA, data)
            }
            startForegroundService(i)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        val info = TextView(this).apply {
            text = "a11y bridge\n\n1. Enable the accessibility service\n2. Grant screen capture\n3. Start the control service"
        }
        val enableA11y = Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        val grantCapture = Button(this).apply {
            text = "Grant Screen Capture + Start"
            setOnClickListener { requestProjection() }
        }
        layout.addView(info)
        layout.addView(enableA11y)
        layout.addView(grantCapture)
        setContentView(layout)

        // Auto-request the projection grant on launch so a headless/automated
        // setup needs no tap (the worker drives everything else).
        if (intent?.getBooleanExtra("autoGrant", false) == true) {
            grantCapture.postDelayed({ requestProjection() }, 400)
        }
    }

    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Android 14+ (API 34) shows an extra "share a single app or the whole
        // screen?" selector when createScreenCaptureIntent() is called with no
        // config. A headless sandbox cannot answer that, so request the DEFAULT
        // display explicitly — the consent dialog then goes straight to
        // Start/Cancel, which we can auto-accept.
        // createConfigForDefaultDisplay() captures the WHOLE screen and skips the
        // Android 14 "capture range" selector, so with PROJECT_MEDIA allowed via
        // appops the whole grant is automatic — no UI taps at all. (The earlier
        // "AUD only" result was the annexB() parsing bug, not this config.)
        val intent = if (android.os.Build.VERSION.SDK_INT >= 34) {
            mpm.createScreenCaptureIntent(
                android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }
}
