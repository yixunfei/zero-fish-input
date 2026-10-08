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
    @Test fun explicitCancelDropsReferencesAndHistoryButEditingStopPreservesSelections() {
        val f = Fixture()
        f.controller.submit(AiAction.ASK, "first", null)
        f.provider.emit(AiStreamEvent.Completed("answer"))
        f.flush()
        f.controller.addReferences(f.controller.contextState.revision, listOf(AiReference("page"),
            AiReference("import", AiReference.Source.IMPORT)))
        val selected = f.controller.contextState
        f.controller.stop()
        assertSame(selected, f.controller.contextState)
        f.controller.submit(AiAction.ASK, "second", null)
        assertFalse(f.provider.requests.last().history.isEmpty())
        assertEquals(2, f.provider.requests.last().references.size)
        f.provider.emit(AiStreamEvent.Completed("late"))
        f.controller.cancelRequest()
        f.flush()
        assertNull(f.controller.consumeResult())
        assertTrue(f.controller.contextState.revision > selected.revision)
        assertFalse(f.controller.includeHistory(selected.revision, 0, true))
        f.controller.stop()
        f.controller.submit(AiAction.ASK, "edited question", null)
        assertTrue(f.provider.requests.last().references.isEmpty())
        assertTrue(f.provider.requests.last().history.isEmpty())
        assertEquals(2, f.controller.contextState.messages.size)
    }

    @Test fun quickModelSwitchClearsOldContextAndPendingCompletionWithoutChangingDefault() {
        val f = Fixture(save = false)
        f.profile = f.profile.copy(models = listOf("fixture-model", "other-model"))
        f.controller.submit(AiAction.ASK, "old prompt", null)
        f.provider.emit(AiStreamEvent.Completed("old answer"))
        assertTrue(f.controller.selectModel("other-model"))
        f.flush()
        assertNull(f.controller.consumeResult())
        f.controller.submit(AiAction.ASK, "fresh prompt", null)
        val request = f.provider.requests.last()
        assertEquals("other-model", request.model)
        assertTrue(request.history.isEmpty())
        assertNull(request.conversationId)
        assertEquals("fixture-model", f.profile.selectedModel)
        f.controller.newConversation()
        f.controller.submit(AiAction.ASK, "another chat", null)
        assertEquals("other-model", f.provider.requests.last().model)
        f.controller.invalidate()
        f.controller.submit(AiAction.ASK, "reopened", null)
        assertEquals("fixture-model", f.provider.requests.last().model)
    }

    @Test fun newChatDropsPendingOutputAndHistoryWhileUnknownOrRestrictedModelSelectionFails() {
        val f = Fixture(save = false)
        f.profile = f.profile.copy(models = listOf("fixture-model", "other-model"))
        f.controller.submit(AiAction.ASK, "first", null)
        f.provider.emit(AiStreamEvent.Completed("answer"))
        f.flush()
        f.controller.submit(AiAction.ASK, "continue", null)
        f.provider.emit(AiStreamEvent.Completed("late"))
        f.controller.newConversation()
        f.flush()
        assertNull(f.controller.consumeResult())
        assertFalse(f.controller.selectModel("unknown"))
        f.allowed = false
        assertFalse(f.controller.selectModel("other-model"))
        f.allowed = true
        f.controller.submit(AiAction.ASK, "new", null)
        assertTrue(f.provider.requests.last().history.isEmpty())
        assertNull(f.provider.requests.last().conversationId)
    }

    @Test fun changingProviderOrModelRevokesOldResultHistoryAndQueuedPersistence() {
        val f = Fixture()
        f.controller.submit(AiAction.ASK, "first", null)
        f.provider.emit(AiStreamEvent.Completed("answer"))
        f.ui.drain()
        f.profile = f.profile.copy(id = "second", models = listOf("second-model"), selectedModel = "second-model")
        f.worker.drain()
        assertNull(f.controller.consumeResult())
        assertTrue(f.repository.list().isEmpty())
        f.controller.submit(AiAction.ASK, "new", null)
        assertTrue(f.provider.requests.last().history.isEmpty())
        assertNull(f.provider.requests.last().conversationId)
        f.provider.emit(AiStreamEvent.Completed("late"))
        f.profile = f.profile.copy(apiKey = "replacement-key")
        f.flush()
        assertNull(f.controller.consumeResult())
    }

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
        f.controller.recentHistory(f.controller.contextState.revision)
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
        f.controller.recentHistory(f.controller.contextState.revision)
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

    @Test fun savedHistoryRequiresSelectionAndPageReferencesNeverBecomeSavedMessages() {
        val f = Fixture()
        f.repository.upsert(AiConversation(id = "saved", title = "name", messages = listOf(
            AiMessage(AiRole.USER, "old question"), AiMessage(AiRole.ASSISTANT, "old answer"))))
        f.controller.selectConversation("saved")
        f.flush()
        assertTrue(f.controller.contextState.selectedHistory.isEmpty())
        f.controller.includeHistory(f.controller.contextState.revision, 1, true)
        f.controller.addReferences(f.controller.contextState.revision, listOf(AiReference("selected public page")))
        f.controller.submit(AiAction.ASK, "new question", null)
        val sent = f.provider.requests.last()
        assertEquals(listOf("old answer"), sent.history.map { it.content })
        assertEquals(listOf("selected public page"), sent.references.map { it.text })
        f.provider.emit(AiStreamEvent.Completed("new answer"))
        f.flush()
        assertEquals(listOf("old question", "old answer", "new question", "new answer"),
            f.repository.list().single().messages.map { it.content })
        f.controller.newConversation()
        assertTrue(f.controller.contextState.references.isEmpty())
    }

    @Test fun deselectingContextCancelsGenerationAndCannotReviveItsResult() {
        val f = Fixture()
        f.controller.addReferences(f.controller.contextState.revision, listOf(AiReference("public page")))
        f.controller.submit(AiAction.ASK, "question", null)
        f.provider.emit(AiStreamEvent.Completed("old answer"))
        f.controller.clearContext(f.controller.contextState.revision)
        f.flush()
        assertNull(f.controller.consumeResult())
        assertTrue(f.repository.list().isEmpty())
    }

    @Test fun renameUsesExistingFormatAndQueuedRenameCannotResurrectDeletedOrClearedHistory() {
        val f = Fixture()
        f.repository.upsert(AiConversation(id = "saved", title = "old"))
        f.controller.renameConversation("saved", "new")
        f.flush()
        assertEquals("new", f.repository.list().single().title)
        f.controller.renameConversation("saved", "late")
        f.controller.deleteConversation("saved")
        f.flush()
        assertTrue(f.repository.list().isEmpty())
        f.repository.upsert(AiConversation(id = "saved", title = "old"))
        f.controller.renameConversation("saved", "late")
        f.generation.invalidate()
        f.repository.clear()
        f.flush()
        assertTrue(f.repository.list().isEmpty())
    }

    @Test fun renamingRefreshesTheListWithoutEnteringLoadingState() {
        val f = Fixture()
        f.repository.upsert(AiConversation(id = "saved", title = "old"))
        f.controller.renameConversation("saved", "new")
        f.flush()
        assertEquals("new", f.summaries.single().title)
        assertTrue(f.historyLoading.none { it })
        f.controller.refreshConversations()
        assertTrue(f.historyLoading.last())
        f.flush()
        assertFalse(f.historyLoading.last())
    }

    @Test fun pageRevocationRejectsCompletionAndPersistenceBeforeUiObserverRuns() {
        for (completed in listOf(false, true)) {
            val f = Fixture()
            f.controller.addReferences(f.controller.contextState.revision, listOf(AiReference("public page")))
            f.controller.submit(AiAction.ASK, "question", null)
            f.provider.emit(AiStreamEvent.Completed("old answer"))
            if (completed) f.ui.drain()
            f.contextCurrent = false
            f.flush()
            assertNull(f.controller.consumeResult())
            assertTrue(f.repository.list().isEmpty())
        }
    }

    private class Fixture(save: Boolean = true) {
        val worker = Queue()
        val ui = Queue()
        val provider = Provider()
        val generation = AiDataGeneration()
        val repository = AiConversationRepository(MemoryStore())
        var allowed = true
        var contextCurrent = true
        var persistenceEnabled = save
        val events = mutableListOf<AiStreamEvent>()
        var summaries = emptyList<AiConversationSummary>()
        val historyLoading = mutableListOf<Boolean>()
        var profile = AiProviderProfile(
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
            generation, { ui.execute(it); true }, { allowed }, events::add, { summaries = it }, {},
            renderHistoryStatus = { _, loading, _ -> historyLoading += loading },
            contextCurrent = { contextCurrent })
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
