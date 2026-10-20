package dev.zeroinput.ime.ai.device

import dev.zeroinput.ai.api.*

enum class DevicePolicyReason {
    ALLOWED, DISABLED, DISCLOSURE_REQUIRED, SERVICE_UNAVAILABLE, DEVICE_LOCKED,
    TARGET_MISMATCH, PACKAGE_NOT_ALLOWED, SNAPSHOT_EXPIRED, UNKNOWN_ACTION,
    SENSITIVE_ELEMENT, INVALID_ARGUMENT, HARD_BLOCKED, CATEGORY_NOT_ALLOWED, APPROVAL_REQUIRED,
}

data class DevicePolicyDecision(val allowed: Boolean, val reason: DevicePolicyReason, val requiresApproval: Boolean = false) {
    companion object {
        fun allow(requiresApproval: Boolean = false) = DevicePolicyDecision(true, DevicePolicyReason.ALLOWED, requiresApproval)
        fun deny(reason: DevicePolicyReason) = DevicePolicyDecision(false, reason)
    }
}

/** Pure local policy, shared by the planner and the final execution boundary. */
class DeviceAgentPolicyEvaluator(private val ownPackage: String = "dev.zeroinput.ime") {
    fun checkTarget(policy: DeviceAgentPolicy, target: String): DevicePolicyDecision {
        if (!policy.enabled || policy.scope == DeviceCapabilityScope.DISABLED) return deny(DevicePolicyReason.DISABLED)
        if (!policy.disclosureAccepted) return deny(DevicePolicyReason.DISCLOSURE_REQUIRED)
        if (!target.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.]{0,254}"))) return deny(DevicePolicyReason.TARGET_MISMATCH)
        if (!policy.permitsPackage(target) || DeviceAgentSafety.blockedPackage(target, ownPackage)) {
            return deny(DevicePolicyReason.PACKAGE_NOT_ALLOWED)
        }
        if (DeviceActionCategory.OBSERVE !in policy.allowedCategories) return deny(DevicePolicyReason.CATEGORY_NOT_ALLOWED)
        return DevicePolicyDecision.allow()
    }

    fun checkReady(
        policy: DeviceAgentPolicy, systemAuthorized: Boolean, serviceConnected: Boolean, unlocked: Boolean,
        targetPackage: String, currentPackage: String?,
    ): DevicePolicyDecision {
        val target = checkTarget(policy, targetPackage)
        if (!target.allowed) return target
        if (!systemAuthorized || !serviceConnected) return deny(DevicePolicyReason.SERVICE_UNAVAILABLE)
        if (!unlocked) return deny(DevicePolicyReason.DEVICE_LOCKED)
        if (currentPackage != targetPackage) return deny(DevicePolicyReason.TARGET_MISMATCH)
        return DevicePolicyDecision.allow()
    }

    fun checkAction(
        policy: DeviceAgentPolicy, definition: DeviceActionDefinition?, call: DeviceActionCall,
        snapshot: DeviceScreenSnapshot, currentPackage: String?, taskApproved: Boolean = false,
    ): DevicePolicyDecision {
        val ready = checkTarget(policy, snapshot.packageName)
        if (!ready.allowed) return ready
        if (definition == null || definition.name != call.toolName) return deny(DevicePolicyReason.UNKNOWN_ACTION)
        if (definition.category !in policy.allowedCategories) return deny(DevicePolicyReason.CATEGORY_NOT_ALLOWED)
        if (snapshot.snapshotId != call.snapshotId || currentPackage != snapshot.packageName) return deny(DevicePolicyReason.SNAPSHOT_EXPIRED)
        if (!DeviceActionArguments.valid(definition, call.arguments)) return deny(DevicePolicyReason.INVALID_ARGUMENT)
        if (definition.risk == DeviceActionRisk.HIGH) return deny(DevicePolicyReason.HARD_BLOCKED)
        if (call.toolName == "open_app" && call.arguments["package_name"] != snapshot.packageName) return deny(DevicePolicyReason.TARGET_MISMATCH)
        val element = call.arguments["element_ref"]?.let { ref -> snapshot.elements.firstOrNull { it.ref == ref } }
        if (call.arguments.containsKey("element_ref") && element == null) return deny(DevicePolicyReason.INVALID_ARGUMENT)
        if (element != null) {
            if (!element.enabled || element.password || element.sensitive) return deny(DevicePolicyReason.SENSITIVE_ELEMENT)
            val elementDecision = checkElement(call, element, snapshot)
            if (!elementDecision.allowed) return elementDecision
        }
        val approval = when (policy.approvalMode) {
            DeviceApprovalMode.EACH_ACTION -> true
            DeviceApprovalMode.TASK -> !taskApproved || call.toolName in setOf("click", "long_click", "swipe", "input_text", "ime_action")
            DeviceApprovalMode.SAFE_AUTOMATIC -> !isSafeAutomatic(definition)
        }
        return DevicePolicyDecision.allow(approval)
    }

    fun checkStage6Action(
        policy: DeviceAgentPolicy,
        entry: DeviceStage6ToolCatalog.Entry?,
        call: DeviceActionCall,
        snapshot: DeviceScreenSnapshot,
        currentPackage: String?,
    ): DevicePolicyDecision {
        val ready = checkTarget(policy, snapshot.packageName)
        if (!ready.allowed) return ready
        if (entry == null || entry.definition.name != call.toolName ||
            !policy.permitsStage6(entry.capability) || !DeviceStage6Catalog.permits(entry.capability, entry.operation)) {
            return deny(DevicePolicyReason.CATEGORY_NOT_ALLOWED)
        }
        if (snapshot.snapshotId != call.snapshotId || currentPackage != snapshot.packageName) {
            return deny(DevicePolicyReason.SNAPSHOT_EXPIRED)
        }
        if (!DeviceActionArguments.valid(entry.definition, call.arguments)) {
            return deny(DevicePolicyReason.INVALID_ARGUMENT)
        }
        val requestedPackage = entry.packageParameter?.let { call.arguments[it] }
        if (requestedPackage != null &&
            (!policy.permitsPackage(requestedPackage) ||
                DeviceAgentSafety.blockedNotificationPackage(requestedPackage, ownPackage))) {
            return deny(DevicePolicyReason.PACKAGE_NOT_ALLOWED)
        }
        // Stage 6 calls always require a fresh approval, including read-only calls.
        return DevicePolicyDecision.allow(requiresApproval = true)
    }

    private fun checkElement(call: DeviceActionCall, element: DeviceElement, snapshot: DeviceScreenSnapshot): DevicePolicyDecision {
        if (call.toolName == "input_text" &&
            (DeviceAgentSafety.sensitiveLabel(element.label) || DeviceAgentSafety.blockedEffect(element.label))) {
            return deny(DevicePolicyReason.SENSITIVE_ELEMENT)
        }
        val allowed = when (call.toolName) {
            "click" -> element.clickable && DeviceAgentSafety.safePointer(element)
            "long_click" -> element.longClickable && DeviceAgentSafety.safePointer(element)
            "input_text" -> element.editable
            "ime_action" -> element.editable && DeviceAgentSafety.safeSearchAction(element.imeActionLabel)
            "scroll" -> element.scrollable && !element.editable
            "swipe" -> safeSwipe(call, element, snapshot)
            else -> false
        }
        return if (allowed) DevicePolicyDecision.allow() else deny(DevicePolicyReason.HARD_BLOCKED)
    }

    private fun safeSwipe(call: DeviceActionCall, element: DeviceElement, snapshot: DeviceScreenSnapshot): Boolean {
        if (!element.scrollable || element.editable) return false
        val sx = call.arguments.getValue("start_x").toInt()
        val sy = call.arguments.getValue("start_y").toInt()
        val ex = call.arguments.getValue("end_x").toInt()
        val ey = call.arguments.getValue("end_y").toInt()
        if (!DeviceAgentSafety.contains(element.bounds, sx, sy) || !DeviceAgentSafety.contains(element.bounds, ex, ey)) return false
        if (kotlin.math.hypot((ex - sx).toDouble(), (ey - sy).toDouble()) !in 8.0..1_500.0) return false
        val path = DeviceBounds(minOf(sx, ex), minOf(sy, ey), maxOf(sx, ex) + 1, maxOf(sy, ey) + 1)
        return snapshot.elements.none { (it.sensitive || it.password || it.editable) && DeviceAgentSafety.intersects(path, it.bounds) }
    }

    fun isSafeAutomatic(definition: DeviceActionDefinition): Boolean = definition.risk == DeviceActionRisk.SAFE && !definition.requiresApproval
    private fun deny(reason: DevicePolicyReason) = DevicePolicyDecision.deny(reason)
}
