package dev.zeroinput.ime.input

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import dev.zeroinput.ime.core.EditorConnection
import dev.zeroinput.ime.core.ReconversionEditorConnection

class AndroidEditorConnection(
    initialSelectionStart: Int = -1,
    initialSelectionEnd: Int = -1,
    private val onCommitted: (String?) -> Unit = {},
    private val onContextInvalidated: () -> Unit = {},
    private val current: () -> InputConnection?,
) : EditorConnection, ReconversionEditorConnection {
    private val selection = EditorSelectionState(initialSelectionStart, initialSelectionEnd)
    private var committedConnection: InputConnection? = null

    fun selectedLength(): Int? = selection.selectedLength()

    fun updateSelection(start: Int, end: Int, composingStart: Int = -1, composingEnd: Int = -1): Boolean =
        selection.updated(start, end, composingStart, composingEnd).also { if (it) onContextInvalidated() }

    override fun setComposingText(text: String) {
        committedConnection = null
        if (current()?.setComposingText(text, 1) == true) selection.replaced(text.length, composing = true)
        else { selection.unknown(); onContextInvalidated() }
    }

    override fun finishComposingText() {
        current()?.finishComposingText()
        selection.finishComposition()
    }

    override fun commitText(text: String): Boolean {
        val connection = current()
        committedConnection = null
        if (connection?.commitText(text, 1) == true) {
            selection.replaced(text.length, composing = false)
            committedConnection = connection
            onCommitted(text)
            return true
        } else { selection.unknown(); onCommitted(null); return false }
    }

    override fun invalidateReconversion() { selection.invalidate(); committedConnection = null }

    override fun reopenCommittedText(expectedText: String): Boolean {
        onContextInvalidated()
        if (expectedText.length !in 1..128) return false
        val range = selection.verifiedRange(expectedText.length) ?: return false
        val connection = current() ?: return false
        if (connection !== committedConnection) { invalidateReconversion(); return false }
        invalidateReconversion()
        connection.beginBatchEdit()
        return try {
            val before = connection.getTextBeforeCursor(expectedText.length, 0) ?: return false
            if (before.toString() != expectedText || current() !== connection) return false
            if (!connection.setComposingRegion(range.start, range.end)) return false
            selection.reopened(range)
            true
        } finally { connection.endBatchEdit() }
    }

    override fun deleteBeforeCursor() {
        val hasSelection = selection.selectedLength() != null
        onContextInvalidated()
        selection.unknown()
        val connection = current() ?: return
        // deleteSurroundingText excludes the selection, so it would delete
        // unrelated text before it. Replace the acknowledged selection directly.
        if (hasSelection) {
            if (!connection.commitText("", 1)) sendKey(connection, KeyEvent.KEYCODE_DEL)
            return
        }
        val before = connection.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        val deleteLength = if (
            before.length >= 2 &&
            Character.isLowSurrogate(before[before.lastIndex]) &&
            Character.isHighSurrogate(before[before.lastIndex - 1])
        ) {
            2
        } else {
            1
        }
        if (!connection.deleteSurroundingText(deleteLength, 0)) sendKey(connection, KeyEvent.KEYCODE_DEL)
    }

    override fun performEditorAction(actionId: Int): Boolean {
        onContextInvalidated()
        selection.invalidate()
        return current()?.performEditorAction(actionId) == true
    }

    override fun sendEnterKey() {
        onContextInvalidated()
        selection.unknown()
        current()?.let { sendKey(it, KeyEvent.KEYCODE_ENTER) }
    }

    private fun sendKey(connection: InputConnection, keyCode: Int) {
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
