package dev.zeroinput.ime.ai.device

import dev.zeroinput.ai.api.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** All mutable task state, including callbacks, is serialized under one owner lock. */
internal class DeviceAgentCoordinator(
    private val provider: DeviceAgentProvider,
    private val broker: DeviceAgentAccessibilityBroker,
    private val executor: Executor,
    private val post: (() -> Unit) -> Boolean,
    private val schedule: (Long, () -> Unit) -> AutoCloseable,
    private val evaluator: DeviceAgentPolicyEvaluator = DeviceAgentPolicyEvaluator(),
    private val text: DeviceAgentDescriptions = DeviceAgentDescriptions(),
    private val stage6Controller: DeviceStage6Controller? = null,
) : AutoCloseable {
    private val lock = Any()
    private var active: Task? = null
    private var closed = false
    private var timelineOwner: String? = null
    private var eventHistory = emptyList<DeviceAgentEvent>()
    private val observers = CopyOnWriteArrayList<(DeviceAgentEvent) -> Unit>()
    private val invalidationObserver = broker.observeInvalidation { synchronized(lock) { active?.fail("Device task expired") } }
    val hasActiveTask: Boolean get() = synchronized(lock) { active != null }

    fun observe(listener: (DeviceAgentEvent) -> Unit): AutoCloseable {
        synchronized(lock) {
            observers += listener
            eventHistory.forEach { runCatching { listener(it) } }
        }
        return AutoCloseable { observers -= listener }
    }

    fun start(task: String, targetPackage: String, policy: DeviceAgentPolicy, listener: (DeviceAgentEvent) -> Unit): AiRequestHandle =
        start(task, targetPackage, policy, emptyList(), emptyMap(), emptyMap(), {}, listener)

    fun start(
        task: String,
        targetPackage: String,
        policy: DeviceAgentPolicy,
        attachments: List<AiAttachment>,
        stage6Targets: Map<DeviceStage6Capability, String> = emptyMap(),
        stage6TargetLabels: Map<DeviceStage6Capability, String> = emptyMap(),
        releaseStage6Targets: () -> Unit = {},
        listener: (DeviceAgentEvent) -> Unit,
    ): AiRequestHandle {
        val handle: Task
        synchronized(lock) {
            if (closed || active != null) {
                post { listener(DeviceAgentEvent.Failed(text.unavailable)) }
                return object : AiRequestHandle { override fun cancel() = Unit }
            }
            if (attachments.size > DeviceAgentLimits.MAX_TASK_ATTACHMENTS ||
                attachments.sumOf { it.bytes.size } > AiLimits.MAX_ATTACHMENT_BYTES) {
                post { listener(DeviceAgentEvent.Failed(text.unavailable)) }
                return object : AiRequestHandle { override fun cancel() = Unit }
            }
            handle = Task(task, targetPackage, policy, attachments, stage6Targets, stage6TargetLabels, releaseStage6Targets, listener)
            active = handle
            timelineOwner = handle.id
            eventHistory = emptyList()
            handle.deadline = schedule(DeviceAgentLimits.MAX_WALL_TIME_MS) { synchronized(lock) { if (active === handle) handle.fail("Task time limit reached") } }
        }
        try { executor.execute { synchronized(lock) { if (active === handle) handle.begin() } } }
        catch (_: RejectedExecutionException) { synchronized(lock) { handle.fail("Device task queue unavailable") } }
        return handle
    }

    fun approve(approvalId: String, accepted: Boolean): Boolean = synchronized(lock) { active?.approve(approvalId, accepted) == true }
    fun stop() { synchronized(lock) { active?.cancel() } }
    fun clearTimeline() { synchronized(lock) { if (active == null) { eventHistory = emptyList(); timelineOwner = null } } }

    override fun close() {
        synchronized(lock) { active?.cancel(); closed = true; eventHistory = emptyList(); timelineOwner = null }
        invalidationObserver.close()
        observers.clear()
    }

    private inner class Task(
        private var taskText: String, private val target: String, private val policy: DeviceAgentPolicy,
        attachments: List<AiAttachment>,
        stage6Targets: Map<DeviceStage6Capability, String>,
        stage6TargetLabels: Map<DeviceStage6Capability, String>,
        private val releaseStage6Targets: () -> Unit,
        private val listener: (DeviceAgentEvent) -> Unit,
    ) : AiRequestHandle {
        private val attachments = attachments.map { attachment ->
            AiAttachment(attachment.mimeType, attachment.bytes.copyOf(), attachment.displayName)
        }
        private val stage6Targets = stage6Targets.toMap()
        private val stage6TargetLabels = stage6TargetLabels.toMap()
        val id: String = UUID.randomUUID().toString()
        var deadline: AutoCloseable? = null
        private var ended = false
        private var lease: DeviceTaskLease? = null
        private var providerHandle: AiRequestHandle? = null
        private var snapshot: DeviceScreenSnapshot? = null
        private var snapshotToken = -1L
        private var round = 0
        private var actionCount = 0
        private var history = emptyList<DeviceAgentTurn>()
        private var pending: DeviceActionCall? = null
        private var continuation = emptyList<DeviceReasoningItem>()
        private var approvalId: String? = null
        private var taskApproved = false
        private var availableToolNames = emptySet<String>()
        private var transientAttachment: AiAttachment? = null
        private val usedCallIds = mutableSetOf<String>()
        private var requestSerial = 0L

        fun begin() {
            if (!valid()) return
            if (taskText.isBlank() || taskText.length > DeviceAgentLimits.MAX_TASK_CHARS) return fail("Invalid device task")
            state(DeviceAgentState.CONFIGURING)
            val ready = evaluator.checkReady(policy, broker.connected, broker.connected, broker.unlocked(), target, broker.currentWindow()?.packageName)
            if (!ready.allowed) return fail(ready.reason.name)
            val owner = broker.beginTask(target, policy, id) ?: return fail("Device task authorization unavailable")
            lease = owner
            if (policy.approvalMode == DeviceApprovalMode.TASK || attachments.isNotEmpty() || stage6Targets.isNotEmpty()) {
                val approval = "task-$id"
                approvalId = approval
                state(DeviceAgentState.PLAN_REVIEW)
                val description = text.task(taskText, target, policy, attachments, stage6TargetLabels.values.toList())
                emit(DeviceAgentEvent.TaskApprovalRequired(approval, description))
                if (!broker.presentApproval(owner, approval, text.taskTitle, description) { accepted ->
                        synchronized(lock) { if (valid()) approvalConsumed(approval, accepted) }
                    }) fail("Approval surface unavailable")
            } else { broker.showStatus(owner, text.running); observe() }
        }

        private fun observe() {
            val owner = lease ?: return
            if (!valid() || !broker.isCurrent(owner)) return fail("Device task expired")
            releaseSnapshot()
            state(DeviceAgentState.OBSERVING)
            broker.capture(owner) { token, value -> synchronized(lock) {
                if (!valid()) { value?.clearScreenshot(); return@synchronized }
                if (value == null || !broker.isCurrent(owner)) return@synchronized fail("Observation unavailable")
                snapshotToken = token
                snapshot = value
                requestModel()
            } }
        }

        private fun requestModel() {
            if (!valid()) return
            val frame = snapshot ?: return fail("Observation expired")
            if (round >= DeviceAgentLimits.MAX_MODEL_ROUNDS || actionCount >= DeviceAgentLimits.MAX_ACTIONS) return fail("Task action limit reached")
            val tools = DeviceActionCatalog.definitions.filter { it.category in policy.allowedCategories } +
                DeviceStage6ToolCatalog.entries
                    .filter { policy.permitsStage6(it.capability) }
                    .filter { it.fixedTarget != "selected_uri" || stage6Targets[it.capability] != null }
                    .map(DeviceStage6ToolCatalog.Entry::definition)
            availableToolNames = tools.mapTo(linkedSetOf(), DeviceActionDefinition::name)
            if (tools.isEmpty()) return fail("No device actions are enabled")
            val extraAttachment = transientAttachment?.let {
                AiAttachment(it.mimeType, it.bytes.copyOf(), it.displayName)
            }
            val requestAttachments = attachments + listOfNotNull(extraAttachment)
            val request = runCatching {
                DeviceAgentModelRequest(taskText, target, frame, tools, history, attachments = requestAttachments)
            }
                .getOrElse {
                    extraAttachment?.bytes?.fill(0)
                    return fail("Invalid device transcript")
                }
            transientAttachment?.bytes?.fill(0)
            transientAttachment = null
            round++
            state(DeviceAgentState.PLAN_REVIEW)
            val serial = ++requestSerial
            var delivered = false
            val handle = provider.request(request) { event -> synchronized(lock) {
                if (!valid() || serial != requestSerial || delivered) return@synchronized
                when (event) {
                    DeviceAgentProviderEvent.Started -> Unit
                    is DeviceAgentProviderEvent.Completed -> { delivered = true; handleResponse(event.response) }
                    is DeviceAgentProviderEvent.Failed -> { delivered = true; fail("AI request failed") }
                    DeviceAgentProviderEvent.Cancelled -> { delivered = true; fail("AI request cancelled") }
                }
            } }
            extraAttachment?.bytes?.fill(0)
            if (valid() && !delivered && serial == requestSerial) providerHandle = handle else handle.cancel()
        }

        private fun handleResponse(response: DeviceAgentModelResponse) {
            val owner = lease ?: return
            if (!valid() || !broker.isCurrent(owner)) return fail("Device task expired")
            if (response.completed) return finish(DeviceAgentEvent.Completed(response.message ?: text.completed))
            val raw = response.calls.singleOrNull() ?: return fail("AI returned an invalid action count")
            if (!usedCallIds.add(raw.callId)) return fail("AI returned a reused action call")
            if (raw.toolName !in availableToolNames) return fail("AI returned an unavailable action")
            val frame = snapshot ?: return fail("Observation expired")
            val call = raw.copy(snapshotId = frame.snapshotId, round = round - 1)
            val definition = DeviceActionCatalog.find(call.toolName)
            val stage6 = DeviceStage6ToolCatalog.find(call.toolName)
            val decision = if (stage6 != null) {
                evaluator.checkStage6Action(policy, stage6, call, frame, broker.currentWindow()?.packageName)
            } else {
                evaluator.checkAction(policy, definition, call, frame, broker.currentWindow()?.packageName, taskApproved)
            }
            if (!decision.allowed) return fail(decision.reason.name)
            pending = call
            continuation = response.continuation
            if (decision.requiresApproval) {
                approvalId = call.callId
                state(DeviceAgentState.ACTION_APPROVAL)
                val description = text.action(call, frame, stage6TargetLabels[stage6?.capability])
                emit(DeviceAgentEvent.ApprovalRequired(call, requireNotNull(definition), description))
                val stage6Operation = stage6 != null
                if (!broker.presentApproval(
                        owner, call.callId, text.actionTitle, description,
                        call = call.takeUnless { stage6Operation }, snapshotToken = snapshotToken,
                        oneShotOnly = stage6Operation,
                    ) { accepted ->
                        synchronized(lock) { if (valid()) approvalConsumed(call.callId, accepted) }
                    }) fail("Approval surface unavailable")
            } else execute()
        }

        fun approve(id: String, accepted: Boolean): Boolean {
            if (!valid() || approvalId != id) return false
            // Approval from an Activity is routed through the same broker-owned one-use callback.
            return broker.decide(lease ?: return false, id, accepted)
        }

        fun approvalConsumed(id: String, accepted: Boolean) {
            if (!valid() || approvalId != id) return
            approvalId = null
            if (!accepted) return fail("Action declined or expired")
            val owner = lease ?: return
            broker.showStatus(owner, text.running)
            if (pending == null) { taskApproved = true; observe() } else execute()
        }

        private fun execute() {
            if (!valid()) return
            val owner = lease ?: return
            val call = pending ?: return
            val frame = snapshot ?: return fail("Observation expired")
            pending = null
            if (++actionCount > DeviceAgentLimits.MAX_ACTIONS) return fail("Task action limit reached")
            state(DeviceAgentState.EXECUTING)
            val stage6 = DeviceStage6ToolCatalog.find(call.toolName)
            if (stage6 != null) {
                executeStage6(stage6, call)
                return
            }
            DeviceAgentActionDispatcher(broker, evaluator).dispatch(owner, snapshotToken, frame, call) { result -> synchronized(lock) {
                if (!valid()) return@synchronized
                if (result.callId != call.callId) return@synchronized fail("Invalid action result")
                history = history + DeviceAgentTurn(call, result, continuation)
                continuation = emptyList()
                emit(DeviceAgentEvent.ActionFinished(result))
                if (result.status != DeviceActionResultStatus.SUCCESS) fail(result.summary) else observe()
            } }
        }

        private fun executeStage6(entry: DeviceStage6ToolCatalog.Entry, call: DeviceActionCall) {
            val controller = stage6Controller ?: return fail("Stage 6 operation unavailable")
            val generation = controller.currentGeneration
            val stageTarget = runCatching {
                DeviceStage6Target(
                    capability = entry.capability,
                    id = stage6Targets[entry.capability]?.takeIf { entry.fixedTarget == "selected_uri" }
                        ?: entry.targetId(call.arguments),
                    packageName = entry.packageName(call.arguments, target),
                )
            }.getOrElse { return fail("Invalid stage 6 target") }
            val request = runCatching {
                DeviceStage6OperationRequest(
                    taskId = id,
                    generation = generation,
                    taskTargetPackage = target,
                    capability = entry.capability,
                    operation = entry.operation,
                    target = stageTarget,
                    fields = entry.fields(call.arguments) + stage6Targets[entry.capability]?.let {
                        if (entry.fixedTarget == "selected_uri") mapOf("selected_uri" to it) else emptyMap()
                    }.orEmpty(),
                )
            }.getOrElse { return fail("Invalid stage 6 operation") }
            val grant = runCatching {
                DeviceStage6Grant(
                    taskId = id,
                    generation = generation,
                    taskTargetPackage = target,
                    capability = entry.capability,
                    operations = setOf(entry.operation),
                    targets = setOf(stageTarget),
                    expiresAtElapsedMillis = System.nanoTime() / 1_000_000L + DeviceAgentLimits.MAX_STAGE6_GRANT_MS,
                )
            }.getOrElse { return fail("Invalid stage 6 grant") }
            val remainingAttachmentBytes = AiLimits.MAX_ATTACHMENT_BYTES - attachments.sumOf { it.bytes.size }
            controller.execute(
                policy,
                grant,
                request,
                "stage6-${call.callId}",
                approved = true,
                maxAttachmentBytes = remainingAttachmentBytes,
            ) { stage6Result ->
                synchronized(lock) {
                    if (!valid()) {
                        stage6Result.attachment?.bytes?.fill(0)
                        return@synchronized
                    }
                    stage6Result.attachment?.let { attachment ->
                        transientAttachment?.bytes?.fill(0)
                        transientAttachment = runCatching {
                            AiAttachment(attachment.mimeType, attachment.bytes.copyOf(), attachment.displayName)
                        }.getOrNull()
                        attachment.bytes.fill(0)
                    }
                    val result = DeviceActionResult(
                        callId = call.callId,
                        status = stage6Status(stage6Result.status),
                        summary = stage6Result.summary,
                    )
                    history = history + DeviceAgentTurn(call, result, continuation)
                    continuation = emptyList()
                    emit(DeviceAgentEvent.ActionFinished(result))
                    if (result.status != DeviceActionResultStatus.SUCCESS) fail(result.summary) else observe()
                }
            }
        }

        private fun stage6Status(status: DeviceStage6ResultStatus): DeviceActionResultStatus = when (status) {
            DeviceStage6ResultStatus.SUCCESS -> DeviceActionResultStatus.SUCCESS
            DeviceStage6ResultStatus.REJECTED -> DeviceActionResultStatus.REJECTED
            DeviceStage6ResultStatus.EXPIRED -> DeviceActionResultStatus.EXPIRED
            DeviceStage6ResultStatus.UNAVAILABLE,
            DeviceStage6ResultStatus.FAILED -> DeviceActionResultStatus.FAILED
        }

        fun fail(reason: String) { if (valid()) finish(DeviceAgentEvent.Failed(text.stopped), failed = true) }
        override fun cancel() = synchronized(lock) { if (valid()) finish(DeviceAgentEvent.State(DeviceAgentState.PAUSED, text.stopped), failed = true) }
        private fun valid(): Boolean = !ended && !closed && active === this
        private fun state(state: DeviceAgentState) = emit(DeviceAgentEvent.State(state, text.status(state)))

        private fun finish(event: DeviceAgentEvent, failed: Boolean = false) {
            if (!valid()) return
            ended = true
            requestSerial++
            deadline?.close()
            deadline = null
            providerHandle?.cancel()
            providerHandle = null
            releaseSnapshot()
            history = emptyList()
            continuation = emptyList()
            attachments.forEach { it.bytes.fill(0) }
            transientAttachment?.bytes?.fill(0)
            transientAttachment = null
            pending = null
            approvalId = null
            taskText = ""
            usedCallIds.clear()
            availableToolNames = emptySet()
            active = null
            val owner = lease
            lease = null
            stage6Controller?.endTask(id)
            runCatching(releaseStage6Targets)
            if (owner != null) broker.revokeTask(owner)
            // Keep only bounded outcome metadata after termination, never task text or approval parameters.
            eventHistory = emptyList()
            if (failed) emit(DeviceAgentEvent.State(DeviceAgentState.FAILED, text.stopped))
            emit(event)
        }

        private fun emit(event: DeviceAgentEvent) {
            if (timelineOwner != id || closed) return
            if (event !is DeviceAgentEvent.ApprovalRequired &&
                event !is DeviceAgentEvent.TaskApprovalRequired &&
                event !is DeviceAgentEvent.ActionFinished &&
                event !is DeviceAgentEvent.Completed) {
                eventHistory = (eventHistory + event).takeLast(DeviceAgentLimits.MAX_EVENT_HISTORY)
            }
            post { synchronized(lock) {
                if (timelineOwner != id || closed) return@synchronized
                runCatching { listener(event) }
                observers.forEach { runCatching { it(event) } }
            } }
        }

        private fun releaseSnapshot() { snapshot?.clearScreenshot(); snapshot = null; snapshotToken = -1 }
    }
}
