package dev.zeroinput.ime.personalization

import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.PersonalFrequencyStore
import dev.zeroinput.engine.api.PersonalSuggestion
import dev.zeroinput.engine.api.PersonalizationStore
import dev.zeroinput.engine.api.PagedPersonalizationStore
import dev.zeroinput.engine.api.PersonalSuggestionPage
import dev.zeroinput.ime.concurrency.BoundedExecutors
import java.util.LinkedHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class QueuedPersonalizationStore(
    private val delegate: PersonalizationStore,
    private val preload: () -> Unit,
    private val executor: ExecutorService = BoundedExecutors.singleThread(
        name = "zeroinput-personalization",
        queueCapacity = MAX_PENDING_OPERATIONS,
    ),
) : PagedPersonalizationStore, PersonalFrequencyStore, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    private val dataRevision = AtomicLong(0L)
    private val viewRevision = AtomicLong(0L)
    @Volatile
    private var ready = false
    private val stateLock = Any()
    private val submissionLock = Any()
    private val delegateLock = Any()
    private val suggestionCache = object : LinkedHashMap<QueryKey, PersonalSuggestionPage>(
        MAX_CACHED_QUERIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<QueryKey, PersonalSuggestionPage>?,
        ): Boolean = size > MAX_CACHED_QUERIES
    }
    private val pendingQueries = HashMap<QueryKey, QueryStamp>()
    private val frequencyCache = object : LinkedHashMap<FrequencyKey, Int>(
        MAX_CACHED_FREQUENCIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FrequencyKey, Int>?): Boolean =
            size > MAX_CACHED_FREQUENCIES
    }
    private val pendingFrequencyQueries = HashMap<FrequencyKey, QueryStamp>()
    private var preloadGeneration: Long? = null
    private val suggestionListeners = CopyOnWriteArrayList<() -> Unit>()

    override fun suggestionsFor(
        prefix: String,
        language: InputLanguage,
        limit: Int,
    ): List<PersonalSuggestion> = suggestionPage(prefix, language, 0, limit).items

    override fun suggestionPage(prefix: String, language: InputLanguage, offset: Int, limit: Int): PersonalSuggestionPage {
        require(limit in 0..MAX_SUGGESTION_LIMIT)
        require(offset in 0..20000)
        val normalized = prefix.trim().lowercase()
        if (normalized.isEmpty() || limit == 0 || closed.get()) return PersonalSuggestionPage(revision = viewRevision.get())

        schedulePreload()
        // Unavailable/cleared data must erase the display immediately. Only a
        // cache miss within the same ready revision may preserve a visible page.
        if (!ready) return PersonalSuggestionPage(revision = viewRevision.get())

        val key = QueryKey(normalized, language, offset)
        val (cached, revision) = synchronized(stateLock) { suggestionCache[key] to viewRevision.get() }
        if (cached != null) return cached.copy(items = cached.items.take(limit),
            hasMore = cached.hasMore || cached.items.size > limit, revision = revision)

        // A cache miss is deliberately non-blocking. The delegate may still
        // touch encrypted storage or sort a large dictionary, so never call
        // it from the IME key path.
        scheduleSuggestionQuery(key, dataRevision.get())
        return PersonalSuggestionPage(ready = false, revision = revision)
    }

    /**
     * Answers from the prepared frequency cache only.  Misses schedule a
     * worker query and stay absent for this call, so the association strip
     * keeps its editorial order instead of blocking the input thread.
     * Known-absent words are cached as zero to avoid repeat queries.
     */
    override fun frequenciesFor(words: List<String>, language: InputLanguage): Map<String, Int> {
        if (closed.get()) return emptyMap()
        val wanted = words.asSequence().map(String::trim).filter(String::isNotEmpty)
            .distinct().take(PersonalFrequencyStore.MAX_LOOKUP).toList()
        if (wanted.isEmpty()) return emptyMap()
        schedulePreload()
        if (!ready) return emptyMap()
        // Cache contents and the data revision are read under the same lock
        // so a completed write cannot mix old entries into a new view.
        val (result, misses, revision) = synchronized(stateLock) {
            val found = HashMap<String, Int>(wanted.size)
            val missing = ArrayList<String>(wanted.size)
            for (word in wanted) {
                val key = FrequencyKey(language, word)
                if (frequencyCache.containsKey(key)) {
                    val frequency = frequencyCache.getValue(key)
                    if (frequency > 0) found[word] = frequency
                } else {
                    missing += word
                }
            }
            Triple(found, missing, dataRevision.get())
        }
        if (misses.isNotEmpty()) scheduleFrequencyQuery(misses, language, revision)
        return result
    }

    override fun learn(
        shortcut: String,
        value: String,
        language: InputLanguage,
        learningAllowed: Boolean,
    ) {
        if (!learningAllowed || closed.get()) return
        val operationGeneration = generation.get()
        schedulePreload()
        // A query that is waiting behind this write would otherwise keep the
        // old revision marked as pending and suppress the first query for the
        // newly learned value.  It is safe to drop that marker: the queued
        // query will re-check the revision before publishing anything.
        invalidateSuggestionCache(clearPending = true)
        enqueue {
            if (isGenerationCurrent(operationGeneration)) {
                runCatching {
                    withDelegate {
                        delegate.learn(shortcut, value, language, learningAllowed = true)
                    }
                }
                dataRevision.incrementAndGet()
                invalidateSuggestionCache(clearPending = true)
            }
        }
    }

    override fun recordUse(id: String, learningAllowed: Boolean) {
        if (!learningAllowed || closed.get()) return
        val operationGeneration = generation.get()
        schedulePreload()
        invalidateSuggestionCache(clearPending = true)
        enqueue {
            if (isGenerationCurrent(operationGeneration)) {
                runCatching {
                    withDelegate { delegate.recordUse(id, learningAllowed = true) }
                }
                dataRevision.incrementAndGet()
                invalidateSuggestionCache(clearPending = true)
            }
        }
    }

    override fun invalidatePendingWrites() {
        // A settings/privacy change can happen while a learn or usage update
        // is waiting in the executor.  Advance the same generation used by
        // clear() so that operation is discarded before it reaches the
        // encrypted delegate.
        generation.incrementAndGet()
        ready = false
        invalidateSuggestionCache(clearPending = true)
    }

    override fun clear() {
        enqueueClear(await = false)
    }

    /**
     * Clears the encrypted delegate and waits for durable completion.  This
     * is intended for a settings worker, never for the IME input thread.
     * Calling code can therefore give the user an accurate completion signal
     * while normal key handling remains fully asynchronous.
     */
    fun clearAndAwait() {
        val task = enqueueClear(await = true) ?: return
        try {
            task.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            // Invalidate a task that is already running before stopping the
            // executor.  shutdownNow() only removes queued tasks; the extra
            // generation check keeps a running callback from publishing into
            // a destroyed store.
            generation.incrementAndGet()
            ready = false
            synchronized(stateLock) {
                suggestionCache.clear()
                pendingQueries.clear()
                frequencyCache.clear()
                pendingFrequencyQueries.clear()
                preloadGeneration = null
            }
            suggestionListeners.clear()
            executor.shutdownNow()
            BoundedExecutors.purge(executor)
        }
    }

    /**
     * Observes completion of a background suggestion query. The callback is
     * invoked on the personalization worker and must only enqueue UI work.
     */
    fun addSuggestionListener(listener: () -> Unit): AutoCloseable {
        suggestionListeners += listener
        return object : AutoCloseable {
            override fun close() {
                suggestionListeners -= listener
            }
        }
    }

    private fun enqueue(operation: () -> Unit): Boolean {
        return synchronized(submissionLock) {
            if (closed.get()) return@synchronized false
            try {
                executor.execute(operation)
                true
            } catch (_: RejectedExecutionException) {
                // A bounded queue can reject optional learning during a burst;
                // callers keep the input path usable and a later interaction can
                // retry preload instead of leaving the store permanently stuck.
                false
            }
        }
    }

    private fun enqueueClear(await: Boolean): java.util.concurrent.Future<*>? {
        if (closed.get()) {
            if (await) error("Personalization store is closed")
            return null
        }
        generation.incrementAndGet()
        ready = false
        // Hide the old in-memory view immediately.  The encrypted delegate is
        // cleared on the worker, but callers must not continue seeing stale
        // personal candidates during that interval.
        invalidateSuggestionCache(clearPending = true)
        // A clear operation also serves as the initialization barrier when
        // no preload has been requested yet.  This prevents a later key event
        // from scheduling a preload that could re-expose data before clearing.
        val clearGeneration = generation.get()
        synchronized(stateLock) { preloadGeneration = clearGeneration }
        val operation = {
            if (!closed.get()) {
                // If clearing fails, keeping the store unavailable is safer
                // than exposing data that the user asked us to remove.
                runCatching { withDelegate { delegate.clear() } }
                    .onSuccess {
                        dataRevision.incrementAndGet()
                        synchronized(stateLock) {
                            if (preloadGeneration == clearGeneration) preloadGeneration = null
                            suggestionCache.clear()
                        }
                        if (clearGeneration == generation.get() && !closed.get()) ready = true
                    }
                    .onFailure {
                        synchronized(stateLock) {
                            if (preloadGeneration == clearGeneration) preloadGeneration = null
                        }
                        ready = false
                    }
                    .getOrThrow()
            }
        }
        var runSynchronously = false
        val task = synchronized(submissionLock) {
            // Learning and query work is optional; a user-requested clear is
            // a control operation and must not wait behind stale queued work.
            // Any task already running is generation-invalidated and is also
            // serialized with clear by delegateLock below.
            BoundedExecutors.cancelQueued(executor)
            try {
                if (await) {
                    executor.submit(operation)
                } else {
                    executor.execute(operation)
                    null
                }
            } catch (error: RejectedExecutionException) {
                if (await) {
                    // This is only the durable settings path. The caller is
                    // already on a worker, so a final synchronous attempt is
                    // preferable to reporting success while old data remains.
                    runSynchronously = true
                    null
                } else {
                    synchronized(stateLock) {
                        if (preloadGeneration == clearGeneration) preloadGeneration = null
                    }
                    null
                }
            }
        }
        if (runSynchronously) {
            operation()
        }
        return task
    }

    private fun schedulePreload() {
        if (ready || closed.get()) return
        val operationGeneration = generation.get()
        synchronized(stateLock) {
            if (closed.get() || ready || preloadGeneration == operationGeneration) return
            preloadGeneration = operationGeneration
        }
        if (!enqueue {
            if (closed.get() || operationGeneration != generation.get()) {
                synchronized(stateLock) {
                    if (preloadGeneration == operationGeneration) preloadGeneration = null
                }
                return@enqueue
            }
            val result = runCatching { withDelegate { preload() } }
            var becameReady = false
            synchronized(stateLock) {
                if (preloadGeneration == operationGeneration) preloadGeneration = null
                if (operationGeneration == generation.get() && result.isSuccess && !closed.get()) {
                    suggestionCache.clear()
                    ready = true
                    becameReady = true
                } else if (operationGeneration == generation.get()) {
                    ready = false
                }
            }
            if (becameReady) notifySuggestionListeners()
        }) {
            synchronized(stateLock) {
                if (preloadGeneration == operationGeneration) preloadGeneration = null
            }
        }
    }

    private fun scheduleSuggestionQuery(key: QueryKey, queryRevision: Long) {
        val queryGeneration = generation.get()
        val queryStamp = QueryStamp(queryGeneration, queryRevision)
        synchronized(stateLock) {
            if (!isQueryCurrent(queryGeneration, queryRevision) || suggestionCache.containsKey(key)) return
            if (pendingQueries[key] == queryStamp) return
            pendingQueries[key] = queryStamp
        }
        if (!enqueue {
                var notify = false
                try {
                    if (isQueryCurrent(queryGeneration, queryRevision)) {
                        val values = runCatching {
                            withDelegate {
                                (delegate as? PagedPersonalizationStore)?.suggestionPage(
                                    key.prefix, key.language, key.offset, MAX_SUGGESTION_LIMIT,
                                ) ?: PersonalSuggestionPage(if (key.offset == 0) delegate.suggestionsFor(
                                    key.prefix,
                                    key.language,
                                    MAX_SUGGESTION_LIMIT,
                                ) else emptyList())
                            }
                        }.getOrDefault(PersonalSuggestionPage())
                        synchronized(stateLock) {
                            if (isQueryCurrent(queryGeneration, queryRevision)) {
                                suggestionCache[key] = values
                                notify = true
                            }
                        }
                    }
                } finally {
                    synchronized(stateLock) {
                        if (pendingQueries[key] == queryStamp) pendingQueries.remove(key)
                    }
                }
                if (notify) {
                    suggestionListeners.forEach { listener -> runCatching(listener) }
                }
            }) {
            synchronized(stateLock) {
                if (pendingQueries[key] == queryStamp) pendingQueries.remove(key)
            }
        }
    }

    private fun isQueryCurrent(queryGeneration: Long, queryRevision: Long): Boolean =
        !closed.get() && ready &&
            queryGeneration == generation.get() && queryRevision == dataRevision.get()

    private fun scheduleFrequencyQuery(misses: List<String>, language: InputLanguage, queryRevision: Long) {
        val queryGeneration = generation.get()
        val queryStamp = QueryStamp(queryGeneration, queryRevision)
        val accepted = ArrayList<String>(misses.size)
        synchronized(stateLock) {
            if (!isQueryCurrent(queryGeneration, queryRevision)) return
            for (word in misses) {
                val key = FrequencyKey(language, word)
                if (frequencyCache.containsKey(key) || pendingFrequencyQueries[key] == queryStamp) continue
                pendingFrequencyQueries[key] = queryStamp
                accepted += word
            }
        }
        if (accepted.isEmpty()) return
        enqueue {
            // A delegate without the frequency port resolves every word as
            // absent (zero), which is the safe editorial-order fallback.
            val values = runCatching {
                withDelegate {
                    (delegate as? PersonalFrequencyStore)?.frequenciesFor(accepted, language).orEmpty()
                }
            }.getOrDefault(emptyMap())
            synchronized(stateLock) {
                for (word in accepted) {
                    val key = FrequencyKey(language, word)
                    if (isQueryCurrent(queryGeneration, queryRevision)) {
                        frequencyCache[key] = values[word] ?: 0
                    }
                    if (pendingFrequencyQueries[key] == queryStamp) pendingFrequencyQueries.remove(key)
                }
            }
        }
    }

    private fun isGenerationCurrent(operationGeneration: Long): Boolean =
        !closed.get() && operationGeneration == generation.get()

    private fun notifySuggestionListeners() {
        if (closed.get()) return
        suggestionListeners.forEach { listener -> runCatching { listener() } }
    }

    private inline fun <T> withDelegate(block: () -> T): T = synchronized(delegateLock) { block() }

    private fun invalidateSuggestionCache(clearPending: Boolean = false) {
        synchronized(stateLock) {
            viewRevision.incrementAndGet()
            suggestionCache.clear()
            frequencyCache.clear()
            if (clearPending) {
                pendingQueries.clear()
                pendingFrequencyQueries.clear()
            }
        }
    }

    private data class QueryKey(
        val prefix: String,
        val language: InputLanguage,
        val offset: Int,
    )

    private data class FrequencyKey(
        val language: InputLanguage,
        val word: String,
    )

    private data class QueryStamp(
        val generation: Long,
        val revision: Long,
    )

    private companion object {
        const val MAX_PENDING_OPERATIONS = 64
        const val MAX_CACHED_QUERIES = 64
        const val MAX_CACHED_FREQUENCIES = 256
        const val MAX_SUGGESTION_LIMIT = 50
    }
}
