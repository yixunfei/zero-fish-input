package dev.zeroinput.ime.ai.device

import dev.zeroinput.ai.api.AiRequestHandle
import dev.zeroinput.ai.api.AiLimits
import dev.zeroinput.ai.api.DeviceAgentPolicy
import dev.zeroinput.ai.api.DeviceAgentLimits
import dev.zeroinput.ai.api.DeviceStage6Capability
import dev.zeroinput.ai.api.DeviceStage6Catalog
import dev.zeroinput.ai.api.DeviceStage6Grant
import dev.zeroinput.ai.api.DeviceStage6Operation
import dev.zeroinput.ai.api.DeviceStage6OperationRequest
import dev.zeroinput.ai.api.DeviceStage6Result
import dev.zeroinput.ai.api.DeviceStage6ResultStatus
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

enum class DeviceStage6PolicyReason {
    ALLOWED,
    DISABLED,
    DISCLOSURE_REQUIRED,
    CAPABILITY_NOT_ALLOWED,
    OPERATION_NOT_ALLOWED,
    TARGET_NOT_ALLOWED,
    GRANT_EXPIRED,
    DEVICE_LOCKED,
    FOREGROUND_REQUIRED,
    APPROVAL_REQUIRED,
    REPLAYED_APPROVAL,
    INVALID_REQUEST,
}

data class DeviceStage6PolicyDecision(
    val allowed: Boolean,
    val reason: DeviceStage6PolicyReason,
) {
    companion object {
        fun allow() = DeviceStage6PolicyDecision(true, DeviceStage6PolicyReason.ALLOWED)
        fun deny(reason: DeviceStage6PolicyReason) = DeviceStage6PolicyDecision(false, reason)
    }
}

/** Pure gate for direct data operations. The model never supplies the grant or target. */
class DeviceStage6PolicyEvaluator(
    private val ownPackage: String = "dev.zeroinput.ime",
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    fun check(
        policy: DeviceAgentPolicy,
        grant: DeviceStage6Grant,
        request: DeviceStage6OperationRequest,
        unlocked: Boolean,
        foregroundPackage: String?,
        approved: Boolean,
    ): DeviceStage6PolicyDecision {
        if (!policy.enabled) return DeviceStage6PolicyDecision.deny(DeviceStage6PolicyReason.DISABLED)
        if (!policy.disclosureAccepted || !policy.stage6DisclosureAccepted) {
            return DeviceStage6PolicyDecision.deny(DeviceStage6PolicyReason.DISCLOSURE_REQUIRED)
        }
        if (!policy.permitsStage6(request.capability) || request.capability != grant.capability) {
            return DeviceStage6PolicyDecision.deny(DeviceStage6PolicyReason.CAPABILITY_NOT_ALLOWED)
        }
        if (request.taskTargetPackage != grant.taskTargetPackage) {
            return DeviceStage6PolicyDecision.deny(DeviceStage6PolicyReason.TARGET_NOT_ALLOWED)
        }
        if (!policy.permitsPackage(request.taskTargetPackage) ||
            request.target.packageName?.let { !policy.permitsPackage(it) } == true) {
            return DeviceStage6PolicyDecision.deny(DeviceStage6PolicyReason.TARGET_NOT_ALLOWED)
        }
        if (request.capability == DeviceStage6Capability.NOTIFICATIONS &&
            request.target.packageName?.let { DeviceAgentSafety.blockedNotificationPackage(it, ownPackage) } == true) {
            return DeviceStage6PolicyDecision.deny(DeviceStage6PolicyReason.TARGET_NOT_ALLOWED)
        }
        if (!DeviceStage6Catalog.permits(request.capability, request.operation) ||
            request.operation !in grant.operations) {
            return DeviceStage6PolicyDecision.deny(DeviceStage6PolicyReason.OPERATION_NOT_ALLOWED)
        }
        if (request.target !in grant.targets) {
            return DeviceStage6PolicyDecision.deny(DeviceStage6PolicyReason.TARGET_NOT_ALLOWED)
        }
        val currentTime = now()
        val remaining = grant.expiresAtElapsedMillis - currentTime
        if (remaining !in 1..DeviceAgentLimits.MAX_STAGE6_GRANT_MS ||
            !grant.permits(request.operation, request.target, currentTime)) {
            return DeviceStage6PolicyDecision.deny(DeviceStage6PolicyReason.GRANT_EXPIRED)
        }
        if (!unlocked) return DeviceStage6PolicyDecision.deny(DeviceStage6PolicyReason.DEVICE_LOCKED)
        if (foregroundPackage != request.taskTargetPackage) {
            return DeviceStage6PolicyDecision.deny(DeviceStage6PolicyReason.FOREGROUND_REQUIRED)
        }
        if (!approved) return DeviceStage6PolicyDecision.deny(DeviceStage6PolicyReason.APPROVAL_REQUIRED)
        return DeviceStage6PolicyDecision.allow()
    }
}

/** Platform boundary for provider, sensor and system-data implementations. */
internal interface DeviceStage6Platform {
    fun execute(
        request: DeviceStage6OperationRequest,
        maxAttachmentBytes: Int,
        stillValid: () -> Boolean,
    ): DeviceStage6Result
}

/** Serializes one-use approvals and invalidates all queued work on task changes. */
internal class DeviceStage6Controller(
    private val executor: Executor,
    private val platform: DeviceStage6Platform,
    private val policy: DeviceStage6PolicyEvaluator = DeviceStage6PolicyEvaluator(),
    private val unlocked: () -> Boolean,
    private val foregroundPackage: () -> String?,
) : AutoCloseable {
    private val lock = Any()
    private var generation = 1L
    private var closed = false
    private var activeTaskId: String? = null
    private val consumedApprovals = mutableMapOf<String, MutableSet<String>>()
    private val running = mutableSetOf<String>()

    val currentGeneration: Long get() = synchronized(lock) { generation }

    fun invalidate() = synchronized(lock) {
        generation++
        activeTaskId = null
        running.clear()
        consumedApprovals.clear()
    }

    fun endTask(taskId: String) = synchronized(lock) {
        if (activeTaskId == taskId) {
            generation++
            activeTaskId = null
            consumedApprovals.clear()
            running.clear()
        }
    }

    fun execute(
        policySnapshot: DeviceAgentPolicy,
        grant: DeviceStage6Grant,
        request: DeviceStage6OperationRequest,
        approvalId: String,
        approved: Boolean,
        maxAttachmentBytes: Int = AiLimits.MAX_ATTACHMENT_BYTES,
        deliver: (DeviceStage6Result) -> Unit,
    ): AiRequestHandle {
        val cancelled = AtomicBoolean()
        val handleGeneration = synchronized(lock) {
            if (closed || grant.taskId != request.taskId || grant.generation != request.generation ||
                grant.generation != generation) return rejected(deliver, DeviceStage6ResultStatus.EXPIRED, "Task grant expired")
            if (!approvalId.matches(Regex("[A-Za-z0-9._-]{1,128}"))) {
                return rejected(deliver, DeviceStage6ResultStatus.REJECTED, "Approval is invalid")
            }
            if (maxAttachmentBytes !in 0..AiLimits.MAX_ATTACHMENT_BYTES) {
                return rejected(deliver, DeviceStage6ResultStatus.REJECTED, "Attachment budget is invalid")
            }
            if (!consumedApprovals.getOrPut(request.taskId) { mutableSetOf() }.add(approvalId)) {
                return rejected(deliver, DeviceStage6ResultStatus.REJECTED, "Approval already used")
            }
            val decision = policy.check(policySnapshot, grant, request, unlocked(), foregroundPackage(), approved)
            if (!decision.allowed) {
                return rejected(deliver, status(decision.reason), decision.reason.name)
            }
            if (activeTaskId != null && activeTaskId != request.taskId) {
                return rejected(deliver, DeviceStage6ResultStatus.REJECTED, "Another task owns stage 6 access")
            }
            activeTaskId = request.taskId
            if (!running.add(request.taskId)) {
                return rejected(deliver, DeviceStage6ResultStatus.REJECTED, "Another data operation is running")
            }
            generation
        }
        try {
            executor.execute {
                val stillValid = {
                    !cancelled.get() && isCurrent(request, handleGeneration) && runCatching {
                        policy.check(policySnapshot, grant, request, unlocked(), foregroundPackage(), approved).allowed
                    }.getOrDefault(false)
                }
                val result = runCatching {
                    if (!stillValid()) DeviceStage6Result(DeviceStage6ResultStatus.EXPIRED, "Task grant expired")
                    else boundAttachment(platform.execute(request, maxAttachmentBytes, stillValid), maxAttachmentBytes)
                }.getOrElse { DeviceStage6Result(DeviceStage6ResultStatus.FAILED, "Data operation failed") }
                val current = synchronized(lock) {
                    val valid = !closed && generation == handleGeneration && request.generation == generation &&
                        request.taskId in running
                    running.remove(request.taskId)
                    valid
                }
                if (!cancelled.get() && current) {
                    try {
                        deliver(result)
                    } finally {
                        result.attachment?.bytes?.fill(0)
                    }
                } else {
                    result.attachment?.bytes?.fill(0)
                }
            }
        } catch (_: RejectedExecutionException) {
            synchronized(lock) {
                running.remove(request.taskId)
                if (activeTaskId == request.taskId) activeTaskId = null
            }
            deliver(DeviceStage6Result(DeviceStage6ResultStatus.UNAVAILABLE, "Data operation queue unavailable"))
        }
        return object : AiRequestHandle {
            override fun cancel() {
                cancelled.set(true)
            }
        }
    }

    private fun isCurrent(request: DeviceStage6OperationRequest, expectedGeneration: Long): Boolean = synchronized(lock) {
        !closed && generation == expectedGeneration && request.generation == generation && request.taskId in running
    }

    private fun boundAttachment(result: DeviceStage6Result, maxAttachmentBytes: Int): DeviceStage6Result {
        val attachment = result.attachment ?: return result
        if (attachment.bytes.size <= maxAttachmentBytes) return result
        attachment.bytes.fill(0)
        return DeviceStage6Result(DeviceStage6ResultStatus.FAILED, "Data attachment is too large")
    }

    override fun close() = synchronized(lock) {
        closed = true
        generation++
        activeTaskId = null
        running.clear()
        consumedApprovals.clear()
    }

    private fun rejected(deliver: (DeviceStage6Result) -> Unit, status: DeviceStage6ResultStatus, message: String): AiRequestHandle {
        deliver(DeviceStage6Result(status, message))
        return object : AiRequestHandle { override fun cancel() = Unit }
    }

    private fun status(reason: DeviceStage6PolicyReason): DeviceStage6ResultStatus = when (reason) {
        DeviceStage6PolicyReason.GRANT_EXPIRED,
        DeviceStage6PolicyReason.DEVICE_LOCKED,
        DeviceStage6PolicyReason.FOREGROUND_REQUIRED -> DeviceStage6ResultStatus.EXPIRED
        DeviceStage6PolicyReason.CAPABILITY_NOT_ALLOWED,
        DeviceStage6PolicyReason.TARGET_NOT_ALLOWED,
        DeviceStage6PolicyReason.OPERATION_NOT_ALLOWED,
        DeviceStage6PolicyReason.APPROVAL_REQUIRED,
        DeviceStage6PolicyReason.REPLAYED_APPROVAL,
        DeviceStage6PolicyReason.DISABLED,
        DeviceStage6PolicyReason.DISCLOSURE_REQUIRED,
        DeviceStage6PolicyReason.INVALID_REQUEST -> DeviceStage6ResultStatus.REJECTED
        DeviceStage6PolicyReason.ALLOWED -> DeviceStage6ResultStatus.SUCCESS
    }
}
