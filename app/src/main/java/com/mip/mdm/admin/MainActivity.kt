package com.mip.mdm.admin

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.view.WindowManager
import android.app.admin.DevicePolicyManager
import android.telephony.TelephonyManager
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private var captureInProgress = false   // 🔒 CRITICAL LOCK
    private lateinit var deviceId: String

    // --------------------------------------------------
    // 🔔 Notification permission (Android 13+)
    // --------------------------------------------------
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                requestScreenCapture()
            } else {
                openNotificationSettings()
            }
        }

    // --------------------------------------------------
    // 🎥 Screen capture permission (MediaProjection)
    // --------------------------------------------------
    private val screenCaptureLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            captureInProgress = false

            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startScreenShareService(result.resultCode, result.data!!)
            } else {
                Log.w("MainActivity", "Screen capture permission denied")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        requestIgnoreBatteryOptimizations()

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (captureInProgress) {
                Log.w("MainActivity", "Capture already in progress, ignoring")
                return@setOnClickListener
            }

            captureInProgress = true
            ensureNotificationPermissionAndStart()
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_PHONE_STATE),
                1001
            )
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopScreenShareService()
        }
        val txtId = findViewById<TextView>(R.id.txtEnterpriseId)

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val enterpriseId = getEnterpriseDeviceId(this)
//
//
//        deviceId = "${Build.MANUFACTURER}-${Build.MODEL}-${System.currentTimeMillis()}-${enterpriseId}"

        val deviceId= getIMEI1(this)

        txtId.text = getString(R.string.enterprise_id, deviceId)
    }

//    @SuppressLint("MissingPermission")
//    fun getIMEI(context: Context): String? {
//
//        val telephonyManager =
//            context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
//
//        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            telephonyManager.imei   // or telephonyManager.getImei()
//        } else {
//            // telephonyManager.deviceId
//            "0"
//        }
//    }


//    @SuppressLint("MissingPermission")
//    fun getIMEI1(context: Context): String? {
//
//        val telephonyManager =
//            context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
//
//        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            telephonyManager.getImei(0)  // SIM slot 0
//        } else {
//            telephonyManager.imei
//            //telephonyManager.deviceId
//        }
//    }

    @SuppressLint("MissingPermission", "HardwareIds")
    fun getIMEI1(context: Context): String {

        return try {

            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                val imei1 = tm.getImei(0)
                val imei2 = tm.getImei(1)

                imei1 ?: imei2

            } else {
                tm.deviceId
            }

        } catch (e: Exception) {
            ""
        }
    }

    // --------------------------------------------------
    // 🔔 Permission flow
    // --------------------------------------------------
    private fun ensureNotificationPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                notificationPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
                )
                return
            }
        }

        requestScreenCapture()
    }

    // --------------------------------------------------
    // 🎥 Request MediaProjection
    // --------------------------------------------------
    private fun requestScreenCapture() {
        val intent = projectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(intent)
    }

    // --------------------------------------------------
    // ▶ Start foreground service (ONE TIME ONLY)
    // --------------------------------------------------
    private fun startScreenShareService(
        resultCode: Int,
        data: Intent
    ) {
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("data", data)
        }

        ContextCompat.startForegroundService(this, serviceIntent)
        Log.d("MainActivity", "Screen share service started")
    }

    // --------------------------------------------------
    // ⛔ Stop service
    // --------------------------------------------------
    private fun stopScreenShareService() {
        stopService(Intent(this, ScreenCaptureService::class.java))
        Log.d("MainActivity", "Screen share service stopped")
    }

    // --------------------------------------------------
    // 🔋 Battery optimization (OEM survival)
    // --------------------------------------------------
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val pkg = packageName

        if (!pm.isIgnoringBatteryOptimizations(pkg)) {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$pkg"))
                )
            } catch (e: Exception) {
                Log.w("MainActivity", "Battery optimization request failed")
            }
        }
    }

    // --------------------------------------------------
    // 🔔 Notification settings fallback
    // --------------------------------------------------
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

    @Suppress("DEPRECATION")
    fun getEnterpriseDeviceId(context: Context): String {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                    as DevicePolicyManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                dpm.enrollmentSpecificId ?: "ENROLLMENT_ID_NULL"

            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    Build.getSerial()
                } catch (e: SecurityException) {
                    "SERIAL_PERMISSION_DENIED"
                }

            } else {
                Build.SERIAL ?: "SERIAL_NULL"
            }

        } catch (e: Exception) {
            "ID_UNAVAILABLE"
        }
    }


    override fun onDestroy() {
        stopScreenShareService()
        super.onDestroy()
    }
}
