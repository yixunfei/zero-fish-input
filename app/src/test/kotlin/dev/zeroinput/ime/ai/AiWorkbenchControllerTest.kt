package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.*
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.userdata.AiConfiguration
import dev.zeroinput.userdata.AiConversationRepository
import dev.zeroinput.userdata.AiProviderProfile
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AiWorkbenchControllerTest {
    @Test fun disablingPersistenceWithoutAnObserverCannotSendPreviouslySelectedHistory() {
        for (refresh in listOf(false, true)) {
            val f = Fixture()
            f.controller.submit(AiAction.ASK, "first", null)
            f.provider.emit(AiStreamEvent.Completed("answer"))
            f.flush()
            f.persistenceEnabled = false
            if (refresh) f.controller.refreshConversations()
            f.controller.submit(AiAction.ASK, "new", null)
            assertNull(f.provider.requests.last().conversationId)
            assertTrue(f.provider.requests.last().history.isEmpty())
        }
    }

    @Test fun disablingPersistenceDropsPendingHistoryAndCompletionWithoutAnObserver() {
        val f = Fixture()
        f.repository.upsert(AiConversation(id = "saved", title = "fixture",
            messages = listOf(AiMessage(AiRole.USER, "old"))))
        f.controller.selectConversation("saved")
        f.worker.drain()
        f.persistenceEnabled = false
        f.ui.drain()
        f.controller.submit(AiAction.ASK, "new", null)
        assertTrue(f.provider.requests.last().history.isEmpty())
        f.persistenceEnabled = true
        f.controller.submit(AiAction.ASK, "another", null)
        f.provider.emit(AiStreamEvent.Completed("late"))
        f.persistenceEnabled = false
        f.flush()
        assertNull(f.controller.consumeResult())
        assertEquals(1, f.repository.list().size)
    }

    @Test fun queuedCompletionCannotReachAnotherEditorOrPersistAfterInvalidation() {
        val f = Fixture()
        f.controller.submit(AiAction.ASK, "fixture", null)
        f.provider.emit(AiStreamEvent.Completed("old"))
        f.controller.invalidate()
        f.ui.drain(); f.worker.drain(); f.ui.drain()
        assertNull(f.controller.consumeResult())
        assertTrue(f.repository.list().isEmpty())
        assertFalse(f.events.any { it is AiStreamEvent.Completed })
    }

    @Test fun selectedHistoryContinuesTheSameConversationAndCanBeDeleted() {
        val f = Fixture()
        f.controller.submit(AiAction.ASK, "first", null)
        f.provider.emit(AiStreamEvent.Completed("answer"))
        f.flush()
        val id = f.repository.list().single().id
        f.controller.newConversation()
        f.controller.selectConversation(id)
        f.flush()
        f.controller.submit(AiAction.ASK, "follow up", null)
        assertEquals(id, f.provider.requests.last().conversationId)
        assertEquals(listOf("first", "answer"), f.provider.requests.last().history.map { it.content })
        f.provider.emit(AiStreamEvent.Completed("second answer"))
        f.flush()
        assertEquals(4, f.repository.list().single().messages.size)
        f.controller.deleteConversation(id)
        f.flush()
        assertTrue(f.repository.list().isEmpty())
        assertNull(f.controller.consumeResult())
    }

    @Test fun clearingDataBeforeQueuedSaveCannotResurrectAConversation() {
        val f = Fixture()
        f.controller.submit(AiAction.PLAN, "goal", null)
        f.provider.emit(AiStreamEvent.Completed("steps"))
        f.ui.drain()
        f.generation.invalidate()
        f.repository.clear()
        f.worker.drain(); f.ui.drain()
        assertTrue(f.repository.list().isEmpty())
        assertNull(f.controller.consumeResult())
    }

    @Test fun continuedConversationPreservesMessagesExcludedFromNetworkContext() {
        val f = Fixture()
        val history = List(AiLimits.MAX_HISTORY_MESSAGES) { index ->
            AiMessage(if (index % 2 == 0) AiRole.USER else AiRole.ASSISTANT, "fixture $index")
        }
        f.repository.upsert(AiConversation(id = "saved", title = "fixture", messages = history))
        f.controller.selectConversation("saved")
        f.flush()
        f.controller.submit(AiAction.ASK, "follow up", null)
        assertEquals(12, f.provider.requests.last().history.size)
        f.provider.emit(AiStreamEvent.Completed("answer"))
        f.flush()
        val saved = f.repository.list().single().messages
        assertEquals(AiLimits.MAX_HISTORY_MESSAGES, saved.size)
        assertEquals(history.drop(2), saved.dropLast(2))
        assertEquals(listOf("follow up", "answer"), saved.takeLast(2).map { it.content })
    }

    @Test fun restrictedSessionNeverStartsProviderAndRevokesVisibleResult() {
        val f = Fixture()
        f.allowed = false
        f.controller.submit(AiAction.TRANSLATE, "text", "English")
        assertTrue(f.provider.requests.isEmpty())
        f.allowed = true
        f.controller.submit(AiAction.ASK, "fixture", null)
        f.provider.emit(AiStreamEvent.Completed("response"))
        f.flush()
        f.allowed = false
        assertNull(f.controller.consumeResult())
    }

    @Test fun invalidTerminalOutputIsRejectedBeforeItBecomesInsertable() {
        for (text in listOf("", " ", "x".repeat(AiLimits.MAX_OUTPUT_CHARS + 1))) {
            val f = Fixture()
            f.controller.submit(AiAction.ASK, "fixture", null)
            f.provider.emit(AiStreamEvent.Completed(text))
            f.flush()
            assertNull(f.controller.consumeResult())
            assertTrue(f.events.last() is AiStreamEvent.Failed)
            assertFalse(f.events.any { it is AiStreamEvent.Completed })
            assertTrue(f.repository.list().isEmpty())
        }
    }

    @Test fun defaultOffPersistenceStillAllowsTransientFollowUpAndOneTimeInsertion() {
        val f = Fixture(save = false)
        f.controller.submit(AiAction.ASK, "fixture", null)
        f.provider.emit(AiStreamEvent.Completed("response"))
        f.flush()
        assertEquals("response", f.controller.consumeResult())
        assertNull(f.controller.consumeResult())
        assertTrue(f.repository.list().isEmpty())
        f.controller.submit(AiAction.ASK, "continue", null)
        assertEquals(2, f.provider.requests.last().history.size)
    }

    @Test fun refreshingHistoryWithoutPersistenceKeepsTheTransientConversationAndResult() {
        val f = Fixture(save = false)
        f.controller.submit(AiAction.ASK, "first", null)
        f.provider.emit(AiStreamEvent.Completed("answer"))
        f.flush()
        f.controller.refreshConversations()
        f.flush()
        assertEquals("answer", f.controller.consumeResult())
        f.controller.submit(AiAction.ASK, "continue", null)
        assertEquals(listOf("first", "answer"), f.provider.requests.last().history.map { it.content })
    }

    @Test fun refreshingHistoryWithoutPersistenceDoesNotCancelAnActiveRequest() {
        val f = Fixture(save = false)
        f.controller.submit(AiAction.ASK, "fixture", null)
        f.controller.refreshConversations()
        f.provider.emit(AiStreamEvent.Completed("answer"))
        f.flush()
        assertEquals("answer", f.controller.consumeResult())
    }

    @Test fun settingsInvalidationClearsSelectedConversationWithoutDeletingFromThePanel() {
        val f = Fixture()
        f.controller.submit(AiAction.ASK, "fixture", null)
        f.provider.emit(AiStreamEvent.Completed("answer"))
        f.flush()
        val id = f.repository.list().single().id
        f.controller.selectConversation(id)
        f.flush()
        f.persistenceEnabled = false
        f.controller.invalidate()
        f.controller.refreshConversations()
        f.flush()
        assertEquals(id, f.repository.list().single().id)
        f.controller.submit(AiAction.ASK, "new", null)
        assertNull(f.provider.requests.last().conversationId)
    }

    @Test fun lateConversationLoadCannotReplaceANewChat() {
        val f = Fixture()
        f.repository.upsert(AiConversation(id = "saved", title = "fixture"))
        f.controller.selectConversation("saved")
        f.worker.drain()
        f.controller.newConversation()
        f.ui.drain()
        f.controller.submit(AiAction.ASK, "new", null)
        assertNull(f.provider.requests.last().conversationId)
    }

    @Test fun conversationTitleDoesNotSplitASupplementaryCharacterAtTheLimit() {
        val f = Fixture()
        val prefix = "x".repeat(AiLimits.MAX_TITLE_CHARS - 1)
        f.controller.submit(AiAction.ASK, prefix + "\uD83D\uDE00", null)
        f.provider.emit(AiStreamEvent.Completed("answer"))
        f.flush()
        assertEquals(prefix, f.repository.list().single().title)
        assertEquals("answer", f.controller.consumeResult())
    }

    private class Fixture(save: Boolean = true) {
        val worker = Queue()
        val ui = Queue()
        val provider = Provider()
        val generation = AiDataGeneration()
        val repository = AiConversationRepository(MemoryStore())
        var allowed = true
        var persistenceEnabled = save
        val events = mutableListOf<AiStreamEvent>()
        private val profile = AiProviderProfile(
            id = "fixture",
            name = "Fixture",
            endpoint = "https://provider.example/v1/chat/completions",
            apiKey = "fixture-key",
            models = listOf("fixture-model"),
            selectedModel = "fixture-model",
        )
        val controller = AiWorkbenchController(AiCoordinator(provider), repository, worker,
            {
                AiConfiguration(
                    enabled = true,
                    networkAllowed = true,
                    saveConversations = persistenceEnabled,
                    providers = listOf(profile),
                    selectedProviderId = profile.id,
                )
            },
            generation, { ui.execute(it); true }, { allowed }, events::add, {}, {})
        fun flush() { repeat(3) { ui.drain(); worker.drain() }; ui.drain() }
    }
    private class Queue : Executor {
        val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { pending.add(command) }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }
    private class Provider : AiProvider {
        val requests = mutableListOf<AiRequest>()
        lateinit var listener: (AiStreamEvent) -> Unit
        override fun stream(request: AiRequest, listener: (AiStreamEvent) -> Unit): AiRequestHandle {
            requests += request
            this.listener = listener
            return object : AiRequestHandle { override fun cancel() = Unit }
        }
        fun emit(event: AiStreamEvent) = listener(event)
    }
    private class MemoryStore : EncryptedStore {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) { bytes = plaintext.copyOf() }
        override fun delete(deleteKey: Boolean) { bytes = null }
    }
}
