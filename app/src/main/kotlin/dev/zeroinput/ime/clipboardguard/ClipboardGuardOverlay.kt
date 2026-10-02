package dev.zeroinput.ime.clipboardguard

import android.app.Activity
import android.app.AppOpsManager
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.WindowManager
import androidx.core.content.ContextCompat
import dev.zeroinput.ime.R
import java.util.Collections
import java.util.WeakHashMap

/** Main-thread window owner. Permission never grants clipboard access or keeps the IME alive. */
internal class ClipboardGuardOverlay(
    private val context: Context,
    private val currentState: () -> ClipboardGuardState,
) : Application.ActivityLifecycleCallbacks, AutoCloseable {
    private val application = context.applicationContext as Application
    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(WindowManager::class.java)
    private val appOps = context.getSystemService(AppOpsManager::class.java)
    private var window: ClipboardGuardOverlayView? = null
    private var lastEvent: String? = null
    private var lastStatus: ClipboardGuardStatus? = null
    private val startedActivities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private var receiverRegistered = false
    private var appOpsWatching = false
    private var closed = false
    private val expire = Runnable { hide() }
    private val permissionChanged = AppOpsManager.OnOpChangedListener { _, _ ->
        main.post { if (!permitted()) hide() }
    }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { hide() }
    }

    init { application.registerActivityLifecycleCallbacks(this) }

    fun render(state: ClipboardGuardState, announce: Boolean) {
        if (!state.options.listening || !state.options.overlayReminder || !permitted() || !announce) {
            hide()
            return
        }
        val event = state.eventId
        if (event == null || state.status !in EVENT_STATES) { hide(); return }
        if (lastEvent == event) {
            if (lastStatus != state.status) hide()
            lastStatus = state.status
            return
        }
        lastEvent = event
        lastStatus = state.status
        if (startedActivities.any { !it.isFinishing && !it.isDestroyed }) { hide(); return }
        show(state.options, state.statusText(), event, preview = false)
    }

    fun preview(options: ClipboardGuardOptions): Boolean =
        options.overlayReminder && show(options, R.string.clipboard_guard_overlay_preview_message, null, preview = true)

    private fun show(options: ClipboardGuardOptions, message: Int, event: String?, preview: Boolean): Boolean {
        hide()
        if (closed || !permitted() || !interactive()) return false
        val state = currentState()
        val canClear = !preview && state.ticket != null && state.options.clearMode != ClipboardClearMode.NONE
        val themed = ContextThemeWrapper(context, R.style.Theme_ZeroInput)
        val view = ClipboardGuardOverlayView(themed, message, canClear,
            onOpen = { open(event, preview) }, onDismiss = ::hide)
        val density = context.resources.displayMetrics.density
        val width = minOf((360 * density).toInt(), context.resources.displayMetrics.widthPixels - (32 * density).toInt())
        val parameters = WindowManager.LayoutParams(width.coerceAtLeast(1), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL or if (options.overlayPosition == ClipboardOverlayPosition.TOP) Gravity.TOP else Gravity.BOTTOM
            y = (64 * density).toInt()
        }
        return try {
            window = view
            manager.addView(view, parameters)
            ContextCompat.registerReceiver(context, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            appOps.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, context.packageName, permissionChanged)
            appOpsWatching = true
            main.postDelayed(expire, options.overlaySeconds * 1_000L)
            true
        } catch (_: RuntimeException) { hide(); false }
    }

    private fun open(event: String?, preview: Boolean) {
        if (!permitted() || !interactive()) { hide(); return }
        val state = currentState()
        val ticket = state.ticket?.takeIf { !preview && state.eventId == event && state.options.listening &&
            state.options.overlayReminder && state.options.clearMode != ClipboardClearMode.NONE }
        val intent = if (ticket != null) {
            Intent(context, ClipboardClearActivity::class.java).putExtra(ClipboardClearActivity.EXTRA_TICKET, ticket.id)
        } else Intent(context, ClipboardGuardSettingsActivity::class.java)
        try {
            // The visible overlay and direct user tap allow this foreground transition on Android 15+.
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: RuntimeException) {
            // OEM activity restrictions must never become an alternate cleanup path.
        } finally { hide() }
    }

    fun hide() {
        main.removeCallbacks(expire)
        window?.let { runCatching { manager.removeViewImmediate(it) } }
        window = null
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(screenOff) }
            receiverRegistered = false
        }
        if (appOpsWatching) {
            runCatching { appOps.stopWatchingMode(permissionChanged) }
            appOpsWatching = false
        }
    }

    private fun permitted(): Boolean = Settings.canDrawOverlays(context)
    private fun interactive(): Boolean = context.getSystemService(PowerManager::class.java).isInteractive &&
        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    override fun close() {
        closed = true
        hide()
        application.unregisterActivityLifecycleCallbacks(this)
        startedActivities.clear()
    }

    override fun onActivityStarted(activity: Activity) { startedActivities.add(activity); hide() }
    override fun onActivityStopped(activity: Activity) { startedActivities.remove(activity) }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) { startedActivities.remove(activity) }

    private companion object {
        val EVENT_STATES = setOf(ClipboardGuardStatus.CHANGED, ClipboardGuardStatus.CLEARED, ClipboardGuardStatus.BLOCKED, ClipboardGuardStatus.FAILED)
    }
}
