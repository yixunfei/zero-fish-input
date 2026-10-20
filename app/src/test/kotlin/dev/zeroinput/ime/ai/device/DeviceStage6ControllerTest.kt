package dev.zeroinput.ime.ai.device

import dev.zeroinput.ai.api.DeviceAgentPolicy
import dev.zeroinput.ai.api.DeviceAgentLimits
import dev.zeroinput.ai.api.DeviceCapabilityScope
import dev.zeroinput.ai.api.DeviceStage6Capability
import dev.zeroinput.ai.api.DeviceStage6Grant
import dev.zeroinput.ai.api.DeviceStage6Operation
import dev.zeroinput.ai.api.DeviceStage6OperationRequest
import dev.zeroinput.ai.api.DeviceStage6Result
import dev.zeroinput.ai.api.DeviceStage6ResultStatus
import dev.zeroinput.ai.api.AiAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

class DeviceStage6ControllerTest {
    private val now = 2_000L
    private val target = dev.zeroinput.ai.api.DeviceStage6Target(DeviceStage6Capability.CONTACTS, "42")
    private val policy = DeviceAgentPolicy(
        enabled = true,
        disclosureAccepted = true,
        stage6DisclosureAccepted = true,
        stage6Capabilities = setOf(DeviceStage6Capability.CONTACTS),
        scope = DeviceCapabilityScope.FULL_SUPPORT,
    )

    @Test
    fun directOperationRequiresCurrentSingleUseApprovalAndExactTarget() {
        val platform = FakePlatform()
        val controller = controller(Executor { it.run() }, platform)
        val result = mutableListOf<DeviceStage6Result>()

        controller.execute(policy, grant(), request(), "approve-1", approved = true, deliver = result::add)
        controller.execute(policy, grant(), request(), "approve-1", approved = true, deliver = result::add)

        assertEquals(1, platform.calls)
        assertEquals(DeviceStage6ResultStatus.SUCCESS, result.first().status)
        assertEquals(DeviceStage6ResultStatus.REJECTED, result.last().status)
        controller.close()
    }

    @Test
    fun queuedOperationIsDiscardedAfterTaskInvalidation() {
        var queued: Runnable? = null
        val platform = FakePlatform()
        val controller = controller(Executor { queued = it }, platform)
        val result = mutableListOf<DeviceStage6Result>()
        controller.execute(policy, grant(), request(), "approve-2", approved = true, deliver = result::add)

        controller.invalidate()
        requireNotNull(queued).run()

        assertEquals(0, platform.calls)
        assertTrue(result.isEmpty())
        controller.close()
    }

    @Test
    fun policyRejectsUnlistedTargetAndMissingApproval() {
        val evaluator = DeviceStage6PolicyEvaluator { now }
        val request = request()
        assertEquals(
            DeviceStage6PolicyReason.TARGET_NOT_ALLOWED,
            evaluator.check(policy, grant(targetId = "43"), request, true, "com.example.target", true).reason,
        )
        assertEquals(
            DeviceStage6PolicyReason.APPROVAL_REQUIRED,
            evaluator.check(policy, grant(), request, true, "com.example.target", false).reason,
        )
    }

    @Test
    fun policyRejectsGrantLongerThanOneMinute() {
        val longGrant = grant().let {
            DeviceStage6Grant(
                taskId = it.taskId,
                generation = it.generation,
                taskTargetPackage = it.taskTargetPackage,
                capability = it.capability,
                operations = it.operations,
                targets = it.targets,
                expiresAtElapsedMillis = now + DeviceAgentLimits.MAX_STAGE6_GRANT_MS + 1,
            )
        }
        assertEquals(
            DeviceStage6PolicyReason.GRANT_EXPIRED,
            DeviceStage6PolicyEvaluator { now }.check(policy, longGrant, request(), true, "com.example.target", true).reason,
        )
    }

    @Test
    fun notificationGrantAllowsNamedUserAppAndRejectsPlatformControls() {
        val notificationPolicy = policy.copy(stage6Capabilities = setOf(DeviceStage6Capability.NOTIFICATIONS))
        val evaluator = DeviceStage6PolicyEvaluator { now }
        val allowedTarget = dev.zeroinput.ai.api.DeviceStage6Target(
            DeviceStage6Capability.NOTIFICATIONS,
            "com.whatsapp",
            "com.whatsapp",
        )
        val allowedRequest = notificationRequest(allowedTarget)

        assertEquals(
            DeviceStage6PolicyReason.ALLOWED,
            evaluator.check(notificationPolicy, notificationGrant(allowedTarget), allowedRequest, true,
                "com.example.target", true).reason,
        )

        val blockedTarget = allowedTarget.copy(id = "com.android.permissioncontroller", packageName = "com.android.permissioncontroller")
        assertEquals(
            DeviceStage6PolicyReason.TARGET_NOT_ALLOWED,
            evaluator.check(notificationPolicy, notificationGrant(blockedTarget), notificationRequest(blockedTarget), true,
                "com.example.target", true).reason,
        )
    }

    @Test
    fun resultStringNeverContainsPrivatePayload() {
        val result = DeviceStage6Result(DeviceStage6ResultStatus.SUCCESS, "private contact content")
        assertFalse(result.toString().contains("private contact content"))
        assertTrue(result.toString().contains("redacted"))
    }

    @Test
    fun rejectedExecutorReleasesTaskOwnershipAndApproval() {
        var reject = true
        val platform = FakePlatform()
        val executor = Executor {
            if (reject) throw RejectedExecutionException()
            it.run()
        }
        val controller = controller(executor, platform)
        val results = mutableListOf<DeviceStage6Result>()

        controller.execute(policy, grant(), request(), "approve-rejected", approved = true, deliver = results::add)
        assertEquals(DeviceStage6ResultStatus.UNAVAILABLE, results.single().status)

        reject = false
        controller.execute(policy, grant(), request(), "approve-retry", approved = true, deliver = results::add)
        assertEquals(DeviceStage6ResultStatus.SUCCESS, results.last().status)
        assertEquals(1, platform.calls)
        controller.close()
    }

    @Test
    fun rejectedExecutorDoesNotAllowApprovalReplay() {
        var reject = true
        val platform = FakePlatform()
        val executor = Executor {
            if (reject) throw RejectedExecutionException()
            it.run()
        }
        val controller = controller(executor, platform)
        val results = mutableListOf<DeviceStage6Result>()

        controller.execute(policy, grant(), request(), "approve-once", approved = true, deliver = results::add)
        reject = false
        controller.execute(policy, grant(), request(), "approve-once", approved = true, deliver = results::add)

        assertEquals(DeviceStage6ResultStatus.UNAVAILABLE, results[0].status)
        assertEquals(DeviceStage6ResultStatus.REJECTED, results[1].status)
        assertEquals(0, platform.calls)
        controller.close()
    }

    @Test
    fun attachmentOverBudgetIsClearedAndRejected() {
        val platform = FakePlatform(attachment = AiAttachment("image/jpeg", byteArrayOf(1, 2, 3), "data.jpg"))
        val controller = controller(Executor { it.run() }, platform)
        val results = mutableListOf<DeviceStage6Result>()

        controller.execute(policy, grant(), request(), "approve-budget", approved = true, maxAttachmentBytes = 2, deliver = results::add)

        assertEquals(DeviceStage6ResultStatus.FAILED, results.single().status)
        assertEquals(0, platform.attachment!!.bytes[0].toInt())
        assertEquals(0, platform.attachment!!.bytes[2].toInt())
        controller.close()
    }

    @Test
    fun lateAttachmentAfterInvalidationIsClearedWithoutDelivery() {
        val platform = FakePlatform(attachment = AiAttachment("image/jpeg", byteArrayOf(7, 8), "late.jpg"))
        var controller: DeviceStage6Controller? = null
        platform.beforeReturn = { controller!!.invalidate() }
        controller = controller(Executor { it.run() }, platform)
        val results = mutableListOf<DeviceStage6Result>()

        controller!!.execute(policy, grant(), request(), "approve-late", approved = true, deliver = results::add)

        assertTrue(results.isEmpty())
        assertEquals(0, platform.attachment!!.bytes[0].toInt())
        assertEquals(0, platform.attachment!!.bytes[1].toInt())
        controller!!.close()
    }

    private fun controller(executor: Executor, platform: FakePlatform) = DeviceStage6Controller(
        executor = executor,
        platform = platform,
        policy = DeviceStage6PolicyEvaluator { now },
        unlocked = { true },
        foregroundPackage = { "com.example.target" },
    )

    private fun grant(targetId: String = target.id) = DeviceStage6Grant(
        taskId = "task-1",
        generation = 1,
        taskTargetPackage = "com.example.target",
        capability = DeviceStage6Capability.CONTACTS,
        operations = setOf(DeviceStage6Operation.READ),
        targets = setOf(target.copy(id = targetId)),
        expiresAtElapsedMillis = 5_000,
    )

    private fun request() = DeviceStage6OperationRequest(
        taskId = "task-1",
        generation = 1,
        taskTargetPackage = "com.example.target",
        capability = DeviceStage6Capability.CONTACTS,
        operation = DeviceStage6Operation.READ,
        target = target,
    )

    private fun notificationRequest(target: dev.zeroinput.ai.api.DeviceStage6Target) = DeviceStage6OperationRequest(
        taskId = "task-1",
        generation = 1,
        taskTargetPackage = "com.example.target",
        capability = DeviceStage6Capability.NOTIFICATIONS,
        operation = DeviceStage6Operation.READ,
        target = target,
    )

    private fun notificationGrant(target: dev.zeroinput.ai.api.DeviceStage6Target) = DeviceStage6Grant(
        taskId = "task-1",
        generation = 1,
        taskTargetPackage = "com.example.target",
        capability = DeviceStage6Capability.NOTIFICATIONS,
        operations = setOf(DeviceStage6Operation.READ),
        targets = setOf(target),
        expiresAtElapsedMillis = now + 1_000,
    )

    private class FakePlatform : DeviceStage6Platform {
        var attachment: AiAttachment? = null
        var beforeReturn: (() -> Unit)? = null
        var calls = 0
        constructor()
        constructor(attachment: AiAttachment) { this.attachment = attachment }
        override fun execute(
            request: DeviceStage6OperationRequest,
            maxAttachmentBytes: Int,
            stillValid: () -> Boolean,
        ): DeviceStage6Result {
            if (!stillValid()) return DeviceStage6Result(DeviceStage6ResultStatus.EXPIRED, "Task grant expired")
            calls++
            beforeReturn?.invoke()
            return DeviceStage6Result(DeviceStage6ResultStatus.SUCCESS, "private contact content", attachment)
        }
    }
}
