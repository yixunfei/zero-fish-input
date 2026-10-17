package dev.zeroinput.ime.ai

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ai.api.*
import dev.zeroinput.userdata.AiConfiguration
import dev.zeroinput.userdata.AiProviderProfile
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Explicit opt-in only. Credentials are instrumentation arguments, never fixtures or saved settings. */
@RunWith(AndroidJUnit4::class)
class AiLiveProviderTest {
    @Test fun discoverProbeAndContinuePublicConversationThroughTheRealAndroidTransport() {
        val args = InstrumentationRegistry.getArguments()
        val key = args.getString("aiTestKey")
        assumeTrue("Live validation requires explicit temporary credentials", !key.isNullOrBlank())
        args.remove("aiTestKey")
        val endpoint = checkNotNull(args.getString("aiTestEndpoint"))
        val model = checkNotNull(args.getString("aiTestModel"))
        val protocol = AiProtocol.valueOf(args.getString("aiTestProtocol") ?: "CHAT_COMPLETIONS")
        val profile = AiProviderProfile("live-fixture", "Live fixture", endpoint, checkNotNull(key), listOf(model), model,
            protocol = protocol)
        val config = AiConfiguration(enabled = true, networkAllowed = true, timeoutMs = 45_000,
            providers = listOf(profile), selectedProviderId = profile.id)
        val worker = Executors.newSingleThreadExecutor()
        val cancellation = Executors.newSingleThreadExecutor()
        try {
            val provider = OpenAiCompatibleProvider({ config }, worker, cancellation)
            val catalog = AtomicReference<AiModelCatalogEvent>()
            val listed = CountDownLatch(1)
            provider.fetchModels { if (it != AiModelCatalogEvent.Started) { catalog.set(it); listed.countDown() } }
            assertTrue("Model discovery timed out", listed.await(50, TimeUnit.SECONDS))
            val catalogResult = catalog.get()
            val models = (catalogResult as? AiModelCatalogEvent.Completed)?.models.orEmpty()
            val catalogFailure = (catalogResult as? AiModelCatalogEvent.Failed)?.error
            report("Live catalog: " + (catalogFailure?.let(::safeFailure) ?: "completed (${models.size})"))
            assertTrue("Model discovery failed: " + (catalogFailure?.let(::safeFailure) ?: "cancelled"),
                catalogResult is AiModelCatalogEvent.Completed)
            assertTrue("Requested model is absent from the catalog", model in models)

            val probed = CountDownLatch(1)
            val probeState = AtomicReference<AiProbeState>()
            val probe = AiProviderProbe({ snapshot -> OpenAiCompatibleProvider({ snapshot }, worker, cancellation) },
                { true }, { it(); true }, { if (it != AiProbeState.Running) { probeState.set(it); probed.countDown() } })
            probe.start(config)
            assertTrue("Model probe timed out", probed.await(35, TimeUnit.SECONDS))
            val probeFailure = (probeState.get() as? AiProbeState.Failed)?.error
            report("Live generation probe: " + (probeFailure?.let(::safeFailure) ?: "completed"))
            assertTrue("Model probe failed: " + (probeFailure?.let(::safeFailure) ?: "cancelled"),
                probeState.get() == AiProbeState.Available)
            probe.close()

            val prompt = "Remember the public test word ORCHID. Reply with OK."
            val first = completed(provider, AiRequest(null, AiAction.ASK, prompt, outputTokenLimit = 512))
            val full = config.copy(providers = listOf(profile.copy(
                endpoint = AiEndpoint.parse(endpoint, protocol).generation.toString(), streaming = false)))
            val followup = completed(OpenAiCompatibleProvider({ full }, worker, cancellation),
                AiRequest(null, AiAction.ASK, "What public test word did I ask you to remember? Reply only with that word.", history = listOf(
                    AiMessage(AiRole.USER, prompt), AiMessage(AiRole.ASSISTANT, first)), outputTokenLimit = 512))
            assertTrue("Follow-up did not use the conversation context", followup.contains("ORCHID", ignoreCase = true))
            val invalid = config.copy(providers = listOf(profile.copy(apiKey = "public-invalid-key")))
            val failure = terminal(OpenAiCompatibleProvider({ invalid }, worker, cancellation),
                AiRequest(null, AiAction.ASK, "Reply with OK.", outputTokenLimit = 16))
            val reason = ((failure as? AiStreamEvent.Failed)?.error as? AiProviderError.Network)?.reason
            assertTrue("Invalid credentials were accepted", reason == AiNetworkFailure.AUTHENTICATION)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "Live verification: catalog=${models.size}; probe=passed; base/full chat=passed; history=passed; invalid key=rejected\n")
            })
        } finally { worker.shutdownNow(); cancellation.shutdownNow() }
    }

    private fun safeFailure(error: AiProviderError): String = when (error) {
        is AiProviderError.Network -> "${error.reason} HTTP ${error.statusCode ?: 0}"
        is AiProviderError.Response -> "response ${error.reason}"
        is AiProviderError.Configuration -> "configuration"
        is AiProviderError.Policy -> "policy"
        is AiProviderError.Storage -> "storage"
    }

    private fun report(message: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply { putString("stream", "$message\n") })

    private fun completed(provider: AiProvider, request: AiRequest): String {
        val event = terminal(provider, request)
        assertTrue("Live completion failed", event is AiStreamEvent.Completed)
        return (event as AiStreamEvent.Completed).text
    }

    private fun terminal(provider: AiProvider, request: AiRequest): AiStreamEvent {
        val done = CountDownLatch(1)
        val value = AtomicReference<AiStreamEvent>()
        val handle = provider.stream(request) {
            if (it is AiStreamEvent.Completed || it is AiStreamEvent.Failed || it == AiStreamEvent.Cancelled) {
                value.set(it); done.countDown()
            }
        }
        try { assertTrue("Live completion timed out", done.await(50, TimeUnit.SECONDS)); return value.get() }
        finally { handle.cancel() }
    }
}
