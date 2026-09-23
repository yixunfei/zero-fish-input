package dev.zeroinput.ime

import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.InputEngine
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.ime.core.PreparedInputEngine
import dev.zeroinput.ime.core.privacy.SessionPrivacy
import dev.zeroinput.ime.concurrency.BoundedExecutors
import dev.zeroinput.ime.settings.ChineseEngineChoice
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException

/** Immutable identity and policy captured when a warm-up is requested. */
internal data class EngineWarmupRequest(
    val sessionToken: Long,
    val language: InputLanguage,
    val languagePackKey: String?,
    val packageName: String?,
    val privacy: SessionPrivacy,
    val chineseOptions: ChineseInputOptions = ChineseInputOptions(),
    val retryInitialization: Boolean = false,
    val chineseEngine: ChineseEngineChoice = ChineseEngineChoice.RIME,
)

internal sealed interface EngineWarmupResult {
    val ticket: Long
    val request: EngineWarmupRequest

    data class Prepared(
        override val ticket: Long,
        override val request: EngineWarmupRequest,
        val engine: PreparedInputEngine,
    ) : EngineWarmupResult

    data class Unavailable(
        override val ticket: Long,
        override val request: EngineWarmupRequest,
    ) : EngineWarmupResult
}

/**
 * Serializes expensive engine creation and keeps at most one queued request.
 * A request superseded by a newer session is cancelled and any engine that
 * nevertheless finishes is closed before it can escape the coordinator.
 */
internal class EngineWarmupCoordinator(
    private val executor: ExecutorService,
    private val prepare: (EngineWarmupRequest) -> InputEngine?,
    private val dispatch: (EngineWarmupResult) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private var nextTicket = 0L
    private var currentTicket = 0L
    private var currentTask: Future<*>? = null
    private var closed = false

    /** Starts the latest request and returns its monotonic ticket. */
    fun request(request: EngineWarmupRequest): Long? {
        var rejected = false
        val ticket = synchronized(lock) {
            if (closed) return null
            val value = ++nextTicket
            currentTicket = value
            currentTask?.cancel(true)
            purgeCancelledTasks()
            currentTask = try {
                executor.submit { prepareAndDispatch(value, request) }
            } catch (_: RejectedExecutionException) {
                rejected = true
                null
            }
            value
        }
        if (rejected) {
            // Keep the failure observable without invoking client code while
            // the coordinator lock is held (a dispatcher may synchronously
            // schedule another request in tests or during teardown).
            runCatching { dispatch(EngineWarmupResult.Unavailable(ticket, request)) }
        }
        return ticket
    }

    /** Cancels the current request without shutting down a shared executor. */
    fun cancel() = synchronized(lock) {
        currentTicket = ++nextTicket
        currentTask?.cancel(true)
        currentTask = null
        purgeCancelledTasks()
    }

    override fun close() = synchronized(lock) {
        if (closed) return
        closed = true
        currentTicket = ++nextTicket
        currentTask?.cancel(true)
        currentTask = null
        purgeCancelledTasks()
    }

    private fun prepareAndDispatch(ticket: Long, request: EngineWarmupRequest) {
        if (!isCurrent(ticket)) return
        var candidate: InputEngine? = null
        val prepared = runCatching {
            if (!request.privacy.suggestionsAllowed) return@runCatching null
            candidate = prepare(request)
            val engine = candidate ?: return@runCatching null
            val snapshot = engine.start(
                EditorContext(
                    language = request.language,
                    isSensitive = request.privacy.isSensitive,
                    learningAllowed = request.privacy.learningAllowed,
                    packageName = request.packageName,
                    predictionsAllowed = request.privacy.predictionsAllowed,
                ),
            )
            PreparedInputEngine(
                language = request.language,
                languagePackKey = request.languagePackKey,
                packageName = request.packageName,
                privacy = request.privacy,
                snapshot = snapshot,
                engine = engine,
            ).also { candidate = null }
        }.getOrNull()

        // A failed start or a cancelled/stale request must never retain a
        // partially initialized native engine.
        candidate?.let { runCatching { it.close() } }
        if (!isCurrent(ticket)) {
            prepared?.close()
            return
        }
        val result = if (prepared == null) {
            EngineWarmupResult.Unavailable(ticket, request)
        } else {
            EngineWarmupResult.Prepared(ticket, request, prepared)
        }
        runCatching { dispatch(result) }
            .onFailure { if (result is EngineWarmupResult.Prepared) result.engine.close() }
    }

    private fun isCurrent(ticket: Long): Boolean = synchronized(lock) {
        !closed && ticket == currentTicket
    }

    private fun purgeCancelledTasks() {
        // ThreadPoolExecutor.purge is intentionally accessed without a hard
        // dependency so tests can inject a deterministic ExecutorService.
        BoundedExecutors.purge(executor)
    }
}
