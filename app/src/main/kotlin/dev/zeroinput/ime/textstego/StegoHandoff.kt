package dev.zeroinput.ime.textstego

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dev.zeroinput.ime.settings.SettingsRepository
import dev.zeroinput.ime.clipboard.PasteEditorIdentity
import dev.zeroinput.ime.clipboard.PendingClipboardAddition
import java.util.UUID

/** One-use internal transfer. Only an opaque ID crosses an Intent. No editor/Activity/callback. */
internal class StegoHandoff(private val context: Context, settings: SettingsRepository,
                            private val generation: () -> Long) : AutoCloseable {
    enum class Kind { CARRIER, VAULT, FILE, SAVE }
    class Request(val kind: Kind, var text: CharArray? = null, val editor: PasteEditorIdentity? = null) : AutoCloseable {
        var generation = -1L
            internal set
        var addition: PendingClipboardAddition? = if (kind == Kind.SAVE) text?.let(::PendingClipboardAddition) else null
        init { if (kind == Kind.SAVE) text = null }
        override fun close() { text?.fill('\u0000'); text = null; addition?.close(); addition = null }
    }

    private val main = Handler(Looper.getMainLooper())
    private var id: String? = null
    private var request: Request? = null
    private var deadline = 0L
    private var file: ByteArray? = null
    private var fileEditor: PasteEditorIdentity? = null
    private var fileGeneration = -1L
    private val settingsObserver = settings.addChangeListener { close() }
    private var observingScreen = false
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { close() }
    }
    private val expire = Runnable { close() }

    @Synchronized fun put(value: Request): String {
        close()
        observeScreen()
        value.generation = generation()
        val token = UUID.randomUUID().toString()
        id = token; request = value; deadline = SystemClock.elapsedRealtime() + 30_000
        main.postDelayed(expire, 30_000)
        return token
    }

    @Synchronized fun take(token: String?): Request? {
        if (token == null || token != id || SystemClock.elapsedRealtime() >= deadline || request?.generation != generation()) { close(); return null }
        return request.also { request = null; id = null; main.removeCallbacks(expire); stopObservingScreen() }
    }

    @Synchronized fun offerFile(factor: ByteArray, editor: PasteEditorIdentity) {
        close()
        observeScreen()
        fileGeneration = generation()
        file = factor.copyOf(); fileEditor = editor; deadline = SystemClock.elapsedRealtime() + 30_000
        main.postDelayed(expire, 30_000)
    }

    @Synchronized fun takeFile(editor: PasteEditorIdentity?): ByteArray? {
        if (editor == null || fileEditor != editor || SystemClock.elapsedRealtime() >= deadline || fileGeneration != generation()) { close(); return null }
        return file.also { file = null; fileEditor = null; main.removeCallbacks(expire); stopObservingScreen() }
    }

    @Synchronized override fun close() {
        main.removeCallbacks(expire)
        request?.close(); request = null; id = null
        file?.fill(0); file = null; fileEditor = null
        stopObservingScreen()
    }

    private fun observeScreen() {
        if (observingScreen) return
        ContextCompat.registerReceiver(context, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        observingScreen = true
    }
    private fun stopObservingScreen() {
        if (!observingScreen) return
        context.unregisterReceiver(screenOff); observingScreen = false
    }
    fun destroy() { close(); settingsObserver.close() }
}
