package dev.zeroinput.ime.personalization

import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.PersonalFrequencyStore
import dev.zeroinput.engine.api.PersonalSuggestion
import dev.zeroinput.engine.api.PersonalizationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class QueuedPersonalizationStoreTest {
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
    fun `listener is notified after preload and query complete`() {
        val executor = TestExecutorService()
        val store = QueuedPersonalizationStore(FakeStore(), preload = {}, executor = executor)
        var notifications = 0
        val listener = store.addSuggestionListener { notifications += 1 }
        try {
            store.suggestionsFor("ni", InputLanguage.CHINESE, 3)
            executor.runNext() // preload
            assertEquals(1, notifications)

            store.suggestionsFor("ni", InputLanguage.CHINESE, 3)
            executor.runNext() // query
            assertEquals(2, notifications)
        } finally {
            listener.close()
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
    fun `frequency lookup is non-blocking and serves the prepared cache after the worker fills it`() {
        val executor = TestExecutorService()
        val store = QueuedPersonalizationStore(FrequencyFakeStore(), preload = {}, executor = executor)
        try {
            // Nothing is ready: the caller gets an empty answer and only a
            // preload is queued, never a delegate call on this thread.
            assertTrue(store.frequenciesFor(listOf("世界"), InputLanguage.CHINESE).isEmpty())
            executor.runNext() // preload
            // First cache miss queues a worker query and stays empty.
            assertTrue(store.frequenciesFor(listOf("世界"), InputLanguage.CHINESE).isEmpty())
            executor.runNext() // frequency query
            assertEquals(mapOf("世界" to 7), store.frequenciesFor(listOf("世界"), InputLanguage.CHINESE))
        } finally {
            store.close()
        }
    }

    @Test
    fun `frequency lookup caches absence once and keeps working after a write invalidates the view`() {
        val executor = TestExecutorService()
        // A delegate without the optional port resolves every word as absent.
        val store = QueuedPersonalizationStore(FakeStore(), preload = {}, executor = executor)
        try {
            store.frequenciesFor(listOf("未知词"), InputLanguage.CHINESE)
            executor.runNext() // preload
            assertTrue(store.frequenciesFor(listOf("未知词"), InputLanguage.CHINESE).isEmpty())
            executor.runNext() // frequency query caches zero
            assertTrue(store.frequenciesFor(listOf("未知词"), InputLanguage.CHINESE).isEmpty())
            // The zero entry is served from the cache: no new worker task.
            assertFalse(executor.hasTasks())

            // A queued learn invalidates the prepared view; the next lookup
            // returns empty without stale data and re-queries once on the worker.
            store.learn("weizhi", "未知词", InputLanguage.CHINESE, learningAllowed = true)
            while (executor.hasTasks()) executor.runNext()
            assertTrue(store.frequenciesFor(listOf("未知词"), InputLanguage.CHINESE).isEmpty())
            executor.runNext() // re-query after invalidation
            assertTrue(store.frequenciesFor(listOf("未知词"), InputLanguage.CHINESE).isEmpty())
        } finally {
            store.close()
        }
    }

    @Test
    fun `frequency query does not read after privacy invalidation`() {
        val delegate = CountingFrequencyStore()
        val executor = TestExecutorService()
        val store = QueuedPersonalizationStore(delegate, preload = {}, executor = executor)
        try {
            store.frequenciesFor(listOf("世界"), InputLanguage.CHINESE)
            executor.runNext() // preload
            store.frequenciesFor(listOf("世界"), InputLanguage.CHINESE)
            store.invalidatePendingWrites()
            executor.runNext() // stale frequency query
            assertEquals(0, delegate.frequencyReads)
        } finally {
            store.close()
        }
    }

    @Test
    fun `rejected frequency query clears pending marker for retry`() {
        val delegate = CountingFrequencyStore()
        val executor = TestExecutorService()
        val store = QueuedPersonalizationStore(delegate, preload = {}, executor = executor)
        try {
            store.frequenciesFor(listOf("世界"), InputLanguage.CHINESE)
            executor.runNext() // preload
            executor.rejectSubmissions = true
            store.frequenciesFor(listOf("世界"), InputLanguage.CHINESE)
            executor.rejectSubmissions = false
            store.frequenciesFor(listOf("世界"), InputLanguage.CHINESE)
            assertTrue(executor.hasTasks())
        } finally {
            store.close()
        }
    }

    private open class FrequencyFakeStore : PersonalFrequencyStore {
        override fun frequenciesFor(words: List<String>, language: InputLanguage): Map<String, Int> =
            mapOf("世界" to 7)

        override fun suggestionsFor(prefix: String, language: InputLanguage, limit: Int) =
            emptyList<PersonalSuggestion>()

        override fun learn(shortcut: String, value: String, language: InputLanguage, learningAllowed: Boolean) = Unit

        override fun recordUse(id: String, learningAllowed: Boolean) = Unit

        override fun clear() = Unit
    }

    private class CountingFrequencyStore : FrequencyFakeStore() {
        var frequencyReads = 0

        override fun frequenciesFor(words: List<String>, language: InputLanguage): Map<String, Int> {
            frequencyReads += 1
            return super.frequenciesFor(words, language)
        }
    }

    private class FakeStore : PersonalizationStore {
        val learned = CountDownLatch(1)
        var learningThread: String? = null
        val learnedValues = mutableListOf<String>()
        var cleared = false

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
