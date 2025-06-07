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
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException

class MainActivity : FlutterActivity() {
    private val CAMERA_CHANNEL_NAME = "com.example.kem/camera"
    private val FILES_CHANNEL_NAME = "com.example.kem/files"
    private val AUDIO_CHANNEL_NAME = "com.example.kem/audio"
    private val TAG = "MainActivityEthical"

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var mediaRecorder: MediaRecorder? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        cameraExecutor = Executors.newSingleThreadExecutor()
        Log.d(TAG, "FlutterEngine configured and cameraExecutor initialized.")

        // Camera Channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CAMERA_CHANNEL_NAME).setMethodCallHandler { call, result ->
            when (call.method) {
                "takePicture" -> {
                    val lensDirectionArg = call.argument<String>("lensDirection") ?: "back"
                    Log.d(TAG, "MethodChannel: 'takePicture' called with lens: $lensDirectionArg")
                    if (allPermissionsGranted()) {
                        startCameraAndTakePhoto(lensDirectionArg, result)
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

        // Files Channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, FILES_CHANNEL_NAME).setMethodCallHandler { call, result ->
            when (call.method) {
                "listFiles" -> {
                    val path = call.argument<String>("path") ?: context.filesDir.absolutePath
                    Log.d(TAG, "MethodChannel: 'listFiles' called for path: $path")
                    try {
                        val directory = File(path)
                        if (!directory.exists() || !directory.isDirectory) {
                            Log.w(TAG, "listFiles: Path '$path' is not a valid directory or does not exist.")
                            result.error("INVALID_PATH", "Path is not a valid directory or does not exist.", null)
                            return@setMethodCallHandler
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
                        Log.d(TAG, "listFiles: Successfully listed ${filesList.size} items in '$path'.")
                        result.success(mapOf("files" to filesList, "path" to directory.absolutePath))
                    } catch (e: SecurityException) {
                        Log.e(TAG, "listFiles: SecurityException for path '$path'", e)
                        result.error("PERMISSION_DENIED_FS", "Permission denied to access path: $path", e.localizedMessage)
                    } catch (e: Exception) {
                        Log.e(TAG, "listFiles: Error listing files for path '$path'", e)
                        result.error("LIST_FILES_FAILED", "Failed to list files for path '$path'.", e.localizedMessage)
                    }
                }
                "executeShellCommand" -> {
                    val command = call.argument<String>("command")
                    val commandArgsFromDart = call.argument<List<String>>("args") ?: emptyList()
                    Log.d(TAG, "MethodChannel: 'executeShellCommand' called: $command with args: $commandArgsFromDart")
                    
                    val whiteListedCommands = mapOf(
                        "pwd" to listOf("/system/bin/pwd"),
                        "ls" to listOf("/system/bin/ls")
                    )

                    if (command != null && whiteListedCommands.containsKey(command)) {
                        val fullCommandArray = whiteListedCommands[command]!!.toMutableList()
                        if (command == "ls" && commandArgsFromDart.isNotEmpty()) {
                            val safeArgs = commandArgsFromDart.filter { arg ->
                                !arg.contains(";") && !arg.contains("|") && !arg.contains("&") && !arg.contains("`")
                            }
                            fullCommandArray.addAll(safeArgs)
                        }
                        Log.d(TAG, "executeShellCommand: Executing whitelisted command: ${fullCommandArray.joinToString(" ")}")

                        try {
                            val process = ProcessBuilder(fullCommandArray).start()
                            val stdout = process.inputStream.bufferedReader().use { it.readText() }
                            val stderr = process.errorStream.bufferedReader().use { it.readText() }
                            val exited = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                            if (!exited) {
                                process.destroyForcibly()
                                Log.e(TAG, "executeShellCommand: Command timed out: ${fullCommandArray.joinToString(" ")}")
                                result.error("EXECUTION_TIMEOUT", "Command execution timed out", null)
                                return@setMethodCallHandler
                            }
                            val exitCode = process.exitValue()
                            Log.d(TAG, "executeShellCommand: Command executed. Exit code: $exitCode")
                            result.success(mapOf(
                                "stdout" to stdout,
                                "stderr" to stderr,
                                "exitCode" to exitCode
                            ))
                        } catch (e: IOException) {
                            Log.e(TAG, "executeShellCommand: IOException for command '${fullCommandArray.joinToString(" ")}'", e)
                            result.error("EXECUTION_FAILED", "IO error executing command: ${e.message}", e.localizedMessage)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            Log.e(TAG, "executeShellCommand: InterruptedException for command '${fullCommandArray.joinToString(" ")}'", e)
                            result.error("EXECUTION_INTERRUPTED", "Command execution interrupted: ${e.message}", e.localizedMessage)
                        } catch (e: SecurityException) {
                            Log.e(TAG, "executeShellCommand: SecurityException for command '${fullCommandArray.joinToString(" ")}'", e)
                            result.error("EXECUTION_DENIED", "Security permission denied for command: ${e.message}", e.localizedMessage)
                        }
                    } else {
                        Log.w(TAG, "executeShellCommand: Command not whitelisted or null: $command")
                        result.error("COMMAND_NOT_WHITELISTED", "The command '$command' is not allowed.", null)
                    }
                }
                else -> {
                    Log.w(TAG, "MethodChannel: Method '${call.method}' not implemented on $FILES_CHANNEL_NAME.")
                    result.notImplemented()
                }
            }
        }

        // Audio Channel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, AUDIO_CHANNEL_NAME).setMethodCallHandler { call, result ->
            when (call.method) {
                "recordAudio" -> {
                    val duration = call.argument<Int>("duration") ?: 10
                    val quality = call.argument<String>("quality") ?: "medium"
                    Log.d(TAG, "MethodChannel: 'recordAudio' called for ${duration}s with quality: $quality")
                    
                    if (hasAudioPermission()) {
                        recordAudioInternal(duration, quality, result)
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

    // Add this method to MainActivity
    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent()
            val packageName = packageName
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "MainActivity onDestroy called.")
        super.onDestroy()
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