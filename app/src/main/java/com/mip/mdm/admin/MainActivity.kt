package com.mip.mdm.admin

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager

    // UI refs
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtEnterpriseId: TextView
    private lateinit var dotCore: View
    private lateinit var dotPulse: View
    private lateinit var badgeLive: TextView

    // State
    @Volatile private var captureInProgress = false
    private var pulseAnim: Animation? = null

    // ──────────────────────────────────────────────────────────
    // Permission launchers
    // ──────────────────────────────────────────────────────────

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                requestScreenCapture()
            } else {
                captureInProgress = false
                setStatus(ScreenState.IDLE)
                openNotificationSettings()
            }
        }

    private val screenCaptureLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            captureInProgress = false
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startScreenShareService(result.resultCode, result.data!!)
                setStatus(ScreenState.ACTIVE)
            } else {
                Log.w(TAG, "Screen capture permission denied or cancelled")
                setStatus(ScreenState.IDLE)
            }
        }

    private val phoneStatePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            updateDeviceIdDisplay()
        }

    // ──────────────────────────────────────────────────────────
    // onCreate
    // ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StatusBarHelper.applyDarkStatusBar(this)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        btnStart        = findViewById(R.id.btnStart)
        btnStop         = findViewById(R.id.btnStop)
        txtStatus       = findViewById(R.id.txtStatus)
        txtEnterpriseId = findViewById(R.id.txtEnterpriseId)
        dotCore         = findViewById(R.id.dotCore)
        dotPulse        = findViewById(R.id.dotPulse)
        badgeLive       = findViewById(R.id.badgeLive)

        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setStatus(ScreenState.IDLE)
        requestIgnoreBatteryOptimizations()

        btnStart.setOnClickListener {
            if (captureInProgress) return@setOnClickListener
            captureInProgress = true
            setStatus(ScreenState.CONNECTING)
            ensureNotificationPermissionAndStart()
        }

        btnStop.setOnClickListener {
            stopScreenShareService()
            setStatus(ScreenState.IDLE)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            phoneStatePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
        } else {
            updateDeviceIdDisplay()
        }
    }

    // ──────────────────────────────────────────────────────────
    // UI state machine
    // ──────────────────────────────────────────────────────────

    enum class ScreenState { IDLE, CONNECTING, ACTIVE }

    private fun setStatus(state: ScreenState) {
        when (state) {
            ScreenState.IDLE -> {
                txtStatus.text = getString(R.string.status_idle)
                txtStatus.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                badgeLive.visibility = View.GONE
                setDotColor(R.color.dot_idle)
                stopPulse()
                btnStart.isEnabled = true
                btnStart.alpha = 1f
                btnStop.isEnabled = false
                btnStop.alpha = 0.4f
            }
            ScreenState.CONNECTING -> {
                txtStatus.text = getString(R.string.status_connecting)
                txtStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_blue))
                badgeLive.visibility = View.GONE
                setDotColor(R.color.accent_blue)
                startPulse(R.color.accent_blue)
                btnStart.isEnabled = false
                btnStart.alpha = 0.5f
                btnStop.isEnabled = false
                btnStop.alpha = 0.4f
            }
            ScreenState.ACTIVE -> {
                txtStatus.text = getString(R.string.status_active)
                txtStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                badgeLive.visibility = View.VISIBLE
                setDotColor(R.color.accent_green)
                startPulse(R.color.accent_green)
                btnStart.isEnabled = false
                btnStart.alpha = 0.4f
                btnStop.isEnabled = true
                btnStop.alpha = 1f
            }
        }
    }

    private fun setDotColor(colorRes: Int) {
        val color = ContextCompat.getColor(this, colorRes)
        (dotCore.background as? GradientDrawable)?.setColor(color)
    }

    private fun startPulse(colorRes: Int) {
        val color = ContextCompat.getColor(this, colorRes)
        (dotPulse.background as? GradientDrawable)?.setColor(color)

        val scale = ScaleAnimation(
            0.6f, 2.2f, 0.6f, 2.2f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        )
        val fade = AlphaAnimation(0.7f, 0f)
        val set = AnimationSet(true).apply {
            addAnimation(scale)
            addAnimation(fade)
            duration = 1400
            repeatCount = Animation.INFINITE
            repeatMode = Animation.RESTART
        }
        pulseAnim = set
        dotPulse.alpha = 1f
        dotPulse.startAnimation(set)
    }

    private fun stopPulse() {
        dotPulse.clearAnimation()
        dotPulse.alpha = 0f
        pulseAnim = null
    }

    // ──────────────────────────────────────────────────────────
    // Device ID
    // ──────────────────────────────────────────────────────────

    private fun updateDeviceIdDisplay() {
        txtEnterpriseId.text = resolveDeviceId()
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    fun resolveDeviceId(): String {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val imei = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    tm.getImei(0) ?: tm.getImei(1)
                } else {
                    @Suppress("DEPRECATION") tm.deviceId
                }
                if (!imei.isNullOrBlank()) return imei
            } catch (e: Exception) {
                Log.w(TAG, "IMEI unavailable: ${e.message}")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val id = dpm.enrollmentSpecificId
                if (!id.isNullOrBlank()) return id
            } catch (e: Exception) { /* continue */ }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val serial = Build.getSerial()
                if (!serial.isNullOrBlank() && serial != Build.UNKNOWN) return serial
            } catch (e: SecurityException) { /* continue */ }
        }

        @Suppress("DEPRECATION")
        val legacy = Build.SERIAL
        if (!legacy.isNullOrBlank() && legacy != Build.UNKNOWN) return legacy

        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        if (!androidId.isNullOrBlank()) return androidId

        return "UNKNOWN"
    }

    // ──────────────────────────────────────────────────────────
    // Permission flow
    // ──────────────────────────────────────────────────────────

    private fun ensureNotificationPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    // ──────────────────────────────────────────────────────────
    // Service control
    // ──────────────────────────────────────────────────────────

    private fun startScreenShareService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("data", data)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopScreenShareService() {
        stopService(Intent(this, ScreenCaptureService::class.java))
    }

    // ──────────────────────────────────────────────────────────
    // Battery optimisation
    // ──────────────────────────────────────────────────────────

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                Log.w(TAG, "Battery optimisation request: ${e.message}")
            }
        }
    }

    private fun openNotificationSettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (e: Exception) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    // ──────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────

    override fun onDestroy() {
        stopPulse()
        captureInProgress = false
        stopScreenShareService()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}