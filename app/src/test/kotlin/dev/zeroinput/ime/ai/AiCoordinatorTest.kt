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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    @Test
    fun overlappingReplacementNeverCancelsTheSamePreviousHandleTwice() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancellations = AtomicInteger()
        val submitted = CopyOnWriteArrayList<String>()
        val provider = object : AiProvider {
            override fun stream(request: AiRequest, listener: (AiStreamEvent) -> Unit): AiRequestHandle {
                submitted += request.input
                return object : AiRequestHandle {
                    override fun cancel() {
                        if (request.input == "hello" && cancellations.incrementAndGet() == 1) {
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                    }
                }
            }
        }
        val coordinator = AiCoordinator(provider)
        val worker = Executors.newSingleThreadExecutor()
        try {
            coordinator.submit(request, policy) {}
            val older = worker.submit { coordinator.submit(AiRequest(null, AiAction.ASK, "old"), policy) {} }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            coordinator.submit(AiRequest(null, AiAction.ASK, "new"), policy) {}
            assertEquals(1, cancellations.get())
            release.countDown()
            older.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("hello", "new"), submitted)
        } finally {
            release.countDown()
            worker.shutdownNow()
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
