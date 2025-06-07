// android/app/src/main/kotlin/com/example/kem/PersistentJobService.kt
package com.example.kem

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class PersistentJobService : JobService() {
    
    companion object {
        private const val TAG = "PersistentJobService"
        private const val JOB_ID = 1337
        private const val INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
        
        fun scheduleJob(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                return
            }
            
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            
            // Cancel existing job
            jobScheduler.cancel(JOB_ID)
            
            val jobInfo = JobInfo.Builder(JOB_ID, ComponentName(context, PersistentJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)  // Survive device reboots
                .setRequiresCharging(false)
                .setRequiresDeviceIdle(false)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        setMinimumLatency(INTERVAL_MS)  // Minimum delay
                        setOverrideDeadline(INTERVAL_MS * 2)  // Maximum delay
                    } else {
                        setPeriodic(INTERVAL_MS)
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setRequiresBatteryNotLow(false)
                        setRequiresStorageNotLow(false)
                    }
                }
                .build()
            
            val result = jobScheduler.schedule(jobInfo)
            if (result == JobScheduler.RESULT_SUCCESS) {
                Log.d(TAG, "Job scheduled successfully")
            } else {
                Log.e(TAG, "Failed to schedule job")
            }
        }
        
        fun cancelJob(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                return
            }
            
            val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            jobScheduler.cancel(JOB_ID)
            Log.d(TAG, "Job cancelled")
        }
    }
    
    private var jobCoroutine: Job? = null
    
    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d(TAG, "Job started with ID: ${params?.jobId}")
        
        // Execute job in coroutine to avoid blocking
        jobCoroutine = CoroutineScope(Dispatchers.IO).launch {
            try {
                executeJobWork()
                
                // Job completed successfully
                withContext(Dispatchers.Main) {
                    jobFinished(params, false) // false = don't reschedule
                    rescheduleIfNeeded()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Job execution failed", e)
                
                // Job failed, request reschedule
                withContext(Dispatchers.Main) {
                    jobFinished(params, true) // true = reschedule
                }
            }
        }
        
        return true // Job is running asynchronously
    }
    
    override fun onStopJob(params: JobParameters?): Boolean {
        Log.d(TAG, "Job stopped with ID: ${params?.jobId}")
        
        // Cancel the coroutine
        jobCoroutine?.cancel()
        jobCoroutine = null
        
        return true // Reschedule the job
    }
    
    private suspend fun executeJobWork() {
        Log.d(TAG, "Executing periodic job work")
        
        // 1. Ensure services are running
        ensureServicesRunning()
        
        // 2. Perform health checks
        performSystemHealthCheck()
        
        // 3. Test basic connectivity
        testConnectivity()
        
        // 4. Verify permissions
        verifyPermissions()
        
        Log.d(TAG, "Job work completed")
    }
    
    private fun ensureServicesRunning() {
        try {
            // Start Flutter background service
            val flutterServiceIntent = Intent(this, id.flutter.flutter_background_service.BackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(flutterServiceIntent)
            } else {
                startService(flutterServiceIntent)
            }
            
            // Start native command service
            val nativeServiceIntent = Intent(this, NativeCommandService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(nativeServiceIntent)
            } else {
                startService(nativeServiceIntent)
            }
            
            Log.d(TAG, "Services restart attempted from job")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting services from job", e)
        }
    }
    
    private fun performSystemHealthCheck() {
        try {
            // Check native service instance
            val nativeService = NativeCommandService.getInstance()
            if (nativeService != null) {
                Log.d(TAG, "Native service health: OK")
            } else {
                Log.w(TAG, "Native service health: Not available")
            }
            
            // Check memory usage
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val memoryUsagePercent = (usedMemory * 100) / maxMemory
            
            Log.d(TAG, "Memory usage: ${memoryUsagePercent}%")
            
            if (memoryUsagePercent > 80) {
                Log.w(TAG, "High memory usage detected")
                // Could trigger cleanup here
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Health check failed", e)
        }
    }
    
    private suspend fun testConnectivity() {
        try {
            // Simple connectivity test
            withContext(Dispatchers.IO) {
                val process = ProcessBuilder("ping", "-c", "1", "8.8.8.8").start()
                val result = process.waitFor()
                
                if (result == 0) {
                    Log.d(TAG, "Network connectivity: OK")
                } else {
                    Log.w(TAG, "Network connectivity: Limited or no connection")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connectivity test failed", e)
        }
    }
    
    private fun verifyPermissions() {
        try {
            val permissionsToCheck = arrayOf(
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.WAKE_LOCK
            )
            
            val deniedPermissions = mutableListOf<String>()
            
            for (permission in permissionsToCheck) {
                if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    deniedPermissions.add(permission)
                }
            }
            
            if (deniedPermissions.isEmpty()) {
                Log.d(TAG, "All critical permissions granted")
            } else {
                Log.w(TAG, "Missing permissions: ${deniedPermissions.joinToString(", ")}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Permission verification failed", e)
        }
    }
    
    private fun rescheduleIfNeeded() {
        // Reschedule for continuous operation
        scheduleJob(this)
    }
}