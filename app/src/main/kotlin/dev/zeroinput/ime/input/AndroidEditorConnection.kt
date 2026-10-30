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
        previousStart: Int = -1, previousEnd: Int = -1): Boolean {
        if (current() == null) return false
        return selection.updated(start, end, composingStart, composingEnd, previousStart, previousEnd)
            .also { if (it) onContextInvalidated() }
    }

    override fun setComposingText(text: String) {
        committedConnection = null
        // Register the expected cursor/span before crossing the Binder call.
        // Editors are allowed to invoke updateSelection synchronously from
        // setComposingText; registering afterwards makes that callback look
        // like an external cursor move and drops the next key.
        selection.replaced(text.length, composing = true)
        if (platformCall { current()?.setComposingText(text, 1) } != true) {
            selection.unknown()
            onContextInvalidated()
        }
    }

    override fun finishComposingText() {
        selection.finishComposition()
        if (platformCall { current()?.finishComposingText() } != true) invalidateContext()
    }

    override fun clearComposingText(): Boolean {
        val connection = current() ?: run { invalidateContext(); return false }
        committedConnection = null
        selection.replaced(0, composing = true)
        if (platformCall { connection.setComposingText("", 1) } != true) {
            selection.unknown()
            onContextInvalidated()
            return false
        }
        selection.finishComposition()
        if (platformCall { connection.finishComposingText() } == true) return true
        selection.unknown()
        onContextInvalidated()
        return false
    }

    override fun commitText(text: String): Boolean {
        val connection = current()
        committedConnection = null
        // As with pre-edit updates, reserve the resulting cursor before the
        // platform callback can arrive.  A rejected commit invalidates the
        // prediction and forces the next interaction to re-anchor.
        selection.replaced(text.length, composing = false)
        if (platformCall { connection?.commitText(text, 1) } == true) {
            committedConnection = connection
            onCommitted(text)
            return true
        } else { invalidateContext(); onCommitted(null); return false }
    }

    override fun commitPairedText(text: String, cursorOffset: Int): Boolean {
        if (text.isEmpty()) return false
        val connection = current() ?: run { invalidateContext(); onCommitted(null); return false }
        committedConnection = null
        val start = selection.insertionStart()
        selection.replaced(text.length, composing = false)
        if (platformCall { connection.commitText(text, 1) } != true) {
            selection.unknown()
            onContextInvalidated()
            onCommitted(null)
            return false
        }
        committedConnection = connection
        onCommitted(text)
        val target = start?.plus(cursorOffset)?.coerceIn(start, start + text.length)
        if (target != null) {
            selection.expectSelection(target)
            if (platformCall { connection.setSelection(target, target) } != true) {
                invalidateContext()
                return false
            }
        }
        return true
    }

    override fun invalidateReconversion() { selection.invalidate(); committedConnection = null }

    override fun reopenCommittedText(expectedText: String): Boolean {
        onContextInvalidated()
        if (expectedText.length !in 1..128) return false
        val range = selection.verifiedRange(expectedText.length) ?: return false
        val connection = current() ?: return false
        if (connection !== committedConnection) { invalidateReconversion(); return false }
        invalidateReconversion()
        var ended = false
        val reopened = try {
            if (platformCall { connection.beginBatchEdit() } == true) {
                reopenWithinBatch(connection, expectedText, range)
            } else { invalidateContext(); false }
        } finally {
            // false normally means the outermost batch ended; only an exception is failure.
            ended = platformCall { connection.endBatchEdit() } != null
            if (!ended) invalidateContext()
        }
        return reopened && ended
    }

    private fun reopenWithinBatch(connection: InputConnection, expectedText: String,
        range: EditorSelectionState.CommitRange): Boolean {
        val before = platformCall { connection.getTextBeforeCursor(expectedText.length, 0) }
        if (before == null || before.toString() != expectedText || current() !== connection) {
            invalidateContext()
            return false
        }
        selection.reopened(range)
        if (platformCall { connection.setComposingRegion(range.start, range.end) } == true) return true
        invalidateContext()
        return false
    }

    override fun deleteBeforeCursor() {
        val hasSelection = selection.selectedLength() != null
        onContextInvalidated()
        selection.unknown()
        val connection = current() ?: return
        // deleteSurroundingText excludes the selection, so it would delete
        // unrelated text before it. Replace the acknowledged selection directly.
        if (hasSelection) {
            // A throwing editor may already have applied the edit. Never replay it.
            if (platformCall { connection.commitText("", 1) } == false) sendKey(connection, KeyEvent.KEYCODE_DEL)
            return
        }
        val before = platformCall {
            connection.getTextBeforeCursor(UnicodeDeletionBoundary.LOOKBEHIND_LIMIT, InputConnection.GET_TEXT_WITH_STYLES)
        } ?: return
        if (current() !== connection) return
        val deleteLength = UnicodeDeletionBoundary.precedingLength(before)
        if (deleteLength == 1 && UnicodeDeletionBoundary.prefersEditorDelete(before)) {
            // A true result (or exception after an uncertain edit) must not be
            // followed by a second deletion. Only explicit rejection falls back.
            if (sendKey(connection, KeyEvent.KEYCODE_DEL) != false) return
            if (current() !== connection) return
            platformCall { connection.deleteSurroundingText(1, 0) }
            return
        }
        if (platformCall { connection.deleteSurroundingText(deleteLength, 0) } == false) sendKey(connection, KeyEvent.KEYCODE_DEL)
    }

    override fun performEditorAction(actionId: Int): Boolean {
        onContextInvalidated()
        selection.invalidate()
        val accepted = platformCall { current()?.performEditorAction(actionId) } == true
        if (!accepted) invalidateContext()
        return accepted
    }

    override fun sendEnterKey() {
        onContextInvalidated()
        selection.unknown()
        current()?.let { sendKey(it, KeyEvent.KEYCODE_ENTER) }
    }

    private fun sendKey(connection: InputConnection, keyCode: Int): Boolean? {
        val accepted = platformCall { connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode)) }
        if (accepted != true) return accepted
        platformCall { connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode)) }
        return true
    }

    private fun invalidateContext() {
        selection.unknown()
        committedConnection = null
        onContextInvalidated()
    }

    /** Null distinguishes an uncertain throwing edit from a rejected Boolean edit. */
    private inline fun <T> platformCall(operation: () -> T): T? = try {
        operation()
    } catch (_: RuntimeException) {
        invalidateContext()
        null
    }
}
