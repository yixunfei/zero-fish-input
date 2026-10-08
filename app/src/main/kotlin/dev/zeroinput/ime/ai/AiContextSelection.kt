package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.*

/** Owner-thread selection; stored messages and their network projection are independent. */
internal class AiContextSelection {
    var state = AiContextState()
        private set

    fun reset(messages: List<AiMessage> = emptyList()) {
        state = AiContextState(state.revision + 1, messages.toList())
    }

    fun history(): List<AiMessage> = state.messages.filterIndexed { index, _ -> index in state.selectedHistory }

    fun include(revision: Long, index: Int, included: Boolean): Boolean {
        if (revision != state.revision || index !in state.messages.indices) return false
        val selected = if (included) state.selectedHistory + index else state.selectedHistory - index
        return replace(selected, state.references)
    }

    fun recent(revision: Long): Boolean {
        if (revision != state.revision) return false
        var remaining = AiReference.MAX_CONTEXT_CHARS - state.references.sumOf { it.text.length }
        var count = 0
        val indices = linkedSetOf<Int>()
        for (index in state.messages.indices.reversed()) {
            val message = state.messages[index]
            if (message.role == AiRole.SYSTEM) continue
            if (count == AiReference.MAX_CONTEXT_MESSAGES || remaining < message.content.length) break
            remaining -= message.content.length
            indices += index
            count++
        }
        return replace(indices, state.references)
    }

    fun add(revision: Long, values: List<AiReference>): Boolean {
        if (revision != state.revision) return false
        val unique = (state.references + values).distinctBy { it.text }
        return replace(state.selectedHistory, unique)
    }

    fun remove(revision: Long, index: Int): Boolean {
        if (revision != state.revision || index !in state.references.indices) return false
        return replace(state.selectedHistory, state.references.filterIndexed { i, _ -> i != index })
    }

    fun clear(revision: Long): Boolean {
        if (revision != state.revision) return false
        return replace(emptySet(), emptyList())
    }

    fun clearPages(): Boolean {
        if (state.references.none { it.source == AiReference.Source.PAGE }) return false
        return replace(state.selectedHistory, state.references.filter { it.source != AiReference.Source.PAGE })
    }

    /** Preserve explicitly selected history when the bounded stored transcript drops its oldest turns. */
    fun append(messages: List<AiMessage>, dropped: Int) {
        val previous = state.selectedHistory.mapNotNull { (it - dropped).takeIf { index -> index >= 0 } }.toSet()
        state = AiContextState(state.revision + 1, messages.toList(), previous, state.references)
        // Continuing this active chat includes its new turn only when the complete pair fits.
        replace(previous + listOf(messages.lastIndex - 1, messages.lastIndex), state.references)
    }

    private fun replace(selected: Set<Int>, references: List<AiReference>): Boolean {
        val history = state.messages.filterIndexed { index, _ -> index in selected }
        if (runCatching { AiReference.validate(history, references) }.isFailure) return false
        state = AiContextState(state.revision + 1, state.messages, selected.toSet(), references.toList())
        return true
    }
}
