package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiAction
import dev.zeroinput.ai.api.AiRequest
import dev.zeroinput.ai.api.AiProviderError
import dev.zeroinput.ai.api.AiStreamEvent
import dev.zeroinput.userdata.AiConfiguration
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class OpenAiCompatibleProviderTest {
    @Test
    fun plaintextEndpointIsRejectedBeforeNetworkAccess() {
        assertConfigurationFailure(AiConfiguration(endpoint = "http://example.test", apiKey = "key"))
    }

    @Test
    fun missingApiKeyIsRejectedBeforeNetworkAccess() {
        assertConfigurationFailure(AiConfiguration(apiKey = ""))
    }

    @Test
    fun malformedHttpsEndpointIsRejectedBeforeNetworkAccess() {
        assertConfigurationFailure(AiConfiguration(endpoint = "https://", apiKey = "key"))
    }

    @Test
    fun endpointQueryFragmentAndUserInfoAreRejected() {
        for (endpoint in listOf(
            "https://example.test/v1/chat?token=fixture",
            "https://example.test/v1/chat#fragment",
            "https://user:pass@example.test/v1/chat",
            "https://example.test:0/v1/chat",
        )) {
            assertConfigurationFailure(AiConfiguration(endpoint = endpoint, apiKey = "key"))
        }
    }

    @Test
    fun oversizedEndpointAndKeyAreRejectedBeforeNetworkAccess() {
        assertConfigurationFailure(AiConfiguration(endpoint = "https://example.test/" + "x".repeat(512), apiKey = "key"))
        assertConfigurationFailure(AiConfiguration(apiKey = "k".repeat(513)))
    }

    private fun assertConfigurationFailure(configuration: AiConfiguration) {
        val executor = Executors.newSingleThreadExecutor()
        val provider = OpenAiCompatibleProvider({ configuration }, executor)
        val event = AtomicReference<AiStreamEvent>()
        val completed = CountDownLatch(1)
        try {
            provider.stream(AiRequest(null, AiAction.ASK, "hello")) {
                event.set(it)
                completed.countDown()
            }
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            val failure = event.get() as? AiStreamEvent.Failed
            assertTrue(failure?.error is AiProviderError.Configuration)
        } finally {
            executor.shutdownNow()
        }
    }
}
