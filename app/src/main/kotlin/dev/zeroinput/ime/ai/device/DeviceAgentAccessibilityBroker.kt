package dev.zeroinput.ime.ai.device

import dev.zeroinput.ai.api.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

internal data class DeviceWindowIdentity(val packageName: String, val windowId: Int)
internal class DeviceTaskLease(
    val taskId: String, val target: DeviceWindowIdentity, val policy: DeviceAgentPolicy,
) {
    override fun toString(): String = "DeviceTaskLease(redacted)"
}

internal interface DeviceAgentAccessibilityPort {
    /** Window/event metadata only; this method must never acquire a root node. */
    fun currentWindow(): DeviceWindowIdentity?
    fun unlocked(): Boolean
    fun snapshot(includeScreenshot: Boolean, current: () -> Boolean): DeviceScreenSnapshot
    fun perform(call: DeviceActionCall, snapshot: DeviceScreenSnapshot, current: () -> Boolean): DeviceActionResult
    fun presentApproval(title: String, details: String, decide: (Boolean) -> Unit)
    fun showStatus(message: String)
    fun clearOverlay()
}

/** Exclusive task lease and single-use action approval at the last platform boundary. */
internal class DeviceAgentAccessibilityBroker(
    private val executor: Executor,
    private val post: (() -> Unit) -> Boolean,
    private val runtimeAllowed: (DeviceAgentPolicy) -> Boolean = { true },
    private val evaluator: DeviceAgentPolicyEvaluator = DeviceAgentPolicyEvaluator(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val lock = Any()
    @Volatile private var port: DeviceAgentAccessibilityPort? = null
    private var lease: DeviceTaskLease? = null
    private var scene = 1L
    private var executing: String? = null
    private var settling = false
    private var taskApproved = false
    private var pendingApproval: String? = null
    private var pendingDecision: ((Boolean) -> Unit)? = null
    private var approvedCall: DeviceActionCall? = null
    private val observers = CopyOnWriteArrayList<() -> Unit>()
    val connected: Boolean get() = port != null
    val currentGeneration: Long get() = synchronized(lock) { scene }

    fun attach(value: DeviceAgentAccessibilityPort): AutoCloseable {
        invalidate()
        port = value
        return AutoCloseable { if (port === value) { invalidate(); port = null } }
    }

    fun observeInvalidation(listener: () -> Unit): AutoCloseable {
        observers += listener
        return AutoCloseable { observers -= listener }
    }

    fun invalidate() {
        val old = synchronized(lock) {
            val previous = lease
            lease = null
            scene++
            executing = null
            settling = false
            pendingApproval = null
            pendingDecision = null
            approvedCall = null
            taskApproved = false
            previous
        }
        if (old != null) clearOverlay(old)
        observers.forEach { runCatching(it) }
    }

    fun beginTask(target: String, policy: DeviceAgentPolicy, taskId: String): DeviceTaskLease? {
        if (!evaluator.checkTarget(policy, target).allowed || !runtimeAllowed(policy)) return null
        val source = port ?: return null
        if (!source.unlocked()) return null
        val window = source.currentWindow()?.takeIf { it.packageName == target } ?: return null
        return synchronized(lock) {
            if (lease != null || source !== port || !runtimeAllowed(policy)) return@synchronized null
            DeviceTaskLease(taskId, window, policy).also { lease = it; scene++ }
        }
    }

    /** Checks whether a task could start without reading the target UI tree. */
    fun canStart(policy: DeviceAgentPolicy, target: String): Boolean {
        if (!evaluator.checkTarget(policy, target).allowed || !runtimeAllowed(policy)) return false
        val source = port ?: return false
        return source.unlocked() && source === port && runtimeAllowed(policy)
    }

    fun revokeTask(owner: DeviceTaskLease) {
        val revoked = synchronized(lock) {
            if (lease !== owner) return
            lease = null
            scene++
            executing = null
            settling = false
            pendingApproval = null
            pendingDecision = null
            approvedCall = null
            taskApproved = false
            true
        }
        if (revoked) {
            clearOverlay(owner)
            observers.forEach { runCatching(it) }
        }
    }

    fun currentWindow(): DeviceWindowIdentity? = port?.currentWindow()
    fun unlocked(): Boolean = port?.unlocked() == true

    /** Event sources never read event text or nodes. Own action content changes require a fresh capture afterward. */
    fun observeWindowEvent(window: DeviceWindowIdentity?, contentChanged: Boolean = false) {
        val revoke = synchronized(lock) {
            val owner = lease ?: return
            window != owner.target || contentChanged && executing == null && !settling
        }
        if (revoke) invalidate()
    }

    fun isCurrent(owner: DeviceTaskLease): Boolean {
        val source = port ?: return false
        return synchronized(lock) { lease === owner } && runtimeAllowed(owner.policy) &&
            source.unlocked() && source.currentWindow() == owner.target && source === port
    }

    private fun snapshotCurrent(owner: DeviceTaskLease, token: Long, snapshot: DeviceScreenSnapshot? = null): Boolean =
        isCurrent(owner) && synchronized(lock) { scene == token } &&
            (snapshot == null || snapshot.packageName == owner.target.packageName && snapshot.windowId == owner.target.windowId &&
                nowMillis() - snapshot.capturedAtEpochMillis in 0..DeviceAgentLimits.MAX_SNAPSHOT_AGE_MS)

    fun capture(owner: DeviceTaskLease, deliver: (Long, DeviceScreenSnapshot?) -> Unit) {
        val source = port
        val token = synchronized(lock) { if (lease === owner) ++scene else -1 }
        val current = { source === port && snapshotCurrent(owner, token) }
        if (source == null || !current()) { deliver(token, null); return }
        submit({ deliver(token, null) }) {
            val snapshot = if (current()) runCatching { source.snapshot(owner.policy.allowScreenshots, current) }.getOrNull() else null
            val delivery = {
                if (current() && snapshot?.packageName == owner.target.packageName && snapshot.windowId == owner.target.windowId) {
                    synchronized(lock) { if (lease === owner) settling = false }
                    deliver(token, snapshot)
                } else { snapshot?.clearScreenshot(); deliver(token, null) }
            }
            if (!post(delivery)) { snapshot?.clearScreenshot(); deliver(token, null) }
        }
    }

    fun perform(
        owner: DeviceTaskLease, token: Long, call: DeviceActionCall, snapshot: DeviceScreenSnapshot,
        deliver: (DeviceActionResult) -> Unit,
    ) {
        val source = port
        if (source == null || !snapshotCurrent(owner, token, snapshot)) { deliver(expired(call)); return }
        val decision = synchronized(lock) {
            val checked = evaluator.checkAction(owner.policy, DeviceActionCatalog.find(call.toolName), call, snapshot,
                owner.target.packageName, taskApproved)
            when {
                !checked.allowed -> checked
                executing != null -> DevicePolicyDecision.deny(DevicePolicyReason.SNAPSHOT_EXPIRED)
                checked.requiresApproval && approvedCall !== call -> DevicePolicyDecision.deny(DevicePolicyReason.APPROVAL_REQUIRED)
                else -> { approvedCall = null; executing = call.callId; checked }
            }
        }
        if (!decision.allowed) { deliver(DeviceActionResult(call.callId, DeviceActionResultStatus.REJECTED, decision.reason.name)); return }
        val current = { source === port && isCurrent(owner) && synchronized(lock) { executing == call.callId && scene == token } }
        submit({ finishPerform(owner, call); deliver(expired(call)) }) {
            val result = if (current()) runCatching { source.perform(call, snapshot, current) }.getOrElse {
                DeviceActionResult(call.callId, DeviceActionResultStatus.FAILED, "Action failed")
            } else expired(call)
            val valid = isCurrent(owner)
            finishPerform(owner, call)
            val delivery = { deliver(if (valid && isCurrent(owner)) result else expired(call)) }
            if (!post(delivery)) deliver(expired(call))
        }
    }

    private fun finishPerform(owner: DeviceTaskLease, call: DeviceActionCall) = synchronized(lock) {
        if (lease === owner && executing == call.callId) { executing = null; settling = true; scene++ }
    }

    fun presentApproval(
        owner: DeviceTaskLease, approvalId: String, title: String, details: String,
        call: DeviceActionCall? = null, snapshotToken: Long = -1,
        onDecision: (Boolean) -> Unit,
    ): Boolean {
        val source = port ?: return false
        if (!isCurrent(owner)) return false
        synchronized(lock) {
            if (lease !== owner) return false
            pendingApproval = approvalId
            approvedCall = null
            pendingDecision = { accepted ->
                val valid = isCurrent(owner) && (call == null || snapshotCurrent(owner, snapshotToken))
                val consumed = synchronized(lock) {
                    if (lease !== owner || pendingApproval != approvalId) false else {
                        pendingApproval = null
                        if (accepted && valid) {
                            if (call == null) taskApproved = true else approvedCall = call
                        }
                        true
                    }
                }
                if (consumed) onDecision(accepted && valid)
            }
        }
        val posted = post {
            if (!isCurrent(owner)) { decide(owner, approvalId, false); return@post }
            runCatching {
                source.presentApproval(title, details) { accepted -> this.decide(owner, approvalId, accepted) }
            }.onFailure { invalidate() }
        }
        if (posted) return true
        synchronized(lock) {
            if (lease === owner && pendingApproval == approvalId) {
                pendingApproval = null
                pendingDecision = null
                approvedCall = null
            }
        }
        return false
    }

    fun decide(owner: DeviceTaskLease, approvalId: String, accepted: Boolean): Boolean {
        val decision = synchronized(lock) {
            if (lease !== owner || pendingApproval != approvalId) return false
            pendingDecision.also { pendingDecision = null }
        } ?: return false
        clearOverlay(owner)
        decision(accepted)
        return true
    }

    fun showStatus(owner: DeviceTaskLease, message: String) {
        val source = port ?: return
        if (!post { if (isCurrent(owner)) runCatching { source.showStatus(message) }.onFailure { invalidate() } }) invalidate()
    }

    fun clearOverlay(owner: DeviceTaskLease) {
        val source = port ?: return
        post {
            // A delayed old-task cleanup may not remove a newer task's controls.
            if (synchronized(lock) { lease == null || lease === owner }) runCatching { source.clearOverlay() }
        }
    }

    private fun submit(rejected: () -> Unit, work: () -> Unit) {
        try { executor.execute(work) } catch (_: RejectedExecutionException) { rejected() }
    }

    private fun expired(call: DeviceActionCall) = DeviceActionResult(call.callId, DeviceActionResultStatus.EXPIRED, "Device task expired")
    override fun close() { invalidate(); port = null; observers.clear() }
}
