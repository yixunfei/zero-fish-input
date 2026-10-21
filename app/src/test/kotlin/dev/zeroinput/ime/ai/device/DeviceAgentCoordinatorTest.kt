package dev.zeroinput.ime.ai.device

import dev.zeroinput.ai.api.*
import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

class DeviceAgentCoordinatorTest {
    private val target = DeviceWindowIdentity("com.example.app", 7)
    private val policy = DeviceAgentPolicy(
        enabled = true,
        disclosureAccepted = true,
        scope = DeviceCapabilityScope.FULL_SUPPORT,
        approvalMode = DeviceApprovalMode.EACH_ACTION,
    )

    @Test
    fun recreationObserverReattachesWithoutStartingAnotherProviderRequest() {
        val fixture = Fixture()
        val firstObserver = mutableListOf<DeviceAgentEvent>()
        fixture.coordinator.observe(firstObserver::add)
        fixture.coordinator.start("Tap Continue", target.packageName, fixture.policy) {}
        fixture.drain()
        assertEquals(1, fixture.provider.requests.size)

        firstObserver.clear()
        val reattachedEvents = mutableListOf<DeviceAgentEvent>()
        val observer = fixture.coordinator.observe(reattachedEvents::add)

        assertTrue(reattachedEvents.any { it is DeviceAgentEvent.State })
        assertEquals(1, fixture.provider.requests.size)
        observer.close()
        fixture.close()
    }

    @Test
    fun approvedActionIsBoundToFreshSnapshotAndResultIsIncludedInNextTurn() {
        val fixture = Fixture()
        fixture.coordinator.start("Tap Continue", target.packageName, fixture.policy) {}
        fixture.drain()
        val snapshot = fixture.provider.requests.single().snapshot
        val call = DeviceActionCall(
            callId = "call-1",
            toolName = "click",
            snapshotId = snapshot.snapshotId,
            arguments = mapOf("element_ref" to "0"),
            round = 0,
        )
        fixture.provider.complete(0, DeviceAgentModelResponse(null, listOf(call), completed = false))
        fixture.drain()

        val approval = requireNotNull(fixture.port.approval)
        assertTrue(fixture.coordinator.approve("call-1", true))
        assertFalse(fixture.coordinator.approve("call-1", true))
        fixture.drain()

        assertEquals(1, fixture.port.actions)
        assertEquals(2, fixture.provider.requests.size)
        val turn = fixture.provider.requests.last().history.single()
        assertEquals("call-1", turn.call.callId)
        assertEquals(DeviceActionResultStatus.SUCCESS, turn.result.status)
        approval.invoke(true)
        assertEquals(1, fixture.port.actions)
        fixture.close()
    }

    @Test
    fun locallyRejectedActionBecomesModelFeedbackInsteadOfEndingTask() {
        val fixture = Fixture()
        val events = mutableListOf<DeviceAgentEvent>()
        fixture.coordinator.start("Tap Continue", target.packageName, fixture.policy, events::add)
        fixture.drain()
        val snapshotId = fixture.provider.requests.single().snapshot.snapshotId
        val invalid = DeviceActionCall(
            "invalid-action", "click", snapshotId, mapOf("element_ref" to "missing"), 0,
        )
        fixture.provider.complete(0, DeviceAgentModelResponse(null, listOf(invalid), completed = false))
        fixture.drain()

        assertTrue(fixture.coordinator.hasActiveTask)
        assertEquals(2, fixture.provider.requests.size)
        assertTrue(events.any {
            it is DeviceAgentEvent.ActionFinished && it.result.status == DeviceActionResultStatus.REJECTED
        })
        fixture.close()
    }

    @Test
    fun cancellationDiscardsLateProviderResponseAndDoesNotExecuteAction() {
        val fixture = Fixture()
        val events = mutableListOf<DeviceAgentEvent>()
        val handle = fixture.coordinator.start("Tap Continue", target.packageName, fixture.policy, events::add)
        fixture.drain()
        assertEquals(1, fixture.provider.requests.size)

        handle.cancel()
        fixture.provider.complete(0, clickResponse(fixture.provider.requests.single().snapshot.snapshotId))
        fixture.drain()

        assertFalse(fixture.coordinator.hasActiveTask)
        assertEquals(0, fixture.port.actions)
        assertTrue(events.any { it is DeviceAgentEvent.State && it.state == DeviceAgentState.PAUSED })
        fixture.close()
    }

    @Test
    fun taskDeadlineRevokesProviderAndSnapshotState() {
        val fixture = Fixture()
        fixture.coordinator.start("Tap Continue", target.packageName, fixture.policy) {}
        fixture.drain()
        assertTrue(fixture.coordinator.hasActiveTask)

        fixture.deadline?.invoke()
        fixture.drain()

        assertFalse(fixture.coordinator.hasActiveTask)
        assertEquals(1, fixture.provider.cancelled)
        assertEquals(0, fixture.port.actions)
        fixture.close()
    }

    @Test
    fun providerSubmissionFailureFinishesTaskWithoutLeakingActiveState() {
        val fixture = Fixture(providerFailure = true)
        val events = mutableListOf<DeviceAgentEvent>()
        fixture.coordinator.start("Tap Continue", target.packageName, fixture.policy, events::add)
        fixture.drain()

        assertFalse(fixture.coordinator.hasActiveTask)
        assertTrue(events.any { it is DeviceAgentEvent.Failed })
        fixture.close()
    }

    @Test
    fun providerRequestReceivesIndependentAttachmentBytes() {
        val fixture = Fixture()
        val attachment = dev.zeroinput.ai.api.AiAttachment("text/plain", "fixture".toByteArray(), "note.txt")
        fixture.coordinator.start("Read selected file", target.packageName, fixture.policy,
            attachments = listOf(attachment), listener = {})
        fixture.drain()
        fixture.port.approval?.invoke(true)
        fixture.drain()

        val requestAttachment = fixture.provider.requests.single().attachments.single()
        assertEquals("fixture", String(requestAttachment.bytes))
        assertTrue(attachment.bytes.all { it != 0.toByte() })
        fixture.close()
    }

    @Test
    fun stage6AttachmentRemainsReadableUntilProviderOwnsRequest() {
        val attachment = dev.zeroinput.ai.api.AiAttachment("image/jpeg", byteArrayOf(9, 8, 7), "selected.jpg")
        val stage6 = DeviceStage6Controller(
            executor = Executor { it.run() },
            platform = object : DeviceStage6Platform {
                override fun execute(
                    request: DeviceStage6OperationRequest,
                    maxAttachmentBytes: Int,
                    stillValid: () -> Boolean,
                    cancellation: DeviceStage6Cancellation,
                ) = if (stillValid()) {
                    DeviceStage6Result(DeviceStage6ResultStatus.SUCCESS, "Selected data is ready", attachment)
                } else DeviceStage6Result(DeviceStage6ResultStatus.EXPIRED, "Task grant expired")
            },
            unlocked = { true },
            foregroundPackage = { target.packageName },
        )
        val fixture = Fixture(stage6Controller = stage6)
        val stage6Policy = fixture.policy.copy(
            stage6DisclosureAccepted = true,
            stage6Capabilities = setOf(DeviceStage6Capability.CONTACTS),
        )
        fixture.coordinator.start("Read the selected contact", target.packageName, stage6Policy) {}
        fixture.drain()
        val snapshotId = fixture.provider.requests.single().snapshot.snapshotId
        val call = DeviceActionCall("stage6-call", "read_contact", snapshotId,
            mapOf("display_name" to "Alice"), 0)
        fixture.provider.complete(0, DeviceAgentModelResponse(null, listOf(call), completed = false))
        fixture.drain()
        assertTrue(fixture.coordinator.approve("stage6-call", true))
        fixture.drain()

        val sent = fixture.provider.requests.last().attachments.single()
        assertArrayEquals(byteArrayOf(9, 8, 7), sent.bytes)
        assertArrayEquals(byteArrayOf(0, 0, 0), attachment.bytes)
        fixture.close()
    }

    private fun clickResponse(snapshotId: String) = DeviceAgentModelResponse(
        message = null,
        calls = listOf(DeviceActionCall("call-1", "click", snapshotId, mapOf("element_ref" to "0"), 0)),
        completed = false,
    )

    private inner class Fixture(
        private val providerFailure: Boolean = false,
        private val stage6Controller: DeviceStage6Controller? = null,
    ) {
        val queue = ArrayDeque<() -> Unit>()
        val provider = FakeProvider(providerFailure)
        val port = FakePort()
        var deadline: (() -> Unit)? = null
        val policy = this@DeviceAgentCoordinatorTest.policy
        private val broker = DeviceAgentAccessibilityBroker(
            executor = Executor { it.run() },
            post = { action -> queue.addLast(action); true },
        )
        val coordinator = DeviceAgentCoordinator(
            provider = provider,
            broker = broker,
            executor = Executor { it.run() },
            post = { action -> queue.addLast(action); true },
            schedule = { _, action -> deadline = action; AutoCloseable { deadline = null } },
            stage6Controller = stage6Controller,
        )

        init { broker.attach(port) }

        fun drain() {
            var count = 0
            while (queue.isNotEmpty()) {
                check(++count <= 100) { "Main queue did not settle" }
                queue.removeFirst().invoke()
            }
        }

        fun close() { coordinator.close(); broker.close(); stage6Controller?.close(); provider.clear() }
    }

    private class FakeProvider(var shouldThrow: Boolean = false) : DeviceAgentProvider {
        val requests = mutableListOf<DeviceAgentModelRequest>()
        private val callbacks = mutableListOf<(DeviceAgentProviderEvent) -> Unit>()
        var cancelled = 0

        override fun request(
            request: DeviceAgentModelRequest,
            listener: (DeviceAgentProviderEvent) -> Unit,
        ): AiRequestHandle {
            if (shouldThrow) throw IllegalStateException("provider unavailable")
            requests += request
            callbacks += listener
            listener(DeviceAgentProviderEvent.Started)
            return object : AiRequestHandle { override fun cancel() { cancelled++ } }
        }

        fun complete(index: Int, response: DeviceAgentModelResponse) {
            callbacks[index](DeviceAgentProviderEvent.Completed(response))
        }

        fun clear() {
            requests.flatMap(DeviceAgentModelRequest::attachments).forEach { it.bytes.fill(0) }
        }
    }

    private class FakePort : DeviceAgentAccessibilityPort {
        var approval: ((Boolean) -> Unit)? = null
        var actions = 0
        private var snapshotNumber = 0

        override fun currentWindow() = DeviceWindowIdentity("com.example.app", 7)
        override fun unlocked() = true
        override fun snapshot(includeScreenshot: Boolean, current: () -> Boolean): DeviceScreenSnapshot {
            check(current())
            val number = ++snapshotNumber
            return DeviceScreenSnapshot(
                packageName = "com.example.app",
                windowId = 7,
                snapshotId = "snapshot-$number",
                elements = listOf(DeviceElement(
                    ref = "0", role = "Button", label = "Continue", text = "Continue",
                    bounds = DeviceBounds(0, 0, 40, 40), clickable = true, longClickable = false,
                    scrollable = false, editable = false, password = false, sensitive = false, enabled = true,
                )),
            )
        }

        override fun perform(
            call: DeviceActionCall,
            snapshot: DeviceScreenSnapshot,
            current: () -> Boolean,
        ): DeviceActionResult {
            check(current())
            actions++
            return DeviceActionResult(call.callId, DeviceActionResultStatus.SUCCESS, "Action completed")
        }

        override fun presentApproval(title: String, details: String, decide: (Boolean) -> Unit) { approval = decide }
        override fun showStatus(message: String) = Unit
        override fun clearOverlay() { approval = null }
    }
}
