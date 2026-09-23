package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

/**
 * Two prepared sessions share public dictionary data; only explicit selection changes reading.
 *
 * The secondary session is created lazily: it exists solely to serve the
 * related-reading expansion past the last primary page, which most sessions
 * never reach.  A native session carries the compiled schema and dictionary
 * context, so keeping it allocated for every full-pinyin session doubled the
 * engine's native footprint.  Creation is delegated to [createSecondary] and
 * scheduled on [executor] because a native session must never be created on
 * the IME input thread.
 */
internal class ExpandingRimeEngine(
    private var primary: RimeInputEngine,
    private val readings: RelatedReadings,
    private val createSecondary: (EditorContext) -> RimeInputEngine,
    private val executor: ExecutorService,
    private val onSecondaryFailure: (Throwable) -> Unit = {},
) : InputEngine, CompositionEditingEngine, ReadingSelectionEngine, CandidateTextNormalizer {
    private val lifecycleLock = Any()
    @Volatile private var related: RimeInputEngine? = null
    private var secondaryRequested = false
    private var startedContext: EditorContext? = null
    @Volatile private var closed = false
    private var lifecycleGeneration = 0L
    private var alternatives: List<String>? = null
    private val lastPages = HashMap<Int, Int>()
    private var source = -1
    private var page = 0
    override val descriptor get() = primary.descriptor
    override var snapshot = EngineSnapshot.Empty
        private set

    override fun start(context: EditorContext): EngineSnapshot {
        synchronized(lifecycleLock) {
            closed = false
            lifecycleGeneration++
            startedContext = context
        }
        primary.start(context)
        return reset()
    }

    override fun handle(key: EngineKey): EngineUpdate {
        if (source >= 0 && key == EngineKey.Space && snapshot.candidates.isNotEmpty()) return selectCandidate(0)
        clearExpansion()
        return publish(primary.handle(key))
    }

    override fun selectCandidate(index: Int): EngineUpdate {
        if (index !in snapshot.candidates.indices) return EngineUpdate(snapshot, consumed = false)
        if (source >= 0) {
            // Expansion is active, so the secondary session necessarily exists.
            val active = related ?: return EngineUpdate(snapshot, consumed = false)
            val old = primary
            primary = active
            related = old
        }
        clearExpansion()
        return publish(primary.selectCandidate(index))
    }

    override fun changePage(direction: PageDirection): EngineUpdate {
        if (source < 0) {
            if (direction == PageDirection.PREVIOUS || primary.snapshot.hasNextPage) {
                return publish(primary.changePage(direction))
            }
            if (!eligible()) return EngineUpdate(snapshot, consumed = false)
            if (related == null) {
                // The secondary session is needed for the first time. Creating
                // it asynchronously keeps the input thread free of native work;
                // the request is otherwise a no-op and the user's next page
                // press consumes the now-ready session, matching the existing
                // "expand only past the last page" contract.
                requestSecondary()
                return EngineUpdate(snapshot, consumed = false)
            }
            if (alternatives == null) alternatives = readings.alternatives(primary.snapshot.rawInput)
            return moveSource(0, backwards = false)
        }
        if (direction == PageDirection.PREVIOUS && page == 0) return moveSource(source - 1, backwards = true)
        val active = related ?: return EngineUpdate(snapshot, consumed = false)
        if (direction == PageDirection.NEXT && !active.snapshot.hasNextPage) {
            lastPages[source] = page
            return moveSource(source + 1, backwards = false)
        }
        page += if (direction == PageDirection.NEXT) 1 else -1
        val update = active.browsePage(page)
        return publishRelated(update.consumed)
    }

    private fun requestSecondary() {
        val request = synchronized(lifecycleLock) {
            if (secondaryRequested) return@synchronized null
            val context = startedContext ?: return@synchronized null
            secondaryRequested = true
            context to lifecycleGeneration
        } ?: return
        val context = request.first
        val generation = request.second
        try {
            executor.execute {
                // A session that finished while creation was queued must not
                // leak a native session; close reports failure through the
                // same channel as every other native call.
                runCatching { createSecondary(context) }
                    .onSuccess { candidate ->
                        val accepted = synchronized(lifecycleLock) {
                            if (closed || generation != lifecycleGeneration) false
                            else {
                                related = candidate
                                true
                            }
                        }
                        if (!accepted) candidate.close()
                    }
                    .onFailure { error ->
                        val report = synchronized(lifecycleLock) {
                            secondaryRequested = false
                            !closed && generation == lifecycleGeneration
                        }
                        if (report) onSecondaryFailure(error)
                    }
            }
        } catch (_: RejectedExecutionException) {
            // The bounded engine queue is full; the next page press retries.
            synchronized(lifecycleLock) { secondaryRequested = false }
        }
    }

    private fun moveSource(target: Int, backwards: Boolean): EngineUpdate {
        if (target < 0) { source = -1; return publish(EngineUpdate(primary.snapshot)) }
        val active = related ?: return EngineUpdate(snapshot, consumed = false)
        val values = alternatives.orEmpty()
        var next = target
        while (next in values.indices) {
            active.restoreComposition(values[next])
            val targetPage = if (backwards) lastPages[next] ?: 0 else 0
            val update = active.browsePage(targetPage)
            if (update.consumed) {
                source = next
                page = targetPage
                return publishRelated(true)
            }
            next += if (backwards) -1 else 1
        }
        if (backwards) { source = -1; return publish(EngineUpdate(primary.snapshot)) }
        if (source >= 0) {
            active.restoreComposition(values[source])
            active.browsePage(page)
        }
        snapshot = snapshot.copy(hasNextPage = false)
        return EngineUpdate(snapshot, consumed = false)
    }

    private fun publishRelated(consumed: Boolean): EngineUpdate {
        val active = related ?: return EngineUpdate(snapshot, consumed = consumed)
        val result = active.snapshot
        snapshot = primary.snapshot.copy(
            candidates = result.candidates.map { it.copy(id = "related:$source:${it.id}", kind = CandidateKind.RELATED_READING) },
            highlightedIndex = result.highlightedIndex,
            hasPreviousPage = true,
            hasNextPage = result.hasNextPage || source + 1 < alternatives.orEmpty().size,
        )
        return EngineUpdate(snapshot, consumed = consumed)
    }

    private fun eligible(): Boolean = primary.snapshot.rawInput.length in 2..64 &&
        !primary.snapshot.canUndoSelection && primary.snapshot.rawInput.all { it in 'a'..'z' || it == '\'' }

    private fun publish(update: EngineUpdate): EngineUpdate {
        snapshot = update.snapshot.copy(hasNextPage = update.snapshot.hasNextPage ||
            eligible() && (alternatives == null || !alternatives.isNullOrEmpty()))
        return update.copy(snapshot = snapshot)
    }

    private fun clearExpansion() {
        related?.let { if (it.snapshot.isComposing) it.reset() }
        alternatives = null
        lastPages.clear()
        source = -1
        page = 0
    }

    override fun restoreComposition(input: String): EngineUpdate { clearExpansion(); return publish(primary.restoreComposition(input)) }
    override fun undoSelection(): EngineUpdate { clearExpansion(); return publish(primary.undoSelection()) }
    override fun selectSyllable(): EngineUpdate { clearExpansion(); return publish(primary.selectSyllable()) }
    override fun selectReading(index: Int): EngineUpdate { clearExpansion(); return publish(primary.selectReading(index)) }
    override fun normalizeCandidateText(text: String): String = primary.normalizeCandidateText(text)
    override fun reset(): EngineSnapshot { clearExpansion(); primary.reset(); snapshot = EngineSnapshot.Empty; return snapshot }
    override fun close() {
        val pending = synchronized(lifecycleLock) {
            closed = true
            lifecycleGeneration++
            startedContext = null
            related.also { related = null }
        }
        try { primary.close() } finally {
            // Creation may still be queued; the executor task closes whatever
            // it managed to create and never publishes a closed session.
            pending?.close()
            alternatives = null
            lastPages.clear()
            snapshot = EngineSnapshot.Empty
        }
    }
}
