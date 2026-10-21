package dev.zeroinput.ime.ai.device

import dev.zeroinput.ai.api.*
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

class DeviceAgentAccessibilityBrokerTest {
    private val mainQueue = ArrayDeque<() -> Unit>()
    private val policy = DeviceAgentPolicy(
        enabled = true,
        disclosureAccepted = true,
        scope = DeviceCapabilityScope.FULL_SUPPORT,
    )
    private val broker = DeviceAgentAccessibilityBroker(
        executor = Executor { it.run() },
        post = { task -> mainQueue.addLast(task); true },
    )
    private val target = DeviceWindowIdentity("com.example.app", 7)

    @Test
    fun taskCannotStartUntilDisclosureAndTargetAreValid() {
        val port = FakePort(target)
        broker.attach(port)
        assertFalse(broker.canStart(policy.copy(disclosureAccepted = false), target.packageName))
        assertNull(broker.beginTask("com.other.app", policy, "task-a"))
        assertEquals(0, port.snapshotReads)
        assertNotNull(broker.beginTask(target.packageName, policy, "task-a"))
        assertEquals(0, port.snapshotReads)
    }

    @Test
    fun invalidatedCaptureReturnsNoSnapshotAndWipesCapturedFrame() {
        val port = FakePort(target, screenshot = true)
        broker.attach(port)
        val owner = requireNotNull(broker.beginTask(target.packageName, policy.copy(allowScreenshots = true), "task-a"))
        var callbackCount = 0
        var captured: DeviceScreenSnapshot? = null
        broker.capture(owner) { _, value -> callbackCount++; captured = value }

        broker.invalidate()
        drainMainQueue()

        assertEquals(1, callbackCount)
        assertNull(captured)
        assertEquals(1, port.snapshotReads)
        assertTrue(port.lastSnapshot?.hasScreenshot == false)
    }

    @Test
    fun staleActionIsReportedExpiredBeforePlatformExecution() {
        val port = FakePort(target)
        broker.attach(port)
        val owner = requireNotNull(broker.beginTask(target.packageName, policy, "task-stale"))
        var token = -1L
        var snapshot: DeviceScreenSnapshot? = null
        broker.capture(owner) { value, frame -> token = value; snapshot = frame }
        drainMainQueue()
        val frame = requireNotNull(snapshot)
        val call = DeviceActionCall("call-stale", "click", frame.snapshotId, mapOf("element_ref" to "0"), 0)
        broker.invalidate()
        var result: DeviceActionResult? = null
        broker.perform(owner, token, call, frame) { result = it }
        assertEquals(DeviceActionResultStatus.EXPIRED, result?.status)
        assertEquals(0, port.actionCount)
        frame.clearScreenshot()
    }

    @Test
    fun stage6ApprovalIsRejectedAfterItsSnapshotTokenChanges() {
        val port = FakePort(target)
        broker.attach(port)
        val owner = requireNotNull(broker.beginTask(target.packageName, policy, "task-stage6-stale"))
        var firstToken = -1L
        var firstSnapshot: DeviceScreenSnapshot? = null
        broker.capture(owner) { token, frame -> firstToken = token; firstSnapshot = frame }
        drainMainQueue()
        requireNotNull(firstSnapshot)

        var decision: Boolean? = null
        assertTrue(broker.presentApproval(
            owner, "stage6-read", "Read data", "Selected operation",
            snapshotToken = firstToken, oneShotOnly = true,
        ) { decision = it })
        var nextSnapshot: DeviceScreenSnapshot? = null
        broker.capture(owner) { _, frame -> nextSnapshot = frame }
        drainMainQueue()

        assertTrue(broker.decide(owner, "stage6-read", true))
        assertEquals(false, decision)
        firstSnapshot?.clearScreenshot()
        nextSnapshot?.clearScreenshot()
    }

    @Test
    fun approvalIsTaskBoundAndCanOnlyBeConsumedOnce() {
        val port = FakePort(target)
        broker.attach(port)
        val owner = requireNotNull(broker.beginTask(target.packageName, policy, "task-a"))
        var decisionCount = 0
        var accepted = false
        assertTrue(broker.presentApproval(owner, "approval-a", "Review", "Click Continue") {
            decisionCount++
            accepted = it
        })
        drainMainQueue()
        assertNotNull(port.approvalDecision)
        port.approvalDecision?.invoke(true)

        assertEquals(1, decisionCount)
        assertTrue(accepted)
        assertFalse(broker.decide(owner, "approval-a", true))
        assertEquals(1, decisionCount)
    }

    @Test
    fun failedApprovalPostDoesNotLeaveAnActionPending() {
        val rejectedBroker = DeviceAgentAccessibilityBroker(
            executor = Executor { it.run() },
            post = { false },
        )
        val port = FakePort(target)
        rejectedBroker.attach(port)
        val owner = requireNotNull(rejectedBroker.beginTask(target.packageName, policy, "task-post-failed"))

        assertFalse(rejectedBroker.presentApproval(owner, "approval-post-failed", "Review", "Click Continue") {})
        assertFalse(rejectedBroker.decide(owner, "approval-post-failed", true))

        rejectedBroker.close()
    }

    @Test
    fun approvedActionCannotBeReplayedAfterExecution() {
        val port = FakePort(target)
        broker.attach(port)
        val owner = requireNotNull(broker.beginTask(target.packageName, policy, "task-a"))
        var token = -1L
        var snapshot: DeviceScreenSnapshot? = null
        broker.capture(owner) { value, frame -> token = value; snapshot = frame }
        drainMainQueue()
        val frame = requireNotNull(snapshot)
        val call = DeviceActionCall("call-a", "click", frame.snapshotId, mapOf("element_ref" to "0"), 0)
        var approval: ((Boolean) -> Unit)? = null
        assertTrue(broker.presentApproval(owner, call.callId, "Review", "Click Continue", call, token) {})
        drainMainQueue()
        approval = port.approvalDecision
        approval?.invoke(true)

        var first: DeviceActionResult? = null
        broker.perform(owner, token, call, frame) { first = it }
        drainMainQueue()
        assertEquals(DeviceActionResultStatus.SUCCESS, first?.status)
        assertEquals(1, port.actionCount)

        var replay: DeviceActionResult? = null
        broker.perform(owner, token, call, frame) { replay = it }
        assertEquals(DeviceActionResultStatus.EXPIRED, replay?.status)
        assertEquals(1, port.actionCount)
        frame.clearScreenshot()
    }

    @Test
    fun delayedOldLeaseCleanupCannotClearNewTaskOverlay() {
        val port = FakePort(target)
        broker.attach(port)
        val old = requireNotNull(broker.beginTask(target.packageName, policy, "task-a"))
        broker.revokeTask(old)
        val fresh = requireNotNull(broker.beginTask(target.packageName, policy, "task-b"))

        drainMainQueue()

        assertFalse(port.overlayCleared)
        assertTrue(broker.isCurrent(fresh))
    }

    @Test
    fun rejectedWorkerFailsCaptureClosed() {
        val rejectedBroker = DeviceAgentAccessibilityBroker(
            executor = Executor { throw java.util.concurrent.RejectedExecutionException() },
            post = { task -> mainQueue.addLast(task); true },
        )
        val port = FakePort(target)
        rejectedBroker.attach(port)
        val owner = requireNotNull(rejectedBroker.beginTask(target.packageName, policy, "task-a"))
        var delivered = false
        rejectedBroker.capture(owner) { _, value -> delivered = value == null }
        assertTrue(delivered)
    }

    private fun drainMainQueue() {
        while (mainQueue.isNotEmpty()) mainQueue.removeFirst().invoke()
    }

    private class FakePort(
        private val window: DeviceWindowIdentity,
        private val screenshot: Boolean = false,
    ) : DeviceAgentAccessibilityPort {
        var lastSnapshot: DeviceScreenSnapshot? = null
        var approvalDecision: ((Boolean) -> Unit)? = null
        var overlayCleared = false
        var snapshotReads = 0
        var actionCount = 0

        override fun currentWindow(): DeviceWindowIdentity = window
        override fun unlocked(): Boolean = true
        override fun snapshot(includeScreenshot: Boolean, current: () -> Boolean): DeviceScreenSnapshot {
            snapshotReads++
            return DeviceScreenSnapshot(
                packageName = window.packageName,
                windowId = window.windowId,
                snapshotId = "snapshot-1",
                elements = listOf(DeviceElement(
                    "0", "Button", "Continue", "Continue", DeviceBounds(0, 0, 20, 20),
                    clickable = true, longClickable = false, scrollable = false, editable = false,
                    password = false, sensitive = false, enabled = true,
                )),
                screenshotJpeg = if (screenshot && includeScreenshot) byteArrayOf(1, 2, 3) else null,
            ).also { lastSnapshot = it }
        }

        override fun perform(
            call: DeviceActionCall, snapshot: DeviceScreenSnapshot, current: () -> Boolean,
        ): DeviceActionResult {
            actionCount++
            return DeviceActionResult(call.callId, DeviceActionResultStatus.SUCCESS, "Action completed")
        }

        override fun presentApproval(title: String, details: String, decide: (Boolean) -> Unit) {
            approvalDecision = decide
        }
        override fun showStatus(message: String) = Unit
        override fun clearOverlay() { overlayCleared = true }
    }
}
