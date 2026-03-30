package com.rokid.translator.service.translation

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device ML Kit translator for fast local translations.
 * Supports ES↔EN and IT↔EN.
 */
class MLKitTranslator {
    companion object {
        private const val TAG = "MLKitTranslator"
    }
    
    // Cache translators to avoid re-creating them
    private val translators = mutableMapOf<String, Translator>()
    private val downloadedModels = mutableSetOf<String>()
    
    private fun getLanguageCode(lang: String): String {
        return when {
            lang.startsWith("es") -> TranslateLanguage.SPANISH
            lang.startsWith("it") -> TranslateLanguage.ITALIAN
            lang.startsWith("en") -> TranslateLanguage.ENGLISH
            else -> TranslateLanguage.ENGLISH
        }
    }
    
    private fun getOrCreateTranslator(sourceLang: String, targetLang: String): Translator {
        val key = "$sourceLang->$targetLang"
        return translators.getOrPut(key) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(getLanguageCode(sourceLang))
                .setTargetLanguage(getLanguageCode(targetLang))
                .build()
            Translation.getClient(options)
        }
    }
    
    /**
     * Ensure model is downloaded. Call this early (e.g., app startup).
     */
    suspend fun ensureModelDownloaded(sourceLang: String, targetLang: String) {
        val key = "$sourceLang->$targetLang"
        if (key in downloadedModels) return
        
        val translator = getOrCreateTranslator(sourceLang, targetLang)
        val conditions = DownloadConditions.Builder().build()
        
        suspendCancellableCoroutine { cont ->
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    Log.d(TAG, "Model downloaded: $key")
                    downloadedModels.add(key)
                    cont.resume(Unit)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Model download failed: $key", e)
                    cont.resumeWithException(e)
                }
        }
    }
    
    /**
     * Translate text using on-device ML Kit.
     */
    suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
        val translator = getOrCreateTranslator(sourceLang, targetLang)
        
        // Ensure model is downloaded first
        ensureModelDownloaded(sourceLang, targetLang)
        
        return suspendCancellableCoroutine { cont ->
            translator.translate(text)
                .addOnSuccessListener { result ->
                    Log.d(TAG, "Translated ($sourceLang→$targetLang): $result")
                    cont.resume(result)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Translation failed", e)
                    cont.resumeWithException(e)
                }
        }
    }
    
    fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
    }
}
