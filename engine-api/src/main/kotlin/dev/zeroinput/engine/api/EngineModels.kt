package dev.zeroinput.engine.api

enum class InputLanguage {
    CHINESE,
    ENGLISH,
}

data class EngineDescriptor(
    val id: String,
    val displayName: String,
    val version: String,
    val languages: Set<InputLanguage>,
    val isFallback: Boolean = false,
    val capabilities: Set<EngineCapability> = emptySet(),
)

data class EditorContext(
    val language: InputLanguage,
    val isSensitive: Boolean,
    val learningAllowed: Boolean,
    val packageName: String?,
    /** Optional predictions may be disabled while required script conversion remains available. */
    val predictionsAllowed: Boolean = true,
)

data class Candidate(
    val id: String,
    val text: String,
    val comment: String = "",
    val score: Int = 0,
    val input: String = "",
    val kind: CandidateKind = CandidateKind.STANDARD,
)

enum class CandidateKind { STANDARD, RELATED_READING, NEXT_WORD }

data class EngineSnapshot(
    val rawInput: String = "",
    val composition: String = "",
    val candidates: List<Candidate> = emptyList(),
    val highlightedIndex: Int = 0,
    val hasPreviousPage: Boolean = false,
    val hasNextPage: Boolean = false,
    val readings: List<String> = emptyList(),
    val canUndoSelection: Boolean = false,
    val canSelectSyllable: Boolean = false,
) {
    val isComposing: Boolean
        get() = rawInput.isNotEmpty() || composition.isNotEmpty()

    companion object {
        val Empty = EngineSnapshot()
    }
}

data class EngineUpdate(
    val snapshot: EngineSnapshot,
    val committedText: String = "",
    val consumed: Boolean = true,
    val committedInput: String = "",
    val learnable: Boolean = true,
)

sealed interface EngineKey {
    data class Character(val text: String) : EngineKey

    data object Backspace : EngineKey

    data object Space : EngineKey

    data object Enter : EngineKey
}

enum class PageDirection {
    PREVIOUS,
    NEXT,
}
