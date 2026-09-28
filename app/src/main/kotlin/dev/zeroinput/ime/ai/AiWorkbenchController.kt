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
) {
    private val generation = AtomicLong()
    private var selected: AiConversation? = null
    private var result: String? = null
    private var handle: AiRequestHandle? = null
    private var delivery: AiStreamDelivery? = null
    private var resultDataGeneration = -1L

    fun invalidate() {
        stop()
        selected = null
        renderConversation(null)
        renderList(emptyList())
    }

    fun stop() {
        generation.incrementAndGet()
        delivery?.close()
        delivery = null
        handle?.cancel()
        handle = null
        result = null
        render(AiStreamEvent.Cancelled)
    }

    fun newConversation() {
        stop()
        selected = null
        renderConversation(null)
    }

    fun refreshConversations() {
        if (!allowed() || configuration()?.saveConversations != true) {
            renderList(emptyList())
            return
        }
        storage({ current -> conversations.listSummaries(current) }, renderList)
    }

    fun selectConversation(id: String) {
        if (!allowed() || configuration()?.saveConversations != true) return
        newConversation()
        storage({ current -> conversations.find(id, current) }, deliver = {
            selected = it
            renderConversation(it)
        })
    }

    fun deleteConversation(id: String) {
        if (!allowed() || configuration()?.saveConversations != true) return
        stop()
        if (selected?.id == id) {
            selected = null
            renderConversation(null)
        }
        storage({ current -> conversations.delete(id, current) }, deliver = { refreshConversations() })
    }

    fun submit(action: AiAction, input: String, target: String?) {
        stop()
        val config = configuration()
        if (!allowed() || config == null || !config.enabled || !config.networkAllowed) {
            fail()
            return
        }
        val previous = selected
        val request = try {
            AiRequest(previous?.id, action, input, target, boundedHistory(previous?.messages.orEmpty()))
        } catch (_: IllegalArgumentException) {
            fail()
            return
        }
        val token = generation.get()
        val data = dataGeneration.current()
        val sink = AiStreamDelivery(post) { event ->
            if (generation.get() != token || !dataGeneration.isCurrent(data) || !allowed()) return@AiStreamDelivery
            when (event) {
                is AiStreamEvent.Completed -> {
                    // A provider implementation can emit a terminal event
                    // without going through the bounded SSE reader. Reject
                    // malformed/empty output before exposing it as a usable
                    // result or persisting it.
                    if (event.text.isBlank() || event.text.length > AiLimits.MAX_OUTPUT_CHARS) {
                        result = null
                        render(AiStreamEvent.Failed(AiProviderError.Response("AI response is invalid")))
                    } else {
                        render(event)
                        complete(request, previous, event.text, token, data, config.saveConversations)
                    }
                }
                is AiStreamEvent.Failed, AiStreamEvent.Cancelled -> {
                    render(event)
                    result = null
                }
                else -> render(event)
            }
        }
        delivery = sink
        render(AiStreamEvent.Started)
        try {
            handle = coordinator.submit(request, AiGenerationPolicy(true, true, false), sink::offer)
        } catch (_: Exception) {
            fail()
        }
    }

    /** The UI cannot supply arbitrary text or revive a result from an older editor. */
    fun consumeResult(): String? {
        if (!allowed() || !dataGeneration.isCurrent(resultDataGeneration)) return null
        return result.also { result = null }
    }

    private fun complete(request: AiRequest, previous: AiConversation?, text: String, token: Long, data: Long, save: Boolean) {
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
        renderConversation(conversation)
        if (save) storage({ current -> conversations.upsert(conversation, current) }, { refreshConversations() }, token, data)
    }

    private fun <T> storage(
        work: (() -> Boolean) -> T,
        deliver: (T) -> Unit,
        token: Long = generation.get(),
        data: Long = dataGeneration.current(),
    ) {
        val current = { generation.get() == token && dataGeneration.isCurrent(data) }
        try {
            executor.execute {
                if (!current()) return@execute
                val value = runCatching { work(current) }
                post {
                    if (current() && allowed()) value.fold(deliver) { fail() }
                }
            }
        } catch (_: RejectedExecutionException) { fail() }
    }

    private fun fail() {
        result = null
        render(AiStreamEvent.Failed(AiProviderError.Policy("AI operation unavailable")))
    }

    private fun conversationTitle(input: String): String {
        val title = input.trim()
        var end = minOf(title.length, AiLimits.MAX_TITLE_CHARS)
        if (end < title.length && Character.isHighSurrogate(title[end - 1]) &&
            Character.isLowSurrogate(title[end])) end--
        return title.substring(0, end)
    }

    private fun boundedHistory(messages: List<AiMessage>): List<AiMessage> {
        var remaining = 16_384
        return messages.takeLast(12).asReversed().takeWhile {
            remaining -= it.content.length
            remaining >= 0
        }.asReversed()
    }
}
