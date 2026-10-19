package dev.zeroinput.ai.api

import java.util.UUID
import java.util.Collections

/** The two independent controls that limit what a device task may do. */
enum class DeviceCapabilityScope {
    DISABLED,
    ALLOWLIST,
    FULL_SUPPORT,
}

enum class DeviceApprovalMode {
    EACH_ACTION,
    TASK,
    SAFE_AUTOMATIC,
}

enum class DeviceActionCategory {
    OBSERVE,
    NAVIGATION,
    POINTER,
    TEXT_INPUT,
}

enum class DeviceActionRisk {
    SAFE,
    REVERSIBLE,
    HIGH,
}

enum class DeviceAgentState {
    OFF,
    DISCLOSURE_REQUIRED,
    AUTHORIZATION_PENDING,
    READY,
    CONFIGURING,
    OBSERVING,
    PLAN_REVIEW,
    ACTION_APPROVAL,
    EXECUTING,
    PAUSED,
    COMPLETED,
    FAILED,
    REVOKED,
}

data class DeviceBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init {
        require(left >= 0 && top >= 0 && right > left && bottom > top) { "Invalid device bounds" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top

    override fun toString(): String = "DeviceBounds($left,$top,$right,$bottom)"
}

/** A public, bounded representation of one visible interactive element. */
data class DeviceElement(
    val ref: String,
    val role: String,
    val label: String?,
    val text: String?,
    val bounds: DeviceBounds,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val password: Boolean,
    val sensitive: Boolean,
    val enabled: Boolean,
    val imeActionLabel: String? = null,
) {
    init {
        require(ref.matches(Regex("[0-9.]{1,96}"))) { "Invalid device element reference" }
        require(role.length in 1..128 && role.none(Char::isISOControl)) { "Invalid device element role" }
        listOf(label, text, imeActionLabel).filterNotNull().forEach {
            require(it.length <= DeviceAgentLimits.MAX_ELEMENT_TEXT &&
                it.none { character -> character.isISOControl() && character !in "\n\r\t" }) {
                "Device element text is invalid"
            }
        }
        if (password || sensitive) require(text == null && label == null && imeActionLabel == null) { "Sensitive element content must be omitted" }
        if (editable) require(text == null) { "Editor content must be omitted" }
    }

    override fun toString(): String = "DeviceElement(ref=$ref,role=$role,sensitive=$sensitive)"
}

class DeviceScreenSnapshot(
    val packageName: String,
    val windowId: Int,
    val snapshotId: String = UUID.randomUUID().toString(),
    elements: List<DeviceElement>,
    val incomplete: Boolean = false,
    screenshotJpeg: ByteArray? = null,
    val capturedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    val elements: List<DeviceElement> = Collections.unmodifiableList(elements.toList())
    private var pixels = screenshotJpeg?.copyOf()
    val screenshotJpeg: ByteArray? get() = synchronized(this) { pixels?.copyOf() }
    val hasScreenshot: Boolean get() = synchronized(this) { pixels != null }

    /** The caller receives a disposable copy; the owned frame never escapes. */
    fun <T> useScreenshot(block: (ByteArray?) -> T): T {
        val copy = screenshotJpeg
        return try { block(copy) } finally { copy?.fill(0) }
    }

    fun clearScreenshot() = synchronized(this) { pixels?.fill(0); pixels = null }

    fun copy(
        elements: List<DeviceElement> = this.elements,
        screenshotJpeg: ByteArray? = this.screenshotJpeg,
        packageName: String = this.packageName,
        windowId: Int = this.windowId,
        snapshotId: String = this.snapshotId,
        incomplete: Boolean = this.incomplete,
    ): DeviceScreenSnapshot = DeviceScreenSnapshot(
        packageName, windowId, snapshotId, elements, incomplete, screenshotJpeg, capturedAtEpochMillis,
    )
    init {
        require(packageName.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.]{0,254}"))) { "Invalid target package" }
        require(windowId >= 0) { "Invalid window id" }
        require(snapshotId.length in 1..DeviceAgentLimits.MAX_ID_CHARS && snapshotId.none(Char::isISOControl)) {
            "Invalid snapshot id"
        }
        require(elements.size <= DeviceAgentLimits.MAX_NODES) { "Too many device elements" }
        require(elements.map { it.ref }.toSet().size == elements.size) { "Duplicate device element reference" }
        require(elements.sumOf { (it.label?.length ?: 0) + (it.text?.length ?: 0) } <=
            DeviceAgentLimits.MAX_SNAPSHOT_TEXT_CHARS) { "Device snapshot text is too large" }
        require(screenshotJpeg == null || screenshotJpeg.size <= DeviceAgentLimits.MAX_SCREENSHOT_BYTES) {
            "Device screenshot is too large"
        }
    }

    override fun toString(): String =
        "DeviceScreenSnapshot(elements=${elements.size},incomplete=$incomplete)"
}

data class DeviceTaskToken(
    val sessionGeneration: Long,
    val taskId: String,
    val packageName: String,
    val windowId: Int,
    val snapshotId: String,
) {
    init {
        require(sessionGeneration > 0) { "Invalid session generation" }
        require(taskId.length in 1..DeviceAgentLimits.MAX_ID_CHARS && taskId.none(Char::isISOControl)) {
            "Invalid device task id"
        }
        require(packageName.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.]{0,254}"))) { "Invalid target package" }
        require(windowId >= 0) { "Invalid window id" }
        require(snapshotId.length in 1..DeviceAgentLimits.MAX_ID_CHARS && snapshotId.none(Char::isISOControl)) {
            "Invalid snapshot id"
        }
    }

    override fun toString(): String = "DeviceTaskToken(redacted)"
}

data class DeviceActionParameter(
    val name: String,
    val type: Type,
    val required: Boolean = true,
    val maxLength: Int = DeviceAgentLimits.MAX_ARGUMENT_CHARS,
) {
    enum class Type { ELEMENT_REF, TEXT, INTEGER, DECIMAL, DIRECTION, PACKAGE_NAME }

    init {
        require(name.matches(Regex("[a-z][a-z0-9_]{0,31}"))) { "Invalid action parameter" }
        require(maxLength in 1..DeviceAgentLimits.MAX_ARGUMENT_CHARS) { "Invalid action parameter limit" }
    }
}

data class DeviceActionDefinition(
    val name: String,
    val description: String,
    val category: DeviceActionCategory,
    val risk: DeviceActionRisk,
    val parameters: List<DeviceActionParameter> = emptyList(),
    val requiresApproval: Boolean = risk != DeviceActionRisk.SAFE,
) {
    init {
        require(name.matches(Regex("[a-z][a-z0-9_]{1,47}"))) { "Invalid action name" }
        require(description.length in 1..256 && description.none(Char::isISOControl)) { "Invalid action description" }
        require(parameters.map { it.name }.toSet().size == parameters.size) { "Duplicate action parameter" }
    }
}

class DeviceActionCall(
    val callId: String,
    val toolName: String,
    val snapshotId: String,
    arguments: Map<String, String>,
    val round: Int,
) {
    val arguments: Map<String, String> = Collections.unmodifiableMap(arguments.toMap())

    fun copy(
        snapshotId: String = this.snapshotId,
        round: Int = this.round,
        arguments: Map<String, String> = this.arguments,
    ) = DeviceActionCall(callId, toolName, snapshotId, arguments, round)
    init {
        require(callId.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid action call id" }
        require(toolName.matches(Regex("[a-z][a-z0-9_]{1,47}"))) { "Invalid action tool" }
        require(snapshotId.length in 1..DeviceAgentLimits.MAX_ID_CHARS && snapshotId.none(Char::isISOControl)) {
            "Invalid action snapshot id"
        }
        require(arguments.size <= DeviceAgentLimits.MAX_ARGUMENTS) { "Too many action arguments" }
        arguments.forEach { (key, value) ->
            require(key.matches(Regex("[a-z][a-z0-9_]{0,31}"))) { "Invalid action argument" }
            require(value.length <= DeviceAgentLimits.MAX_ARGUMENT_CHARS &&
                value.none { it.isISOControl() && it !in "\n\r\t" }) { "Invalid action argument value" }
        }
        require(round in 0 until DeviceAgentLimits.MAX_MODEL_ROUNDS) { "Invalid model round" }
    }

    override fun toString(): String = "DeviceActionCall(id=$callId,tool=$toolName,round=$round)"
}

enum class DeviceActionResultStatus { SUCCESS, REJECTED, EXPIRED, FAILED }

data class DeviceActionResult(
    val callId: String,
    val status: DeviceActionResultStatus,
    val summary: String,
    val retryable: Boolean = false,
) {
    init {
        require(callId.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Invalid action result id" }
        require(summary.length in 1..DeviceAgentLimits.MAX_TOOL_RESULT_CHARS &&
            summary.none { it.isISOControl() && it !in "\n\r\t" }) { "Invalid action result" }
    }

    override fun toString(): String = "DeviceActionResult(call=$callId,status=$status,retryable=$retryable)"
}

class DeviceAgentPolicy(
    val enabled: Boolean = false,
    val disclosureAccepted: Boolean = false,
    val scope: DeviceCapabilityScope = DeviceCapabilityScope.DISABLED,
    val approvalMode: DeviceApprovalMode = DeviceApprovalMode.EACH_ACTION,
    val allowScreenshots: Boolean = false,
    allowedPackages: Set<String> = emptySet(),
    allowedCategories: Set<DeviceActionCategory> = DeviceActionCategory.entries.toSet(),
) {
    val allowedPackages: Set<String> = Collections.unmodifiableSet(allowedPackages.toSet())
    val allowedCategories: Set<DeviceActionCategory> = Collections.unmodifiableSet(allowedCategories.toSet())

    fun copy(
        enabled: Boolean = this.enabled,
        disclosureAccepted: Boolean = this.disclosureAccepted,
        scope: DeviceCapabilityScope = this.scope,
        approvalMode: DeviceApprovalMode = this.approvalMode,
        allowScreenshots: Boolean = this.allowScreenshots,
        allowedPackages: Set<String> = this.allowedPackages,
        allowedCategories: Set<DeviceActionCategory> = this.allowedCategories,
    ) = DeviceAgentPolicy(enabled, disclosureAccepted, scope, approvalMode, allowScreenshots, allowedPackages, allowedCategories)

    /** Per-task consent may tighten screenshot permission, never expand a setting. */
    fun isWithin(current: DeviceAgentPolicy): Boolean =
        enabled && current.enabled && disclosureAccepted && current.disclosureAccepted &&
            scope == current.scope && approvalMode == current.approvalMode &&
            allowedPackages == current.allowedPackages && allowedCategories == current.allowedCategories &&
            (!allowScreenshots || current.allowScreenshots)
    init {
        require(allowedPackages.size <= DeviceAgentLimits.MAX_ALLOWLIST_PACKAGES) { "Too many device packages" }
        allowedPackages.forEach {
            require(it.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.]{0,254}"))) { "Invalid device package" }
        }
        if (scope == DeviceCapabilityScope.DISABLED) require(!enabled) { "Disabled device policy cannot be enabled" }
    }

    fun permitsPackage(packageName: String): Boolean = scope == DeviceCapabilityScope.FULL_SUPPORT ||
        scope == DeviceCapabilityScope.ALLOWLIST && packageName in allowedPackages

    override fun toString(): String =
        "DeviceAgentPolicy(enabled=$enabled,scope=$scope,approval=$approvalMode,screenshots=$allowScreenshots)"
}

class DeviceAgentModelRequest(
    val task: String,
    val targetPackage: String,
    val snapshot: DeviceScreenSnapshot,
    tools: List<DeviceActionDefinition>,
    history: List<DeviceAgentTurn> = emptyList(),
    val model: String? = null,
) {
    val tools: List<DeviceActionDefinition> = Collections.unmodifiableList(tools.toList())
    val history: List<DeviceAgentTurn> = Collections.unmodifiableList(history.toList())
    init {
        require(task.isNotBlank() && task.length <= DeviceAgentLimits.MAX_TASK_CHARS) { "Invalid device task" }
        require(snapshot.packageName == targetPackage) { "Snapshot target mismatch" }
        require(tools.size in 1..DeviceAgentLimits.MAX_TOOLS) { "Invalid device tool set" }
        require(history.size <= DeviceAgentLimits.MAX_ACTIONS) { "Too many device turns" }
        require(history.map { it.call.callId }.toSet().size == history.size) { "Duplicate device turn" }
        require(history.sumOf { turn -> turn.continuation.sumOf { it.encryptedContent.length } } <=
            DeviceAgentLimits.MAX_TRANSCRIPT_CHARS) { "Device continuation limit exceeded" }
    }

    fun copy(snapshot: DeviceScreenSnapshot = this.snapshot) =
        DeviceAgentModelRequest(task, targetPackage, snapshot, tools, history, model)

    override fun toString(): String = "DeviceAgentModelRequest(tools=${tools.size},turns=${history.size})"
}

class DeviceReasoningItem(val id: String, val encryptedContent: String) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid continuation id" }
        require(encryptedContent.length in 1..DeviceAgentLimits.MAX_CONTINUATION_CHARS) { "Invalid continuation" }
    }
    override fun toString(): String = "DeviceReasoningItem(redacted)"
}

class DeviceAgentTurn(val call: DeviceActionCall, val result: DeviceActionResult, continuation: List<DeviceReasoningItem> = emptyList()) {
    val continuation: List<DeviceReasoningItem> = Collections.unmodifiableList(continuation.toList())
    init { require(call.callId == result.callId && continuation.size <= 4) { "Invalid device turn" } }
    override fun toString(): String = "DeviceAgentTurn(tool=${call.toolName},status=${result.status})"
}

class DeviceAgentModelResponse(
    val message: String?,
    calls: List<DeviceActionCall>,
    val completed: Boolean,
    continuation: List<DeviceReasoningItem> = emptyList(),
) {
    val calls: List<DeviceActionCall> = Collections.unmodifiableList(calls.toList())
    val continuation: List<DeviceReasoningItem> = Collections.unmodifiableList(continuation.toList())
    init {
        require(message == null || message.length <= DeviceAgentLimits.MAX_MESSAGE_CHARS) { "Device model message is too large" }
        require(calls.size <= DeviceAgentLimits.MAX_ACTIONS_PER_RESPONSE) { "Too many device actions" }
        require(completed || calls.isNotEmpty()) { "Device model response has no next step" }
        require(!completed || calls.isEmpty()) { "Completed device response has actions" }
        require(continuation.size <= 4) { "Too many continuation items" }
    }
    override fun toString(): String = "DeviceAgentModelResponse(calls=${calls.size},completed=$completed)"
}

sealed interface DeviceAgentEvent {
    data class State(val state: DeviceAgentState, val message: String) : DeviceAgentEvent
    data class ApprovalRequired(
        val call: DeviceActionCall,
        val action: DeviceActionDefinition,
        val description: String,
    ) : DeviceAgentEvent
    data class TaskApprovalRequired(val approvalId: String, val description: String) : DeviceAgentEvent
    data class ActionFinished(val result: DeviceActionResult) : DeviceAgentEvent
    data class Completed(val message: String) : DeviceAgentEvent
    data class Failed(val message: String) : DeviceAgentEvent
}

sealed interface DeviceAgentProviderEvent {
    data object Started : DeviceAgentProviderEvent
    data class Completed(val response: DeviceAgentModelResponse) : DeviceAgentProviderEvent
    data class Failed(val error: AiProviderError) : DeviceAgentProviderEvent
    data object Cancelled : DeviceAgentProviderEvent
}

interface DeviceAgentProvider {
    fun request(request: DeviceAgentModelRequest, listener: (DeviceAgentProviderEvent) -> Unit): AiRequestHandle
}

object DeviceAgentLimits {
    const val MAX_ID_CHARS = 128
    const val MAX_TASK_CHARS = 8_192
    const val MAX_MESSAGE_CHARS = 8_192
    const val MAX_ELEMENT_TEXT = 512
    const val MAX_NODES = 512
    const val MAX_NODE_VISITS = 2_048
    const val MAX_PENDING_NODES = 512
    const val MAX_SNAPSHOT_TEXT_CHARS = 32_768
    const val MAX_DEPTH = 32
    const val MAX_SCREENSHOT_BYTES = 2 * 1024 * 1024
    const val MAX_SCREENSHOT_PIXELS = 4_000_000L
    const val MAX_REQUEST_BYTES = 4 * 1024 * 1024
    const val MAX_ARGUMENT_CHARS = 512
    const val MAX_ARGUMENTS = 8
    const val MAX_TOOLS = 16
    const val MAX_ALLOWLIST_PACKAGES = 32
    const val MAX_MODEL_ROUNDS = 24
    const val MAX_ACTIONS = 48
    const val MAX_ACTIONS_PER_RESPONSE = 1
    const val MAX_CONTINUATION_CHARS = 65_536
    const val MAX_TRANSCRIPT_CHARS = 262_144
    const val MAX_SNAPSHOT_AGE_MS = 60_000L
    const val MAX_TOOL_RESULT_CHARS = 2_048
    const val MAX_EVENT_HISTORY = 128
    const val MAX_WALL_TIME_MS = 5 * 60 * 1_000L
}
