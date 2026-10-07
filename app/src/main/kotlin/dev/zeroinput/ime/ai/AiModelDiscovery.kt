package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.*
import dev.zeroinput.userdata.AiConfiguration
import java.util.concurrent.atomic.AtomicBoolean

/** One owner-thread discovery session; no editor, history or persistence ports. */
internal class AiModelDiscovery(
    private val provider: (AiConfiguration) -> AiModelCatalog,
    private val current: () -> Boolean,
    private val post: (() -> Unit) -> Boolean,
    private val render: (AiModelCatalogEvent) -> Unit,
) : AutoCloseable {
    private var generation = 0L
    private var handle: AiRequestHandle? = null

    fun start(configuration: AiConfiguration) {
        close()
        if (!current() || !configuration.enabled || !configuration.networkAllowed) {
            render(AiModelCatalogEvent.Failed(AiProviderError.Policy("AI network is disabled")))
            return
        }
        val token = generation
        val ended = AtomicBoolean()
        render(AiModelCatalogEvent.Started)
        try {
            val request = provider(configuration.copy(timeoutMs = minOf(configuration.timeoutMs, 30_000L)))
                .fetchModels { event ->
                    if (event != AiModelCatalogEvent.Started && ended.compareAndSet(false, true)) {
                        post { if (token == generation && current()) render(event) }
                    }
                }
            if (token == generation && current()) handle = request else request.cancel()
        } catch (_: Exception) {
            if (token == generation && current())
                render(AiModelCatalogEvent.Failed(AiProviderError.Network("AI model discovery unavailable")))
        }
    }

    fun cancel() { close(); render(AiModelCatalogEvent.Cancelled) }
    override fun close() { generation++; handle?.cancel(); handle = null }
}
