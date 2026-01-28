package com.peach.android_screen_share

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat



class MainActivity : AppCompatActivity() {

    private val REQUEST_CAPTURE = 1001
    private lateinit var mediaProjectionManager: MediaProjectionManager

    // 🔔 Notification permission launcher (CRITICAL for IQOO)
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Log.e("MainActivity", "Notification permission denied")
                openNotificationSettings()
            } else {
                Log.d("MainActivity", "Notification permission granted")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

//        requestNotificationPermission()
        // 🔋 Ask battery optimization ignore (good, keep this)
        requestIgnoreBatteryOptimizations()

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            ensureNotificationPermission()
            startScreenCaptureRequest()
        }

        findViewById<Button>(R.id.btnStop)?.setOnClickListener {
            stopScreenShareService()
        }
    }

    // ------------------------------------------------
    // 🔔 Notification permission (MANDATORY)
    // ------------------------------------------------
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                notificationPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
                )
            }
        }
    }

    private fun openNotificationSettings() {
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            }
            startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    // ------------------------------------------------
    // 🎥 Screen capture
    // ------------------------------------------------
    private fun startScreenCaptureRequest() {
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, REQUEST_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CAPTURE && resultCode == Activity.RESULT_OK && data != null) {

            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            Log.d("MainActivity", "Screen share started")
        }
    }

    // ------------------------------------------------
    // 🔋 Battery optimization
    // ------------------------------------------------
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val pkg = packageName

        if (!pm.isIgnoringBatteryOptimizations(pkg)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$pkg")
            }
            startActivity(intent)
        }
    }

    // ------------------------------------------------
    // ⛔ Stop service
    // ------------------------------------------------
    private fun stopScreenShareService() {
        try {
            stopService(Intent(this, ScreenCaptureService::class.java))
            Log.d("MainActivity", "Stop service request sent")
        } catch (e: Exception) {
            Log.e("MainActivity", "Stop error: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScreenShareService()
    }

}
