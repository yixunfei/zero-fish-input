package dev.zeroinput.ime.personalization

import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.PersonalFrequencyLookup
import dev.zeroinput.engine.api.PersonalFrequencyStore
import dev.zeroinput.engine.api.PersonalSuggestion
import dev.zeroinput.engine.api.PersonalizationStore
import dev.zeroinput.ime.concurrency.BoundedExecutors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class QueuedPersonalizationStoreTest {
    @Test fun `readiness and query completion notify once and closed registration receives nothing`() {
        val executor = TestExecutorService()
        val store = QueuedPersonalizationStore(FakeStore(), preload = {}, executor = executor)
        var changes = 0
        val registration = store.observeChanges { changes++ }
        store.suggestionPage("ni", InputLanguage.CHINESE, 0, 8)
        assertEquals(0, changes)
        executor.runNext()
        assertEquals(1, changes)
        store.suggestionPage("ni", InputLanguage.CHINESE, 0, 8)
        executor.runNext()
        assertEquals(2, changes)
        assertTrue(store.suggestionPage("ni", InputLanguage.CHINESE, 0, 8).items.isNotEmpty())
        assertEquals(2, changes)
        registration.close()
        store.invalidatePendingWrites()
        assertEquals(2, changes)
        store.close()
    }

    @Test fun `clear immediately invalidates a displayed page before its worker runs`() {
        val executor = TestExecutorService()
        val store = QueuedPersonalizationStore(FakeStore(), preload = {}, executor = executor)
        try {
            store.suggestionPage("ni", InputLanguage.CHINESE, 0, 8)
            executor.runNext()
            store.suggestionPage("ni", InputLanguage.CHINESE, 0, 8)
            executor.runNext()
            val displayed = store.suggestionPage("ni", InputLanguage.CHINESE, 0, 8)
            assertTrue(displayed.items.isNotEmpty())
            store.clear()
            val cleared = store.suggestionPage("ni", InputLanguage.CHINESE, 0, 8)
            assertTrue(cleared.ready)
            assertTrue(cleared.items.isEmpty())
            assertTrue(cleared.revision > displayed.revision)
        } finally { store.close() }
    }

    @Test
    fun `constructor does not read the delegate before personalization is requested`() {
        var preloadCalls = 0
        val executor = TestExecutorService()
        val store = QueuedPersonalizationStore(
            delegate = FakeStore(),
            preload = { preloadCalls += 1 },
            executor = executor,
        )
        try {
            assertEquals(0, preloadCalls)
            store.suggestionsFor("ni", InputLanguage.CHINESE, 3)
            executor.runNext()
            assertEquals(1, preloadCalls)
        } finally {
            store.close()
        }
    }

    @Test
    fun `suggestions stay non-blocking until preload completes`() {
        val delegate = FakeStore()
        val executor = TestExecutorService()
        val store = QueuedPersonalizationStore(delegate, preload = {}, executor = executor)
        try {
            assertEquals(emptyList<PersonalSuggestion>(), store.suggestionsFor("ni", InputLanguage.CHINESE, 3))

            executor.runNext()
            // Preload only makes the encrypted snapshot available.  The
            // first cache miss is queued separately so the caller still does
            // not execute the dictionary scan inline.
            assertTrue(store.suggestionsFor("ni", InputLanguage.CHINESE, 3).isEmpty())
            executor.runNext()
            assertEquals("你好", store.suggestionsFor("ni", InputLanguage.CHINESE, 3).single().text)
        } finally {
            store.close()
        }
    }

    @Test
    fun `learning runs on the personalization worker`() {
        val callerThread = Thread.currentThread().name
        val delegate = FakeStore()
        val store = QueuedPersonalizationStore(delegate, preload = {})
        try {
            store.learn("nihao", "你好", InputLanguage.CHINESE, learningAllowed = true)

            assertTrue(delegate.learned.await(2, TimeUnit.SECONDS))
            assertNotEquals(callerThread, delegate.learningThread)
        } finally {
            store.close()
        }
    }

    @Test
    fun `clear invalidates learning already queued before it`() {
        val delegate = FakeStore()
        val executor = TestExecutorService()
        val store = QueuedPersonalizationStore(delegate, preload = {}, executor = executor)
        try {
            store.learn("ni", "旧词", InputLanguage.CHINESE, learningAllowed = true)
            store.clear()

            // Preload, stale learn, and clear are FIFO on the worker.  The
            // stale write must be skipped even if it has not started yet.
            while (executor.hasTasks()) executor.runNext()

            assertTrue(delegate.cleared)
            assertTrue(delegate.learnedValues.isEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun `privacy invalidation discards learning already queued`() {
        val delegate = FakeStore()
        val executor = TestExecutorService()
        val store = QueuedPersonalizationStore(delegate, preload = {}, executor = executor)
        try {
            store.learn("ni", "旧会话词组", InputLanguage.CHINESE, learningAllowed = true)

            store.invalidatePendingWrites()
            while (executor.hasTasks()) executor.runNext()

            assertTrue(delegate.learnedValues.isEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun `invalidated preload can be retried by a later permitted session`() {
        var preloadCalls = 0
        val executor = TestExecutorService()
        val store = QueuedPersonalizationStore(
            delegate = FakeStore(),
            preload = { preloadCalls += 1 },
            executor = executor,
        )
        try {
            assertTrue(store.suggestionsFor("ni", InputLanguage.CHINESE, 3).isEmpty())
            store.invalidatePendingWrites()
            executor.runNext()

            assertTrue(store.suggestionsFor("ni", InputLanguage.CHINESE, 3).isEmpty())
            executor.runNext()

            // The invalidated preload is skipped before touching the
            // encrypted delegate; only the retried generation performs I/O.
            assertEquals(1, preloadCalls)
            assertTrue(store.suggestionsFor("ni", InputLanguage.CHINESE, 3).isEmpty())
            executor.runNext()
            assertEquals(1, store.suggestionsFor("ni", InputLanguage.CHINESE, 3).size)
        } finally {
            store.close()
        }
    }

    @Test
    fun `clear hides stale suggestions before the worker finishes`() {
        val delegate = FakeStore()
        val executor = TestExecutorService()
        val store = QueuedPersonalizationStore(delegate, preload = {}, executor = executor)
        try {
            assertTrue(store.suggestionsFor("ni", InputLanguage.CHINESE, 3).isEmpty())
            executor.runNext() // preload
            assertTrue(store.suggestionsFor("ni", InputLanguage.CHINESE, 3).isEmpty())
            executor.runNext() // query
            assertEquals(1, store.suggestionsFor("ni", InputLanguage.CHINESE, 3).size)

            store.clear()

            assertTrue(store.suggestionsFor("ni", InputLanguage.CHINESE, 3).isEmpty())
            executor.runNext() // clear
            assertTrue(store.suggestionsFor("ni", InputLanguage.CHINESE, 3).isEmpty())
            executor.runNext() // query after clear
            assertTrue(store.suggestionsFor("ni", InputLanguage.CHINESE, 3).isEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun `failed clear blocks later preload until an explicit retry succeeds`() {
        val delegate = FakeStore().apply { failClear = true }
        val executor = TestExecutorService()
        val store = QueuedPersonalizationStore(delegate, preload = {}, executor = executor)
        try {
            store.clear()
            try {
                executor.runNext()
            } catch (_: IllegalStateException) {
                // The failed control operation must leave the store unavailable.
            }

            assertTrue(store.suggestionsFor("ni", InputLanguage.CHINESE, 3).isEmpty())
            assertFalse(executor.hasTasks())

            delegate.failClear = false
            store.clear()
            executor.runNext()
            assertTrue(delegate.cleared)
            assertTrue(store.suggestionsFor("ni", InputLanguage.CHINESE, 3).isEmpty())
            executor.runNext() // query against the successfully cleared delegate
            assertTrue(store.suggestionsFor("ni", InputLanguage.CHINESE, 3).isEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun `durable clear still completes when the worker rejects a control task`() {
        val delegate = FakeStore()
        val executor = TestExecutorService().apply { rejectSubmissions = true }
        val store = QueuedPersonalizationStore(delegate, preload = {}, executor = executor)
        try {
            store.clearAndAwait()

            assertTrue(delegate.cleared)
            assertTrue(store.suggestionsFor("ni", InputLanguage.CHINESE, 3).isEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun `privacy invalidation preserves a queued durable clear`() {
        val preloadStarted = CountDownLatch(1)
        val releasePreload = CountDownLatch(1)
        val executor = BoundedExecutors.singleThread("personalization-clear-test", 4) as ThreadPoolExecutor
        val delegate = FakeStore()
        val store = QueuedPersonalizationStore(
            delegate = delegate,
            preload = {
                preloadStarted.countDown()
                check(releasePreload.await(2, TimeUnit.SECONDS))
            },
            executor = executor,
        )
        var clearFailure: Throwable? = null
        val clearThread = Thread {
            try {
                store.clearAndAwait()
            } catch (error: Throwable) {
                clearFailure = error
            }
        }
        try {
            store.warmFrequencyLookup()
            assertTrue(preloadStarted.await(2, TimeUnit.SECONDS))

            clearThread.start()
            val queueDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (executor.queue.isEmpty() && System.nanoTime() < queueDeadline) Thread.yield()
            assertFalse(executor.queue.isEmpty())

            store.invalidatePendingWrites()
            releasePreload.countDown()
            clearThread.join(TimeUnit.SECONDS.toMillis(2))

            assertFalse(clearThread.isAlive)
            assertEquals(null, clearFailure)
            assertTrue(delegate.cleared)
        } finally {
            releasePreload.countDown()
            if (clearThread.isAlive) clearThread.join(TimeUnit.SECONDS.toMillis(2))
            store.close()
        }
    }

    @Test
    fun `completed suggestion query is visible to a later read without a UI callback`() {
        val executor = TestExecutorService()
        val store = QueuedPersonalizationStore(FakeStore(), preload = {}, executor = executor)
        try {
            store.suggestionsFor("ni", InputLanguage.CHINESE, 3)
            executor.runNext() // preload

            store.suggestionsFor("ni", InputLanguage.CHINESE, 3)
            executor.runNext() // query
            assertEquals(listOf("你好"), store.suggestionsFor("ni", InputLanguage.CHINESE, 3).map { it.text })
        } finally {
            store.close()
        }
    }

    @Test
    fun `stale query result is discarded after privacy invalidation`() {
        val delegate = FakeStore()
        val executor = TestExecutorService()
        val store = QueuedPersonalizationStore(delegate, preload = {}, executor = executor)
        try {
            store.suggestionsFor("ni", InputLanguage.CHINESE, 3)
            executor.runNext() // preload
            store.suggestionsFor("ni", InputLanguage.CHINESE, 3)
            store.invalidatePendingWrites()
            executor.runNext() // stale query

            assertTrue(store.suggestionsFor("ni", InputLanguage.CHINESE, 3).isEmpty())
            executor.runNext() // fresh preload
            assertTrue(store.suggestionsFor("ni", InputLanguage.CHINESE, 3).isEmpty())
            executor.runNext() // fresh query
            assertEquals(1, store.suggestionsFor("ni", InputLanguage.CHINESE, 3).size)
        } finally {
            store.close()
        }
    }

    @Test
    fun `frequency lookup is cold until the worker publishes one prepared snapshot`() {
        val executor = TestExecutorService()
        val delegate = FrequencyFakeStore()
        val store = QueuedPersonalizationStore(delegate, preload = {}, executor = executor)
        try {
            // Nothing is ready: the caller gets an empty answer and only a
            // preload is queued, never a delegate call on this thread.
            assertTrue(store.frequenciesFor(listOf("世界"), InputLanguage.CHINESE).isEmpty())
            executor.runNext() // preload
            // The complete snapshot is now ready; lookup is an in-memory
            // exact-value operation and does not enqueue another task.
            assertEquals(1, delegate.snapshotReads)
            assertEquals(mapOf("世界" to 7), store.frequenciesFor(listOf("世界"), InputLanguage.CHINESE))
            assertEquals(0, delegate.directFrequencyReads)
            assertFalse(executor.hasTasks())
        } finally {
            store.close()
        }
    }

    @Test
    fun `frequency warmup publishes an exact lookup without querying candidate prefixes`() {
        val executor = TestExecutorService()
        val delegate = FrequencyFakeStore()
        val store = QueuedPersonalizationStore(delegate, preload = {}, executor = executor)
        try {
            store.warmFrequencyLookup()
            executor.runNext()

            assertEquals(mapOf("世界" to 7), store.frequenciesFor(listOf("世界"), InputLanguage.CHINESE))
            assertEquals(0, delegate.suggestionReads)
        } finally {
            store.close()
        }
    }

    @Test
    fun `a successful write replaces the frequency snapshot after the current round`() {
        val executor = TestExecutorService()
        val delegate = MutableFrequencyStore()
        val store = QueuedPersonalizationStore(delegate, preload = {}, executor = executor)
        try {
            store.warmFrequencyLookup()
            executor.runNext() // preload
            assertEquals(mapOf("世界" to 7), store.frequenciesFor(listOf("世界"), InputLanguage.CHINESE))

            store.learn("shijie", "世界", InputLanguage.CHINESE, learningAllowed = true)
            // The current in-flight round retains its prepared snapshot.
            assertEquals(mapOf("世界" to 7), store.frequenciesFor(listOf("世界"), InputLanguage.CHINESE))
            executor.runNext() // learn + snapshot publication
            assertEquals(mapOf("世界" to 9), store.frequenciesFor(listOf("世界"), InputLanguage.CHINESE))
        } finally {
            store.close()
        }
    }

    @Test
    fun `a rejected learning task keeps the last usable frequency snapshot`() {
        val executor = TestExecutorService()
        val delegate = MutableFrequencyStore()
        val store = QueuedPersonalizationStore(delegate, preload = {}, executor = executor)
        try {
            store.warmFrequencyLookup()
            executor.runNext() // preload
            executor.rejectSubmissions = true
            store.learn("shijie", "世界", InputLanguage.CHINESE, learningAllowed = true)
            assertEquals(mapOf("世界" to 7), store.frequenciesFor(listOf("世界"), InputLanguage.CHINESE))
        } finally {
            store.close()
        }
    }

    @Test
    fun `prepared data invalidation preserves an accepted learning write`() {
        val executor = TestExecutorService()
        val delegate = MutableFrequencyStore()
        val store = QueuedPersonalizationStore(delegate, preload = {}, executor = executor)
        try {
            store.warmFrequencyLookup()
            executor.runNext() // preload
            assertEquals(mapOf("世界" to 7), store.frequenciesFor(listOf("世界"), InputLanguage.CHINESE))

            store.learn("shijie", "世界", InputLanguage.CHINESE, learningAllowed = true)
            store.invalidatePreparedData()

            assertTrue(store.frequenciesFor(listOf("世界"), InputLanguage.CHINESE).isEmpty())
            executor.runNext() // accepted learning write
            executor.runNext() // refreshed snapshot

            assertEquals(9, delegate.frequency)
            assertEquals(mapOf("世界" to 9), store.frequenciesFor(listOf("世界"), InputLanguage.CHINESE))
        } finally {
            store.close()
        }
    }

    @Test
    fun `privacy invalidation clears the prepared frequency snapshot`() {
        val delegate = MutableFrequencyStore()
        val executor = TestExecutorService()
        val store = QueuedPersonalizationStore(delegate, preload = {}, executor = executor)
        try {
            store.warmFrequencyLookup()
            executor.runNext() // preload
            assertEquals(mapOf("世界" to 7), store.frequenciesFor(listOf("世界"), InputLanguage.CHINESE))
            store.invalidatePendingWrites()
            assertTrue(store.frequenciesFor(listOf("世界"), InputLanguage.CHINESE).isEmpty())
            assertTrue(executor.hasTasks())
        } finally {
            store.close()
        }
    }

    private open class FrequencyFakeStore : PersonalFrequencyStore {
        var directFrequencyReads = 0
        var snapshotReads = 0
        var suggestionReads = 0

        override fun frequencyLookupSnapshot(): PersonalFrequencyLookup {
            snapshotReads += 1
            return PersonalFrequencyLookup { words, _ ->
                if ("世界" in words) mapOf("世界" to 7) else emptyMap()
            }
        }

        override fun frequenciesFor(words: List<String>, language: InputLanguage): Map<String, Int> {
            directFrequencyReads += 1
            return mapOf("世界" to 7)
        }

        override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int): List<PersonalSuggestion> {
            suggestionReads++
            return emptyList()
        }

        override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) = Unit

        override fun recordUse(id: String, learningAllowed: Boolean) = Unit

        override fun clear() = Unit
    }

    private class MutableFrequencyStore : FrequencyFakeStore() {
        var frequency = 7

        override fun frequencyLookupSnapshot(): PersonalFrequencyLookup {
            snapshotReads += 1
            val frequencySnapshot = frequency
            return PersonalFrequencyLookup { words, _ ->
                if ("世界" in words) mapOf("世界" to frequencySnapshot) else emptyMap()
            }
        }

        override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) {
            frequency += 2
        }
    }

    private class FakeStore : PersonalizationStore {
        val learned = CountDownLatch(1)
        var learningThread: String? = null
        val learnedValues = mutableListOf<String>()
        var cleared = false
        var failClear = false

        override fun suggestionsFor(
            prefix: String,
            language: InputLanguage,
            limit: Int,
        ): List<PersonalSuggestion> = if (cleared) {
            emptyList()
        } else {
            listOf(PersonalSuggestion("id", "你好", 2))
        }

        override fun learn(
            shortcut: String,
            value: String,
            language: InputLanguage,
            learningAllowed: Boolean,
        ) {
            learningThread = Thread.currentThread().name
            learnedValues += value
            learned.countDown()
        }

        override fun recordUse(id: String, learningAllowed: Boolean) = Unit

        override fun clear() {
            if (failClear) error("clear failed")
            cleared = true
            learnedValues.clear()
        }

    }

    private class TestExecutorService : AbstractExecutorService() {
        private val tasks = ArrayDeque<Runnable>()
        private var shutdown = false
        var rejectSubmissions = false

        override fun execute(command: Runnable) {
            check(!shutdown)
            if (rejectSubmissions) throw java.util.concurrent.RejectedExecutionException()
            tasks.addLast(command)
        }

        fun runNext() = tasks.removeFirst().run()

        fun hasTasks(): Boolean = tasks.isNotEmpty()

        override fun shutdown() {
            shutdown = true
        }

        override fun shutdownNow(): List<Runnable> {
            shutdown = true
            return buildList {
                while (tasks.isNotEmpty()) add(tasks.removeFirst())
            }
        }

        override fun isShutdown(): Boolean = shutdown

        override fun isTerminated(): Boolean = shutdown && tasks.isEmpty()

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
    }
}
