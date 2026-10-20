package dev.zeroinput.ime.ai.device

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
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
    private lateinit var timeline: LinearLayout
    private var observer: AutoCloseable? = null
    private var settingsObserver: AutoCloseable? = null
    private var rendering = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingStart: PendingStart? = null
    private var pendingAttempts = 0
    private var taskHandle: AiRequestHandle? = null
    private var launchingTarget = false
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
        attachmentButton.isEnabled = false
        renderReadyState()
        val owner = WeakReference(this)
        try {
            graph.aiDocumentExecutor.execute {
                val value = runCatching { inspectSelectedDocument(uri, signal) }.getOrNull()
                if (!mainHandler.post {
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
            runOnUiThread { renderEvent(event) }
        }
        settingsObserver = graph.settings.addChangeListener {
            runOnUiThread {
                if (pendingStart != null) {
                    pendingStart = null
                    launchingTarget = false
                    mainHandler.removeCallbacks(pendingStarter)
                }
                if (!graph.deviceAgentPolicySnapshot().let { it.permitsStage6(DeviceStage6Capability.FILES) || it.permitsStage6(DeviceStage6Capability.MEDIA) }) clearSelectedAttachment()
                renderReadyState()
            }
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
        content.addView(TextView(this).apply { setText(R.string.ai_device_agent_target_package); textSize = 14f })
        targetPackage = EditText(this).apply {
            hint = getString(R.string.ai_device_agent_target_package_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(255))
            setSingleLine(true)
            isSaveEnabled = false
            setText(intent.getStringExtra(EXTRA_TARGET_PACKAGE)
                ?: graph.deviceAgentBroker.currentWindow()?.packageName.orEmpty())
        }
        content.addView(targetPackage)
        content.addView(TextView(this).apply {
            setText(R.string.ai_device_agent_activity_task)
            textSize = 14f
            setPadding(0, dp(12), 0, 0)
        })
        task = EditText(this).apply {
            hint = getString(R.string.ai_device_agent_task_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            filters = arrayOf(InputFilter.LengthFilter(DeviceAgentLimits.MAX_TASK_CHARS))
            minLines = 3
            maxLines = 7
            isSaveEnabled = false
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        content.addView(task)
        screenshots = MaterialSwitch(this).apply {
            setText(R.string.ai_device_agent_activity_screenshot_confirm)
            minHeight = dp(56)
            isChecked = false
        }
        content.addView(screenshots)
        attachmentButton = button(R.string.ai_device_agent_activity_add_file) { chooseAttachment() }
        content.addView(attachmentButton)
        removeAttachmentButton = button(R.string.ai_device_agent_activity_file_removed) { clearSelectedAttachment() }
        removeAttachmentButton.visibility = View.GONE
        content.addView(removeAttachmentButton)
        attachmentStatus = TextView(this).apply { textSize = 13f; isSaveEnabled = false }
        content.addView(attachmentStatus)
        status = TextView(this).apply { textSize = 14f; setPadding(0, dp(8), 0, dp(8)) }
        content.addView(status)
        start = button(R.string.ai_device_agent_activity_start) { startTask() }
        stop = button(R.string.ai_device_agent_activity_stop) { taskHandle?.cancel() }
        content.addView(start)
        content.addView(stop)
        content.addView(button(R.string.ai_device_agent_activity_open_settings) {
            startActivity(Intent(this, DeviceAgentSettingsActivity::class.java))
        })
        content.addView(TextView(this).apply {
            setText(R.string.ai_device_agent_activity_timeline)
            textSize = 16f
            setPadding(0, dp(18), 0, dp(8))
        })
        timeline = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(timeline)
        setContentView(ScrollView(this).apply {
            addView(content)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
    }

    private fun startTask() {
        if (graph.deviceAgentCoordinator.hasActiveTask) return
        val packageName = targetPackage.text.toString().trim()
        val taskText = task.text.toString().trim()
        if (!packageName.matches(PACKAGE_PATTERN)) {
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
        timeline.removeAllViews()
        val request = PendingStart(packageName, taskText, basePolicy.allowScreenshots && screenshots.isChecked)
        if (graph.deviceAgentBroker.currentWindow()?.packageName != packageName) {
            openTargetAndWait(request)
            return
        }
        launchTask(request)
    }

    private fun launchTask(start: PendingStart) {
        val policy = graph.deviceAgentPolicySnapshot()
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
        pendingAttempts = 0
        launchingTarget = true
        status.setText(R.string.ai_device_agent_activity_opening_target)
        runCatching { startActivity(launchIntent) }.onFailure {
            pendingStart = null
            status.setText(R.string.ai_device_agent_activity_target_unavailable)
        }
        schedulePendingStart()
    }

    private fun schedulePendingStart() {
        mainHandler.removeCallbacks(pendingStarter)
        if (pendingStart != null) mainHandler.postDelayed(pendingStarter, 250L)
    }

    private fun tryStartPending() {
        val start = pendingStart ?: return
        if (isFinishing || isDestroyed) {
            pendingStart = null
            return
        }
        if (graph.deviceAgentBroker.currentWindow()?.packageName == start.packageName) {
            pendingStart = null
            launchingTarget = false
            launchTask(start)
        } else if (pendingAttempts++ < 40) {
            schedulePendingStart()
        } else {
            pendingStart = null
            status.setText(R.string.ai_device_agent_activity_target_not_ready)
        }
    }

    private fun renderEvent(event: DeviceAgentEvent) {
        when (event) {
            is DeviceAgentEvent.State -> {
                status.text = event.message
                addTimeline(event.message)
            }
            is DeviceAgentEvent.ApprovalRequired -> {
                addTimeline(event.description)
            }
            is DeviceAgentEvent.TaskApprovalRequired -> {
                addTimeline(event.description)
            }
            is DeviceAgentEvent.ActionFinished -> {
                addTimeline(getString(
                    R.string.ai_device_agent_activity_action,
                    resultLabel(event.result.status),
                    event.result.summary,
                ))
            }
            is DeviceAgentEvent.Completed -> {
                status.setText(R.string.ai_device_agent_activity_complete)
                addTimeline(event.message)
                renderReadyState()
            }
            is DeviceAgentEvent.Failed -> {
                status.text = getString(R.string.ai_device_agent_activity_failed, event.message)
                addTimeline(event.message)
                renderReadyState()
            }
        }
    }

    private fun addTimeline(value: String) {
        if (!::timeline.isInitialized || value.isBlank()) return
        val row = TextView(this).apply {
            text = value.take(DeviceAgentLimits.MAX_TOOL_RESULT_CHARS)
            textSize = 14f
            setPadding(0, dp(6), 0, dp(6))
            isSaveEnabled = false
        }
        timeline.addView(row)
        while (timeline.childCount > DeviceAgentLimits.MAX_EVENT_HISTORY) timeline.removeViewAt(0)
    }

    private fun renderReadyState() {
        if (!::start.isInitialized) return
        val running = graph.deviceAgentCoordinator.hasActiveTask
        start.isEnabled = !running && readSignal == null
        stop.isEnabled = running && taskHandle != null
        if (!graph.settings.deviceAgentAllowScreenshots) screenshots.isChecked = false
        screenshots.isEnabled = !running && graph.settings.deviceAgentAllowScreenshots
        attachmentButton.isEnabled = !running
        removeAttachmentButton.isEnabled = !running && selectedDocument != null
        if (!running && status.text.isNullOrBlank()) {
            status.setText(if (graph.deviceAgentBroker.connected) {
                R.string.ai_device_agent_activity_ready
            } else R.string.ai_device_agent_not_authorized)
        }
    }

    override fun onStop() {
        super.onStop()
        if (!launchingTarget && pendingStart != null) {
            pendingStart = null
            mainHandler.removeCallbacks(pendingStarter)
        }
    }

    override fun onRetainCustomNonConfigurationInstance(): Any? = taskHandle

    override fun onDestroy() {
        clearSelectedAttachment()
        mainHandler.removeCallbacks(pendingStarter)
        pendingStart = null
        observer?.close()
        settingsObserver?.close()
        observer = null
        settingsObserver = null
        if (isFinishing && !isChangingConfigurations) taskHandle?.cancel()
        if (!isChangingConfigurations) taskHandle = null
        super.onDestroy()
    }

    private fun resultLabel(status: dev.zeroinput.ai.api.DeviceActionResultStatus): String = when (status) {
        dev.zeroinput.ai.api.DeviceActionResultStatus.SUCCESS -> getString(R.string.ai_device_agent_result_success)
        dev.zeroinput.ai.api.DeviceActionResultStatus.REJECTED -> getString(R.string.ai_device_agent_result_rejected)
        dev.zeroinput.ai.api.DeviceActionResultStatus.EXPIRED -> getString(R.string.ai_device_agent_stopped)
        dev.zeroinput.ai.api.DeviceActionResultStatus.FAILED -> getString(R.string.ai_device_agent_stopped)
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

    private fun inspectSelectedDocument(uri: Uri, cancellation: CancellationSignal): SelectedDocument {
        require(uri.scheme == android.content.ContentResolver.SCHEME_CONTENT)
        val mimeType = requireNotNull(applicationContext.contentResolver.getType(uri))
            .lowercase().substringBefore(';')
        require(mimeType in ALLOWED_MIME_TYPES)
        val displayName = applicationContext.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
            cancellation,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            .orEmpty().take(128).filterNot(Char::isISOControl).ifBlank { "selected item" }
        cancellation.throwIfCanceled()
        return SelectedDocument(uri.toString(), mimeType, displayName)
    }

    companion object {
        const val EXTRA_TARGET_PACKAGE = "dev.zeroinput.ime.ai.device.TARGET_PACKAGE"
        private val PACKAGE_PATTERN = Regex("[A-Za-z0-9_][A-Za-z0-9_.]{0,254}")
        private val ALLOWED_TYPES = arrayOf("text/plain", "image/jpeg", "image/png", "image/webp", "audio/wav", "audio/x-wav", "audio/mpeg")
        private val ALLOWED_MIME_TYPES = setOf("text/plain", "image/jpeg", "image/png", "image/webp", "audio/wav", "audio/x-wav", "audio/mpeg")
    }

    private data class SelectedDocument(val uri: String, val mimeType: String, val displayName: String)

    private data class PendingStart(
        val packageName: String,
        val task: String,
        val allowScreenshots: Boolean,
    )
}
