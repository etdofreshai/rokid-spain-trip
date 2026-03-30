package com.rokid.translator.service.cxr

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.util.Log
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.*
import com.rokid.cxr.client.extend.listeners.AiEventListener
import com.rokid.cxr.client.utils.ValueUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * CXR-M SDK Manager (Phone Side) - adapted for translator app.
 */
class CxrMobileManager(private val context: Context) {
    
    companion object {
        private const val TAG = "CxrMobileManager"
        private const val MAX_RETRY_COUNT = 5
        private const val INITIAL_RETRY_DELAY_MS = 5000L
        private const val MAX_RETRY_DELAY_MS = 60000L
        private const val DEINIT_SETTLE_DELAY_MS = 300L
        
        fun isSdkAvailable(): Boolean {
            return try {
                Class.forName("com.rokid.cxr.client.extend.CxrApi")
                true
            } catch (e: ClassNotFoundException) { false }
        }
    }
    
    sealed class BluetoothState {
        object Disconnected : BluetoothState()
        object Connecting : BluetoothState()
        data class Connected(val socketUuid: String, val macAddress: String) : BluetoothState()
        data class Failed(val error: String) : BluetoothState()
    }
    
    private val _bluetoothState = MutableStateFlow<BluetoothState>(BluetoothState.Disconnected)
    val bluetoothState: StateFlow<BluetoothState> = _bluetoothState.asStateFlow()
    
    private var onAiKeyDown: (() -> Unit)? = null
    private var onAiKeyUp: (() -> Unit)? = null
    
    private var glassSocketUuid: String? = null
    private var glassMacAddress: String? = null
    private var retryCount = 0
    private var lastConnectedDevice: BluetoothDevice? = null
    private var retryJob: Job? = null
    private val retryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val bluetoothMutex = Mutex()
    @Volatile private var isCallbackRegistered = false
    
    private val cxrApi: CxrApi by lazy { CxrApi.getInstance() }
    
    private val bluetoothCallback = object : BluetoothStatusCallback {
        override fun onConnectionInfo(socketUuid: String?, macAddress: String?, rokidAccount: String?, glassesType: Int) {
            if (!isCallbackRegistered) return
            if (socketUuid != null && macAddress != null) {
                glassSocketUuid = socketUuid; glassMacAddress = macAddress
                connectBluetooth(context, socketUuid, macAddress)
            } else {
                _bluetoothState.value = BluetoothState.Failed("Invalid connection info")
            }
        }
        override fun onConnected() {
            if (!isCallbackRegistered) return
            retryCount = 0; retryJob?.cancel()
            _bluetoothState.value = BluetoothState.Connected(glassSocketUuid ?: "", glassMacAddress ?: "")
        }
        override fun onDisconnected() {
            if (!isCallbackRegistered) return
            _bluetoothState.value = BluetoothState.Disconnected
        }
        override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) {
            if (!isCallbackRegistered) return
            _bluetoothState.value = BluetoothState.Failed("Connection failed: $errorCode")
            scheduleRetry()
        }
    }
    
    private val aiEventListener = object : AiEventListener {
        override fun onAiKeyDown() { onAiKeyDown?.invoke() }
        override fun onAiKeyUp() { onAiKeyUp?.invoke() }
        override fun onAiExit() {}
    }
    
    fun initBluetooth(device: BluetoothDevice): Boolean {
        if (!isSdkAvailable()) return false
        retryCount = 0; retryJob?.cancel(); lastConnectedDevice = device
        _bluetoothState.value = BluetoothState.Connecting
        retryScope.launch { initBluetoothInternal(device) }
        return true
    }
    
    @SuppressLint("MissingPermission")
    private suspend fun initBluetoothInternal(device: BluetoothDevice) {
        bluetoothMutex.withLock {
            try {
                isCallbackRegistered = false
                try { cxrApi.deinitBluetooth() } catch (_: Exception) {}
                delay(DEINIT_SETTLE_DELAY_MS)
                cxrApi.initBluetooth(context, device, bluetoothCallback)
                isCallbackRegistered = true
            } catch (e: Exception) {
                isCallbackRegistered = false
                _bluetoothState.value = BluetoothState.Failed(e.message ?: "Unknown error")
            }
        }
    }
    
    private fun connectBluetooth(context: Context, socketUuid: String, macAddress: String) {
        try { cxrApi.connectBluetooth(context, socketUuid, macAddress, bluetoothCallback, null, null) }
        catch (e: Exception) { _bluetoothState.value = BluetoothState.Failed(e.message ?: "Error") }
    }
    
    fun disconnectBluetooth() {
        try {
            isCallbackRegistered = false; retryJob?.cancel(); retryCount = 0; lastConnectedDevice = null
            cxrApi.deinitBluetooth()
            _bluetoothState.value = BluetoothState.Disconnected
        } catch (_: Exception) {}
    }
    
    private fun scheduleRetry() {
        val device = lastConnectedDevice ?: return
        if (retryCount >= MAX_RETRY_COUNT) return
        retryCount++
        val delayMs = (INITIAL_RETRY_DELAY_MS * (1L shl (retryCount - 1).coerceAtMost(4))).coerceAtMost(MAX_RETRY_DELAY_MS)
        retryJob?.cancel()
        retryJob = retryScope.launch {
            delay(delayMs)
            _bluetoothState.value = BluetoothState.Connecting
            initBluetoothInternal(device)
        }
    }
    
    fun setAiEventListener(onKeyDown: (() -> Unit)? = null, onKeyUp: (() -> Unit)? = null) {
        this.onAiKeyDown = onKeyDown; this.onAiKeyUp = onKeyUp
        cxrApi.setAiEventListener(aiEventListener)
    }
    
    fun sendTtsContent(content: String): ValueUtil.CxrStatus? {
        return try { cxrApi.sendTtsContent(content) } catch (_: Exception) { null }
    }
    
    fun release() {
        try { retryJob?.cancel(); retryScope.cancel(); disconnectBluetooth() } catch (_: Exception) {}
    }
}
