package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.*
import dev.zeroinput.userdata.AiConfiguration
import org.junit.Assert.*
import org.junit.Test

class AiProviderProbeTest {
    @Test fun disabledNetworkNeverConstructsProvider() {
        val states = mutableListOf<AiProbeState>()
        val probe = AiProviderProbe({ error("Unexpected network") }, { true }, { it(); true }, states::add)
        probe.start(AiConfiguration(enabled = true, networkAllowed = false))
        assertTrue((states.single() as AiProbeState.Failed).error is AiProviderError.Policy)
    }

    @Test fun probeUsesOnlyBoundedPublicPromptAndSelectedConfiguration() {
        var request: AiRequest? = null
        var snapshot: AiConfiguration? = null
        val states = mutableListOf<AiProbeState>()
        val probe = AiProviderProbe({ config ->
            snapshot = config
            object : AiProvider {
                override fun stream(value: AiRequest, listener: (AiStreamEvent) -> Unit): AiRequestHandle {
                    request = value
                    listener(AiStreamEvent.Completed("OK"))
                    return object : AiRequestHandle { override fun cancel() {} }
                }
            }
        }, { true }, { it(); true }, states::add)
        probe.start(AiConfiguration(enabled = true, networkAllowed = true))
        assertEquals("Reply with OK.", request?.input)
        assertTrue(request?.history?.isEmpty() == true)
        assertTrue(request?.attachments?.isEmpty() == true)
        assertNull(request?.conversationId)
        assertEquals(16, request?.outputTokenLimit)
        assertTrue(checkNotNull(snapshot).timeoutMs <= 30_000L)
        assertEquals(listOf(AiProbeState.Running, AiProbeState.Available), states)
        probe.close()
    }

    @Test fun cancelAndConfigurationRevocationDiscardQueuedAndLateResults() {
        val queued = mutableListOf<() -> Unit>()
        val states = mutableListOf<AiProbeState>()
        var listener: ((AiStreamEvent) -> Unit)? = null
        var cancelled = 0
        var current = true
        val probe = AiProviderProbe({ object : AiProvider {
            override fun stream(request: AiRequest, callback: (AiStreamEvent) -> Unit): AiRequestHandle {
                listener = callback
                return object : AiRequestHandle { override fun cancel() { cancelled++ } }
            }
        } }, { current }, { queued += it; true }, states::add)
        val configuration = AiConfiguration(enabled = true, networkAllowed = true)
        probe.start(configuration)
        listener?.invoke(AiStreamEvent.Completed("OK"))
        probe.cancel()
        queued.toList().forEach { it() }
        listener?.invoke(AiStreamEvent.Completed("late"))
        assertEquals(listOf(AiProbeState.Running, AiProbeState.Cancelled), states)
        assertEquals(1, cancelled)
        probe.start(configuration)
        current = false
        listener?.invoke(AiStreamEvent.Completed("stale"))
        queued.toList().forEach { it() }
        assertFalse(states.contains(AiProbeState.Available))
        probe.close()
    }

    @Test fun emptyResponseIsNotReportedAsAvailable() {
        val states = mutableListOf<AiProbeState>()
        val probe = AiProviderProbe({ object : AiProvider {
            override fun stream(request: AiRequest, listener: (AiStreamEvent) -> Unit): AiRequestHandle {
                listener(AiStreamEvent.Completed(" "))
                return object : AiRequestHandle { override fun cancel() {} }
            }
        } }, { true }, { it(); true }, states::add)
        probe.start(AiConfiguration(enabled = true, networkAllowed = true))
        assertTrue((states.last() as AiProbeState.Failed).error is AiProviderError.Response)
        probe.close()
    }
}
