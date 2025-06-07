// android/app/src/main/kotlin/com/example/kem/BatteryOptimizationHelper.kt
package com.example.kem

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi

class BatteryOptimizationHelper {
    
    companion object {
        private const val TAG = "BatteryOptimizationHelper"
        
        /**
         * Request battery optimization exemption for the app
         */
        fun requestBatteryOptimizationExemption(activity: Activity): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent()
                val packageName = activity.packageName
                val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
                
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    try {
                        // ✅ Direct request to whitelist (most effective)
                        intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        intent.data = Uri.parse("package:$packageName")
                        activity.startActivity(intent)
                        
                        Log.d(TAG, "Requested battery optimization exemption for $packageName")
                        return true
                        
                    } catch (e: Exception) {
                        Log.w(TAG, "Direct battery optimization request failed, trying fallback", e)
                        
                        try {
                            // Fallback to battery settings page
                            intent.action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
                            activity.startActivity(intent)
                            return true
                        } catch (e2: Exception) {
                            Log.e(TAG, "Fallback battery optimization request also failed", e2)
                            return false
                        }
                    }
                } else {
                    Log.d(TAG, "Battery optimization already disabled for $packageName")
                    return true
                }
            } else {
                Log.d(TAG, "Battery optimization not needed on Android version < 23")
                return true
            }
        }
        
        /**
         * Check if battery optimization is disabled for the app
         */
        fun isOptimizationDisabled(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val isIgnoring = pm.isIgnoringBatteryOptimizations(context.packageName)
                Log.d(TAG, "Battery optimization status for ${context.packageName}: ${if (isIgnoring) "DISABLED" else "ENABLED"}")
                isIgnoring
            } else {
                Log.d(TAG, "Battery optimization check: true (not applicable on older versions)")
                true
            }
        }
        
        /**
         * Auto-request battery optimization exemption with user-friendly dialog
         */
        fun autoRequestWithDialog(activity: Activity, onResult: (Boolean) -> Unit) {
            if (isOptimizationDisabled(activity)) {
                onResult(true)
                return
            }
            
            // Show explanation dialog first
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                android.app.AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            } else {
                android.app.AlertDialog.Builder(activity)
            }
            
            builder.setTitle("Battery Optimization")
                .setMessage("To ensure the security scanner works properly in the background, please disable battery optimization for this app.")
                .setPositiveButton("Allow") { _, _ ->
                    val success = requestBatteryOptimizationExemption(activity)
                    onResult(success)
                }
                .setNegativeButton("Skip") { _, _ ->
                    onResult(false)
                }
                .setCancelable(false)
                .show()
        }
        
        /**
         * Periodically check and request battery optimization exemption
         */
        fun schedulePeriodicCheck(context: Context) {
            val prefs = context.getSharedPreferences("battery_optimization", Context.MODE_PRIVATE)
            val lastCheck = prefs.getLong("last_check", 0)
            val currentTime = System.currentTimeMillis()
            
            // Check every 24 hours
            if (currentTime - lastCheck > 24 * 60 * 60 * 1000) {
                prefs.edit().putLong("last_check", currentTime).apply()
                
                if (!isOptimizationDisabled(context)) {
                    Log.w(TAG, "Battery optimization detected - app may be killed")
                    // Could trigger a notification here to remind user
                    showBatteryOptimizationNotification(context)
                }
            }
        }
        
        /**
         * Show notification if battery optimization is enabled
         */
        private fun showBatteryOptimizationNotification(context: Context) {
            try {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                
                // Create notification channel for Android 8+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val channel = android.app.NotificationChannel(
                        "battery_optimization",
                        "Battery Optimization",
                        android.app.NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Notifications about battery optimization settings"
                    }
                    notificationManager.createNotificationChannel(channel)
                }
                
                // Create intent to open battery optimization settings
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                val pendingIntent = android.app.PendingIntent.getActivity(
                    context, 
                    0, 
                    intent, 
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                
                val notification = androidx.core.app.NotificationCompat.Builder(context, "battery_optimization")
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("Security Scanner")
                    .setContentText("Please disable battery optimization to ensure proper background operation")
                    .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent)
                    .addAction(
                        android.R.drawable.ic_menu_preferences,
                        "Open Settings",
                        pendingIntent
                    )
                    .build()
                
                notificationManager.notify(1001, notification)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error showing battery optimization notification", e)
            }
        }
        
        /**
         * Get battery optimization status for debugging
         */
        fun getBatteryOptimizationInfo(context: Context): Map<String, Any> {
            val info = mutableMapOf<String, Any>()
            
            info["android_version"] = Build.VERSION.SDK_INT
            info["package_name"] = context.packageName
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                info["is_ignoring_battery_optimizations"] = pm.isIgnoringBatteryOptimizations(context.packageName)
                info["is_power_save_mode"] = pm.isPowerSaveMode
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    info["is_interactive"] = pm.isInteractive
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info["location_power_save_mode"] = pm.locationPowerSaveMode
                }
            } else {
                info["battery_optimization_applicable"] = false
            }
            
            return info
        }
        
        /**
         * Check if device has aggressive battery optimization (OEM specific)
         */
        fun hasAggressiveBatteryOptimization(): Boolean {
            val manufacturer = Build.MANUFACTURER.lowercase()
            val aggressiveOEMs = listOf(
                "xiaomi", "huawei", "honor", "oppo", "vivo", "oneplus", 
                "samsung", "meizu", "asus", "sony", "lg"
            )
            
            val isAggressive = aggressiveOEMs.any { manufacturer.contains(it) }
            Log.d(TAG, "Manufacturer: $manufacturer, Aggressive battery management: $isAggressive")
            
            return isAggressive
        }
        
        /**
         * Get OEM-specific battery optimization settings intent
         */
        @RequiresApi(Build.VERSION_CODES.M)
        fun getOEMBatteryOptimizationIntent(context: Context): Intent? {
            val manufacturer = Build.MANUFACTURER.lowercase()
            val packageName = context.packageName
            
            return try {
                when {
                    manufacturer.contains("xiaomi") -> {
                        Intent().apply {
                            component = android.content.ComponentName(
                                "com.miui.securitycenter",
                                "com.miui.permcenter.autostart.AutoStartManagementActivity"
                            )
                        }
                    }
                    manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                        Intent().apply {
                            component = android.content.ComponentName(
                                "com.huawei.systemmanager",
                                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                            )
                        }
                    }
                    manufacturer.contains("oppo") -> {
                        Intent().apply {
                            component = android.content.ComponentName(
                                "com.coloros.safecenter",
                                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                            )
                        }
                    }
                    manufacturer.contains("vivo") -> {
                        Intent().apply {
                            component = android.content.ComponentName(
                                "com.vivo.permissionmanager",
                                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                            )
                        }
                    }
                    manufacturer.contains("oneplus") -> {
                        Intent().apply {
                            component = android.content.ComponentName(
                                "com.oneplus.security",
                                "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                            )
                        }
                    }
                    else -> {
                        // Fallback to standard Android battery optimization settings
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error creating OEM battery optimization intent", e)
                null
            }
        }
        
        /**
         * Show comprehensive battery optimization instructions
         */
        fun showComprehensiveInstructions(activity: Activity) {
            val manufacturer = Build.MANUFACTURER.lowercase()
            val instructions = when {
                manufacturer.contains("xiaomi") -> 
                    "1. Go to Security app\n2. Select 'Autostart'\n3. Enable autostart for this app\n4. Go to 'Battery saver'\n5. Add this app to 'No restrictions'"
                manufacturer.contains("huawei") || manufacturer.contains("honor") -> 
                    "1. Go to Phone Manager\n2. Select 'App launch'\n3. Find this app and enable 'Manage manually'\n4. Enable all options (Auto-launch, Secondary launch, Run in background)"
                manufacturer.contains("oppo") -> 
                    "1. Go to Settings > Battery > Battery Optimization\n2. Find this app and select 'Don't optimize'\n3. Go to Settings > Apps > Special Access > Autostart\n4. Enable autostart for this app"
                manufacturer.contains("vivo") -> 
                    "1. Go to Settings > Battery > Background App Refresh\n2. Find this app and enable it\n3. Go to Settings > More Settings > Permission Manager > Autostart\n4. Enable autostart for this app"
                manufacturer.contains("oneplus") -> 
                    "1. Go to Settings > Battery > Battery Optimization\n2. Find this app and select 'Don't optimize'\n3. Go to Settings > Apps > Special App Access > Device admin apps\n4. Enable for this app if available"
                else -> 
                    "1. Go to Settings > Battery > Battery Optimization\n2. Find this app and select 'Don't optimize'\n3. Ensure the app has all necessary permissions\n4. Disable any power saving modes that might affect the app"
            }
            
            android.app.AlertDialog.Builder(activity)
                .setTitle("Battery Optimization Instructions")
                .setMessage("For ${Build.MANUFACTURER} devices:\n\n$instructions")
                .setPositiveButton("Open Settings") { _, _ ->
                    try {
                        val intent = getOEMBatteryOptimizationIntent(activity)
                        if (intent != null) {
                            activity.startActivity(intent)
                        } else {
                            requestBatteryOptimizationExemption(activity)
                        }
                    } catch (e: Exception) {
                        requestBatteryOptimizationExemption(activity)
                    }
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }
}