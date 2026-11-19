package dev.zeroinput.ime.core

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.zeroinput.engine.api.*
import dev.zeroinput.ime.core.privacy.PrivacyConfiguration
import dev.zeroinput.ime.core.privacy.PrivacyReason
import dev.zeroinput.ime.core.privacy.SessionPrivacy
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class EngineLinkageLifecycleTest {
    @Test fun enterResetLinkageFailureDoesNotRepeatAnAcceptedCommitOrSendEnter() {
        val engine = Engine()
        val editor = Connection()
        controller(engine, editor).use { input ->
            input.handle(InputCommand.Text("ni"))
            engine.resetFailure = UnsatisfiedLinkError()
            input.handle(InputCommand.Enter)
            assertEquals(listOf("ni"), editor.commits)
            assertEquals(0, editor.enters)
            assertFalse(input.state.snapshot.isComposing)
            assertEquals(1, engine.closes)
            input.handle(InputCommand.Text("x"))
            assertEquals(listOf("ni", "x"), editor.commits)
        }
    }

    @Test fun rawSpaceAndPersonalCandidateResetFailuresNeverReplayAcceptedText() {
        for (unproven in listOf(true, false)) {
            val engine = Engine()
            val editor = Connection()
            val page = if (unproven) PersonalSuggestionPage(ready = false, editsUnproven = true)
                else PersonalSuggestionPage(listOf(PersonalSuggestion("public-id", "公开", 99)))
            controller(engine, editor, page).use { input ->
                input.handle(InputCommand.Text("ni"))
                engine.resetFailure = UnsatisfiedLinkError()
                input.handle(InputCommand.Space)
                assertEquals(if (unproven) listOf("ni", " ") else listOf("公开"), editor.commits)
                assertFalse(input.state.snapshot.isComposing)
                assertEquals(1, engine.closes)
            }
        }
    }

    @Test fun sessionResetAndPrivacyTighteningStillClearOldCompositionWhenNativeResetIsUnavailable() {
        for (tighten in listOf(true, false)) {
            val engine = Engine()
            val editor = Connection()
            controller(engine, editor).use { input ->
                input.handle(InputCommand.Text("ni"))
                engine.resetFailure = UnsatisfiedLinkError()
                if (tighten) input.updatePrivacy(PrivacyConfiguration(incognitoMode = true)) else input.reset()
                assertTrue(editor.commits.isEmpty())
                assertFalse(input.state.snapshot.isComposing)
                assertTrue(editor.finishes > 0)
                assertTrue(engine.closes >= 1)
            }
        }
    }

    @Test fun startupLinkageFailureLeavesDirectInputAvailable() {
        val engine = Engine().apply { startFailure = UnsatisfiedLinkError() }
        val editor = Connection()
        controller(engine, editor).use { input ->
            input.handle(InputCommand.Text("x"))
            assertEquals(listOf("x"), editor.commits)
            assertEquals(1, engine.closes)
        }
    }

    @Test fun preparedCleanupToleratesLinkageFailureAndRemainsOneUse() {
        val engine = Engine().apply { closeFailure = UnsatisfiedLinkError() }
        val prepared = PreparedInputEngine(InputLanguage.CHINESE, null, null,
            SessionPrivacy(false, true, false, PrivacyReason.USER_DISABLED), EngineSnapshot.Empty, engine)
        prepared.close()
        prepared.close()
        assertEquals(1, engine.closes)
        assertNull(prepared.takeEngine())
    }

    @Test fun nativeResetCancellationInterruptionAndOtherFatalErrorsStillPropagate() {
        for (failure in listOf(CancellationException(), InterruptedException(), AssertionError())) {
            val engine = Engine()
            val editor = Connection()
            controller(engine, editor).use { input ->
                input.handle(InputCommand.Text("ni"))
                engine.resetFailure = failure
                try {
                    assertSame(failure, assertThrows(failure.javaClass) { input.reset() })
                    assertTrue(editor.commits.isEmpty())
                    assertEquals(failure is InterruptedException, Thread.currentThread().isInterrupted)
                } finally { Thread.interrupted(); engine.resetFailure = null }
            }
        }
    }

    private fun controller(engine: Engine, editor: Connection, page: PersonalSuggestionPage = PersonalSuggestionPage()) =
        InputSessionController(editor, { engine }, object : PagedPersonalizationStore {
            override fun suggestionPage(prefix: String, language: InputLanguage, offset: Int, limit: Int) = page
            override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int) = page.items
            override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) = Unit
            override fun recordUse(id: String, learningAllowed: Boolean) = Unit
        }).apply { start(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, InputLanguage.CHINESE, PrivacyConfiguration()) }

    private class Engine : InputEngine {
        var resetFailure: Throwable? = null
        var startFailure: Throwable? = null
        var closeFailure: Throwable? = null
        var closes = 0
        override val descriptor = EngineDescriptor("public-engine", "Fixture", "1", setOf(InputLanguage.CHINESE))
        override var snapshot = EngineSnapshot.Empty
        override fun start(context: EditorContext): EngineSnapshot {
            startFailure?.let { throw it }; snapshot = EngineSnapshot.Empty; return snapshot
        }
        override fun reset(): EngineSnapshot { resetFailure?.let { throw it }; snapshot = EngineSnapshot.Empty; return snapshot }
        override fun close() { closes++; closeFailure?.let { throw it } }
        override fun handle(key: EngineKey): EngineUpdate {
            if (key is EngineKey.Character) snapshot = EngineSnapshot(key.text, key.text)
            return EngineUpdate(snapshot, consumed = key is EngineKey.Character)
        }
        override fun selectCandidate(index: Int) = EngineUpdate(snapshot, consumed = false)
        override fun changePage(direction: PageDirection) = EngineUpdate(snapshot)
    }

    private class Connection : EditorConnection {
        val commits = mutableListOf<String>()
        var enters = 0
        var finishes = 0
        override fun setComposingText(text: String) = Unit
        override fun finishComposingText() { finishes++ }
        override fun commitText(text: String): Boolean { commits += text; return true }
        override fun deleteBeforeCursor() = Unit
        override fun performEditorAction(actionId: Int) = false
        override fun sendEnterKey() { enters++ }
    }
}
