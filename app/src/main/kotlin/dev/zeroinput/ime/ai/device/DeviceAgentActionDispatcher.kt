package dev.zeroinput.ime.ai.device

import dev.zeroinput.ai.api.*

/** There is no unchecked execution path; the broker consumes any required approval. */
internal class DeviceAgentActionDispatcher(
    private val broker: DeviceAgentAccessibilityBroker,
    private val evaluator: DeviceAgentPolicyEvaluator,
) {
    fun dispatch(
        owner: DeviceTaskLease, token: Long, snapshot: DeviceScreenSnapshot, call: DeviceActionCall,
        deliver: (DeviceActionResult) -> Unit,
    ) {
        val definition = DeviceActionCatalog.find(call.toolName)
        val decision = evaluator.checkAction(owner.policy, definition, call, snapshot, broker.currentWindow()?.packageName)
        if (!decision.allowed) {
            deliver(DeviceActionResult(call.callId, DeviceActionResultStatus.REJECTED, decision.reason.name))
        } else broker.perform(owner, token, call, snapshot, deliver)
    }
}
