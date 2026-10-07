package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.*
import dev.zeroinput.userdata.AiConfiguration
import org.junit.Assert.*
import org.junit.Test

class AiModelDiscoveryTest {
    @Test fun disabledNetworkingCannotConstructProvider() {
        val events = mutableListOf<AiModelCatalogEvent>()
        AiModelDiscovery({ error("Unexpected request") }, { true }, { it(); true }, events::add).start(AiConfiguration())
        assertTrue((events.single() as AiModelCatalogEvent.Failed).error is AiProviderError.Policy)
    }
    @Test fun cancelledRevokedAndDuplicateResultsCannotReplaceTheCurrentList() {
        var callback: (AiModelCatalogEvent) -> Unit = {}
        val queue = mutableListOf<() -> Unit>()
        val events = mutableListOf<AiModelCatalogEvent>()
        var current = true
        var cancels = 0
        val discovery = AiModelDiscovery({ object : AiModelCatalog {
            override fun fetchModels(listener: (AiModelCatalogEvent) -> Unit): AiRequestHandle {
                callback = listener
                return object : AiRequestHandle { override fun cancel() { cancels++ } }
            }
        } }, { current }, { queue += it; true }, events::add)
        val config = AiConfiguration(enabled = true, networkAllowed = true)
        discovery.start(config)
        callback(AiModelCatalogEvent.Completed(listOf("one")))
        callback(AiModelCatalogEvent.Completed(listOf("duplicate")))
        assertEquals(1, queue.size)
        discovery.cancel()
        queue.forEach { it() }; queue.clear()
        assertFalse(events.any { it is AiModelCatalogEvent.Completed })
        assertEquals(1, cancels)
        discovery.start(config)
        callback(AiModelCatalogEvent.Completed(listOf("stale")))
        current = false
        queue.forEach { it() }
        assertFalse(events.any { it is AiModelCatalogEvent.Completed })
        discovery.close()
    }
}
