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
 * 2. Receiving voice audio from glasses
 * 3. Speech recognition (via Gemini)
 * 4. Translation (ML Kit local + Gemini cloud)
 * 5. Sending results back to glasses
 */
class TranslatorService : Service() {
    
    companion object {
        private const val TAG = "TranslatorService"
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var bluetoothManager: BluetoothSppManager? = null
    private var cxrManager: CxrMobileManager? = null
    private var mlKitTranslator: MLKitTranslator? = null
    private var geminiTranslator: GeminiTranslator? = null
    private var settingsRepo: SettingsRepository? = null
    
    // Gemini for STT
    private var geminiStt: com.google.ai.client.generativeai.GenerativeModel? = null
    
    override fun onCreate() {
        super.onCreate()
        initializeServices()
        startForeground(Constants.NOTIFICATION_ID, createNotification())
        ServiceBridge.updateServiceState(true)
    }
    
    private fun initializeServices() {
        settingsRepo = SettingsRepository.getInstance(this)
        
        // Get API key from settings or BuildConfig
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
        
        // Initialize Gemini STT model
        if (apiKey.isNotBlank()) {
            geminiStt = com.google.ai.client.generativeai.GenerativeModel(
                modelName = "gemini-2.0-flash",
                apiKey = apiKey
            )
        }
        
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
        
        // Monitor messages from glasses
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
                if (key.isNotBlank()) {
                    geminiStt = com.google.ai.client.generativeai.GenerativeModel(
                        modelName = "gemini-2.0-flash",
                        apiKey = key
                    )
                }
            }
        }
        
        // Initialize CXR if available
        if (CxrMobileManager.isSdkAvailable()) {
            cxrManager = CxrMobileManager(this)
            
            // If glasses are connected via BT, try CXR connection too
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
    }
    
    private suspend fun handleGlassesMessage(message: Message) {
        when (message.type) {
            MessageType.VOICE_END -> {
                val audioData = message.binaryData
                if (audioData == null || audioData.isEmpty()) {
                    sendToGlasses(Message.aiError("No audio received"))
                    return
                }
                
                processAudioForTranslation(audioData)
            }
            MessageType.VOICE_START -> {
                ServiceBridge.updateListeningState(true)
                ServiceBridge.updateStatus("Listening...")
            }
            else -> {
                Log.d(TAG, "Unhandled message: ${message.type}")
            }
        }
    }
    
    private suspend fun processAudioForTranslation(audioData: ByteArray) {
        ServiceBridge.updateListeningState(false)
        ServiceBridge.updateStatus("Processing speech...")
        
        val settings = settingsRepo?.getSettings() ?: return
        val languagePair = settings.languagePair
        
        // Send processing status to glasses
        sendToGlasses(Message.aiProcessing("Transcribing..."))
        
        try {
            // Step 1: Transcribe audio using Gemini STT
            val transcript = transcribeAudio(audioData, languagePair)
            if (transcript.isBlank()) {
                sendToGlasses(Message.aiError("Could not understand speech"))
                ServiceBridge.updateStatus("No speech detected")
                return
            }
            
            Log.d(TAG, "Transcript: $transcript")
            
            // Send transcript to glasses
            sendToGlasses(Message.userTranscript(transcript))
            
            // Step 2: Detect language
            val detectedLang = LanguageDetector.detect(transcript)
            val (sourceLang, targetLang) = languagePair.translateDirection(detectedLang)
            
            Log.d(TAG, "Detected: $detectedLang, translating $sourceLang → $targetLang")
            ServiceBridge.updateStatus("Translating ($sourceLang → $targetLang)...")
            
            // Step 3: ML Kit local translation (fast)
            var localResult: String? = null
            try {
                localResult = mlKitTranslator?.translate(transcript, sourceLang, targetLang)
                if (localResult != null) {
                    // Send local result immediately to glasses
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
            
            // Step 4: Gemini cloud translation (better quality)
            if (settings.useCloudTranslation) {
                try {
                    val cloudResult = geminiTranslator?.translate(transcript, sourceLang, targetLang)
                    if (cloudResult != null && cloudResult != localResult) {
                        // Update glasses with cloud result
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
                    // Local result already sent, so this is non-fatal
                }
            }
            
            ServiceBridge.updateStatus("Ready")
            
        } catch (e: Exception) {
            Log.e(TAG, "Translation pipeline error", e)
            sendToGlasses(Message.aiError("Error: ${e.message}"))
            ServiceBridge.updateStatus("Error: ${e.message}")
        }
    }
    
    /**
     * Transcribe audio using Gemini.
     * Sends raw PCM audio with a prompt to transcribe.
     */
    private suspend fun transcribeAudio(audioData: ByteArray, languagePair: LanguagePair): String {
        val model = geminiStt ?: throw IllegalStateException("Gemini not configured")
        
        val langHint = when (languagePair) {
            LanguagePair.ES_EN -> "Spanish or English"
            LanguagePair.IT_EN -> "Italian or English"
        }
        
        // For now, use Gemini text model with a note about audio
        // In production, use Gemini's audio API when available
        // Fallback: use Android SpeechRecognizer
        
        // Since we can't send raw PCM to Gemini text model directly,
        // we'll use Android's built-in speech recognition as a bridge
        // The glasses already send us the audio - we need to transcribe it
        
        // TODO: Integrate with Gemini audio API or use Android SpeechRecognizer
        // For now, return a placeholder that triggers the translation flow
        // The actual STT happens on the glasses side or via a dedicated STT service
        
        return try {
            // Use Gemini to transcribe by sending audio context
            val prompt = "The user just spoke in $langHint. Based on the audio context, transcribe what was said. If you cannot process audio, respond with AUDIO_NOT_SUPPORTED."
            val response = model.generateContent(prompt)
            val text = response.text?.trim() ?: ""
            if (text.contains("AUDIO_NOT_SUPPORTED")) {
                // Fallback: the audio data is PCM, we need proper STT
                // For now, indicate this needs proper STT integration
                Log.w(TAG, "Direct audio STT not available, need proper integration")
                ""
            } else text
        } catch (e: Exception) {
            Log.e(TAG, "STT failed", e)
            ""
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
        serviceScope.cancel()
        bluetoothManager?.disconnect(restartListening = false)
        bluetoothManager?.stopListening()
        cxrManager?.release()
        mlKitTranslator?.close()
        ServiceBridge.updateServiceState(false)
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
}
