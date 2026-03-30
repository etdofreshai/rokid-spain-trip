package com.rokid.translator.glasses.viewmodel

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rokid.translator.common.Constants
import com.rokid.translator.common.protocol.ConnectionState
import com.rokid.translator.common.protocol.Message
import com.rokid.translator.common.protocol.MessageType
import com.rokid.translator.glasses.R
import com.rokid.translator.glasses.service.BluetoothClientState
import com.rokid.translator.glasses.service.BluetoothSppClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream

data class GlassesUiState(
    val isConnected: Boolean = false,
    val isListening: Boolean = false,
    val isProcessing: Boolean = false,
    val displayText: String = "",
    val hintText: String = "",
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val bluetoothState: BluetoothClientState = BluetoothClientState.DISCONNECTED,
    val connectedDeviceName: String? = null,
    val availableDevices: List<BluetoothDevice> = emptyList(),
    // Translation-specific
    val detectedLanguage: String = "",
    val translationText: String = ""
)

class GlassesViewModel(private val context: Context) : ViewModel() {
    
    companion object {
        private const val TAG = "GlassesViewModel"
    }
    
    private val _uiState = MutableStateFlow(GlassesUiState(
        displayText = "Rokid Translator",
        hintText = "Tap to speak"
    ))
    val uiState: StateFlow<GlassesUiState> = _uiState.asStateFlow()
    
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val bluetoothClient = BluetoothSppClient(context, viewModelScope)
    private val audioBuffer = ByteArrayOutputStream()
    
    init {
        initializeBluetooth()
    }
    
    private fun initializeBluetooth() {
        viewModelScope.launch {
            bluetoothClient.connectionState.collect { state ->
                val connState = when (state) {
                    BluetoothClientState.DISCONNECTED -> ConnectionState.DISCONNECTED
                    BluetoothClientState.CONNECTING -> ConnectionState.CONNECTING
                    BluetoothClientState.CONNECTED -> ConnectionState.CONNECTED
                }
                _uiState.update { it.copy(
                    bluetoothState = state,
                    connectionState = connState,
                    isConnected = state == BluetoothClientState.CONNECTED,
                    displayText = when (state) {
                        BluetoothClientState.DISCONNECTED -> context.getString(R.string.not_connected)
                        BluetoothClientState.CONNECTING -> context.getString(R.string.connecting_status)
                        BluetoothClientState.CONNECTED -> context.getString(R.string.connected_ready)
                    },
                    hintText = when (state) {
                        BluetoothClientState.DISCONNECTED -> "Tap to select device"
                        BluetoothClientState.CONNECTING -> "Please wait"
                        BluetoothClientState.CONNECTED -> context.getString(R.string.tap_to_speak)
                    }
                )}
            }
        }
        
        viewModelScope.launch {
            bluetoothClient.connectedDeviceName.collect { name ->
                _uiState.update { it.copy(connectedDeviceName = name) }
            }
        }
        
        viewModelScope.launch {
            bluetoothClient.messageFlow.collect { message -> handlePhoneMessage(message) }
        }
        
        refreshPairedDevices()
    }
    
    fun refreshPairedDevices() {
        _uiState.update { it.copy(availableDevices = bluetoothClient.getPairedDevices()) }
    }
    
    fun connectToDevice(device: BluetoothDevice) {
        bluetoothClient.connect(device)
    }
    
    fun disconnectBluetooth() { bluetoothClient.disconnect() }
    
    fun startRecording() {
        if (_uiState.value.bluetoothState != BluetoothClientState.CONNECTED) {
            _uiState.update { it.copy(displayText = "Connect phone first", hintText = "Tap to select device") }
            return
        }
        if (_uiState.value.isListening) return
        
        audioBuffer.reset()
        _uiState.update { it.copy(
            isListening = true,
            displayText = context.getString(R.string.listening),
            hintText = context.getString(R.string.tap_stop),
            translationText = "",
            detectedLanguage = ""
        )}
        
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _uiState.update { it.copy(displayText = "Mic permission needed", isListening = false) }
            return
        }
        
        viewModelScope.launch { bluetoothClient.sendVoiceStart() }
        
        recordingJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val bufferSize = AudioRecord.getMinBufferSize(Constants.AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, Constants.AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
                
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    withContext(Dispatchers.Main) { _uiState.update { it.copy(displayText = "Mic init failed", isListening = false) } }
                    return@launch
                }
                
                audioRecord?.startRecording()
                val buffer = ByteArray(Constants.AUDIO_BUFFER_SIZE)
                while (isActive && _uiState.value.isListening) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) synchronized(audioBuffer) { audioBuffer.write(buffer, 0, read) }
                }
            } catch (e: SecurityException) {
                withContext(Dispatchers.Main) { _uiState.update { it.copy(displayText = "Mic permission error", isListening = false) } }
            } finally {
                try { audioRecord?.stop() } catch (_: Exception) {}
                try { audioRecord?.release() } catch (_: Exception) {}
                audioRecord = null
            }
        }
    }
    
    fun stopRecording() {
        _uiState.update { it.copy(
            isListening = false,
            isProcessing = true,
            displayText = context.getString(R.string.sending),
            hintText = "Please wait"
        )}
        
        recordingJob?.cancel()
        recordingJob = null
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val audioData: ByteArray
                synchronized(audioBuffer) { audioData = audioBuffer.toByteArray() }
                
                if (audioData.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(isProcessing = false, displayText = context.getString(R.string.no_voice), hintText = context.getString(R.string.try_again)) }
                    }
                    return@launch
                }
                
                val success = bluetoothClient.sendVoiceEnd(audioData)
                if (!success) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(isProcessing = false, displayText = "Send failed", hintText = "Check Bluetooth") }
                    }
                    return@launch
                }
                
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(displayText = context.getString(R.string.translating), hintText = "AI processing") }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isProcessing = false, displayText = "Error: ${e.message}", hintText = context.getString(R.string.try_again)) }
                }
            }
        }
    }
    
    fun toggleRecording() {
        if (_uiState.value.isListening) stopRecording() else startRecording()
    }
    
    private fun handlePhoneMessage(message: Message) {
        when (message.type) {
            MessageType.AI_PROCESSING -> {
                _uiState.update { it.copy(isProcessing = true, displayText = message.payload ?: "Processing...") }
            }
            MessageType.USER_TRANSCRIPT -> {
                _uiState.update { it.copy(displayText = "You: ${message.payload ?: ""}") }
            }
            MessageType.AI_RESPONSE_TEXT -> {
                _uiState.update { it.copy(
                    isProcessing = false,
                    translationText = message.payload ?: "",
                    displayText = message.payload ?: "",
                    hintText = context.getString(R.string.tap_to_speak)
                )}
            }
            MessageType.AI_ERROR -> {
                _uiState.update { it.copy(
                    isProcessing = false,
                    displayText = "Error: ${message.payload ?: ""}",
                    hintText = context.getString(R.string.try_again)
                )}
            }
            MessageType.DISPLAY_TEXT -> {
                _uiState.update { it.copy(displayText = message.payload ?: "") }
            }
            MessageType.DISPLAY_CLEAR -> {
                _uiState.update { it.copy(displayText = "", hintText = context.getString(R.string.tap_to_speak)) }
            }
            MessageType.HEARTBEAT -> {
                viewModelScope.launch { bluetoothClient.sendMessage(Message(type = MessageType.HEARTBEAT_ACK)) }
            }
            else -> Log.d(TAG, "Unhandled: ${message.type}")
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        recordingJob?.cancel()
        audioRecord?.release()
        bluetoothClient.disconnect()
    }
    
    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(GlassesViewModel::class.java))
                return GlassesViewModel(context.applicationContext) as T
            throw IllegalArgumentException("Unknown ViewModel")
        }
    }
}
