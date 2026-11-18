package dev.zeroinput.ime.textstego

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.*
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.auth.AuthenticationBroker
import dev.zeroinput.ime.input.isPrivateDraftDeviceAvailable
import dev.zeroinput.ime.ui.*
import dev.zeroinput.security.AuthenticationGrant
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.lang.ref.WeakReference

/** Private, input-only host. No plaintext result, credential or file URI is put in an Intent. */
class StegoSourceActivity : AppCompatActivity() {
    private val graph by lazy { (application as ZeroInputApplication).graph }
    private val main = Handler(Looper.getMainLooper())
    private var request: StegoHandoff.Request? = null
    private var workspace: StegoWorkspace? = null
    private var keyboard: ZeroInputView? = null
    private var authentication: AuthenticationBroker.RequestHandle? = null
    private var grant: AuthenticationGrant? = null
    private var authorizedSave: Boolean? = null
    private var selectedId: String? = null
    private var selectedUri: Uri? = null
    private var resumed = false
    private var detour = false
    private var enabling = false
    private val active = AtomicBoolean(true)
    @Volatile private var operation = 0L
    private var generation = 0L
    @Volatile private var deadline = 0L
    private var settingsObserver: AutoCloseable? = null
    private var dialog: StegoOptionsDialog? = null
    private val fileRead = AtomicReference<StegoKeyFileRead?>()
    private val expire = Runnable { discard() }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { discard() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setResult(RESULT_CANCELED)
        val incoming = intent
        val token = incoming.getStringExtra(EXTRA_TOKEN)
        intent = Intent()
        incoming.replaceExtras(null as Bundle?)
        request = if (savedInstanceState == null) graph.stegoHandoff.take(token) else null
        if (request == null || !isPrivateDraftDeviceAvailable(this)) { discard(); return }
        generation = requireNotNull(request).generation
        if (generation != graph.secureClipboard.captureGeneration()) { discard(); return }
        settingsObserver = graph.settings.addChangeListener { if (!enabling) main.post { discard() } }
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        resetDeadline(120_000)
        when (request?.kind) {
            StegoHandoff.Kind.CARRIER -> launchWorkspace()
            StegoHandoff.Kind.FILE -> showAction(R.string.stego_file_warning, R.string.stego_select_file, ::pickFile)
            StegoHandoff.Kind.VAULT -> showAction(R.string.stego_read_start, dev.zeroinput.ime.ui.R.string.stego_vault, ::listVault)
            StegoHandoff.Kind.SAVE -> showAction(R.string.stego_save_start, dev.zeroinput.ime.ui.R.string.stego_save) { authenticate(save = true) }
            null -> discard()
        }
    }

    override fun onResume() {
        super.onResume(); resumed = true
        if (!deviceReady()) discard() else showAuthorizedAction()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) showAuthorizedAction()
    }
    override fun onPause() {
        resumed = false
        workspace?.close(); keyboard?.closeAuxiliaryInput()
        if (!detour) discard()
        super.onPause()
    }

    @SuppressLint("MissingSuperCall")
    override fun onSaveInstanceState(outState: Bundle) { outState.clear() }

    override fun onDestroy() {
        active.set(false)
        main.removeCallbacks(expire)
        workspace?.close(); keyboard?.release(); dialog?.close()
        authentication?.close(); authentication = null; grant = null
        selectedUri = null
        closeFile()
        request?.close(); request = null
        settingsObserver?.close(); settingsObserver = null
        runCatching { unregisterReceiver(screenOff) }
        super.onDestroy()
    }

    private fun deviceReady(): Boolean = active.get() && !isFinishing &&
        SystemClock.elapsedRealtime() < deadline && isPrivateDraftDeviceAvailable(this)

    private fun current(): Boolean = deviceReady() && resumed &&
        generation == graph.secureClipboard.captureGeneration()

    private fun resetDeadline(duration: Long) {
        deadline = SystemClock.elapsedRealtime() + duration
        main.removeCallbacks(expire); main.postDelayed(expire, duration)
    }

    private fun launchWorkspace(file: ByteArray? = null) {
        workspace?.close(); keyboard?.release(); dialog?.close()
        val view = ZeroInputView(this)
        keyboard = view
        val controller = StegoWorkspace({ graph }, main, view, ::current, { null }, ::message,
            onSource = { action ->
                when (action) {
                    StegoAction.KEY_FILE -> pickFile()
                    StegoAction.VAULT -> { workspace?.close(); listVault() }
                    else -> message(R.string.stego_import_selection_failed)
                }
            }, onSave = { value ->
                request?.close(); request = StegoHandoff.Request(StegoHandoff.Kind.SAVE, value)
                showAction(R.string.stego_save_start, dev.zeroinput.ime.ui.R.string.stego_save) { authenticate(save = true) }
            }, isKeyboard = false)
        workspace = controller
        view.onKeyboardAction = controller::type
        view.onCandidateSelected = { index, id -> controller.command(dev.zeroinput.ime.core.InputCommand.SelectCandidate(index, id)) }
        view.onCandidatePageChanged = { controller.command(dev.zeroinput.ime.core.InputCommand.ChangeCandidatePage(it)) }
        view.onClearCompositionRequested = controller::clearComposition
        view.onHideKeyboardRequested = { discard() }
        view.onTextStegoVisibilityChanged = { if (!it && !detour && active.get()) discard() }
        view.openTextStego()
        val carrier = request?.text?.let(::String)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            isSaveEnabled = false
            addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            protectInsets(this)
        })
        controller.open(InputLanguage.CHINESE, ChineseInputOptions(), carrier, file)
        resetDeadline(120_000)
    }

    private fun listVault() {
        if (!current() || !graph.settings.secureClipboardEnabled) { message(R.string.stego_no_entries); return }
        workspace?.close()
        val expected = ++operation
        showBusy()
        val vault = graph.secureClipboard
        val owner = WeakReference(this)
        val handler = main
        enqueue {
            val ids = try { vault.summaries().map { it.id } } catch (_: Exception) { emptyList() }
            handler.post { owner.get()?.showVaultEntries(expected, ids) }
        }
    }

    private fun showVaultEntries(expected: Long, ids: List<String>) {
        if (expected != operation || !current()) return
        if (ids.isEmpty()) { message(R.string.stego_no_entries); discard(); return }
        val host = TextView(this)
        setContentView(host)
        val chooser = StegoOptionsDialog(host, ::current).apply { attachToIme = false }
        dialog = chooser
        chooser.choose(dev.zeroinput.ime.ui.R.string.stego_vault, ids.indices.map { getString(R.string.stego_entry, it + 1) }) {
            selectedId = ids[it]; authenticate(save = false)
        }
    }

    private fun authenticate(save: Boolean) {
        if (!current()) return
        detour = true
        workspace?.close(); keyboard?.closeAuxiliaryInput()
        showBusy()
        resetDeadline(30_000)
        authentication = AuthenticationBroker.requestCancellable(this) { result ->
            authentication = null
            if (!deviceReady()) { discard(); return@requestCancellable }
            if (result == null) { discard(); return@requestCancellable }
            if (generation != graph.secureClipboard.captureGeneration()) { discard(); return@requestCancellable }
            grant = result
            // The callback only stages a one-use grant; a foreground click starts the read/write.
            authorizedSave = save
            showAuthorizedAction()
        }
    }

    private fun showAuthorizedAction() {
        val save = authorizedSave ?: return
        if (!current() || !hasWindowFocus()) return
        authorizedSave = null
        detour = false
        showAction(if (save) R.string.stego_save_confirm else R.string.stego_read_confirm,
            if (save) R.string.stego_confirm_save else R.string.stego_confirm_read) { useGrant(save) }
    }

    private fun useGrant(save: Boolean) {
        if (!current()) return
        val authorization = grant ?: return
        grant = null
        val id = selectedId
        val addition = request?.addition
        if (save && addition == null || !save && id == null) { discard(); return }
        if (save && !graph.settings.secureClipboardEnabled) {
            enabling = true; graph.settings.secureClipboardEnabled = true; enabling = false
        }
        val expected = ++operation
        showBusy()
        val vault = graph.secureClipboard
        val settings = graph.settings
        val expectedGeneration = generation
        val expectedDeadline = deadline
        val alive = active
        val context = applicationContext
        val owner = WeakReference(this)
        val handler = main
        enqueue {
            var value: CharArray? = null
            val valid = { alive.get() && SystemClock.elapsedRealtime() < expectedDeadline &&
                expectedGeneration == vault.captureGeneration() && settings.secureClipboardEnabled && isPrivateDraftDeviceAvailable(context) }
            val success = try {
                if (!valid()) false
                else if (save) {
                    requireNotNull(addition).consume { vault.add("", it, authorization, expectedGeneration, valid) }; true
                } else {
                    value = vault.read(requireNotNull(id), authorization, expectedGeneration, valid)?.toCharArray()
                    value != null
                }
            } catch (_: Exception) { false }
            val delivered = value
            if (!valid() || !handler.post {
                val activity = owner.get()
                if (activity == null) delivered?.fill('\u0000') else activity.completeGrant(expected, success, save, delivered)
            }) delivered?.fill('\u0000')
        }
    }

    private fun completeGrant(expected: Long, success: Boolean, save: Boolean, value: CharArray?) {
        if (!current() || expected != operation) { value?.fill('\u0000'); return }
        if (success && !save && value != null) {
            request?.close(); request = StegoHandoff.Request(StegoHandoff.Kind.CARRIER, value)
            launchWorkspace()
        } else { value?.fill('\u0000'); message(if (success) R.string.clipboard_import_saved else R.string.stego_failed); discard() }
    }

    @Suppress("DEPRECATION")
    private fun pickFile() {
        if (!current()) return
        detour = true
        workspace?.close(); keyboard?.closeAuxiliaryInput()
        resetDeadline(120_000)
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), FILE_REQUEST)
        } catch (_: RuntimeException) { discard() }
    }

    @Deprecated("Uses the bounded legacy Activity result contract")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != FILE_REQUEST || resultCode != RESULT_OK || !deviceReady()) { discard(); return }
        detour = false
        val uri = data?.data
        if (uri?.scheme != "content" || data.clipData?.itemCount?.let { it != 1 } == true) { discard(); return }
        selectedUri = uri
        resetDeadline(30_000)
        showAction(R.string.stego_confirm_file, android.R.string.ok, ::readFile)
    }

    private fun readFile() {
        if (!current()) return
        val uri = selectedUri ?: return
        selectedUri = null
        val expected = ++operation
        resetDeadline(5000)
        val importDeadline = deadline
        showBusy()
        val alive = active
        val context = applicationContext
        val resolver = context.contentResolver
        val read = StegoKeyFileRead()
        fileRead.getAndSet(read)?.close()
        val owner = WeakReference(this)
        val handler = main
        enqueue {
            val valid = { alive.get() && SystemClock.elapsedRealtime() < importDeadline && isPrivateDraftDeviceAvailable(context) }
            val factor = read.read(resolver, uri, valid)
            if (!valid() || !handler.post {
                val activity = owner.get()
                if (activity == null) factor?.fill(0) else activity.completeFile(expected, factor)
            }) factor?.fill(0)
        }
    }

    private fun completeFile(expected: Long, factor: ByteArray?) {
        if (!current() || expected != operation || factor == null) { factor?.fill(0); message(R.string.stego_failed); discard(); return }
        try {
            val editor = request?.editor
            if (request?.kind == StegoHandoff.Kind.FILE && editor != null) {
                graph.stegoHandoff.offerFile(factor, editor)
                message(R.string.stego_file_imported); discard()
            } else { launchWorkspace(factor); message(R.string.stego_file_imported) }
        } finally { factor.fill(0) }
    }

    private fun closeFile() {
        fileRead.getAndSet(null)?.close()
    }

    private fun showBusy() { showAction(R.string.stego_busy, null) {} }
    private fun showAction(message: Int, action: Int?, click: () -> Unit) {
        dialog?.close()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            if (Build.VERSION.SDK_INT >= 30) importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
            addView(TextView(context).apply { setText(message); textSize = 17f; setPadding(24, 24, 24, 24) })
            if (action != null) addView(MaterialButton(context).apply {
                setText(action); minHeight = (48 * resources.displayMetrics.density).toInt()
                filterTouchesWhenObscured = true; setOnClickListener { if (current()) click() }
            })
            protectInsets(this)
        })
    }

    private fun protectInsets(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { owner, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            owner.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
    }

    private fun enqueue(action: () -> Unit) {
        try { StegoSourceWorker.executor.execute(action) }
        catch (_: RejectedExecutionException) { message(R.string.stego_worker_busy); discard() }
    }
    private fun message(id: Int) { Toast.makeText(this, id, Toast.LENGTH_LONG).show() }
    private fun discard() {
        active.set(false); operation++; grant = null; authorizedSave = null; selectedUri = null
        workspace?.close(); dialog?.close(); request?.close(); closeFile()
        finish()
    }

    companion object {
        const val EXTRA_TOKEN = "dev.zeroinput.ime.textstego.TOKEN"
        private const val FILE_REQUEST = 1
    }
}
