package com.rokid.translator.service

import com.rokid.translator.common.protocol.Message
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridge between TranslatorService and UI.
 */
object ServiceBridge {
    // Translation results for UI display
    data class TranslationResult(
        val originalText: String,
        val detectedLanguage: String,
        val localTranslation: String?,
        val cloudTranslation: String?,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    private val _translationFlow = MutableSharedFlow<TranslationResult>(replay = 1)
    val translationFlow: SharedFlow<TranslationResult> = _translationFlow.asSharedFlow()
    
    private val _serviceStateFlow = MutableStateFlow(false)
    val serviceStateFlow: StateFlow<Boolean> = _serviceStateFlow.asStateFlow()
    
    private val _bluetoothStateFlow = MutableStateFlow(BluetoothConnectionState.DISCONNECTED)
    val bluetoothStateFlow: StateFlow<BluetoothConnectionState> = _bluetoothStateFlow.asStateFlow()
    
    private val _connectedDeviceNameFlow = MutableStateFlow<String?>(null)
    val connectedDeviceNameFlow: StateFlow<String?> = _connectedDeviceNameFlow.asStateFlow()
    
    private val _isListeningFlow = MutableStateFlow(false)
    val isListeningFlow: StateFlow<Boolean> = _isListeningFlow.asStateFlow()
    
    private val _statusTextFlow = MutableStateFlow("Ready")
    val statusTextFlow: StateFlow<String> = _statusTextFlow.asStateFlow()
    
    suspend fun emitTranslation(result: TranslationResult) { _translationFlow.emit(result) }
    fun updateServiceState(running: Boolean) { _serviceStateFlow.value = running }
    fun updateBluetoothState(state: BluetoothConnectionState) { _bluetoothStateFlow.value = state }
    fun updateConnectedDeviceName(name: String?) { _connectedDeviceNameFlow.value = name }
    fun updateListeningState(listening: Boolean) { _isListeningFlow.value = listening }
    fun updateStatus(text: String) { _statusTextFlow.value = text }
}
