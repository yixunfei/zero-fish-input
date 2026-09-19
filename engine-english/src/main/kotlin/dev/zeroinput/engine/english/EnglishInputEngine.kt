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

    override val snapshot: EngineSnapshot
        get() = currentSnapshot

    override fun start(context: EditorContext): EngineSnapshot {
        // Learned terms are personal data.  The engine must apply the same
        // session privacy decision as the controller before querying its
        // optional learned-suggestion source.
        learnedSuggestionsAllowed = context.learningAllowed && !context.isSensitive
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
        val seen = HashSet<String>(MAX_CANDIDATES * 2)
        val scored = ArrayList<Pair<String, Int>>(MAX_CANDIDATES * 2)
        fun collect(word: String, score: Int) {
            if (seen.add(word.lowercase())) scored.add(word to score)
        }
        collect(typed, Int.MAX_VALUE)
        learned.forEach { collect(it.text, 10_000 + it.weight) }
        var matched = 0
        for (word in lexicon) {
            if (matched >= MAX_CANDIDATES) break
            if (word.startsWith(normalized)) {
                collect(word, 1_000 - matched)
                matched++
            }
        }
        scored.sortByDescending { it.second }
        val limit = minOf(scored.size, MAX_CANDIDATES)
        val candidates = ArrayList<Candidate>(limit)
        for (index in 0 until limit) {
            val (word, score) = scored[index]
            candidates += Candidate(
                id = "english:$index:$word",
                text = preserveCase(typed, word),
                score = score,
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

        val Descriptor = EngineDescriptor(
            id = "zeroinput.english",
            displayName = "ZeroInput English",
            version = "1",
            languages = setOf(InputLanguage.ENGLISH),
        )
    }
}
