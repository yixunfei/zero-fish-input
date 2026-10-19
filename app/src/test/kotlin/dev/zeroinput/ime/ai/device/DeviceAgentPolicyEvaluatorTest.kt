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
}
