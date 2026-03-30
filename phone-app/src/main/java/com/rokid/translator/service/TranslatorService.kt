package com.rokid.translator.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rokid.translator.BuildConfig
import com.rokid.translator.MainActivity
import com.rokid.translator.R
import com.rokid.translator.common.Constants
import com.rokid.translator.common.protocol.Message
import com.rokid.translator.common.protocol.MessageType
import com.rokid.translator.data.LanguagePair
import com.rokid.translator.data.SettingsRepository
import com.rokid.translator.service.cxr.CxrMobileManager
import com.rokid.translator.service.translation.GeminiTranslator
import com.rokid.translator.service.translation.LanguageDetector
import com.rokid.translator.service.translation.MLKitTranslator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Background service that handles:
 * 1. Bluetooth connection with glasses
 * 2. Speech recognition via Android SpeechRecognizer (phone mic)
 * 3. Translation (ML Kit local + Gemini cloud)
 * 4. Sending results back to glasses
 */
class TranslatorService : Service() {
    
    companion object {
        private const val TAG = "TranslatorService"
        const val ACTION_START_LISTENING = "com.rokid.translator.START_LISTENING"
        const val ACTION_STOP_LISTENING = "com.rokid.translator.STOP_LISTENING"
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var bluetoothManager: BluetoothSppManager? = null
    private var cxrManager: CxrMobileManager? = null
    private var mlKitTranslator: MLKitTranslator? = null
    private var geminiTranslator: GeminiTranslator? = null
    private var settingsRepo: SettingsRepository? = null
    private var speechManager: SpeechRecognizerManager? = null
    
    override fun onCreate() {
        super.onCreate()
        initializeServices()
        startForeground(Constants.NOTIFICATION_ID, createNotification())
        ServiceBridge.updateServiceState(true)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_LISTENING -> startSpeechRecognition()
            ACTION_STOP_LISTENING -> stopSpeechRecognition()
        }
        return START_STICKY
    }
    
    private fun initializeServices() {
        settingsRepo = SettingsRepository.getInstance(this)
        
        val apiKey = settingsRepo?.getSettings()?.geminiApiKey?.takeIf { it.isNotBlank() }
            ?: BuildConfig.GEMINI_API_KEY
        
        // Initialize translators
        mlKitTranslator = MLKitTranslator()
        geminiTranslator = GeminiTranslator(apiKey)
        
        // Pre-download ML Kit models
        serviceScope.launch {
            try {
                mlKitTranslator?.ensureModelDownloaded("es", "en")
                mlKitTranslator?.ensureModelDownloaded("en", "es")
                mlKitTranslator?.ensureModelDownloaded("it", "en")
                mlKitTranslator?.ensureModelDownloaded("en", "it")
                Log.d(TAG, "ML Kit models ready")
            } catch (e: Exception) {
                Log.e(TAG, "ML Kit model download failed", e)
            }
        }
        
        // Initialize SpeechRecognizer (phone mic)
        initSpeechRecognizer()
        
        // Initialize Bluetooth
        bluetoothManager = BluetoothSppManager(this, serviceScope)
        bluetoothManager?.startListening()
        
        // Monitor BT connection state
        serviceScope.launch {
            bluetoothManager?.connectionState?.collectLatest { state ->
                ServiceBridge.updateBluetoothState(state)
                Log.d(TAG, "Bluetooth state: $state")
            }
        }
        
        serviceScope.launch {
            bluetoothManager?.connectedDeviceName?.collectLatest { name ->
                ServiceBridge.updateConnectedDeviceName(name)
            }
        }
        
        // Monitor messages from glasses (keep glasses audio path intact)
        serviceScope.launch {
            bluetoothManager?.messageFlow?.collect { message ->
                handleGlassesMessage(message)
            }
        }
        
        // Monitor settings changes
        serviceScope.launch {
            settingsRepo?.settingsFlow?.collectLatest { settings ->
                val key = settings.geminiApiKey.takeIf { it.isNotBlank() } ?: BuildConfig.GEMINI_API_KEY
                geminiTranslator?.updateApiKey(key)
                // Update speech recognizer language hint
                val langHint = when (settings.languagePair) {
                    LanguagePair.ES_EN -> "es"
                    LanguagePair.IT_EN -> "it"
                }
                speechManager?.setLanguageHint(langHint)
            }
        }
        
        // Initialize CXR if available
        if (CxrMobileManager.isSdkAvailable()) {
            cxrManager = CxrMobileManager(this)
            serviceScope.launch {
                bluetoothManager?.connectionState?.collectLatest { state ->
                    if (state == BluetoothConnectionState.CONNECTED) {
                        val device = bluetoothManager?.connectedDevice
                        if (device != null) {
                            cxrManager?.initBluetooth(device)
                        }
                    }
                }
            }
        }
        
        // Auto-start listening
        startSpeechRecognition()
    }
    
    private fun initSpeechRecognizer() {
        speechManager = SpeechRecognizerManager(
            context = this,
            onPartialResult = { partial ->
                ServiceBridge.updateStatus("Hearing: $partial")
            },
            onFinalResult = { transcript ->
                Log.d(TAG, "STT result: $transcript")
                serviceScope.launch {
                    processTranscript(transcript)
                }
            },
            onError = { error ->
                Log.w(TAG, "STT error: $error")
                ServiceBridge.updateStatus("STT: $error")
            },
            onListeningStateChanged = { listening ->
                ServiceBridge.updateListeningState(listening)
                if (listening) {
                    ServiceBridge.updateStatus("Listening...")
                }
            }
        )
        
        // Set initial language hint
        val langHint = when (settingsRepo?.getSettings()?.languagePair) {
            LanguagePair.ES_EN -> "es"
            LanguagePair.IT_EN -> "it"
            else -> "es"
        }
        speechManager?.setLanguageHint(langHint)
    }
    
    private fun startSpeechRecognition() {
        speechManager?.startContinuousListening()
        ServiceBridge.updateStatus("Listening...")
        ServiceBridge.updateSttActive(true)
    }
    
    private fun stopSpeechRecognition() {
        speechManager?.stopListening()
        ServiceBridge.updateStatus("Stopped")
        ServiceBridge.updateSttActive(false)
    }
    
    /**
     * Process a transcript from SpeechRecognizer through the translation pipeline.
     */
    private suspend fun processTranscript(transcript: String) {
        if (transcript.isBlank()) return
        
        val settings = settingsRepo?.getSettings() ?: return
        val languagePair = settings.languagePair
        
        // Send transcript to glasses
        sendToGlasses(Message.userTranscript(transcript))
        
        try {
            // Detect language
            val detectedLang = LanguageDetector.detect(transcript)
            val (sourceLang, targetLang) = languagePair.translateDirection(detectedLang)
            
            Log.d(TAG, "Detected: $detectedLang, translating $sourceLang → $targetLang")
            ServiceBridge.updateStatus("Translating ($sourceLang → $targetLang)...")
            
            // ML Kit local translation (fast)
            var localResult: String? = null
            try {
                localResult = mlKitTranslator?.translate(transcript, sourceLang, targetLang)
                if (localResult != null) {
                    val displayText = "[$sourceLang→$targetLang] $localResult"
                    sendToGlasses(Message.aiResponse(displayText))
                    
                    ServiceBridge.emitTranslation(ServiceBridge.TranslationResult(
                        originalText = transcript,
                        detectedLanguage = detectedLang,
                        localTranslation = localResult,
                        cloudTranslation = null
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "ML Kit translation failed", e)
            }
            
            // Gemini cloud translation (better quality)
            if (settings.useCloudTranslation) {
                try {
                    val cloudResult = geminiTranslator?.translate(transcript, sourceLang, targetLang)
                    if (cloudResult != null && cloudResult != localResult) {
                        val displayText = "[$sourceLang→$targetLang] $cloudResult"
                        sendToGlasses(Message.aiResponse(displayText))
                        
                        ServiceBridge.emitTranslation(ServiceBridge.TranslationResult(
                            originalText = transcript,
                            detectedLanguage = detectedLang,
                            localTranslation = localResult,
                            cloudTranslation = cloudResult
                        ))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Gemini translation failed", e)
                }
            }
            
            ServiceBridge.updateStatus("Listening...")
            
        } catch (e: Exception) {
            Log.e(TAG, "Translation pipeline error", e)
            sendToGlasses(Message.aiError("Error: ${e.message}"))
            ServiceBridge.updateStatus("Error: ${e.message}")
        }
    }
    
    /**
     * Handle messages from glasses (keep existing audio path for future use).
     */
    private suspend fun handleGlassesMessage(message: Message) {
        when (message.type) {
            MessageType.VOICE_END -> {
                // Glasses sent audio - for v1, we use phone mic via SpeechRecognizer instead
                // Keep this path intact for future glasses-mic integration
                Log.d(TAG, "Received glasses audio (${message.binaryData?.size ?: 0} bytes) - using phone mic STT instead")
            }
            MessageType.VOICE_START -> {
                Log.d(TAG, "Glasses started recording")
            }
            else -> {
                Log.d(TAG, "Unhandled message: ${message.type}")
            }
        }
    }
    
    private suspend fun sendToGlasses(message: Message) {
        bluetoothManager?.sendMessage(message)
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        speechManager?.destroy()
        serviceScope.cancel()
        bluetoothManager?.disconnect(restartListening = false)
        bluetoothManager?.stopListening()
        cxrManager?.release()
        mlKitTranslator?.close()
        ServiceBridge.updateServiceState(false)
        ServiceBridge.updateSttActive(false)
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}
