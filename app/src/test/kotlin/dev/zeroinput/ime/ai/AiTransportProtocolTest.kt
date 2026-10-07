package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.*
import dev.zeroinput.userdata.AiConfiguration
import dev.zeroinput.userdata.AiProviderProfile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.*

class AiTransportProtocolTest {
    @Test fun quickChoiceUsesBoundProviderModelsAndItsOwnAttachmentCapabilities() {
        Fixture().use { f ->
            assertTrue(f.stream(AiRequest(null, AiAction.ASK, "fixture", model = "model-one")).last() is AiStreamEvent.Completed)
            assertEquals("model-one", JSONObject(f.exchange.written.toString("UTF-8")).getString("model"))
            assertEquals("model-two", f.config.activeModel())
        }
        Fixture().use { f ->
            assertTrue(f.stream(AiRequest(null, AiAction.ASK, "fixture", model = "unknown")).last() is AiStreamEvent.Failed)
            assertNull(f.uri)
        }
        Fixture().use { f ->
            f.config = f.config.copy(providers = listOf(f.profile.copy(imageModels = setOf("model-two"))))
            val image = AiAttachment("image/png", byteArrayOf(1), "public.png")
            assertTrue(f.stream(AiRequest(null, AiAction.ASK, "fixture", attachments = listOf(image),
                model = "model-one")).last() is AiStreamEvent.Failed)
            assertNull(f.uri)
            assertEquals(0, image.bytes.single().toInt())
        }
        Fixture().use { f ->
            f.config = f.config.copy(providers = listOf(f.profile.copy(imageModels = setOf("model-one"))))
            val image = AiAttachment("image/png", byteArrayOf(1), "public.png")
            assertTrue(f.stream(AiRequest(null, AiAction.ASK, "fixture", attachments = listOf(image),
                model = "model-one")).last() is AiStreamEvent.Completed)
        }
    }

    @Test fun baseAddressSendsAuthenticatedChatAndRetainsMultiTurnMessages() {
        Fixture().use { f ->
            val events = f.stream(AiRequest(null, AiAction.ASK, "follow up", history = listOf(
                AiMessage(AiRole.USER, "first"), AiMessage(AiRole.ASSISTANT, "answer"))))
            assertEquals("/v1/chat/completions", f.uri?.path)
            assertEquals("POST", f.exchange.method)
            assertEquals("fixture-key", f.exchange.key)
            assertEquals("OK", (events.last() as AiStreamEvent.Completed).text)
            val body = JSONObject(f.exchange.written.toString("UTF-8"))
            assertEquals("model-two", body.getString("model"))
            assertEquals(4, body.getJSONArray("messages").length())
            assertTrue(f.exchange.disconnected)
        }
    }

    @Test fun catalogFetchRequiresNoSelectedModelAndNeverSendsDraftOrGenerationRequest() {
        Fixture().use { f ->
            f.config = f.config.copy(providers = listOf(f.profile.copy(models = emptyList(), selectedModel = "")))
            f.exchange.type = "application/json; charset=utf-8"
            f.exchange.response = """{"data":[{"id":"model-two"},{"id":"model-one"},{"id":"model-two"}]}"""
            val done = CountDownLatch(1)
            val events = CopyOnWriteArrayList<AiModelCatalogEvent>()
            f.provider.fetchModels { events += it; if (it != AiModelCatalogEvent.Started) done.countDown() }
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertEquals("/v1/models", f.uri?.path)
            assertEquals("GET", f.exchange.method)
            assertEquals(0, f.exchange.written.size())
            assertEquals(listOf("model-one", "model-two"), (events.last() as AiModelCatalogEvent.Completed).models)
        }
    }

    @Test fun htmlSuccessAndAuthenticationFailureNeverBecomeAvailable() {
        for ((status, type) in listOf(200 to "text/html", 401 to "application/json", 302 to "text/html")) {
            Fixture().use { f ->
                f.exchange.code = status; f.exchange.type = type
                f.exchange.response = "secret error body"
                val error = (f.stream().last() as AiStreamEvent.Failed).error
                assertFalse(error.toString().contains("secret"))
                assertEquals(0, f.exchange.reads)
                if (status == 401) assertEquals(AiNetworkFailure.AUTHENTICATION, (error as AiProviderError.Network).reason)
            }
        }
    }

    @Test fun nonStreamingJsonCompletionIsAcceptedButPartialAnswersAreRejected() {
        for (reason in listOf("stop", "length", "tool_calls")) {
            Fixture().use { f ->
                f.exchange.type = "application/json"
                f.exchange.response = """{"choices":[{"message":{"content":"OK"},"finish_reason":"$reason"}]}"""
                val event = f.stream().last()
                if (reason == "stop") assertEquals("OK", (event as AiStreamEvent.Completed).text)
                else assertTrue(event is AiStreamEvent.Failed)
            }
        }
    }

    @Test fun queuedRequestCannotUseReplacementCredentials() {
        Fixture().use { f ->
            val gate = CountDownLatch(1)
            f.executor.submit { gate.await() }
            val done = CountDownLatch(1)
            val events = CopyOnWriteArrayList<AiStreamEvent>()
            f.provider.stream(AiRequest(null, AiAction.ASK, "fixture")) { events += it; done.countDown() }
            f.config = f.config.copy(providers = listOf(f.profile.copy(apiKey = "new-key")))
            gate.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertEquals(listOf(AiStreamEvent.Cancelled), events)
            assertNull(f.uri)
        }
    }

    @Test fun deadlineExpiresWhileQueuedAndCancellationDoesNotRemoveOtherRequests() {
        Fixture().use { f ->
            val gate = CountDownLatch(1)
            f.executor.submit { gate.await() }
            var timeout: (() -> Unit)? = null
            val provider = OpenAiCompatibleProvider({ f.config }, f.executor, exchangeFactory = { f.exchange },
                scheduleTimeout = { _, action -> timeout = action; AutoCloseable {} })
            val events = CopyOnWriteArrayList<AiStreamEvent>()
            provider.stream(AiRequest(null, AiAction.ASK, "fixture"), events::add)
            val other = f.executor.submit(Callable { "still queued" })
            checkNotNull(timeout).invoke()
            gate.countDown()
            assertEquals("still queued", other.get(3, TimeUnit.SECONDS))
            assertEquals(1, events.size)
            assertEquals(AiNetworkFailure.TIMEOUT, ((events.single() as AiStreamEvent.Failed).error as AiProviderError.Network).reason)
        }
    }

    private class Fixture : AutoCloseable {
        val executor: ExecutorService = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8))
        val profile = AiProviderProfile("fixture", "Fixture", "https://example.test/v1", "fixture-key",
            listOf("model-one", "model-two"), "model-two")
        @Volatile var config = AiConfiguration(enabled = true, networkAllowed = true,
            providers = listOf(profile), selectedProviderId = profile.id)
        val exchange = Exchange()
        var uri: URI? = null
        val provider = OpenAiCompatibleProvider({ config }, executor, exchangeFactory = { uri = it; exchange })
        fun stream(request: AiRequest = AiRequest(null, AiAction.ASK, "fixture")): List<AiStreamEvent> {
            val done = CountDownLatch(1)
            val events = CopyOnWriteArrayList<AiStreamEvent>()
            provider.stream(request) {
                events += it
                if (it is AiStreamEvent.Completed || it is AiStreamEvent.Failed || it == AiStreamEvent.Cancelled) done.countDown()
            }
            assertTrue(done.await(3, TimeUnit.SECONDS))
            executor.submit {}.get(3, TimeUnit.SECONDS)
            return events
        }
        override fun close() { executor.shutdownNow() }
    }

    private class Exchange : AiTransportExchange {
        var type = "text/event-stream"
        var code = 200
        var response = "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        var method = ""
        var key = ""
        var reads = 0
        var disconnected = false
        val written = ByteArrayOutputStream()
        override fun prepare(method: String, key: String, accept: String, timeoutMs: Int, bodySize: Int?) {
            this.method = method; this.key = key
            assertTrue(timeoutMs > 0)
            assertEquals(method == "POST", bodySize != null)
        }
        override fun timeout(timeoutMs: Int) { assertTrue(timeoutMs > 0) }
        override fun connect() = Unit
        override fun output() = written
        override fun status() = code
        override fun contentType() = type
        override fun input() = ByteArrayInputStream(response.toByteArray()).also { reads++ }
        override fun disconnect() { disconnected = true }
    }
}
