package dev.zeroinput.ime.input

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.zeroinput.engine.api.*
import dev.zeroinput.engine.english.EnglishInputEngine
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.core.*
import dev.zeroinput.ime.core.privacy.PrivacyConfiguration
import dev.zeroinput.ime.ui.KeyboardAction
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RejectedCommitRecoveryTest {
    private val noStore = object : PersonalizationStore {
        override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int) = emptyList<PersonalSuggestion>()
        override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) = Unit
        override fun recordUse(id: String, learningAllowed: Boolean) = Unit
    }

    @Test fun retryUsesTheSavedPartialUpdateOnceAndThenResumesTheAdvancedEngine() {
        val editor = RejectingEditor()
        val engine = object : InputEngine {
            override val descriptor = EnglishInputEngine.Descriptor
            override var snapshot = EngineSnapshot.Empty
            var selections = 0
            override fun start(context: EditorContext) = reset()
            override fun reset(): EngineSnapshot { snapshot = EngineSnapshot.Empty; return snapshot }
            override fun handle(key: EngineKey): EngineUpdate {
                snapshot = EngineSnapshot("nihao", "ni hao", listOf(Candidate("first", "你好")))
                return EngineUpdate(snapshot)
            }
            override fun selectCandidate(index: Int): EngineUpdate {
                selections++
                snapshot = EngineSnapshot("hao", "hao", listOf(Candidate("rest", "好")))
                return EngineUpdate(snapshot, committedText = "你", committedInput = "ni")
            }
            override fun changePage(direction: PageDirection) = error("Frozen engines cannot page")
            override fun close() = Unit
        }
        val controller = InputSessionController(editor, { engine }, noStore)
        try {
            controller.start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, InputLanguage.CHINESE, PrivacyConfiguration())
            controller.handle(InputCommand.Text("nihao"))
            controller.handle(InputCommand.SelectCandidate(0))
            controller.handle(InputCommand.ChangeCandidatePage(PageDirection.NEXT))
            controller.handle(InputCommand.Text("x"))
            controller.refreshPersonalization()
            assertTrue(controller.modelCandidates().isEmpty())
            assertEquals("ni hao", editor.preedit)
            assertEquals(1, engine.selections)
            assertEquals(listOf("你"), editor.attempts)
            editor.accept = true
            controller.handle(InputCommand.Enter)
            assertEquals(1, engine.selections)
            assertEquals("你", editor.committed)
            assertEquals("hao", editor.preedit)
            assertEquals(engine.snapshot, controller.state.snapshot)
            assertNull(controller.state.commitFailure)
        } finally { controller.close() }
    }

    @Test fun privacyTighteningAndResetRevokeThePendingCommitWithoutReplayingIt() {
        for (tighten in listOf(true, false)) {
            val editor = RejectingEditor()
            val controller = InputSessionController(editor, { EnglishInputEngine() }, noStore)
            try {
                controller.start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, InputLanguage.ENGLISH, PrivacyConfiguration())
                controller.handle(InputCommand.Text("x"))
                controller.handle(InputCommand.Space)
                val retryId = controller.state.snapshot.candidates.single().id
                if (tighten) controller.updatePrivacy(PrivacyConfiguration(incognitoMode = true)) else controller.reset()
                editor.accept = true
                controller.handle(InputCommand.SelectCandidate(0, retryId))
                assertEquals(listOf("x "), editor.attempts)
                assertNull(controller.state.commitFailure)
                assertFalse(controller.state.snapshot.isComposing)
            } finally { controller.close() }
        }
    }

    @Test fun oldRetryClicksCannotCommitANewerPendingResult() {
        val editor = RejectingEditor()
        val controller = InputSessionController(editor, { EnglishInputEngine() }, noStore)
        try {
            controller.start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, InputLanguage.ENGLISH, PrivacyConfiguration())
            controller.handle(InputCommand.Text("x"))
            controller.handle(InputCommand.Space)
            val oldId = controller.state.snapshot.candidates.single().id
            controller.handle(InputCommand.SelectCandidate(0, oldId))
            val currentId = controller.state.snapshot.candidates.single().id
            assertNotEquals(oldId, currentId)
            editor.accept = true
            controller.handle(InputCommand.SelectCandidate(0, oldId))
            assertEquals(2, editor.attempts.size)
            controller.handle(InputCommand.SelectCandidate(0, currentId))
            assertEquals("x ", editor.committed)
            assertEquals(3, editor.attempts.size)
        } finally { controller.close() }
    }

    @Test fun uncertainPlatformCommitIsNeverReplayedAndBackspaceDoesNotDeleteAcceptedText() {
        val application = RuntimeEnvironment.getApplication()
        var attempts = 0
        var body = ""
        val platform = object : android.view.inputmethod.BaseInputConnection(android.view.View(application), false) {
            override fun setComposingText(text: CharSequence, newCursorPosition: Int) = true
            override fun finishComposingText() = true
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                attempts++
                body += text
                throw IllegalStateException("Public uncertain edit fixture")
            }
        }
        val adapter = AndroidEditorConnection(current = { platform })
        val controller = InputSessionController(adapter, { EnglishInputEngine() }, noStore)
        try {
            controller.start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, InputLanguage.ENGLISH, PrivacyConfiguration())
            controller.handle(InputCommand.Text("x"))
            controller.handle(InputCommand.Space)
            assertEquals(CommitTextResult.UNKNOWN, controller.state.commitFailure)
            listOf(InputCommand.Space, InputCommand.Enter, InputCommand.SelectCandidate(0), InputCommand.Text("y"))
                .forEach(controller::handle)
            assertEquals(1, attempts)
            assertEquals("x ", body)
            controller.handle(InputCommand.Backspace)
            assertEquals("x ", body)
            assertNull(controller.state.commitFailure)
        } finally { controller.close() }
    }

    @Test fun rejectedCandidateAndSpaceWaitForExplicitRetryWithoutLearningOrOverwritingPreedit() {
        for (command in listOf(InputCommand.SelectCandidate(0), InputCommand.Space)) {
            val editor = RejectingEditor()
            var learned = 0
            val store = object : PersonalizationStore {
                override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int) = emptyList<PersonalSuggestion>()
                override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) { learned++ }
                override fun recordUse(id: String, learningAllowed: Boolean) = Unit
            }
            val controller = InputSessionController(editor, { EnglishInputEngine(listOf("hello")) }, store,
                learnFromCommittedText = { true })
            try {
                controller.start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT },
                    InputLanguage.ENGLISH, PrivacyConfiguration())
                "hel".forEach { controller.handle(InputCommand.Text(it.toString())) }
                controller.handle(command)
                assertTrue(controller.state.snapshot.isComposing)
                assertEquals("hel", editor.preedit)
                assertEquals(0, learned)
                editor.accept = true
                controller.handle(InputCommand.Text("x"))
                assertEquals(listOf("hel "), editor.attempts)
                assertEquals("hel", editor.preedit)
                controller.handle(command)
                assertEquals(listOf("hel ", "hel "), editor.attempts)
                assertEquals("hel ", editor.committed)
                assertFalse(controller.state.snapshot.isComposing)
                assertEquals(1, learned)
                controller.handle(InputCommand.Text("x"))
                assertEquals("x", editor.preedit)
            } finally { controller.close() }
        }
    }

    @Test fun fullDraftKeepsRefusingSubmissionUntilTheRejectedCompositionIsCancelled() {
        val application = RuntimeEnvironment.getApplication() as ZeroInputApplication
        var last = InputSessionState()
        var caret = dev.zeroinput.ime.ui.DraftCaretUi(0, 0)
        var limits = 0
        val draft = LocalDraftInput({ application.graph }, { false }, {}, { _, _, state -> last = state }, maxChars = 3,
            onLengthLimit = { limits++ }, renderCaret = { caret = it })
        try {
            draft.start(InputLanguage.ENGLISH)
            draft.replaceCommittedText("ABC")
            draft.handle(KeyboardAction.Text("x"))
            draft.command(InputCommand.SelectCandidate(0))
            assertTrue(last.snapshot.isComposing)
            assertEquals(1, limits)
            draft.handle(KeyboardAction.MoveDraftCaret(0, caret.revision))
            assertEquals(1, limits)
            repeat(2) { assertNull(draft.submittedChars()) }
            assertEquals(LocalDraftInput.SubmissionBlockReason.LENGTH_LIMIT, draft.submissionBlockReason)
            assertEquals("", draft.submittedText())
            draft.handle(KeyboardAction.Backspace)
            assertFalse(last.snapshot.isComposing)
            assertNull(draft.submissionBlockReason)
            val acceptedPrefix = requireNotNull(draft.submittedChars())
            try { assertEquals("ABC", String(acceptedPrefix)) } finally { acceptedPrefix.fill('\u0000') }
            draft.handle(KeyboardAction.Backspace)
            draft.handle(KeyboardAction.Backspace)
            draft.handle(KeyboardAction.Text("x"))
            draft.command(InputCommand.SelectCandidate(0))
            val submitted = requireNotNull(draft.submittedChars())
            try { assertEquals("Ax ", String(submitted)) } finally { submitted.fill('\u0000') }
        } finally { draft.close() }
    }

    @Test fun exactUtf8BudgetCanSubmitWithoutDeletingAcceptedTextAfterCancellingRejectedInput() {
        val application = RuntimeEnvironment.getApplication() as ZeroInputApplication
        val draft = LocalDraftInput({ application.graph }, { false }, {}, { _, _, _ -> },
            maxChars = 2048, maxUtf8Bytes = 2048, emitCommittedText = false, preserveTextOnRejectedBackspace = true)
        try {
            draft.start(InputLanguage.ENGLISH)
            val accepted = "汉".repeat(682) + "ab"
            assertTrue(draft.replaceCommittedText(accepted))
            draft.handle(KeyboardAction.LiteralText("汉"))
            repeat(2) { assertNull(draft.submittedChars()) }
            assertEquals(LocalDraftInput.SubmissionBlockReason.LENGTH_LIMIT, draft.submissionBlockReason)
            draft.handle(KeyboardAction.Backspace)
            val chars = requireNotNull(draft.submittedChars())
            try { assertEquals(accepted, String(chars)) } finally { chars.fill('\u0000') }
            assertNull(draft.submissionBlockReason)
        } finally { draft.close() }
    }

    @Test fun directLiteralPairedAndNewlineRejectionsConsumeOnlyTheFirstBackspace() {
        val application = RuntimeEnvironment.getApplication() as ZeroInputApplication
        for (action in listOf(KeyboardAction.LiteralText("x"), KeyboardAction.PairedText("(", ")"), KeyboardAction.Enter)) {
            val draft = LocalDraftInput({ application.graph }, { false }, {}, { _, _, _ -> }, maxChars = 3,
                preserveTextOnRejectedBackspace = true)
            try {
                draft.start(InputLanguage.ENGLISH)
                assertTrue(draft.replaceCommittedText("ABC"))
                draft.handle(action)
                assertNull(draft.submittedChars())
                draft.command(InputCommand.Backspace)
                assertEquals("ABC", draft.submittedText())
                draft.command(InputCommand.Backspace)
                assertEquals("AB", draft.submittedText())
            } finally { draft.close() }
        }
    }

    @Test fun ordinaryDraftKeepsItsExistingBackspaceDeletionAfterLiteralRejection() {
        val application = RuntimeEnvironment.getApplication() as ZeroInputApplication
        val draft = LocalDraftInput({ application.graph }, { false }, {}, { _, _, _ -> }, maxChars = 3)
        try {
            draft.start(InputLanguage.ENGLISH)
            assertTrue(draft.replaceCommittedText("ABC"))
            draft.handle(KeyboardAction.LiteralText("x"))
            assertNull(draft.submittedChars())
            draft.handle(KeyboardAction.Backspace)
            assertEquals("AB", draft.submittedText())
        } finally { draft.close() }
    }

    private class RejectingEditor : EditorConnection {
        var accept = false
        var preedit = ""
        var committed = ""
        val attempts = mutableListOf<String>()
        override fun setComposingText(text: String) { preedit = text }
        override fun finishComposingText() = Unit
        override fun commitText(text: String): Boolean {
            attempts += text
            if (!accept) return false
            preedit = ""
            committed += text
            return true
        }
        override fun deleteBeforeCursor() = Unit
        override fun performEditorAction(actionId: Int) = false
        override fun sendEnterKey() = Unit
    }
}
