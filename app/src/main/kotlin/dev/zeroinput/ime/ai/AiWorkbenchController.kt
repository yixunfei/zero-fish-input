package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.*
import dev.zeroinput.userdata.AiConfiguration
import dev.zeroinput.userdata.AiConversationRepository
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/** Owner-thread workbench state; no editor, clipboard or personal-data read ports. */
internal class AiWorkbenchController(
    private val coordinator: AiCoordinator,
    private val conversations: AiConversationRepository,
    private val executor: Executor,
    private val configuration: () -> AiConfiguration?,
    private val dataGeneration: AiDataGeneration,
    private val post: (() -> Unit) -> Boolean,
    private val allowed: () -> Boolean,
    private val render: (AiStreamEvent) -> Unit,
    private val renderList: (List<AiConversationSummary>) -> Unit,
    private val renderConversation: (AiConversation?) -> Unit,
    private val editorPrivacy: () -> dev.zeroinput.ime.core.privacy.SessionPrivacy?,
    private val renderModels: (List<String>, String?) -> Unit = { _, _ -> },
    private val renderContext: (AiContextState) -> Unit = {},
    private val renderHistoryStatus: (Boolean, Boolean, Boolean) -> Unit = { _, _, _ -> },
    private val contextCurrent: () -> Boolean = { true },
    private val storageFailed: () -> Unit = {},
) {
    private val context = AiContextSelection()
    val contextState: AiContextState get() = context.state
    private val generation = AtomicLong()
    private var selected: AiConversation? = null
    private var result: String? = null
    private var handle: AiRequestHandle? = null
    private var delivery: AiStreamDelivery? = null
    private var resultDataGeneration = -1L
    private var boundConfiguration = configuration()
    private var selectedModel: String? = null
    private var outputNeedsReset = false
    private var reasoningMode = AiReasoningMode.DEFAULT

    fun setReasoningMode(value: AiReasoningMode) {
        if (reasoningMode != value) { stop(); reasoningMode = value }
    }

    fun invalidate() {
        stop()
        selectedModel = null
        renderModels(emptyList(), null)
        selected = null
        context.reset()
        renderContext(context.state)
        showConversation(null)
        renderList(emptyList())
    }

    fun stop() {
        // Generation revocation is required even when only a storage task is pending.
        generation.incrementAndGet()
        delivery?.close()
        delivery = null
        handle?.cancel()
        handle = null
        result = null
        if (outputNeedsReset) renderOutput(AiStreamEvent.Cancelled)
    }

    /** Explicit user cancellation ends the request and clears transient context choices. */
    fun cancelRequest() {
        stop()
        if (context.state.selectedHistory.isNotEmpty() || context.state.references.isNotEmpty()) {
            context.reset(context.state.messages)
            renderContext(context.state)
        }
    }

    fun newConversation() {
        stop()
        selected = null
        context.reset()
        renderContext(context.state)
        showConversation(null)
    }

    fun includeHistory(revision: Long, index: Int, included: Boolean) = changeContext {
        context.include(revision, index, included)
    }

    fun recentHistory(revision: Long) = changeContext { context.recent(revision) }
    fun clearContext(revision: Long) = changeContext { context.clear(revision) }
    fun removeReference(revision: Long, index: Int) = changeContext { context.remove(revision, index) }
    fun addReferences(revision: Long, references: List<AiReference>): Boolean = changeContext {
        context.add(revision, references)
    }

    fun clearPageReferences() {
        if (context.clearPages()) { stop(); renderContext(context.state) }
    }

    private fun changeContext(change: () -> Boolean): Boolean {
        reconcilePersistence()
        if (!allowed() || !change()) return false
        stop()
        renderContext(context.state)
        return true
    }

    fun refreshModels() {
        reconcilePersistence()
        val profile = configuration()?.activeProvider()?.takeIf { allowed() }
        renderModels(profile?.models.orEmpty(), selectedModel ?: profile?.selectedModel)
    }

    /** A quick choice is local to this workbench and starts with no prior model context. */
    fun selectModel(model: String): Boolean {
        reconcilePersistence()
        val config = configuration() ?: return false
        val profile = config.activeProvider() ?: return false
        if (!allowed() || !config.enabled || !config.networkAllowed || model !in profile.models ||
            model == (selectedModel ?: profile.selectedModel)) return false
        newConversation()
        selectedModel = model
        refreshModels()
        return true
    }

    fun refreshConversations(showLoading: Boolean = true) {
        reconcilePersistence()
        val saving = configuration()?.saveConversations == true
        renderHistoryStatus(saving, false, false)
        if (!allowed()) {
            renderList(emptyList())
            return
        }
        if (configuration()?.saveConversations != true) {
            renderList(emptyList())
            return
        }
        if (showLoading) renderHistoryStatus(true, true, false)
        storage({ current -> conversations.listSummaries(current) }, {
            renderList(it)
            renderHistoryStatus(true, false, false)
        }, historyRead = true)
    }

    fun selectConversation(id: String) {
        reconcilePersistence()
        if (!allowed()) return
        if (configuration()?.saveConversations != true) {
            return
        }
        newConversation()
        storage({ current -> conversations.find(id, current) }, deliver = {
            selected = it
            // Opening a saved chat is an explicit choice to continue it. Include
            // only the bounded recent history; users can uncheck individual turns.
            context.reset(it?.messages.orEmpty(), includeRecent = true)
            renderContext(context.state)
            showConversation(it)
        })
    }

    fun deleteConversation(id: String) {
        reconcilePersistence()
        if (!allowed() || configuration()?.saveConversations != true) return
        stop()
        storage({ current -> conversations.delete(id, current) }, deliver = {
            if (selected?.id == id) {
                selected = null
                context.reset()
                renderContext(context.state)
                showConversation(null)
            }
            refreshConversations()
        }, onFailure = {
            selected?.takeIf { it.id == id }?.let(::showConversation)
        })
    }

    fun renameConversation(id: String, title: String) {
        reconcilePersistence()
        if (!allowed() || configuration()?.saveConversations != true) return
        stop()
        storage({ current -> conversations.rename(id, title, current) }, { renamed ->
            if (renamed != null && selected?.id == id) {
                selected = renamed
                showConversation(renamed)
            }
            refreshConversations(showLoading = false)
        })
    }

    fun submit(action: AiAction, input: String, target: String?, attachments: List<AiAttachment> = emptyList()) {
        reconcilePersistence()
        stop()
        val config = configuration()
        if (!allowed() || !contextCurrent()) {
            fail(AiProviderError.Policy("AI unavailable in this editor"))
            return
        }
        if (config == null || !config.enabled || !config.networkAllowed) {
            fail(AiProviderError.Policy("AI network is disabled"))
            return
        }
        if (config.activeKey().isBlank()) {
            fail(AiProviderError.Configuration("AI provider is not configured"))
            return
        }
        val previous = selected
        val request = try {
            copyRequest(AiRequest(previous?.id, action, input, target,
                context.history(), attachments, model = selectedModel ?: config.activeModel(),
                references = context.state.references.toList(), reasoningMode = reasoningMode))
        } catch (_: IllegalArgumentException) {
            fail()
            return
        }
        val token = generation.get()
        val data = dataGeneration.current()
        val reasoningStream = AiThinkingText.Stream()
        val sink = AiStreamDelivery(post) { event ->
            reconcilePersistence()
            if (generation.get() != token || !dataGeneration.isCurrent(data) || !allowed() || !contextCurrent()) return@AiStreamDelivery
            when (event) {
                is AiStreamEvent.Completed -> {
                    reasoningStream.clear()
                    // A provider implementation can emit a terminal event
                    // without going through the bounded SSE reader. Reject
                    // malformed/empty output before exposing it as a usable
                    // result or persisting it.
                    val split = AiThinkingText.split(event.text)
                    val answer = split.answer
                    if (event.text.isBlank() || event.text.length > AiLimits.MAX_OUTPUT_CHARS || answer.isBlank() ||
                        event.thinking.length > AiLimits.MAX_OUTPUT_CHARS - split.thinking.length) {
                        result = null
                        renderOutput(AiStreamEvent.Failed(AiProviderError.Response("AI response is invalid")))
                    } else {
                        complete(request, previous, answer, token, data, config.saveConversations)
                        renderOutput(event.copy(text = answer, thinking = event.thinking + split.thinking))
                    }
                }
                is AiStreamEvent.Delta -> {
                    val part = reasoningStream.append(event.text)
                    renderOutput(event.copy(text = part.answer, thinking = event.thinking + part.thinking))
                }
                is AiStreamEvent.Failed, AiStreamEvent.Cancelled -> {
                    reasoningStream.clear()
                    renderOutput(event)
                    result = null
                }
                else -> renderOutput(event)
            }
        }
        delivery = sink
        renderOutput(AiStreamEvent.Started)
        try {
            val policy = AiEditorPolicy.generationPolicy(config.enabled, config.networkAllowed, editorPrivacy())
            handle = coordinator.submit(request, policy, sink::offer)
        } catch (_: Exception) {
            fail()
        }
    }

    /** The UI cannot supply arbitrary text or revive a result from an older editor. */
    fun consumeResult(
        includeThinking: Boolean = false,
        displayedResult: String? = null,
        selectedSections: List<Int>? = null,
    ): String? {
        reconcilePersistence()
        if (!allowed() || !contextCurrent() || !dataGeneration.isCurrent(resultDataGeneration)) return null
        if (displayedResult != null && displayedResult != result) return null
        if (selectedSections != null && selectedSections.isEmpty()) return null
        // Reasoning is presentation-only and can never be inserted.
        val source = result
        val insertable = when {
            source == null -> null
            selectedSections == null -> source
            else -> AiResultSections.select(source, selectedSections)
        } ?: return null
        result = null
        return insertable
    }

    fun splitResult(value: String): AiThinkingText.Split = AiThinkingText.split(value)

    private fun complete(request: AiRequest, previous: AiConversation?, text: String,
        token: Long, data: Long, save: Boolean) {
        result = text
        resultDataGeneration = data
        // Network context has a smaller budget than stored conversation history.
        // Appending to that projection would silently discard earlier messages.
        val history = (previous?.messages.orEmpty() + AiMessage(AiRole.USER, request.input) + AiMessage(AiRole.ASSISTANT, text))
            .takeLast(AiLimits.MAX_HISTORY_MESSAGES)
        val conversation = if (previous == null) {
            AiConversation(title = conversationTitle(request.input), messages = history)
        } else previous.copy(messages = history, updatedAtEpochMillis = System.currentTimeMillis())
        selected = conversation
        context.append(history, (previous?.messages.orEmpty().size + 2 - history.size).coerceAtLeast(0))
        renderContext(context.state)
        showConversation(conversation)
        if (save) storage({ current -> conversations.upsert(conversation, current) }, { refreshConversations() }, token, data)
    }

    private fun <T> storage(
        work: (() -> Boolean) -> T,
        deliver: (T) -> Unit,
        token: Long = generation.get(),
        data: Long = dataGeneration.current(),
        historyRead: Boolean = false,
        onFailure: () -> Unit = {},
    ) {
        val config = configuration()
        val current = { generation.get() == token && dataGeneration.isCurrent(data) && contextCurrent() &&
            config?.saveConversations == true && configuration() == config }
        try {
            executor.execute {
                if (!current()) return@execute
                val value = runCatching { work(current) }
                post {
                    reconcilePersistence()
                    if (current() && allowed()) value.fold(deliver) {
                        reportStorageFailure(historyRead)
                        onFailure()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            if (current() && allowed()) {
                reportStorageFailure(historyRead)
                onFailure()
            }
        }
    }

    private fun reportStorageFailure(historyRead: Boolean) {
        if (historyRead) renderHistoryStatus(true, false, true)
        else storageFailed()
    }

    /** Provider/model/key changes revoke results and context even before an observer is delivered. */
    private fun reconcilePersistence() {
        val next = configuration()
        if (boundConfiguration != next) {
            boundConfiguration = next
            invalidate()
        }
    }

    private fun copyRequest(request: AiRequest): AiRequest {
        val copies = mutableListOf<ByteArray>()
        try {
            return request.copy(attachments = request.attachments.map { attachment ->
                val bytes = attachment.bytes.copyOf().also(copies::add)
                AiAttachment(attachment.mimeType, bytes, attachment.displayName)
            })
        } catch (error: Exception) {
            copies.forEach { it.fill(0) }
            throw error
        }
    }

    private fun fail(error: AiProviderError = AiProviderError.Policy("AI operation unavailable")) {
        result = null
        renderOutput(AiStreamEvent.Failed(error))
    }

    private fun renderOutput(event: AiStreamEvent) {
        outputNeedsReset = event != AiStreamEvent.Cancelled
        render(event)
    }

    private fun showConversation(value: AiConversation?) {
        outputNeedsReset = value != null
        renderConversation(value)
    }

    private fun conversationTitle(input: String): String {
        val title = input.trim()
        var end = minOf(title.length, AiLimits.MAX_TITLE_CHARS)
        if (end < title.length && Character.isHighSurrogate(title[end - 1]) &&
            Character.isLowSurrogate(title[end])) end--
        return title.substring(0, end)
    }

}
