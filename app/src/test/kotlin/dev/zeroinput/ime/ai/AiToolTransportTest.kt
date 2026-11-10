package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.*
import dev.zeroinput.userdata.AiConfiguration
import dev.zeroinput.userdata.AiProviderProfile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class AiToolTransportTest {
    @Test fun toolRequestsUseExistingTransportAndRejectDisabledOrRevokedConfiguration() {
        val executor = Executors.newSingleThreadExecutor()
        val profile = AiProviderProfile("fixture", "Fixture", "https://example.test/v1", "fixture-key", listOf("model"), "model")
        var config = AiConfiguration(enabled = true, networkAllowed = true, providers = listOf(profile), selectedProviderId = profile.id)
        var exchanges = 0
        val body = ByteArrayOutputStream()
        val provider = OpenAiCompatibleProvider({ config }, executor, exchangeFactory = { uri ->
            assertEquals("/v1/chat/completions", uri.path); exchanges++
            object : AiTransportExchange {
                override fun prepare(method: String, key: String, accept: String, timeoutMs: Int, bodySize: Int?, protocol: AiProtocol) {
                    assertEquals("POST", method); assertEquals("fixture-key", key)
                }
                override fun timeout(timeoutMs: Int) = Unit
                override fun connect() = Unit
                override fun output() = body
                override fun status() = 200
                override fun contentType() = "application/json"
                override fun input() = ByteArrayInputStream("""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"Public answer"}}]}""".toByteArray())
                override fun disconnect() = Unit
            }
        })
        val request = AiToolRequest("Public task", AiFileTools.definitions(emptyList()))
        try {
            fun execute(): Result<AiToolResponse> {
                val done = CountDownLatch(1); var response: Result<AiToolResponse>? = null
                provider.request(request) { response = it; done.countDown() }
                assertTrue(done.await(5, TimeUnit.SECONDS))
                executor.submit {}.get(5, TimeUnit.SECONDS)
                return checkNotNull(response)
            }
            assertEquals("Public answer", execute().getOrThrow().message)
            assertEquals(1, AiToolJson.objectValue(body.toString("UTF-8")).getJSONArray("tools").length())
            config = config.copy(networkAllowed = false)
            assertTrue(execute().isFailure); assertEquals(1, exchanges)
            config = config.copy(networkAllowed = true)
            val gate = CountDownLatch(1); executor.submit { gate.await() }
            val done = CountDownLatch(1); val results = CopyOnWriteArrayList<Result<AiToolResponse>>()
            provider.request(request) { results += it; done.countDown() }
            config = config.copy(providers = listOf(profile.copy(apiKey = "replacement-fixture")))
            gate.countDown(); assertTrue(done.await(5, TimeUnit.SECONDS))
            assertTrue(results.single().isFailure); assertEquals(1, exchanges)
        } finally { executor.shutdownNow() }
    }
}
