package dev.zeroinput.ime

import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.EngineDescriptor
import dev.zeroinput.engine.api.EngineKey
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.engine.api.EngineUpdate
import dev.zeroinput.engine.api.InputEngine
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.PageDirection
import dev.zeroinput.ime.concurrency.BoundedExecutors
import dev.zeroinput.ime.core.privacy.PrivacyReason
import dev.zeroinput.ime.core.privacy.SessionPrivacy
import dev.zeroinput.ime.core.PreparedInputEngine
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineWarmupCoordinatorTest {
    @Test fun `prepared engines receive the editor prediction restriction`() {
        val executor = worker()
        val delivered = CountDownLatch(1)
        val engine = TrackingEngine()
        val coordinator = EngineWarmupCoordinator(executor, { engine }, {
            (it as EngineWarmupResult.Prepared).engine.close()
            delivered.countDown()
        })
        try {
            val original = request()
            coordinator.request(original.copy(privacy = original.privacy.copy(predictionsAllowed = false)))
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            assertEquals(false, engine.startedContext?.predictionsAllowed)
        } finally {
            coordinator.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `changing phonetic options retires a prepared engine from the old configuration`() {
        val original = request()
        verifyReplacement(original.copy(chineseOptions = original.chineseOptions.copy(abbreviatedPinyin = false)))
    }

    @Test
    fun `changing engine selection retires the delayed engine from the old selection`() {
        verifyReplacement(request().copy(chineseEngine = dev.zeroinput.ime.settings.ChineseEngineChoice.DICTIONARY_TEST))
    }

    @Test
    fun `changing keyboard layout retires a delayed engine with the old spelling rules`() {
        val original = request()
        verifyReplacement(original.copy(chineseOptions = original.chineseOptions.copy(
            keyboardLayout = dev.zeroinput.engine.api.ChineseKeyboardLayout.NINE_KEY)))
    }

    private fun verifyReplacement(updated: EngineWarmupRequest) {
        val executor = worker()
        val oldEngine = TrackingEngine()
        val newEngine = TrackingEngine()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delivered = CountDownLatch(1)
        val original = request()
        val result = AtomicReference<EngineWarmupResult>()
        val coordinator = EngineWarmupCoordinator(executor, { requested ->
            if (requested == original) {
                entered.countDown()
                while (release.count > 0) {
                    try { release.await() } catch (_: InterruptedException) { /* Simulate uncancellable native work. */ }
                }
                oldEngine
            } else newEngine
        }, { value -> result.set(value); delivered.countDown() })
        try {
            coordinator.request(original)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            coordinator.request(updated)
            release.countDown()
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            assertTrue(oldEngine.closed.await(2, TimeUnit.SECONDS))
            assertTrue(result.get().request == updated)
            (result.get() as EngineWarmupResult.Prepared).engine.close()
            assertEquals(1, oldEngine.closeCalls.get())
        } finally {
            release.countDown()
            coordinator.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `preparation and engine start run away from the caller`() {
        val executor = worker()
        val result = AtomicReference<EngineWarmupResult>()
        val delivered = CountDownLatch(1)
        val caller = Thread.currentThread().name
        val engine = TrackingEngine()
        val coordinator = EngineWarmupCoordinator(
            executor = executor,
            prepare = { engine },
            dispatch = {
                result.set(it)
                delivered.countDown()
            },
        )
        try {
            coordinator.request(request())

            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            val prepared = result.get() as EngineWarmupResult.Prepared
            assertTrue(engine.startedThread != null)
            assertNotEquals(caller, engine.startedThread)
            prepared.engine.close()
        } finally {
            coordinator.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `a cancelled preparation closes an engine that finishes late`() {
        val executor = worker()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val engine = TrackingEngine()
        val delivered = CountDownLatch(1)
        val coordinator = EngineWarmupCoordinator(
            executor = executor,
            prepare = {
                started.countDown()
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    // Cancellation is expected; still return the allocated
                    // engine so the coordinator can exercise its close path.
                }
                engine
            },
            dispatch = { delivered.countDown() },
        )
        try {
            coordinator.request(request())
            assertTrue(started.await(2, TimeUnit.SECONDS))
            coordinator.cancel()
            release.countDown()

            assertTrue(engine.closed.await(2, TimeUnit.SECONDS))
            assertEquals(1L, delivered.count)
        } finally {
            coordinator.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `sensitive requests never create an engine`() {
        val executor = worker()
        val providerCalls = AtomicInteger()
        val result = AtomicReference<EngineWarmupResult>()
        val delivered = CountDownLatch(1)
        val coordinator = EngineWarmupCoordinator(
            executor = executor,
            prepare = {
                providerCalls.incrementAndGet()
                TrackingEngine()
            },
            dispatch = {
                result.set(it)
                delivered.countDown()
            },
        )
        try {
            coordinator.request(
                request().copy(
                    privacy = SessionPrivacy(
                        isSensitive = true,
                        suggestionsAllowed = false,
                        learningAllowed = false,
                        reason = PrivacyReason.PASSWORD_FIELD,
                    ),
                ),
            )

            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            assertEquals(0, providerCalls.get())
            assertTrue(result.get() is EngineWarmupResult.Unavailable)
        } finally {
            coordinator.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `closing the owner releases a result removed from the main queue`() {
        val engine = TrackingEngine()
        val queue = ArrayDeque<Runnable>()
        val delivered = AtomicInteger()
        val delivery = EngineWarmupResultDelivery(
            post = { runnable -> queue.addLast(runnable); true },
            deliver = { delivered.incrementAndGet() },
        )
        try {
            delivery.offer(EngineWarmupResult.Prepared(1L, request(), prepared(engine)))
            delivery.close()

            assertTrue(engine.closed.await(2, TimeUnit.SECONDS))
            queue.removeFirst().run()
            assertEquals(0, delivered.get())
            assertEquals(1, engine.closeCalls.get())
        } finally {
            delivery.close()
        }
    }

    @Test
    fun `multiple offers keep one queued callback and deliver the newest result`() {
        val firstEngine = TrackingEngine()
        val latestEngine = TrackingEngine()
        val queue = ArrayDeque<Runnable>()
        val delivered = AtomicReference<EngineWarmupResult>()
        val delivery = EngineWarmupResultDelivery(
            post = { runnable -> queue.addLast(runnable); true },
            deliver = delivered::set,
        )
        try {
            delivery.offer(EngineWarmupResult.Prepared(1L, request(), prepared(firstEngine)))
            delivery.offer(EngineWarmupResult.Prepared(2L, request(), prepared(latestEngine)))

            assertTrue(firstEngine.closed.await(2, TimeUnit.SECONDS))
            assertEquals(1, queue.size)
            queue.removeFirst().run()
            assertEquals(2L, delivered.get().ticket)
            assertTrue(latestEngine.closed.await(2, TimeUnit.SECONDS))
        } finally {
            delivery.close()
        }
    }

    @Test
    fun `a rejected main-thread post closes its prepared result`() {
        val engine = TrackingEngine()
        val delivery = EngineWarmupResultDelivery(
            post = { false },
            deliver = { error("rejected result must not be delivered") },
        )
        try {
            delivery.offer(EngineWarmupResult.Prepared(1L, request(), prepared(engine)))
            assertTrue(engine.closed.await(2, TimeUnit.SECONDS))
            assertEquals(1, engine.closeCalls.get())
        } finally {
            delivery.close()
        }
    }

    private fun request() = EngineWarmupRequest(
        sessionToken = 1L,
        language = InputLanguage.CHINESE,
        languagePackKey = null,
        packageName = "example.editor",
        privacy = SessionPrivacy(
            isSensitive = false,
            suggestionsAllowed = true,
            learningAllowed = true,
            reason = PrivacyReason.NONE,
        ),
    )

    private fun worker(): ExecutorService = BoundedExecutors.singleThread(
        name = "warmup-test",
        queueCapacity = 1,
    )

    private fun prepared(engine: InputEngine) = PreparedInputEngine(
        language = request().language,
        languagePackKey = request().languagePackKey,
        packageName = request().packageName,
        privacy = request().privacy,
        snapshot = EngineSnapshot.Empty,
        engine = engine,
    )

    private class TrackingEngine : InputEngine {
        private var current = EngineSnapshot.Empty
        val closed = CountDownLatch(1)
        val closeCalls = AtomicInteger()
        var startedThread: String? = null
        var startedContext: EditorContext? = null

        override val descriptor = EngineDescriptor(
            id = "tracking",
            displayName = "Tracking",
            version = "1",
            languages = setOf(InputLanguage.CHINESE),
        )

        override val snapshot: EngineSnapshot
            get() = current

        override fun start(context: EditorContext): EngineSnapshot {
            startedThread = Thread.currentThread().name
            startedContext = context
            return EngineSnapshot.Empty
        }

        override fun handle(key: EngineKey) = EngineUpdate(current, consumed = false)

        override fun selectCandidate(index: Int) = EngineUpdate(current, consumed = false)

        override fun changePage(direction: PageDirection) = EngineUpdate(current, consumed = false)

        override fun reset(): EngineSnapshot {
            current = EngineSnapshot.Empty
            return current
        }

        override fun close() {
            closeCalls.incrementAndGet()
            current = EngineSnapshot.Empty
            closed.countDown()
        }
    }

}
