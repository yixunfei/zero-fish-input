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
    private val onDraftActivity: () -> Unit = {},
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
    private var keyBits = "256"
    private var replaceBits = true
    private var factor: ByteArray? = null
    private var prepared: String? = null
    private var boundCommit: ((String) -> Boolean)? = null
    private var insertOnReady = false
    private var result: CharArray? = null
    private var outputs: StegoOutputs? = null
    private val options = StegoOptions()
    private val dialogs = StegoOptionsDialog(view, ::current).apply { attachToIme = isKeyboard }
    @Volatile private var pending: StegoCryptoOperation? = null
    private var vaultGeneration = 0L
    private var workspaceGeneration = 0L
    private val valid = AtomicBoolean(false)
    private val expire = object : Runnable {
        override fun run() {
            if (!current()) { notify(R.string.stego_expired); close(); view.closeAuxiliaryInput(); return }
            if (gate.phase == Phase.EDITING && gate.remainingMillis() <= 30_000) render()
            main.postDelayed(this, 1000)
        }
    }

    fun open(language: InputLanguage, chinese: ChineseInputOptions, carrier: String? = null, file: ByteArray? = null) {
        close()
        gate.open()
        valid.set(true)
        val defaults = graph().settings.stegoDefaults
        options.stages = defaults.stages.toList()
        options.mode = MessageCredentialMode.PASSPHRASE
        options.kdf = MessageKdf.STANDARD
        options.transport = StegoTransport.DENSE_UNICODE
        keyBits = defaults.masterBits.toString(); replaceBits = true
        vaultGeneration = graph().secureClipboard.captureGeneration()
        decrypt = carrier != null
        reveal = false
        notice = null
        field = if (decrypt) StegoField.CREDENTIAL else StegoField.COVER
        factor = file?.copyOf()
        if (file != null) options.mode = MessageCredentialMode.KEY_FILE
        val generation = workspaceGeneration
        view.textStegoPanel.onAction = { if (generation == workspaceGeneration) act(it) }
        view.textStegoPanel.onCaret = { if (generation == workspaceGeneration) type(it) }
        view.textStegoPanel.onSelectAlgorithm = { id, selected ->
            if (generation == workspaceGeneration) updateAlgorithm(id) { options.select(it, selected) }
        }
        view.textStegoPanel.onAlgorithmWidth = { id ->
            if (generation == workspaceGeneration) updateAlgorithm(id, options::cycleWidth)
        }
        view.textStegoPanel.onAlgorithmEarlier = { id ->
            if (generation == workspaceGeneration) updateAlgorithm(id, options::moveEarlier)
        }
        view.textStegoPanel.onPasteRequested = { target ->
            if (generation == workspaceGeneration) pasteMenu(target)
        }
        view.textStegoPanel.onKeyBits = { bits ->
            if (generation == workspaceGeneration && editable() && !decrypt && isRandomMode()) {
                touch(); keyBits = bits.toString(); replaceBits = true; render()
            }
        }
        for (target in listOf(StegoField.COVER, StegoField.SECRET, StegoField.CARRIER)) {
            val max = when (target) { StegoField.COVER -> 512; StegoField.SECRET -> 2048; else -> 8192 }
            val draft = LocalDraftInput(graph, { main.post(it) }, main::removeCallbacks,
                render = { _, _, state -> states[target] = state; if (field == target) render() }, maxChars = max,
                onLengthLimit = { notify(R.string.stego_length_limit) }, renderCaret = { carets[target] = it },
                maxUtf8Bytes = StegoPastePolicy.maxBytes(target), emitCommittedText = false,
                preserveTextOnRejectedBackspace = true)
            drafts[target] = draft
            draft.start(if (target == StegoField.CARRIER) InputLanguage.ENGLISH else language,
                chinese.copy(keyboardLayout = ChineseKeyboardLayout.FULL, doublePinyinScheme = DoublePinyinScheme.OFF))
        }
        if (carrier != null) {
            drafts[StegoField.CARRIER]?.replaceCommittedText(carrier)
            try {
                val chars = carrier.toCharArray()
                val requirements = try { inspectCarrier(chars) } finally { chars.fill('\u0000') }
                options.mode = requirements.mode; options.kdf = requirements.kdf
                field = if (StegoCarrierCodec.isDetached(carrier)) StegoField.COVER
                    else if (options.mode == MessageCredentialMode.KEY_FILE) StegoField.CARRIER else StegoField.CREDENTIAL
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
        notice = null
        if (field == StegoField.KEY_BITS) {
            when (action) {
                is KeyboardAction.Text -> typeBits(action.value)
                is KeyboardAction.LiteralText -> typeBits(action.value)
                KeyboardAction.Backspace -> { keyBits = if (replaceBits) "" else keyBits.dropLast(1); replaceBits = false }
                else -> Unit
            }
            touch(); render(); return
        }
        reveal = if (field == StegoField.CREDENTIAL) false else reveal
        touch()
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

    fun command(command: InputCommand) {
        if (editable() && field != StegoField.CREDENTIAL) { notice = null; touch(); drafts[field]?.command(command) }
    }
    fun clearComposition(): Boolean {
        if (!editable()) return false
        notice = null; touch()
        return drafts[field]?.clearComposition() == true
    }

    private fun appendCredential(value: String) {
        if (credential.length + value.length <= 1024 && value.none { it == '\u0000' }) { credential.append(value); credentialLimit = false }
        else { credentialLimit = true; notify(R.string.stego_failed) }
    }

    private fun typeBits(value: String) {
        if (value.isEmpty() || value.any { it !in '0'..'9' }) return
        val next = if (replaceBits) value else keyBits + value
        if (next.length <= 4) { keyBits = next; replaceBits = false }
    }

    private fun isRandomMode(): Boolean = options.mode == MessageCredentialMode.RANDOM_KEY ||
        options.mode == MessageCredentialMode.RANDOM_KEY_FILE

    private fun pasteMenu(target: StegoField) {
        if (!editable() || !pasteTargetAllowed(target)) return
        val defaults = graph().settings.stegoDefaults
        val sources = buildList {
            if (defaults.vaultPaste && graph().settings.secureClipboardEnabled) add(R.string.stego_paste_vault)
            if (defaults.systemPaste) add(R.string.stego_paste_system)
        }
        if (sources.isEmpty()) return
        touch()
        dialogs.choose(R.string.stego_paste_title, sources.map(view.context::getString)) { source ->
            if (!editable() || !pasteTargetAllowed(target)) return@choose
            val settings = graph().settings.stegoDefaults
            if (sources[source] == R.string.stego_paste_system) {
                if (settings.systemPaste) pasteSystem(target)
            } else if (settings.vaultPaste) onSource(when (target) {
                StegoField.COVER -> StegoAction.VAULT_COVER
                StegoField.SECRET -> StegoAction.VAULT_SECRET
                else -> StegoAction.VAULT
            })
        }
    }

    private fun pasteSystem(target: StegoField) {
        if (!current() || !graph().settings.stegoDefaults.systemPaste) return
        val token = gate.begin() ?: return
        val lease = StegoCryptoOperation(token, SystemClock.elapsedRealtime() + 30_000)
        pending = lease
        val owner = WeakReference(this)
        val handler = main
        val context = view.context.applicationContext
        val alive = valid
        val vault = graph().secureClipboard
        val expectedVaultGeneration = vaultGeneration
        render()
        try {
            StegoSourceWorker.executor.execute {
                val value = StegoSystemPaste(context).read(target) {
                    lease.current() && alive.get() && expectedVaultGeneration == vault.captureGeneration() &&
                        dev.zeroinput.ime.input.isPrivateDraftDeviceAvailable(context)
                }
                if (!handler.post { owner.get()?.acceptSystemPaste(lease, target, value) ?: lease.close() }) lease.close()
            }
        } catch (_: RejectedExecutionException) {
            lease.close(); pending = null; gate.fail(token); notify(R.string.stego_worker_busy); render()
        }
    }

    private fun acceptSystemPaste(lease: StegoCryptoOperation, target: StegoField, value: String?) {
        if (pending !== lease) { lease.close(); return }
        if (!current() || !gate.accepts(lease.token)) { lease.close(); return }
        val timely = lease.current()
        pending = null; gate.fail(lease.token); lease.close()
        if (!timely || !graph().settings.stegoDefaults.systemPaste || value == null || !pasteText(target, value))
            notify(R.string.stego_paste_failed)
        render()
    }

    private fun pasteTargetAllowed(target: StegoField): Boolean = when (target) {
        StegoField.COVER -> !decrypt || StegoCarrierCodec.isDetached(drafts[StegoField.CARRIER]?.committedText().orEmpty())
        StegoField.CARRIER -> decrypt
        StegoField.SECRET -> !decrypt
        else -> false
    }

    fun pasteText(target: StegoField, value: String): Boolean {
        if (!editable() || !pasteTargetAllowed(target)) return false
        if (target == StegoField.CARRIER) return importCarrier(value)
        if (!StegoPastePolicy.accepts(target, value)) return false
        touch()
        notice = null
        reveal = false
        val accepted = drafts[target]?.replaceCommittedText(value) == true
        if (accepted) field = target
        render(); return accepted
    }

    private fun generateKey() {
        if (!editable() || decrypt || !isRandomMode()) return
        val bits = keyBits.toIntOrNull() ?: 0
        if (!MessageRandomKey.validBits(bits)) { notify(R.string.stego_invalid_bits); return }
        val token = gate.begin() ?: return
        val generation = workspaceGeneration
        val owner = WeakReference(this)
        val handler = main
        render()
        try {
            StegoCryptoWorker.executor.execute {
                val chars = try { MessageRandomKey.generate(bits) } catch (_: Exception) { null }
                if (!handler.post {
                    try { owner.get()?.acceptGeneratedKey(generation, token, chars) }
                    finally { chars?.fill('\u0000') }
                }) chars?.fill('\u0000')
            }
        } catch (_: RejectedExecutionException) { gate.fail(token); notify(R.string.stego_worker_busy); render() }
    }

    private fun acceptGeneratedKey(generation: Long, token: Long, chars: CharArray?) {
        if (generation != workspaceGeneration || !current() || !gate.accepts(token)) return
        gate.fail(token)
        if (chars == null) notify(R.string.stego_failed)
        else { clearCredential(); chars.forEach(credential::append); field = StegoField.CREDENTIAL; reveal = false }
        render()
    }

    fun importCarrier(value: String): Boolean {
        if (!editable()) return false
        return try {
            val carrier = StegoCarrierCodec.inspectable(value)
            val requirement = try { MessageEnvelope().inspect(carrier.envelope) } finally { carrier.envelope.fill(0) }
            clearSensitive()
            options.mode = requirement.mode
            options.kdf = requirement.kdf
            decrypt = true
            field = if (StegoCarrierCodec.isDetached(value)) StegoField.COVER
                else if (options.mode == MessageCredentialMode.KEY_FILE) StegoField.CARRIER else StegoField.CREDENTIAL
            drafts[StegoField.CARRIER]?.replaceCommittedText(value) == true
        } catch (_: Exception) { notify(R.string.stego_failed); false }.also { render() }
    }

    /** Only a validated detached encrypted block may accompany a cover-source detour. */
    fun detachedCarrierForPaste(): CharArray? {
        if (!editable() || !decrypt) return null
        val value = drafts[StegoField.CARRIER]?.committedText() ?: return null
        if (!StegoCarrierCodec.isDetached(value)) return null
        val chars = value.toCharArray()
        return try { inspectCarrier(chars); chars } catch (_: Exception) { chars.fill('\u0000'); null }
    }

    private fun act(action: StegoAction) {
        if (action == StegoAction.CLOSE) { notify(R.string.stego_cleared); close(); view.returnToKeyboard(); return }
        if (!current()) { close(); view.closeAuxiliaryInput(); return }
        when (action) {
            StegoAction.CONFIRM -> if (gate.phase == Phase.EDITING) prepare(true) else submit(false)
            StegoAction.INSERT -> if (gate.phase == Phase.RESULT)
                dialogs.confirm(R.string.stego_insert_warning) { submit(true) }
            StegoAction.SAVE -> save(StegoOutput.PLAINTEXT)
            StegoAction.SAVE_COVER -> save(StegoOutput.COVER)
            StegoAction.SAVE_CIPHERTEXT -> save(StegoOutput.CIPHERTEXT)
            StegoAction.SAVE_CARRIER -> save(StegoOutput.CARRIER)
            StegoAction.SAVE_FIELD -> saveField()
            StegoAction.EDIT -> dialogs.confirm(R.string.stego_edit_warning) {
                clearPrepared(); gate.edit(); notice = null; render()
            }
            StegoAction.REVEAL -> { touch(); reveal = !reveal; render() }
            else -> if (editable()) editAction(action)
        }
    }

    private fun editAction(action: StegoAction) {
        touch()
        notice = null
        when (action) {
            StegoAction.ENCRYPT, StegoAction.DECRYPT -> {
                if (decrypt == (action == StegoAction.DECRYPT)) return
                decrypt = action == StegoAction.DECRYPT
                field = if (decrypt) StegoField.CARRIER else StegoField.COVER
                clearSensitive()
            }
            StegoAction.COVER -> field = StegoField.COVER
            StegoAction.SECRET -> field = StegoField.SECRET
            StegoAction.CREDENTIAL -> field = StegoField.CREDENTIAL
            StegoAction.KEY_BITS -> { field = StegoField.KEY_BITS; replaceBits = true }
            StegoAction.GENERATE_KEY -> generateKey()
            StegoAction.CREDENTIAL_MODE -> if (!decrypt) dialogs.mode(options) {
                clearCredential(); field = if (options.mode == MessageCredentialMode.KEY_FILE) StegoField.COVER else StegoField.CREDENTIAL
                render()
            }
            StegoAction.CARRIER -> field = StegoField.CARRIER
            StegoAction.OPTIONS -> dialogs.show(options, decrypt, ::render) {
                clearCredential()
                if (options.mode == MessageCredentialMode.KEY_FILE && field == StegoField.CREDENTIAL)
                    field = if (decrypt) StegoField.CARRIER else StegoField.COVER
                notify(R.string.stego_credentials_changed)
            }
            StegoAction.NEXT -> nextField()
            StegoAction.CLEAR_FIELD -> clearField()
            StegoAction.PASTE_FIELD -> pasteMenu(field)
            StegoAction.PREPARE -> prepare()
            StegoAction.SELECTION -> onSource(action)
            StegoAction.VAULT, StegoAction.VAULT_COVER, StegoAction.VAULT_SECRET ->
                if (graph().settings.stegoDefaults.vaultPaste) onSource(action)
            StegoAction.KEY_FILE -> dialogs.confirm(R.string.stego_file_warning) {
                close(); onSource(StegoAction.KEY_FILE)
            }
            else -> Unit
        }
        reveal = false
        render()
    }

    private fun updateAlgorithm(id: Int, update: (MessageCipher) -> Unit) {
        if (!editable() || decrypt) return
        val cipher = MessageCipher.entries.firstOrNull { it.id == id } ?: return
        touch(); notice = null; update(cipher); render()
    }

    private fun clearField() {
        val target = field
        val clear = {
            if (editable() && field == target) {
                touch(); notice = null; reveal = false
                when (target) {
                    StegoField.CREDENTIAL -> clearCredential()
                    StegoField.KEY_BITS -> { keyBits = ""; replaceBits = true }
                    else -> drafts[target]?.clear()
                }
                render()
            }
        }
        if (target == StegoField.SECRET || target == StegoField.CREDENTIAL)
            dialogs.confirm(R.string.stego_clear_field_warning, clear)
        else clear()
    }

    private fun nextField() {
        if (decrypt && field == StegoField.CARRIER) {
            val chars = submittedField(field) ?: return
            try {
                val requirements = inspectCarrier(chars)
                options.mode = requirements.mode; options.kdf = requirements.kdf
            } catch (_: Exception) { notify(R.string.stego_failed); return }
            finally { chars.fill('\u0000') }
        }
        field = if (decrypt) {
            when {
                field == StegoField.CARRIER && StegoCarrierCodec.isDetached(drafts[field]?.committedText().orEmpty()) -> StegoField.COVER
                field != StegoField.CREDENTIAL && options.mode != MessageCredentialMode.KEY_FILE -> StegoField.CREDENTIAL
                else -> StegoField.CARRIER
            }
        } else when (field) {
            StegoField.COVER -> StegoField.SECRET
            StegoField.SECRET -> if (options.mode == MessageCredentialMode.KEY_FILE) StegoField.COVER else StegoField.CREDENTIAL
            else -> StegoField.COVER
        }
    }

    private fun save(kind: StegoOutput) {
        if (gate.phase != Phase.PREVIEW && gate.phase != Phase.RESULT) return
        val chars = outputs?.copy(kind) ?: return
        transferSave(chars, gate.phase)
    }

    private fun saveField() {
        if (!editable() || field == StegoField.CREDENTIAL || field == StegoField.KEY_BITS) return
        val chars = submittedField(field) ?: return
        if (chars.isEmpty()) return
        transferSave(chars, Phase.EDITING)
    }

    private fun transferSave(chars: CharArray, phase: Phase) {
        if (current() && gate.consume(phase)) { close(); onSave(chars) }
        else chars.fill('\u0000')
    }

    private fun prepare(insert: Boolean = false) {
        if (!editable()) return
        touch()
        if (!decrypt && options.stages.isEmpty()) { notify(R.string.stego_choose_algorithm); render(); return }
        if (insert && (decrypt || !isKeyboard || commitTarget() == null)) {
            notify(R.string.stego_commit_unavailable); render(); return
        }
        if (credentialLimit) { notify(R.string.stego_failed); return }
        val kdf = try {
            if (decrypt) {
                val carrier = submittedField(StegoField.CARRIER) ?: return
                val requirements = try { inspectCarrier(carrier) } finally { carrier.fill('\u0000') }
                options.mode = requirements.mode
                options.kdf = requirements.kdf
                render()
                requirements.kdf
            } else if (options.mode.password) options.kdf else MessageKdf.NONE
        } catch (_: Exception) { notify(R.string.stego_failed); return }
        if (options.mode.file && factor == null) { notify(R.string.stego_file_missing); return }
        if (kdf != MessageKdf.NONE && !StegoOptions.available(kdf)) { notify(R.string.stego_memory_unavailable); return }
        if (kdf == MessageKdf.INCREASED || kdf == MessageKdf.HIGH_MEMORY)
            dialogs.confirm(R.string.stego_cost_warning) { enqueue(kdf, insert) }
        else enqueue(kdf, insert)
    }

    private fun enqueue(kdf: MessageKdf, insert: Boolean) {
        if (!editable()) return
        val cover = submittedField(StegoField.COVER) ?: return
        val secret = submittedField(StegoField.SECRET) ?: run {
            cover.fill('\u0000'); return
        }
        val carrier = submittedField(StegoField.CARRIER) ?: run {
            cover.fill('\u0000'); secret.fill('\u0000'); return
        }
        val invalid = when {
            !decrypt && cover.isEmpty() -> R.string.stego_cover_required
            !decrypt && secret.isEmpty() -> R.string.stego_secret_required
            !decrypt && options.mode.password && Character.codePointCount(credential, 0, credential.length) < 12 -> R.string.stego_passphrase_required
            else -> null
        }
        if (invalid != null) {
            cover.fill('\u0000'); secret.fill('\u0000'); carrier.fill('\u0000')
            notify(invalid); render(); return
        }
        // Recheck the exact owned buffer handed to the worker, including after a cost dialog.
        if (decrypt) try {
            val requirements = inspectCarrier(carrier, String(cover))
            if (requirements.mode != options.mode || requirements.kdf != kdf) throw MessageFailure()
        } catch (_: Exception) {
            cover.fill('\u0000'); secret.fill('\u0000'); carrier.fill('\u0000')
            notify(R.string.stego_failed); render(); return
        }
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
        insertOnReady = insert
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

    private fun inspectCarrier(chars: CharArray, exactCover: String? = null): MessageRequirements {
        val decoded = if (exactCover == null) StegoCarrierCodec.inspectable(String(chars))
            else StegoCarrierCodec.decode(String(chars), exactCover)
        return try { MessageEnvelope().inspect(decoded.envelope) } finally { decoded.envelope.fill(0) }
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
            outputs = delivery.outputs; delivery.outputs = null
            reveal = false
            clearCredential()
            factor?.fill(0); factor = null
            drafts[StegoField.SECRET]?.clear()
        }
        delivery.close()
        if (insertOnReady && gate.phase == Phase.PREVIEW) { submit(false); return }
        render()
    }

    private fun submit(plaintext: Boolean) {
        if (!current()) return
        val target = if (plaintext) commitTarget() else boundCommit
        val text = if (plaintext) result?.let(::String) else prepared
        if (target == null || text == null) { notify(R.string.stego_commit_unavailable); render(); return }
        if (!gate.consume(if (plaintext) Phase.RESULT else Phase.PREVIEW)) return
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
            hidden && !reveal -> "•".repeat((drafts[field]?.length ?: 0).coerceAtMost(32))
            else -> fieldDisplay(field).text
        }
        val status = when {
            busy -> view.context.getString(R.string.stego_busy)
            preview -> view.context.getString(R.string.stego_preview, text, prepared?.length ?: 0,
                prepared?.let(StegoCarrierCodec::utf8Length) ?: 0)
            hasResult -> view.context.getString(R.string.stego_result)
            gate.remainingMillis() <= 30_000 -> {
                val seconds = ((gate.remainingMillis() + 999) / 1000).toInt()
                view.resources.getQuantityString(R.plurals.stego_expiry_warning, seconds, seconds)
            }
            field == StegoField.CREDENTIAL && notice == null -> view.context.getString(
                if (isRandomMode()) R.string.stego_random_key_help else R.string.stego_credential_input_help)
            else -> view.context.getString(notice ?: if (decrypt) R.string.stego_shared_ready else R.string.stego_intro)
        }
        val detached = decrypt && StegoCarrierCodec.isDetached(drafts[StegoField.CARRIER]?.committedText().orEmpty())
        view.textStegoPanel.render(StegoUiState(decrypt, field, text, busy, preview, reveal, hasResult,
            isKeyboard, if (detached && !hasResult && !busy && notice == null) view.context.getString(R.string.stego_detached_hint) else status,
            if (decrypt) view.context.getString(R.string.stego_decrypt_options_fixed) else options.summary(view, factor != null),
            options.mode != MessageCredentialMode.KEY_FILE,
            options.mode.file, detached, field != StegoField.CREDENTIAL &&
                ((drafts[field]?.length ?: 0) > 0 || states[field]?.snapshot?.isComposing == true),
            fields = fieldDisplays(), algorithms = stegoAlgorithmRows(options.stages),
            randomKey = isRandomMode(), credentialMode = view.context.getString(StegoOptions.MODE_LABELS[options.mode.ordinal]),
            canPaste = graph().settings.stegoDefaults.let { it.vaultPaste && graph().settings.secureClipboardEnabled || it.systemPaste },
            canVaultPaste = graph().settings.stegoDefaults.vaultPaste && graph().settings.secureClipboardEnabled),
            if (hidden && !reveal) null else carets[field])
        view.renderTextStegoInput(if (busy || preview || hasResult || field == StegoField.CREDENTIAL) null else states[field])
    }

    private fun fieldDisplays(): Map<StegoField, StegoFieldUi> = StegoField.entries.associateWith(::fieldDisplay)

    private fun fieldDisplay(target: StegoField): StegoFieldUi {
        val hidden = target == StegoField.SECRET || target == StegoField.CREDENTIAL
        if (hidden && !reveal) {
            val length = if (target == StegoField.CREDENTIAL) credential.length else drafts[target]?.length ?: 0
            return StegoFieldUi("•".repeat(length.coerceAtMost(32)))
        }
        val committed = when (target) {
            StegoField.KEY_BITS -> keyBits
            StegoField.CREDENTIAL -> credential
            else -> drafts[target]?.committedText().orEmpty()
        }
        val preedit = if (target == StegoField.CREDENTIAL) "" else states[target]?.snapshot?.composition.orEmpty()
        val caret = carets[target]
        val insertion = caret?.offset?.coerceIn(0, committed.length) ?: committed.length
        val text = StringBuilder(committed).insert(insertion, preedit).toString()
        return StegoFieldUi(text, caret, preedit.length)
    }

    private fun submittedField(target: StegoField): CharArray? {
        val draft = drafts[target] ?: return null
        val chars = draft.submittedChars()
        if (chars == null) {
            notify(if (draft.submissionBlockReason == LocalDraftInput.SubmissionBlockReason.LENGTH_LIMIT)
                R.string.stego_length_limit else R.string.stego_composition_unfinished)
            render()
        }
        return chars
    }

    private fun editable(): Boolean = current() && gate.phase == Phase.EDITING
    private fun touch() { gate.touch(); onDraftActivity() }
    private fun notify(id: Int) { notice = id; notifyUser(id) }
    private fun clearCredential() {
        for (i in credential.indices) credential.setCharAt(i, '\u0000')
        credential.setLength(0); credentialLimit = false
    }
    private fun clearSensitive() { clearCredential(); drafts[StegoField.SECRET]?.clear(); clearPrepared(); reveal = false }
    private fun clearPrepared() {
        prepared = null; boundCommit = null; insertOnReady = false; result?.fill('\u0000'); result = null
        outputs?.close(); outputs = null
    }

    override fun close() {
        workspaceGeneration++
        valid.set(false)
        gate.close(); pending?.close(); pending = null; main.removeCallbacks(expire)
        dialogs.close(); clearCredential(); factor?.fill(0); factor = null; clearPrepared()
        drafts.values.forEach(LocalDraftInput::close); drafts.clear(); states.clear(); carets.clear()
        view.textStegoPanel.clear()
        view.textStegoPanel.onAction = {}; view.textStegoPanel.onCaret = {}
        view.textStegoPanel.onSelectAlgorithm = { _, _ -> }; view.textStegoPanel.onAlgorithmWidth = {}
        view.textStegoPanel.onAlgorithmEarlier = {}
        view.textStegoPanel.onPasteRequested = {}; view.textStegoPanel.onKeyBits = {}
    }
}
