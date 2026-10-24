package dev.zeroinput.ime.personalization

import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.PersonalFrequencyLookup
import dev.zeroinput.engine.api.PersonalFrequencyStore
import dev.zeroinput.engine.api.PersonalSuggestion
import dev.zeroinput.engine.api.PersonalizationStore
import dev.zeroinput.engine.api.PagedPersonalizationStore
import dev.zeroinput.engine.api.PersonalSuggestionPage
import dev.zeroinput.ime.concurrency.BoundedExecutors
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
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
    private val frequencyRevision = AtomicLong(0L)
    private val preparationRevision = AtomicLong(0L)
    private val clearRevision = AtomicLong(0L)
    @Volatile
    private var ready = false
    @Volatile
    private var clearBlocked = false
    private val stateLock = Any()
    private val submissionLock = Any()
    private val delegateLock = Any()
    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()
    private val suggestionCache = object : LinkedHashMap<QueryKey, PersonalSuggestionPage>(
        MAX_CACHED_QUERIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<QueryKey, PersonalSuggestionPage>?,
        ): Boolean = size > MAX_CACHED_QUERIES || values.sumOf { it.suppressedDictionaryTexts.size + it.suppressedDictionaryEntries.size } > 40000
    }
    private val pendingQueries = HashMap<QueryKey, QueryStamp>()
    @Volatile private var frequencyLookup: PersonalFrequencyLookup? = null
    private var preloadGeneration: PreloadStamp? = null

    override fun suggestionsFor(
        prefix: String,
        language: InputLanguage,
        limit: Int,
    ): List<PersonalSuggestion> = suggestionPage(prefix, language, 0, limit).items

    override fun observeChanges(listener: () -> Unit): AutoCloseable {
        if (closed.get()) return AutoCloseable { }
        changeListeners += listener
        return AutoCloseable { changeListeners -= listener }
    }

    override fun suggestionPage(prefix: String, language: InputLanguage, offset: Int, limit: Int): PersonalSuggestionPage {
        require(limit in 0..MAX_SUGGESTION_LIMIT)
        require(offset in 0..20000)
        val normalized = prefix.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty() || limit == 0 || closed.get()) return PersonalSuggestionPage(revision = viewRevision.get())

        schedulePreload()
        // Unavailable/cleared data must erase the display immediately. Only a
        // cache miss within the same ready revision may preserve a visible page.
        if (!ready) return if (delegate is DictionaryPersonalizationStore && language == InputLanguage.CHINESE)
            unavailablePage(viewRevision.get(), language) else PersonalSuggestionPage(revision = viewRevision.get())

        val key = QueryKey(normalized, language, offset)
        val (cached, revision) = synchronized(stateLock) { suggestionCache[key] to viewRevision.get() }
        if (cached != null) return cached.copy(items = cached.items.take(limit),
            hasMore = cached.hasMore || cached.items.size > limit, revision = revision)

        // A cache miss is deliberately non-blocking. The delegate may still
        // touch encrypted storage or sort a large dictionary, so never call
        // it from the IME key path.
        scheduleSuggestionQuery(key, dataRevision.get())
        return unavailablePage(revision, language)
    }

    private fun unavailablePage(revision: Long, language: InputLanguage) = PersonalSuggestionPage(
        ready = false, revision = revision,
        dictionaryEditsApplied = language == InputLanguage.CHINESE &&
            (delegate as? DictionaryPersonalizationStore)?.requiresFiltering == true,
    )

    /**
     * Answers from the prepared frequency snapshot only. A cold snapshot
     * returns no frequencies for this call, preserving editorial order.
     */
    override fun frequenciesFor(words: List<String>, language: InputLanguage): Map<String, Int> {
        if (closed.get() || clearBlocked) return emptyMap()
        val wanted = words.asSequence().map(String::trim).filter(String::isNotEmpty)
            .distinct().take(PersonalFrequencyStore.MAX_LOOKUP).toList()
        if (wanted.isEmpty()) return emptyMap()
        schedulePreload()
        if (!ready) return emptyMap()
        val snapshot = frequencyLookup ?: return emptyMap()
        return runCatching { snapshot.frequenciesFor(wanted, language) }
            .getOrDefault(emptyMap())
            .filter { (word, frequency) -> word in wanted && frequency > 0 }
    }

    /**
     * Prepares the exact-value frequency index on the existing worker without
     * publishing a UI result.
     */
    fun warmFrequencyLookup() {
        schedulePreload()
    }

    override fun learn(
        shortcut: String,
        value: String,
        language: InputLanguage,
        learningAllowed: Boolean,
    ) {
        if (!learningAllowed || closed.get()) return
        val operationGeneration = generation.get()
        val operationFrequencyRevision = frequencyRevision.get()
        schedulePreload()
        // A query that is waiting behind this write would otherwise keep the
        // old revision marked as pending and suppress the first query for the
        // newly learned value.  It is safe to drop that marker: the queued
        // query will re-check the revision before publishing anything.
        invalidateSuggestionCache(clearPending = true)
        enqueue {
            if (isGenerationCurrent(operationGeneration)) {
                val result = runCatching {
                    withDelegate {
                        if (!isGenerationCurrent(operationGeneration)) null else {
                            delegate.learn(shortcut, value, language, learningAllowed = true)
                            (delegate as? PersonalFrequencyStore)?.frequencyLookupSnapshot()
                        }
                    }
                }
                result.onSuccess { snapshot ->
                    if (!isGenerationCurrent(operationGeneration)) return@onSuccess
                    publishFrequencyLookup(operationGeneration, operationFrequencyRevision, snapshot)
                    dataRevision.incrementAndGet()
                    invalidateSuggestionCache(clearPending = true)
                }
            }
        }
    }

    override fun recordUse(id: String, learningAllowed: Boolean) {
        if (!learningAllowed || closed.get()) return
        val operationGeneration = generation.get()
        val operationFrequencyRevision = frequencyRevision.get()
        schedulePreload()
        invalidateSuggestionCache(clearPending = true)
        enqueue {
            if (isGenerationCurrent(operationGeneration)) {
                val result = runCatching {
                    withDelegate {
                        if (!isGenerationCurrent(operationGeneration)) null else {
                            delegate.recordUse(id, learningAllowed = true)
                            (delegate as? PersonalFrequencyStore)?.frequencyLookupSnapshot()
                        }
                    }
                }
                result.onSuccess { snapshot ->
                    if (!isGenerationCurrent(operationGeneration)) return@onSuccess
                    publishFrequencyLookup(operationGeneration, operationFrequencyRevision, snapshot)
                    dataRevision.incrementAndGet()
                    invalidateSuggestionCache(clearPending = true)
                }
            }
        }
    }

    override fun invalidatePendingWrites() {
        // A settings/privacy change can happen while a learn or usage update
        // is waiting in the executor.  Advance the same generation used by
        // clear() so that operation is discarded before it reaches the
        // encrypted delegate.
        generation.incrementAndGet()
        synchronized(submissionLock) {
            // Privacy/session revocation drops queued optional work before it
            // consumes executor capacity. An in-flight task checks its token
            // again after acquiring the delegate lock.
            cancelQueuedOptionalWork()
        }
        synchronized(stateLock) {
            pendingQueries.clear()
            preloadGeneration = null
        }
        invalidatePreparedData()
    }

    /** Invalidates prepared reads without discarding already authorized learning writes. */
    fun invalidatePreparedData() {
        preparationRevision.incrementAndGet()
        invalidateFrequencyLookup()
        (delegate as? DictionaryPersonalizationStore)?.invalidate()
        ready = false
        dataRevision.incrementAndGet()
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
            invalidateFrequencyLookup()
            ready = false
            synchronized(stateLock) {
                suggestionCache.clear()
                pendingQueries.clear()
                preloadGeneration = null
            }
            executor.shutdownNow()
            changeListeners.clear()
            BoundedExecutors.purge(executor)
        }
    }

    private fun enqueue(operation: () -> Unit): Boolean {
        return synchronized(submissionLock) {
            if (closed.get()) return@synchronized false
            try {
                executor.execute(OptionalTask(operation))
                true
            } catch (_: RejectedExecutionException) {
                // A bounded queue can reject optional learning during a burst;
                // callers keep the input path usable and a later interaction can
                // retry preload instead of leaving the store permanently stuck.
                false
            }
        }
    }

    private fun cancelQueuedOptionalWork() {
        BoundedExecutors.cancelQueued(executor) { task -> task is OptionalTask }
    }

    private fun enqueueClear(await: Boolean): java.util.concurrent.Future<*>? {
        if (closed.get()) {
            if (await) error("Personalization store is closed")
            return null
        }
        generation.incrementAndGet()
        val clearPreparationRevision = preparationRevision.incrementAndGet()
        val clearFrequencyRevision = invalidateFrequencyLookup()
        val clearAttempt = clearRevision.incrementAndGet()
        ready = false
        clearBlocked = true
        // Hide the old in-memory view immediately.  The encrypted delegate is
        // cleared on the worker, but callers must not continue seeing stale
        // personal candidates during that interval.
        invalidateSuggestionCache(clearPending = true)
        // A clear operation also serves as the initialization barrier when
        // no preload has been requested yet.  This prevents a later key event
        // from scheduling a preload that could re-expose data before clearing.
        val clearGeneration = generation.get()
        val clearStamp = PreloadStamp(clearGeneration, clearPreparationRevision)
        synchronized(stateLock) {
            preloadGeneration = clearStamp
        }
        val operation = {
            if (!closed.get()) {
                // If clearing fails, keeping the store unavailable is safer
                // than exposing data that the user asked us to remove.
                val clearResult = runCatching {
                    withDelegate {
                        delegate.clear()
                        (delegate as? PersonalFrequencyStore)?.frequencyLookupSnapshot()
                    }
                }
                clearResult
                    .onSuccess {
                        val snapshot = it
                        dataRevision.incrementAndGet()
                        if (clearAttempt == clearRevision.get()) clearBlocked = false
                        synchronized(stateLock) {
                            if (preloadGeneration == clearStamp) preloadGeneration = null
                            suggestionCache.clear()
                            if (clearFrequencyRevision == frequencyRevision.get() &&
                                clearGeneration == generation.get() &&
                                clearPreparationRevision == preparationRevision.get() && !closed.get()) {
                                frequencyLookup = snapshot
                            }
                        }
                        if (clearGeneration == generation.get() &&
                            clearPreparationRevision == preparationRevision.get() && !closed.get()) ready = true
                    }
                    .onFailure {
                        synchronized(stateLock) {
                            if (preloadGeneration == clearStamp) clearBlocked = true
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
            cancelQueuedOptionalWork()
            try {
                if (await) {
                    val clearTask = AwaitableControlTask(operation)
                    executor.execute(clearTask)
                    clearTask
                } else {
                    executor.execute(ControlTask(operation))
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
                        if (preloadGeneration == clearStamp) clearBlocked = true
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
        if (ready || clearBlocked || closed.get()) return
        val stamp = PreloadStamp(generation.get(), preparationRevision.get())
        synchronized(stateLock) {
            if (closed.get() || ready) return
            if (preloadGeneration == stamp) return
            preloadGeneration = stamp
        }
        if (!enqueue {
            if (!isPreparationCurrent(stamp)) {
                synchronized(stateLock) {
                    if (preloadGeneration == stamp) preloadGeneration = null
                }
                return@enqueue
            }
            val preparedFrequencyRevision = frequencyRevision.get()
            val result = runCatching {
                withDelegate {
                    if (isPreparationCurrent(stamp)) {
                        preload()
                        (delegate as? PersonalFrequencyStore)?.frequencyLookupSnapshot()
                    } else {
                        null
                    }
                }
            }
            synchronized(stateLock) {
                if (preloadGeneration == stamp) {
                    preloadGeneration = null
                }
                if (isPreparationCurrent(stamp) && result.isSuccess) {
                    suggestionCache.clear()
                    if (preparedFrequencyRevision == frequencyRevision.get()) frequencyLookup = result.getOrNull()
                    ready = true
                } else if (isPreparationCurrent(stamp)) {
                    ready = false
                }
            }
            if (isPreparationCurrent(stamp) && result.isSuccess) notifyChanges()
        }) {
            synchronized(stateLock) {
                if (preloadGeneration == stamp) {
                    preloadGeneration = null
                }
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
                try {
                    if (isQueryCurrent(queryGeneration, queryRevision)) {
                        val values = runCatching {
                            withDelegate {
                                if (isQueryCurrent(queryGeneration, queryRevision)) {
                                    (delegate as? PagedPersonalizationStore)?.suggestionPage(
                                        key.prefix, key.language, key.offset, MAX_SUGGESTION_LIMIT,
                                    ) ?: PersonalSuggestionPage(if (key.offset == 0) delegate.suggestionsFor(
                                        key.prefix,
                                        key.language,
                                        MAX_SUGGESTION_LIMIT,
                                    ) else emptyList())
                                } else {
                                    null
                                }
                            }
                        }.getOrNull()
                        var published = false
                        synchronized(stateLock) {
                            if (values != null && isQueryCurrent(queryGeneration, queryRevision)) {
                                suggestionCache[key] = values
                                while (suggestionCache.values.sumOf { it.suppressedDictionaryTexts.size + it.suppressedDictionaryEntries.size } > 40000) {
                                    val oldest = suggestionCache.entries.iterator()
                                    oldest.next(); oldest.remove()
                                }
                                published = true
                            }
                        }
                        if (published) notifyChanges()
                    }
                } finally {
                    synchronized(stateLock) {
                        if (pendingQueries[key] == queryStamp) pendingQueries.remove(key)
                    }
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

    private fun invalidateFrequencyLookup(): Long = synchronized(stateLock) {
        frequencyLookup = null
        frequencyRevision.incrementAndGet()
    }

    private fun publishFrequencyLookup(
        operationGeneration: Long,
        expectedRevision: Long,
        snapshot: PersonalFrequencyLookup?,
    ) {
        synchronized(stateLock) {
            if (isGenerationCurrent(operationGeneration) && frequencyRevision.get() == expectedRevision) {
                frequencyLookup = snapshot
            }
        }
    }

    private fun isGenerationCurrent(operationGeneration: Long): Boolean =
        !closed.get() && operationGeneration == generation.get()

    private fun isPreparationCurrent(stamp: PreloadStamp): Boolean =
        !closed.get() && stamp.generation == generation.get() &&
            stamp.revision == preparationRevision.get()

    private inline fun <T> withDelegate(block: () -> T): T = synchronized(delegateLock) { block() }

    private fun invalidateSuggestionCache(clearPending: Boolean = false) {
        synchronized(stateLock) {
            viewRevision.incrementAndGet()
            suggestionCache.clear()
            if (clearPending) pendingQueries.clear()
        }
        notifyChanges()
    }

    private fun notifyChanges() {
        if (closed.get()) return
        changeListeners.forEach { listener -> runCatching { listener() } }
    }

    private data class QueryKey(
        val prefix: String,
        val language: InputLanguage,
        val offset: Int,
    )

    private data class QueryStamp(
        val generation: Long,
        val revision: Long,
    )

    private data class PreloadStamp(
        val generation: Long,
        val revision: Long,
    )

    private class OptionalTask(private val operation: () -> Unit) : Runnable {
        override fun run() = operation()
    }

    private class ControlTask(private val operation: () -> Unit) : Runnable {
        override fun run() = operation()
    }

    private class AwaitableControlTask(operation: () -> Unit) :
        FutureTask<Unit>(Callable { operation(); Unit })

    private companion object {
        const val MAX_PENDING_OPERATIONS = 64
        const val MAX_CACHED_QUERIES = 64
        const val MAX_SUGGESTION_LIMIT = 50
    }
}
