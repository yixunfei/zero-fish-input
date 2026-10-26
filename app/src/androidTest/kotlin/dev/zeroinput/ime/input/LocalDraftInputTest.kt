package dev.zeroinput.ime.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.core.InputSessionState
import dev.zeroinput.ime.ui.KeyboardAction
import dev.zeroinput.userdata.UserTerm
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalDraftInputTest {
    @Test fun layoutSpecificHandoffKeepsConvertiblePreeditWithoutPersonalization() = onMain {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication).graph
        for ((options, code) in listOf(
            dev.zeroinput.engine.api.ChineseInputOptions(keyboardLayout = dev.zeroinput.engine.api.ChineseKeyboardLayout.NINE_KEY) to "64",
            dev.zeroinput.engine.api.ChineseInputOptions(doublePinyinScheme = dev.zeroinput.engine.api.DoublePinyinScheme.MICROSOFT) to "n;",
        )) {
            var state = InputSessionState()
            var committed = ""
            val draft = LocalDraftInput({ graph }, { false }, {}, { text, _, next -> committed = text; state = next }, maxChars = 64)
            try {
                draft.start(InputLanguage.CHINESE, options)
                assertTrue(draft.appendPreedit(code))
                assertEquals("", committed)
                assertEquals(code, state.snapshot.rawInput)
                assertTrue(state.snapshot.isComposing)
                assertFalse(state.privacy.personalizationAllowed)
                assertFalse(state.privacy.learningAllowed)
            } finally { draft.close() }
        }
    }

    @Test fun preparedNativeEngineRestoresTheDraftWhilePinyinIsStillComposing() {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication).graph
        val deadline = SystemClock.uptimeMillis() + 60_000
        while (!graph.rime.runtime.isReady && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue("Real Rime must be ready for this regression", graph.rime.runtime.isReady)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = graph.engineExecutor.submit { entered.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val handler = Handler(Looper.getMainLooper())
        var state = InputSessionState()
        val draft = LocalDraftInput({ graph }, handler::post, handler::removeCallbacks, { _, _, next -> state = next }, maxChars = 64)
        try {
            onMain {
                draft.start(InputLanguage.CHINESE)
                typePinyin(draft, "zhongguoren")
                assertTrue(state.engineDescriptor?.isFallback == true)
            }
            release.countDown()
            graph.engineExecutor.submit {}.get(20, TimeUnit.SECONDS)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            // READY can post another preparation after the worker barrier. Wait
            // for adoption, not merely for the first worker task to complete.
            awaitOnMain { state.engineDescriptor?.id == graph.rime.descriptor.id }
            onMain {
                assertTrue(state.snapshot.isComposing)
                assertEquals(graph.rime.descriptor.id, state.engineDescriptor?.id)
                assertEquals("zhongguoren", state.snapshot.rawInput)
                assertEquals("中国人", state.snapshot.candidates.first().text)
                val composition = state.snapshot.composition
                repeat(3) {
                    draft.command(dev.zeroinput.ime.core.InputCommand.ChangeCandidatePage(dev.zeroinput.engine.api.PageDirection.NEXT))
                    assertEquals("Browsing must keep the active composition", composition, state.snapshot.composition)
                    assertTrue("Browsing must retain the first candidate", state.snapshot.candidates.any { it.text == "中国人" })
                }
                draft.handle(KeyboardAction.Space)
                assertEquals("中国人", draft.submittedText())
            }
            onMain {
                assertEquals(graph.rime.descriptor.id, state.engineDescriptor?.id)
                typePinyin(draft, "zhongguoren")
                assertEquals("中国人中国人", draft.submittedText())
                assertFalse(state.snapshot.isComposing)
                assertFalse(state.privacy.learningAllowed)
                assertFalse(state.privacy.personalizationAllowed)
            }
        } finally { release.countDown(); onMain { draft.close() }; blocker.get(10, TimeUnit.SECONDS) }
    }

    @Test fun sendingChineseDraftConvertsCompositionAndPreservesEverySegment() = onMain {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication).graph
        var state = InputSessionState()
        val draft = LocalDraftInput({ graph }, { false }, {}, { _, _, next -> state = next }, maxChars = 64)
        try {
            draft.start(InputLanguage.CHINESE)
            typePinyin(draft, "nihao")
            assertTrue(state.snapshot.isComposing)
            val expected = state.snapshot.candidates.first().text
            assertEquals(expected, draft.submittedText())
            typePinyin(draft, "zhongguo")
            assertEquals(expected + "中国", draft.submittedText())
            assertFalse(state.snapshot.isComposing)
            assertFalse(state.privacy.personalizationAllowed)
        } finally { draft.close() }
    }

    @Test fun partialSelectionRejectsLossyHandoffAndRetriesAfterClearingComposition() {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication).graph
        val handler = Handler(Looper.getMainLooper())
        var state = InputSessionState()
        val draft = LocalDraftInput({ graph }, handler::post, handler::removeCallbacks, { _, _, next -> state = next }, maxChars = 64)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocker = graph.engineExecutor.submit { entered.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
        try {
            assertTrue(entered.await(60, TimeUnit.SECONDS))
            assertTrue(graph.rime.runtime.isReady)
            onMain {
                draft.start(InputLanguage.CHINESE)
                typePinyin(draft, "zhongguoren")
                draft.command(dev.zeroinput.ime.core.InputCommand.SelectCandidate(0))
                assertTrue(state.snapshot.canUndoSelection)
            }
            release.countDown()
            graph.engineExecutor.submit {}.get(20, TimeUnit.SECONDS)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            onMain {
                assertTrue(state.engineDescriptor?.isFallback == true)
                assertTrue(state.snapshot.canUndoSelection)
                assertTrue(draft.clearComposition())
            }
            awaitOnMain { state.engineDescriptor?.id == graph.rime.descriptor.id }
            onMain {
                assertEquals("", draft.submittedText())
                typePinyin(draft, "zhongguoren")
                assertEquals("中国人", draft.submittedText())
                assertFalse(state.privacy.personalizationAllowed)
            }
        } finally { release.countDown(); onMain { draft.close() }; blocker.get(20, TimeUnit.SECONDS) }
    }

    @Test fun queuedNativeResultCannotReviveAClosedOrReplacedDraft() {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication).graph
        val callbacks = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        var state = InputSessionState()
        var rendered = ""
        val draft = LocalDraftInput({ graph }, { callbacks.add(it); true }, { callbacks.remove(it) },
            { text, _, next -> rendered = text; state = next }, maxChars = 64)
        try {
            onMain { draft.start(InputLanguage.CHINESE); typePinyin(draft, "zhongguoren") }
            graph.engineExecutor.submit {}.get(60, TimeUnit.SECONDS)
            val stale = callbacks.toList()
            assertTrue(stale.isNotEmpty())
            onMain {
                draft.close()
                draft.start(InputLanguage.ENGLISH)
                draft.handle(KeyboardAction.LiteralText("public draft"))
                stale.forEach(Runnable::run)
                assertEquals(InputLanguage.ENGLISH, state.language)
                assertEquals("public draft", rendered)
                assertFalse(state.privacy.personalizationAllowed)
            }
        } finally { onMain { draft.close() } }
    }

    @Test fun boundedDraftDisablesPersonalizationAndClearsOnCloseOrRestart() = onMain {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication).graph
        val queued = mutableListOf<Runnable>()
        var value = ""
        var renders = 0
        var state = InputSessionState()
        val draft = LocalDraftInput({ graph }, { queued += it; true }, { queued.remove(it) }, { text, _, next ->
            value = text; state = next; renders++
        }, maxChars = 4, multiline = false)
        try {
            draft.start(InputLanguage.ENGLISH)
            assertFalse(state.privacy.learningAllowed)
            assertFalse(state.privacy.personalizationAllowed)
            draft.handle(KeyboardAction.LiteralText("😀ab"))
            assertEquals("😀ab", value)
            draft.handle(KeyboardAction.LiteralText("c"))
            assertEquals("😀ab", value)
            draft.handle(KeyboardAction.Backspace)
            draft.handle(KeyboardAction.Backspace)
            draft.handle(KeyboardAction.Backspace)
            assertEquals("", value)
            draft.handle(KeyboardAction.Enter)
            assertEquals("", value)
            draft.handle(KeyboardAction.LiteralText("test"))
            draft.clear()
            assertEquals("", value)
            draft.close()
            val closedRenders = renders
            draft.handle(KeyboardAction.LiteralText("late"))
            queued.toList().forEach(Runnable::run)
            assertEquals(closedRenders, renders)
            draft.start(InputLanguage.ENGLISH)
            assertEquals("", value)
        } finally { draft.close() }
    }

    @Test fun englishDraftNeitherPromotesNorLearnsPersonalCandidates() {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication).graph
        val previous = graph.engineExecutor.submit<UserTerm?> {
            graph.userLexicon.list().singleOrNull {
                it.language == InputLanguage.ENGLISH && it.shortcut == "th" && it.value == "there"
            }
        }.get(60, TimeUnit.SECONDS)
        graph.engineExecutor.submit {
            if (previous == null) graph.userLexicon.addPhrase("th", "there", InputLanguage.ENGLISH, 100)
            else graph.userLexicon.updatePhrase(previous.id, "th", "there", InputLanguage.ENGLISH, 100)
        }.get(60, TimeUnit.SECONDS)
        var committed = ""
        var state = InputSessionState()
        val draft = LocalDraftInput({ graph }, { false }, {}, { text, _, next -> committed = text; state = next }, maxChars = 64)
        try {
            onMain {
                draft.start(InputLanguage.ENGLISH)
                typePinyin(draft, "th")
                assertFalse(state.privacy.learningAllowed)
                assertFalse(state.privacy.personalizationAllowed)
                assertEquals("th", state.snapshot.candidates.first().text)
                assertFalse(state.snapshot.candidates.any { it.id.startsWith("personal:") })
                val choice = state.snapshot.candidates.indexOfFirst { it.text == "there" }
                draft.command(dev.zeroinput.ime.core.InputCommand.SelectCandidate(choice))
                assertEquals("there ", committed)
            }
            graph.engineExecutor.submit {}.get(60, TimeUnit.SECONDS)
            assertEquals(100, graph.userLexicon.list().single { it.value == "there" && it.shortcut == "th" }.frequency)
        } finally {
            onMain { draft.close() }
            graph.engineExecutor.submit {
                if (previous == null) {
                    graph.userLexicon.list().filter { it.shortcut == "th" && it.value == "there" }
                        .forEach { graph.userLexicon.remove(it.id) }
                } else {
                    graph.userLexicon.updatePhrase(previous.id, previous.shortcut, previous.value, previous.language, previous.frequency)
                }
                Unit
            }.get(60, TimeUnit.SECONDS)
        }
    }

    @Test fun draftRendersCommittedTextAndPreeditSeparately() = onMain {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication).graph
        var committed = ""
        var preedit = ""
        val draft = LocalDraftInput({ graph }, { false }, {}, { text, code, _ ->
            committed = text; preedit = code
        }, maxChars = 64)
        try {
            draft.start(InputLanguage.CHINESE)
            typePinyin(draft, "nih")
            // The unfinished code must stay out of the committed text; Rime
            // would even format it per syllable ("ni h").
            assertEquals("", committed)
            assertEquals("nih", preedit)
        } finally { draft.close() }
    }

    @Test fun handedOffReadingBecomesACompositionInsteadOfLetters() = onMain {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication).graph
        var committed = ""
        var preedit = ""
        val draft = LocalDraftInput({ graph }, { false }, {}, { text, code, _ ->
            committed = text; preedit = code
        }, maxChars = 64)
        try {
            draft.start(InputLanguage.CHINESE)
            assertTrue(draft.appendPreedit("hao"))
            assertEquals("", committed)
            assertEquals("hao", preedit)
            assertEquals("好", draft.submittedText())
        } finally { draft.close() }
    }

    private fun typePinyin(draft: LocalDraftInput, value: String) {
        value.forEach { draft.handle(KeyboardAction.Text(it.toString())) }
    }

    private fun awaitOnMain(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            onMain { ready = condition() }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("Draft engine did not reach the expected state")
    }

    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
