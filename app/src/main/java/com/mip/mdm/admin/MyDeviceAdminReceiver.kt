package com.mip.mdm.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class MyDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.d(TAG, "Device Admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.d(TAG, "Device Admin disabled")
    }

    companion object {
        private const val TAG = "DeviceAdmin"
    }
}