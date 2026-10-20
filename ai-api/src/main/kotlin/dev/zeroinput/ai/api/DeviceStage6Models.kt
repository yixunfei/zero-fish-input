package dev.zeroinput.ai.api

import java.util.Collections

/** High-risk data domains kept outside the accessibility action catalogue. */
enum class DeviceStage6Capability {
    NOTIFICATIONS,
    MEDIA,
    SYSTEM_SETTINGS,
    CAMERA,
    MICROPHONE,
    FILES,
    SMS,
    TELEPHONY,
    CONTACTS,
    CALENDAR,
}

enum class DeviceStage6Operation {
    READ,
    CREATE,
    UPDATE,
    DELETE,
    SEND,
    CAPTURE,
    MODIFY,
}

/** An opaque, task-local target. The identifier is never sent to the model. */
data class DeviceStage6Target(
    val capability: DeviceStage6Capability,
    val id: String,
    val packageName: String? = null,
) {
    init {
        require(id.length in 1..DeviceAgentLimits.MAX_STAGE6_TARGET_CHARS &&
            id.none { it.isISOControl() }) { "Invalid stage 6 target" }
        packageName?.let {
            require(it.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.]{0,254}"))) { "Invalid stage 6 package" }
        }
    }

    override fun toString(): String = "DeviceStage6Target(capability=$capability,redacted)"
}

/** A grant is deliberately scoped to one task and one set of explicit targets. */
class DeviceStage6Grant(
    val taskId: String,
    val generation: Long,
    val taskTargetPackage: String,
    val capability: DeviceStage6Capability,
    operations: Set<DeviceStage6Operation>,
    targets: Set<DeviceStage6Target>,
    /** Monotonic deadline; wall-clock changes cannot extend a task grant. */
    val expiresAtElapsedMillis: Long,
) {
    val operations: Set<DeviceStage6Operation> = Collections.unmodifiableSet(operations.toSet())
    val targets: Set<DeviceStage6Target> = Collections.unmodifiableSet(targets.toSet())

    init {
        require(taskId.length in 1..DeviceAgentLimits.MAX_ID_CHARS && taskId.none(Char::isISOControl)) {
            "Invalid stage 6 grant task"
        }
        require(generation > 0) { "Invalid stage 6 grant generation" }
        require(taskTargetPackage.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.]{0,254}"))) {
            "Invalid stage 6 grant task target"
        }
        require(operations.isNotEmpty()) { "Stage 6 grant has no operations" }
        require(targets.isNotEmpty() && targets.size <= DeviceAgentLimits.MAX_STAGE6_TARGETS) {
            "Stage 6 grant has invalid targets"
        }
        require(targets.all { it.capability == capability }) { "Stage 6 target capability mismatch" }
        require(expiresAtElapsedMillis > 0) { "Stage 6 grant expiry is invalid" }
    }

    fun permits(operation: DeviceStage6Operation, target: DeviceStage6Target, now: Long): Boolean =
        now in 1 until expiresAtElapsedMillis && operation in operations && target in targets

    override fun toString(): String = "DeviceStage6Grant(capability=$capability,operations=$operations,targets=${targets.size},redacted)"
}

class DeviceStage6OperationRequest(
    val taskId: String,
    val generation: Long,
    val taskTargetPackage: String,
    val capability: DeviceStage6Capability,
    val operation: DeviceStage6Operation,
    val target: DeviceStage6Target,
    fields: Map<String, String> = emptyMap(),
) {
    val fields: Map<String, String> = Collections.unmodifiableMap(fields.toMap())

    init {
        require(taskId.length in 1..DeviceAgentLimits.MAX_ID_CHARS && taskId.none(Char::isISOControl)) {
            "Invalid stage 6 task"
        }
        require(generation > 0) { "Invalid stage 6 generation" }
        require(taskTargetPackage.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.]{0,254}"))) {
            "Invalid stage 6 task target"
        }
        require(target.capability == capability) { "Stage 6 operation target mismatch" }
        require(fields.size <= DeviceAgentLimits.MAX_STAGE6_FIELDS) { "Too many stage 6 fields" }
        fields.forEach { (key, value) ->
            require(key.matches(Regex("[a-z][a-z0-9_]{0,31}"))) { "Invalid stage 6 field" }
            require(value.length <= DeviceAgentLimits.MAX_STAGE6_FIELD_CHARS &&
                value.none { it.isISOControl() && it !in "\n\r\t" }) { "Invalid stage 6 field value" }
        }
    }

    override fun toString(): String = "DeviceStage6OperationRequest(capability=$capability,operation=$operation,redacted)"
}

enum class DeviceStage6ResultStatus { SUCCESS, REJECTED, EXPIRED, UNAVAILABLE, FAILED }

data class DeviceStage6Result(
    val status: DeviceStage6ResultStatus,
    val summary: String,
    val attachment: AiAttachment? = null,
) {
    init {
        require(summary.isNotBlank() && summary.length <= DeviceAgentLimits.MAX_TOOL_RESULT_CHARS) {
            "Invalid stage 6 result"
        }
        require(summary.none { it.isISOControl() && it !in "\n\r\t" }) { "Invalid stage 6 result" }
    }

    override fun toString(): String =
        "DeviceStage6Result(status=$status,hasAttachment=${attachment != null},redacted)"
}

object DeviceStage6Catalog {
    val operations: Map<DeviceStage6Capability, Set<DeviceStage6Operation>> = mapOf(
        DeviceStage6Capability.NOTIFICATIONS to setOf(DeviceStage6Operation.READ),
        DeviceStage6Capability.MEDIA to setOf(DeviceStage6Operation.READ, DeviceStage6Operation.DELETE),
        DeviceStage6Capability.SYSTEM_SETTINGS to setOf(DeviceStage6Operation.READ, DeviceStage6Operation.MODIFY),
        DeviceStage6Capability.CAMERA to setOf(DeviceStage6Operation.CAPTURE),
        DeviceStage6Capability.MICROPHONE to setOf(DeviceStage6Operation.CAPTURE),
        DeviceStage6Capability.FILES to setOf(DeviceStage6Operation.READ, DeviceStage6Operation.DELETE),
        DeviceStage6Capability.SMS to setOf(DeviceStage6Operation.READ, DeviceStage6Operation.SEND),
        DeviceStage6Capability.TELEPHONY to setOf(DeviceStage6Operation.READ, DeviceStage6Operation.SEND),
        DeviceStage6Capability.CONTACTS to setOf(DeviceStage6Operation.READ, DeviceStage6Operation.CREATE, DeviceStage6Operation.UPDATE, DeviceStage6Operation.DELETE),
        DeviceStage6Capability.CALENDAR to setOf(DeviceStage6Operation.READ, DeviceStage6Operation.CREATE, DeviceStage6Operation.UPDATE, DeviceStage6Operation.DELETE),
    ).mapValues { Collections.unmodifiableSet(it.value) }

    fun permits(capability: DeviceStage6Capability, operation: DeviceStage6Operation): Boolean =
        operation in operations[capability].orEmpty()

    fun availableCapabilities(selected: Set<DeviceStage6Capability>): Set<DeviceStage6Capability> =
        selected.filterTo(linkedSetOf()) { operations[it].orEmpty().isNotEmpty() }

    fun describe(selected: Set<DeviceStage6Capability>): String =
        availableCapabilities(selected).sortedBy { it.name }.joinToString("; ") { capability ->
            "${capability.name}: ${operations.getValue(capability).sortedBy { it.name }.joinToString()}"
        }
}

