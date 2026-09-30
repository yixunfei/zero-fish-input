package dev.zeroinput.ime.glide

import dev.zeroinput.engine.api.*
import dev.zeroinput.ime.ui.GlideLetterCase
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class GlideCoordinatorTest {
    private val candidate = GlideCandidate("hello", "hello", 0.9f)
    private fun request() = GlideRequest(GlideLayout.ENGLISH_QWERTY,
        listOf(GlidePoint(0.1f, 0.1f, 0), GlidePoint(0.9f, 0.1f, 100)),
        listOf(GlideKey('h', 0f, 0f, 0.2f, 0.3f), GlideKey('o', 0.8f, 0f, 1f, 0.3f)))

    private class Harness(val decoder: GlideDecoder) : AutoCloseable {
        var identity: GlideSessionIdentity? = GlideSessionIdentity(1, 1, GlideLayout.ENGLISH_QWERTY)
        val callbacks = ConcurrentLinkedQueue<Runnable>()
        val posted = CountDownLatch(1)
        var results = emptyList<GlideCandidate>()
        var failed = false
        val coordinator = GlideCoordinator({ decoder }, { identity },
            { callbacks.add(it); posted.countDown(); true }, { callbacks.remove(it); Unit },
            { values, failure -> results = values; failed = failure })
        fun complete() { assertTrue(posted.await(5, TimeUnit.SECONDS)); callbacks.poll()?.run() }
        override fun close() = coordinator.close()
    }

    @Test fun validChoiceIsConsumedOnceAndRetainsLetterCase() {
        Harness(GlideDecoder { _, _ -> listOf(candidate) }).use { test ->
            test.coordinator.request(request(), GlideLetterCase.INITIAL_CAPITAL)
            test.complete()
            assertEquals(listOf(candidate), test.results)
            assertEquals(GlideSelection(candidate, GlideLetterCase.INITIAL_CAPITAL), test.coordinator.consume(candidate))
            assertNull(test.coordinator.consume(candidate))
        }
    }

    @Test fun newEditorRejectsAlreadyPostedResult() {
        Harness(GlideDecoder { _, _ -> listOf(candidate) }).use { test ->
            test.coordinator.request(request(), GlideLetterCase.LOWER)
            assertTrue(test.posted.await(5, TimeUnit.SECONDS))
            test.identity = GlideSessionIdentity(2, 1, GlideLayout.ENGLISH_QWERTY)
            test.callbacks.poll()?.run()
            assertTrue(test.results.isEmpty())
            assertNull(test.coordinator.consume(candidate))
        }
    }

    @Test fun privacyRevocationOrNewInteractionRejectsVisibleChoice() {
        for (next in listOf(null, GlideSessionIdentity(1, 2, GlideLayout.ENGLISH_QWERTY))) {
            Harness(GlideDecoder { _, _ -> listOf(candidate) }).use { test ->
                test.coordinator.request(request(), GlideLetterCase.LOWER)
                test.complete()
                test.identity = next
                assertNull(test.coordinator.consume(candidate))
            }
        }
    }

    @Test fun cancellingRemovesQueuedDeliveryAndCannotResurrectChoices() {
        Harness(GlideDecoder { _, _ -> listOf(candidate) }).use { test ->
            test.coordinator.request(request(), GlideLetterCase.LOWER)
            assertTrue(test.posted.await(5, TimeUnit.SECONDS))
            test.coordinator.invalidate()
            assertTrue(test.callbacks.isEmpty())
            assertNull(test.coordinator.consume(candidate))
        }
    }

    @Test fun decoderFailureHasNoInsertableCandidate() {
        Harness(GlideDecoder { _, _ -> throw IllegalStateException("Public fixture failure") }).use { test ->
            test.coordinator.request(request(), GlideLetterCase.LOWER)
            test.complete()
            assertTrue(test.failed)
            assertTrue(test.results.isEmpty())
            assertNull(test.coordinator.consume(candidate))
        }
    }

    @Test fun backgroundRevocationImmediatelyRejectsChoiceBeforeMainCleanup() {
        Harness(GlideDecoder { _, _ -> listOf(candidate) }).use { test ->
            test.coordinator.request(request(), GlideLetterCase.LOWER)
            test.complete()
            val revoked = CountDownLatch(1)
            Thread { test.coordinator.revoke(); revoked.countDown() }.start()
            assertTrue(revoked.await(2, TimeUnit.SECONDS))
            assertNull(test.coordinator.consume(candidate))
        }
    }

    @Test fun differentActiveLayoutCannotStartDecode() {
        var called = false
        Harness(GlideDecoder { _, _ -> called = true; listOf(candidate) }).use { test ->
            test.identity = GlideSessionIdentity(1, 1, GlideLayout.PINYIN_NINE_KEY)
            test.coordinator.request(request(), GlideLetterCase.LOWER)
            assertFalse(called)
            assertTrue(test.callbacks.isEmpty())
        }
    }

    @Test fun rejectedWorkerLeavesKeyboardUsableAndReportsFailure() {
        val executor = Executors.newSingleThreadExecutor().apply { shutdown() }
        var failed = false
        val coordinator = GlideCoordinator({ GlideDecoder { _, _ -> listOf(candidate) } },
            { GlideSessionIdentity(1, 1, GlideLayout.ENGLISH_QWERTY) }, { it.run(); true }, {},
            { values, failure -> assertTrue(values.isEmpty()); failed = failure }, executor)
        coordinator.use { it.request(request(), GlideLetterCase.LOWER); assertTrue(failed) }
    }
}
