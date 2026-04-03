package com.rokid.translator.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.rokid.translator.service.ServiceBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class TranslatorSettings(
    val geminiApiKey: String = "",
    val languagePair: LanguagePair = LanguagePair.ES_EN,
    val useCloudTranslation: Boolean = true
)

class SettingsRepository(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "translator_settings"
        private const val HISTORY_PREFS_NAME = "translator_history"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_LANGUAGE_PAIR = "language_pair"
        private const val KEY_USE_CLOUD = "use_cloud_translation"
        private const val KEY_HISTORY = "translation_history"
        
        @Volatile private var instance: SettingsRepository? = null
        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    
    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context, PREFS_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    private val historyPrefs: SharedPreferences =
        context.getSharedPreferences(HISTORY_PREFS_NAME, Context.MODE_PRIVATE)

    private val _settingsFlow = MutableStateFlow(loadSettings())
    val settingsFlow: StateFlow<TranslatorSettings> = _settingsFlow.asStateFlow()
    
    fun getSettings(): TranslatorSettings = _settingsFlow.value
    
    private fun loadSettings(): TranslatorSettings {
        return TranslatorSettings(
            geminiApiKey = prefs.getString(KEY_GEMINI_API_KEY, "") ?: "",
            languagePair = try {
                LanguagePair.valueOf(prefs.getString(KEY_LANGUAGE_PAIR, LanguagePair.ES_EN.name) ?: LanguagePair.ES_EN.name)
            } catch (e: Exception) { LanguagePair.ES_EN },
            useCloudTranslation = prefs.getBoolean(KEY_USE_CLOUD, true)
        )
    }

    fun saveSettings(settings: TranslatorSettings) {
        prefs.edit().apply {
            putString(KEY_GEMINI_API_KEY, settings.geminiApiKey)
            putString(KEY_LANGUAGE_PAIR, settings.languagePair.name)
            putBoolean(KEY_USE_CLOUD, settings.useCloudTranslation)
            apply()
        }
        _settingsFlow.value = settings
    }

    fun updateGeminiApiKey(key: String) = saveSettings(getSettings().copy(geminiApiKey = key))
    fun updateLanguagePair(pair: LanguagePair) = saveSettings(getSettings().copy(languagePair = pair))
    fun updateUseCloud(use: Boolean) = saveSettings(getSettings().copy(useCloudTranslation = use))

    fun saveTranslationHistory(translations: List<ServiceBridge.TranslationResult>) {
        val array = JSONArray()
        for (t in translations) {
            array.put(JSONObject().apply {
                put("originalText", t.originalText)
                put("detectedLanguage", t.detectedLanguage)
                put("localTranslation", t.localTranslation ?: JSONObject.NULL)
                put("cloudTranslation", t.cloudTranslation ?: JSONObject.NULL)
                put("timestamp", t.timestamp)
            })
        }
        historyPrefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    fun loadTranslationHistory(): List<ServiceBridge.TranslationResult> {
        val json = historyPrefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                ServiceBridge.TranslationResult(
                    originalText = obj.getString("originalText"),
                    detectedLanguage = obj.getString("detectedLanguage"),
                    localTranslation = obj.optString("localTranslation", null),
                    cloudTranslation = obj.optString("cloudTranslation", null),
                    timestamp = obj.getLong("timestamp")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearTranslationHistory() {
        historyPrefs.edit().remove(KEY_HISTORY).apply()
    }
}
