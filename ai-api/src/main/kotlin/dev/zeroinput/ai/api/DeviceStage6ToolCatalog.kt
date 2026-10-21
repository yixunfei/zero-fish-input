package dev.zeroinput.ai.api

/**
 * Static provider tools for direct data operations.  A tool represents one
 * capability and one operation; the model cannot select an arbitrary provider
 * method or supply a map of unvalidated fields.
 */
object DeviceStage6ToolCatalog {
    data class Entry(
        val definition: DeviceActionDefinition,
        val capability: DeviceStage6Capability,
        val operation: DeviceStage6Operation,
        val targetParameter: String? = null,
        val packageParameter: String? = null,
        val fixedTarget: String? = null,
        val fieldParameters: Set<String> = emptySet(),
    ) {
        init {
            require(DeviceStage6Catalog.permits(capability, operation))
            require(definition.risk == DeviceActionRisk.HIGH && definition.requiresApproval)
            require(targetParameter != null || fixedTarget != null)
            require(fieldParameters.all { it in definition.parameters.map(DeviceActionParameter::name) })
        }

        fun targetId(arguments: Map<String, String>): String =
            fixedTarget ?: requireNotNull(targetParameter).let { arguments[it].orEmpty() }

        fun packageName(arguments: Map<String, String>, taskPackage: String): String =
            packageParameter?.let { arguments[it].orEmpty() } ?: taskPackage

        fun fields(arguments: Map<String, String>): Map<String, String> =
            fieldParameters.mapNotNull { key -> arguments[key]?.let { key to it } }.toMap()
    }

    private fun parameter(
        name: String,
        type: DeviceActionParameter.Type = DeviceActionParameter.Type.TEXT,
        required: Boolean = true,
        maxLength: Int = DeviceAgentLimits.MAX_STAGE6_FIELD_CHARS,
    ) = DeviceActionParameter(name, type, required, maxLength)

    private fun tool(
        name: String,
        description: String,
        capability: DeviceStage6Capability,
        operation: DeviceStage6Operation,
        parameters: List<DeviceActionParameter>,
        targetParameter: String? = null,
        packageParameter: String? = null,
        fixedTarget: String? = null,
        fieldParameters: Set<String> = emptySet(),
    ) = Entry(
        DeviceActionDefinition(name, description, DeviceActionCategory.OBSERVE, DeviceActionRisk.HIGH, parameters, true),
        capability, operation, targetParameter, packageParameter, fixedTarget, fieldParameters,
    )

    val entries: List<Entry> = listOf(
        tool("read_notifications", "Read a bounded set of current notifications from the explicitly named package.", DeviceStage6Capability.NOTIFICATIONS, DeviceStage6Operation.READ,
            listOf(parameter("package_name", DeviceActionParameter.Type.PACKAGE_NAME)), targetParameter = "package_name", packageParameter = "package_name"),
        tool("read_selected_media", "Read the single media item selected by the user for this task.", DeviceStage6Capability.MEDIA, DeviceStage6Operation.READ,
            emptyList(), fixedTarget = "selected_uri"),
        tool("delete_selected_media", "Delete the single media item selected by the user after approval.", DeviceStage6Capability.MEDIA, DeviceStage6Operation.DELETE,
            emptyList(), fixedTarget = "selected_uri"),
        tool("read_selected_file", "Read the single document selected by the user for this task.", DeviceStage6Capability.FILES, DeviceStage6Operation.READ,
            emptyList(), fixedTarget = "selected_uri"),
        tool("delete_selected_file", "Delete the single document selected by the user after approval.", DeviceStage6Capability.FILES, DeviceStage6Operation.DELETE,
            emptyList(), fixedTarget = "selected_uri"),
        tool("read_system_setting", "Read one allowlisted non-security system setting.", DeviceStage6Capability.SYSTEM_SETTINGS, DeviceStage6Operation.READ,
            listOf(parameter("key", maxLength = 64)), fixedTarget = "system_setting", fieldParameters = setOf("key")),
        tool("modify_system_setting", "Modify one allowlisted non-security system setting after approval.", DeviceStage6Capability.SYSTEM_SETTINGS, DeviceStage6Operation.MODIFY,
            listOf(parameter("key", maxLength = 64), parameter("value")), fixedTarget = "system_setting", fieldParameters = setOf("key", "value")),
        tool("capture_camera", "Capture one bounded image from the camera for this task after approval.", DeviceStage6Capability.CAMERA, DeviceStage6Operation.CAPTURE,
            emptyList(), fixedTarget = "current_task"),
        tool("capture_microphone", "Capture one bounded audio sample from the microphone for this task after approval.", DeviceStage6Capability.MICROPHONE, DeviceStage6Operation.CAPTURE,
            emptyList(), fixedTarget = "current_task"),
        tool("read_sms", "Read the newest SMS from the explicitly named phone number.", DeviceStage6Capability.SMS, DeviceStage6Operation.READ,
            listOf(parameter("phone_number", maxLength = 32)), targetParameter = "phone_number"),
        tool("send_sms", "Send one SMS to the explicitly named number after approval.", DeviceStage6Capability.SMS, DeviceStage6Operation.SEND,
            listOf(parameter("phone_number", maxLength = 32), parameter("body", maxLength = DeviceAgentLimits.MAX_STAGE6_FIELD_CHARS)),
            targetParameter = "phone_number", fieldParameters = setOf("body")),
        tool("read_call_log", "Read the latest call record for the explicitly named phone number.", DeviceStage6Capability.TELEPHONY, DeviceStage6Operation.READ,
            listOf(parameter("phone_number", maxLength = 32)), targetParameter = "phone_number"),
        tool("place_call", "Start one call to the explicitly named number after approval.", DeviceStage6Capability.TELEPHONY, DeviceStage6Operation.SEND,
            listOf(parameter("phone_number", maxLength = 32)), targetParameter = "phone_number"),
        tool("read_contact", "Read one unique contact selected by exact display name.", DeviceStage6Capability.CONTACTS, DeviceStage6Operation.READ,
            listOf(parameter("display_name", maxLength = 128)), targetParameter = "display_name"),
        tool("create_contact", "Create one contact from explicitly supplied fields after approval.", DeviceStage6Capability.CONTACTS, DeviceStage6Operation.CREATE,
            listOf(parameter("display_name"), parameter("phone", required = false, maxLength = 64), parameter("email", required = false, maxLength = 256)),
            fixedTarget = "new_contact", fieldParameters = setOf("display_name", "phone", "email")),
        tool("update_contact", "Rename one unique contact selected by exact display name after approval.", DeviceStage6Capability.CONTACTS, DeviceStage6Operation.UPDATE,
            listOf(parameter("display_name", maxLength = 128), parameter("new_display_name")), targetParameter = "display_name",
            fieldParameters = setOf("new_display_name")),
        tool("delete_contact", "Delete one unique contact selected by exact display name after approval.", DeviceStage6Capability.CONTACTS, DeviceStage6Operation.DELETE,
            listOf(parameter("display_name", maxLength = 128)), targetParameter = "display_name"),
        tool("read_calendar_event", "Read one unique calendar event selected by exact title and start time.", DeviceStage6Capability.CALENDAR, DeviceStage6Operation.READ,
            listOf(parameter("title", maxLength = 128), parameter("start_epoch_ms", maxLength = 20)), targetParameter = "title",
            fieldParameters = setOf("start_epoch_ms")),
        tool("create_calendar_event", "Create one calendar event after approval.", DeviceStage6Capability.CALENDAR, DeviceStage6Operation.CREATE,
            listOf(parameter("calendar_name", maxLength = 128), parameter("title"), parameter("start_epoch_ms", maxLength = 20),
                parameter("end_epoch_ms", maxLength = 20), parameter("description", required = false)),
            targetParameter = "calendar_name", fieldParameters = setOf("title", "start_epoch_ms", "end_epoch_ms", "description")),
        tool("update_calendar_event", "Update one unique calendar event selected by exact title and start time after approval.", DeviceStage6Capability.CALENDAR, DeviceStage6Operation.UPDATE,
            listOf(parameter("title", maxLength = 128), parameter("start_epoch_ms", maxLength = 20), parameter("new_title", required = false),
                parameter("new_start_epoch_ms", required = false, maxLength = 20), parameter("new_end_epoch_ms", required = false, maxLength = 20),
                parameter("description", required = false)),
            targetParameter = "title", fieldParameters = setOf("start_epoch_ms", "new_title", "new_start_epoch_ms", "new_end_epoch_ms", "description")),
        tool("delete_calendar_event", "Delete one unique calendar event selected by exact title and start time after approval.", DeviceStage6Capability.CALENDAR, DeviceStage6Operation.DELETE,
            listOf(parameter("title", maxLength = 128), parameter("start_epoch_ms", maxLength = 20)), targetParameter = "title",
            fieldParameters = setOf("start_epoch_ms")),
    )

    val definitions: List<DeviceActionDefinition> = entries.map(Entry::definition)
    private val byName = entries.associateBy { it.definition.name }

    fun find(name: String): Entry? = byName[name]

    fun valid(entry: Entry, arguments: Map<String, String>): Boolean {
        if (!DeviceActionArguments.validGeneric(entry.definition, arguments)) return false
        val target = entry.targetId(arguments)
        if (target.isBlank() || target.length > DeviceAgentLimits.MAX_STAGE6_TARGET_CHARS) return false
        if (entry.targetParameter?.contains("uri") == true && !target.startsWith("content://")) return false
        if (entry.targetParameter?.endsWith("_id") == true && target.toLongOrNull() == null) return false
        if (entry.targetParameter in setOf("phone_number", "address") && !validPhoneTarget(target)) return false
        if (entry.packageParameter != null && !arguments[entry.packageParameter].orEmpty().matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.]{0,254}"))) return false
        if (entry.definition.name.contains("system_setting") && arguments["key"].orEmpty() !in setOf(
                "screen_brightness", "screen_off_timeout", "accelerometer_rotation", "sound_effects_enabled")) return false
        if (entry.definition.name == "modify_system_setting" &&
            !validSettingValue(arguments["key"].orEmpty(), arguments["value"].orEmpty())) return false
        if (entry.definition.name == "send_sms" && arguments["body"].orEmpty().isBlank()) return false
        if (entry.definition.name == "create_contact" && arguments["display_name"].orEmpty().isBlank()) return false
        if (entry.definition.name == "update_contact" && arguments["new_display_name"].orEmpty().isBlank()) return false
        if (entry.definition.name == "create_calendar_event") {
            if (arguments["title"].orEmpty().isBlank()) return false
            if (!validEventWindow(arguments["start_epoch_ms"], arguments["end_epoch_ms"])) return false
        }
        if (entry.definition.name == "update_calendar_event") {
            val hasTitle = !arguments["new_title"].isNullOrBlank()
            val hasDescription = !arguments["description"].isNullOrBlank()
            val hasTime = arguments["new_start_epoch_ms"] != null || arguments["new_end_epoch_ms"] != null
            if (!hasTitle && !hasDescription && !hasTime) return false
            if (hasTime && !validEventWindow(arguments["new_start_epoch_ms"], arguments["new_end_epoch_ms"])) return false
        }
        return true
    }

    fun validSettingValue(key: String, value: String): Boolean = when (key) {
        "screen_brightness" -> value.toIntOrNull()?.let { it in 0..255 } == true
        "screen_off_timeout" -> value.toLongOrNull()?.let { it in 0..86_400_000L } == true
        "accelerometer_rotation", "sound_effects_enabled" -> value == "0" || value == "1"
        else -> false
    }

    /** Rejects USSD/service codes so a call can only target a conventional number. */
    private fun validPhoneTarget(value: String): Boolean {
        if (!value.matches(Regex("\\+?[0-9][0-9(). -]{2,31}"))) return false
        val open = value.indexOf('(')
        val close = value.indexOf(')')
        if (value.count { it == '(' } > 1 || value.count { it == ')' } > 1 ||
            (open >= 0 && (close <= open + 1 || close == value.lastIndex)) ||
            (open < 0) != (close < 0)) return false
        return value.count(Char::isDigit) in 3..15
    }

    private fun validEventWindow(startValue: String?, endValue: String?): Boolean {
        val start = startValue?.toLongOrNull() ?: return false
        val end = endValue?.toLongOrNull() ?: return false
        return start > 0L && end > start && end - start <= MAX_EVENT_DURATION_MS
    }

    private const val MAX_EVENT_DURATION_MS = 24 * 60 * 60 * 1_000L
}
