package dev.zeroinput.ime.ai.device

import dev.zeroinput.ai.api.DeviceActionResult
import dev.zeroinput.ai.api.DeviceScreenSnapshot

/** Keeps network prompts and UI summaries bounded without retaining raw platform objects. */
internal object DeviceAgentRedactor {
    fun snapshotSummary(snapshot: DeviceScreenSnapshot): String = buildString {
        append("package=").append(snapshot.packageName)
        append(" window=").append(snapshot.windowId)
        append(" snapshot=").append(snapshot.snapshotId)
        append(" incomplete=").append(snapshot.incomplete)
        append(" elements=").append(snapshot.elements.size)
        snapshot.elements.forEach { element ->
            append("\n[").append(element.ref).append("] ")
            append(element.role)
            if (!element.label.isNullOrBlank()) append(" label=").append(element.label)
            if (!element.text.isNullOrBlank() && !element.sensitive && !element.password) {
                append(" text=").append(element.text)
            }
            append(" bounds=").append(element.bounds)
            append(" clickable=").append(element.clickable)
            append(" scrollable=").append(element.scrollable)
            append(" editable=").append(element.editable)
        }
    }

    fun resultSummary(result: DeviceActionResult): String =
        "call=${result.callId};status=${result.status};summary=${result.summary.take(256)}"
}
