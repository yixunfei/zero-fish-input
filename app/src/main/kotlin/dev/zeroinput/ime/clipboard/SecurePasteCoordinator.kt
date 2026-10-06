package dev.zeroinput.ime.clipboard

import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import dev.zeroinput.ime.ZeroInputService
import dev.zeroinput.ime.auth.AuthenticationBroker
import dev.zeroinput.ime.settings.SettingsRepository
import dev.zeroinput.security.AuthenticationGrant
import dev.zeroinput.userdata.SecureClipboardVault
import java.util.concurrent.CopyOnWriteArrayList

internal data class ConfirmedSecurePaste(val id: String, val generation: Long, val grant: AuthenticationGrant)

/** Application-owned unused authorization; does not retain an IME, editor or plaintext. */
internal class SecurePasteCoordinator(
    private val context: Context,
    private val settings: SettingsRepository,
    private val vault: SecureClipboardVault,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private var pending: SecurePasteConsent? = null
    private var authentication: AuthenticationBroker.RequestHandle? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val expire = Runnable { cancel() }
    private val settingsObserver = settings.addChangeListener { cancel() }
    private val defaultImeObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { cancel() }
    }
    private var observingIme = false

    fun deferBinding(identity: PasteEditorIdentity?, authenticationFinished: Boolean): Boolean = pending?.let {
        !it.authorized || (authenticationFinished && identity != it.source)
    } ?: false

    fun request(id: String, source: PasteEditorIdentity) {
        cancel()
        if (!settings.secureClipboardEnabled || !selectedIme()) return
        val consent = SecurePasteConsent(id, source, vault.captureGeneration(), SystemClock::elapsedRealtime)
        pending = consent
        context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD),
            false, defaultImeObserver)
        observingIme = true
        main.postDelayed(expire, 30_000L)
        authentication = AuthenticationBroker.requestCancellable(context) { grant ->
            if (pending === consent) {
                authentication = null
                if (!allowed(consent) || !consent.authorize(grant)) cancel()
                else {
                    main.removeCallbacks(expire)
                    main.postDelayed(expire, 30_000L)
                    listeners.forEach { it() }
                }
            }
        }
    }

    fun observe(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    fun bind(session: Long, identity: PasteEditorIdentity): Boolean {
        val consent = pending ?: return false
        if (!allowed(consent) || !consent.bind(session, identity)) { cancel(); return false }
        return consent.ready(session)
    }

    fun confirm(session: Long, identity: PasteEditorIdentity): ConfirmedSecurePaste? {
        val consent = pending ?: return null
        if (!allowed(consent) || consent.source != identity) { cancel(); return null }
        val grant = consent.confirm(session)
        val result = grant?.let { ConfirmedSecurePaste(consent.id, consent.generation, it) }
        cancel()
        return result
    }

    fun leaveEditor() {
        if (pending?.leaveEditor() == false) cancel()
    }

    fun editorChanged(identity: PasteEditorIdentity?) {
        if (pending?.editorChanged(identity) == false) cancel()
    }

    fun cancel() {
        val changed = pending != null
        pending?.close()
        pending = null
        authentication?.close()
        authentication = null
        main.removeCallbacks(expire)
        if (observingIme) {
            context.contentResolver.unregisterContentObserver(defaultImeObserver)
            observingIme = false
        }
        if (changed) listeners.forEach { it() }
    }

    private fun allowed(consent: SecurePasteConsent): Boolean = settings.secureClipboardEnabled &&
        consent.generation == vault.captureGeneration() && selectedIme()

    private fun selectedIme(): Boolean = try {
        ComponentName.unflattenFromString(Settings.Secure.getString(context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()) == ComponentName(context, ZeroInputService::class.java)
    } catch (_: RuntimeException) { false }

    override fun close() { cancel(); settingsObserver.close(); listeners.clear() }
}
