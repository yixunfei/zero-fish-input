package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.*
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

internal enum class AiToolRunState { IDLE, THINKING, APPROVAL, EXECUTING, COMPLETED, FAILED, STOPPED }
internal enum class AiToolRunFailure { UNAVAILABLE, INVALID_CALL, LIMIT, MODEL, EXECUTION, UNCERTAIN }
internal class AiToolRunEvent(val state: AiToolRunState, val message: String = "",
    val approval: String? = null, val call: AiToolCall? = null, val tool: AiToolDefinition? = null,
    val result: AiToolResult? = null, val failure: AiToolRunFailure? = null) {
    override fun toString() = "AiToolRunEvent(state=$state)"
}

/** One bounded serial task. Every callback is fenced before it can change state. */
internal class AiToolRun(
    private val provider: AiToolProvider,
    private val allowed: () -> Boolean,
    private val post: (() -> Unit) -> Boolean,
    private val schedule: (Long, () -> Unit) -> AutoCloseable,
    private val changed: (AiToolRunEvent) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val generation = AtomicLong()
    private var active: Task? = null
    private var closed = false
    val running get() = synchronized(lock) { active != null }

    fun start(request: AiToolRequest, executors: Map<String, AiToolExecutor>): Boolean = synchronized(lock) {
        if (closed || active != null || !allowed() || request.turns.isNotEmpty() ||
            request.tools.any { it.name !in executors }) return false
        val schemas = runCatching { request.tools.associate { it.name to AiToolJson.schema(it.schema) } }.getOrNull() ?: return false
        val task = Task(generation.incrementAndGet(), request, executors.toMap(), schemas)
        active = task
        try {
            val timer = schedule(300_000) {
                val expire = { synchronized(lock) { if (active === task) task.fail(AiToolRunFailure.LIMIT) } }
                if (!post(expire)) expire()
            }
            if (active === task) task.deadline = timer else timer.close()
        } catch (_: Exception) { task.fail(AiToolRunFailure.UNAVAILABLE); return false }
        task.next()
        true
    }

    fun approve(token: String, accepted: Boolean): Boolean = synchronized(lock) {
        val task = active ?: return false
        if (!task.current()) { task.fail(AiToolRunFailure.UNAVAILABLE); return false }
        task.approve(token, accepted)
    }
    fun stop(): Unit = synchronized(lock) {
        active?.let { task ->
            val uncertain = task.state == AiToolRunState.EXECUTING && task.pendingTool?.effect?.let { it != AiToolEffect.READ } == true
            task.end(AiToolRunEvent(AiToolRunState.STOPPED, failure = if (uncertain) AiToolRunFailure.UNCERTAIN else null))
        } ?: generation.incrementAndGet()
        Unit
    }
    override fun close() = synchronized(lock) { stop(); closed = true; generation.incrementAndGet(); Unit }

    private inner class Task(val token: Long, private var request: AiToolRequest?,
        private var executors: Map<String, AiToolExecutor>, private var schemas: Map<String, org.json.JSONObject>) {
        var deadline: AutoCloseable? = null
        var state = AiToolRunState.IDLE
        var pendingTool: AiToolDefinition? = null
        private var pending: AiToolCall? = null
        private var approval: String? = null
        private var continuation = emptyList<AiToolContinuation>()
        private var turns = emptyList<AiToolTurn>()
        private val used = mutableSetOf<String>()
        private var rounds = 0
        private var serial = 0L
        private var handle: AiRequestHandle? = null

        fun current() = active === this && generation.get() == token && !closed && allowed()
        private fun emit(event: AiToolRunEvent): Boolean {
            state = event.state
            val accepted = post { synchronized(lock) { if (generation.get() == token && !closed) changed(event) } }
            if (!accepted) fail(AiToolRunFailure.UNAVAILABLE)
            return accepted
        }
        fun next() {
            if (!current()) return fail(AiToolRunFailure.UNAVAILABLE)
            if (rounds >= 20 || turns.size >= 40) return fail(AiToolRunFailure.LIMIT)
            val base = request ?: return fail(AiToolRunFailure.UNAVAILABLE)
            rounds++; val ticket = ++serial
            val next = runCatching { AiToolRequest(base.input, base.tools, base.workspace, turns, base.model) }.getOrNull()
                ?: return fail(AiToolRunFailure.LIMIT)
            if (!emit(AiToolRunEvent(AiToolRunState.THINKING)) || !current()) return
            try {
                val launched = provider.request(next, modelCallback(ticket))
                if (current() && serial == ticket && state == AiToolRunState.THINKING) handle = launched else launched.cancel()
            } catch (_: Exception) { fail(AiToolRunFailure.MODEL) }
        }

        private fun modelCallback(ticket: Long): (Result<AiToolResponse>) -> Unit {
            val owner = WeakReference(this); val dispatcher = post; val mutex = lock
            return { result ->
                val accepted = dispatcher { owner.get()?.let { task -> synchronized(mutex) { task.modelFinished(ticket, result) } } }
                if (!accepted) owner.get()?.let { task -> synchronized(mutex) { task.deliveryFailed() } }
            }
        }
        private fun modelFinished(ticket: Long, result: Result<AiToolResponse>) {
            if (active !== this || ticket != serial || state != AiToolRunState.THINKING) return
            if (!current()) return fail(AiToolRunFailure.UNAVAILABLE)
            handle = null; serial++
            result.fold(::response, { fail(AiToolRunFailure.MODEL) })
        }
        private fun deliveryFailed() { if (active === this) fail(AiToolRunFailure.UNAVAILABLE) }

        private fun response(response: AiToolResponse) {
            val call = response.call ?: return end(AiToolRunEvent(AiToolRunState.COMPLETED, response.message))
            val tool = request?.tools?.singleOrNull { it.name == call.name }
            if (tool == null || !used.add(call.id) || used.size > 40 ||
                runCatching { AiToolJson.arguments(schemas.getValue(call.name), call.arguments) }.isFailure)
                return fail(AiToolRunFailure.INVALID_CALL)
            pending = call; pendingTool = tool; continuation = response.continuation
            approval = UUID.randomUUID().toString()
            emit(AiToolRunEvent(AiToolRunState.APPROVAL, response.message, approval, call, tool))
        }

        fun approve(token: String, accepted: Boolean): Boolean {
            if (state != AiToolRunState.APPROVAL || approval != token) return false
            val call = pending ?: return false
            approval = null
            if (!accepted) {
                finished(AiToolResult(call.id, AiToolResultStatus.REJECTED, "The user declined this operation."))
                return true
            }
            val executor = executors[call.name] ?: run { fail(AiToolRunFailure.UNAVAILABLE); return false }
            val ticket = ++serial
            if (!emit(AiToolRunEvent(AiToolRunState.EXECUTING, call = call, tool = pendingTool)) || !current()) return false
            try {
                val launched = executor.execute(call, executionCurrent(ticket), executionCallback(ticket))
                if (current() && serial == ticket && state == AiToolRunState.EXECUTING) handle = launched else launched.cancel()
            } catch (_: Exception) { fail(if (pendingTool?.effect == AiToolEffect.READ) AiToolRunFailure.EXECUTION else AiToolRunFailure.UNCERTAIN) }
            return true
        }

        private fun executionCurrent(ticket: Long): () -> Boolean {
            val owner = WeakReference(this); val mutex = lock
            return { owner.get()?.let { task -> synchronized(mutex) { task.current() && ticket == task.serial && task.state == AiToolRunState.EXECUTING } } == true }
        }
        private fun executionCallback(ticket: Long): (AiToolResult) -> Unit {
            val owner = WeakReference(this); val dispatcher = post; val mutex = lock
            return { result ->
                val accepted = dispatcher { owner.get()?.let { task -> synchronized(mutex) { task.executionFinished(ticket, result) } } }
                if (!accepted) owner.get()?.let { task -> synchronized(mutex) { task.deliveryFailed() } }
            }
        }
        private fun executionFinished(ticket: Long, result: AiToolResult) {
            if (active !== this || ticket != serial || state != AiToolRunState.EXECUTING) return
            if (!current()) return fail(AiToolRunFailure.UNAVAILABLE)
            handle = null; serial++; finished(result)
        }

        private fun finished(result: AiToolResult) {
            val call = pending ?: return fail(AiToolRunFailure.INVALID_CALL)
            if (result.callId != call.id) return fail(AiToolRunFailure.INVALID_CALL)
            if (result.status == AiToolResultStatus.UNKNOWN) return fail(AiToolRunFailure.UNCERTAIN)
            if (pendingTool?.effect != AiToolEffect.READ && result.status == AiToolResultStatus.FAILED) return fail(AiToolRunFailure.EXECUTION)
            turns = turns + AiToolTurn(call, result, continuation)
            if (!emit(AiToolRunEvent(AiToolRunState.EXECUTING, call = call, tool = pendingTool, result = result))) return
            pending = null; pendingTool = null; continuation = emptyList()
            val ticket = serial
            val accepted = post { synchronized(lock) {
                if (active === this && ticket == serial) {
                    if (current()) next() else fail(AiToolRunFailure.UNAVAILABLE)
                }
            } }
            if (!accepted) fail(AiToolRunFailure.UNAVAILABLE)
        }
        fun fail(reason: AiToolRunFailure) = end(AiToolRunEvent(AiToolRunState.FAILED, failure = reason))
        fun end(event: AiToolRunEvent) {
            if (active !== this) return
            active = null; serial++; state = event.state
            runCatching { deadline?.close() }; deadline = null
            val oldHandle = handle; handle = null
            request = null; executors = emptyMap(); schemas = emptyMap(); turns = emptyList()
            pending = null; pendingTool = null; continuation = emptyList(); approval = null; used.clear()
            // Invalidate executors via active/serial before cancellation can deliver a callback.
            val endedGeneration = generation.incrementAndGet()
            runCatching { oldHandle?.cancel() }
            post { synchronized(lock) { if (generation.get() == endedGeneration && !closed) changed(event) } }
        }
    }
}
