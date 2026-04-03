package com.rokid.translator.glasses.translation

object ConversationLanguageDetector {

    private val englishIndicators = setOf(
        "the", "is", "are", "was", "were", "have", "has", "had", "do", "does",
        "will", "would", "could", "should", "can", "may", "might", "this", "that",
        "these", "those", "what", "where", "when", "how", "hello", "thanks",
        "good", "well", "please", "yes", "no", "you", "your", "me", "my"
    )

    private val italianIndicators = setOf(
        "ciao", "grazie", "buongiorno", "buonasera", "arrivederci", "per", "con",
        "non", "sono", "come", "ma", "piu", "questo", "molto", "anche", "dove",
        "quanto", "bene", "buono", "buon", "lavoro", "stato", "tutto", "della",
        "delle", "degli", "allora", "prego", "andiamo", "ciaone"
    )

    private val spanishIndicators = setOf(
        "hola", "gracias", "buenos", "buenas", "adios", "por", "con", "como",
        "pero", "mas", "muy", "tambien", "donde", "cuanto", "bueno", "bien",
        "todo", "esta", "son", "para", "que", "una", "uno"
    )

    fun detect(text: String, preferredCounterpart: String): String {
        val normalizedWords = text
            .lowercase()
            .split(Regex("\\s+"))
            .map { it.replace(Regex("[^\\p{L}\\p{N}]"), "") }
            .filter { it.isNotBlank() }

        var englishScore = 0
        var counterpartScore = 0

        for (word in normalizedWords) {
            if (word in englishIndicators) englishScore++
            if (word in counterpartIndicators(preferredCounterpart)) counterpartScore++
        }

        if (counterpartScore > englishScore) return preferredCounterpart
        if (englishScore > 0) return "en"

        val vowelEndingWords = normalizedWords.count { it.lastOrNull() in listOf('a', 'e', 'i', 'o') }
        return if (preferredCounterpart != "en" && vowelEndingWords >= 2 && vowelEndingWords >= normalizedWords.size / 2) {
            preferredCounterpart
        } else {
            "en"
        }
    }

    private fun counterpartIndicators(languageCode: String): Set<String> =
        when (languageCode) {
            "es" -> spanishIndicators
            else -> italianIndicators
        }
}
