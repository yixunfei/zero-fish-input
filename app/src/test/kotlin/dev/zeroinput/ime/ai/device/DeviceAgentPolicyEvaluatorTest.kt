package dev.zeroinput.ime.ai.device

import dev.zeroinput.ai.api.*
import org.junit.Assert.*
import org.junit.Test

class DeviceAgentPolicyEvaluatorTest {
    private val evaluator = DeviceAgentPolicyEvaluator()
    private val policy = DeviceAgentPolicy(
        enabled = true,
        disclosureAccepted = true,
        scope = DeviceCapabilityScope.ALLOWLIST,
        approvalMode = DeviceApprovalMode.EACH_ACTION,
        allowedPackages = setOf("com.example.app"),
    )
    private val element = DeviceElement("0", "Button", "Continue", "Continue", DeviceBounds(0, 0, 50, 50), true, false, false, false, false, false, true)
    private val snapshot = DeviceScreenSnapshot("com.example.app", 7, "snap", listOf(element))

    @Test fun disabledPolicyAndWrongPackageAreRejected() {
        assertEquals(DevicePolicyReason.DISABLED, evaluator.checkReady(policy.copy(enabled = false), true, true, true, "com.example.app", "com.example.app").reason)
        assertEquals(DevicePolicyReason.PACKAGE_NOT_ALLOWED, evaluator.checkReady(policy, true, true, true, "com.other.app", "com.other.app").reason)
    }

    @Test fun clickRequiresApprovalAndSnapshotMismatchExpires() {
        val click = DeviceActionCall("c", "click", snapshot.snapshotId, mapOf("element_ref" to "0"), 0)
        val allowed = evaluator.checkAction(policy, DeviceActionCatalog.find("click"), click, snapshot, "com.example.app")
        assertTrue(allowed.allowed)
        assertTrue(allowed.requiresApproval)
        val stale = evaluator.checkAction(policy, DeviceActionCatalog.find("click"), click.copy(snapshotId = "old"), snapshot, "com.example.app")
        assertEquals(DevicePolicyReason.SNAPSHOT_EXPIRED, stale.reason)
    }

    @Test fun sensitiveEditableElementCannotReceiveInput() {
        val sensitive = snapshot.copy(elements = listOf(element.copy(editable = true, sensitive = true, label = null, text = null)))
        val input = DeviceActionCall("i", "input_text", sensitive.snapshotId, mapOf("element_ref" to "0", "text" to "hello"), 0)
        assertEquals(DevicePolicyReason.SENSITIVE_ELEMENT, evaluator.checkAction(policy, DeviceActionCatalog.find("input_text"), input, sensitive, "com.example.app").reason)
    }

    @Test fun clickRejectsSensitiveLabelsAndDestructiveEffects() {
        listOf("Show password", "Update", "Clear", "Reveal PIN").forEach { label ->
            val frame = snapshot.copy(elements = listOf(element.copy(label = label, text = label)))
            val call = DeviceActionCall(
                "click-${label.hashCode()}", "click", frame.snapshotId, mapOf("element_ref" to "0"), 0,
            )
            assertEquals(
                label,
                DevicePolicyReason.HARD_BLOCKED,
                evaluator.checkAction(policy, DeviceActionCatalog.find("click"), call, frame, "com.example.app").reason,
            )
        }
    }

    @Test fun ordinaryNavigationLabelsRemainClickable() {
        listOf("Email", "Account", "Messages").forEach { label ->
            val frame = snapshot.copy(elements = listOf(element.copy(label = label, text = label)))
            val call = DeviceActionCall(
                "navigation-${label.hashCode()}", "click", frame.snapshotId, mapOf("element_ref" to "0"), 0,
            )
            assertTrue(
                label,
                evaluator.checkAction(policy, DeviceActionCatalog.find("click"), call, frame, "com.example.app").allowed,
            )
        }
    }

    @Test fun emailAndUriValuesCannotBeClickedOrEntered() {
        listOf("alice@example.com", "Open https://example.com/private").forEach { value ->
            val frame = snapshot.copy(elements = listOf(element.copy(
                label = value,
                text = null,
                editable = true,
            )))
            val input = DeviceActionCall(
                "sensitive-value-${value.hashCode()}", "input_text", frame.snapshotId,
                mapOf("element_ref" to "0", "text" to "test"), 0,
            )
            assertEquals(
                DevicePolicyReason.SENSITIVE_ELEMENT,
                evaluator.checkAction(policy, DeviceActionCatalog.find("input_text"), input, frame, "com.example.app").reason,
            )
            val pointerFrame = frame.copy(elements = listOf(element.copy(label = value, text = value)))
            assertFalse(DeviceAgentSafety.safePointer(pointerFrame.elements.single()))
        }
        assertFalse(DeviceAgentSafety.sensitiveValue("Email"))
        assertFalse(DeviceAgentSafety.sensitiveValue("Open Settings"))
    }

    @Test fun sensitiveScrollableContainerCannotBeScrolled() {
        val scrollable = element.copy(
            label = "Password manager", text = "Password manager", clickable = false, scrollable = true,
            bounds = DeviceBounds(0, 0, 200, 200),
        )
        val frame = snapshot.copy(elements = listOf(scrollable))
        val call = DeviceActionCall(
            "scroll-sensitive", "scroll", frame.snapshotId,
            mapOf("element_ref" to "0", "direction" to "forward"), 0,
        )
        assertEquals(
            DevicePolicyReason.SENSITIVE_ELEMENT,
            evaluator.checkAction(policy, DeviceActionCatalog.find("scroll"), call, frame, "com.example.app").reason,
        )
    }

    @Test fun incompleteSnapshotRejectsCoordinateSwipe() {
        val scrollable = element.copy(
            label = "Results", text = "Results", clickable = false, scrollable = true,
            bounds = DeviceBounds(0, 0, 200, 200),
        )
        val frame = DeviceScreenSnapshot(
            "com.example.app", 7, "incomplete", listOf(scrollable), incomplete = true,
        )
        val call = DeviceActionCall(
            "swipe-incomplete", "swipe", frame.snapshotId,
            mapOf("element_ref" to "0", "start_x" to "10", "start_y" to "10", "end_x" to "10", "end_y" to "100"),
            0,
        )
        assertEquals(
            DevicePolicyReason.SNAPSHOT_INCOMPLETE,
            evaluator.checkAction(policy, DeviceActionCatalog.find("swipe"), call, frame, "com.example.app").reason,
        )
    }

    @Test fun commonEmailAndPaymentPackagesAreBlockedEvenInFullScope() {
        val fullPolicy = policy.copy(scope = DeviceCapabilityScope.FULL_SUPPORT)
        listOf("com.google.android.gm", "com.paypal.android.p2pmobile").forEach { packageName ->
            val decision = evaluator.checkReady(
                fullPolicy, systemAuthorized = true, serviceConnected = true, unlocked = true,
                targetPackage = packageName, currentPackage = packageName,
            )
            assertEquals(packageName, DevicePolicyReason.PACKAGE_NOT_ALLOWED, decision.reason)
        }
    }

    @Test fun notificationReadRejectsMailAndPaymentPackages() {
        val stage6Policy = policy.copy(
            stage6DisclosureAccepted = true,
            stage6Capabilities = setOf(DeviceStage6Capability.NOTIFICATIONS),
            allowedPackages = setOf(
                "com.example.app", "com.google.android.gm", "com.paypal.android.p2pmobile",
                "com.google.android.apps.messaging",
            ),
        )
        listOf(
            "com.google.android.gm", "com.paypal.android.p2pmobile", "com.google.android.apps.messaging",
        ).forEach { packageName ->
            val call = DeviceActionCall(
                "notifications-${packageName.hashCode()}", "read_notifications", snapshot.snapshotId,
                mapOf("package_name" to packageName), 0,
            )
            assertEquals(
                packageName,
                DevicePolicyReason.PACKAGE_NOT_ALLOWED,
                evaluator.checkStage6Action(
                    stage6Policy, DeviceStage6ToolCatalog.find(call.toolName), call, snapshot, "com.example.app",
                ).reason,
            )
        }
    }

    @Test fun blockedSystemPackageIsRejectedEvenInFullScope() {
        val fullPolicy = policy.copy(scope = DeviceCapabilityScope.FULL_SUPPORT)
        val decision = evaluator.checkReady(
            fullPolicy,
            systemAuthorized = true,
            serviceConnected = true,
            unlocked = true,
            targetPackage = "com.android.permissioncontroller",
            currentPackage = "com.android.permissioncontroller",
        )
        assertEquals(DevicePolicyReason.PACKAGE_NOT_ALLOWED, decision.reason)
    }

    @Test fun sensitiveLabelBlocksInputTextEvenWhenElementIsOtherwiseEditable() {
        val sensitive = snapshot.copy(
            elements = listOf(element.copy(editable = true, label = "Password", text = null)),
        )
        val input = DeviceActionCall(
            "password-input",
            "input_text",
            sensitive.snapshotId,
            mapOf("element_ref" to "0", "text" to "hello"),
            0,
        )
        assertEquals(
            DevicePolicyReason.SENSITIVE_ELEMENT,
            evaluator.checkAction(policy, DeviceActionCatalog.find("input_text"), input, sensitive, "com.example.app").reason,
        )
    }

    @Test fun emailLabelBlocksInputTextEvenWhenElementIsOtherwiseEditable() {
        val sensitive = snapshot.copy(
            elements = listOf(element.copy(editable = true, label = "Email", text = null)),
        )
        val input = DeviceActionCall(
            "email-input",
            "input_text",
            sensitive.snapshotId,
            mapOf("element_ref" to "0", "text" to "hello@example.com"),
            0,
        )
        assertEquals(
            DevicePolicyReason.SENSITIVE_ELEMENT,
            evaluator.checkAction(policy, DeviceActionCatalog.find("input_text"), input, sensitive, "com.example.app").reason,
        )
    }

    @Test fun notificationReadMayTargetAnotherAllowedPackage() {
        val stage6Policy = policy.copy(
            stage6DisclosureAccepted = true,
            stage6Capabilities = setOf(DeviceStage6Capability.NOTIFICATIONS),
            allowedPackages = setOf("com.example.app", "com.example.notifications"),
        )
        val call = DeviceActionCall(
            "notification-read",
            "read_notifications",
            snapshot.snapshotId,
            mapOf("package_name" to "com.example.notifications"),
            0,
        )
        val decision = evaluator.checkStage6Action(
            stage6Policy,
            DeviceStage6ToolCatalog.find(call.toolName),
            call,
            snapshot,
            "com.example.app",
        )

        assertTrue(decision.allowed)
        assertTrue(decision.requiresApproval)
    }

    @Test fun notificationReadStillRejectsPackageOutsidePolicy() {
        val stage6Policy = policy.copy(
            stage6DisclosureAccepted = true,
            stage6Capabilities = setOf(DeviceStage6Capability.NOTIFICATIONS),
        )
        val call = DeviceActionCall(
            "notification-read-blocked",
            "read_notifications",
            snapshot.snapshotId,
            mapOf("package_name" to "com.example.notifications"),
            0,
        )
        val decision = evaluator.checkStage6Action(
            stage6Policy,
            DeviceStage6ToolCatalog.find(call.toolName),
            call,
            snapshot,
            "com.example.app",
        )

        assertEquals(DevicePolicyReason.PACKAGE_NOT_ALLOWED, decision.reason)
    }

    @Test fun notificationReadCanTargetCommunicationAppsButNotPlatformControls() {
        val stage6Policy = policy.copy(
            stage6DisclosureAccepted = true,
            stage6Capabilities = setOf(DeviceStage6Capability.NOTIFICATIONS),
            allowedPackages = setOf("com.example.app", "com.whatsapp", "com.android.permissioncontroller"),
        )
        fun decision(packageName: String, callId: String) = evaluator.checkStage6Action(
            stage6Policy,
            DeviceStage6ToolCatalog.find("read_notifications"),
            DeviceActionCall(callId, "read_notifications", snapshot.snapshotId, mapOf("package_name" to packageName), 0),
            snapshot,
            "com.example.app",
        )

        assertTrue(decision("com.whatsapp", "whatsapp-notifications").allowed)
        assertEquals(
            DevicePolicyReason.PACKAGE_NOT_ALLOWED,
            decision("com.android.permissioncontroller", "system-notifications").reason,
        )
    }
}
