package dev.zeroinput.ime.ai.device

import android.content.Context
import dev.zeroinput.ai.api.*
import dev.zeroinput.ime.R
import java.text.DateFormat
import java.util.Date

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

    fun task(
        task: String,
        target: String,
        policy: DeviceAgentPolicy,
        attachments: List<AiAttachment> = emptyList(),
        selectedTargets: List<String> = emptyList(),
    ): String = string(
        R.string.ai_device_agent_task_review_detail,
        "Target: $target\n$task\nAt most 5 minutes / 24 decisions. Clicks and text still need approval.",
        target, task, policy.allowedCategories.joinToString { category(it) },
        if (policy.allowScreenshots) string(R.string.ai_device_agent_yes, "Yes") else string(R.string.ai_device_agent_no, "No"),
        (attachments.map(AiAttachment::displayName) + selectedTargets).joinToString()
            .ifEmpty { string(R.string.ai_device_agent_no, "None") },
        DeviceStage6Catalog.describe(policy.stage6Capabilities).ifEmpty { string(R.string.ai_device_agent_no, "None") },
    )

    fun action(call: DeviceActionCall, snapshot: DeviceScreenSnapshot, selectedAttachmentName: String? = null): String {
        DeviceStage6ToolCatalog.find(call.toolName)?.let { entry ->
            val target = selectedAttachmentName?.takeIf { entry.fixedTarget == "selected_uri" }
                ?: entry.targetId(call.arguments).take(160)
            val fields = entry.fields(call.arguments).entries.joinToString { (key, value) ->
                "${stage6Field(key)}=${stage6FieldValue(key, value)}"
            }
            val actionName = "${stage6Capability(entry.capability)} · ${stage6Operation(entry.operation)}"
            val detail = "$actionName: $target${if (fields.isBlank()) "" else "\n$fields"}"
            return string(
                R.string.ai_device_agent_stage6_action_review_detail,
                detail,
                snapshot.packageName,
            )
        }
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

    private fun stage6Capability(capability: DeviceStage6Capability): String = when (capability) {
        DeviceStage6Capability.NOTIFICATIONS -> string(R.string.ai_device_agent_stage6_notifications, "Read notifications")
        DeviceStage6Capability.MEDIA -> string(R.string.ai_device_agent_stage6_media, "Read or delete media")
        DeviceStage6Capability.SYSTEM_SETTINGS -> string(R.string.ai_device_agent_stage6_system_settings, "System settings")
        DeviceStage6Capability.CAMERA -> string(R.string.ai_device_agent_stage6_camera, "One-time camera capture")
        DeviceStage6Capability.MICROPHONE -> string(R.string.ai_device_agent_stage6_microphone, "One-time microphone capture")
        DeviceStage6Capability.FILES -> string(R.string.ai_device_agent_stage6_files, "Selected file")
        DeviceStage6Capability.SMS -> string(R.string.ai_device_agent_stage6_sms, "SMS")
        DeviceStage6Capability.TELEPHONY -> string(R.string.ai_device_agent_stage6_telephony, "Calls")
        DeviceStage6Capability.CONTACTS -> string(R.string.ai_device_agent_stage6_contacts, "Contacts")
        DeviceStage6Capability.CALENDAR -> string(R.string.ai_device_agent_stage6_calendar, "Calendar")
    }

    private fun stage6Operation(operation: DeviceStage6Operation): String = when (operation) {
        DeviceStage6Operation.READ -> string(R.string.ai_device_agent_stage6_read, "Read")
        DeviceStage6Operation.CREATE -> string(R.string.ai_device_agent_stage6_create, "Create")
        DeviceStage6Operation.UPDATE -> string(R.string.ai_device_agent_stage6_update, "Update")
        DeviceStage6Operation.DELETE -> string(R.string.ai_device_agent_stage6_delete, "Delete")
        DeviceStage6Operation.SEND -> string(R.string.ai_device_agent_stage6_send, "Send")
        DeviceStage6Operation.CAPTURE -> string(R.string.ai_device_agent_stage6_capture, "Capture")
        DeviceStage6Operation.MODIFY -> string(R.string.ai_device_agent_stage6_modify, "Modify")
    }

    private fun stage6Field(key: String): String = when (key) {
        "package_name" -> string(R.string.ai_device_agent_stage6_field_package, "Package")
        "key" -> string(R.string.ai_device_agent_stage6_field_setting, "Setting")
        "value" -> string(R.string.ai_device_agent_stage6_field_value, "Value")
        "body" -> string(R.string.ai_device_agent_stage6_field_message, "Message")
        "display_name" -> string(R.string.ai_device_agent_stage6_field_name, "Name")
        "new_display_name" -> string(R.string.ai_device_agent_stage6_field_new_name, "New name")
        "phone" -> string(R.string.ai_device_agent_stage6_field_phone, "Phone")
        "email" -> string(R.string.ai_device_agent_stage6_field_email, "Email")
        "calendar_name" -> string(R.string.ai_device_agent_stage6_field_calendar, "Calendar")
        "title" -> string(R.string.ai_device_agent_stage6_field_title, "Title")
        "start_epoch_ms" -> string(R.string.ai_device_agent_stage6_field_start, "Start")
        "end_epoch_ms" -> string(R.string.ai_device_agent_stage6_field_end, "End")
        "new_start_epoch_ms" -> string(R.string.ai_device_agent_stage6_field_new_start, "New start")
        "new_end_epoch_ms" -> string(R.string.ai_device_agent_stage6_field_new_end, "New end")
        "description" -> string(R.string.ai_device_agent_stage6_field_description, "Description")
        else -> key.replace('_', ' ')
    }

    private fun stage6FieldValue(key: String, value: String): String = when (key) {
        "start_epoch_ms", "end_epoch_ms", "new_start_epoch_ms", "new_end_epoch_ms" ->
            value.toLongOrNull()?.let { runCatching { DateFormat.getDateTimeInstance().format(Date(it)) }.getOrNull() }
                ?: value.take(160)
        else -> value.take(160)
    }
}
