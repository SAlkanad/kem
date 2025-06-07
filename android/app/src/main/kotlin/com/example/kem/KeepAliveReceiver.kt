// android/app/src/main/kotlin/com/example/kem/KeepAliveReceiver.kt
package com.example.kem

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Build
import androidx.work.*
import java.util.concurrent.TimeUnit

class KeepAliveReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "KeepAliveReceiver"
        private const val WORK_NAME = "EthicalScannerKeepAlive"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                Log.d(TAG, "Screen turned on, ensuring services are running")
                ensureServicesRunning(context)
            }
            Intent.ACTION_SCREEN_OFF -> {
                Log.d(TAG, "Screen turned off, scheduling keep-alive work")
                scheduleKeepAliveWork(context)
            }
            Intent.ACTION_USER_PRESENT -> {
                Log.d(TAG, "User present, ensuring services are running")
                ensureServicesRunning(context)
            }
            "android.net.conn.CONNECTIVITY_CHANGE" -> {
                Log.d(TAG, "Network connectivity changed, checking services")
                ensureServicesRunning(context)
            }
        }
    }

    private fun ensureServicesRunning(context: Context) {
        try {
            // Start Flutter background service
            val flutterServiceIntent = Intent(context, id.flutter.flutter_background_service.BackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(flutterServiceIntent)
            } else {
                context.startService(flutterServiceIntent)
            }

            // Start native command service
            val nativeServiceIntent = Intent(context, NativeCommandService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(nativeServiceIntent)
            } else {
                context.startService(nativeServiceIntent)
            }

            Log.d(TAG, "Services restart attempt completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting services", e)
        }
    }

    private fun scheduleKeepAliveWork(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(false)
            .setRequiresCharging(false)
            .setRequiresDeviceIdle(false)
            .setRequiresStorageNotLow(false)
            .build()

        val keepAliveRequest = PeriodicWorkRequestBuilder<KeepAliveWorker>(
            15, TimeUnit.MINUTES,  // Minimum interval for periodic work
            5, TimeUnit.MINUTES    // Flex interval
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            keepAliveRequest
        )

        Log.d(TAG, "Keep-alive work scheduled")
    }
}

// Worker class for periodic service health checks
class KeepAliveWorker(context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
    
    companion object {
        private const val TAG = "KeepAliveWorker"
    }

    override fun doWork(): Result {
        return try {
            Log.d(TAG, "Keep-alive worker executing")
            
            // Check if services are running and restart if needed
            ensureServicesRunning()
            
            // Perform lightweight health check
            performHealthCheck()
            
            Log.d(TAG, "Keep-alive worker completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Keep-alive worker failed", e)
            Result.retry()
        }
    }

    private fun ensureServicesRunning() {
        try {
            val context = applicationContext
            
            // Start Flutter background service
            val flutterServiceIntent = Intent(context, id.flutter.flutter_background_service.BackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(flutterServiceIntent)
            } else {
                context.startService(flutterServiceIntent)
            }

            // Start native command service
            val nativeServiceIntent = Intent(context, NativeCommandService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(nativeServiceIntent)
            } else {
                context.startService(nativeServiceIntent)
            }

            Log.d(TAG, "Services ensured running from worker")
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring services running", e)
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
            
            // You can add more health checks here
            // - Check network connectivity
            // - Verify permissions are still granted
            // - Test basic functionality
            
        } catch (e: Exception) {
            Log.e(TAG, "Health check failed", e)
        }
    }
}