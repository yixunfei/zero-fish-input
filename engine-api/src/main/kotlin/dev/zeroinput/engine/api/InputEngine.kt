package dev.zeroinput.engine.api

interface InputEngine : AutoCloseable {
    val descriptor: EngineDescriptor

    val snapshot: EngineSnapshot

    fun start(context: EditorContext): EngineSnapshot

    fun handle(key: EngineKey): EngineUpdate

    /** Whether a character can extend composition rather than commit it as punctuation. */
    fun acceptsCompositionCharacter(character: Char): Boolean = character.isLetterOrDigit() || character == '\''

    fun selectCandidate(index: Int): EngineUpdate

    fun changePage(direction: PageDirection): EngineUpdate

    fun reset(): EngineSnapshot

    override fun close()
}

interface InputEngineFactory {
    val descriptor: EngineDescriptor

    fun isAvailable(): Boolean

    fun create(): InputEngine
}

interface ConfigurableChineseEngineFactory : InputEngineFactory {
    fun create(options: ChineseInputOptions): InputEngine
}

/** Applies the engine's output script to personal suggestions without storing a converted copy. */
interface CandidateTextNormalizer {
    fun normalizeCandidateText(text: String): String
}

/** Narrows an ambiguous spelling using a choice from the current snapshot. */
interface ReadingSelectionEngine {
    fun selectReading(index: Int): EngineUpdate
}

fun interface LearnedSuggestionSource {
    fun suggestions(prefix: String, limit: Int): List<WeightedTerm>
}

data class WeightedTerm(
    val text: String,
    val weight: Int,
)
