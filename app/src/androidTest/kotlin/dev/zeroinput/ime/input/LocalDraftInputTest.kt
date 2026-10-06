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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalDraftInputTest {
    @Test fun preparedNativeEngineWaitsForCompositionThenConvertsTheWholeNextPhrase() {
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
        val draft = LocalDraftInput({ graph }, handler::post, { _, next -> state = next }, maxChars = 64)
        try {
            onMain { draft.start(InputLanguage.CHINESE); typePinyin(draft, "nihao") }
            release.countDown()
            graph.engineExecutor.submit {}.get(20, TimeUnit.SECONDS)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            onMain {
                assertTrue(state.snapshot.isComposing)
                assertNotEquals(graph.rime.descriptor.id, state.engineDescriptor?.id)
                assertEquals("你好", draft.submittedText())
            }
            awaitOnMain { state.engineDescriptor?.id == graph.rime.descriptor.id }
            onMain {
                assertEquals(graph.rime.descriptor.id, state.engineDescriptor?.id)
                typePinyin(draft, "zhongguoren")
                assertEquals("你好中国人", draft.submittedText())
                assertFalse(state.snapshot.isComposing)
                assertFalse(state.privacy.learningAllowed)
                assertFalse(state.privacy.personalizationAllowed)
            }
        } finally { release.countDown(); onMain { draft.close() }; blocker.get(10, TimeUnit.SECONDS) }
    }

    @Test fun sendingChineseDraftConvertsCompositionAndPreservesEverySegment() = onMain {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication).graph
        var state = InputSessionState()
        val draft = LocalDraftInput({ graph }, { false }, { _, next -> state = next }, maxChars = 64)
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

    @Test fun boundedDraftDisablesPersonalizationAndClearsOnCloseOrRestart() = onMain {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ZeroInputApplication).graph
        val queued = mutableListOf<Runnable>()
        var value = ""
        var renders = 0
        var state = InputSessionState()
        val draft = LocalDraftInput({ graph }, { queued += it; true }, { text, next ->
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
