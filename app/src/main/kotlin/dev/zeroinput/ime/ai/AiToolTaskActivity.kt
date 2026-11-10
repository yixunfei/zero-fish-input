package dev.zeroinput.ime.ai

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.zeroinput.ai.api.*
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/** Independent user-authored task. No IME session or device broker is available here. */
class AiToolTaskActivity : AppCompatActivity() {
    private val graph get() = (application as ZeroInputApplication).graph
    private val main = Handler(Looper.getMainLooper())
    private val foreground = AtomicBoolean()
    private var rejectedEntry = false
    private lateinit var panel: AiToolTaskView
    private lateinit var picker: AiTaskDocumentPicker
    private var workspace = AiWorkspaceContext()
    private var contextRevision = 0L
    private val files = linkedMapOf<String, Uri>()
    private val labels = linkedMapOf<String, String>()
    private val selected = linkedSetOf<String>()
    private val owner = java.util.UUID.randomUUID().toString()
    private var notificationOptIn = false
    private var backgroundOptIn = false
    private var notificationRequestData = -1L
    private val notificationPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        if (visible() && graph.aiDataGeneration.isCurrent(notificationRequestData)) notificationOptIn = it && run.notifications.available()
        notificationRequestData = -1; refresh()
    }
    private var configurationObserver: AutoCloseable? = null
    private var settingsObserver: AutoCloseable? = null
    private var contextDialog: AiContextLibraryDialog? = null
    private var skillsDialog: AiSkillSelectionDialog? = null
    private var connectionsDialog: AiConnectionsDialog? = null
    private var virtualDialog: AiVirtualScreenDialog? = null
    private var remote: AiRemoteTools? = null
    private var connectionRevision = -1L
    private var navigationDialog: androidx.appcompat.app.AlertDialog? = null
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == Intent.ACTION_SCREEN_OFF) revoke() }
    }
    private val run get() = graph.aiTaskRuntime
    private val taskObserver = object : AiTaskObserver {
        override fun event(value: AiToolRunEvent) {
            val event = value
            if (event.state in setOf(AiToolRunState.COMPLETED, AiToolRunState.FAILED, AiToolRunState.STOPPED)) {
                clearBindings()
            }
            if (visible()) { panel.event(event); refresh() }
            else if (event.state in setOf(AiToolRunState.COMPLETED, AiToolRunState.FAILED, AiToolRunState.STOPPED)) panel.clear()
        }
        override fun cleared(wasRunning: Boolean) {
            if (!::panel.isInitialized) return
            if (wasRunning) { clearBindings(); panel.clear() } else panel.clearHistory()
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val notification = intent.action == AiRunNotifications.OPEN
        val entryToken = intent.getStringExtra(AiRunNotifications.TOKEN)
        val valid = !notification || run.validOpen(entryToken) && intent.dataString == "zeroinput-task://$entryToken"
        rejectedEntry = !valid
        intent = Intent(); window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        panel = AiToolTaskView(this)
        picker = AiTaskDocumentPicker(this, graph.aiDocumentExecutor, graph.aiDataGeneration, main, ::visible) { revoke(); panel.error() }
        setContentView(panel)
        ViewCompat.setOnApplyWindowInsetsListener(panel) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            val pad = (16 * resources.displayMetrics.density).toInt()
            view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, bars.bottom); insets
        }
        bind()
        configurationObserver = graph.observeAiConfiguration { main.post { revoke() } }
        settingsObserver = graph.settings.addChangeListener { main.post { revoke() } }
        ContextCompat.registerReceiver(this, lockReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        if (!valid) finish()
    }
    private fun bind() {
        panel.onStart = ::start
        panel.onStop = { run.stop(); picker.close(); clearBindings(); refresh() }
        panel.onPick = {
            if (visible() && !run.running && files.size < 8) picker.select { uri, label ->
                if (files.values.none { it == uri }) {
                    val id = "file${files.size + 1}"; files[id] = uri; labels[id] = getString(R.string.ai_tool_file_label, id, label)
                }
                refresh()
            }
        }
        panel.onClearFiles = {
            if (!run.running) {
                files.clear(); labels.clear(); selected.removeAll(setOf("files/read", "files/search"))
                workspace = AiWorkspaceContext(workspace.persona, workspace.memories); contextRevision++; refresh()
            }
        }
        panel.onSelect = { id, enabled ->
            if (!run.running) {
                if (enabled) selected += id else selected -= id
                contextRevision++; refresh()
            }
        }
        panel.onContext = ::context
        panel.onSkills = ::skills
        panel.onConnections = ::connections
        panel.onVirtualScreen = {
            if (visible() && !run.running) {
                virtualDialog?.close()
                virtualDialog = AiVirtualScreenDialog(this) { visible() && !run.running }.also { it.show() }
            }
        }
        panel.onSource = { url ->
            if (visible() && AiRemotePolicy.source(url)) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)) }
                    .onFailure { panel.error() }
            }
        }
        panel.onNotifications = ::notifications
        panel.onBackground = { enabled ->
            backgroundOptIn = enabled && notificationOptIn && chosen().none { it.origin == AiToolOrigin.FILE && it.effect != AiToolEffect.READ }
            refresh()
        }
        panel.onApprove = { token, accepted -> if (visible()) { if (!run.approve(owner, token, accepted)) panel.error(); refresh() } }
    }
    private fun baseAllowed() = !isFinishing && !isDestroyed && graph.settings.learningEnabled && !graph.settings.incognitoMode &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun visible() = foreground.get() && baseAllowed()
    private fun definitions() = AiFileTools.definitions(files.keys.toList()) + (remote?.definitions ?: emptyList())
    private fun chosen() = definitions().filter { it.id in selected }

    private fun start(text: String) {
        val config = graph.aiConfigurationSnapshot()
        if (!visible() || run.running || text.isBlank() || config?.enabled != true || !config.networkAllowed || config.activeKey().isBlank()) {
            panel.error(); return
        }
        val tools = chosen()
        val request = runCatching { AiToolRequest(text, tools, workspace) }.getOrNull()
        if (request == null) { panel.error(); return }
        if (remote != null && !graph.aiConnectionGeneration.isCurrent(connectionRevision)) { panel.error(); return }
        val reader = AiFileToolExecutor(applicationContext.contentResolver, files, graph.aiDocumentExecutor)
        val weakPicker = WeakReference(picker)
        val writer = AiSaveAsToolExecutor(applicationContext.contentResolver, graph.aiDocumentExecutor,
            { output, completed -> requireNotNull(weakPicker.get()).destination(output, completed) }, files.values.toSet())
        val executors = tools.associate { it.name to when (it.origin) {
            AiToolOrigin.FILE -> if (it.name == AiFileTools.SAVE) writer else reader
            else -> requireNotNull(remote)
        } }
        panel.newRun()
        val model = config.activeProvider()?.let { getString(R.string.ai_tool_provider_model, it.name, config.activeModel()) }.orEmpty()
        val accepted = run.start(owner, request, executors, labels.values.toList(), model, remote, notificationOptIn, backgroundOptIn)
        if (accepted) remote = null
        clearBindings()
        if (!accepted) panel.error()
        refresh()
    }
    private fun notifications(enabled: Boolean) {
        if (!visible() || run.running) return
        if (!enabled) { notificationOptIn = false; backgroundOptIn = false; refresh(); return }
        refresh(); navigationDialog?.dismiss()
        navigationDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_run_notifications).setMessage(R.string.ai_run_disclosure)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.ai_run_enable) { _, _ ->
                if (visible()) {
                    if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        notificationRequestData = graph.aiDataGeneration.current()
                        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else { notificationOptIn = run.notifications.available(); if (!notificationOptIn) panel.error(); refresh() }
                }
            }.create().also { it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE); it.show() }
    }
    private fun context() {
        if (!visible() || run.running) return
        var revision = contextRevision
        contextDialog?.close()
        contextDialog = AiContextLibraryDialog(this, graph.aiContextLibrary, graph.aiPersistenceExecutor, graph.aiDataGeneration,
            { main.post(it) }, AiContextState(workspace = workspace), { visible() && !run.running },
            changing = { workspace = AiWorkspaceContext(); revision = ++contextRevision; refresh() },
            apply = { value ->
                if (contextRevision != revision) false else { workspace = value; contextRevision++; refresh(); true }
            }).also { it.show() }
    }
    private fun skills() {
        if (!visible() || run.running) return
        val revision = contextRevision
        skillsDialog?.close()
        skillsDialog = AiSkillSelectionDialog(this, graph.aiSkills, graph.aiPersistenceExecutor, graph.aiDataGeneration,
            { main.post(it) }, AiContextState(workspace = workspace), { visible() && !run.running },
            manage = ::manageSkills, apply = { skills ->
                if (revision != contextRevision) false else {
                    workspace = AiWorkspaceContext(workspace.persona, workspace.memories, skills); contextRevision++; refresh(); true
                }
            }, availableTools = chosen().map { it.id }.toSet()).also { it.show() }
    }
    private fun manageSkills() {
        if (!visible() || run.running) return
        if (!panel.hasDraft() && files.isEmpty() && workspace.characterCount == 0) {
            startActivity(Intent(this, AiSkillsActivity::class.java)); return
        }
        navigationDialog?.dismiss()
        navigationDialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ai_skill_leave_title).setMessage(R.string.ai_tool_leave_notice)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.ai_skill_leave_confirm) { _, _ ->
                if (visible() && !run.running) startActivity(Intent(this, AiSkillsActivity::class.java))
            }.create().also { it.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE); it.show() }
    }
    private fun connections() {
        if (!visible() || run.running) return
        connectionsDialog?.close()
        connectionsDialog = AiConnectionsDialog(this, graph.aiConnections, graph.aiPersistenceExecutor, graph.aiRemoteExecutor,
            graph.aiDataGeneration, graph.aiConnectionGeneration, main, { visible() && !run.running },
            { visible() && graph.aiConfigurationSnapshot()?.let { it.enabled && it.networkAllowed } == true },
            changing = {
                val ids = remote?.definitions?.map { it.id }?.toSet().orEmpty()
                selected.removeAll(ids); remote?.close(); remote = null; connectionRevision = -1
                workspace = AiWorkspaceContext(workspace.persona, workspace.memories); contextRevision++; refresh()
            }, apply = { value, ids ->
                if (!visible() || run.running || selected.size + ids.size > 32) false else {
                    remote = value; connectionRevision = graph.aiConnectionGeneration.current(); selected += ids
                    contextRevision++; refresh(); true
                }
            }).also { it.show() }
    }
    private fun refresh() {
        if (!::panel.isInitialized) return
        val config = graph.aiConfigurationSnapshot()
        val snapshot = run.snapshot
        panel.configure(snapshot?.request?.tools ?: definitions(), snapshot?.request?.tools?.map { it.id }?.toSet() ?: selected,
            snapshot?.labels ?: labels.values.toList(), snapshot?.request?.workspace ?: workspace, run.running,
            visible() && config?.enabled == true && config.networkAllowed && config.activeKey().isNotBlank(),
            config?.activeProvider()?.let { getString(R.string.ai_tool_provider_model, it.name, config.activeModel()) }
                ?: getString(R.string.ai_tool_model_missing))
        val canBackground = chosen().none { it.origin == AiToolOrigin.FILE && it.effect != AiToolEffect.READ }
        if (!canBackground) backgroundOptIn = false
        panel.modes(if (run.running) run.notificationEnabled else notificationOptIn,
            if (run.running) run.backgroundEnabled else backgroundOptIn, !run.running, canBackground)
    }
    private fun revoke() {
        if (!::panel.isInitialized) return
        virtualDialog?.close(); virtualDialog = null
        run.revoke(); picker.close()
        contextDialog?.close(); contextDialog = null; skillsDialog?.close(); skillsDialog = null
        connectionsDialog?.close(); connectionsDialog = null
        navigationDialog?.dismiss(); navigationDialog = null
        clearBindings()
        panel.clear(); refresh()
    }
    private fun clearBindings() {
        remote?.close(); remote = null; connectionRevision = -1
        files.clear(); labels.clear(); selected.clear(); workspace = AiWorkspaceContext(); contextRevision++
        notificationOptIn = false; backgroundOptIn = false
    }
    override fun onStart() {
        super.onStart(); foreground.set(true)
        if (rejectedEntry) return
        run.attach(owner, taskObserver)
        run.snapshot?.let { panel.restoreDraft(it.request.input) }
        if (run.events.isNotEmpty()) { panel.newRun(); run.events.forEach(panel::event) }
        graph.readAiConfiguration { main.post { if (foreground.get()) refresh() } }
        refresh()
    }
    override fun onResume() { super.onResume(); picker.resume() }
    override fun onStop() {
        foreground.set(false)
        if (!rejectedEntry) run.leave(owner, picker.external)
        if (!picker.external || !baseAllowed()) clearLocal()
        super.onStop()
    }
    override fun onDestroy() {
        clearLocal(); picker.close(); if (!rejectedEntry) run.destroy(owner)
        configurationObserver?.close(); settingsObserver?.close()
        unregisterReceiver(lockReceiver); super.onDestroy()
    }
    private fun clearLocal() {
        virtualDialog?.close(); virtualDialog = null
        contextDialog?.close(); contextDialog = null; skillsDialog?.close(); skillsDialog = null
        connectionsDialog?.close(); connectionsDialog = null; navigationDialog?.dismiss(); navigationDialog = null
        clearBindings(); panel.clear(); notificationOptIn = false; backgroundOptIn = false
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val token = intent.getStringExtra(AiRunNotifications.TOKEN)
        val valid = intent.action != AiRunNotifications.OPEN || run.validOpen(token) && intent.dataString == "zeroinput-task://$token"
        this.intent = Intent()
        if (valid && visible()) {
            run.attach(owner, taskObserver); run.snapshot?.let { panel.restoreDraft(it.request.input) }
            panel.newRun(); run.events.forEach(panel::event); refresh()
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.clear() }
}
