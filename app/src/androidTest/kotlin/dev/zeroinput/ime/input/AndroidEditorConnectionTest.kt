package dev.zeroinput.ime.input

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidEditorConnectionTest {
    @Test fun backspaceDeletesWholeEmojiSequencesAndCombiningCharacters() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = java.util.concurrent.CountDownLatch(1)
        val preparation = dev.zeroinput.ime.ui.EmojiCatalog.prepare(instrumentation.targetContext) { if (it) ready.countDown() }
        try {
            org.junit.Assert.assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
            onMain {
                for (sequence in listOf("👨‍👩‍👧‍👦", "👩🏿‍🚀", "🇨🇳", "1️⃣", "❤️", "🫱🏻‍🫲🏿", "e\u0301")) {
                    val editor = editor("a${sequence}z")
                    val cursor = 1 + sequence.length
                    editor.setSelection(cursor)
                    val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
                    AndroidEditorConnection(cursor, cursor) { connection }.deleteBeforeCursor()
                    assertEquals("az", editor.text.toString())
                }
            }
        } finally { preparation.close() }
    }

    @Test fun backspaceDeletesOnlySelectedTextInEitherSelectionDirection() = onMain {
        for ((selectionStart, selectionEnd) in listOf(1 to 5, 5 to 1)) {
            val editor = editor("a选中🙂z")
            editor.setSelection(selectionStart, selectionEnd)
            val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
            val adapter = AndroidEditorConnection(selectionStart, selectionEnd) { connection }

            adapter.deleteBeforeCursor()

            assertEquals("az", editor.text.toString())
            assertEquals(1, editor.selectionStart)
            assertEquals(1, editor.selectionEnd)
        }
    }

    @Test fun backspaceDeletesOneCodePointAtCollapsedCursor() = onMain {
        val editor = editor("a🙂z")
        editor.setSelection(3)
        val connection = checkNotNull(editor.onCreateInputConnection(EditorInfo()))
        val adapter = AndroidEditorConnection(3, 3) { connection }

        adapter.deleteBeforeCursor()

        assertEquals("az", editor.text.toString())
        assertEquals(1, editor.selectionStart)
    }

    private fun editor(text: String) = EditText(InstrumentationRegistry.getInstrumentation().targetContext).apply {
        inputType = InputType.TYPE_CLASS_TEXT
        setText(text)
    }

    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
