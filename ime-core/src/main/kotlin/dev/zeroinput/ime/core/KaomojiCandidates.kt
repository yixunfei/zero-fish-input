package dev.zeroinput.ime.core

/** The app supplies already prepared public and permitted personal expressions. */
data class KaomojiSuggestion(val id: String, val text: String, val personal: Boolean = false)

fun interface KaomojiCandidateSource {
    fun suggestions(input: String, personalAllowed: Boolean): List<KaomojiSuggestion>

    companion object {
        val Empty = KaomojiCandidateSource { _, _ -> emptyList() }
    }
}
