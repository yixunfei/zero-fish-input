package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiLimits
import org.junit.Assert.*
import org.junit.Test

class AiConversationNameEditorTest {
    @Test fun rejectedTitleImportRestoresQuestionAndDoesNotEnterRenameMode() {
        val f = Fixture(maxChars = 12)
        f.editor.start("saved", "x".repeat(13))
        assertFalse(f.editor.active)
        assertFalse(f.renaming)
        assertEquals("question", f.text)
        assertEquals(1, f.stops)
        assertTrue(f.renames.isEmpty())
    }

    @Test fun invalidTitleExitsRenameModeAndRestoresQuestionBeforeReportingRejection() {
        for (invalid in listOf("   ", "x".repeat(AiLimits.MAX_TITLE_CHARS + 1), "bad\u0000title")) {
            val f = Fixture()
            f.editor.start("saved", "old")
            f.text = invalid
            f.editor.save()
            assertFalse(f.editor.active)
            assertFalse(f.renaming)
            assertEquals("question", f.text)
            assertEquals(listOf("question"), f.rejectedDrafts)
            assertTrue(f.renames.isEmpty())
            f.editor.save()
            assertEquals(1, f.rejectedDrafts.size)
        }
    }

    @Test fun validTitleIsTrimmedAndQuestionIsRestoredAfterRename() {
        val f = Fixture()
        f.editor.start("saved", "old")
        assertTrue(f.editor.active)
        f.text = "  new title  "
        f.editor.save()
        assertEquals(listOf("saved" to "new title"), f.renames)
        assertFalse(f.editor.active)
        assertEquals("question", f.text)
    }

    @Test fun restartingRenamePreservesOriginalQuestionAndCancelDoesNotRename() {
        val f = Fixture()
        f.editor.start("first", "first title")
        f.text = "edited title"
        f.editor.start("second", "second title")
        assertEquals("second title", f.text)
        f.editor.cancel()
        assertEquals("question", f.text)
        assertFalse(f.editor.active)
        assertTrue(f.renames.isEmpty())
    }

    private class Fixture(maxChars: Int = AiLimits.MAX_INPUT_CHARS) {
        var text = "question"
        var renaming = false
        var stops = 0
        val renames = mutableListOf<Pair<String, String>>()
        val rejectedDrafts = mutableListOf<String>()
        val editor = AiConversationNameEditor(
            draftText = { text }, clearDraft = { text = "" },
            appendToDraft = { value ->
                if (text.length + value.length > maxChars) false else { text += value; true }
            },
            stopRequest = { stops++ }, renameConversation = { id, title -> renames += id to title },
            render = { renaming = it }, rejected = { rejectedDrafts += text },
        )
    }
}
