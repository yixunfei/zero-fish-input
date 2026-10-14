package dev.zeroinput.ime.ai

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zeroinput.ai.api.*
import dev.zeroinput.userdata.AiConfiguration
import dev.zeroinput.userdata.AiProviderProfile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.*

/** Runs production routing/serialization/decoding on Android with a deterministic I/O boundary. */
@RunWith(AndroidJUnit4::class)
class AiProtocolPlatformTest {
    @Test fun allProtocolsGenerateWithAndroidJsonInBothResponseModes() {
        for (protocol in AiProtocol.entries) for (streaming in listOf(false, true)) Fixture(protocol, streaming).use { f ->
            val event = f.generate()
            assertEquals("OK", (event as AiStreamEvent.Completed).text)
            assertEquals("/proxy/v1/${protocol.route}", f.path)
            assertEquals(protocol, f.exchange.protocol)
            val body = JSONObject(f.exchange.written.toString("UTF-8"))
            assertEquals(streaming, body.getBoolean("stream"))
            if (protocol == AiProtocol.RESPONSES) assertFalse(body.getBoolean("store"))
            assertEquals(1, f.connections)
        }
    }

    @Test fun catalog404AndGeneration404RemainSeparateAndNeverRetryOrReadErrorBodies() {
        Fixture(AiProtocol.CHAT_COMPLETIONS, true).use { f ->
            f.exchange.code = 404
            val catalog = LinkedBlockingQueue<AiModelCatalogEvent>()
            f.provider.fetchModels { if (it != AiModelCatalogEvent.Started) catalog.offer(it) }
            val catalogError = (catalog.poll(5, TimeUnit.SECONDS) as AiModelCatalogEvent.Failed).error as AiProviderError.Network
            assertEquals(AiNetworkOperation.MODELS, catalogError.operation)
            assertEquals("/proxy/v1/models", f.path)
            val generation = (f.generate() as AiStreamEvent.Failed).error as AiProviderError.Network
            assertEquals(AiNetworkOperation.GENERATION, generation.operation)
            assertEquals(404, generation.statusCode)
            assertEquals(2, f.connections)
            assertEquals(0, f.exchange.reads)
        }
    }

    @Test fun queuedProtocolChangesCancelWithoutSendingTheOldRequest() {
        Fixture(AiProtocol.CHAT_COMPLETIONS, true).use { f ->
            val gate = CountDownLatch(1)
            f.executor.submit { gate.await() }
            val result = LinkedBlockingQueue<AiStreamEvent>()
            f.provider.stream(AiRequest(null, AiAction.ASK, "public fixture")) { result.offer(it) }
            f.config = f.config.copy(providers = listOf(f.profile.copy(protocol = AiProtocol.RESPONSES)))
            gate.countDown()
            assertEquals(AiStreamEvent.Cancelled, result.poll(5, TimeUnit.SECONDS))
            assertEquals(0, f.connections)
        }
    }

    @Test fun truncatedOutputAndRedirectsNeverBecomeInsertable() {
        for (protocol in AiProtocol.entries) Fixture(protocol, false).use { f ->
            f.exchange.response = f.exchange.response.replace("stop", "length")
                .replace("completed", "incomplete").replace("end_turn", "max_tokens")
            // The Anthropic field name is stop_reason, not length_reason.
            if (protocol == AiProtocol.ANTHROPIC_MESSAGES)
                f.exchange.response = """{"type":"message","role":"assistant","stop_reason":"max_tokens","content":[{"type":"text","text":"partial"}]}"""
            assertTrue(f.generate() is AiStreamEvent.Failed)
            f.exchange.code = 302
            val failed = (f.generate() as AiStreamEvent.Failed).error as AiProviderError.Network
            assertEquals(AiNetworkFailure.REDIRECT, failed.reason)
        }
    }

    private class Fixture(protocol: AiProtocol, streaming: Boolean) : AutoCloseable {
        val executor = Executors.newSingleThreadExecutor()
        val profile = AiProviderProfile("public", "Public", "https://example.test/proxy/v1", "public-key",
            listOf("fixture"), "fixture", protocol = protocol, streaming = streaming)
        @Volatile var config = AiConfiguration(enabled = true, networkAllowed = true,
            providers = listOf(profile), selectedProviderId = profile.id)
        var path: String? = null
        var connections = 0
        val exchange = Exchange(protocol, streaming)
        val provider = OpenAiCompatibleProvider({ config }, executor, exchangeFactory = {
            path = it.path; connections++; exchange
        })
        fun generate(): AiStreamEvent {
            val results = LinkedBlockingQueue<AiStreamEvent>()
            provider.stream(AiRequest(null, AiAction.ASK, "public fixture")) {
                if (it is AiStreamEvent.Completed || it is AiStreamEvent.Failed || it == AiStreamEvent.Cancelled) results.offer(it)
            }
            val event = checkNotNull(results.poll(5, TimeUnit.SECONDS))
            executor.submit {}.get(5, TimeUnit.SECONDS)
            return event
        }
        override fun close() { executor.shutdownNow() }
    }

    private class Exchange(format: AiProtocol, val streaming: Boolean) : AiTransportExchange {
        var protocol: AiProtocol? = null
        var code = 200
        var reads = 0
        val written = ByteArrayOutputStream()
        private val json = when (format) {
            AiProtocol.CHAT_COMPLETIONS -> """{"choices":[{"message":{"content":"OK"},"finish_reason":"stop"}]}"""
            AiProtocol.RESPONSES -> """{"status":"completed","error":null,"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"OK"}]}]}"""
            AiProtocol.ANTHROPIC_MESSAGES -> """{"type":"message","role":"assistant","stop_reason":"end_turn","content":[{"type":"text","text":"OK"}]}"""
        }
        var response = if (!streaming) json else when (format) {
            AiProtocol.CHAT_COMPLETIONS -> "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            AiProtocol.RESPONSES -> "data: {\"type\":\"response.completed\",\"response\":$json}\n\n"
            AiProtocol.ANTHROPIC_MESSAGES -> listOf(
                """{"type":"message_start","message":{"role":"assistant","content":[]}}""",
                """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"OK"}}""",
                """{"type":"content_block_stop","index":0}""",
                """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
                """{"type":"message_stop"}""",
            ).joinToString("") { "data: $it\n\n" }
        }
        override fun prepare(method: String, key: String, accept: String, timeoutMs: Int, bodySize: Int?, protocol: AiProtocol) {
            this.protocol = protocol
            assertEquals("public-key", key)
            written.reset()
        }
        override fun timeout(timeoutMs: Int) = Unit
        override fun connect() = Unit
        override fun output() = written
        override fun status() = code
        override fun contentType() = if (streaming) "text/event-stream" else "application/json"
        override fun input() = ByteArrayInputStream(response.toByteArray()).also { reads++ }
        override fun disconnect() = Unit
    }
}
