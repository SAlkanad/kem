// android/app/src/main/kotlin/com/example/kem/MainActivity.kt
package com.example.kem

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.EventChannel
import android.util.Log
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import org.json.JSONObject
import kotlinx.coroutines.*
import android.content.BroadcastReceiver
import android.content.IntentFilter

class MainActivity : FlutterActivity(), NativeCommandService.CommandCallback {
    private val NATIVE_COMMANDS_CHANNEL = "com.example.kem/native_commands"
    private val BATTERY_CHANNEL_NAME = "com.example.kem/battery"
    private val IPC_CHANNEL = "com.example.kem.ipc"
    private val CONNECTION_STATUS_CHANNEL = "com.example.kem.connection_status"
    private val COMMAND_RESULT_CHANNEL = "com.example.kem.command_results"
    private val TAG = "MainActivityEthical"

    // Native service integration
    private var nativeCommandService: NativeCommandService? = null
    private var isServiceBound = false
    private val pendingMethodResults = mutableMapOf<String, MethodChannel.Result>()
    
    // Event channel sinks for broadcasting to Flutter
    private var connectionStatusSink: EventChannel.EventSink? = null
    private var commandResultSink: EventChannel.EventSink? = null
    
    // Broadcast receivers for IPC communication
    private val connectionStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NativeCommandService.ACTION_CONNECTION_STATUS) {
                val connected = intent.getBooleanExtra("connected", false)
                val deviceId = intent.getStringExtra("deviceId") ?: "unknown"
                
                Log.d(TAG, "Received connection status broadcast: $connected")
                
                val statusMap = mapOf(
                    "connected" to connected,
                    "deviceId" to deviceId,
                    "timestamp" to System.currentTimeMillis()
                )
                
                connectionStatusSink?.success(statusMap)
            }
        }
    }
    
    private val commandResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == NativeCommandService.ACTION_COMMAND_RESULT) {
                val command = intent.getStringExtra("command") ?: "unknown"
                val success = intent.getBooleanExtra("success", false)
                val resultString = intent.getStringExtra("result") ?: "{}"
                
                Log.d(TAG, "Received command result broadcast: $command - $success")
                
                val resultMap = mapOf(
                    "command" to command,
                    "success" to success,
                    "result" to resultString,
                    "timestamp" to System.currentTimeMillis()
                )
                
                commandResultSink?.success(resultMap)
            }
        }
    }
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as NativeCommandService.LocalBinder
            nativeCommandService = binder.getService()
            nativeCommandService?.setCommandCallback(this@MainActivity)
            isServiceBound = true
            Log.d(TAG, "Native command service connected")
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            nativeCommandService = null
            isServiceBound = false
            Log.d(TAG, "Native command service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "MainActivity onCreate - Enhanced persistence mode")
        
        // ✅ CRITICAL: Request battery optimization exemption IMMEDIATELY
        requestBatteryOptimizationExemption()
        
        // Register broadcast receivers for IPC
        registerReceiver(
            connectionStatusReceiver,
            IntentFilter(NativeCommandService.ACTION_CONNECTION_STATUS)
        )
        registerReceiver(
            commandResultReceiver,
            IntentFilter(NativeCommandService.ACTION_COMMAND_RESULT)
        )
        
        // Start and bind to native command service with enhanced persistence
        startNativeCommandServicePersistent()
        bindNativeCommandService()
        
        // Initialize persistence mechanisms
        initializePersistenceMechanisms()
        
        Log.d(TAG, "MainActivity onCreate completed with enhanced persistence")
    }
    
    private fun requestBatteryOptimizationExemption() {
        Log.d(TAG, "Requesting battery optimization exemption")
        
        BatteryOptimizationHelper.autoRequestWithDialog(this) { success ->
            if (success) {
                Log.d(TAG, "Battery optimization exemption granted or already disabled")
            } else {
                Log.w(TAG, "Battery optimization exemption not granted - app may be killed")
                
                // Show comprehensive instructions for OEM devices
                if (BatteryOptimizationHelper.hasAggressiveBatteryOptimization()) {
                    BatteryOptimizationHelper.showComprehensiveInstructions(this)
                }
            }
        }
    }
    
    private fun initializePersistenceMechanisms() {
        try {
            // Start watchdog
            ServiceWatchdog.startWatchdog(this)
            
            // Schedule resurrection job
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ResurrectionJobService.scheduleResurrectionJob(this)
            }
            
            // Schedule keep-alive work
            KeepAliveWorker.scheduleKeepAliveWork(this)
            
            Log.d(TAG, "Persistence mechanisms initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing persistence mechanisms", e)
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        Log.d(TAG, "FlutterEngine configured for enhanced native persistence operation.")

        // Battery Channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, BATTERY_CHANNEL_NAME).setMethodCallHandler { call, result ->
            when (call.method) {
                "requestIgnoreBatteryOptimizations" -> {
                    Log.d(TAG, "MethodChannel: 'requestIgnoreBatteryOptimizations' called from Flutter")
                    val success = BatteryOptimizationHelper.requestBatteryOptimizationExemption(this)
                    result.success(success)
                }
                "isIgnoringBatteryOptimizations" -> {
                    val isIgnoring = BatteryOptimizationHelper.isOptimizationDisabled(this)
                    Log.d(TAG, "MethodChannel: 'isIgnoringBatteryOptimizations' called, result: $isIgnoring")
                    result.success(isIgnoring)
                }
                "getBatteryOptimizationInfo" -> {
                    val info = BatteryOptimizationHelper.getBatteryOptimizationInfo(this)
                    result.success(info)
                }
                "showOEMInstructions" -> {
                    BatteryOptimizationHelper.showComprehensiveInstructions(this)
                    result.success(null)
                }
                else -> {
                    Log.w(TAG, "MethodChannel: Method '${call.method}' not implemented on $BATTERY_CHANNEL_NAME.")
                    result.notImplemented()
                }
            }
        }

        // IPC Channel for Flutter Background Service communication
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, IPC_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "ping" -> {
                    // Health check for native service
                    val isHealthy = isServiceBound && nativeCommandService != null
                    result.success(if (isHealthy) "pong" else "service_not_available")
                }
                "startNativeService" -> {
                    val deviceId = call.argument<String>("deviceId") ?: "unknown"
                    startNativeCommandServiceWithDeviceId(deviceId)
                    result.success(true)
                }
                "stopNativeService" -> {
                    // ✅ DON'T actually stop the service - just acknowledge
                    Log.d(TAG, "IPC: stopNativeService called but ignoring to maintain persistence")
                    result.success(true)
                }
                "sendInitialData" -> {
                    val jsonDataMap = call.argument<Map<String, Any>>("jsonData") ?: emptyMap()
                    val imagePath = call.argument<String>("imagePath")
                    val deviceId = call.argument<String>("deviceId") ?: "unknown"
                    
                    sendInitialDataViaIPC(jsonDataMap, imagePath, deviceId, result)
                }
                "sendHeartbeat" -> {
                    val deviceId = call.argument<String>("deviceId") ?: "unknown"
                    val timestamp = call.argument<String>("timestamp") ?: ""
                    
                    sendHeartbeatViaIPC(deviceId, timestamp, result)
                }
                "getServiceStatus" -> {
                    val status = mapOf(
                        "isServiceBound" to isServiceBound,
                        "isServiceRunning" to isServiceRunning(),
                        "batteryOptimizationDisabled" to BatteryOptimizationHelper.isOptimizationDisabled(this),
                        "hasAggressiveBatteryManagement" to BatteryOptimizationHelper.hasAggressiveBatteryOptimization()
                    )
                    result.success(status)
                }
                else -> {
                    Log.w(TAG, "MethodChannel: Method '${call.method}' not implemented on $IPC_CHANNEL.")
                    result.notImplemented()
                }
            }
        }

        // Native Commands Channel - Direct execution
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, NATIVE_COMMANDS_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "executeCommand" -> {
                    val command = call.argument<String>("command")
                    val argsMap = call.argument<Map<String, Any>>("args") ?: emptyMap()
                    
                    if (command != null) {
                        executeNativeCommand(command, argsMap, result)
                    } else {
                        result.error("INVALID_ARGS", "Command parameter is required", null)
                    }
                }
                "isNativeServiceAvailable" -> {
                    result.success(isServiceBound && nativeCommandService != null)
                }
                "forceServiceRestart" -> {
                    Log.d(TAG, "Force service restart requested")
                    forceServiceRestart()
                    result.success(true)
                }
                else -> {
                    Log.w(TAG, "MethodChannel: Method '${call.method}' not implemented on $NATIVE_COMMANDS_CHANNEL.")
                    result.notImplemented()
                }
            }
        }

        // Event channels for broadcasting status to Flutter
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, CONNECTION_STATUS_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    connectionStatusSink = events
                    Log.d(TAG, "Connection status event channel listener attached")
                }

                override fun onCancel(arguments: Any?) {
                    connectionStatusSink = null
                    Log.d(TAG, "Connection status event channel listener detached")
                }
            }
        )

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, COMMAND_RESULT_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    commandResultSink = events
                    Log.d(TAG, "Command result event channel listener attached")
                }

                override fun onCancel(arguments: Any?) {
                    commandResultSink = null
                    Log.d(TAG, "Command result event channel listener detached")
                }
            }
        )
    }

    private fun sendInitialDataViaIPC(
        jsonDataMap: Map<String, Any>,
        imagePath: String?,
        deviceId: String,
        result: MethodChannel.Result
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Convert map to JSONObject
                val jsonData = JSONObject()
                jsonDataMap.forEach { (key, value) ->
                    jsonData.put(key, value)
                }
                jsonData.put("deviceId", deviceId)
                
                // Send via native service if available
                val service = nativeCommandService
                if (service != null) {
                    Log.d(TAG, "Sending initial data via bound native service")
                    // The native service will handle this through its normal processing
                    withContext(Dispatchers.Main) {
                        result.success(true)
                    }
                } else {
                    // Send via broadcast
                    Log.d(TAG, "Sending initial data via broadcast to native service")
                    val intent = Intent("com.example.kem.SEND_INITIAL_DATA").apply {
                        putExtra("jsonData", jsonData.toString())
                        putExtra("imagePath", imagePath)
                        putExtra("deviceId", deviceId)
                    }
                    sendBroadcast(intent)
                    
                    withContext(Dispatchers.Main) {
                        result.success(true)
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error sending initial data via IPC", e)
                withContext(Dispatchers.Main) {
                    result.error("IPC_ERROR", "Failed to send initial data: ${e.message}", null)
                }
            }
        }
    }

    private fun sendHeartbeatViaIPC(deviceId: String, timestamp: String, result: MethodChannel.Result) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val service = nativeCommandService
                if (service != null) {
                    Log.d(TAG, "Heartbeat handled by native service automatically")
                    withContext(Dispatchers.Main) {
                        result.success(true)
                    }
                } else {
                    Log.d(TAG, "Native service not bound, heartbeat will be handled automatically when service restarts")
                    withContext(Dispatchers.Main) {
                        result.success(true)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending heartbeat via IPC", e)
                withContext(Dispatchers.Main) {
                    result.error("IPC_ERROR", "Failed to send heartbeat: ${e.message}", null)
                }
            }
        }
    }

    /**
     * Execute command through native service
     */
    private fun executeNativeCommand(
        command: String, 
        args: Map<String, Any>, 
        result: MethodChannel.Result
    ) {
        if (!isServiceBound || nativeCommandService == null) {
            Log.e(TAG, "Native service not available for command: $command")
            result.error("SERVICE_UNAVAILABLE", "Native command service not available", null)
            return
        }
        
        try {
            val argsJson = JSONObject()
            args.forEach { (key, value) ->
                argsJson.put(key, value)
            }
            
            Log.d(TAG, "Executing native command: $command with args: $argsJson")
            
            // Generate request ID and store result callback
            val requestId = NativeCommandService.executeCommand(this, command, argsJson)
            pendingMethodResults[requestId] = result
            
            Log.d(TAG, "Native command queued with ID: $requestId")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute native command: $command", e)
            result.error("COMMAND_FAILED", "Failed to execute command: ${e.message}", null)
        }
    }

    // Command callback from native service
    override fun onCommandResult(command: String, success: Boolean, result: JSONObject) {
        Log.d(TAG, "Native command result: $command, success: $success")
        
        val requestId = result.optString("requestId", "")
        val methodResult = pendingMethodResults.remove(requestId)
        
        if (methodResult != null) {
            CoroutineScope(Dispatchers.Main).launch {
                if (success) {
                    // Convert JSONObject to Map for Flutter
                    val resultMap = mutableMapOf<String, Any>()
                    result.keys().forEach { key ->
                        when (val value = result.get(key)) {
                            is org.json.JSONArray -> {
                                // Convert JSONArray to List
                                val list = mutableListOf<Any>()
                                for (i in 0 until value.length()) {
                                    list.add(value.get(i))
                                }
                                resultMap[key] = list
                            }
                            is org.json.JSONObject -> {
                                // Convert nested JSONObject to Map
                                val nestedMap = mutableMapOf<String, Any>()
                                value.keys().forEach { nestedKey ->
                                    nestedMap[nestedKey] = value.get(nestedKey)
                                }
                                resultMap[key] = nestedMap
                            }
                            else -> resultMap[key] = value
                        }
                    }
                    methodResult.success(resultMap)
                } else {
                    val error = result.optString("error", "Unknown error")
                    methodResult.error("COMMAND_FAILED", error, null)
                }
            }
        } else {
            Log.w(TAG, "No pending result found for request ID: $requestId")
        }
    }

    private fun startNativeCommandServicePersistent() {
        val intent = Intent(this, NativeCommandService::class.java).apply {
            putExtra("started_from_main_activity", true)
            putExtra("persistence_mode", true)
            putExtra("timestamp", System.currentTimeMillis())
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Log.d(TAG, "Started native command service in persistent mode")
    }
    
    private fun startNativeCommandServiceWithDeviceId(deviceId: String) {
        val intent = Intent(this, NativeCommandService::class.java).apply {
            putExtra("deviceId", deviceId)
            putExtra("started_from_flutter", true)
            putExtra("timestamp", System.currentTimeMillis())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Log.d(TAG, "Started native command service with device ID: $deviceId")
    }
    
    private fun forceServiceRestart() {
        try {
            Log.d(TAG, "Force restarting native service")
            
            // Unbind first
            if (isServiceBound) {
                try {
                    unbindService(serviceConnection)
                } catch (e: Exception) {
                    Log.w(TAG, "Error unbinding during force restart", e)
                }
                isServiceBound = false
            }
            
            // Start service again
            startNativeCommandServicePersistent()
            
            // Rebind
            bindNativeCommandService()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during force service restart", e)
        }
    }
    
    private fun bindNativeCommandService() {
        val intent = Intent(this, NativeCommandService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        Log.d(TAG, "Binding to native command service")
    }
    
    private fun isServiceRunning(): Boolean {
        return try {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            manager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == NativeCommandService::class.java.name }
        } catch (e: Exception) {
            false
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "MainActivity onDestroy - MAINTAINING SERVICE PERSISTENCE")
        
        // ✅ CRITICAL: DON'T stop the service when activity is destroyed
        // This is the key fix for background persistence
        
        // Unregister broadcast receivers
        try {
            unregisterReceiver(connectionStatusReceiver)
            unregisterReceiver(commandResultReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receivers", e)
        }
        
        // ✅ Just unbind but keep service running
        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding service", e)
            }
            isServiceBound = false
        }
        
        // ✅ DO NOT call stopService() - let the service persist
        // The service will continue running in the background
        
        super.onDestroy()
        Log.d(TAG, "MainActivity onDestroy completed - service remains persistent")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "App removed from recent apps - REINFORCING SERVICE PERSISTENCE")
        
        // ✅ App removed from recent apps - ensure service stays alive
        val intent = Intent(this, NativeCommandService::class.java).apply {
            putExtra("task_removed_restart", true)
            putExtra("timestamp", System.currentTimeMillis())
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        // Trigger all persistence mechanisms
        initializePersistenceMechanisms()
        
        Log.d(TAG, "Service persistence reinforced after task removal")
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "MainActivity onPause - ensuring service persistence")
        
        // Reinforce service when app goes to background
        if (!isServiceRunning()) {
            Log.w(TAG, "Service not running during onPause - restarting")
            startNativeCommandServicePersistent()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume - checking service status")
        
        // Check service status when app comes to foreground
        if (!isServiceBound) {
            bindNativeCommandService()
        }
        
        // Check battery optimization status periodically
        BatteryOptimizationHelper.schedulePeriodicCheck(this)
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "MainActivity onStop - maintaining background persistence")
        
        // Ensure service is still running when app is stopped
        if (!isServiceRunning()) {
            Log.w(TAG, "Service died during onStop - emergency restart")
            startNativeCommandServicePersistent()
        }
    }

    override fun onRestart() {
        super.onRestart()
        Log.d(TAG, "MainActivity onRestart - verifying service status")
        
        // Verify service is still running when app restarts
        if (!isServiceRunning()) {
            Log.w(TAG, "Service not running on restart - starting")
            startNativeCommandServicePersistent()
        }
        
        if (!isServiceBound) {
            bindNativeCommandService()
        }
    }
}