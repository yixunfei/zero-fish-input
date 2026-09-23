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

class EnglishInputEngine(
    private val learnedSuggestions: LearnedSuggestionSource = LearnedSuggestionSource { _, _ -> emptyList() },
    private val lexicon: List<String> = DefaultEnglishLexicon.words,
) : InputEngine {
    override val descriptor = Descriptor

    private val buffer = StringBuilder()
    private var currentSnapshot = EngineSnapshot.Empty
    private var learnedSuggestionsAllowed = true
    private var predictionsAllowed = true
    // InputEngine calls are serialized by the session controller. These
    // scratch collections therefore belong to this engine instance and can be
    // reused for every snapshot without exposing mutable state in a snapshot.
    private val seenWords = HashSet<String>(MAX_CANDIDATES * 2)
    private val scoredWords = ArrayList<ScoredWord>(MAX_CANDIDATES * 2)

    override val snapshot: EngineSnapshot
        get() = currentSnapshot

    override fun start(context: EditorContext): EngineSnapshot {
        // Learned terms are personal data.  The engine must apply the same
        // session privacy decision as the controller before querying its
        // optional learned-suggestion source.
        predictionsAllowed = context.predictionsAllowed && !context.isSensitive
        learnedSuggestionsAllowed = context.learningAllowed && predictionsAllowed
        return reset()
    }

    override fun handle(key: EngineKey): EngineUpdate = when (key) {
        is EngineKey.Character -> handleText(key.text)
        EngineKey.Backspace -> handleBackspace()
        EngineKey.Space -> commitWithSuffix(" ")
        EngineKey.Enter -> commitWithSuffix("\n")
    }

    override fun selectCandidate(index: Int): EngineUpdate {
        val selected = currentSnapshot.candidates.getOrNull(index) ?: return unchanged(consumed = false)
        buffer.clear()
        currentSnapshot = EngineSnapshot.Empty
        return EngineUpdate(currentSnapshot, committedText = selected.text)
    }

    override fun changePage(direction: PageDirection): EngineUpdate = unchanged(consumed = false)

    override fun reset(): EngineSnapshot {
        buffer.clear()
        currentSnapshot = EngineSnapshot.Empty
        return currentSnapshot
    }

    override fun close() {
        reset()
    }

    private fun handleText(text: String): EngineUpdate {
        if (text.length == 1 && (text[0].isLetter() || text == "'")) {
            buffer.append(text)
            currentSnapshot = createSnapshot()
            return EngineUpdate(currentSnapshot)
        }

        val committed = buffer.toString() + text
        buffer.clear()
        currentSnapshot = EngineSnapshot.Empty
        return EngineUpdate(currentSnapshot, committedText = committed)
    }

    private fun handleBackspace(): EngineUpdate {
        if (buffer.isEmpty()) return unchanged(consumed = false)
        buffer.deleteCharAt(buffer.lastIndex)
        currentSnapshot = createSnapshot()
        return EngineUpdate(currentSnapshot)
    }

    private fun commitWithSuffix(suffix: String): EngineUpdate {
        val committed = buffer.toString() + suffix
        buffer.clear()
        currentSnapshot = EngineSnapshot.Empty
        return EngineUpdate(currentSnapshot, committedText = committed)
    }

    private fun createSnapshot(): EngineSnapshot {
        val typed = buffer.toString()
        if (typed.isEmpty()) return EngineSnapshot.Empty
        if (!predictionsAllowed) return EngineSnapshot(rawInput = typed, composition = typed)

        val normalized = typed.lowercase()
        val learned = if (learnedSuggestionsAllowed) {
            learnedSuggestions.suggestions(normalized, MAX_CANDIDATES)
        } else {
            emptyList()
        }
        // Collected with plain loops: a Sequence pipeline allocates an
        // iterator and a lambda for every stage on each keystroke, while the
        // lexicon is small enough that loops are allocation-light.  Keeping
        // the first occurrence of a word mirrors the previous distinctBy.
        seenWords.clear()
        scoredWords.clear()
        fun collect(word: String, score: Int) {
            if (seenWords.add(word.lowercase())) scoredWords.add(ScoredWord(word, score))
        }
        collect(typed, Int.MAX_VALUE)
        for (term in learned) collect(term.text, 10_000 + term.weight)
        var matched = 0
        for (word in lexicon) {
            if (matched >= MAX_CANDIDATES) break
            if (word.startsWith(normalized)) {
                collect(word, 1_000 - matched)
                matched++
            }
        }
        scoredWords.sortWith(SCORED_WORD_COMPARATOR)
        val limit = minOf(scoredWords.size, MAX_CANDIDATES)
        val candidates = ArrayList<Candidate>(limit)
        for (index in 0 until limit) {
            val scored = scoredWords[index]
            candidates += Candidate(
                id = "english:$index:${scored.word}",
                text = preserveCase(typed, scored.word),
                score = scored.score,
            )
        }

        return EngineSnapshot(
            rawInput = typed,
            composition = typed,
            candidates = candidates,
        )
    }

    private fun unchanged(consumed: Boolean) = EngineUpdate(currentSnapshot, consumed = consumed)

    private fun preserveCase(typed: String, suggestion: String): String = when {
        typed.all(Char::isUpperCase) -> suggestion.uppercase()
        typed.firstOrNull()?.isUpperCase() == true -> suggestion.replaceFirstChar(Char::uppercase)
        else -> suggestion
    }

    companion object {
        private const val MAX_CANDIDATES = 8
        private val SCORED_WORD_COMPARATOR = compareByDescending<ScoredWord> { it.score }

        val Descriptor = EngineDescriptor(
            id = "zeroinput.english",
            displayName = "ZeroInput English",
            version = "1",
            languages = setOf(InputLanguage.ENGLISH),
        )
    }

    private data class ScoredWord(val word: String, val score: Int)
}
