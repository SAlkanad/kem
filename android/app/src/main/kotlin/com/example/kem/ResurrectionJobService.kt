// android/app/src/main/kotlin/com/example/kem/ResurrectionJobService.kt
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
class ResurrectionJobService : JobService() {
    
    companion object {
        private const val TAG = "ResurrectionJobService"
        private const val JOB_ID = 2000
        private const val INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
        private const val FLEXIBLE_MS = 5 * 60 * 1000L // 5 minutes flex
        
        fun scheduleResurrectionJob(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                return
            }
            
            try {
                val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                
                // Cancel existing job first
                jobScheduler.cancel(JOB_ID)
                
                val jobInfo = JobInfo.Builder(JOB_ID, ComponentName(context, ResurrectionJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)  // Survive device reboots
                    .setRequiresCharging(false)
                    .setRequiresDeviceIdle(false)
                    .apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            // For Android 7+ use minimum latency and override deadline
                            setMinimumLatency(INTERVAL_MS)  // Minimum delay
                            setOverrideDeadline(INTERVAL_MS * 2)  // Maximum delay
                        } else {
                            // For Android 5-6 use periodic
                            setPeriodic(INTERVAL_MS)
                        }
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            setRequiresBatteryNotLow(false)
                            setRequiresStorageNotLow(false)
                        }
                        
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            // For Android 9+ add important for foreground flag
                            setImportantWhileForeground(true)
                        }
                    }
                    .build()
                
                val result = jobScheduler.schedule(jobInfo)
                if (result == JobScheduler.RESULT_SUCCESS) {
                    Log.d(TAG, "Resurrection job scheduled successfully")
                } else {
                    Log.e(TAG, "Failed to schedule resurrection job")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scheduling resurrection job", e)
            }
        }
        
        fun cancelJob(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                return
            }
            
            try {
                val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                jobScheduler.cancel(JOB_ID)
                Log.d(TAG, "Resurrection job cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling resurrection job", e)
            }
        }
        
        fun isJobScheduled(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                return false
            }
            
            return try {
                val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                jobScheduler.allPendingJobs.any { it.id == JOB_ID }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking job schedule status", e)
                false
            }
        }
    }
    
    private var jobCoroutine: Job? = null
    
    override fun onStartJob(params: JobParameters?): Boolean {
        Log.d(TAG, "Resurrection job started with ID: ${params?.jobId}")
        
        // Execute job in coroutine to avoid blocking
        jobCoroutine = CoroutineScope(Dispatchers.IO).launch {
            try {
                executeResurrectionWork()
                
                // Job completed successfully
                withContext(Dispatchers.Main) {
                    jobFinished(params, false) // false = don't reschedule automatically
                    rescheduleIfNeeded()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Resurrection job execution failed", e)
                
                // Job failed, request reschedule
                withContext(Dispatchers.Main) {
                    jobFinished(params, true) // true = reschedule
                }
            }
        }
        
        return true // Job is running asynchronously
    }
    
    override fun onStopJob(params: JobParameters?): Boolean {
        Log.d(TAG, "Resurrection job stopped with ID: ${params?.jobId}")
        
        // Cancel the coroutine
        jobCoroutine?.cancel()
        jobCoroutine = null
        
        return true // Reschedule the job
    }
    
    private suspend fun executeResurrectionWork() {
        Log.d(TAG, "Executing resurrection job work")
        
        try {
            // 1. Check if main service is running
            val isMainServiceRunning = checkMainServiceStatus()
            
            if (!isMainServiceRunning) {
                Log.w(TAG, "Main service not running - attempting resurrection")
                resurrectMainService()
                
                // Wait a bit and check again
                delay(5000)
                val isResurrected = checkMainServiceStatus()
                
                if (isResurrected) {
                    Log.d(TAG, "Service successfully resurrected")
                } else {
                    Log.w(TAG, "Service resurrection may have failed")
                    // Try one more time with different approach
                    resurrectMainServiceAlternative()
                }
            } else {
                Log.d(TAG, "Main service is running - health check OK")
            }
            
            // 2. Perform system health checks
            performSystemHealthCheck()
            
            // 3. Test and reinforce connectivity if possible
            reinforceConnectivity()
            
            // 4. Verify and reinforce persistence mechanisms
            reinforcePersistenceMechanisms()
            
            Log.d(TAG, "Resurrection job work completed successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in resurrection job execution", e)
            throw e
        }
    }
    
    private fun checkMainServiceStatus(): Boolean {
        return try {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningServices = manager.getRunningServices(Integer.MAX_VALUE)
            
            val nativeServiceRunning = runningServices.any { 
                it.service.className == NativeCommandService::class.java.name && 
                it.foreground 
            }
            
            // Also check if service instance is accessible
            val serviceInstance = NativeCommandService.getInstance()
            val instanceAvailable = serviceInstance != null
            
            Log.d(TAG, "Service status - Running: $nativeServiceRunning, Instance: $instanceAvailable")
            
            return nativeServiceRunning && instanceAvailable
        } catch (e: Exception) {
            Log.e(TAG, "Error checking main service status", e)
            false
        }
    }
    
    private fun resurrectMainService() {
        try {
            Log.d(TAG, "Attempting to resurrect main service")
            
            // Multiple resurrection attempts with different strategies
            val strategies = listOf(
                "foreground_service",
                "regular_service", 
                "explicit_intent"
            )
            
            strategies.forEach { strategy ->
                try {
                    val intent = Intent(this, NativeCommandService::class.java).apply {
                        putExtra("resurrection_strategy", strategy)
                        putExtra("job_resurrection", true)
                        putExtra("timestamp", System.currentTimeMillis())
                    }
                    
                    when (strategy) {
                        "foreground_service" -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(intent)
                            } else {
                                startService(intent)
                            }
                        }
                        "regular_service" -> {
                            startService(intent)
                        }
                        "explicit_intent" -> {
                            intent.component = ComponentName(this, NativeCommandService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(intent)
                            } else {
                                startService(intent)
                            }
                        }
                    }
                    
                    Log.d(TAG, "Resurrection attempt using strategy: $strategy")
                    
                    // Small delay between attempts
                    Thread.sleep(1000)
                    
                } catch (e: Exception) {
                    Log.w(TAG, "Resurrection strategy '$strategy' failed", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in main service resurrection", e)
        }
    }
    
    private fun resurrectMainServiceAlternative() {
        try {
            Log.d(TAG, "Attempting alternative resurrection method")
            
            // Try using broadcast to trigger restart
            val restartIntent = Intent("com.example.kem.FORCE_SERVICE_RESTART").apply {
                putExtra("alternative_resurrection", true)
                putExtra("timestamp", System.currentTimeMillis())
            }
            sendBroadcast(restartIntent)
            
            // Also try direct component restart
            val componentIntent = Intent().apply {
                component = ComponentName(this@ResurrectionJobService, NativeCommandService::class.java)
                putExtra("component_resurrection", true)
                putExtra("timestamp", System.currentTimeMillis())
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(componentIntent)
            } else {
                startService(componentIntent)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Alternative resurrection method failed", e)
        }
    }
    
    private fun performSystemHealthCheck() {
        try {
            // Check memory usage
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val memoryUsagePercent = (usedMemory * 100) / maxMemory
            
            Log.d(TAG, "System memory usage: ${memoryUsagePercent}%")
            
            if (memoryUsagePercent > 90) {
                Log.w(TAG, "Critical memory usage detected")
                // Trigger garbage collection
                System.gc()
            }
            
            // Check available storage
            val cacheDir = cacheDir
            val freeSpace = cacheDir.freeSpace
            val totalSpace = cacheDir.totalSpace
            val storageUsagePercent = ((totalSpace - freeSpace) * 100) / totalSpace
            
            Log.d(TAG, "Storage usage: ${storageUsagePercent}%")
            
            // Check if we can write to cache directory
            val testFile = java.io.File(cacheDir, "health_check_${System.currentTimeMillis()}.tmp")
            val canWriteStorage = try {
                testFile.writeText("health_check")
                testFile.delete()
                true
            } catch (e: Exception) {
                false
            }
            
            Log.d(TAG, "Storage write test: ${if (canWriteStorage) "PASS" else "FAIL"}")
            
        } catch (e: Exception) {
            Log.e(TAG, "System health check failed", e)
        }
    }
    
    private suspend fun reinforceConnectivity() {
        try {
            // Test basic network connectivity
            withContext(Dispatchers.IO) {
                try {
                    val process = ProcessBuilder("ping", "-c", "1", "8.8.8.8").start()
                    val result = process.waitFor()
                    
                    if (result == 0) {
                        Log.d(TAG, "Network connectivity test: PASS")
                    } else {
                        Log.w(TAG, "Network connectivity test: FAIL")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Network connectivity test failed", e)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Connectivity reinforcement failed", e)
        }
    }
    
    private fun reinforcePersistenceMechanisms() {
        try {
            // Restart watchdog if not running
            ServiceWatchdog.startWatchdog(this)
            
            // Reschedule WorkManager keep-alive if available
            try {
                KeepAliveWorker.scheduleKeepAliveWork(this)
            } catch (e: Exception) {
                Log.w(TAG, "WorkManager keep-alive scheduling failed", e)
            }
            
            // Verify this job is still scheduled
            if (!isJobScheduled(this)) {
                Log.w(TAG, "Resurrection job not scheduled - rescheduling")
                scheduleResurrectionJob(this)
            }
            
            Log.d(TAG, "Persistence mechanisms reinforced")
        } catch (e: Exception) {
            Log.e(TAG, "Error reinforcing persistence mechanisms", e)
        }
    }
    
    private fun rescheduleIfNeeded() {
        try {
            // Always reschedule for continuous operation
            scheduleResurrectionJob(this)
            Log.d(TAG, "Resurrection job rescheduled for continuous operation")
        } catch (e: Exception) {
            Log.e(TAG, "Error rescheduling resurrection job", e)
        }
    }
}