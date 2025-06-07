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
        private const val STARTUP_DELAY_MS = 10000L // 10 seconds delay after boot
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                Log.d(TAG, "Device boot completed - starting services")
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
                Log.d(TAG, "Starting services after boot delay")
                
                // Initialize multiple persistence mechanisms
                initializePersistenceMechanisms(context)
                
                // Start core services
                startCoreServices(context)
                
                Log.d(TAG, "Boot startup sequence completed")
            } catch (e: Exception) {
                Log.e(TAG, "Error during boot startup", e)
                
                // Retry after delay if initial startup fails
                delay(30000) // 30 seconds
                retryStartup(context)
            }
        }
    }
    
    private fun handleAppReplaced(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Handling app replacement")
                
                // Cancel existing work
                WorkManager.getInstance(context).cancelAllWork()
                
                // Short delay to ensure clean state
                delay(5000)
                
                // Restart everything
                initializePersistenceMechanisms(context)
                startCoreServices(context)
                
                Log.d(TAG, "App replacement startup completed")
            } catch (e: Exception) {
                Log.e(TAG, "Error during app replacement startup", e)
            }
        }
    }
    
    private fun handleReboot(context: Context) {
        try {
            Log.d(TAG, "Preparing for reboot - saving state if needed")
            
            // Could save important state here before reboot
            // For now, just log the event
            
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
            
            // 3. Set up any other persistence mechanisms
            // (Could add AlarmManager fallback for very old devices)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing persistence mechanisms", e)
        }
    }
    
    private fun startCoreServices(context: Context) {
        try {
            // Start Flutter background service
            val flutterServiceIntent = Intent(context, id.flutter.flutter_background_service.BackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(flutterServiceIntent)
            } else {
                context.startService(flutterServiceIntent)
            }
            Log.d(TAG, "Flutter background service start command sent")
            
            // Start native command service
            val nativeServiceIntent = Intent(context, NativeCommandService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(nativeServiceIntent)
            } else {
                context.startService(nativeServiceIntent)
            }
            Log.d(TAG, "Native command service start command sent")
            
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
                for (attempt in 1..3) {
                    try {
                        startCoreServices(context)
                        Log.d(TAG, "Retry startup successful on attempt $attempt")
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Retry attempt $attempt failed", e)
                        if (attempt < 3) {
                            delay(60000 * attempt) // Increasing delay: 1min, 2min, 3min
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "All retry attempts failed", e)
            }
        }
    }
}