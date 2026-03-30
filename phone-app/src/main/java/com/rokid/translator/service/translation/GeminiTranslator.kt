package com.rokid.translator.service.translation

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig

/**
 * Cloud-based Gemini translator for higher quality translations.
 */
class GeminiTranslator(private var apiKey: String) {
    companion object {
        private const val TAG = "GeminiTranslator"
    }
    
    private var model: GenerativeModel? = null
    
    private fun getModel(): GenerativeModel {
        if (model == null || apiKey.isNotEmpty()) {
            model = GenerativeModel(
                modelName = "gemini-2.0-flash",
                apiKey = apiKey,
                generationConfig = generationConfig {
                    temperature = 0.3f
                    maxOutputTokens = 256
                }
            )
        }
        return model!!
    }
    
    fun updateApiKey(key: String) {
        apiKey = key
        model = null
    }
    
    /**
     * Translate using Gemini with language-aware prompting.
     */
    suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
        if (apiKey.isBlank()) throw IllegalStateException("Gemini API key not set")
        
        val sourceName = langName(sourceLang)
        val targetName = langName(targetLang)
        
        val prompt = """Translate the following $sourceName text to $targetName. 
Return ONLY the translation, nothing else. No explanations, no notes.

Text: $text"""
        
        return try {
            val response = getModel().generateContent(prompt)
            val result = response.text?.trim() ?: throw Exception("Empty response")
            Log.d(TAG, "Gemini translation ($sourceLang→$targetLang): $result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Gemini translation failed", e)
            throw e
        }
    }
    
    /**
     * Transcribe and translate audio using Gemini STT.
     * Takes raw PCM audio data and returns transcription + translation.
     */
    suspend fun transcribeAndTranslate(
        audioData: ByteArray,
        languagePairHint: String
    ): Pair<String, String> {
        if (apiKey.isBlank()) throw IllegalStateException("Gemini API key not set")
        
        val prompt = """You are a real-time translator. Listen to this audio and:
1. Transcribe what was said
2. Detect the language (Spanish, Italian, or English)
3. Translate to the other language in the pair ($languagePairHint)

Respond in this exact format:
DETECTED: <language code: es/it/en>
ORIGINAL: <transcription>
TRANSLATED: <translation>"""
        
        return try {
            val response = getModel().generateContent(prompt)
            val text = response.text ?: throw Exception("Empty response")
            parseTranscriptionResponse(text)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini transcribe+translate failed", e)
            throw e
        }
    }
    
    private fun parseTranscriptionResponse(response: String): Pair<String, String> {
        val lines = response.lines()
        var original = ""
        var translated = ""
        
        for (line in lines) {
            when {
                line.startsWith("ORIGINAL:") -> original = line.substringAfter("ORIGINAL:").trim()
                line.startsWith("TRANSLATED:") -> translated = line.substringAfter("TRANSLATED:").trim()
            }
        }
        
        return Pair(original, translated)
    }
    
    private fun langName(code: String): String = when {
        code.startsWith("es") -> "Spanish"
        code.startsWith("it") -> "Italian"
        code.startsWith("en") -> "English"
        else -> code
    }
}
