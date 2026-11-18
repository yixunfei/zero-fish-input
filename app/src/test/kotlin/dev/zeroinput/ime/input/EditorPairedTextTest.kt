package dev.zeroinput.ime.input

import android.view.inputmethod.InputConnectionWrapper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EditorPairedTextTest {
    @Test fun acceptedTextIsReportedAsCommittedWhenCaretAdjustmentIsRejected() {
        val commits = mutableListOf<String?>()
        var invalidated = 0
        var writes = 0
        val connection = object : InputConnectionWrapper(null, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean { writes++; return true }
            override fun setSelection(start: Int, end: Int) = false
        }
        val editor = AndroidEditorConnection(0, 0, commits::add, { invalidated++ }) { connection }
        assertTrue(editor.commitPairedText("()", 1))
        assertEquals(1, writes)
        assertEquals(listOf("()"), commits)
        assertTrue(invalidated > 0)
    }
}
