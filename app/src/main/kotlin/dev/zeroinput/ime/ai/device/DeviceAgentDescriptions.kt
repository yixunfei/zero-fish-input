package dev.zeroinput.ime.ai.device

import android.content.Context
import dev.zeroinput.ai.api.*
import dev.zeroinput.ime.R

/** Resource-backed descriptions also used by the accessibility approval surface. */
internal class DeviceAgentDescriptions(private val context: Context? = null) {
    private fun string(id: Int, fallback: String, vararg args: Any): String = context?.getString(id, *args) ?: fallback
    val running get() = string(R.string.ai_device_agent_running, "Device task running")
    val stopped get() = string(R.string.ai_device_agent_stopped, "Task stopped; check settings, target and permissions")
    val completed get() = string(R.string.ai_device_agent_activity_complete, "Task completed")
    val unavailable get() = string(R.string.ai_device_agent_unavailable, "Another task is running or the assistant is unavailable")
    val taskTitle get() = string(R.string.ai_device_agent_task_approval, "Review task")
    val actionTitle get() = string(R.string.ai_device_agent_action_review, "Review action")

    fun status(state: DeviceAgentState): String = when (state) {
        DeviceAgentState.CONFIGURING -> string(R.string.ai_device_agent_preparing, "Preparing task")
        DeviceAgentState.OBSERVING -> string(R.string.ai_device_agent_observing, "Observing selected app")
        DeviceAgentState.PLAN_REVIEW -> string(R.string.ai_device_agent_planning, "Planning next step")
        DeviceAgentState.ACTION_APPROVAL -> actionTitle
        DeviceAgentState.COMPLETED -> completed
        DeviceAgentState.FAILED, DeviceAgentState.REVOKED, DeviceAgentState.PAUSED -> stopped
        else -> running
    }

    fun task(task: String, target: String, policy: DeviceAgentPolicy): String = string(
        R.string.ai_device_agent_task_review_detail,
        "Target: $target\n$task\nAt most 5 minutes / 24 decisions. Clicks and text still need approval.",
        target, task, policy.allowedCategories.joinToString { category(it) },
        if (policy.allowScreenshots) string(R.string.ai_device_agent_yes, "Yes") else string(R.string.ai_device_agent_no, "No"),
    )

    fun action(call: DeviceActionCall, snapshot: DeviceScreenSnapshot): String {
        val element = snapshot.elements.firstOrNull { it.ref == call.arguments["element_ref"] }
        val target = element?.label ?: element?.text ?: element?.role.orEmpty()
        val name = actionName(call.toolName)
        val detail = when (call.toolName) {
            "input_text" -> call.arguments["text"].orEmpty()
            "swipe" -> "(${call.arguments["start_x"]}, ${call.arguments["start_y"]}) → (${call.arguments["end_x"]}, ${call.arguments["end_y"]})"
            "scroll" -> call.arguments["direction"].orEmpty()
            "wait" -> call.arguments["milliseconds"].orEmpty() + " ms"
            else -> ""
        }
        return string(R.string.ai_device_agent_action_review_detail, "$name: $target\n$detail", snapshot.packageName, name, target, detail)
    }

    fun category(category: DeviceActionCategory): String = when (category) {
        DeviceActionCategory.OBSERVE -> string(R.string.ai_device_agent_category_observe, "Observation")
        DeviceActionCategory.NAVIGATION -> string(R.string.ai_device_agent_category_navigation, "Navigation")
        DeviceActionCategory.POINTER -> string(R.string.ai_device_agent_category_pointer, "Pointer")
        DeviceActionCategory.TEXT_INPUT -> string(R.string.ai_device_agent_category_text, "Text input")
    }

    fun actionName(tool: String): String {
        val id = when (tool) {
            "wait" -> R.string.ai_device_agent_action_wait
            "back" -> R.string.ai_device_agent_action_back
            "home" -> R.string.ai_device_agent_action_home
            "scroll" -> R.string.ai_device_agent_action_scroll
            "click" -> R.string.ai_device_agent_action_click
            "long_click" -> R.string.ai_device_agent_action_long_click
            "swipe" -> R.string.ai_device_agent_action_swipe
            "input_text" -> R.string.ai_device_agent_action_input
            "ime_action" -> R.string.ai_device_agent_action_search
            else -> R.string.ai_device_agent_action_open
        }
        return string(id, tool.replace('_', ' '))
    }
}
