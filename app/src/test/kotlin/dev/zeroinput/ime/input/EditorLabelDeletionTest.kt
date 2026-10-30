package dev.zeroinput.ime.input

import android.view.KeyEvent
import android.view.inputmethod.InputConnectionWrapper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EditorLabelDeletionTest {
    @Test fun changedConnectionAfterReadReceivesNoDeletion() {
        val next = Fixture("new[文字]")
        var current: android.view.inputmethod.InputConnection? = null
        var deleted = false
        val old = object : InputConnectionWrapper(null, false) {
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
                current = next
                return "old[文字]"
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean { deleted = true; return true }
            override fun sendKeyEvent(event: KeyEvent): Boolean { deleted = true; return true }
        }
        current = old
        AndroidEditorConnection { current }.deleteBeforeCursor()
        assertFalse(deleted)
        assertTrue(next.keys.isEmpty())
        assertTrue(next.deletes.isEmpty())
    }

    @Test fun customLabelsGiveTheHostOneDeleteGestureWithoutGuessingTheirLength() {
        for (text in listOf("a[微笑]", "a[doge]", "a:smile:", "a/:smile")) {
            val connection = Fixture(text)
            AndroidEditorConnection { connection }.deleteBeforeCursor()
            assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), connection.keys)
            assertTrue(connection.deletes.isEmpty())
        }
    }

    @Test fun rejectedHostGestureFallsBackToOnlyOneCharacterAndExceptionsNeverReplay() {
        val rejected = Fixture("a[文字]", false)
        AndroidEditorConnection { rejected }.deleteBeforeCursor()
        assertEquals(listOf(1), rejected.deletes)
        val uncertain = Fixture("a[文字]", throws = true)
        AndroidEditorConnection { uncertain }.deleteBeforeCursor()
        assertTrue(uncertain.deletes.isEmpty())
        assertEquals(1, uncertain.keys.size)
    }

    private class Fixture(private val text: String, private val accepted: Boolean = true,
        private val throws: Boolean = false) : InputConnectionWrapper(null, false) {
        val keys = mutableListOf<Int>()
        val deletes = mutableListOf<Int>()
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = text.takeLast(n)
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            keys += event.action
            if (throws) throw IllegalStateException()
            return accepted
        }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            deletes += beforeLength
            return true
        }
    }
}
