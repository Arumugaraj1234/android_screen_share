package com.mip.mdm.admin

import android.app.Activity
import android.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Call this in Activity.onCreate() BEFORE setContentView()
 * to make the status bar transparent and match the dark UI.
 */
object StatusBarHelper {

    fun applyDarkStatusBar(activity: Activity) {
        val window = activity.window

        // Draw content edge-to-edge (under status bar)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // Status bar color — matches top band gradient #1A2236
        window.statusBarColor = Color.parseColor("#1A2236")

        // Navigation bar — matches deep background #0D0F14
        window.navigationBarColor = Color.parseColor("#0D0F14")

        // Force white (light) icons in the dark status bar
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false       // white icons
        insetsController.isAppearanceLightNavigationBars = false   // white nav icons
    }
}