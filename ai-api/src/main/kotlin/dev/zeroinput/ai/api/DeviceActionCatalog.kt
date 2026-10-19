package dev.zeroinput.ai.api

/** Static tool directory exposed to the model. New actions require a code and test review. */
object DeviceActionCatalog {
    val definitions: List<DeviceActionDefinition> = listOf(
        DeviceActionDefinition(
            name = "wait",
            description = "Wait briefly for the current page to settle.",
            category = DeviceActionCategory.OBSERVE,
            risk = DeviceActionRisk.SAFE,
            parameters = listOf(DeviceActionParameter("milliseconds", DeviceActionParameter.Type.INTEGER, maxLength = 4)),
            requiresApproval = false,
        ),
        DeviceActionDefinition(
            name = "back",
            description = "Navigate back in the current application.",
            category = DeviceActionCategory.NAVIGATION,
            risk = DeviceActionRisk.REVERSIBLE,
        ),
        DeviceActionDefinition(
            name = "home",
            description = "Navigate to the Android home screen.",
            category = DeviceActionCategory.NAVIGATION,
            risk = DeviceActionRisk.REVERSIBLE,
        ),
        DeviceActionDefinition(
            name = "scroll",
            description = "Scroll a visible scrollable element.",
            category = DeviceActionCategory.POINTER,
            risk = DeviceActionRisk.SAFE,
            parameters = listOf(
                DeviceActionParameter("element_ref", DeviceActionParameter.Type.ELEMENT_REF),
                DeviceActionParameter("direction", DeviceActionParameter.Type.DIRECTION),
            ),
            requiresApproval = false,
        ),
        DeviceActionDefinition(
            name = "click",
            description = "Click one visible, enabled interactive element.",
            category = DeviceActionCategory.POINTER,
            risk = DeviceActionRisk.REVERSIBLE,
            parameters = listOf(DeviceActionParameter("element_ref", DeviceActionParameter.Type.ELEMENT_REF)),
        ),
        DeviceActionDefinition(
            name = "long_click",
            description = "Long press one visible, enabled interactive element.",
            category = DeviceActionCategory.POINTER,
            risk = DeviceActionRisk.REVERSIBLE,
            parameters = listOf(DeviceActionParameter("element_ref", DeviceActionParameter.Type.ELEMENT_REF)),
        ),
        DeviceActionDefinition(
            name = "swipe",
            description = "Swipe inside one visible scrollable element using bounded coordinates.",
            category = DeviceActionCategory.POINTER,
            risk = DeviceActionRisk.REVERSIBLE,
            parameters = listOf(
                DeviceActionParameter("element_ref", DeviceActionParameter.Type.ELEMENT_REF),
                DeviceActionParameter("start_x", DeviceActionParameter.Type.INTEGER, maxLength = 5),
                DeviceActionParameter("start_y", DeviceActionParameter.Type.INTEGER, maxLength = 5),
                DeviceActionParameter("end_x", DeviceActionParameter.Type.INTEGER, maxLength = 5),
                DeviceActionParameter("end_y", DeviceActionParameter.Type.INTEGER, maxLength = 5),
            ),
        ),
        DeviceActionDefinition(
            name = "input_text",
            description = "Enter non-sensitive text into one visible editable element.",
            category = DeviceActionCategory.TEXT_INPUT,
            risk = DeviceActionRisk.REVERSIBLE,
            parameters = listOf(
                DeviceActionParameter("element_ref", DeviceActionParameter.Type.ELEMENT_REF),
                DeviceActionParameter("text", DeviceActionParameter.Type.TEXT, maxLength = 256),
            ),
        ),
        DeviceActionDefinition(
            name = "ime_action",
            description = "Invoke the visible editor action on a non-sensitive field.",
            category = DeviceActionCategory.TEXT_INPUT,
            risk = DeviceActionRisk.REVERSIBLE,
            parameters = listOf(DeviceActionParameter("element_ref", DeviceActionParameter.Type.ELEMENT_REF)),
        ),
        DeviceActionDefinition(
            name = "open_app",
            description = "Open the explicitly selected target application.",
            category = DeviceActionCategory.NAVIGATION,
            risk = DeviceActionRisk.REVERSIBLE,
            parameters = listOf(DeviceActionParameter("package_name", DeviceActionParameter.Type.PACKAGE_NAME)),
        ),
    )

    private val byName = definitions.associateBy { it.name }

    fun find(name: String): DeviceActionDefinition? = byName[name]

    fun require(name: String): DeviceActionDefinition =
        find(name) ?: throw IllegalArgumentException("Unknown device action")
}
