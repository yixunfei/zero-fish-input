package dev.zeroinput.ime.ai.device

import dev.zeroinput.ime.ai.takeWholeCodePoints

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import androidx.core.view.isGone
import androidx.core.widget.doAfterTextChanged
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zeroinput.ai.api.DeviceAgentEvent
import dev.zeroinput.ai.api.DeviceAgentLimits
import dev.zeroinput.ai.api.AiRequestHandle
import dev.zeroinput.ai.api.DeviceStage6Capability
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import java.lang.ref.WeakReference
import java.util.concurrent.RejectedExecutionException

/**
 * Standalone task surface. The activity only owns user input and rendering;
 * the process coordinator owns the task and survives configuration changes.
 */
class AiDeviceAgentActivity : AppCompatActivity() {
    private val graph get() = (application as ZeroInputApplication).graph
    private lateinit var targetPackage: EditText
    private lateinit var task: EditText
    private lateinit var screenshots: MaterialSwitch
    private lateinit var attachmentButton: MaterialButton
    private lateinit var removeAttachmentButton: MaterialButton
    private lateinit var attachmentStatus: TextView
    private lateinit var start: MaterialButton
    private lateinit var stop: MaterialButton
    private lateinit var status: TextView
    private lateinit var readiness: TextView
    private lateinit var timeline: DeviceTaskTimelineView
    private lateinit var dock: LinearLayout
    private lateinit var examples: MaterialButton
    private var capabilityDialog: androidx.appcompat.app.AlertDialog? = null
    private var observer: AutoCloseable? = null
    private var settingsObserver: AutoCloseable? = null
    private var configurationObserver: AutoCloseable? = null
    private var appDialog: androidx.appcompat.app.AlertDialog? = null
    private lateinit var chooseApp: MaterialButton
    private var rendering = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingStart: PendingStart? = null
    private var taskHandle: AiRequestHandle? = null
    private var launchingTarget = false
    private var leftForTarget = false
    private var selectedDocument: SelectedDocument? = null
    private var readSignal: CancellationSignal? = null
    private var dataGeneration = 0L
    private val pendingStarter = Runnable { tryStartPending() }
    private val attachmentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || isFinishing || isDestroyed) return@registerForActivityResult
        if (!graph.deviceAgentPolicySnapshot().let { it.permitsStage6(DeviceStage6Capability.FILES) || it.permitsStage6(DeviceStage6Capability.MEDIA) }) {
            attachmentStatus.setText(R.string.ai_device_agent_activity_file_disabled)
            return@registerForActivityResult
        }
        val generation = dataGeneration
        val signal = CancellationSignal().also { readSignal = it }
        val resolver = applicationContext.contentResolver
        val dispatcher = mainHandler
        attachmentButton.isEnabled = false
        renderReadyState()
        val owner = WeakReference(this)
        try {
            graph.aiDocumentExecutor.execute {
                val value = runCatching { Companion.inspectSelectedDocument(resolver, uri, signal) }.getOrNull()
                if (!dispatcher.post {
                    val activity = owner.get()
                    if (activity == null || activity.dataGeneration != generation || activity.isFinishing || activity.isDestroyed) {
                        Unit
                    } else {
                        activity.readSignal = null
                        activity.selectedDocument = value
                        activity.removeAttachmentButton.visibility = if (value == null) View.GONE else View.VISIBLE
                        activity.attachmentStatus.setText(
                            if (value == null) R.string.ai_attachment_failed
                            else R.string.ai_device_agent_activity_file_selected,
                        )
                        activity.attachmentButton.isEnabled = true
                        activity.renderReadyState()
                    }
                }) Unit
            }
        } catch (_: RejectedExecutionException) {
            readSignal = null
            attachmentButton.isEnabled = true
            attachmentStatus.setText(R.string.ai_attachment_failed)
            renderReadyState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dataGeneration++
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        taskHandle = lastCustomNonConfigurationInstance as? AiRequestHandle
        buildScreen()
        observer = graph.deviceAgentCoordinator.observe { event ->
            runOnUiThread { if (!isDestroyed) renderEvent(event) }
        }
        settingsObserver = graph.settings.addChangeListener {
            runOnUiThread {
                cancelPending()
                appDialog?.dismiss()
                capabilityDialog?.dismiss()
                timeline.clear()
                cancelSelectionRead()
                if (!graph.deviceAgentPolicySnapshot().let { it.permitsStage6(DeviceStage6Capability.FILES) || it.permitsStage6(DeviceStage6Capability.MEDIA) }) clearSelectedAttachment()
                renderReadyState()
            }
        }
        configurationObserver = graph.observeAiConfiguration {
            runOnUiThread { cancelPending(); renderReadyState() }
        }
        renderReadyState()
    }

    private fun buildScreen() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(32))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
            }
        }
        content.addView(TextView(this).apply {
            setText(R.string.ai_device_agent_activity_title)
            textSize = 24f
        })
        content.addView(TextView(this).apply {
            setText(R.string.ai_device_agent_activity_description)
            textSize = 14f
            setPadding(0, dp(8), 0, dp(14))
        })

        targetPackage = EditText(this).apply {
            id = R.id.ai_device_target
            hint = getString(R.string.ai_device_agent_target_package_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(255))
            setSingleLine(true)
            isSaveEnabled = false
            setText(intent.getStringExtra(EXTRA_TARGET_PACKAGE)
                ?: graph.deviceAgentBroker.currentWindow()?.packageName.orEmpty())
        }
        targetPackage.visibility = View.GONE
        chooseApp = button(R.string.ai_device_choose_app) { chooseTargetApp() }
        if (targetPackage.text.isNotBlank()) chooseApp.text = targetPackage.text.toString()
        targetPackage.doAfterTextChanged { chooseApp.text = it?.toString()?.ifBlank { getString(R.string.ai_device_choose_app) } }
        content.addView(chooseApp)
        content.addView(button(R.string.ai_target_manual) {
            targetPackage.isGone = !targetPackage.isGone
        })
        content.addView(targetPackage)
        content.addView(TextView(this).apply {
            setText(R.string.ai_device_agent_activity_task)
            textSize = 14f
            setPadding(0, dp(12), 0, 0)
        })
        task = EditText(this).apply {
            id = R.id.ai_device_task
            hint = getString(R.string.ai_device_agent_task_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(DeviceAgentLimits.MAX_TASK_CHARS))
            minLines = 3
            maxLines = 7
            isSaveEnabled = false
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        content.addView(task)
        examples = button(R.string.ai_task_examples) { showExamples() }
        content.addView(examples)
        dock = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(12), 0) }
        buildTaskControls(content)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ScrollView(this@AiDeviceAgentActivity).apply { addView(content) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(dock)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
    }

    private fun buildTaskControls(content: LinearLayout) {
        val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        content.addView(button(R.string.ai_task_options) { options.isGone = !options.isGone })
        content.addView(options)
        screenshots = MaterialSwitch(this).apply {
            setText(R.string.ai_device_agent_activity_screenshot_confirm)
            minHeight = dp(56)
            isChecked = false
        }
        options.addView(screenshots)
        attachmentButton = button(R.string.ai_device_agent_activity_add_file) { chooseAttachment() }
        options.addView(attachmentButton)
        removeAttachmentButton = button(R.string.ai_device_agent_activity_file_removed) { clearSelectedAttachment() }
        removeAttachmentButton.visibility = View.GONE
        options.addView(removeAttachmentButton)
        attachmentStatus = TextView(this).apply { textSize = 13f; isSaveEnabled = false }
        options.addView(attachmentStatus)
        status = TextView(this).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; textSize = 14f; setPadding(0, dp(8), 0, dp(8)); isSaveEnabled = false }
        readiness = TextView(this).apply { textSize = 14f; isSaveEnabled = false }
        content.addView(readiness)
        content.addView(status)
        start = button(R.string.ai_device_agent_activity_start) { startTask() }.apply { id = R.id.ai_device_start }
        stop = button(R.string.ai_device_agent_activity_stop) {
            val waiting = pendingStart != null
            cancelPending()
            graph.deviceAgentCoordinator.stop()
            taskHandle = null
            if (waiting) status.setText(R.string.ai_device_stop_pending)
            renderReadyState()
        }.apply { id = R.id.ai_device_stop }
        dock.addView(start, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        dock.addView(stop, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(button(R.string.ai_capabilities) {
            capabilityDialog?.dismiss()
            capabilityDialog = DeviceCapabilityOverview.show(this, graph.deviceAgentPolicySnapshot(), graph.deviceAgentBroker.connected)
        })
        content.addView(button(R.string.ai_readiness_fix) {
            val config = graph.aiConfigurationSnapshot()
            if (config == null || !config.enabled || !config.networkAllowed || config.activeKey().isBlank()) {
                startActivity(Intent(this, dev.zeroinput.ime.settings.MainActivity::class.java)
                    .putExtra(dev.zeroinput.ime.settings.MainActivity.EXTRA_AI_SETTINGS, true))
            } else startActivity(Intent(this, DeviceAgentSettingsActivity::class.java))
        })
        content.addView(button(R.string.ai_device_agent_activity_open_settings) {
            startActivity(Intent(this, DeviceAgentSettingsActivity::class.java))
        })
        content.addView(button(R.string.ai_assistant_open) {
            startActivity(Intent(this, dev.zeroinput.ime.ai.AiAssistantActivity::class.java))
        })
        content.addView(TextView(this).apply {
            setText(R.string.ai_device_agent_activity_timeline)
            textSize = 16f
            setPadding(0, dp(18), 0, dp(8))
        })
        timeline = DeviceTaskTimelineView(this)
        content.addView(timeline)
    }

    private fun startTask() {
        if (!hasWindowFocus() || pendingStart != null || graph.deviceAgentCoordinator.hasActiveTask) return
        val config = graph.aiConfigurationSnapshot()
        if (config == null || !config.enabled || !config.networkAllowed || config.activeKey().isBlank()) {
            status.setText(R.string.ai_assistant_configuration_required)
            return
        }
        val packageName = targetPackage.text.toString().trim()
        val taskText = task.text.toString().trim()
        if (!packageName.matches(PACKAGE_PATTERN)) {
            targetPackage.isGone = false
            targetPackage.requestFocus()
            targetPackage.error = getString(R.string.ai_device_agent_activity_target_invalid)
            return
        }
        if (taskText.isBlank()) {
            task.error = getString(R.string.ai_device_agent_activity_task_required)
            return
        }
        if (!graph.deviceAgentPolicySnapshot().let { it.enabled && it.disclosureAccepted }) {
            status.setText(R.string.ai_device_agent_activity_disclosure_required)
            startActivity(Intent(this, DeviceAgentSettingsActivity::class.java))
            return
        }
        if (!graph.deviceAgentBroker.connected) {
            status.setText(R.string.ai_device_agent_not_authorized)
            return
        }
        val basePolicy = graph.deviceAgentPolicySnapshot()
        if (selectedDocument != null && selectedAttachmentCapability()?.let { !basePolicy.permitsStage6(it) } == true) {
            attachmentStatus.setText(R.string.ai_device_agent_activity_file_disabled)
            clearSelectedAttachment()
            return
        }
        val targetDecision = DeviceAgentPolicyEvaluator().checkTarget(basePolicy, packageName)
        if (!targetDecision.allowed || !graph.deviceAgentBroker.canStart(basePolicy, packageName)) {
            status.setText(R.string.ai_device_agent_activity_target_not_ready)
            return
        }
        timeline.clear()
        val request = PendingStart(packageName, taskText, basePolicy.allowScreenshots && screenshots.isChecked,
            DeviceAgentLaunchGuard(SystemClock.elapsedRealtime(), graph.aiDataGeneration.current(), config, basePolicy))
        screenshots.isChecked = false
        if (graph.deviceAgentBroker.currentWindow()?.packageName != packageName) {
            openTargetAndWait(request)
            return
        }
        launchTask(request)
    }

    private fun launchTask(start: PendingStart) {
        val policy = graph.deviceAgentPolicySnapshot()
        if (!start.guard.consume(SystemClock.elapsedRealtime(), graph.aiDataGeneration.current(), graph.aiConfigurationSnapshot(), policy)) {
            status.setText(R.string.ai_device_launch_expired)
            renderReadyState()
            return
        }
        val attachmentCapability = selectedAttachmentCapability()
        val attachmentUri = selectedDocument?.uri
        val attachmentLabel = selectedDocument?.displayName
        val attachmentTarget = attachmentCapability?.let { capability -> attachmentUri?.let { capability to it } }
        taskHandle = graph.deviceAgentCoordinator.start(
            task = start.task,
            targetPackage = start.packageName,
            policy = policy.copy(
                allowScreenshots = policy.allowScreenshots && start.allowScreenshots,
            ),
            // The selected URI is read only if the model requests its fixed Stage 6 tool;
            // this avoids sending the same document once as an attachment and again as a tool result.
            attachments = emptyList(),
            stage6Targets = attachmentTarget?.let { mapOf(it.first to it.second) }.orEmpty(),
            stage6TargetLabels = if (attachmentCapability != null && attachmentLabel != null) {
                mapOf(attachmentCapability to attachmentLabel)
            } else emptyMap(),
            listener = {},
        )
        clearSelectedAttachment()
        renderReadyState()
    }

    private fun openTargetAndWait(start: PendingStart) {
        val launchIntent = packageManager.getLaunchIntentForPackage(start.packageName)
        if (launchIntent == null) {
            status.setText(R.string.ai_device_agent_activity_target_unavailable)
            return
        }
        pendingStart = start
        launchingTarget = true
        leftForTarget = false
        status.setText(R.string.ai_device_agent_activity_opening_target)
        runCatching { startActivity(launchIntent) }.onFailure {
            cancelPending()
            status.setText(R.string.ai_device_agent_activity_target_unavailable)
        }
        renderReadyState()
        schedulePendingStart()
    }

    private fun schedulePendingStart() {
        mainHandler.removeCallbacks(pendingStarter)
        if (pendingStart != null) mainHandler.postDelayed(pendingStarter, 250L)
    }

    private fun tryStartPending() {
        val start = pendingStart ?: return
        if (isFinishing || isDestroyed || !start.guard.current(SystemClock.elapsedRealtime(),
                graph.aiDataGeneration.current(), graph.aiConfigurationSnapshot(), graph.deviceAgentPolicySnapshot())) {
            cancelPending()
            status.setText(R.string.ai_device_launch_expired)
            renderReadyState()
            return
        }
        if (graph.deviceAgentBroker.currentWindow()?.packageName == start.packageName) {
            pendingStart = null
            launchingTarget = false
            leftForTarget = false
            launchTask(start)
        } else {
            schedulePendingStart()
        }
    }

    private fun renderEvent(event: DeviceAgentEvent) {
        timeline.append(event)
        when (event) {
            is DeviceAgentEvent.State -> status.text = DeviceAgentDescriptions(this).status(event.state)
            is DeviceAgentEvent.ApprovalRequired, is DeviceAgentEvent.TaskApprovalRequired -> status.setText(R.string.ai_task_approval_hint)
            is DeviceAgentEvent.ActionFinished -> status.setText(DeviceTaskTimelineView.resultLabel(event.result.status))
            is DeviceAgentEvent.Completed -> status.setText(R.string.ai_device_agent_activity_complete)
            is DeviceAgentEvent.Failed -> status.text = event.message
        }
        if (!graph.deviceAgentCoordinator.hasActiveTask) {
            taskHandle = null
            start.setText(R.string.ai_task_start_new)
        }
        renderReadyState()
    }

    private fun showExamples() {
        val values = resources.getStringArray(R.array.ai_task_examples)
        appDialog?.dismiss()
        appDialog = MaterialAlertDialogBuilder(this).setTitle(R.string.ai_task_examples)
            .setItems(values) { _, index ->
                if (graph.deviceAgentCoordinator.hasActiveTask || pendingStart != null) return@setItems
                if (task.text.isNotBlank()) task.error = getString(R.string.ai_task_clear_draft)
                else { task.setText(values[index]); task.requestFocus() }
            }.setNegativeButton(R.string.cancel, null).create().also {
                it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE); it.show()
            }
    }

    private fun renderReadyState() {
        if (!::start.isInitialized) return
        val running = graph.deviceAgentCoordinator.hasActiveTask
        val busy = running || pendingStart != null
        val config = graph.aiConfigurationSnapshot()
        val policy = graph.deviceAgentPolicySnapshot()
        val readyMessage = when {
            !graph.settings.learningEnabled || graph.settings.incognitoMode -> R.string.ai_assistant_privacy_blocked
            config == null -> R.string.ai_assistant_configuration_loading
            !config.enabled || !config.networkAllowed -> R.string.ai_assistant_network_disabled
            config.activeKey().isBlank() -> R.string.ai_assistant_configuration_required
            !policy.enabled || !policy.disclosureAccepted -> R.string.ai_device_agent_activity_disclosure_required
            !graph.deviceAgentBroker.connected -> R.string.ai_device_agent_not_authorized
            !graph.deviceAgentBroker.unlocked() -> R.string.ai_assistant_privacy_blocked
            else -> R.string.ai_device_agent_activity_ready
        }
        readiness.setText(if (pendingStart != null) R.string.ai_device_task_waiting else readyMessage)
        start.isEnabled = !busy && readSignal == null && readyMessage == R.string.ai_device_agent_activity_ready
        stop.isEnabled = busy
        examples.isEnabled = !busy
        targetPackage.isEnabled = !busy
        task.isEnabled = !busy
        chooseApp.isEnabled = !busy && readSignal == null
        if (!graph.settings.deviceAgentAllowScreenshots) screenshots.isChecked = false
        screenshots.isEnabled = !busy && graph.settings.deviceAgentAllowScreenshots
        attachmentButton.isEnabled = !busy && readSignal == null
        removeAttachmentButton.isEnabled = !busy && selectedDocument != null
        if (!running && status.text.isNullOrBlank()) {
            status.setText(if (graph.deviceAgentBroker.connected) {
                R.string.ai_device_agent_activity_ready
            } else R.string.ai_device_agent_not_authorized)
        }
    }

    override fun onStop() {
        super.onStop()
        appDialog?.dismiss()
        capabilityDialog?.dismiss()
        cancelSelectionRead()
        if (launchingTarget && pendingStart != null) leftForTarget = true
        if (!launchingTarget && pendingStart != null) {
            pendingStart = null
            mainHandler.removeCallbacks(pendingStarter)
        }
    }

    override fun onRetainCustomNonConfigurationInstance(): Any? = taskHandle

    override fun onDestroy() {
        clearSelectedAttachment()
        cancelPending()
        observer?.close()
        settingsObserver?.close()
        configurationObserver?.close()
        observer = null
        settingsObserver = null
        if (isFinishing && !isChangingConfigurations) taskHandle?.cancel()
        if (!isChangingConfigurations) taskHandle = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (!::status.isInitialized) return
        if (leftForTarget && pendingStart != null) {
            cancelPending()
            status.setText(R.string.ai_device_stop_pending)
        }
        renderReadyState()
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.clear() }

    private fun cancelPending() {
        pendingStart?.guard?.cancel()
        pendingStart = null
        launchingTarget = false
        leftForTarget = false
        mainHandler.removeCallbacks(pendingStarter)
    }

    private fun chooseTargetApp() {
        val generation = ++dataGeneration
        val policy = graph.deviceAgentPolicySnapshot()
        val manager = applicationContext.packageManager
        val ownPackage = applicationContext.packageName
        val owner = WeakReference(this)
        val dispatcher = mainHandler
        chooseApp.isEnabled = false
        try {
            graph.aiDocumentExecutor.execute {
                val choices = runCatching { DeviceAgentAppChoices.load(manager, policy, ownPackage) }
                dispatcher.post {
                    val activity = owner.get() ?: return@post
                    if (activity.isFinishing || activity.isDestroyed || activity.dataGeneration != generation) return@post
                    activity.chooseApp.isEnabled = true
                    choices.fold(activity::showAppChoices) { activity.status.setText(R.string.ai_device_choose_app_failed) }
                }
            }
        } catch (_: RejectedExecutionException) {
            chooseApp.isEnabled = true
            status.setText(R.string.ai_device_choose_app_failed)
        }
    }

    private fun showAppChoices(choices: List<DeviceAgentAppChoice>) {
        if (choices.isEmpty()) { status.setText(R.string.ai_device_choose_app_empty); return }
        appDialog?.dismiss()
        appDialog = DeviceAppChoiceDialog.show(this, choices) { choice ->
            if (!graph.deviceAgentCoordinator.hasActiveTask && pendingStart == null) {
                targetPackage.setText(choice.packageName)
                chooseApp.text = choice.label.ifBlank { choice.packageName }
            }
        }
    }

    private fun button(label: Int, action: () -> Unit) = MaterialButton(this).apply {
        setText(label)
        isAllCaps = false
        minHeight = dp(48)
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun chooseAttachment() {
        if (!graph.deviceAgentPolicySnapshot().let { it.permitsStage6(DeviceStage6Capability.FILES) || it.permitsStage6(DeviceStage6Capability.MEDIA) }) {
            attachmentStatus.setText(R.string.ai_device_agent_activity_file_disabled)
            return
        }
        attachmentPicker.launch(ALLOWED_TYPES)
    }

    private fun clearSelectedAttachment() {
        cancelSelectionRead()
        selectedDocument = null
        if (::attachmentStatus.isInitialized) attachmentStatus.text = ""
        if (::removeAttachmentButton.isInitialized) removeAttachmentButton.visibility = View.GONE
    }

    private fun cancelSelectionRead() {
        dataGeneration++
        val signal = readSignal ?: return
        readSignal = null
        runCatching { graph.aiCancellationExecutor.execute { signal.cancel() } }
    }

    private fun selectedAttachmentCapability(): DeviceStage6Capability? = when {
        selectedDocument == null -> null
        selectedDocument?.mimeType?.let { mime ->
            mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/")
        } == true -> DeviceStage6Capability.MEDIA
        else -> DeviceStage6Capability.FILES
    }

    companion object {
        private fun inspectSelectedDocument(resolver: android.content.ContentResolver, uri: Uri, cancellation: CancellationSignal): SelectedDocument {
            require(uri.scheme == android.content.ContentResolver.SCHEME_CONTENT)
            val mimeType = requireNotNull(resolver.getType(uri))
                .lowercase().substringBefore(';')
            require(mimeType in ALLOWED_MIME_TYPES)
            val displayName = resolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
                cancellation,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                .orEmpty().takeWholeCodePoints(128).filterNot(Char::isISOControl).ifBlank { "selected item" }
            cancellation.throwIfCanceled()
            return SelectedDocument(uri.toString(), mimeType, displayName)
        }

        const val EXTRA_TARGET_PACKAGE = "dev.zeroinput.ime.ai.device.TARGET_PACKAGE"
        private val PACKAGE_PATTERN = Regex("[A-Za-z0-9_][A-Za-z0-9_.]{0,254}")
        private val ALLOWED_TYPES = arrayOf("text/plain", "image/jpeg", "image/png", "image/webp", "audio/wav", "audio/x-wav", "audio/mpeg")
        private val ALLOWED_MIME_TYPES = setOf("text/plain", "image/jpeg", "image/png", "image/webp", "audio/wav", "audio/x-wav", "audio/mpeg")
    }

    private data class SelectedDocument(val uri: String, val mimeType: String, val displayName: String)

    private class PendingStart(
        val packageName: String,
        val task: String,
        val allowScreenshots: Boolean,
        val guard: DeviceAgentLaunchGuard,
    )
}
