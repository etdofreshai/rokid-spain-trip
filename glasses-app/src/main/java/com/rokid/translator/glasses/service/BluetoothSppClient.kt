package com.rokid.translator.glasses.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import com.rokid.translator.common.protocol.Message
import com.rokid.translator.common.protocol.MessageType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

enum class BluetoothClientState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * Glasses-side Bluetooth SPP Client.
 * Connects to phone, sends voice data, receives translation results.
 */
class BluetoothSppClient(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "BluetoothSppClient"
        val SERVICE_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        private const val MESSAGE_DELIMITER = "\n"
    }
    
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    
    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var connectJob: Job? = null
    private var readJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastConnectedDevice: BluetoothDevice? = null
    @Volatile private var missedHeartbeatCount = 0
    
    private val _connectionState = MutableStateFlow(BluetoothClientState.DISCONNECTED)
    val connectionState: StateFlow<BluetoothClientState> = _connectionState.asStateFlow()
    
    private val _messageFlow = MutableSharedFlow<Message>(extraBufferCapacity = 16)
    val messageFlow: SharedFlow<Message> = _messageFlow.asSharedFlow()
    
    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()
    
    private fun getSafeDeviceName(device: BluetoothDevice): String {
        val hasPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        return if (hasPerm) device.name ?: "unknown" else "unknown"
    }
    
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> {
        if (!hasBluetoothPermission()) return emptyList()
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }
    
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice, maxRetries: Int = 5) {
        if (!hasBluetoothPermission()) return
        if (_connectionState.value != BluetoothClientState.DISCONNECTED) return
        
        connectJob?.cancel()
        connectJob = scope.launch(Dispatchers.IO) {
            for (attempt in 1..maxRetries) {
                try {
                    _connectionState.value = BluetoothClientState.CONNECTING
                    bluetoothAdapter?.cancelDiscovery()
                    delay(200)
                    closeSocket()
                    
                    socket = if (attempt <= 2) {
                        device.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
                    } else {
                        device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                    }
                    
                    socket?.connect()
                    if (socket?.isConnected != true) throw IOException("Not connected")
                    
                    inputStream = socket?.inputStream
                    outputStream = socket?.outputStream
                    _connectionState.value = BluetoothClientState.CONNECTED
                    _connectedDeviceName.value = getSafeDeviceName(device)
                    lastConnectedDevice = device
                    missedHeartbeatCount = 0
                    
                    startReading()
                    startHeartbeat()
                    return@launch
                } catch (e: Exception) {
                    Log.e(TAG, "Attempt $attempt failed: ${e.message}")
                    closeSocket()
                    if (attempt < maxRetries) delay(2500L + attempt * 1500L)
                }
            }
            _connectionState.value = BluetoothClientState.DISCONNECTED
        }
    }
    
    fun disconnect() {
        connectJob?.cancel(); readJob?.cancel(); heartbeatJob?.cancel()
        closeSocket()
        _connectionState.value = BluetoothClientState.DISCONNECTED
        _connectedDeviceName.value = null
    }
    
    suspend fun sendMessage(message: Message): Boolean {
        if (_connectionState.value != BluetoothClientState.CONNECTED) return false
        return withContext(Dispatchers.IO) {
            try {
                outputStream?.write((message.toJson() + MESSAGE_DELIMITER).toByteArray(Charsets.UTF_8))
                outputStream?.flush()
                true
            } catch (e: IOException) {
                handleDisconnection()
                false
            }
        }
    }
    
    suspend fun sendVoiceStart(): Boolean = sendMessage(Message(type = MessageType.VOICE_START))
    
    suspend fun sendVoiceEnd(audioData: ByteArray): Boolean =
        sendMessage(Message(type = MessageType.VOICE_END, binaryData = audioData))
    
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        missedHeartbeatCount = 0
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive && _connectionState.value == BluetoothClientState.CONNECTED) {
                delay(10_000)
                if (_connectionState.value == BluetoothClientState.CONNECTED) {
                    missedHeartbeatCount++
                    try { sendMessage(Message(type = MessageType.HEARTBEAT)) } catch (_: Exception) {}
                    if (missedHeartbeatCount >= 3) {
                        handleDisconnection()
                        break
                    }
                }
            }
        }
    }
    
    private fun startReading() {
        readJob?.cancel()
        readJob = scope.launch(Dispatchers.IO) {
            val buffer = StringBuilder()
            val readBuffer = ByteArray(4096)
            
            while (isActive && _connectionState.value == BluetoothClientState.CONNECTED) {
                try {
                    val bytesRead = inputStream?.read(readBuffer) ?: -1
                    if (bytesRead == -1) break
                    
                    buffer.append(String(readBuffer, 0, bytesRead, Charsets.UTF_8))
                    
                    var idx: Int
                    while (buffer.indexOf(MESSAGE_DELIMITER).also { idx = it } >= 0) {
                        val msgStr = buffer.substring(0, idx)
                        buffer.delete(0, idx + MESSAGE_DELIMITER.length)
                        if (msgStr.isNotBlank()) parseAndEmitMessage(msgStr)
                    }
                } catch (e: IOException) { break }
            }
            handleDisconnection()
        }
    }
    
    private suspend fun parseAndEmitMessage(jsonStr: String) {
        val jsonStart = jsonStr.indexOf('{')
        if (jsonStart < 0) return
        val json = if (jsonStart > 0) jsonStr.substring(jsonStart) else jsonStr
        
        try {
            val obj = JSONObject(json)
            val type = MessageType.fromCode(obj.optInt("type", -1)) ?: return
            if (type == MessageType.HEARTBEAT_ACK) { missedHeartbeatCount = 0; return }
            
            val payload = if (obj.has("payload")) obj.getString("payload") else null
            val binaryData = if (obj.has("binaryData")) {
                try { android.util.Base64.decode(obj.getString("binaryData"), android.util.Base64.DEFAULT) }
                catch (_: Exception) { null }
            } else null
            
            _messageFlow.emit(Message(type = type, payload = payload, binaryData = binaryData))
        } catch (_: Exception) {}
    }
    
    private suspend fun handleDisconnection() {
        if (_connectionState.value == BluetoothClientState.DISCONNECTED) return
        heartbeatJob?.cancel()
        closeSocket()
        _connectionState.value = BluetoothClientState.DISCONNECTED
        _connectedDeviceName.value = null
        
        val device = lastConnectedDevice
        if (device != null) {
            delay(2000)
            if (_connectionState.value == BluetoothClientState.DISCONNECTED) connect(device)
        }
    }
    
    private fun closeSocket() {
        try { outputStream?.flush() } catch (_: Exception) {}
        try { inputStream?.close() } catch (_: IOException) {}
        try { outputStream?.close() } catch (_: IOException) {}
        try { socket?.close() } catch (_: IOException) {}
        inputStream = null; outputStream = null; socket = null
    }
    
    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
    }
}
