package com.rokid.translator.service

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Manages Android's built-in SpeechRecognizer for continuous listening.
 * Uses the phone mic to pick up nearby speech and transcribe it.
 */
class SpeechRecognizerManager(
    private val context: Context,
    private val onPartialResult: (String) -> Unit,
    private val onFinalResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onListeningStateChanged: (Boolean) -> Unit
) {
    companion object {
        private const val TAG = "SpeechRecognizerMgr"
        private const val RESTART_DELAY_MS = 300L
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListening = false
    private var shouldContinue = false
    private var currentLanguageHint: String = "es" // default to Spanish

    fun setLanguageHint(langCode: String) {
        currentLanguageHint = langCode
    }

    fun startContinuousListening() {
        shouldContinue = true
        mainHandler.post { startRecognizer() }
    }

    fun stopListening() {
        shouldContinue = false
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping", e)
            }
            isListening = false
            onListeningStateChanged(false)
        }
    }

    fun destroy() {
        shouldContinue = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Error destroying", e)
            }
            speechRecognizer = null
            isListening = false
        }
    }

    private fun startRecognizer() {
        if (!shouldContinue) return

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition not available on this device")
            return
        }

        // Destroy previous instance
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(createListener())
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // Set language based on hint - support multilingual recognition
            val locale = when {
                currentLanguageHint.startsWith("es") -> "es-ES"
                currentLanguageHint.startsWith("it") -> "it-IT"
                currentLanguageHint.startsWith("en") -> "en-US"
                else -> "es-ES"
            }
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
            // Also accept other languages
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, locale)
            putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("es-ES", "it-IT", "en-US"))
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Longer silence timeouts for natural speech
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
            onListeningStateChanged(true)
            Log.d(TAG, "Started listening (lang=$locale)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start listening", e)
            onError("Failed to start: ${e.message}")
            scheduleRestart()
        }
    }

    private fun scheduleRestart() {
        if (shouldContinue) {
            mainHandler.postDelayed({ startRecognizer() }, RESTART_DELAY_MS)
        }
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech started")
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "Speech ended")
            isListening = false
            onListeningStateChanged(false)
        }

        override fun onError(error: Int) {
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "no_match"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "timeout"
                SpeechRecognizer.ERROR_AUDIO -> "audio_error"
                SpeechRecognizer.ERROR_CLIENT -> "client_error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no_permission"
                SpeechRecognizer.ERROR_NETWORK -> "network_error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network_timeout"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
                SpeechRecognizer.ERROR_SERVER -> "server_error"
                else -> "error_$error"
            }
            Log.d(TAG, "Recognition error: $msg")

            isListening = false
            onListeningStateChanged(false)

            // Don't report no_match/timeout as errors - just restart
            if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT &&
                error != SpeechRecognizer.ERROR_CLIENT) {
                this@SpeechRecognizerManager.onError("STT: $msg")
            }

            // Restart for continuous listening
            scheduleRestart()
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim() ?: ""
            if (text.isNotBlank()) {
                Log.d(TAG, "Final result: $text")
                onFinalResult(text)
            }

            isListening = false
            onListeningStateChanged(false)
            // Restart for continuous listening
            scheduleRestart()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim() ?: ""
            if (text.isNotBlank()) {
                onPartialResult(text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
