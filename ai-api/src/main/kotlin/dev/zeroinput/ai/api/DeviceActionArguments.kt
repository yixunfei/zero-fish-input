package dev.zeroinput.ai.api

/** Shared validation for decoding, policy evaluation and the final platform boundary. */
object DeviceActionArguments {
    fun valid(definition: DeviceActionDefinition, arguments: Map<String, String>): Boolean {
        DeviceStage6ToolCatalog.find(definition.name)?.let { return DeviceStage6ToolCatalog.valid(it, arguments) }
        return validGeneric(definition, arguments)
    }

    internal fun validGeneric(definition: DeviceActionDefinition, arguments: Map<String, String>): Boolean {
        if (arguments.keys.any { key -> definition.parameters.none { it.name == key } }) return false
        if (definition.parameters.any { parameter ->
                val value = arguments[parameter.name]
                if (value == null) parameter.required else !validValue(parameter, value)
            }) return false
        return when (definition.name) {
            "wait" -> arguments["milliseconds"]?.toIntOrNull() in 0..5_000
            "swipe" -> listOf("start_x", "start_y", "end_x", "end_y").all {
                arguments[it]?.toIntOrNull() in 0..100_000
            }
            else -> true
        }
    }

    private fun validValue(parameter: DeviceActionParameter, value: String): Boolean {
        if (value.isEmpty() || value.length > parameter.maxLength ||
            value.any { it.isISOControl() && it !in "\n\r\t" }) return false
        return when (parameter.type) {
            DeviceActionParameter.Type.ELEMENT_REF -> value.matches(Regex("0(?:\\.[0-9]{1,4}){0,32}"))
            DeviceActionParameter.Type.TEXT -> true
            DeviceActionParameter.Type.INTEGER -> value.toIntOrNull()?.let { it in -100_000..100_000 } == true
            DeviceActionParameter.Type.DECIMAL -> value.toDoubleOrNull()?.isFinite() == true
            DeviceActionParameter.Type.DIRECTION -> value in setOf("forward", "backward", "up", "down", "left", "right")
            DeviceActionParameter.Type.PACKAGE_NAME -> value.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.]{0,254}"))
        }
    }
}
