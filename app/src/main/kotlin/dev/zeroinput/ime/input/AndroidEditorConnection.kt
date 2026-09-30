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

    fun updateSelection(start: Int, end: Int, composingStart: Int = -1, composingEnd: Int = -1,
        previousStart: Int = -1, previousEnd: Int = -1): Boolean =
        selection.updated(start, end, composingStart, composingEnd, previousStart, previousEnd)
            .also { if (it) onContextInvalidated() }

    override fun setComposingText(text: String) {
        committedConnection = null
        // Register the expected cursor/span before crossing the Binder call.
        // Editors are allowed to invoke updateSelection synchronously from
        // setComposingText; registering afterwards makes that callback look
        // like an external cursor move and drops the next key.
        selection.replaced(text.length, composing = true)
        if (current()?.setComposingText(text, 1) != true) {
            selection.unknown()
            onContextInvalidated()
        }
    }

    override fun finishComposingText() {
        selection.finishComposition()
        current()?.finishComposingText()
    }

    override fun commitText(text: String): Boolean {
        val connection = current()
        committedConnection = null
        // As with pre-edit updates, reserve the resulting cursor before the
        // platform callback can arrive.  A rejected commit invalidates the
        // prediction and forces the next interaction to re-anchor.
        selection.replaced(text.length, composing = false)
        if (connection?.commitText(text, 1) == true) {
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
            selection.reopened(range)
            if (!connection.setComposingRegion(range.start, range.end)) {
                selection.unknown()
                return false
            }
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
