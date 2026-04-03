package com.rokid.translator.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rokid.translator.data.LanguagePair
import com.rokid.translator.data.SettingsRepository
import com.rokid.translator.service.BluetoothConnectionState
import com.rokid.translator.service.ServiceBridge
import com.rokid.translator.service.TranslatorService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class TranslatorUiState(
    val isServiceRunning: Boolean = false,
    val bluetoothState: BluetoothConnectionState = BluetoothConnectionState.DISCONNECTED,
    val connectedDeviceName: String? = null,
    val isListening: Boolean = false,
    val isSttActive: Boolean = false,
    val statusText: String = "Ready",
    val languagePair: LanguagePair = LanguagePair.ES_EN,
    val useCloudTranslation: Boolean = true,
    val geminiApiKey: String = "",
    val translations: List<ServiceBridge.TranslationResult> = emptyList(),
    val showSettings: Boolean = false
)

class TranslatorViewModel(application: Application) : AndroidViewModel(application) {
    
    private val settingsRepo = SettingsRepository.getInstance(application)
    
    private val _uiState = MutableStateFlow(TranslatorUiState())
    val uiState: StateFlow<TranslatorUiState> = _uiState.asStateFlow()
    
    init {
        // Load saved translation history
        val savedHistory = settingsRepo.loadTranslationHistory()
        if (savedHistory.isNotEmpty()) {
            _uiState.update { it.copy(translations = savedHistory) }
        }

        // Collect settings
        viewModelScope.launch {
            settingsRepo.settingsFlow.collectLatest { settings ->
                _uiState.update {
                    it.copy(
                        languagePair = settings.languagePair,
                        useCloudTranslation = settings.useCloudTranslation,
                        geminiApiKey = settings.geminiApiKey
                    )
                }
            }
        }
        
        // Collect service state
        viewModelScope.launch {
            ServiceBridge.serviceStateFlow.collectLatest { running ->
                _uiState.update { it.copy(isServiceRunning = running) }
            }
        }
        
        viewModelScope.launch {
            ServiceBridge.bluetoothStateFlow.collectLatest { state ->
                _uiState.update { it.copy(bluetoothState = state) }
            }
        }
        
        viewModelScope.launch {
            ServiceBridge.connectedDeviceNameFlow.collectLatest { name ->
                _uiState.update { it.copy(connectedDeviceName = name) }
            }
        }
        
        viewModelScope.launch {
            ServiceBridge.isListeningFlow.collectLatest { listening ->
                _uiState.update { it.copy(isListening = listening) }
            }
        }
        
        viewModelScope.launch {
            ServiceBridge.statusTextFlow.collectLatest { text ->
                _uiState.update { it.copy(statusText = text) }
            }
        }
        
        viewModelScope.launch {
            ServiceBridge.isSttActiveFlow.collectLatest { active ->
                _uiState.update { it.copy(isSttActive = active) }
            }
        }
        
        viewModelScope.launch {
            ServiceBridge.translationFlow.collect { result ->
                _uiState.update { current ->
                    // Replace last entry if it's an update (same original text), else add
                    val translations = current.translations.toMutableList()
                    val existingIndex = translations.indexOfLast { it.originalText == result.originalText }
                    if (existingIndex >= 0) {
                        translations[existingIndex] = result
                    } else {
                        translations.add(result)
                    }
                    // Keep last 50
                    if (translations.size > 50) translations.removeAt(0)
                    current.copy(translations = translations).also {
                        settingsRepo.saveTranslationHistory(it.translations)
                    }
                }
            }
        }
    }
    
    fun startService() {
        val context = getApplication<Application>()
        val intent = Intent(context, TranslatorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
    
    fun stopService() {
        val context = getApplication<Application>()
        context.stopService(Intent(context, TranslatorService::class.java))
    }
    
    fun setLanguagePair(pair: LanguagePair) {
        settingsRepo.updateLanguagePair(pair)
    }
    
    fun setUseCloudTranslation(use: Boolean) {
        settingsRepo.updateUseCloud(use)
    }
    
    fun setGeminiApiKey(key: String) {
        settingsRepo.updateGeminiApiKey(key)
    }
    
    fun toggleSettings() {
        _uiState.update { it.copy(showSettings = !it.showSettings) }
    }
    
    fun clearTranslations() {
        _uiState.update { it.copy(translations = emptyList()) }
        settingsRepo.clearTranslationHistory()
    }
    
    fun toggleStt() {
        val context = getApplication<Application>()
        val intent = Intent(context, TranslatorService::class.java)
        if (_uiState.value.isSttActive) {
            intent.action = TranslatorService.ACTION_STOP_LISTENING
        } else {
            intent.action = TranslatorService.ACTION_START_LISTENING
        }
        context.startService(intent)
    }
}
