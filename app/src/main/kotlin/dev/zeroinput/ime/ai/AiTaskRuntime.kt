package dev.zeroinput.ime.ai

import android.app.KeyguardManager
import android.content.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.zeroinput.ai.api.*
import dev.zeroinput.userdata.AiConfiguration
import java.lang.ref.WeakReference

internal interface AiTaskObserver {
    fun event(value: AiToolRunEvent)
    fun cleared(wasRunning: Boolean)
}
internal class AiTaskSnapshot(val request: AiToolRequest, val labels: List<String>, val model: String)

/** App-owned transient run. Only a weak observer points at the UI. All control methods run on main. */
internal class AiTaskRuntime(private val context: Context, private val configuration: () -> AiConfiguration?,
    private val allowed: () -> Boolean, private val data: AiDataGeneration, private val connections: AiDataGeneration,
    provider: AiToolProvider) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val serviceIntent = Intent(context, AiTaskForegroundService::class.java)
    private val lease = AiTaskLease(SystemClock::elapsedRealtime)
    private var observer = WeakReference<AiTaskObserver>(null)
    private var uiOwner: String? = null
    private var foreground = false
    private var config: AiConfiguration? = null
    private var dataToken = -1L
    private var connectionToken = -1L
    private var remote: AiRemoteTools? = null
    private var pending: Pair<AiToolRequest, Map<String, AiToolExecutor>>? = null
    private var notify = false
    private var background = false
    @Volatile private var serviceReady = false
    private var nonce: String? = null
    private val history = ArrayList<AiToolRunEvent>()
    private var historySize = 0
    var snapshot: AiTaskSnapshot? = null
        private set
    val notifications = AiRunNotifications(context)
    private val expiry = Runnable { revoke() }
    private val serviceDeadline = Runnable { if (pending != null && !serviceReady) revoke() }
    private val backgroundMonitor = object : Runnable {
        override fun run() {
            if (!background || !running) return
            if (!lease.valid() || !bindingAllowed() || !notifications.available()) revoke()
            else main.postDelayed(this, 1_000)
        }
    }
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == Intent.ACTION_SCREEN_OFF) revoke() }
    }
    private val run = AiToolRun(provider, ::dispatchAllowed, { main.post(it) }, { delay, callback ->
        val action = Runnable(callback); main.postDelayed(action, delay); AutoCloseable { main.removeCallbacks(action) }
    }, ::changed)
    val running get() = pending != null || run.running
    val notificationEnabled get() = notify
    val backgroundEnabled get() = background
    val events get() = history.toList()
    init { ContextCompat.registerReceiver(context, lockReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED) }

    fun attach(owner: String, target: AiTaskObserver) {
        uiOwner = owner; foreground = true; observer = WeakReference(target); lease.enter(owner)
        if (nonce != null && (!lease.valid() || !bindingAllowed())) revoke()
    }
    fun leave(owner: String, picker: Boolean) {
        if (owner != uiOwner) return
        foreground = false; observer.clear()
        if (!lease.leave(owner, picker) || !baseAllowed()) revoke()
    }
    fun destroy(owner: String) {
        if (owner != uiOwner) return
        observer.clear(); foreground = false; uiOwner = null
        if (!lease.destroy(owner)) revoke()
    }
    fun validOpen(token: String?) = token != null && lease.valid(token) && bindingAllowed()
    fun start(owner: String, request: AiToolRequest, executors: Map<String, AiToolExecutor>, labels: List<String>, model: String,
        remote: AiRemoteTools?, notifications: Boolean, background: Boolean): Boolean {
        if (uiOwner != owner || !foreground || running || !baseAllowed() || background && !notifications ||
            notifications && !this.notifications.available() || background && request.tools.any { it.origin == AiToolOrigin.FILE && it.effect != AiToolEffect.READ }) return false
        val currentConfig = configuration() ?: return false
        if (!currentConfig.enabled || !currentConfig.networkAllowed || currentConfig.activeKey().isBlank()) return false
        clearState()
        config = currentConfig; dataToken = data.current(); connectionToken = connections.current()
        this.remote = remote; this.notify = notifications; this.background = background
        val token = lease.begin(owner, background); nonce = token
        snapshot = AiTaskSnapshot(request, labels.toList(), model); pending = request to executors.toMap()
        main.postDelayed(expiry, lease.remaining())
        if (background) {
            try {
                main.postDelayed(backgroundMonitor, 1_000)
                ContextCompat.startForegroundService(context, Intent(serviceIntent).putExtra(AiRunNotifications.TOKEN, token))
                main.postDelayed(serviceDeadline, 5_000)
                changed(AiToolRunEvent(AiToolRunState.THINKING))
            } catch (_: RuntimeException) { revoke(); return false }
        } else launch()
        return running
    }
    private fun launch() {
        val launch = pending ?: return
        pending = null
        if (!dispatchAllowed() || !run.start(launch.first, launch.second)) changed(AiToolRunEvent(AiToolRunState.FAILED, failure = AiToolRunFailure.UNAVAILABLE))
    }
    fun approve(owner: String, token: String, accepted: Boolean): Boolean =
        uiOwner == owner && foreground && lease.canApprove(owner) && run.approve(token, accepted)
    fun stop(token: String? = nonce) {
        if (!lease.valid(token)) return
        if (pending != null) {
            pending = null; changed(AiToolRunEvent(AiToolRunState.STOPPED))
        } else run.stop()
    }
    fun acceptsService(token: String?) = background && token != null && lease.valid(token) && bindingAllowed() && (pending != null || run.running)
    fun serviceStarted(token: String): Boolean {
        if (!acceptsService(token) || !notifications.available()) return false
        serviceReady = true; main.removeCallbacks(serviceDeadline); launch(); return true
    }
    fun serviceLost(token: String?) {
        if (nonce != token) return
        serviceReady = false
        if (running) revoke() else if (notify && lease.valid()) nonce?.let { notifications.show(it, history.lastOrNull()?.state ?: AiToolRunState.STOPPED) }
    }
    fun foregroundNotification(token: String) = notifications.notification(token, history.lastOrNull()?.state ?: AiToolRunState.THINKING)
    private fun baseAllowed() = allowed() &&
        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun bindingAllowed() = baseAllowed() && data.isCurrent(dataToken) && configuration() == config && connections.isCurrent(connectionToken)
    private fun dispatchAllowed() = bindingAllowed() && lease.canDispatch(serviceReady) &&
        (!background || notifications.available())
    private fun changed(event: AiToolRunEvent) {
        if (!lease.valid() || !bindingAllowed()) { revoke(); return }
        val size = event.message.length + (event.result?.content?.length ?: 0) + (event.call?.arguments?.length ?: 0)
        while (history.isNotEmpty() && (history.size >= 100 || historySize + size > 100_000)) {
            val old = history.removeAt(0); historySize -= old.message.length + (old.result?.content?.length ?: 0) + (old.call?.arguments?.length ?: 0)
        }
        history += event; historySize += size
        if (event.state in setOf(AiToolRunState.COMPLETED, AiToolRunState.STOPPED, AiToolRunState.FAILED)) {
            pending = null; snapshot = null; remote?.close(); remote = null
            lease.completed(); main.removeCallbacks(expiry); main.postDelayed(expiry, lease.remaining())
            main.removeCallbacks(serviceDeadline)
            main.removeCallbacks(backgroundMonitor)
            if (background) context.stopService(serviceIntent)
        }
        if (notify) nonce?.let { if (!notifications.show(it, event.state) && background && running) { revoke(); return } }
        if (foreground) observer.get()?.event(event)
    }
    fun revoke() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { revoke() }; return }
        val wasRunning = running
        lease.revoke(); run.stop(); clearState(); if (foreground) observer.get()?.cleared(wasRunning)
    }
    private fun clearState() {
        main.removeCallbacks(expiry); main.removeCallbacks(serviceDeadline)
        main.removeCallbacks(backgroundMonitor)
        pending = null; snapshot = null; remote?.close(); remote = null; config = null; dataToken = -1; connectionToken = -1
        history.clear(); historySize = 0; notify = false; background = false; serviceReady = false; nonce = null
        notifications.clear(); context.stopService(serviceIntent)
    }
    override fun close() { revoke(); run.close(); context.unregisterReceiver(lockReceiver) }
}
