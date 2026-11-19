package dev.zeroinput.ime.core

import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.engine.api.EngineUpdate

/** One failed edit owns the already-advanced engine until explicit retry or cancellation. */
internal data class PendingEngineCommit(
    val update: EngineUpdate,
    val before: EngineSnapshot,
    val learningShortcut: String,
    val allowLearning: Boolean,
    val result: CommitTextResult,
    val retryId: String,
) {
    val visible: EngineSnapshot get() = before.copy(
        rawInput = before.rawInput.ifEmpty { update.committedText },
        composition = before.composition.ifEmpty { update.committedText },
        candidates = if (result == CommitTextResult.REJECTED)
            listOf(Candidate(retryId, update.committedText)) else emptyList(),
        highlightedIndex = 0,
        hasPreviousPage = false,
        hasNextPage = false,
        readings = emptyList(),
        canUndoSelection = false,
        canSelectSyllable = false,
        selection = null,
    )
}
