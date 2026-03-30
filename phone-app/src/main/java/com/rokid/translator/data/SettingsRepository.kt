package com.rokid.translator.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TranslatorSettings(
    val geminiApiKey: String = "",
    val languagePair: LanguagePair = LanguagePair.ES_EN,
    val useCloudTranslation: Boolean = true
)

class SettingsRepository(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "translator_settings"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_LANGUAGE_PAIR = "language_pair"
        private const val KEY_USE_CLOUD = "use_cloud_translation"
        
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
}
