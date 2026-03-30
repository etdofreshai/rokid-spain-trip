package com.rokid.translator.service

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

enum class BluetoothConnectionState {
    DISCONNECTED, LISTENING, CONNECTING, CONNECTED
}

/**
 * Phone-side Bluetooth SPP Manager.
 * Listens for connections from glasses, receives voice data, sends translation results.
 */
class BluetoothSppManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "BluetoothSppManager"
        private const val SERVICE_NAME = "RokidTranslator"
        private val APP_UUID: UUID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        private const val BUFFER_SIZE = 8192
    }
    
    private val bluetoothAdapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    
    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    
    private var acceptJob: Job? = null
    private var readJob: Job? = null
    
    private val _connectionState = MutableStateFlow(BluetoothConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BluetoothConnectionState> = _connectionState.asStateFlow()
    
    private val _messageFlow = MutableSharedFlow<Message>(replay = 0, extraBufferCapacity = 100)
    val messageFlow: SharedFlow<Message> = _messageFlow.asSharedFlow()
    
    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()
    
    private var _connectedDevice: BluetoothDevice? = null
    val connectedDevice: BluetoothDevice? get() = _connectedDevice
    
    private val audioBuffer = mutableListOf<ByteArray>()
    
    @Volatile private var isDisconnecting = false
    private val disconnectLock = Any()
    
    fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
    }
    
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true
    
    fun startListening() {
        if (!hasBluetoothPermission() || bluetoothAdapter == null) return
        stopListening()
        
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    _connectionState.value = BluetoothConnectionState.LISTENING
                    serverSocket = try {
                        bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
                    } catch (e: Exception) {
                        bluetoothAdapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, APP_UUID)
                    }
                    
                    val socket = serverSocket?.accept()
                    if (socket != null) {
                        handleConnection(socket)
                        readJob?.join()
                        delay(1000)
                    }
                } catch (e: SecurityException) {
                    _connectionState.value = BluetoothConnectionState.DISCONNECTED
                    break
                } catch (e: IOException) {
                    if (_connectionState.value != BluetoothConnectionState.DISCONNECTED) delay(500)
                    else break
                } catch (e: CancellationException) { break }
                finally {
                    try { serverSocket?.close() } catch (_: IOException) {}
                    serverSocket = null
                }
            }
        }
    }
    
    private suspend fun handleConnection(socket: BluetoothSocket) {
        clientSocket = socket
        inputStream = socket.inputStream
        outputStream = socket.outputStream
        
        try {
            _connectedDevice = socket.remoteDevice
            _connectedDeviceName.value = socket.remoteDevice.name
        } catch (e: SecurityException) {
            _connectedDevice = socket.remoteDevice
            _connectedDeviceName.value = "Unknown"
        }
        
        _connectionState.value = BluetoothConnectionState.CONNECTED
        startReading()
    }
    
    private fun startReading() {
        readJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_SIZE)
            val messageBuffer = StringBuilder()
            
            try {
                while (isActive && _connectionState.value == BluetoothConnectionState.CONNECTED) {
                    val bytesRead = inputStream?.read(buffer) ?: -1
                    if (bytesRead == -1) break
                    
                    if (bytesRead > 0) {
                        val text = String(buffer, 0, bytesRead, Charsets.UTF_8)
                        messageBuffer.append(text)
                        
                        var newlineIndex: Int
                        while (messageBuffer.indexOf("\n").also { newlineIndex = it } != -1) {
                            val messageJson = messageBuffer.substring(0, newlineIndex)
                            messageBuffer.delete(0, newlineIndex + 1)
                            
                            if (messageJson.isNotEmpty()) {
                                try {
                                    val message = Message.fromJson(messageJson) ?: continue
                                    when (message.type) {
                                        MessageType.HEARTBEAT -> {
                                            scope.launch { sendMessage(Message(type = MessageType.HEARTBEAT_ACK)) }
                                        }
                                        MessageType.VOICE_START -> {
                                            audioBuffer.clear()
                                            _messageFlow.emit(message)
                                        }
                                        MessageType.VOICE_DATA -> {
                                            message.binaryData?.let { audioBuffer.add(it) }
                                        }
                                        MessageType.VOICE_END -> {
                                            val fullAudio = if (message.binaryData != null && message.binaryData.isNotEmpty()) {
                                                message.binaryData
                                            } else {
                                                audioBuffer.flatMap { it.toList() }.toByteArray()
                                            }
                                            audioBuffer.clear()
                                            _messageFlow.emit(Message(type = MessageType.VOICE_END, binaryData = fullAudio))
                                        }
                                        else -> _messageFlow.emit(message)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Parse error", e)
                                }
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                if (_connectionState.value == BluetoothConnectionState.CONNECTED)
                    Log.e(TAG, "Read error", e)
            } finally {
                if (_connectionState.value == BluetoothConnectionState.CONNECTED)
                    handleDisconnection()
            }
        }
    }
    
    suspend fun sendMessage(message: Message): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (_connectionState.value != BluetoothConnectionState.CONNECTED) return@withContext false
                val json = message.toJson() + "\n"
                outputStream?.write(json.toByteArray(Charsets.UTF_8))
                outputStream?.flush()
                true
            } catch (e: IOException) {
                Log.e(TAG, "Send failed", e)
                false
            }
        }
    }
    
    fun stopListening() {
        acceptJob?.cancel()
        acceptJob = null
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
    }
    
    private fun handleDisconnection() {
        synchronized(disconnectLock) {
            if (isDisconnecting) return
            isDisconnecting = true
        }
        try { inputStream?.close() } catch (_: IOException) {}
        try { outputStream?.close() } catch (_: IOException) {}
        try { clientSocket?.close() } catch (_: IOException) {}
        inputStream = null; outputStream = null; clientSocket = null
        _connectionState.value = BluetoothConnectionState.DISCONNECTED
        _connectedDevice = null; _connectedDeviceName.value = null
        audioBuffer.clear()
        synchronized(disconnectLock) { isDisconnecting = false }
    }
    
    fun disconnect(restartListening: Boolean = true) {
        synchronized(disconnectLock) {
            if (isDisconnecting) return
            isDisconnecting = true
        }
        _connectionState.value = BluetoothConnectionState.DISCONNECTED
        readJob?.cancel(); readJob = null
        try { inputStream?.close() } catch (_: IOException) {}
        try { outputStream?.close() } catch (_: IOException) {}
        try { clientSocket?.close() } catch (_: IOException) {}
        inputStream = null; outputStream = null; clientSocket = null
        _connectedDevice = null; _connectedDeviceName.value = null
        audioBuffer.clear()
        
        if (restartListening) {
            scope.launch(Dispatchers.IO) {
                try { delay(500); stopListening(); delay(200); startListening() }
                finally { synchronized(disconnectLock) { isDisconnecting = false } }
            }
        } else {
            synchronized(disconnectLock) { isDisconnecting = false }
        }
    }
}
