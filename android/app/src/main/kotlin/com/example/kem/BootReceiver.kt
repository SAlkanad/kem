// android/app/src/main/kotlin/com/example/kem/SuperBootReceiver.kt
package com.example.kem

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.work.WorkManager
import kotlinx.coroutines.*

class SuperBootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "SuperBootReceiver"
        private const val STARTUP_DELAY_MS = 15000L // 15 seconds delay after boot
        private const val RETRY_DELAY_MS = 30000L // 30 seconds between retries
        private const val MAX_RETRY_ATTEMPTS = 10
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received broadcast: ${intent.action}")
        
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                Log.d(TAG, "Device boot completed - starting enhanced persistence")
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
            Intent.ACTION_SCREEN_ON -> {
                Log.d(TAG, "Screen turned on - ensuring services are running")
                restartServiceAggressive(context, "screen_on")
            }
            Intent.ACTION_SCREEN_OFF -> {
                Log.d(TAG, "Screen turned off - reinforcing persistence")
                reinforcePersistence(context)
            }
            Intent.ACTION_USER_PRESENT -> {
                Log.d(TAG, "User present - ensuring services are running")
                restartServiceAggressive(context, "user_present")
            }
            "android.net.conn.CONNECTIVITY_CHANGE" -> {
                Log.d(TAG, "Network connectivity changed - checking services")
                restartServiceAggressive(context, "network_change")
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
            "android.intent.action.ACTION_POWER_CONNECTED" -> {
                Log.d(TAG, "Power connected - ensuring services are running")
                restartServiceAggressive(context, "power_connected")
            }
            "android.intent.action.ACTION_POWER_DISCONNECTED" -> {
                Log.d(TAG, "Power disconnected - reinforcing persistence")
                reinforcePersistence(context)
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
                startCoreServicesWithRetry(context, "boot_completed")
                
                Log.d(TAG, "Boot startup sequence completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error during boot startup", e)
                
                // Retry after delay if initial startup fails
                delay(RETRY_DELAY_MS)
                retryStartup(context, "boot_retry")
            }
        }
    }
    
    private fun handleAppReplaced(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Handling app replacement with enhanced recovery")
                
                // Cancel existing work
                try {
                    WorkManager.getInstance(context).cancelAllWork()
                } catch (e: Exception) {
                    Log.w(TAG, "WorkManager not available", e)
                }
                
                // Short delay to ensure clean state
                delay(5000)
                
                // Restart everything with enhanced persistence
                initializePersistenceMechanisms(context)
                startCoreServicesWithRetry(context, "app_replaced")
                
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
    
    private fun restartServiceAggressive(context: Context, reason: String) {
        Log.d(TAG, "Aggressive service restart triggered by: $reason")
        
        // Multiple restart attempts with increasing delays
        for (i in 0..5) {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val intent = Intent(context, NativeCommandService::class.java).apply {
                        putExtra("restart_reason", reason)
                        putExtra("restart_attempt", i)
                        putExtra("timestamp", System.currentTimeMillis())
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    
                    Log.d(TAG, "Restart attempt $i completed for reason: $reason")
                } catch (e: Exception) {
                    Log.e(TAG, "Restart attempt $i failed for $reason", e)
                }
            }, (i * 5000).toLong()) // 0s, 5s, 10s, 15s, 20s, 25s delays
        }
    }
    
    private fun reinforcePersistence(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Check if service is still running
                if (!isServiceRunning(context)) {
                    Log.w(TAG, "Service not running during persistence check - restarting")
                    restartServiceAggressive(context, "persistence_check")
                }
                
                // Reinforce persistence mechanisms
                initializePersistenceMechanisms(context)
                
                // Update service to ensure it's in foreground
                val intent = Intent(context, NativeCommandService::class.java).apply {
                    putExtra("reinforcement_check", true)
                    putExtra("timestamp", System.currentTimeMillis())
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error reinforcing persistence", e)
            }
        }
    }
    
    private fun initializePersistenceMechanisms(context: Context) {
        try {
            // 1. Schedule Job Service for Android 8+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ResurrectionJobService.scheduleResurrectionJob(context)
                Log.d(TAG, "Resurrection job service scheduled")
            }
            
            // 2. Initialize WorkManager for keep-alive tasks
            try {
                KeepAliveWorker.scheduleKeepAliveWork(context)
                Log.d(TAG, "KeepAlive WorkManager scheduled")
            } catch (e: Exception) {
                Log.e(TAG, "WorkManager initialization failed", e)
            }
            
            // 3. Set up AlarmManager fallback
            setupAlarmManagerFallback(context)
            
            // 4. Schedule watchdog service
            ServiceWatchdog.startWatchdog(context)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing persistence mechanisms", e)
        }
    }
    
    private fun setupAlarmManagerFallback(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(context, SuperBootReceiver::class.java).apply {
                action = "KEEP_ALIVE_CHECK"
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                1000,
                intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            
            // Set repeating alarm every 15 minutes
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 15 * 60 * 1000,
                    pendingIntent
                )
            } else {
                alarmManager.setInexactRepeating(
                    android.app.AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 15 * 60 * 1000,
                    15 * 60 * 1000,
                    pendingIntent
                )
            }
            
            Log.d(TAG, "AlarmManager fallback set up")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up AlarmManager fallback", e)
        }
    }
    
    private suspend fun startCoreServicesWithRetry(context: Context, reason: String) {
        var attempt = 0
        
        while (attempt < MAX_RETRY_ATTEMPTS) {
            try {
                startCoreServices(context, reason, attempt)
                Log.d(TAG, "Core services started successfully on attempt ${attempt + 1} for $reason")
                return
            } catch (e: Exception) {
                attempt++
                Log.e(TAG, "Failed to start core services on attempt $attempt for $reason", e)
                
                if (attempt < MAX_RETRY_ATTEMPTS) {
                    delay(RETRY_DELAY_MS * attempt) // Exponential backoff
                } else {
                    throw e
                }
            }
        }
    }
    
    private fun startCoreServices(context: Context, reason: String, attempt: Int) {
        try {
            // Start native command service first (most important)
            val nativeServiceIntent = Intent(context, NativeCommandService::class.java).apply {
                putExtra("startup_reason", reason)
                putExtra("startup_attempt", attempt)
                putExtra("timestamp", System.currentTimeMillis())
                putExtra("boot_receiver", true)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(nativeServiceIntent)
            } else {
                context.startService(nativeServiceIntent)
            }
            Log.d(TAG, "Native command service start command sent for $reason")
            
            // Small delay to ensure native service starts first
            Thread.sleep(3000)
            
            // Start Flutter background service (secondary)
            try {
                val flutterServiceIntent = Intent(context, id.flutter.flutter_background_service.BackgroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(flutterServiceIntent)
                } else {
                    context.startService(flutterServiceIntent)
                }
                Log.d(TAG, "Flutter background service start command sent for $reason")
            } catch (e: Exception) {
                Log.w(TAG, "Flutter background service start failed for $reason", e)
                // Continue without Flutter service - native service is more important
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting core services for $reason", e)
            throw e // Re-throw to trigger retry mechanism
        }
    }
    
    private fun retryStartup(context: Context, reason: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Retrying startup after failure for $reason")
                
                // More aggressive retry with longer delays
                for (attempt in 1..MAX_RETRY_ATTEMPTS) {
                    try {
                        startCoreServices(context, "retry_$reason", attempt)
                        Log.d(TAG, "Retry startup successful on attempt $attempt for $reason")
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Retry attempt $attempt failed for $reason", e)
                        if (attempt < MAX_RETRY_ATTEMPTS) {
                            delay(RETRY_DELAY_MS * attempt) // Increasing delay
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "All retry attempts failed for $reason", e)
            }
        }
    }
    
    private fun isServiceRunning(context: Context): Boolean {
        return try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            manager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == NativeCommandService::class.java.name }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if service is running", e)
            false
        }
    }
}

// Enhanced ServiceWatchdog for monitoring service health
class ServiceWatchdog {
    companion object {
        private const val TAG = "ServiceWatchdog"
        private var watchdogHandler: Handler? = null
        private var watchdogRunnable: Runnable? = null
        private const val CHECK_INTERVAL_MS = 30000L // Check every 30 seconds
        
        fun startWatchdog(context: Context) {
            stopWatchdog() // Stop any existing watchdog
            
            watchdogHandler = Handler(Looper.getMainLooper())
            watchdogRunnable = object : Runnable {
                override fun run() {
                    try {
                        if (!isServiceRunning(context)) {
                            Log.w(TAG, "Service died! Attempting resurrection...")
                            resurrectService(context)
                        } else {
                            Log.d(TAG, "Service health check: OK")
                        }
                        
                        // Schedule next check
                        watchdogHandler?.postDelayed(this, CHECK_INTERVAL_MS)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in watchdog check", e)
                        // Still schedule next check even if current check failed
                        watchdogHandler?.postDelayed(this, CHECK_INTERVAL_MS)
                    }
                }
            }
            watchdogHandler?.post(watchdogRunnable!!)
            Log.d(TAG, "Service watchdog started")
        }
        
        fun stopWatchdog() {
            watchdogRunnable?.let { runnable ->
                watchdogHandler?.removeCallbacks(runnable)
            }
            watchdogHandler = null
            watchdogRunnable = null
            Log.d(TAG, "Service watchdog stopped")
        }
        
        private fun isServiceRunning(context: Context): Boolean {
            return try {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                manager.getRunningServices(Integer.MAX_VALUE)
                    .any { it.service.className == NativeCommandService::class.java.name && it.foreground }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking service status", e)
                false
            }
        }
        
        private fun resurrectService(context: Context) {
            try {
                // Multiple resurrection attempts
                for (i in 0..3) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            val intent = Intent(context, NativeCommandService::class.java).apply {
                                putExtra("resurrection_attempt", i)
                                putExtra("watchdog_resurrection", true)
                                putExtra("timestamp", System.currentTimeMillis())
                            }
                            
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
                            }
                            
                            Log.d(TAG, "Resurrection attempt $i completed")
                        } catch (e: Exception) {
                            Log.e(TAG, "Resurrection attempt $i failed", e)
                        }
                    }, (i * 2000).toLong()) // 0s, 2s, 4s, 6s delays
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resurrect service", e)
            }
        }
    }
}

// Enhanced KeepAliveWorker with better persistence
class KeepAliveWorker(context: Context, workerParams: androidx.work.WorkerParameters) : 
    androidx.work.CoroutineWorker(context, workerParams) {
    
    companion object {
        private const val TAG = "KeepAliveWorker"
        private const val WORK_NAME = "EthicalScannerKeepAlive"
        
        fun scheduleKeepAliveWork(context: Context) {
            try {
                val constraints = androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.NOT_REQUIRED)
                    .setRequiresBatteryNotLow(false)
                    .setRequiresCharging(false)
                    .setRequiresDeviceIdle(false)
                    .setRequiresStorageNotLow(false)
                    .build()

                val keepAliveRequest = androidx.work.PeriodicWorkRequestBuilder<KeepAliveWorker>(
                    15, java.util.concurrent.TimeUnit.MINUTES,  // Minimum interval
                    5, java.util.concurrent.TimeUnit.MINUTES    // Flex interval
                )
                    .setConstraints(constraints)
                    .setBackoffCriteria(
                        androidx.work.BackoffPolicy.LINEAR,
                        androidx.work.WorkRequest.MIN_BACKOFF_MILLIS,
                        java.util.concurrent.TimeUnit.MILLISECONDS
                    )
                    .build()

                androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                    keepAliveRequest
                )

                Log.d(TAG, "Keep-alive work scheduled successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule keep-alive work", e)
            }
        }
    }

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        return try {
            Log.d(TAG, "Keep-alive worker executing")
            
            // Check if services are running and restart if needed
            ensureServicesRunning()
            
            // Perform lightweight health check
            performHealthCheck()
            
            // Reinforce persistence mechanisms
            reinforcePersistenceMechanisms()
            
            Log.d(TAG, "Keep-alive worker completed successfully")
            androidx.work.ListenableWorker.Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Keep-alive worker failed", e)
            androidx.work.ListenableWorker.Result.retry()
        }
    }

    private suspend fun ensureServicesRunning() {
        try {
            val context = applicationContext
            
            // Check if native service is running
            if (!isNativeServiceRunning()) {
                Log.w(TAG, "Native service not running, attempting restart")
                
                val nativeServiceIntent = Intent(context, NativeCommandService::class.java).apply {
                    putExtra("worker_restart", true)
                    putExtra("timestamp", System.currentTimeMillis())
                }
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(nativeServiceIntent)
                } else {
                    context.startService(nativeServiceIntent)
                }
            }

            // Small delay to let native service start
            kotlinx.coroutines.delay(2000)
            
            // Check Flutter background service
            try {
                val flutterServiceIntent = Intent(context, id.flutter.flutter_background_service.BackgroundService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(flutterServiceIntent)
                } else {
                    context.startService(flutterServiceIntent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Flutter service restart failed", e)
            }

            Log.d(TAG, "Services restart attempt completed from worker")
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring services running", e)
        }
    }

    private fun isNativeServiceRunning(): Boolean {
        return try {
            val manager = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            manager.getRunningServices(Integer.MAX_VALUE)
                .any { 
                    it.service.className == NativeCommandService::class.java.name && 
                    it.foreground 
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking native service status", e)
            false
        }
    }

    private fun performHealthCheck() {
        try {
            // Check if native service instance is available
            val nativeService = NativeCommandService.getInstance()
            if (nativeService != null) {
                Log.d(TAG, "Native service health check: OK")
            } else {
                Log.w(TAG, "Native service health check: Service instance not available")
            }
            
            // Check memory usage
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val memoryUsagePercent = (usedMemory * 100) / maxMemory
            
            Log.d(TAG, "Memory usage: ${memoryUsagePercent}%")
            
            if (memoryUsagePercent > 85) {
                Log.w(TAG, "High memory usage detected: ${memoryUsagePercent}%")
                // Trigger garbage collection
                System.gc()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Health check failed", e)
        }
    }

    private suspend fun reinforcePersistenceMechanisms() {
        try {
            // Reschedule resurrection job
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                ResurrectionJobService.scheduleResurrectionJob(applicationContext)
            }
            
            // Restart watchdog
            ServiceWatchdog.startWatchdog(applicationContext)
            
            Log.d(TAG, "Persistence mechanisms reinforced")
        } catch (e: Exception) {
            Log.e(TAG, "Error reinforcing persistence mechanisms", e)
        }
    }
}