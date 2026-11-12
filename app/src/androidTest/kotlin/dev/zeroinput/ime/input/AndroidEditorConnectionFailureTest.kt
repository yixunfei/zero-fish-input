package dev.zeroinput.ime.input

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidEditorConnectionFailureTest {
    @Test fun throwingCompositionAndActionCallsInvalidateWithoutEscaping() {
        val operations: List<Pair<String, (AndroidEditorConnection) -> Unit>> = listOf(
            "compose" to { it.setComposingText("fixture") },
            "finish" to { it.finishComposingText() },
            "compose" to { assertFalse(it.clearComposingText()) },
            "action" to { assertFalse(it.performEditorAction(1)) },
            "key" to { it.sendEnterKey() },
        )
        operations.forEach { (failure, operation) ->
            val connection = Fixture(failure)
            var invalidations = 0
            val adapter = AndroidEditorConnection(1, 3, onContextInvalidated = { invalidations++ }) { connection }
            operation(adapter)
            assertNull(adapter.selectedLength())
            assertTrue(invalidations > 0)
        }
    }

    @Test fun throwingCommitReportsFailureWithoutReplayingText() {
        val connection = Fixture("commit")
        val commits = mutableListOf<String?>()
        val adapter = AndroidEditorConnection(0, 0, onCommitted = { commits += it }) { connection }

        assertFalse(adapter.commitText("fixture"))

        assertEquals(1, connection.commits)
        assertEquals(listOf<String?>(null), commits)
        assertFalse(adapter.reopenCommittedText("fixture"))
    }

    @Test fun throwingCursorPlacementFailsThePairWithoutCommittingItTwice() {
        val connection = Fixture("selection")
        val adapter = AndroidEditorConnection(0, 0) { connection }

        assertFalse(adapter.commitPairedText("()", 1))

        assertEquals(1, connection.commits)
        assertFalse(adapter.reopenCommittedText("()"))
    }

    @Test fun pairedCursorPlacementIsSkippedAfterTheBoundEditorChanges() {
        val old = Fixture("")
        val replacement = Fixture("")
        var current = old
        val adapter = AndroidEditorConnection(0, 0, onCommitted = { current = replacement }) { current }

        assertTrue(adapter.commitPairedText("[]", 1))

        assertEquals(1, old.commits)
        assertEquals(0, old.selections)
        assertEquals(0, replacement.selections)
    }

    @Test fun uncertainDeleteDoesNotReplayADeleteKey() {
        for (failure in listOf("before", "delete")) {
            val connection = Fixture(failure)
            AndroidEditorConnection(1, 1) { connection }.deleteBeforeCursor()
            assertEquals(0, connection.keys)
            assertEquals(if (failure == "delete") 1 else 0, connection.deletes)
        }
        val selected = Fixture("commit")
        AndroidEditorConnection(0, 1) { selected }.deleteBeforeCursor()
        assertEquals(1, selected.commits)
        assertEquals(0, selected.keys)
    }

    @Test fun unavailableCursorTextDelegatesOneBackspaceToTheEditor() {
        val connection = object : Fixture("") {
            override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? = null
        }

        AndroidEditorConnection(1, 1) { connection }.deleteBeforeCursor()

        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).map { it to KeyEvent.KEYCODE_DEL },
            connection.keyEvents)
        assertEquals(0, connection.deletes)
    }

    @Test fun aCursorReadThatRebindsTheEditorCannotDispatchBackspace() {
        val replacement = Fixture("")
        for (text in listOf(null, "x")) {
            var current: InputConnection? = null
            val old = object : Fixture("") {
                override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? {
                    current = replacement
                    return text
                }
            }
            current = old

            AndroidEditorConnection(1, 1) { current }.deleteBeforeCursor()

            assertEquals(0, old.keys)
            assertEquals(0, old.deletes)
            assertEquals(0, replacement.keys)
            assertEquals(0, replacement.deletes)
        }
    }

    @Test fun aRejectedEditThatRebindsTheEditorCannotDispatchBackspace() {
        val replacement = Fixture("")
        for (selectionStart in listOf(0, 1)) {
            var current: InputConnection? = null
            val old = object : Fixture("") {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    current = replacement
                    return false
                }

                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    current = replacement
                    return false
                }
            }
            current = old

            AndroidEditorConnection(selectionStart, 1) { current }.deleteBeforeCursor()

            assertSame(replacement, current)
            assertEquals(0, old.keys)
            assertEquals(0, replacement.keys)
        }
    }

    @Test fun reconversionAlwaysAttemptsBatchCleanupAndRejectsThrowingCalls() {
        for (failure in listOf("begin", "before", "region", "end")) {
            val connection = Fixture(failure)
            val adapter = AndroidEditorConnection(0, 0) { connection }
            assertTrue(adapter.commitText("x"))
            adapter.updateSelection(1, 1, previousStart = 0, previousEnd = 0)

            assertFalse(adapter.reopenCommittedText("x"))

            assertEquals(1, connection.batchEnds)
            assertFalse(adapter.reopenCommittedText("x"))
        }
    }

    @Test fun endingTheOutermostBatchNormallyReturnsFalseAndStillAllowsReconversion() {
        val connection = Fixture("")
        val adapter = AndroidEditorConnection(0, 0) { connection }
        assertTrue(adapter.commitText("x"))
        adapter.updateSelection(1, 1, previousStart = 0, previousEnd = 0)

        assertTrue(adapter.reopenCommittedText("x"))
        assertEquals(1, connection.batchEnds)
    }

    private open class Fixture(private val failure: String) : InputConnectionWrapper(null, false) {
        var commits = 0
        var selections = 0
        var deletes = 0
        val keyEvents = mutableListOf<Pair<Int, Int>>()
        val keys get() = keyEvents.size
        var batchEnds = 0
        private fun accept(operation: String): Boolean {
            if (failure == operation) throw IllegalStateException("Fixture editor failure")
            return true
        }
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int) = accept("compose")
        override fun finishComposingText() = accept("finish")
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            commits++
            return accept("commit")
        }
        override fun setSelection(start: Int, end: Int): Boolean {
            selections++
            return accept("selection")
        }
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? {
            accept("before")
            return "x"
        }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            deletes++
            return accept("delete")
        }
        override fun sendKeyEvent(event: KeyEvent?): Boolean {
            requireNotNull(event)
            keyEvents.add(event.action to event.keyCode)
            return accept("key")
        }
        override fun performEditorAction(editorAction: Int) = accept("action")
        override fun beginBatchEdit() = accept("begin")
        override fun setComposingRegion(start: Int, end: Int) = accept("region")
        override fun endBatchEdit(): Boolean { batchEnds++; accept("end"); return false }
    }
}
