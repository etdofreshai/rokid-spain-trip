package com.rokid.translator.glasses.translation

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class CloudTranslationResult(
    val translation: String,
    val pronunciation: String = "",
)

class CloudConversationTranslator(
    private var apiKey: String,
    private var model: String,
    private val siteUrl: String = "",
    private val appName: String = "Rokid Translator",
) {

    companion object {
        private const val TAG = "CloudTranslator"
        private const val API_URL = "https://openrouter.ai/api/v1/chat/completions"
    }

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    fun updateApiKey(key: String) {
        apiKey = key
    }

    fun updateModel(modelId: String) {
        model = modelId
    }

    suspend fun translateWithPronunciation(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
    ): CloudTranslationResult {
        if (apiKey.isBlank()) throw IllegalStateException("OpenRouter API key not configured")

        val sourceName = languageName(sourceLanguage)
        val targetName = languageName(targetLanguage)
        val shouldIncludePronunciation = targetLanguage != "en"

        val requestBody = JSONObject().apply {
            put("model", model)
            put("temperature", 0.2)
            put("response_format", JSONObject().put("type", "json_object"))
            put(
                "messages",
                org.json.JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put(
                                "content",
                                buildString {
                                    appendLine("You are a live translation engine.")
                                    appendLine("Return strict JSON with keys translation and pronunciation.")
                                    appendLine("translation must contain only the translated phrase.")
                                    if (shouldIncludePronunciation) {
                                        appendLine("pronunciation must be a plain-English phonetic guide for saying the translated phrase out loud.")
                                        appendLine("Use only English letters and simple syllables, like: BWO-nah SEH-rah.")
                                    } else {
                                        appendLine("pronunciation must be an empty string.")
                                    }
                                    appendLine("Do not include markdown fences or extra keys.")
                                }
                            )
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", "Translate this $sourceName text into $targetName:\n$text")
                    )
            )
        }

        val raw = withContext(Dispatchers.IO) {
            executeRequest(requestBody)
        }
        val parsed = extractJsonObject(raw)
        val result = CloudTranslationResult(
            translation = parsed.optString("translation").trim(),
            pronunciation = parsed.optString("pronunciation").trim()
        )
        if (result.translation.isBlank()) {
            throw IllegalStateException("OpenRouter returned empty translation")
        }
        Log.d(TAG, "Cloud translation $sourceLanguage->$targetLanguage via $model: ${result.translation}")
        return result
    }

    private fun executeRequest(body: JSONObject): String {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            if (siteUrl.isNotBlank()) {
                connection.setRequestProperty("HTTP-Referer", siteUrl)
            }
            if (appName.isNotBlank()) {
                connection.setRequestProperty("X-Title", appName)
            }

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
            val payload = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                buildString {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        append(line)
                    }
                }
            }

            if (responseCode !in 200..299) {
                throw IllegalStateException("OpenRouter HTTP $responseCode: $payload")
            }

            val responseJson = JSONObject(payload)
            responseJson.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content")
                .trim()
        } finally {
            connection.disconnect()
        }
    }

    private fun extractJsonObject(raw: String): JSONObject {
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start >= 0 && end > start) JSONObject(raw.substring(start, end + 1))
            else throw IllegalStateException("OpenRouter returned non-JSON content: $raw")
        }
    }

    private fun languageName(code: String): String =
        when (code) {
            "it" -> "Italian"
            "es" -> "Spanish"
            else -> "English"
        }
}
