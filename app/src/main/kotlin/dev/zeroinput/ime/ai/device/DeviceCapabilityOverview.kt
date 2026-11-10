package dev.zeroinput.ime.ai.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zeroinput.ai.api.*
import dev.zeroinput.ime.R

/** A read-only projection of current gates; viewing this list never requests a grant. */
internal object DeviceCapabilityOverview {
    enum class Availability { DISABLED, PERMISSION_REQUIRED, UNSUPPORTED, READY }

    fun availability(enabled: Boolean, supported: Boolean, permission: Boolean): Availability = when {
        !supported -> Availability.UNSUPPORTED
        !enabled -> Availability.DISABLED
        !permission -> Availability.PERMISSION_REQUIRED
        else -> Availability.READY
    }

    fun show(context: Context, policy: DeviceAgentPolicy, connected: Boolean): AlertDialog {
        val text = DeviceAgentDescriptions(context)
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (android.os.Build.VERSION.SDK_INT >= 30) importantForContentCapture = android.view.View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, pad)
        }
        fun row(title: String, state: Availability? = null) {
            rows.addView(TextView(context).apply {
                this.text = if (state == null) title else context.getString(R.string.ai_capability_row, title, context.getString(label(state)))
                textSize = if (state == null) 16f else 14f
                setPadding(0, 12, 0, 12)
                isSaveEnabled = false
            })
        }
        row(context.getString(R.string.ai_capability_notice))
        DeviceActionCatalog.definitions.groupBy { it.category }.forEach { (category, tools) ->
            row(text.category(category))
            tools.forEach { tool ->
                row(text.actionName(tool.name), availability(policy.enabled && policy.disclosureAccepted &&
                    policy.scope != DeviceCapabilityScope.DISABLED && category in policy.allowedCategories, true, connected))
            }
        }
        DeviceStage6ToolCatalog.entries.groupBy { it.capability }.forEach { (capability, entries) ->
            row(text.stage6Capability(capability))
            entries.forEach { entry ->
                row(text.stage6Operation(entry.operation), availability(policy.permitsStage6(capability),
                    supported(context, capability), connected && permitted(context, capability, entry.operation)))
            }
        }
        return MaterialAlertDialogBuilder(context).setTitle(R.string.ai_capabilities)
            .setView(ScrollView(context).apply { addView(rows) })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ai_device_agent_activity_open_settings) { _, _ ->
                context.startActivity(Intent(context, DeviceAgentSettingsActivity::class.java))
            }.create().also { it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE); it.show() }
    }

    private fun label(state: Availability) = when (state) {
        Availability.DISABLED -> R.string.ai_capability_disabled
        Availability.PERMISSION_REQUIRED -> R.string.ai_capability_permission
        Availability.UNSUPPORTED -> R.string.ai_capability_unsupported
        Availability.READY -> R.string.ai_capability_ready
    }

    private fun supported(context: Context, capability: DeviceStage6Capability): Boolean {
        val feature = when (capability) {
            DeviceStage6Capability.CAMERA -> PackageManager.FEATURE_CAMERA_ANY
            DeviceStage6Capability.MICROPHONE -> PackageManager.FEATURE_MICROPHONE
            DeviceStage6Capability.SMS, DeviceStage6Capability.TELEPHONY -> PackageManager.FEATURE_TELEPHONY
            else -> return true
        }
        return context.packageManager.hasSystemFeature(feature)
    }

    private fun permitted(context: Context, capability: DeviceStage6Capability, operation: DeviceStage6Operation): Boolean {
        val read = operation == DeviceStage6Operation.READ
        val permission = when (capability) {
            DeviceStage6Capability.CAMERA -> Manifest.permission.CAMERA
            DeviceStage6Capability.MICROPHONE -> Manifest.permission.RECORD_AUDIO
            DeviceStage6Capability.SMS -> if (read) Manifest.permission.READ_SMS else Manifest.permission.SEND_SMS
            DeviceStage6Capability.TELEPHONY -> if (read) Manifest.permission.READ_CALL_LOG else Manifest.permission.CALL_PHONE
            DeviceStage6Capability.CONTACTS -> if (read) Manifest.permission.READ_CONTACTS else Manifest.permission.WRITE_CONTACTS
            DeviceStage6Capability.CALENDAR -> if (read) Manifest.permission.READ_CALENDAR else Manifest.permission.WRITE_CALENDAR
            DeviceStage6Capability.NOTIFICATIONS -> return DeviceStage6NotificationListenerService.connected
            DeviceStage6Capability.SYSTEM_SETTINGS -> return read || Settings.System.canWrite(context)
            DeviceStage6Capability.FILES, DeviceStage6Capability.MEDIA -> return true
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
