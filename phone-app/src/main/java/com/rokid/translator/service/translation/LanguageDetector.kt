package com.rokid.translator.service.translation

/**
 * Simple heuristic language detector for Spanish/Italian/English.
 * For production, use ML Kit Language ID, but this avoids an extra dependency.
 */
object LanguageDetector {
    
    // Common Spanish words/patterns
    private val spanishIndicators = setOf(
        "el", "la", "los", "las", "de", "del", "en", "que", "es", "un", "una",
        "por", "con", "no", "se", "su", "para", "como", "pero", "más", "este",
        "ya", "muy", "también", "fue", "ser", "hay", "todo", "está", "son",
        "hola", "gracias", "bueno", "bien", "dónde", "cuánto", "cómo",
        "ñ", "¿", "¡"
    )
    
    // Common Italian words/patterns
    private val italianIndicators = setOf(
        "il", "lo", "la", "le", "gli", "di", "del", "che", "è", "un", "una",
        "per", "con", "non", "si", "suo", "come", "ma", "più", "questo",
        "già", "molto", "anche", "stato", "essere", "sono", "tutto",
        "ciao", "grazie", "buono", "bene", "dove", "quanto",
        "gli", "nella", "della", "delle", "degli"
    )
    
    // Common English words
    private val englishIndicators = setOf(
        "the", "is", "are", "was", "were", "have", "has", "had", "do", "does",
        "will", "would", "could", "should", "can", "may", "might",
        "this", "that", "these", "those", "what", "where", "when", "how",
        "hello", "thanks", "good", "well", "please", "yes", "no"
    )
    
    /**
     * Detect language from text. Returns "es", "it", or "en".
     */
    fun detect(text: String): String {
        val words = text.lowercase().split(Regex("\\s+"))
        
        // Check for Spanish-specific characters
        if (text.contains("ñ") || text.contains("¿") || text.contains("¡")) return "es"
        
        var esScore = 0
        var itScore = 0
        var enScore = 0
        
        for (word in words) {
            val clean = word.replace(Regex("[^a-záéíóúüñàèìòù]"), "")
            if (clean in spanishIndicators) esScore++
            if (clean in italianIndicators) itScore++
            if (clean in englishIndicators) enScore++
        }
        
        // Italian-specific: words ending in vowels are more common
        val vowelEndCount = words.count { it.matches(Regex(".*[aeiou]$")) }
        if (vowelEndCount > words.size * 0.7) itScore += 2
        
        return when {
            esScore > itScore && esScore > enScore -> "es"
            itScore > esScore && itScore > enScore -> "it"
            enScore > 0 -> "en"
            else -> "en" // default
        }
    }
}
