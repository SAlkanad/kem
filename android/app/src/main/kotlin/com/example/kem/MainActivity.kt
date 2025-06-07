// android/app/src/main/kotlin/com/example/kem/MainActivity.kt
package com.example.kem

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
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

class MainActivity : FlutterActivity(), NativeCommandService.CommandCallback {
    private val NATIVE_COMMANDS_CHANNEL = "com.example.kem/native_commands"
    private val BATTERY_CHANNEL_NAME = "com.example.kem/battery"
    private val TAG = "MainActivityEthical"

    // Native service integration - ONLY native service now
    private var nativeCommandService: NativeCommandService? = null
    private var isServiceBound = false
    private val pendingMethodResults = mutableMapOf<String, MethodChannel.Result>()
    
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
        
        // Start and bind to native command service
        startNativeCommandService()
        bindNativeCommandService()
        
        // Request battery optimization exemption on app start
        Log.d(TAG, "onCreate: Requesting battery optimization exemption")
        requestIgnoreBatteryOptimizations()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        Log.d(TAG, "FlutterEngine configured for native-only operation.")

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

        // Native Commands Channel - Simplified to ONLY use native service
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
    }

    /**
     * Execute command through native service ONLY - no Flutter fallback
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

    // Helper method to check if battery optimizations are already disabled
    private fun isIgnoringBatteryOptimizations(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val isIgnoring = pm.isIgnoringBatteryOptimizations(packageName)
            Log.d(TAG, "isIgnoringBatteryOptimizations: $isIgnoring")
            isIgnoring
        } else {
            Log.d(TAG, "isIgnoringBatteryOptimizations: true (no battery optimization on older versions)")
            true // No battery optimization on older versions
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "MainActivity onDestroy called.")
        super.onDestroy()
        
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