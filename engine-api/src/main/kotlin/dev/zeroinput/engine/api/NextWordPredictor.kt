package dev.zeroinput.engine.api

data class NextWordSuggestion(val text: String, val commitText: String = text)

/** Bounded, memory-only lookup. Never retain [context] or read private/persistent data here. */
fun interface NextWordPredictor {
    fun suggest(language: InputLanguage, context: CharSequence, limit: Int): List<NextWordSuggestion>

    companion object {
        val Empty = NextWordPredictor { _, _, _ -> emptyList() }
    }
}
