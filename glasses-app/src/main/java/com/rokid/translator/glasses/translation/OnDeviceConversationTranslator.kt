package com.rokid.translator.glasses.translation

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OnDeviceConversationTranslator {

    companion object {
        private const val TAG = "OnDeviceTranslator"
    }

    private val translators = mutableMapOf<String, Translator>()
    private val downloadedModels = mutableSetOf<String>()

    suspend fun preloadCounterpart(counterpartLanguage: String) {
        ensureModelDownloaded("en", counterpartLanguage)
        ensureModelDownloaded(counterpartLanguage, "en")
    }

    suspend fun translate(text: String, sourceLanguage: String, targetLanguage: String): String {
        val translator = getOrCreateTranslator(sourceLanguage, targetLanguage)
        ensureModelDownloaded(sourceLanguage, targetLanguage)

        return suspendCancellableCoroutine { continuation ->
            translator.translate(text)
                .addOnSuccessListener { result ->
                    Log.d(TAG, "Translated $sourceLanguage->$targetLanguage: $result")
                    continuation.resume(result)
                }
                .addOnFailureListener { error ->
                    Log.e(TAG, "Translation failed for $sourceLanguage->$targetLanguage", error)
                    continuation.resumeWithException(error)
                }
        }
    }

    fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
    }

    private suspend fun ensureModelDownloaded(sourceLanguage: String, targetLanguage: String) {
        val key = "$sourceLanguage->$targetLanguage"
        if (key in downloadedModels) return

        val translator = getOrCreateTranslator(sourceLanguage, targetLanguage)
        val conditions = DownloadConditions.Builder().build()

        suspendCancellableCoroutine { continuation ->
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener {
                    downloadedModels.add(key)
                    Log.d(TAG, "Model ready: $key")
                    continuation.resume(Unit)
                }
                .addOnFailureListener { error ->
                    Log.e(TAG, "Model download failed: $key", error)
                    continuation.resumeWithException(error)
                }
        }
    }

    private fun getOrCreateTranslator(sourceLanguage: String, targetLanguage: String): Translator {
        val key = "$sourceLanguage->$targetLanguage"
        return translators.getOrPut(key) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(mapLanguageCode(sourceLanguage))
                .setTargetLanguage(mapLanguageCode(targetLanguage))
                .build()
            Translation.getClient(options)
        }
    }

    private fun mapLanguageCode(languageCode: String): String =
        when (languageCode) {
            "es" -> TranslateLanguage.SPANISH
            "it" -> TranslateLanguage.ITALIAN
            else -> TranslateLanguage.ENGLISH
        }
}
