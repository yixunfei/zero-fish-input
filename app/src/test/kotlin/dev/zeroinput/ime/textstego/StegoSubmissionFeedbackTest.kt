package dev.zeroinput.ime.textstego

import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import dev.zeroinput.engine.api.*
import dev.zeroinput.engine.english.EnglishInputEngine
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.input.LocalDraftInput
import dev.zeroinput.ime.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StegoSubmissionFeedbackTest {
    @Test fun nonfinishingCompositionReportsUnfinishedInputForEncryptionAndDraftSave() = withWorkspace { workspace, draft, notices ->
        val controller = field(draft, "controller")
        val engine = controller.javaClass.getDeclaredField("engine").apply { isAccessible = true }
        (engine.get(controller) as InputEngine).close()
        engine.set(controller, StalledEngine())
        draft.handle(KeyboardAction.Text("x"))
        assertNull(draft.submittedChars())
        assertEquals(LocalDraftInput.SubmissionBlockReason.UNFINISHED_COMPOSITION, draft.submissionBlockReason)
        for (action in listOf(StegoAction.PREPARE, StegoAction.SAVE_FIELD)) {
            notices.clear()
            workspaceAction(workspace, action)
            assertEquals(listOf(R.string.stego_composition_unfinished), notices)
            assertTrue(workspace.current())
        }
    }

    @Test fun budgetRejectionKeepsItsOwnNoticeAndRequiresExplicitCancellation() = withWorkspace { workspace, draft, notices ->
        assertTrue(draft.replaceCommittedText("A".repeat(512)))
        draft.handle(KeyboardAction.LiteralText("x"))
        notices.clear()
        workspaceAction(workspace, StegoAction.SAVE_FIELD)
        assertEquals(listOf(R.string.stego_length_limit), notices)
        draft.handle(KeyboardAction.Backspace)
        val chars = requireNotNull(draft.submittedChars())
        try { assertEquals("A".repeat(512), String(chars)) } finally { chars.fill('\u0000') }
    }

    private fun withWorkspace(check: (StegoWorkspace, LocalDraftInput, MutableList<Int>) -> Unit) {
        val app = RuntimeEnvironment.getApplication() as ZeroInputApplication
        val view = ZeroInputView(ContextThemeWrapper(app, R.style.Theme_ZeroInput))
        val notices = mutableListOf<Int>()
        view.openTextStego()
        val workspace = StegoWorkspace({ app.graph }, Handler(Looper.getMainLooper()), view,
            { true }, { null }, { notices.add(it) }, {}, {}, false)
        try {
            workspace.open(InputLanguage.ENGLISH, ChineseInputOptions())
            @Suppress("UNCHECKED_CAST")
            val drafts = field(workspace, "drafts") as Map<StegoField, LocalDraftInput>
            check(workspace, drafts.getValue(StegoField.COVER), notices)
        } finally { workspace.close(); view.release() }
    }

    private fun workspaceAction(workspace: StegoWorkspace, action: StegoAction) {
        (field(workspace, "view") as ZeroInputView).textStegoPanel.onAction(action)
    }

    private fun field(owner: Any, name: String): Any = requireNotNull(owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner))

    /** A provider that cannot finish its preedit, without any budget rejection. */
    private class StalledEngine : InputEngine {
        override val descriptor = EnglishInputEngine.Descriptor
        override var snapshot = EngineSnapshot.Empty
        override fun start(context: EditorContext) = reset()
        override fun reset(): EngineSnapshot { snapshot = EngineSnapshot.Empty; return snapshot }
        override fun handle(key: EngineKey): EngineUpdate {
            snapshot = EngineSnapshot("x", "x", listOf(Candidate("fixture", "x")))
            return EngineUpdate(snapshot)
        }
        override fun selectCandidate(index: Int): EngineUpdate {
            val next = if (snapshot.rawInput == "x") "y" else "x"
            snapshot = EngineSnapshot(next, next, listOf(Candidate("fixture", next)))
            return EngineUpdate(snapshot)
        }
        override fun changePage(direction: PageDirection) = EngineUpdate(snapshot)
        override fun close() = Unit
    }
}
