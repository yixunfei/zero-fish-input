package dev.zeroinput.ime.core

import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.CandidateKind
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.NextWordPredictor
import dev.zeroinput.engine.api.NextWordSuggestion
import java.nio.CharBuffer

/** One editor's successful commits. Only the bounded context buffer contains user text. */
internal class WordAssociationSession(
    private val predictor: NextWordPredictor,
    /**
     * Read-only learned-frequency lookup evaluated once per commit with the
     * already normalized, at most eight suggestion texts.  The host wires the
     * session privacy gate; an empty or failing answer preserves editorial
     * order.  It never feeds text back into the strip.
     */
    private val frequencyProvider: (InputLanguage, List<String>) -> Map<String, Int> = { _, _ -> emptyMap() },
) {
    private val context = CharArray(32)
    private var length = 0
    private var generation = 0L
    private var suggestions = emptyList<NextWordSuggestion>()
    var candidates: List<Candidate> = emptyList()
        private set

    fun clear() { context.fill('\u0000'); length = 0; hide() }

    fun hide() { generation++; suggestions = emptyList(); candidates = emptyList() }

    fun committed(text: String, language: InputLanguage, normalize: (String) -> String = { it }) {
        hide()
        for (index in (text.length - context.size).coerceAtLeast(0) until text.length) {
            val character = text[index]
            if (!character.isLetter() && character != ' ') { clear(); continue }
            if (length == context.size) { context.copyInto(context, 0, 1); length-- }
            context[length++] = character
        }
        if (length == 0 || (0 until length).none { context[it].isLetter() }) return
        val view = CharBuffer.wrap(context, 0, length).asReadOnlyBuffer()
        suggestions = try {
            predictor.suggest(language, view, 8).take(8).filter {
                it.text.length in 1..24 && it.text.all(Char::isLetter) &&
                    (it.commitText == it.text || it.commitText == " " + it.text)
            }.map { value ->
                val word = normalize(value.text)
                NextWordSuggestion(word, if (value.commitText.startsWith(' ')) " " + word else word)
            }.filter { it.text.length in 1..24 && it.text.all(Char::isLetter) }.distinctBy { it.text }
        } catch (_: Exception) { emptyList() }
        suggestions = rerank(language, suggestions)
        candidates = suggestions.mapIndexed { index, value ->
            Candidate("association:$generation:$index", value.text, kind = CandidateKind.NEXT_WORD)
        }
    }

    /**
     * Stable descending-frequency sort: unknown or zero frequencies keep
     * their relative editorial order, and no row enters or leaves the set.
     */
    private fun rerank(language: InputLanguage, values: List<NextWordSuggestion>): List<NextWordSuggestion> {
        if (values.size < 2) return values
        val frequencies = runCatching { frequencyProvider(language, values.map(NextWordSuggestion::text)) }
            .getOrDefault(emptyMap())
        if (frequencies.isEmpty()) return values
        return values.sortedByDescending { frequencies[it.text] ?: 0 }
    }

    fun selection(id: String): NextWordSuggestion? = candidates.indexOfFirst { it.id == id }
        .takeIf { it >= 0 }?.let(suggestions::get)
}
