package dev.zeroinput.ime.ai

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zeroinput.ai.api.AiLimits
import dev.zeroinput.ai.api.AiStreamEvent
import dev.zeroinput.ime.ai.device.DeviceCapabilityOverview
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.ai.device.AiDeviceAgentActivity
import dev.zeroinput.ime.settings.MainActivity
import dev.zeroinput.ime.ui.AiAssistantPanelView
import dev.zeroinput.ime.ui.AiContextCommand

/** Private full-screen host; launching it never sends a request or collects a page. */
class AiAssistantActivity : AppCompatActivity() {
    private val graph get() = (application as ZeroInputApplication).graph
    private val main = Handler(Looper.getMainLooper())
    private lateinit var panel: AiAssistantPanelView
    private var started = false
    private var revision = 0L
    private var configurationReadFailed = false
    private var settingsObserver: AutoCloseable? = null
    private var configurationObserver: AutoCloseable? = null
    private var renameDialog: androidx.appcompat.app.AlertDialog? = null
    private var queueTurn = 0L
    private var dispatching = false
    private var queueConfiguration: dev.zeroinput.userdata.AiConfiguration? = null
    private var queueGeneration = -1L
    private var capabilityDialog: androidx.appcompat.app.AlertDialog? = null
    private var contextLibraryDialog: AiContextLibraryDialog? = null
    private var compactionDialog: AiCompactionDialog? = null
    private var skillSelectionDialog: AiSkillSelectionDialog? = null
    private var skillNavigationDialog: androidx.appcompat.app.AlertDialog? = null
    private val queue: AiChatQueue by lazy {
        AiChatQueue(
            ready = { hasWindowFocus() && allowed() && graph.aiConfigurationSnapshot() == queueConfiguration &&
                graph.aiDataGeneration.isCurrent(queueGeneration) },
            post = { main.post(it) },
            submit = { message ->
                queueTurn++
                dispatching = true
                try { workbench.submit(message.action, message.text, message.target) }
                finally { dispatching = false }
            },
            cancel = { queueTurn++; workbench.stop() },
            changed = { messages, busy -> panel.renderQueue(messages.map { it.text }, busy) },
        )
    }
    private val workbench: AiWorkbenchController by lazy {
        AiWorkbenchController(
            graph.standaloneAiCoordinator, graph.aiConversations, graph.aiPersistenceExecutor,
            graph::aiConfigurationSnapshot, graph.aiDataGeneration,
            post = { main.post(it) }, allowed = ::allowed,
            generationPolicy = { AiStandalonePolicy.generationPolicy(it, allowed()) },
            turnSettled = { success ->
                val token = queueTurn
                main.post {
                    if (started && token == queueTurn) {
                        if (success) queue.completed() else queue.failed()
                    }
                }
            },
            render = ::renderStream, renderList = panel::renderConversations,
            renderConversation = panel::renderConversation, renderModels = panel::renderModels,
            renderContext = {
                panel.renderContext(it)
                panel.compactionState(workbench.canCompact, workbench.canUndoCompaction)
            }, renderHistoryStatus = panel::renderHistoryStatus,
            storageFailed = { Toast.makeText(this, dev.zeroinput.ime.ui.R.string.ai_storage_failed, Toast.LENGTH_LONG).show() },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        intent = Intent()
        panel = AiAssistantPanelView(this)
        setContentView(panel)
        ViewCompat.setOnApplyWindowInsetsListener(panel) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        bindPanel()
    }

    private fun bindPanel() {
        panel.onSubmit = { action, text, target -> send(AiChatQueue.Message(action, text, target), false) }
        panel.onInterrupt = { action, text, target -> send(AiChatQueue.Message(action, text, target), true) }
        panel.onQueueRemove = { queue.remove(it) }
        panel.onQueueEdit = { index ->
            if (panel.hasDraft()) panel.draftOccupied()
            else queue.remove(index)?.let { panel.editQueued(it.action, it.text, it.target) }
        }
        panel.onCancel = { queue.reset(); workbench.cancelRequest() }
        panel.onNewConversation = { queue.reset(); workbench.newConversation() }
        panel.onModelSelected = { queue.reset(); workbench.selectModel(it) }
        panel.onReasoningChanged = { queue.reset(); workbench.setReasoningMode(it) }
        panel.onConversationsRequested = workbench::refreshConversations
        panel.onConversationSelected = { queue.reset(); workbench.selectConversation(it) }
        panel.onConversationDeleted = { queue.reset(); workbench.deleteConversation(it) }
        panel.onConversationRename = ::renameConversation
        panel.onContextAction = { command ->
            queue.reset()
            when (command) {
                is AiContextCommand.History -> workbench.includeHistory(command.revision, command.index, command.included)
                is AiContextCommand.Recent -> workbench.recentHistory(command.revision)
                is AiContextCommand.Clear -> workbench.clearContext(command.revision)
                is AiContextCommand.Remove -> workbench.removeReference(command.revision, command.index)
            }
        }
        panel.onSettings = {
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_AI_SETTINGS, true))
        }
        panel.onCapabilities = {
            capabilityDialog?.dismiss()
            capabilityDialog = DeviceCapabilityOverview.show(this, graph.deviceAgentPolicySnapshot(), graph.deviceAgentBroker.connected)
        }
        panel.onContextLibrary = ::showContextLibrary
        panel.onCompact = ::showCompaction
        panel.onSkills = ::showSkills
        panel.onNativeAssistant = {
            if (allowed()) { skillNavigationDialog?.dismiss(); skillNavigationDialog = AiNativeAssistantSetup.show(this, ::allowed) }
        }
        panel.onToolTask = {
            if (allowed()) {
                if (!panel.hasDraft() && workbench.contextState.characterCount == 0 && !queue.busy)
                    startActivity(Intent(this, AiToolTaskActivity::class.java))
                else {
                    skillNavigationDialog?.dismiss()
                    skillNavigationDialog = MaterialAlertDialogBuilder(this).setTitle(R.string.ai_tool_title)
                        .setMessage(R.string.ai_tool_leave_notice).setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.ai_tool_open) { _, _ -> if (allowed()) startActivity(Intent(this, AiToolTaskActivity::class.java)) }
                        .create().also { it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE); it.show() }
                }
            }
        }
        panel.onUndoCompaction = {
            queue.reset()
            if (!workbench.undoCompaction()) Toast.makeText(this, R.string.ai_compaction_unavailable, Toast.LENGTH_SHORT).show()
        }
        panel.onDeviceAgent = { startActivity(Intent(this, AiDeviceAgentActivity::class.java)) }
    }

    private fun send(message: AiChatQueue.Message, interrupt: Boolean) {
        val config = graph.aiConfigurationSnapshot()
        if (!hasWindowFocus() || !allowed() || config?.enabled != true || !config.networkAllowed || config.activeKey().isBlank() ||
            queue.busy && (config != queueConfiguration || !graph.aiDataGeneration.isCurrent(queueGeneration))) {
            queue.reset()
            refresh()
            return
        }
        if (!queue.busy) {
            queueConfiguration = graph.aiConfigurationSnapshot()
            queueGeneration = graph.aiDataGeneration.current()
        }
        if (queue.send(message, interrupt)) panel.accepted() else panel.queueFull()
    }

    private fun renderStream(event: AiStreamEvent) {
        // submit() cancels the previous workbench generation before starting its new one.
        if (dispatching && event == AiStreamEvent.Cancelled) return
        panel.render(event)
        when (event) {
            is AiStreamEvent.Failed, AiStreamEvent.Cancelled -> queue.failed()
            else -> Unit
        }
    }

    private fun showContextLibrary() {
        if (!allowed()) return
        queue.reset(); workbench.stop(); contextLibraryDialog?.close()
        var contextRevision = workbench.contextState.revision
        contextLibraryDialog = AiContextLibraryDialog(this, graph.aiContextLibrary, graph.aiPersistenceExecutor,
            graph.aiDataGeneration, { main.post(it) }, workbench.contextState, ::allowed,
            changing = {
                workbench.selectWorkspace(workbench.contextState.revision, dev.zeroinput.ai.api.AiWorkspaceContext())
                contextRevision = workbench.contextState.revision
            },
            apply = { value -> workbench.selectWorkspace(contextRevision, value) },
        ).also { it.show() }
    }

    private fun showCompaction() {
        if (!allowed()) return
        queue.reset(); workbench.stop(); compactionDialog?.close()
        val input = workbench.compactionInput()
        if (input == null) { Toast.makeText(this, R.string.ai_compaction_unavailable, Toast.LENGTH_SHORT).show(); return }
        val inputRevision = input.revision
        compactionDialog = AiCompactionDialog(this, input,
            start = { callback -> workbench.requestCompaction(inputRevision, callback) },
            apply = workbench::applyCompaction, cancel = workbench::stop,
        ).also { it.show() }
    }

    private fun showSkills() {
        if (!allowed()) return
        queue.reset(); workbench.stop(); skillSelectionDialog?.close()
        val contextRevision = workbench.contextState.revision
        skillSelectionDialog = AiSkillSelectionDialog(this, graph.aiSkills, graph.aiPersistenceExecutor,
            graph.aiDataGeneration, { main.post(it) }, workbench.contextState, ::allowed, ::manageSkills,
            apply = { skills ->
                val value = workbench.contextState.workspace
                workbench.selectWorkspace(contextRevision, dev.zeroinput.ai.api.AiWorkspaceContext(value.persona, value.memories, skills))
            }).also { it.show() }
    }

    private fun manageSkills() {
        if (!allowed()) return
        if (!panel.hasDraft() && workbench.contextState.characterCount == 0) {
            startActivity(Intent(this, AiSkillsActivity::class.java)); return
        }
        skillNavigationDialog?.dismiss()
        skillNavigationDialog = MaterialAlertDialogBuilder(this).setTitle(R.string.ai_skill_leave_title)
            .setMessage(R.string.ai_skill_leave_notice).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ai_skill_leave_confirm) { _, _ ->
                if (allowed()) startActivity(Intent(this, AiSkillsActivity::class.java))
            }.create().also { it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE); it.show() }
    }

    override fun onStart() {
        super.onStart()
        started = true
        settingsObserver = graph.settings.addChangeListener { main.post { revoke() } }
        configurationObserver = graph.observeAiConfiguration { main.post { revoke() } }
        val token = ++revision
        graph.readAiConfiguration { result -> main.post {
            if (started && token == revision) { configurationReadFailed = result.isFailure; refresh() }
        } }
        refresh()
    }

    override fun onResume() { super.onResume(); if (::panel.isInitialized) refresh() }

    private fun allowed() = started && !isFinishing && !isDestroyed && AiStandalonePolicy.allows(
        graph.settings.learningEnabled, graph.settings.incognitoMode,
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked,
    )

    private fun refresh() {
        if (!started) return
        val config = graph.aiConfigurationSnapshot()
        val ready = allowed() && config?.enabled == true && config.networkAllowed && config.activeKey().isNotBlank()
        val message = when {
            !allowed() -> R.string.ai_assistant_privacy_blocked
            config == null && configurationReadFailed -> R.string.ai_assistant_configuration_failed
            config == null -> R.string.ai_assistant_configuration_loading
            !config.enabled || !config.networkAllowed -> R.string.ai_assistant_network_disabled
            config.activeKey().isBlank() -> R.string.ai_assistant_configuration_required
            else -> R.string.ai_assistant_ready
        }
        panel.ready(getString(message), ready, graph.settings.aiThinkingVisible)
        workbench.refreshModels()
    }

    private fun revoke() {
        if (!started) return
        revision++
        renameDialog?.dismiss()
        queue.reset()
        capabilityDialog?.dismiss()
        contextLibraryDialog?.close(); contextLibraryDialog = null
        compactionDialog?.close(); compactionDialog = null
        skillSelectionDialog?.close(); skillSelectionDialog = null
        skillNavigationDialog?.dismiss(); skillNavigationDialog = null
        workbench.invalidate()
        panel.clear()
        refresh()
    }

    private fun renameConversation(id: String, title: String) {
        if (!allowed()) return
        queue.reset()
        workbench.stop()
        renameDialog?.dismiss()
        val input = EditText(this).apply {
            setText(title)
            filters = arrayOf(InputFilter.LengthFilter(AiLimits.MAX_TITLE_CHARS))
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        renameDialog = MaterialAlertDialogBuilder(this)
            .setTitle(dev.zeroinput.ime.ui.R.string.ai_conversation_rename).setView(input)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(dev.zeroinput.ime.ui.R.string.ai_conversation_rename) { _, _ ->
                if (allowed() && input.text.toString().isNotBlank()) workbench.renameConversation(id, input.text.toString().trim())
            }.create().also { dialog ->
                dialog.setOnDismissListener { input.text?.clear(); if (renameDialog === dialog) renameDialog = null }
                dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                dialog.show()
            }
    }

    override fun onStop() {
        started = false
        queueConfiguration = null
        queueGeneration = -1L
        revision++
        settingsObserver?.close(); settingsObserver = null
        configurationObserver?.close(); configurationObserver = null
        renameDialog?.dismiss()
        queue.reset()
        capabilityDialog?.dismiss()
        contextLibraryDialog?.close(); contextLibraryDialog = null
        compactionDialog?.close(); compactionDialog = null
        skillSelectionDialog?.close(); skillSelectionDialog = null
        skillNavigationDialog?.dismiss(); skillNavigationDialog = null
        workbench.invalidate()
        panel.clear()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.clear() }
}
