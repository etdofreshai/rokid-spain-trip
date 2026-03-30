package com.rokid.translator.data

/**
 * Supported language pairs for translation.
 */
enum class LanguagePair(val displayName: String, val sourceTag: String, val targetTag: String) {
    ES_EN("ES ↔ EN", "es", "en"),
    IT_EN("IT ↔ EN", "it", "en");
    
    /** Get the "other" language given a detected language */
    fun translateDirection(detectedLang: String): Pair<String, String> {
        return when {
            detectedLang.startsWith("en") -> Pair("en", if (this == ES_EN) "es" else "it")
            detectedLang.startsWith("es") -> Pair("es", "en")
            detectedLang.startsWith("it") -> Pair("it", "en")
            else -> Pair(detectedLang, "en") // fallback
        }
    }
}
