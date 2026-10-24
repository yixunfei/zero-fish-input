package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.*
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.userdata.AiConfiguration
import dev.zeroinput.userdata.AiConversationRepository
import dev.zeroinput.userdata.AiProviderProfile
import dev.zeroinput.ime.core.privacy.SessionPrivacy
import dev.zeroinput.ime.core.privacy.PrivacyReason
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AiWorkbenchControllerTest {
    @Test fun reasoningIsRenderedSeparatelyAndCannotBeConsumedForInsertion() {
        val f = Fixture(save = false)
        f.controller.submit(AiAction.ASK, "public question", null)
        f.provider.emit(AiStreamEvent.Completed("<think>private reasoning</think>public answer"))
        f.flush()

        val completed = f.events.filterIsInstance<AiStreamEvent.Completed>().last()
        assertEquals("public answer", completed.text)
        assertEquals("private reasoning", completed.thinking)
        assertEquals("public answer", f.controller.consumeResult(includeThinking = true))
        assertNull(f.controller.consumeResult())
        assertTrue(f.repository.list().isEmpty())
    }

    @Test fun providerReasoningFieldStaysOutsideTheDisplayedAndStoredAnswer() {
        val f = Fixture(save = true)
        f.controller.submit(AiAction.ASK, "public question", null)
        f.provider.emit(AiStreamEvent.Delta("public ", "interim reasoning"))
        f.provider.emit(AiStreamEvent.Delta("answer"))
        f.provider.emit(AiStreamEvent.Completed("public answer", "provider reasoning"))
        f.flush()

        val completed = f.events.filterIsInstance<AiStreamEvent.Completed>().single()
        assertEquals("public answer", completed.text)
        assertEquals("provider reasoning", completed.thinking)
        assertEquals("public answer", f.controller.consumeResult(includeThinking = true))
        f.worker.drain(); f.ui.drain()
        assertEquals(listOf("public question", "public answer"),
            f.repository.list().single().messages.map { it.content })
    }

    @Test fun realEditorPrivacyRejectsSubmissionEvenWhenUiEligibilityAllowsIt() {
        val restrictions = PrivacyReason.entries.filter { it != PrivacyReason.NONE }.map {
            SessionPrivacy(false, true, false, it)
        } + listOf(null, SessionPrivacy(true, true, true, PrivacyReason.PASSWORD_FIELD),
            SessionPrivacy(false, false, true, PrivacyReason.EDITOR_REQUEST))
        for (privacy in restrictions) {
            val f = Fixture()
            f.privacy = privacy
            f.controller.submit(AiAction.ASK, "public question", null)
            f.flush()
            assertTrue(f.provider.requests.isEmpty())
            assertTrue((f.events.last() as AiStreamEvent.Failed).error is AiProviderError.Policy)
        }
    }

    @Test fun independentNoSuggestionsDraftReachesProviderWithoutPersonalization() {
        val f = Fixture()
        f.privacy = SessionPrivacy(false, true, false, PrivacyReason.EDITOR_REQUEST,
            predictionsAllowed = false, independentAiAllowed = true)
        f.controller.submit(AiAction.ASK, "public question", null)
        assertEquals(1, f.provider.requests.size)
        assertFalse(checkNotNull(f.privacy).personalizationAllowed)
    }

    @Test fun lateStorageFailureCannotNotifyOrReviveARevokedResult() {
        for (revocation in 0..4) {
            val f = Fixture()
            f.store.failWrite = true
            f.controller.submit(AiAction.ASK, "public question", null)
            f.provider.emit(AiStreamEvent.Completed("public answer"))
            f.ui.drain()
            f.worker.drain()
            when (revocation) {
                0 -> f.controller.invalidate()
                1 -> f.generation.invalidate()
                2 -> f.allowed = false
                3 -> f.contextCurrent = false
                4 -> f.persistenceEnabled = false
            }
            f.ui.drain()
            assertEquals(0, f.storageFailures)
            assertNull(f.controller.consumeResult())
            assertTrue(f.repository.list().isEmpty())
        }
    }

    @Test fun rejectedAutomaticListRefreshPreservesTheSavedAnswer() {
        val f = Fixture()
        f.controller.submit(AiAction.ASK, "public question", null)
        f.provider.emit(AiStreamEvent.Completed("public answer"))
        f.ui.drain()
        f.worker.drain()
        f.worker.reject = true
        f.ui.drain()
        assertTrue(f.historyFailed.last())
        assertFalse(f.events.any { it is AiStreamEvent.Failed })
        assertEquals("public answer", f.controller.consumeResult())
        assertEquals(1, f.repository.list().size)
    }

    @Test fun editingStillRevokesPendingHistoryWhenNoOutputNeedsClearing() {
        val f = Fixture()
        f.repository.upsert(AiConversation(id = "saved", title = "public chat"))
        f.controller.selectConversation("saved")
        f.worker.drain()
        f.controller.stop()
        f.ui.drain()
        f.controller.submit(AiAction.ASK, "fresh question", null)
        assertNull(f.provider.requests.last().conversationId)
    }

    @Test fun failedSaveOrQueueRejectionPreservesCompletedAnswerForOneInsertion() {
        for (reject in listOf(false, true)) {
            val f = Fixture()
            f.controller.submit(AiAction.ASK, "public question", null)
            f.worker.reject = reject
            f.store.failWrite = !reject
            f.provider.emit(AiStreamEvent.Completed("public answer"))
            f.flush()
            assertFalse(f.events.any { it is AiStreamEvent.Failed })
            assertEquals(1, f.storageFailures)
            assertEquals("public answer", f.controller.consumeResult())
            assertNull(f.controller.consumeResult())
            assertTrue(f.repository.list().isEmpty())
        }
    }

    @Test fun historyFailureDoesNotCancelAnActiveRequestOrEraseACompletedAnswer() {
        for (completed in listOf(false, true)) {
            val f = Fixture(save = true)
            f.controller.submit(AiAction.ASK, "public question", null)
            if (completed) {
                f.provider.emit(AiStreamEvent.Completed("public answer"))
                f.flush()
            }
            f.worker.reject = true
            f.controller.refreshConversations()
            assertTrue(f.historyFailed.last())
            assertFalse(f.events.any { it is AiStreamEvent.Failed })
            if (!completed) f.provider.emit(AiStreamEvent.Completed("public answer"))
            f.flush()
            assertEquals("public answer", f.controller.consumeResult())
        }
    }

    @Test fun repeatedEditingStopsRenderCancellationOnlyOnceAndRejectLateOutput() {
        val f = Fixture(save = false)
        f.controller.submit(AiAction.ASK, "public question", null)
        f.events.clear()
        f.controller.stop()
        repeat(5) { f.controller.stop() }
        f.provider.emit(AiStreamEvent.Completed("late answer"))
        f.flush()
        assertEquals(listOf(AiStreamEvent.Cancelled), f.events)
        assertNull(f.controller.consumeResult())
    }

    @Test fun editingAfterAConfigurationFailureClearsTheErrorOnlyOnce() {
        val f = Fixture(save = false)
        f.profile = f.profile.copy(apiKey = "")
        f.controller.submit(AiAction.ASK, "public question", null)
        assertTrue(f.events.last() is AiStreamEvent.Failed)
        f.events.clear()
        repeat(3) { f.controller.stop() }
        assertEquals(listOf(AiStreamEvent.Cancelled), f.events)
    }

    @Test fun failedRenameReportsAStorageOperationWithoutClaimingHistoryLoadingFailed() {
        for (reject in listOf(false, true)) {
            val f = Fixture()
            f.repository.upsert(AiConversation(id = "saved", title = "old"))
            f.worker.reject = reject
            f.store.failWrite = !reject
            f.controller.renameConversation("saved", "new")
            f.flush()
            assertFalse(f.events.any { it is AiStreamEvent.Failed })
            assertEquals(1, f.storageFailures)
            assertTrue(f.historyFailed.none { it })
            assertEquals("old", f.repository.list().single().title)
        }
    }

    @Test fun failedHistoryLoadRetainsTheSpecificListFailureStatus() {
        val f = Fixture()
        f.worker.reject = true
        f.controller.refreshConversations()
        assertTrue(f.historyFailed.last())
        assertFalse(f.events.any { it is AiStreamEvent.Failed })
        assertEquals(0, f.storageFailures)
    }

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

    @Test fun loadedConversationContinuesWithRecentHistoryWithoutAnExtraContextAction() {
        val f = Fixture()
        f.repository.upsert(AiConversation(id = "saved", title = "fixture", messages = listOf(
            AiMessage(AiRole.USER, "earlier question"), AiMessage(AiRole.ASSISTANT, "earlier answer"),
        )))
        f.controller.selectConversation("saved")
        f.flush()

        f.controller.submit(AiAction.ASK, "follow up", null)

        assertEquals("saved", f.provider.requests.last().conversationId)
        assertEquals(listOf("earlier question", "earlier answer"),
            f.provider.requests.last().history.map { it.content })
    }

    @Test fun failedDeleteKeepsTheSelectedConversationAvailable() {
        val f = Fixture()
        val conversation = AiConversation(id = "saved", title = "fixture", messages = listOf(
            AiMessage(AiRole.USER, "question"), AiMessage(AiRole.ASSISTANT, "answer"),
        ))
        f.repository.upsert(conversation)
        f.controller.selectConversation(conversation.id)
        f.flush()
        f.store.failWrite = true

        f.controller.deleteConversation(conversation.id)
        f.flush()
        f.controller.submit(AiAction.ASK, "follow up", null)

        assertEquals(listOf(conversation), f.repository.list())
        assertEquals(conversation.id, f.provider.requests.last().conversationId)
        assertEquals(listOf("question", "answer"), f.provider.requests.last().history.map { it.content })
    }

    @Test fun selectedResponseSectionsAreValidatedAndConsumedOnce() {
        val f = Fixture()
        f.controller.submit(AiAction.ASK, "question", null)
        f.provider.emit(AiStreamEvent.Completed("first\n\nsecond\n\nthird"))
        f.flush()
        val response = "first\n\nsecond\n\nthird"

        assertNull(f.controller.consumeResult(displayedResult = response, selectedSections = listOf(0, 3)))
        assertEquals("first\n\nthird",
            f.controller.consumeResult(displayedResult = response, selectedSections = listOf(2, 0, 2)))
        assertNull(f.controller.consumeResult(displayedResult = response, selectedSections = listOf(0)))
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

    @Test fun loadedConversationContinuesWithRecentHistoryAndPageReferencesStayTransient() {
        val f = Fixture()
        f.repository.upsert(AiConversation(id = "saved", title = "name", messages = listOf(
            AiMessage(AiRole.USER, "old question"), AiMessage(AiRole.ASSISTANT, "old answer"))))
        f.controller.selectConversation("saved")
        f.flush()
        assertEquals(setOf(0, 1), f.controller.contextState.selectedHistory)
        f.controller.addReferences(f.controller.contextState.revision, listOf(AiReference("selected public page")))
        f.controller.submit(AiAction.ASK, "new question", null)
        val sent = f.provider.requests.last()
        assertEquals(listOf("old question", "old answer"), sent.history.map { it.content })
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
        val store = MemoryStore()
        val repository = AiConversationRepository(store)
        var allowed = true
        var privacy: SessionPrivacy? = SessionPrivacy(false, true, true, PrivacyReason.NONE)
        var contextCurrent = true
        var persistenceEnabled = save
        val events = mutableListOf<AiStreamEvent>()
        var summaries = emptyList<AiConversationSummary>()
        val historyLoading = mutableListOf<Boolean>()
        val historyFailed = mutableListOf<Boolean>()
        var storageFailures = 0
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
            editorPrivacy = { privacy },
            renderHistoryStatus = { _, loading, failed -> historyLoading += loading; historyFailed += failed },
            contextCurrent = { contextCurrent }, storageFailed = { storageFailures++ })
        fun flush() { repeat(3) { ui.drain(); worker.drain() }; ui.drain() }
    }
    private class Queue : Executor {
        var reject = false
        val pending = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            if (reject) throw java.util.concurrent.RejectedExecutionException()
            pending.add(command)
        }
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
        var failWrite = false
        private var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun write(plaintext: ByteArray) {
            if (failWrite) throw java.io.IOException("Public fixture failure")
            bytes = plaintext.copyOf()
        }
        override fun delete(deleteKey: Boolean) { bytes = null }
    }
}
