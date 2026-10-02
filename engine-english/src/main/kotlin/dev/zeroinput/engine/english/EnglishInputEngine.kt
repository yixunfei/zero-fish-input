package dev.zeroinput.engine.english

import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.EngineDescriptor
import dev.zeroinput.engine.api.EngineKey
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.engine.api.EngineUpdate
import dev.zeroinput.engine.api.InputEngine
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.LearnedSuggestionSource
import dev.zeroinput.engine.api.PageDirection
import java.util.Locale

/** Deterministic offline English word completion. */
class EnglishInputEngine(
    private val learnedSuggestions: LearnedSuggestionSource = LearnedSuggestionSource { _, _ -> emptyList() },
    lexicon: List<String> = DefaultEnglishLexicon.words,
) : InputEngine {
    override val descriptor = Descriptor

    private val lexiconWords = lexicon.asSequence()
        .map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotEmpty() && it.all { character -> character.isLetter() || character == '\'' } }
        .distinct()
        .toList()
    private val prefixIndex: Map<String, List<IndexedWord>> = buildPrefixIndex(lexiconWords)
    private val buffer = StringBuilder()
    private var currentSnapshot = EngineSnapshot.Empty
    private var learnedSuggestionsAllowed = true
    private var predictionsAllowed = true
    private var page = 0
    private var allCandidates: List<ScoredWord> = emptyList()
    private val seenWords = HashSet<String>(LEXICON_PAGE_SIZE * 2)
    private val scoredWords = ArrayList<ScoredWord>(LEXICON_PAGE_SIZE * 4)

    override val snapshot: EngineSnapshot
        get() = currentSnapshot

    override fun start(context: EditorContext): EngineSnapshot {
        predictionsAllowed = context.predictionsAllowed && !context.isSensitive
        learnedSuggestionsAllowed = context.learningAllowed && predictionsAllowed
        return reset()
    }

    override fun handle(key: EngineKey): EngineUpdate = when (key) {
        is EngineKey.Character -> handleText(key.text)
        EngineKey.Backspace -> handleBackspace()
        EngineKey.Space -> commitWithSuffix(" ")
        EngineKey.Enter -> if (buffer.isEmpty()) unchanged(consumed = false) else commitWithSuffix("")
    }

    override fun selectCandidate(index: Int): EngineUpdate {
        val selected = currentSnapshot.candidates.getOrNull(index) ?: return unchanged(consumed = false)
        buffer.clear()
        page = 0
        allCandidates = emptyList()
        currentSnapshot = EngineSnapshot.Empty
        // Selecting a completed English word ends the word, matching normal
        // hardware-keyboard behavior while keeping corrections explicit.
        return EngineUpdate(currentSnapshot, committedText = selected.text + " ")
    }

    override fun changePage(direction: PageDirection): EngineUpdate {
        if (buffer.isEmpty()) return unchanged(consumed = false)
        val target = page + if (direction == PageDirection.NEXT) 1 else -1
        if (target < 0 || target * PAGE_SIZE >= allCandidates.size) return unchanged(consumed = false)
        page = target
        currentSnapshot = publishPage(buffer.toString())
        return EngineUpdate(currentSnapshot)
    }

    override fun reset(): EngineSnapshot {
        buffer.clear()
        page = 0
        allCandidates = emptyList()
        currentSnapshot = EngineSnapshot.Empty
        return currentSnapshot
    }

    override fun close() {
        reset()
    }

    private fun handleText(text: String): EngineUpdate {
        if (text.length == 1 && (text[0].isLetter() || text == "'")) {
            if (buffer.length >= MAX_BUFFER_LENGTH) return unchanged(consumed = true)
            buffer.append(text)
            page = 0
            currentSnapshot = createSnapshot()
            return EngineUpdate(currentSnapshot)
        }
        val committed = buffer.toString() + text
        reset()
        return EngineUpdate(currentSnapshot, committedText = committed)
    }

    private fun handleBackspace(): EngineUpdate {
        if (buffer.isEmpty()) return unchanged(consumed = false)
        buffer.deleteCharAt(buffer.lastIndex)
        page = 0
        currentSnapshot = createSnapshot()
        return EngineUpdate(currentSnapshot)
    }

    private fun commitWithSuffix(suffix: String): EngineUpdate {
        val committed = buffer.toString() + suffix
        reset()
        return EngineUpdate(currentSnapshot, committedText = committed)
    }

    private fun createSnapshot(): EngineSnapshot {
        val typed = buffer.toString()
        if (typed.isEmpty()) return EngineSnapshot.Empty
        if (!predictionsAllowed) {
            allCandidates = emptyList()
            return EngineSnapshot(rawInput = typed, composition = typed)
        }
        val normalized = typed.lowercase(Locale.ROOT)
        seenWords.clear()
        scoredWords.clear()
        var insertionOrder = 0
        fun collect(word: String, score: Int, comment: String = "") {
            val normalizedWord = word.lowercase(Locale.ROOT)
            if (normalizedWord.isEmpty() || !seenWords.add(normalizedWord)) return
            scoredWords += ScoredWord(word, score, insertionOrder++, comment)
        }
        // Keep the literal input available as a deterministic fallback. This
        // preserves editing even when the word is absent from the seed data.
        collect(typed, EXACT_INPUT_SCORE)
        if (learnedSuggestionsAllowed) {
            val learned = learnedSuggestions.suggestions(normalized, MAX_LEARNED_SUGGESTIONS)
            for (term in learned) {
                val score = (LEARNED_SCORE_BASE.toLong() + term.weight.toLong())
                    .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                collect(term.text, score)
            }
        }
        for (indexed in prefixIndex[normalized].orEmpty()) {
            collect(indexed.word, LEXICON_SCORE_BASE - indexed.index)
        }
        scoredWords.sortWith(compareByDescending<ScoredWord> { it.score }.thenBy { it.order })
        allCandidates = scoredWords.toList()
        return publishPage(typed)
    }

    private fun publishPage(typed: String): EngineSnapshot {
        val from = page * PAGE_SIZE
        val visible = allCandidates.drop(from).take(PAGE_SIZE)
        val candidates = visible.mapIndexed { index, scored ->
            Candidate(
                id = "english:${from + index}:${scored.word}",
                text = preserveCase(typed, scored.word),
                comment = scored.comment,
                score = scored.score,
            )
        }
        return EngineSnapshot(
            rawInput = typed,
            composition = typed,
            candidates = candidates,
            hasPreviousPage = page > 0,
            hasNextPage = (page + 1) * PAGE_SIZE < allCandidates.size,
        )
    }

    private fun unchanged(consumed: Boolean) = EngineUpdate(currentSnapshot, consumed = consumed)

    private fun preserveCase(typed: String, suggestion: String): String = when {
        typed.all(Char::isUpperCase) -> suggestion.uppercase(Locale.ROOT)
        typed.firstOrNull()?.isUpperCase() == true -> suggestion.replaceFirstChar { it.uppercase(Locale.ROOT) }
        else -> suggestion
    }

    companion object {
        private const val PAGE_SIZE = 8
        private const val LEXICON_PAGE_SIZE = PAGE_SIZE
        private const val MAX_LEARNED_SUGGESTIONS = 32
        private const val EXACT_INPUT_SCORE = Int.MAX_VALUE
        private const val LEARNED_SCORE_BASE = 1_000_000
        private const val LEXICON_SCORE_BASE = 100_000
        private const val MAX_BUFFER_LENGTH = 64

        val Descriptor = EngineDescriptor(
            id = "zeroinput.english",
            displayName = "ZeroInput English",
            version = "1",
            languages = setOf(InputLanguage.ENGLISH),
        )
    }

    private data class ScoredWord(val word: String, val score: Int, val order: Int, val comment: String = "")
    private data class IndexedWord(val word: String, val index: Int)

    private fun buildPrefixIndex(words: List<String>): Map<String, List<IndexedWord>> {
        val index = HashMap<String, MutableList<IndexedWord>>()
        words.forEachIndexed { position, word ->
            for (length in 1..word.length) {
                index.getOrPut(word.substring(0, length)) { ArrayList() }
                    .add(IndexedWord(word, position))
            }
        }
        return index
    }
}
