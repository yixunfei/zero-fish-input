package dev.zeroinput.ai.api

/** A preview proposal cannot apply to a different context revision. */
class AiCompactionProposal(val revision: Long, val summary: String) {
    init { require(summary.isNotBlank() && summary.length <= AiReference.MAX_REFERENCE_CHARS &&
        summary.none { it.isISOControl() && it !in "\n\r\t" }) { "Invalid context summary" } }
    override fun toString() = "AiCompactionProposal(redacted)"
}

/** Only selected earlier turns and the explicitly included previous summary are summarized. */
class AiCompactionInput(state: AiContextState) {
    val revision = state.revision
    val indices: Set<Int> = java.util.Collections.unmodifiableSet(state.selectedHistory.filter {
        it in state.messages.indices && it < state.messages.size - 2
    }.toSet())
    val history: List<AiMessage> = java.util.Collections.unmodifiableList(state.messages.filterIndexed { index, _ -> index in indices })
    val summaries: List<AiReference> = java.util.Collections.unmodifiableList(state.references.filter { it.source == AiReference.Source.SUMMARY })
    init { require(history.isNotEmpty()) { "No earlier selected history" }; AiReference.validate(history, summaries) }
    override fun toString() = "AiCompactionInput(redacted)"
}
