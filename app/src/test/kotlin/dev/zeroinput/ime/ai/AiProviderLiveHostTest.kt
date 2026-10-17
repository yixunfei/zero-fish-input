package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.*
import dev.zeroinput.userdata.AiConfiguration
import dev.zeroinput.userdata.AiProviderProfile
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Opt-in host acceptance of production transport code; does not establish Android TLS behavior. */
class AiProviderLiveHostTest {
    @Test fun realCatalogAndPublicGenerationRequireSeparateSuccess() {
        val key = System.getenv("ZEROINPUT_AI_TEST_KEY")
        assumeTrue("Explicit temporary test credential required", !key.isNullOrBlank())
        val endpoint = checkNotNull(System.getenv("ZEROINPUT_AI_TEST_ENDPOINT"))
        val model = checkNotNull(System.getenv("ZEROINPUT_AI_TEST_MODEL"))
        val profile = AiProviderProfile("public-fixture", "Public fixture", endpoint, checkNotNull(key), listOf(model), model)
        val config = AiConfiguration(enabled = true, networkAllowed = true, timeoutMs = 30_000,
            providers = listOf(profile), selectedProviderId = profile.id)
        val worker = Executors.newSingleThreadExecutor()
        val cancellation = Executors.newSingleThreadExecutor()
        try {
            val provider = OpenAiCompatibleProvider({ config }, worker, cancellation)
            val catalog = LinkedBlockingQueue<AiModelCatalogEvent>()
            provider.fetchModels { if (it != AiModelCatalogEvent.Started) catalog.offer(it) }
            val listed = catalog.poll(35, TimeUnit.SECONDS)
            assertTrue("Catalog did not succeed: " + safe((listed as? AiModelCatalogEvent.Failed)?.error),
                listed is AiModelCatalogEvent.Completed)
            assertTrue("Requested model absent from catalog", model in (listed as AiModelCatalogEvent.Completed).models)
            for (streaming in listOf(true, false)) {
                val snapshot = config.copy(providers = listOf(profile.copy(streaming = streaming)))
                val result = generate(OpenAiCompatibleProvider({ snapshot }, worker, cancellation))
                assertTrue("Generation did not succeed: " + safe((result as? AiStreamEvent.Failed)?.error),
                    result is AiStreamEvent.Completed)
                assertTrue((result as AiStreamEvent.Completed).text.isNotBlank())
            }
            val invalid = config.copy(providers = listOf(profile.copy(apiKey = "public-invalid-key")))
            val failure = generate(OpenAiCompatibleProvider({ invalid }, worker, cancellation))
            assertEquals(AiNetworkFailure.AUTHENTICATION,
                ((failure as? AiStreamEvent.Failed)?.error as? AiProviderError.Network)?.reason)
        } finally { worker.shutdownNow(); cancellation.shutdownNow() }
    }

    private fun generate(provider: AiProvider): AiStreamEvent? {
        val events = LinkedBlockingQueue<AiStreamEvent>()
        val handle = provider.stream(AiRequest(null, AiAction.ASK, "Reply with OK.", outputTokenLimit = 512)) {
            if (it is AiStreamEvent.Completed || it is AiStreamEvent.Failed || it == AiStreamEvent.Cancelled) events.offer(it)
        }
        try { return events.poll(35, TimeUnit.SECONDS) } finally { handle.cancel() }
    }

    private fun safe(error: AiProviderError?): String = when (error) {
        is AiProviderError.Network -> "${error.reason} HTTP ${error.statusCode ?: 0}"
        is AiProviderError.Response -> "response ${error.reason}"
        else -> "no successful terminal result"
    }
}
