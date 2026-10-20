package dev.zeroinput.ime.ai.device

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import dev.zeroinput.ai.api.DeviceApprovalMode
import dev.zeroinput.ai.api.DeviceActionCategory
import dev.zeroinput.ai.api.DeviceCapabilityScope
import dev.zeroinput.ai.api.DeviceStage6Capability
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication

/** Explicit disclosure and policy controls for the operation-capable service. */
class DeviceAgentSettingsActivity : AppCompatActivity() {
    private val graph get() = (application as ZeroInputApplication).graph
    private lateinit var enabled: MaterialSwitch
    private lateinit var disclosure: MaterialSwitch
    private lateinit var stage6Disclosure: MaterialSwitch
    private lateinit var screenshots: MaterialSwitch
    private lateinit var scope: Spinner
    private lateinit var approval: Spinner
    private lateinit var allowlist: EditText
    private lateinit var status: TextView
    private val categoryChecks = linkedMapOf<DeviceActionCategory, MaterialCheckBox>()
    private val stage6Checks = linkedMapOf<DeviceStage6Capability, MaterialCheckBox>()
    private val stage6PermissionButtons = linkedMapOf<Stage6RuntimePermission, MaterialButton>()
    private val stage6RuntimePermissions = listOf(
        Stage6RuntimePermission(DeviceStage6Capability.CAMERA, R.string.ai_device_agent_stage6_capture, android.Manifest.permission.CAMERA),
        Stage6RuntimePermission(DeviceStage6Capability.MICROPHONE, R.string.ai_device_agent_stage6_capture, android.Manifest.permission.RECORD_AUDIO),
        Stage6RuntimePermission(DeviceStage6Capability.SMS, R.string.ai_device_agent_stage6_read, android.Manifest.permission.READ_SMS),
        Stage6RuntimePermission(DeviceStage6Capability.SMS, R.string.ai_device_agent_stage6_send, android.Manifest.permission.SEND_SMS),
        Stage6RuntimePermission(DeviceStage6Capability.TELEPHONY, R.string.ai_device_agent_stage6_read, android.Manifest.permission.READ_CALL_LOG),
        Stage6RuntimePermission(DeviceStage6Capability.TELEPHONY, R.string.ai_device_agent_stage6_send, android.Manifest.permission.CALL_PHONE),
        Stage6RuntimePermission(DeviceStage6Capability.CONTACTS, R.string.ai_device_agent_stage6_read, android.Manifest.permission.READ_CONTACTS),
        Stage6RuntimePermission(DeviceStage6Capability.CONTACTS, R.string.ai_device_agent_stage6_update, android.Manifest.permission.WRITE_CONTACTS),
        Stage6RuntimePermission(DeviceStage6Capability.CALENDAR, R.string.ai_device_agent_stage6_read, android.Manifest.permission.READ_CALENDAR),
        Stage6RuntimePermission(DeviceStage6Capability.CALENDAR, R.string.ai_device_agent_stage6_update, android.Manifest.permission.WRITE_CALENDAR),
    )
    private var rendering = false
    private val runtimePermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        status.setText(if (granted) R.string.ai_device_agent_stage6_permission_granted
            else R.string.ai_device_agent_stage6_permission_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(32))
        }
        content.addView(TextView(this).apply { setText(R.string.ai_device_agent_title); textSize = 24f })
        content.addView(TextView(this).apply {
            setText(R.string.ai_device_agent_description)
            textSize = 16f
            setPadding(0, dp(8), 0, dp(16))
        })
        enabled = MaterialSwitch(this).apply {
            setText(R.string.ai_device_agent_enable)
            minHeight = dp(52)
            setOnCheckedChangeListener { _, checked ->
                if (!rendering) {
                    graph.settings.deviceAgentEnabled = checked
                    if (checked && graph.settings.deviceAgentScope == DeviceCapabilityScope.DISABLED) {
                        graph.settings.deviceAgentScope = DeviceCapabilityScope.FULL_SUPPORT
                    } else if (!checked) {
                        graph.settings.deviceAgentScope = DeviceCapabilityScope.DISABLED
                        graph.settings.deviceAgentAllowScreenshots = false
                        graph.settings.deviceAgentStage6Capabilities = emptySet()
                    }
                    render()
                }
            }
        }
        content.addView(enabled)
        disclosure = MaterialSwitch(this).apply {
            setText(R.string.ai_device_agent_disclosure)
            minHeight = dp(64)
            setOnCheckedChangeListener { _, checked ->
                if (!rendering) {
                    graph.settings.deviceAgentDisclosureAccepted = checked
                    if (!checked) {
                        graph.settings.deviceAgentEnabled = false
                        graph.settings.deviceAgentScope = DeviceCapabilityScope.DISABLED
                        graph.settings.deviceAgentAllowScreenshots = false
                    } else if (graph.settings.deviceAgentScope == DeviceCapabilityScope.DISABLED) {
                        graph.settings.deviceAgentScope = DeviceCapabilityScope.FULL_SUPPORT
                    }
                    render()
                }
            }
        }
        content.addView(disclosure)
        status = TextView(this).apply { textSize = 14f; setPadding(0, 0, 0, dp(8)) }
        content.addView(status)
        content.addView(TextView(this).apply {
            setText(R.string.ai_device_agent_scope)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(2))
        })
        scope = spinner(
            listOf(
                getString(R.string.ai_device_agent_scope_disabled),
                getString(R.string.ai_device_agent_scope_allowlist),
                getString(R.string.ai_device_agent_scope_full),
            ), dp(52), content, isScope = true,
        )
        content.addView(TextView(this).apply {
            setText(R.string.ai_device_agent_allowlist)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(2))
        })
        allowlist = EditText(this).apply {
            hint = getString(R.string.ai_device_agent_allowlist_hint)
            minHeight = dp(52)
            setSingleLine(false)
            maxLines = 3
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        content.addView(allowlist)
        content.addView(MaterialButton(this).apply {
            setText(R.string.ai_device_agent_allowlist_save)
            setOnClickListener { saveAllowlist() }
        })
        content.addView(TextView(this).apply {
            setText(R.string.ai_device_agent_approval)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(2))
        })
        approval = spinner(
            listOf(
                getString(R.string.ai_device_agent_each_action),
                getString(R.string.ai_device_agent_task_approval),
                getString(R.string.ai_device_agent_safe_auto),
            ), dp(52), content, isScope = false,
        )
        content.addView(TextView(this).apply {
            setText(R.string.ai_device_agent_categories)
            textSize = 14f
            setPadding(0, dp(12), 0, dp(2))
        })
        DeviceActionCategory.entries.forEach { category ->
            val check = MaterialCheckBox(this).apply {
                text = categoryLabel(category)
                minHeight = dp(48)
                setOnCheckedChangeListener { _, checked ->
                    if (!rendering && category != DeviceActionCategory.OBSERVE) {
                        val selected = graph.settings.deviceAgentAllowedCategories.toMutableSet()
                        if (checked) selected += category else selected -= category
                        selected += DeviceActionCategory.OBSERVE
                        graph.settings.deviceAgentAllowedCategories = selected
                        render()
                    }
                }
            }
            categoryChecks[category] = check
            content.addView(check)
        }
        screenshots = MaterialSwitch(this).apply {
            setText(R.string.ai_device_agent_screenshots)
            minHeight = dp(52)
            setOnCheckedChangeListener { _, checked -> if (!rendering) graph.settings.deviceAgentAllowScreenshots = checked }
        }
        content.addView(screenshots)
        content.addView(TextView(this).apply { setText(R.string.ai_device_agent_screenshots_help); textSize = 13f })
        content.addView(TextView(this).apply {
            setText(R.string.ai_device_agent_stage6_title)
            textSize = 18f
            setPadding(0, dp(20), 0, dp(4))
        })
        content.addView(TextView(this).apply {
            setText(R.string.ai_device_agent_stage6_description)
            textSize = 14f
            setPadding(0, 0, 0, dp(6))
        })
        stage6Disclosure = MaterialSwitch(this).apply {
            setText(R.string.ai_device_agent_stage6_disclosure)
            minHeight = dp(64)
            setOnCheckedChangeListener { _, checked ->
                if (!rendering) {
                    graph.settings.deviceAgentStage6DisclosureAccepted = checked
                    if (!checked) graph.settings.deviceAgentStage6Capabilities = emptySet()
                    render()
                }
            }
        }
        content.addView(stage6Disclosure)
        DeviceStage6Capability.entries.forEach { capability ->
            val check = MaterialCheckBox(this).apply {
                text = stage6Label(capability)
                minHeight = dp(48)
                setOnCheckedChangeListener { _, checked ->
                    if (!rendering) {
                        val selected = graph.settings.deviceAgentStage6Capabilities.toMutableSet()
                        if (checked) selected += capability else selected -= capability
                        graph.settings.deviceAgentStage6Capabilities = selected
                        render()
                    }
                }
            }
            stage6Checks[capability] = check
            content.addView(check)
        }
        content.addView(MaterialButton(this).apply {
            setText(R.string.ai_device_agent_stage6_notification_access)
            setOnClickListener { openNotificationAccessSettings() }
        })
        stage6RuntimePermissions.forEach { request ->
            val button = MaterialButton(this).apply {
                text = getString(
                    R.string.ai_device_agent_stage6_request_permission,
                    stage6Label(request.capability),
                    getString(request.operationLabel),
                )
                setOnClickListener { requestStage6Permission(request) }
            }
            stage6PermissionButtons[request] = button
            content.addView(button)
        }
        content.addView(MaterialButton(this).apply {
            setText(R.string.ai_device_agent_stage6_system_settings_access)
            setOnClickListener { openSystemWriteSettings() }
        })
        content.addView(MaterialButton(this).apply {
            setText(R.string.ai_device_agent_authorize)
            setOnClickListener { explainAndOpenSettings() }
        })
        content.addView(MaterialButton(this).apply {
            setText(R.string.ai_device_agent_open_activity)
            setOnClickListener { startActivity(Intent(this@DeviceAgentSettingsActivity, AiDeviceAgentActivity::class.java)) }
        })
        content.addView(MaterialButton(this).apply {
            setText(R.string.cancel)
            setOnClickListener { finish() }
        })
        setContentView(ScrollView(this).apply {
            addView(content)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
        render()
    }

    override fun onResume() { super.onResume(); if (::status.isInitialized) render() }

    private fun spinner(labels: List<String>, height: Int, parent: LinearLayout, isScope: Boolean): Spinner = Spinner(this).apply {
        adapter = ArrayAdapter(this@DeviceAgentSettingsActivity, android.R.layout.simple_spinner_dropdown_item, labels)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)
        onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (rendering) return
                if (isScope) {
                    val selected = DeviceCapabilityScope.entries[position]
                    graph.settings.deviceAgentScope = selected
                    if (selected == DeviceCapabilityScope.DISABLED) {
                        graph.settings.deviceAgentEnabled = false
                        graph.settings.deviceAgentAllowScreenshots = false
                    }
                    render()
                }
                else graph.settings.deviceAgentApprovalMode = DeviceApprovalMode.entries[position]
            }
        }
        parent.addView(this)
    }

    private fun render() {
        rendering = true
        enabled.isChecked = graph.settings.deviceAgentEnabled
        disclosure.isChecked = graph.settings.deviceAgentDisclosureAccepted
        stage6Disclosure.isChecked = graph.settings.deviceAgentStage6DisclosureAccepted
        screenshots.isChecked = graph.settings.deviceAgentAllowScreenshots
        scope.setSelection(graph.settings.deviceAgentScope.ordinal)
        approval.setSelection(graph.settings.deviceAgentApprovalMode.ordinal)
        val categories = graph.settings.deviceAgentAllowedCategories
        categoryChecks.forEach { (category, check) ->
            check.isChecked = category in categories || category == DeviceActionCategory.OBSERVE
            check.isEnabled = category != DeviceActionCategory.OBSERVE && graph.settings.deviceAgentEnabled &&
                graph.settings.deviceAgentDisclosureAccepted
        }
        val stage6Capabilities = graph.settings.deviceAgentStage6Capabilities
        stage6Checks.forEach { (capability, check) ->
            check.isChecked = capability in stage6Capabilities
            check.isEnabled = graph.settings.deviceAgentEnabled && graph.settings.deviceAgentDisclosureAccepted &&
                graph.settings.deviceAgentStage6DisclosureAccepted
        }
        stage6PermissionButtons.forEach { (capability, button) ->
            button.isEnabled = capability.capability in stage6Capabilities && stage6Ready(capability.capability)
        }
        if (!allowlist.hasFocus()) allowlist.setText(graph.settings.deviceAgentAllowedPackages.sorted().joinToString("\n"))
        enabled.isEnabled = graph.settings.deviceAgentDisclosureAccepted
        scope.isEnabled = graph.settings.deviceAgentEnabled && graph.settings.deviceAgentDisclosureAccepted
        allowlist.isEnabled = scope.isEnabled && graph.settings.deviceAgentScope == DeviceCapabilityScope.ALLOWLIST
        allowlist.visibility = if (graph.settings.deviceAgentScope == DeviceCapabilityScope.ALLOWLIST) android.view.View.VISIBLE else android.view.View.GONE
        approval.isEnabled = scope.isEnabled
        screenshots.isEnabled = scope.isEnabled
        stage6Disclosure.isEnabled = graph.settings.deviceAgentEnabled && graph.settings.deviceAgentDisclosureAccepted
        status.setText(
            if (graph.deviceAgentBroker.connected) R.string.ai_device_agent_authorized
            else R.string.ai_device_agent_not_authorized,
        )
        rendering = false
    }

    private fun categoryLabel(category: DeviceActionCategory): String = when (category) {
        DeviceActionCategory.OBSERVE -> getString(R.string.ai_device_agent_category_observe)
        DeviceActionCategory.NAVIGATION -> getString(R.string.ai_device_agent_category_navigation)
        DeviceActionCategory.POINTER -> getString(R.string.ai_device_agent_category_pointer)
        DeviceActionCategory.TEXT_INPUT -> getString(R.string.ai_device_agent_category_text)
    }

    private fun stage6Label(capability: DeviceStage6Capability): String = when (capability) {
        DeviceStage6Capability.NOTIFICATIONS -> getString(R.string.ai_device_agent_stage6_notifications)
        DeviceStage6Capability.MEDIA -> getString(R.string.ai_device_agent_stage6_media)
        DeviceStage6Capability.SYSTEM_SETTINGS -> getString(R.string.ai_device_agent_stage6_system_settings)
        DeviceStage6Capability.CAMERA -> getString(R.string.ai_device_agent_stage6_camera)
        DeviceStage6Capability.MICROPHONE -> getString(R.string.ai_device_agent_stage6_microphone)
        DeviceStage6Capability.FILES -> getString(R.string.ai_device_agent_stage6_files)
        DeviceStage6Capability.SMS -> getString(R.string.ai_device_agent_stage6_sms)
        DeviceStage6Capability.TELEPHONY -> getString(R.string.ai_device_agent_stage6_telephony)
        DeviceStage6Capability.CONTACTS -> getString(R.string.ai_device_agent_stage6_contacts)
        DeviceStage6Capability.CALENDAR -> getString(R.string.ai_device_agent_stage6_calendar)
    }

    private fun saveAllowlist() {
        val packages = allowlist.text.toString().split(',', '\n', '\r', ' ', '\t')
            .map(String::trim).filter(String::isNotEmpty).toSet()
        val valid = packages.size <= dev.zeroinput.ai.api.DeviceAgentLimits.MAX_ALLOWLIST_PACKAGES &&
            packages.all { it.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.]{0,254}")) }
        if (!valid) {
            allowlist.error = getString(R.string.ai_device_agent_allowlist_invalid)
            return
        }
        graph.settings.deviceAgentAllowedPackages = packages
        allowlist.error = null
        render()
    }

    private fun explainAndOpenSettings() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.ai_device_agent_title)
            .setMessage(R.string.ai_device_agent_description)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ai_device_agent_authorize) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.show()
    }

    private fun openNotificationAccessSettings() {
        if (!stage6Ready(DeviceStage6Capability.NOTIFICATIONS)) return
        openSystemPage(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

    private fun requestStage6Permission(request: Stage6RuntimePermission) {
        if (!stage6Ready(request.capability)) return
        if (ContextCompat.checkSelfPermission(this, request.permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            status.setText(R.string.ai_device_agent_stage6_permission_granted)
            return
        }
        runtimePermissionRequest.launch(request.permission)
    }

    private fun openSystemWriteSettings() {
        if (!stage6Ready(DeviceStage6Capability.SYSTEM_SETTINGS)) return
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:$packageName".toUri())
        runCatching { startActivity(intent) }
    }

    private fun stage6Ready(capability: DeviceStage6Capability): Boolean =
        graph.deviceAgentPolicySnapshot().permitsStage6(capability)

    private fun openSystemPage(action: String) {
        runCatching { startActivity(Intent(action)) }
    }

    private data class Stage6RuntimePermission(
        val capability: DeviceStage6Capability,
        val operationLabel: Int,
        val permission: String,
    )
}
