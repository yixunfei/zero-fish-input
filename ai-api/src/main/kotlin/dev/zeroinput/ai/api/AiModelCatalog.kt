package dev.zeroinput.ai.api

/** Discovery reports identifiers only; media capabilities require explicit local configuration. */
sealed interface AiModelCatalogEvent {
    data object Started : AiModelCatalogEvent
    data class Completed(val models: List<String>) : AiModelCatalogEvent
    data class Failed(val error: AiProviderError) : AiModelCatalogEvent
    data object Cancelled : AiModelCatalogEvent
}

interface AiModelCatalog {
    fun fetchModels(listener: (AiModelCatalogEvent) -> Unit): AiRequestHandle
}
