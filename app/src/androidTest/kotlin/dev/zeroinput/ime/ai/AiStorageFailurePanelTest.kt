package dev.zeroinput.ime.ai

import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ai.api.*
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ui.AiWorkbenchPanelView
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.userdata.AiConfiguration
import dev.zeroinput.userdata.AiConversationRepository
import dev.zeroinput.userdata.AiProviderProfile
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test
import dev.zeroinput.ime.ui.R as UiR

/** Fixed public content only; the provider and encrypted store never leave memory. */
class AiStorageFailurePanelTest {
    @Test fun saveFailureAndQueueRejectionLeaveTheAnswerVisibleAndInsertable() = onMain {
        for (reject in listOf(false, true)) {
            val f = Fixture()
            f.controller.submit(AiAction.ASK, "public question", null)
            f.worker.reject = reject
            f.store.failWrite = !reject
            f.provider.complete()
            f.flush()
            assertEquals(1, f.storageFailures)
            assertEquals("public answer", f.panel.findViewById<TextView>(UiR.id.ai_result_text).text.toString())
            assertNull("Completion must not insert automatically", f.inserted)
            assertTrue(f.panel.findViewById<TextView>(UiR.id.ai_insert_result).isEnabled)
            f.panel.findViewById<TextView>(UiR.id.ai_insert_result).performClick()
            assertEquals("public answer", f.inserted)
            assertNull(f.controller.consumeResult())
            f.controller.invalidate()
        }
    }

    @Test fun listReadFailureKeepsRetryVisibleAndPreservesTheAnswer() = onMain {
        for (reject in listOf(false, true)) {
            val f = Fixture()
            f.controller.submit(AiAction.ASK, "public question", null)
            f.provider.complete()
            f.flush()
            f.worker.reject = reject
            f.store.failRead = !reject
            f.button(UiR.string.ai_conversations_title).performClick()
            f.flush()
            assertFalse(f.panel.findViewById<View>(UiR.id.ai_result_text).visibility == View.VISIBLE)
            assertTrue(f.button(UiR.string.ai_conversations_retry).isEnabled)
            assertTrue(f.button(UiR.string.ai_conversations_failed).visibility == View.VISIBLE)
            assertEquals(0, f.storageFailures)
            f.worker.reject = false
            f.store.failRead = false
            f.button(UiR.string.ai_conversations_retry).performClick()
            f.flush()
            assertTrue(visible(f.panel).filterIsInstance<TextView>().none {
                it.text == f.panel.context.getString(UiR.string.ai_conversations_retry)
            })
            f.button(UiR.string.ai_show_result).performClick()
            assertEquals("public answer", f.panel.findViewById<TextView>(UiR.id.ai_result_text).text.toString())
            assertNull("Completion must not insert automatically", f.inserted)
            assertTrue(f.panel.findViewById<TextView>(UiR.id.ai_insert_result).isEnabled)
            f.panel.findViewById<TextView>(UiR.id.ai_insert_result).performClick()
            assertEquals("public answer", f.inserted)
            f.controller.invalidate()
        }
    }

    private class Fixture {
        val panel = AiWorkbenchPanelView(ContextThemeWrapper(
            InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_ZeroInput_InputMethod))
        val worker = Queue()
        private val ui = Queue()
        val store = MemoryStore()
        val provider = Provider()
        var storageFailures = 0
        var inserted: String? = null
        private val profile = AiProviderProfile(id = "fixture", name = "Public fixture",
            endpoint = "https://provider.example/v1/chat/completions", apiKey = "public-fixture",
            models = listOf("fixture-model"), selectedModel = "fixture-model")
        private val configuration = AiConfiguration(enabled = true, networkAllowed = true, saveConversations = true,
            providers = listOf(profile), selectedProviderId = profile.id)
        val controller = AiWorkbenchController(AiCoordinator(provider), AiConversationRepository(store), worker,
            { configuration }, AiDataGeneration(), { ui.execute(it); true }, { true }, panel::render,
            panel::renderConversations, panel::renderConversation,
            editorPrivacy = { dev.zeroinput.ime.core.privacy.SessionPrivacy(false, true, true,
                dev.zeroinput.ime.core.privacy.PrivacyReason.NONE) },
            renderHistoryStatus = panel::renderHistoryStatus, storageFailed = { storageFailures++ })

        init {
            panel.renderDraft("public question")
            panel.onConversationsRequested = { controller.refreshConversations() }
            panel.onInsert = { displayed, thinking, sections ->
                inserted = controller.consumeResult(thinking, displayed, sections)
            }
        }

        fun button(label: Int) = visible(panel).filterIsInstance<TextView>().single {
            it.text == panel.context.getString(label)
        }
        fun flush() { repeat(3) { ui.drain(); worker.drain() }; ui.drain() }
    }

    private class Provider : AiProvider {
        private var listener: ((AiStreamEvent) -> Unit)? = null
        override fun stream(request: AiRequest, listener: (AiStreamEvent) -> Unit): AiRequestHandle {
            this.listener = listener
            return object : AiRequestHandle { override fun cancel() { this@Provider.listener = null } }
        }
        fun complete() { listener?.invoke(AiStreamEvent.Completed("public answer")) }
    }

    private class Queue : Executor {
        var reject = false
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            if (reject) throw RejectedExecutionException()
            tasks.addLast(command)
        }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    private class MemoryStore : EncryptedStore {
        private var bytes: ByteArray? = null
        var failWrite = false
        var failRead = false
        override fun read(): ByteArray? {
            if (failRead) throw java.io.IOException("Public fixture failure")
            return bytes?.copyOf()
        }
        override fun write(plaintext: ByteArray) {
            if (failWrite) throw java.io.IOException("Public fixture failure")
            bytes?.fill(0)
            bytes = plaintext.copyOf()
        }
        override fun delete(deleteKey: Boolean) { bytes?.fill(0); bytes = null }
    }

    companion object {
        private fun visible(view: View): List<View> = if (view.visibility != View.VISIBLE) emptyList() else
            listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { visible(view.getChildAt(it)) } else emptyList()

        private fun onMain(action: () -> Unit) {
            var result: Result<Unit>? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(action) }
            checkNotNull(result).getOrThrow()
        }
    }
}
