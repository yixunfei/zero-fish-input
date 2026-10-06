package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.*
import dev.zeroinput.userdata.AiConfiguration

internal sealed interface AiProbeState {
    data object Running : AiProbeState
    data object Available : AiProbeState
    data object Cancelled : AiProbeState
    data class Failed(val error: AiProviderError) : AiProbeState
}

/** Owner-thread probe with no draft, history, storage or editor ports. */
internal class AiProviderProbe(
    private val provider: (AiConfiguration) -> AiProvider,
    private val current: () -> Boolean,
    private val post: (() -> Unit) -> Boolean,
    private val render: (AiProbeState) -> Unit,
) : AutoCloseable {
    private var generation = 0L
    private var handle: AiRequestHandle? = null
    private var delivery: AiStreamDelivery? = null

    fun start(configuration: AiConfiguration) {
        close()
        if (!current() || !configuration.enabled || !configuration.networkAllowed) {
            render(AiProbeState.Failed(AiProviderError.Policy("AI network is disabled")))
            return
        }
        val token = generation
        val sink = AiStreamDelivery(post) { event ->
            if (token != generation || !current()) return@AiStreamDelivery
            when (event) {
                is AiStreamEvent.Completed -> render(if (event.text.isNotBlank() && event.text.length <= AiLimits.MAX_OUTPUT_CHARS)
                    AiProbeState.Available else AiProbeState.Failed(AiProviderError.Response("Empty probe response")))
                is AiStreamEvent.Failed -> render(AiProbeState.Failed(event.error))
                AiStreamEvent.Cancelled -> render(AiProbeState.Cancelled)
                else -> Unit
            }
        }
        delivery = sink
        render(AiProbeState.Running)
        try {
            handle = provider(configuration.copy(timeoutMs = minOf(configuration.timeoutMs, 15_000L))).stream(
                AiRequest(null, AiAction.ASK, "Reply with OK.", outputTokenLimit = 16), sink::offer)
        } catch (_: Exception) {
            sink.close()
            render(AiProbeState.Failed(AiProviderError.Network("AI probe could not start")))
        }
    }

    fun cancel() { close(); render(AiProbeState.Cancelled) }

    override fun close() {
        generation++
        delivery?.close()
        delivery = null
        handle?.cancel()
        handle = null
    }
}
