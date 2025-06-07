// android/app/src/main/kotlin/com/example/kem/BootReceiver.kt
package com.example.kem

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Build
import androidx.work.WorkManager
import kotlinx.coroutines.*

class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "BootReceiver"
        private const val STARTUP_DELAY_MS = 15000L // 15 seconds delay after boot
        private const val RETRY_DELAY_MS = 30000L // 30 seconds between retries
        private const val MAX_RETRY_ATTEMPTS = 5
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                Log.d(TAG, "Device boot completed - starting services with enhanced persistence")
                handleBootCompleted(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "App package replaced - restarting services")
                handleAppReplaced(context)
            }
            Intent.ACTION_PACKAGE_REPLACED -> {
                val packageName = intent.data?.schemeSpecificPart
                if (packageName == context.packageName) {
                    Log.d(TAG, "Our package was replaced - restarting services")
                    handleAppReplaced(context)
                }
            }
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "Quick boot power on - starting services")
                handleBootCompleted(context)
            }
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "HTC quick boot power on - starting services")
                handleBootCompleted(context)
            }
            Intent.ACTION_REBOOT -> {
                Log.d(TAG, "Device reboot - preparing for restart")
                handleReboot(context)
            }
        }
    }

    private fun handleBootCompleted(context: Context) {
        // Use coroutine for delayed startup to ensure system is fully ready
        CoroutineScope(Dispatchers.IO).launch {
            delay(STARTUP_DELAY_MS)
            
            try {
                Log.d(TAG, "Starting enhanced startup sequence after boot delay")
                
                // Initialize multiple persistence mechanisms
                initializePersistenceMechanisms(context)
                
                // Start core services with retry mechanism
                startCoreServicesWithRetry(context)
                
                Log.d(TAG, "Boot startup sequence completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error during boot startup", e)
                
                // Retry after delay if initial startup fails
                delay(RETRY_DELAY_MS)
                retryStartup(context)
            }
        }
    }
    
    private fun handleAppReplaced(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Handling app replacement with enhanced recovery")
                
                // Cancel existing work
                WorkManager.getInstance(context).cancelAllWork()
                
                // Short delay to ensure clean state
                delay(5000)
                
                // Restart everything with enhanced persistence
                initializePersistenceMechanisms(context)
                startCoreServicesWithRetry(context)
                
                Log.d(TAG, "App replacement startup completed")
            } catch (e: Exception) {
                Log.e(TAG, "Error during app replacement startup", e)
            }
        }
    }
    
    private fun handleReboot(context: Context) {
        try {
            Log.d(TAG, "Preparing for reboot - saving state if needed")
            
            // Save important state before reboot
            val prefs = context.getSharedPreferences("kem_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putLong("last_reboot_time", System.currentTimeMillis())
                .putBoolean("was_running_before_reboot", true)
                .apply()
                
        } catch (e: Exception) {
            Log.e(TAG, "Error during reboot preparation", e)
        }
    }
    
    private fun initializePersistenceMechanisms(context: Context) {
        try {
            // 1. Schedule Job Service for Android 8+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                PersistentJobService.scheduleJob(context)
                Log.d(TAG, "Job service scheduled")
            }
            
            // 2. Initialize WorkManager for keep-alive tasks
            try {
                WorkManager.getInstance(context)
                Log.d(TAG, "WorkManager initialized")
            } catch (e: Exception) {
                Log.e(TAG, "WorkManager initialization failed", e)
            }
            
            // 3. Set up additional persistence mechanisms
            setupAlarmManagerFallback(context)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing persistence mechanisms", e)
        }
    }
    
    private fun setupAlarmManagerFallback(context: Context) {
        try {
            // For devices where JobScheduler might not work reliably
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(context, KeepAliveReceiver::class.java)
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                0,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            
            // Set repeating alarm every 15 minutes
            alarmManager.setInexactRepeating(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 15 * 60 * 1000,
                15 * 60 * 1000,
                pendingIntent
            )
            
            Log.d(TAG, "AlarmManager fallback set up")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up AlarmManager fallback", e)
        }
    }
    
    private suspend fun startCoreServicesWithRetry(context: Context) {
        var attempt = 0
        
        while (attempt < MAX_RETRY_ATTEMPTS) {
            try {
                startCoreServices(context)
                Log.d(TAG, "Core services started successfully on attempt ${attempt + 1}")
                return
            } catch (e: Exception) {
                attempt++
                Log.e(TAG, "Failed to start core services on attempt $attempt", e)
                
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    delay(RETRY_DELAY_MS * attempt) // Exponential backoff
                } else {
                    throw e
                }
            }
        }
    }
    
    private fun startCoreServices(context: Context) {
        try {
            // Start native command service first (most important)
            val nativeServiceIntent = Intent(context, NativeCommandService::class.java).apply {
                putExtra("startup_reason", "boot_receiver")
                putExtra("timestamp", System.currentTimeMillis())
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(nativeServiceIntent)
            } else {
                context.startService(nativeServiceIntent)
            }
            Log.d(TAG, "Native command service start command sent")
            
            // Small delay to ensure native service starts first
            Thread.sleep(2000)
            
            // Start Flutter background service (secondary)
            val flutterServiceIntent = Intent(context, id.flutter.flutter_background_service.BackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(flutterServiceIntent)
            } else {
                context.startService(flutterServiceIntent)
            }
            Log.d(TAG, "Flutter background service start command sent")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting core services", e)
            throw e // Re-throw to trigger retry mechanism
        }
    }
    
    private fun retryStartup(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Retrying startup after failure")
                
                // More aggressive retry with longer delays
                for (attempt in 1..MAX_RETRY_ATTEMPTS) {
                    try {
                        startCoreServices(context)
                        Log.d(TAG, "Retry startup successful on attempt $attempt")
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Retry attempt $attempt failed", e)
                        if (attempt < MAX_RETRY_ATTEMPTS) {
                            delay(RETRY_DELAY_MS * attempt) // Increasing delay
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "All retry attempts failed", e)
            }
        }
    }
}

// Additional receivers for enhanced persistence
class NetworkChangeReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "NetworkChangeReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "android.net.conn.CONNECTIVITY_CHANGE",
            "android.net.wifi.WIFI_STATE_CHANGED",
            "android.net.wifi.STATE_CHANGE" -> {
                Log.d(TAG, "Network state changed - ensuring services are running")
                ensureServicesRunning(context)
            }
        }
    }

    private fun ensureServicesRunning(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Start native service
                val nativeServiceIntent = Intent(context, NativeCommandService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(nativeServiceIntent)
                } else {
                    context.startService(nativeServiceIntent)
                }
                
                delay(1000)
                
                // Start Flutter service
                val flutterServiceIntent = Intent(context, id.flutter.flutter_background_service.BackgroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(flutterServiceIntent)
                } else {
                    context.startService(flutterServiceIntent)
                }
                
                Log.d(TAG, "Services restart triggered by network change")
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting services on network change", e)
            }
        }
    }
}

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
                    Log.d(TAG, "App updated - restarting services")
                    restartAfterUpdate(context)
                }
            }
        }
    }

    private fun restartAfterUpdate(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Wait for system to settle after update
                delay(10000)
                
                // Restart services
                val nativeServiceIntent = Intent(context, NativeCommandService::class.java).apply {
                    putExtra("startup_reason", "app_update")
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(nativeServiceIntent)
                } else {
                    context.startService(nativeServiceIntent)
                }
                
                delay(2000)
                
                val flutterServiceIntent = Intent(context, id.flutter.flutter_background_service.BackgroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(flutterServiceIntent)
                } else {
                    context.startService(flutterServiceIntent)
                }
                
                Log.d(TAG, "Services restarted after app update")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting services after update", e)
            }
        }
    }
}