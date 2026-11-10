package dev.zeroinput.ime.ai

import android.app.KeyguardManager
import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.*
import android.graphics.Bitmap
import android.os.*
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import dev.zeroinput.ai.api.*
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A fresh, user-authored text request only. No assist data is read, retained or forwarded. */
internal class AiNativeSession(private val appContext: Context) : VoiceInteractionSession(appContext) {
    private val graph get() = (appContext.applicationContext as ZeroInputApplication).graph
    private val main = Handler(Looper.getMainLooper())
    private val visible = AtomicBoolean()
    private val generation = AtomicLong()
    private var dataToken = -1L
    private var panel: AiNativePromptView? = null
    private var configObserver: AutoCloseable? = null
    private var settingsObserver: AutoCloseable? = null
    private var registered = false
    private var pending = false
    private val coordinator by lazy {
        val composition = graph
        AiCoordinator(OpenAiCompatibleProvider({ composition.aiConfigurationSnapshot() ?: dev.zeroinput.userdata.AiConfiguration() },
            composition.aiExecutor, composition.aiCancellationExecutor))
    }
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == Intent.ACTION_SCREEN_OFF) { clear(); finish() } }
    }
    init { setTheme(R.style.Theme_ZeroInput_PrivateImport) }
    override fun onCreate() {
        super.onCreate(); setDisabledShowContext(SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT)
        window.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        ContextCompat.registerReceiver(appContext, lockReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED); registered = true
    }
    override fun onCreateContentView(): View = AiNativePromptView(context).also { view ->
        panel = view; view.onSend = ::send
        view.onStop = { generation.incrementAndGet(); coordinator.invalidate(); pending = false; view.running(false); view.status(R.string.ai_run_stopped); ready() }
        view.onFull = {
            if (allowed()) {
                clear(); runCatching { startAssistantActivity(Intent(appContext, AiAssistantActivity::class.java)) }; finish()
            }
        }
    }
    override fun onShow(args: Bundle?, showFlags: Int) {
        clear(); super.onShow(Bundle.EMPTY, 0)
        setDisabledShowContext(SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT)
        visible.set(true); dataToken = graph.aiDataGeneration.current()
        if (!allowed()) { clear(); finish(); return }
        configObserver = graph.observeAiConfiguration { main.post { clear(); finish() } }
        settingsObserver = graph.settings.addChangeListener { main.post { clear(); finish() } }
        ready()
        val weak = WeakReference(this); val handler = main
        graph.readAiConfiguration { handler.post { weak.get()?.let { if (it.allowed()) it.ready() } } }
    }
    private fun allowed() = visible.get() && graph.aiDataGeneration.isCurrent(dataToken) &&
        AiStandalonePolicy.allows(graph.settings.learningEnabled, graph.settings.incognitoMode,
            !appContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
    private fun ready() {
        val config = graph.aiConfigurationSnapshot()
        panel?.ready(!pending && allowed() && config?.enabled == true && config.networkAllowed && config.activeKey().isNotBlank())
    }
    private fun send(text: String) {
        val config = graph.aiConfigurationSnapshot() ?: return
        if (!allowed() || pending || text.isBlank()) return
        val token = generation.incrementAndGet(); val weak = WeakReference(this); val handler = main
        pending = true; panel?.result(""); panel?.running(true); panel?.status(R.string.ai_run_running)
        try {
            coordinator.submit(AiRequest(null, AiAction.ASK, text), AiStandalonePolicy.generationPolicy(config, true)) { event ->
                handler.post { weak.get()?.receive(token, event) }
            }
        } catch (_: Exception) { pending = false; panel?.running(false); panel?.status(R.string.ai_run_failed); ready() }
    }
    private fun receive(token: Long, event: AiStreamEvent) {
        if (generation.get() != token || !allowed()) return
        when (event) {
            is AiStreamEvent.Completed -> { pending = false; panel?.running(false); panel?.result(event.text); panel?.status(R.string.ai_run_finished); ready() }
            is AiStreamEvent.Failed, AiStreamEvent.Cancelled -> { pending = false; panel?.running(false); panel?.status(R.string.ai_run_failed); ready() }
            else -> Unit
        }
    }
    private fun clear() {
        visible.set(false); generation.incrementAndGet(); dataToken = -1; coordinator.invalidate(); pending = false
        configObserver?.close(); configObserver = null; settingsObserver?.close(); settingsObserver = null; panel?.clear()
    }
    override fun onHide() { clear(); super.onHide() }
    override fun onLockscreenShown() { clear(); finish() }
    override fun onDestroy() {
        clear(); coordinator.close(); panel = null
        if (registered) { appContext.unregisterReceiver(lockReceiver); registered = false }; super.onDestroy()
    }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) = Unit
    override fun onHandleAssist(state: AssistState) = Unit
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onHandleAssistSecondary(data: Bundle?, structure: AssistStructure?, content: AssistContent?, index: Int, count: Int) = Unit
    override fun onHandleScreenshot(screenshot: Bitmap?) = Unit
}
