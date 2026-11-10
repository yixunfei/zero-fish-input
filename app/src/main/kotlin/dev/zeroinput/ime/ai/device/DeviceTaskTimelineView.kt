package dev.zeroinput.ime.ai.device

import android.content.Context
import androidx.core.view.isGone
import androidx.core.view.isNotEmpty
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import dev.zeroinput.ai.api.*
import dev.zeroinput.ime.R

/** Bounded presentation only. No tool arguments are reconstructed or executed by a row. */
internal class DeviceTaskTimelineView(context: Context) : LinearLayout(context) {
    private val descriptions = DeviceAgentDescriptions(context)
    private var lastState: DeviceAgentState? = null
    init { orientation = VERTICAL; isSaveEnabled = false }

    fun clear() { removeAllViews(); lastState = null }

    fun append(event: DeviceAgentEvent) {
        if (event is DeviceAgentEvent.State && event.state == lastState) return
        val repeatsOutcome = event is DeviceAgentEvent.Completed && lastState == DeviceAgentState.COMPLETED ||
            event is DeviceAgentEvent.Failed && lastState == DeviceAgentState.FAILED
        if (repeatsOutcome && isNotEmpty()) removeViewAt(childCount - 1)
        lastState = when (event) {
            is DeviceAgentEvent.State -> event.state
            is DeviceAgentEvent.Completed -> DeviceAgentState.COMPLETED
            is DeviceAgentEvent.Failed -> DeviceAgentState.FAILED
            else -> null
        }
        val title = when (event) {
            is DeviceAgentEvent.State -> descriptions.status(event.state)
            is DeviceAgentEvent.ApprovalRequired -> descriptions.actionTitle
            is DeviceAgentEvent.TaskApprovalRequired -> descriptions.taskTitle
            is DeviceAgentEvent.ActionFinished -> context.getString(resultLabel(event.result.status))
            is DeviceAgentEvent.Completed -> descriptions.completed
            is DeviceAgentEvent.Failed -> event.message
        }
        val detail = when (event) {
            is DeviceAgentEvent.State -> ""
            is DeviceAgentEvent.ApprovalRequired -> event.description
            is DeviceAgentEvent.TaskApprovalRequired -> event.description
            is DeviceAgentEvent.ActionFinished -> event.result.summary
            is DeviceAgentEvent.Completed -> event.message
            is DeviceAgentEvent.Failed -> context.getString(R.string.ai_task_failure_hint)
        }.take(DeviceAgentLimits.MAX_TOOL_RESULT_CHARS)
        val row = LinearLayout(context).apply { orientation = VERTICAL; isSaveEnabled = false }
        val details = TextView(context).apply {
            text = detail; textSize = 14f; visibility = View.GONE; isSaveEnabled = false
        }
        row.addView(MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = title; isAllCaps = false; minHeight = (48 * resources.displayMetrics.density).toInt()
            isSaveEnabled = false
            contentDescription = context.getString(R.string.ai_task_details, title)
            setOnClickListener { details.isGone = !details.isGone }
        })
        if (detail.isNotBlank()) row.addView(details)
        addView(row)
        while (childCount > DeviceAgentLimits.MAX_EVENT_HISTORY) removeViewAt(0)
    }

    companion object {
        fun resultLabel(status: DeviceActionResultStatus): Int = when (status) {
            DeviceActionResultStatus.SUCCESS -> R.string.ai_device_agent_result_success
            DeviceActionResultStatus.REJECTED -> R.string.ai_device_agent_result_rejected
            DeviceActionResultStatus.EXPIRED -> R.string.ai_task_expired
            DeviceActionResultStatus.FAILED -> R.string.ai_task_failed
        }
    }
}
