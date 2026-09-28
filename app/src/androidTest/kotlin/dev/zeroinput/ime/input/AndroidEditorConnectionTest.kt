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
