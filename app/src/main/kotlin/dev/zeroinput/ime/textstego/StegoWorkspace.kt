package dev.zeroinput.ime.textstego

import android.os.Handler
import android.os.SystemClock
import dev.zeroinput.engine.api.*
import dev.zeroinput.ime.AppGraph
import dev.zeroinput.ime.R
import dev.zeroinput.ime.core.InputCommand
import dev.zeroinput.ime.core.InputSessionState
import dev.zeroinput.ime.core.stego.*
import dev.zeroinput.ime.core.stego.StegoOperationGate.Phase
import dev.zeroinput.ime.input.LocalDraftInput
import dev.zeroinput.ime.ui.*
import dev.zeroinput.security.message.*
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.lang.ref.WeakReference

/** One protected foreground workspace, shared by the IME and the private import host. */
internal class StegoWorkspace(
    private val graph: () -> AppGraph,
    private val main: Handler,
    private val view: ZeroInputView,
    private val canUse: () -> Boolean,
    private val commitTarget: () -> ((String) -> Boolean)?,
    private val notifyUser: (Int) -> Unit,
    private val onSource: (StegoAction) -> Unit,
    private val onSave: (CharArray) -> Unit,
    private val isKeyboard: Boolean = true,
) : AutoCloseable {
    private val gate = StegoOperationGate(SystemClock::elapsedRealtime)
    private val drafts = mutableMapOf<StegoField, LocalDraftInput>()
    private val states = mutableMapOf<StegoField, InputSessionState>()
    private val carets = mutableMapOf<StegoField, DraftCaretUi>()
    private var field = StegoField.COVER
    private var decrypt = false
    private var reveal = false
    private var notice: Int? = null
    private val credential = StringBuilder(1024)
    private var credentialLimit = false
    private var factor: ByteArray? = null
    private var prepared: String? = null
    private var boundCommit: ((String) -> Boolean)? = null
    private var result: CharArray? = null
    private val options = StegoOptions()
    private val dialogs = StegoOptionsDialog(view, ::current).apply { attachToIme = isKeyboard }
    @Volatile private var pending: StegoCryptoOperation? = null
    private var vaultGeneration = 0L
    private val valid = AtomicBoolean(false)
    private val expire = object : Runnable {
        override fun run() {
            if (!current()) { notify(R.string.stego_expired); close(); view.closeAuxiliaryInput(); return }
            main.postDelayed(this, 1000)
        }
    }

    fun open(language: InputLanguage, chinese: ChineseInputOptions, carrier: String? = null, file: ByteArray? = null) {
        close()
        gate.open()
        valid.set(true)
        vaultGeneration = graph().secureClipboard.captureGeneration()
        decrypt = carrier != null
        notice = null
        field = if (decrypt) StegoField.CREDENTIAL else StegoField.COVER
        factor = file?.copyOf()
        if (file != null) options.mode = MessageCredentialMode.KEY_FILE
        view.textStegoPanel.onAction = ::act
        view.textStegoPanel.onCaret = ::type
        for (target in listOf(StegoField.COVER, StegoField.SECRET, StegoField.CARRIER)) {
            val max = when (target) { StegoField.COVER -> 512; StegoField.SECRET -> 2048; else -> 8192 }
            val draft = LocalDraftInput(graph, { main.post(it) }, main::removeCallbacks,
                render = { _, _, state -> states[target] = state; if (field == target) render() }, maxChars = max,
                onLengthLimit = { notify(R.string.stego_failed) }, renderCaret = { carets[target] = it })
            drafts[target] = draft
            draft.start(if (target == StegoField.CARRIER) InputLanguage.ENGLISH else language,
                chinese.copy(keyboardLayout = ChineseKeyboardLayout.FULL, doublePinyinScheme = DoublePinyinScheme.OFF))
        }
        if (carrier != null) {
            drafts[StegoField.CARRIER]?.replaceCommittedText(carrier)
            try {
                val parsed = StegoCarrierCodec.decode(carrier)
                val requirements = MessageEnvelope().inspect(parsed.envelope)
                options.mode = requirements.mode; options.kdf = requirements.kdf
                parsed.envelope.fill(0)
            } catch (_: Exception) { notify(R.string.stego_failed) }
        }
        main.postDelayed(expire, 1000)
        render()
    }

    fun current(): Boolean = valid.get() && gate.current() && canUse() && view.isTextStegoOpen &&
        vaultGeneration == graph().secureClipboard.captureGeneration()

    fun invalidate() { valid.set(false); pending?.close(); main.post { close() } }

    fun type(action: KeyboardAction) {
        if (!editable()) return
        reveal = if (field == StegoField.CREDENTIAL) false else reveal
        gate.touch()
        if (field == StegoField.CREDENTIAL) {
            when (action) {
                is KeyboardAction.Text -> appendCredential(action.value)
                is KeyboardAction.LiteralText -> appendCredential(action.value)
                is KeyboardAction.PairedText -> appendCredential(action.opening + action.closing)
                KeyboardAction.Space -> appendCredential(" ")
                KeyboardAction.Backspace -> if (credential.isNotEmpty()) {
                    credentialLimit = false
                    val end = credential.length
                    val start = Character.offsetByCodePoints(credential, end, -1)
                    for (i in start until end) credential.setCharAt(i, '\u0000')
                    credential.setLength(start)
                }
                else -> Unit
            }
            render()
        } else drafts[field]?.handle(action)
    }

    fun command(command: InputCommand) { if (editable() && field != StegoField.CREDENTIAL) drafts[field]?.command(command) }
    fun clearComposition(): Boolean = editable() && drafts[field]?.clearComposition() == true

    private fun appendCredential(value: String) {
        if (credential.length + value.length <= 1024 && value.none { it == '\u0000' }) { credential.append(value); credentialLimit = false }
        else { credentialLimit = true; notify(R.string.stego_failed) }
    }

    fun importCarrier(value: String): Boolean {
        if (!editable()) return false
        return try {
            val carrier = StegoCarrierCodec.decode(value)
            val requirement = MessageEnvelope().inspect(carrier.envelope)
            carrier.envelope.fill(0)
            clearSensitive()
            options.mode = requirement.mode
            options.kdf = requirement.kdf
            decrypt = true
            field = StegoField.CREDENTIAL
            drafts[StegoField.CARRIER]?.replaceCommittedText(value) == true
        } catch (_: Exception) { notify(R.string.stego_failed); false }.also { render() }
    }

    private fun act(action: StegoAction) {
        if (action == StegoAction.CLOSE) { close(); view.returnToKeyboard(); return }
        if (!current()) { close(); view.closeAuxiliaryInput(); return }
        when (action) {
            StegoAction.CONFIRM -> submit(false)
            StegoAction.INSERT -> if (gate.phase == Phase.RESULT)
                dialogs.confirm(R.string.stego_insert_warning) { submit(true) }
            StegoAction.SAVE -> if (gate.phase == Phase.RESULT) {
                val chars = result?.copyOf() ?: return
                if (gate.consume(Phase.RESULT)) { close(); onSave(chars) } else chars.fill('\u0000')
            }
            StegoAction.EDIT -> { clearPrepared(); gate.edit(); render() }
            StegoAction.REVEAL -> { reveal = !reveal; render() }
            else -> if (editable()) editAction(action)
        }
    }

    private fun editAction(action: StegoAction) {
        gate.touch()
        when (action) {
            StegoAction.ENCRYPT, StegoAction.DECRYPT -> {
                decrypt = action == StegoAction.DECRYPT
                field = if (decrypt) StegoField.CARRIER else StegoField.COVER
                clearSensitive()
            }
            StegoAction.COVER -> field = StegoField.COVER
            StegoAction.SECRET -> field = StegoField.SECRET
            StegoAction.CREDENTIAL -> field = StegoField.CREDENTIAL
            StegoAction.CARRIER -> field = StegoField.CARRIER
            StegoAction.OPTIONS -> dialogs.show(options, decrypt, ::render) { clearCredential(); notify(R.string.stego_credentials_changed) }
            StegoAction.PREPARE -> prepare()
            StegoAction.SELECTION, StegoAction.VAULT -> onSource(action)
            StegoAction.KEY_FILE -> dialogs.confirm(R.string.stego_file_warning) {
                close(); onSource(StegoAction.KEY_FILE)
            }
            else -> Unit
        }
        reveal = false
        render()
    }

    private fun prepare() {
        if (!editable()) return
        if (credentialLimit) { notify(R.string.stego_failed); return }
        val kdf = try {
            if (decrypt) {
                val decoded = StegoCarrierCodec.decode(drafts[StegoField.CARRIER]?.committedText().orEmpty())
                val requirements = MessageEnvelope().inspect(decoded.envelope)
                decoded.envelope.fill(0)
                if (options.mode != requirements.mode) throw MessageFailure()
                requirements.kdf
            } else if (options.mode.password) options.kdf else MessageKdf.NONE
        } catch (_: Exception) { notify(R.string.stego_failed); return }
        if (options.mode.file && factor == null) { notify(R.string.stego_file_missing); return }
        if (kdf != MessageKdf.NONE && !StegoOptions.available(kdf)) { notify(R.string.stego_memory_unavailable); return }
        if (kdf == MessageKdf.INCREASED || kdf == MessageKdf.HIGH_MEMORY)
            dialogs.confirm(R.string.stego_cost_warning) { enqueue(kdf) }
        else enqueue(kdf)
    }

    private fun enqueue(kdf: MessageKdf) {
        if (!editable()) return
        val cover = drafts[StegoField.COVER]?.submittedChars() ?: return
        val secret = drafts[StegoField.SECRET]?.submittedChars() ?: run { cover.fill('\u0000'); return }
        val carrier = drafts[StegoField.CARRIER]?.submittedChars() ?: run { cover.fill('\u0000'); secret.fill('\u0000'); return }
        val chars = CharArray(credential.length) { credential[it] }
        val master = MessageCredential(options.mode, chars, if (options.mode.file) factor else null)
        chars.fill('\u0000')
        val token = gate.begin() ?: run { master.close(); cover.fill('\u0000'); secret.fill('\u0000'); carrier.fill('\u0000'); return }
        val lease = StegoCryptoOperation(token, SystemClock.elapsedRealtime() + 120_000)
        pending = lease
        notice = null
        val isDecrypt = decrypt
        val stages = options.stages.toList()
        val transport = options.transport
        val target = if (!isDecrypt && isKeyboard) commitTarget() else null
        boundCommit = target
        val task = StegoCryptoTask(lease, WeakReference(this), main, isDecrypt, stages, transport, kdf,
            cover, secret, carrier, master)
        render()
        try {
            StegoCryptoWorker.executor.execute(task)
        } catch (_: RejectedExecutionException) {
            task.close(); lease.close(); boundCommit = null
            gate.fail(token); pending = null; notify(R.string.stego_worker_busy); render()
        }
    }

    fun publish(lease: StegoCryptoOperation) {
        if (!lease.current() || pending !== lease || !current() || !gate.accepts(lease.token)) { lease.close(); return }
        val delivery = lease.take() ?: run { lease.close(); return }
        val token = lease.token
        lease.close()
        pending = null
        if (delivery.encoded == null && delivery.result == null) {
            gate.fail(token); notify(if (delivery.memoryUnavailable) R.string.stego_memory_unavailable else R.string.stego_failed)
        } else if (gate.complete(token, delivery.result != null)) {
            prepared = delivery.encoded; result = delivery.result; delivery.result = null
            clearCredential()
            factor?.fill(0); factor = null
            drafts[StegoField.SECRET]?.clear()
        }
        delivery.close()
        render()
    }

    private fun submit(plaintext: Boolean) {
        if (!current()) return
        val target = if (plaintext) commitTarget() else boundCommit
        val text = if (plaintext) result?.let(::String) else prepared
        if (target == null || text == null || !gate.consume(if (plaintext) Phase.RESULT else Phase.PREVIEW)) return
        // Consume before calling an external editor: an uncertain commit is never retried.
        val accepted = target(text)
        close()
        view.returnToKeyboard()
        if (!accepted) notify(R.string.stego_commit_failed)
    }

    private fun render() {
        if (!gate.current() || !view.isTextStegoOpen) return
        val busy = gate.phase == Phase.WORKING
        val preview = gate.phase == Phase.PREVIEW
        val hasResult = gate.phase == Phase.RESULT
        val hidden = hasResult || field == StegoField.SECRET || field == StegoField.CREDENTIAL
        val text = when {
            hasResult -> if (reveal) result?.let(::String).orEmpty() else "•".repeat((result?.size ?: 0).coerceAtMost(32))
            preview -> drafts[StegoField.COVER]?.committedText().orEmpty()
            field == StegoField.CREDENTIAL -> if (reveal) credential.toString() else "•".repeat(credential.length.coerceAtMost(32))
            hidden && !reveal -> "•".repeat((drafts[field]?.committedText()?.length ?: 0).coerceAtMost(32))
            else -> drafts[field]?.committedText().orEmpty() + states[field]?.snapshot?.composition.orEmpty()
        }
        val status = when {
            busy -> view.context.getString(R.string.stego_busy)
            preview -> view.context.getString(R.string.stego_preview, text, prepared?.length ?: 0,
                prepared?.let(StegoCarrierCodec::utf8Length) ?: 0)
            hasResult -> view.context.getString(R.string.stego_result)
            else -> view.context.getString(notice ?: if (decrypt) R.string.stego_shared_ready else R.string.stego_intro)
        }
        view.textStegoPanel.render(StegoUiState(decrypt, field, text, busy, preview, reveal, hasResult,
            isKeyboard, status, options.summary(view, factor != null)),
            if (hidden && !reveal) null else carets[field])
        view.renderTextStegoInput(if (busy || preview || hasResult || field == StegoField.CREDENTIAL) null else states[field])
    }

    private fun editable(): Boolean = current() && gate.phase == Phase.EDITING
    private fun notify(id: Int) { notice = id; notifyUser(id) }
    private fun clearCredential() {
        for (i in credential.indices) credential.setCharAt(i, '\u0000')
        credential.setLength(0); credentialLimit = false
    }
    private fun clearSensitive() { clearCredential(); drafts[StegoField.SECRET]?.clear(); clearPrepared(); reveal = false }
    private fun clearPrepared() { prepared = null; boundCommit = null; result?.fill('\u0000'); result = null }

    override fun close() {
        valid.set(false)
        gate.close(); pending?.close(); pending = null; main.removeCallbacks(expire)
        dialogs.close(); clearCredential(); factor?.fill(0); factor = null; clearPrepared()
        drafts.values.forEach(LocalDraftInput::close); drafts.clear(); states.clear(); carets.clear()
        view.textStegoPanel.clear()
        view.textStegoPanel.onAction = {}; view.textStegoPanel.onCaret = {}
    }
}
