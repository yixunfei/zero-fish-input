package dev.zeroinput.ime.core

import dev.zeroinput.engine.api.PageDirection

sealed interface InputCommand {
    data class Text(val value: String) : InputCommand

    /** Commits the current composition before inserting a literal symbol-page value. */
    data class LiteralText(val value: String) : InputCommand

    /** Inserts an opening/closing pair and places the cursor between them when supported. */
    data class PairedText(val opening: String, val closing: String) : InputCommand

    data object Backspace : InputCommand

    data object Space : InputCommand

    data object Enter : InputCommand

    data object ReconvertLast : InputCommand

    data object UndoSelection : InputCommand
    data object SelectSyllable : InputCommand

    data class SelectCandidate(val visibleIndex: Int, val candidateId: String? = null) : InputCommand

    data class SelectReading(val index: Int) : InputCommand

    data class ChangeCandidatePage(val direction: PageDirection) : InputCommand
}
