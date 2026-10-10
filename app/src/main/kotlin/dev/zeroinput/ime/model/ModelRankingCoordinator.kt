package dev.zeroinput.ime.model

import dev.zeroinput.engine.api.CandidateScorer
import dev.zeroinput.ime.core.AsyncCandidateRanker
import dev.zeroinput.ime.core.CommittedModelContext
import dev.zeroinput.ime.core.InputSessionController

/** Main-thread lifecycle adapter; asynchronous invalidation never reads controller or editor state. */
class ModelRankingCoordinator(
    createScorer: () -> CandidateScorer,
    post: (Runnable) -> Boolean,
    private val currentController: () -> InputSessionController?,
    private val allowed: () -> Boolean,
    private val resourceRevision: () -> Long = { 0L },
) : AutoCloseable {
    private val context = CommittedModelContext()
    private var lastRevision = -1L
    private var frozen = true
    private var modelRevision = -1L
    private var requestedController: InputSessionController? = null
    private val ranker = AsyncCandidateRanker(createScorer, post, ::deliver)

    @Synchronized fun invalidate() {
        ranker.setEnabled(false)
        ranker.cancel()
        context.clear()
        lastRevision = -1
        requestedController = null
        frozen = true
    }

    @Synchronized fun interaction() { ranker.cancel(); requestedController = null; frozen = true }

    @Synchronized fun typing() { frozen = false }

    @Synchronized fun committed(text: String?) {
        ranker.cancel()
        requestedController = null
        if (text == null || !allowed()) context.clear() else context.append(text)
    }

    @Synchronized fun refresh(surfaceAvailable: Boolean) {
        if (modelRevision != resourceRevision()) {
            ranker.resourcesChanged()
            lastRevision = -1
            modelRevision = resourceRevision()
        }
        if (!allowed()) { invalidate(); return }
        ranker.setEnabled(true)
        if (!surfaceAvailable || frozen) return
        val controller = currentController() ?: return
        val revision = controller.state.candidateRevision
        if (lastRevision == revision) return
        lastRevision = revision
        ranker.cancel()
        requestedController = null
        val candidates = controller.modelCandidates()
        if (candidates.isEmpty()) return
        val prefix = context.copy()
        if (prefix.isEmpty()) return
        requestedController = controller
        ranker.request(revision, prefix, candidates.map { it.text.toCharArray() }.toTypedArray())
    }

    @Synchronized private fun deliver(revision: Long, winner: Int) {
        val controller = currentController()
        if (allowed() && !frozen && modelRevision == resourceRevision() &&
            controller != null && controller === requestedController) controller.applyModelWinner(revision, winner)
    }

    @Synchronized override fun close() { context.clear(); ranker.close() }
}
