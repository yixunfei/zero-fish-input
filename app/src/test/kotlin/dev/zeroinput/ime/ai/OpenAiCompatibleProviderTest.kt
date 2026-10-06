package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiAction
import dev.zeroinput.ai.api.AiAttachment
import dev.zeroinput.ai.api.AiRequest
import dev.zeroinput.ai.api.AiProviderError
import dev.zeroinput.ai.api.AiStreamEvent
import dev.zeroinput.userdata.AiConfiguration
import dev.zeroinput.userdata.AiProviderProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class OpenAiCompatibleProviderTest {
    @Test fun serviceErrorsAreClassifiedWithoutReadingServerBodies() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val provider = OpenAiCompatibleProvider({ AiConfiguration() }, executor)
            for ((status, reason) in mapOf(401 to dev.zeroinput.ai.api.AiNetworkFailure.AUTHENTICATION,
                403 to dev.zeroinput.ai.api.AiNetworkFailure.AUTHENTICATION,
                404 to dev.zeroinput.ai.api.AiNetworkFailure.MODEL_OR_ENDPOINT,
                429 to dev.zeroinput.ai.api.AiNetworkFailure.RATE_LIMIT,
                503 to dev.zeroinput.ai.api.AiNetworkFailure.SERVICE)) {
                assertEquals(reason, (provider.responseError(status) as AiProviderError.Network).reason)
            }
            assertTrue(provider.responseError(400) is AiProviderError.Configuration)
            val body = JSONObject(provider.requestBody(AiRequest(null, AiAction.ASK, "Reply with OK.",
                outputTokenLimit = 16), "fixture-model"))
            assertEquals(16, body.getInt("max_tokens"))
        } finally { executor.shutdownNow() }
    }

    @Test
    fun plaintextEndpointIsRejectedBeforeNetworkAccess() {
        assertConfigurationFailure(configuration(endpoint = "http://example.test"))
    }

    @Test
    fun missingApiKeyIsRejectedBeforeNetworkAccess() {
        assertConfigurationFailure(configuration(apiKey = ""))
    }

    @Test
    fun malformedHttpsEndpointIsRejectedBeforeNetworkAccess() {
        assertConfigurationFailure(configuration(endpoint = "https://"))
    }

    @Test
    fun endpointQueryFragmentAndUserInfoAreRejected() {
        for (endpoint in listOf(
            "https://example.test/v1/chat?token=fixture",
            "https://example.test/v1/chat#fragment",
            "https://user:pass@example.test/v1/chat",
            "https://example.test:0/v1/chat",
        )) {
            assertConfigurationFailure(configuration(endpoint = endpoint))
        }
    }

    @Test
    fun oversizedEndpointAndKeyAreRejectedBeforeNetworkAccess() {
        assertConfigurationFailure(configuration(endpoint = "https://example.test/" + "x".repeat(512)))
        assertConfigurationFailure(configuration(apiKey = "k".repeat(513)))
    }

    @Test
    fun selectedModelWithoutImageCapabilityIsRejectedBeforeNetworkAccess() {
        val profile = AiProviderProfile("one", "Fixture", "https://example.test/v1/chat/completions",
            "key", listOf("text", "vision"), "text", imageModels = setOf("vision"))
        val config = AiConfiguration(enabled = true, networkAllowed = true,
            providers = listOf(profile), selectedProviderId = profile.id)
        val request = AiRequest(null, AiAction.ASK, "describe",
            attachments = listOf(AiAttachment("image/png", byteArrayOf(1, 2, 3), "picture.png")))
        assertConfigurationFailure(config, request)
    }

    @Test
    fun imageAndTextAttachmentsUseBoundedChatContentParts() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val provider = OpenAiCompatibleProvider({ AiConfiguration() }, executor)
            val request = AiRequest(null, AiAction.ASK, "describe", attachments = listOf(
                AiAttachment("text/plain", "fixture".toByteArray(), "notes.txt"),
                AiAttachment("image/png", byteArrayOf(1, 2, 3), "picture.png"),
            ))
            val root = JSONObject(provider.requestBody(request, "vision"))
            val parts = root.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
            assertEquals("text", parts.getJSONObject(1).getString("type"))
            assertEquals("image_url", parts.getJSONObject(2).getString("type"))
            assertTrue(parts.getJSONObject(2).getJSONObject("image_url").getString("url")
                .startsWith("data:image/png;base64,"))
        } finally { executor.shutdownNow() }
    }

    @Test
    fun rejectedQueueAndCancelledQueuedRequestWipeAttachmentCopies() {
        val stopped = Executors.newSingleThreadExecutor().apply { shutdownNow() }
        val rejected = AiAttachment("text/plain", "fixture".toByteArray(), "note.txt")
        OpenAiCompatibleProvider({ AiConfiguration() }, stopped).stream(
            AiRequest(null, AiAction.ASK, "fixture", attachments = listOf(rejected))) {}
        assertTrue(rejected.bytes.all { it == 0.toByte() })

        val executor = Executors.newSingleThreadExecutor()
        val gate = CountDownLatch(1)
        executor.submit { gate.await() }
        try {
            val queued = AiAttachment("text/plain", "fixture".toByteArray(), "note.txt")
            val handle = OpenAiCompatibleProvider({ AiConfiguration() }, executor).stream(
                AiRequest(null, AiAction.ASK, "fixture", attachments = listOf(queued))) {}
            handle.cancel()
            assertTrue(queued.bytes.all { it == 0.toByte() })
        } finally { gate.countDown(); executor.shutdownNow() }
    }

    private fun assertConfigurationFailure(configuration: AiConfiguration,
        request: AiRequest = AiRequest(null, AiAction.ASK, "hello")) {
        val executor = Executors.newSingleThreadExecutor()
        val provider = OpenAiCompatibleProvider({ configuration }, executor)
        val event = AtomicReference<AiStreamEvent>()
        val completed = CountDownLatch(1)
        try {
            provider.stream(request) {
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

    private fun configuration(
        endpoint: String = "https://example.test/v1/chat/completions",
        apiKey: String = "key",
        model: String = "text",
    ): AiConfiguration {
        val profile = AiProviderProfile(
            id = "fixture",
            name = "Fixture",
            endpoint = endpoint,
            apiKey = apiKey,
            models = listOf(model),
            selectedModel = model,
        )
        return AiConfiguration(
            enabled = true,
            networkAllowed = true,
            providers = listOf(profile),
            selectedProviderId = profile.id,
        )
    }
}
