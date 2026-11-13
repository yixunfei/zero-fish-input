package dev.zeroinput.ime.ui

/** Display offset only; a negative offset hides a caret inside a collapsed private prefix. */
data class DraftCaretUi(val offset: Int, val revision: Long)
