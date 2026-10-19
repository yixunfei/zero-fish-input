package dev.zeroinput.ime.input

import android.view.KeyEvent
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

    private class Fixture(private val failure: String) : InputConnectionWrapper(null, false) {
        var commits = 0
        var deletes = 0
        var keys = 0
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
        override fun setSelection(start: Int, end: Int) = accept("selection")
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence {
            accept("before")
            return "x"
        }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            deletes++
            return accept("delete")
        }
        override fun sendKeyEvent(event: KeyEvent?): Boolean { keys++; return accept("key") }
        override fun performEditorAction(editorAction: Int) = accept("action")
        override fun beginBatchEdit() = accept("begin")
        override fun setComposingRegion(start: Int, end: Int) = accept("region")
        override fun endBatchEdit(): Boolean { batchEnds++; accept("end"); return false }
    }
}
