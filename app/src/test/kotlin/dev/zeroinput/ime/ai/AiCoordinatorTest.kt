package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiAction
import dev.zeroinput.ai.api.AiGenerationPolicy
import dev.zeroinput.ai.api.AiProvider
import dev.zeroinput.ai.api.AiRequest
import dev.zeroinput.ai.api.AiRequestHandle
import dev.zeroinput.ai.api.AiStreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class AiCoordinatorTest {
    private val request = AiRequest(null, AiAction.ASK, "hello")
    private val policy = AiGenerationPolicy(enabled = true, networkAllowed = true, sensitiveEditor = false)

    @Test
    fun lateEventsFromAnOlderRequestAreDropped() {
        val provider = RecordingProvider()
        val coordinator = AiCoordinator(provider)
        try {
            val first = mutableListOf<AiStreamEvent>()
            val second = mutableListOf<AiStreamEvent>()
            coordinator.submit(request, policy, first::add)
            coordinator.submit(request, policy, second::add)
            provider.emit(0, AiStreamEvent.Delta("old"))
            provider.emit(1, AiStreamEvent.Delta("new"))
            assertTrue(first.isEmpty())
            assertEquals(listOf(AiStreamEvent.Delta("new")), second)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun cancellingARequestInvalidatesItsCallbacks() {
        val provider = RecordingProvider()
        val coordinator = AiCoordinator(provider)
        try {
            val events = mutableListOf<AiStreamEvent>()
            val handle = coordinator.submit(request, policy, events::add)
            handle.cancel()
            provider.emit(0, AiStreamEvent.Completed("late"))
            assertTrue(events.isEmpty())
            assertEquals(1, provider.cancelCount)
        } finally {
            coordinator.close()
        }
    }

    private class RecordingProvider : AiProvider {
        private val listeners = CopyOnWriteArrayList<(AiStreamEvent) -> Unit>()
        var cancelCount = 0

        override fun stream(request: AiRequest, listener: (AiStreamEvent) -> Unit): AiRequestHandle {
            val index = listeners.size
            listeners += listener
            return object : AiRequestHandle {
                override fun cancel() {
                    cancelCount++
                }
            }
        }

        fun emit(index: Int, event: AiStreamEvent) {
            listeners[index](event)
        }
    }
}
