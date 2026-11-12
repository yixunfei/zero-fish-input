package dev.zeroinput.ime.input

import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.ui.DraftCaretUi
import dev.zeroinput.ime.ui.KeyboardAction
import org.junit.Assert.*
import org.junit.Test

class LocalDraftCaretTest {
    @Test fun pairsAndMiddleDeletionPreserveTheSuffixAndRejectStaleClicks() = onMain {
        withDraft { draft, cursor ->
            draft.start(InputLanguage.ENGLISH)
            draft.handle(KeyboardAction.PairedText("(", ")"))
            assertEquals(1, cursor().offset)
            draft.handle(KeyboardAction.LiteralText("a🙂"))
            assertEquals("(a🙂)", draft.committedText())
            draft.handle(KeyboardAction.Backspace)
            assertEquals("(a)", draft.committedText())
            val stale = cursor()
            draft.handle(KeyboardAction.MoveDraftCaret(0, stale.revision))
            draft.handle(KeyboardAction.LiteralText("X"))
            draft.handle(KeyboardAction.MoveDraftCaret(4, stale.revision))
            draft.handle(KeyboardAction.LiteralText("Y"))
            assertEquals("XY(a)", draft.committedText())
            draft.close()
            draft.start(InputLanguage.ENGLISH)
            draft.handle(KeyboardAction.MoveDraftCaret(1, stale.revision))
            assertEquals(0, cursor().offset)
        }
    }

    @Test fun clickCompletesCompositionBeforeMovingWithoutLosingConvertedText() = onMain {
        withDraft { draft, cursor ->
            draft.start(InputLanguage.CHINESE)
            draft.appendImported("tail")
            draft.handle(KeyboardAction.MoveDraftCaret(0, cursor().revision))
            "nihao".forEach { draft.handle(KeyboardAction.Text(it.toString())) }
            draft.handle(KeyboardAction.MoveDraftCaret(4, cursor().revision))
            assertEquals("你好tail", draft.committedText())
            draft.handle(KeyboardAction.LiteralText("!"))
            assertEquals("你好tail!", draft.committedText())
        }
    }

    @Test fun hiddenPrefixCannotBePositionedUntilExplicitRevealAndOldProjectionIsRevoked() = onMain {
        withDraft(masked = true) { draft, cursor ->
            draft.start(InputLanguage.ENGLISH)
            draft.appendImported("abcdefg")
            val masked = cursor()
            draft.handle(KeyboardAction.MoveDraftCaret(0, masked.revision))
            assertEquals(7, cursor().offset)
            draft.togglePrivateDraftVisibility()
            draft.handle(KeyboardAction.MoveDraftCaret(2, cursor().revision))
            draft.handle(KeyboardAction.LiteralText("X"))
            assertEquals("abXcdefg", draft.committedText())
            draft.togglePrivateDraftVisibility()
            assertEquals(-1, cursor().offset)
            draft.handle(KeyboardAction.MoveDraftCaret(7, masked.revision))
            draft.handle(KeyboardAction.Backspace)
            assertEquals("abcdefg", draft.committedText())
        }
    }

    @Test fun middleInsertionHonorsCapacityAndCaretSnapsOutsideSurrogatePairs() = onMain {
        withDraft(maxChars = 4) { draft, cursor ->
            draft.start(InputLanguage.ENGLISH)
            draft.appendImported("a🙂b")
            draft.handle(KeyboardAction.MoveDraftCaret(2, cursor().revision))
            assertEquals(1, cursor().offset)
            draft.handle(KeyboardAction.LiteralText("X"))
            assertEquals("a🙂b", draft.committedText())
            assertEquals(1, cursor().offset)
            draft.handle(KeyboardAction.Backspace)
            assertEquals("🙂b", draft.committedText())
            draft.handle(KeyboardAction.LiteralText("X"))
            assertEquals("X🙂b", draft.committedText())
        }
    }

    private fun withDraft(masked: Boolean = false, maxChars: Int = 64,
                          action: (LocalDraftInput, () -> DraftCaretUi) -> Unit) {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication).graph
        var cursor = DraftCaretUi(0, 0)
        val draft = LocalDraftInput({ graph }, { false }, {}, { _, _, _ -> }, maxChars,
            maskCommitted = masked, renderCaret = { cursor = it })
        try { action(draft) { cursor } } finally { draft.close() }
    }

    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
