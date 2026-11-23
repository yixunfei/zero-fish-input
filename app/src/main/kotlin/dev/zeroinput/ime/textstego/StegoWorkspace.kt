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
    private val openLocalNote: ((CharArray?) -> Unit)? = null,
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
    private var wordingPreview: StegoWordingCarrier? = null
    private var boundCommit: ((String) -> Boolean)? = null
    private var insertOnReady = false
    private var result: CharArray? = null
    private var outputs: StegoOutputs? = null
    private val options = StegoOptions()
    var wordingProfile: StegoWordingProfile? = null
        private set
    private var randomStageWidths = false
    val naturalWordingSelected get() = wordingProfile != null
    private val mode get() = options.mode
    val localNoteSelected: Boolean get() = mode == MessageCredentialMode.LOCAL_BIOMETRIC_PASSWORD
    private val canChooseWording get() = StegoWordingCodec.available && !localNoteSelected
    val acceptsUnmarkedCarrier get() = decrypt && canChooseWording
    private val dialogs = StegoOptionsDialog(view, ::current) { draftInteraction }.apply {
        attachToIme = isKeyboard
        leaveForLocalNote = openLocalNote?.let { launch -> { launch(null) } }
    }
    @Volatile private var pending: StegoCryptoOperation? = null
    private var biometric: StegoBiometricPrompt? = null
    private var vaultGeneration = 0L
    private var workspaceGeneration = 0L
    private var draftInteraction = 0L
    private val valid = AtomicBoolean(false)
    private val expire = object : Runnable {
        override fun run() {
            if (!current()) { closeInactiveWorkspace(); return }
            if (gate.phase == Phase.EDITING && gate.remainingMillis() <= 30_000) render()
            main.postDelayed(this, 1000)
        }
    }

    fun open(language: InputLanguage, chinese: ChineseInputOptions, carrier: String? = null, file: ByteArray? = null,
             localNote: Boolean = false, wording: StegoWordingProfile? = null, fileUse: StegoWordingFile? = null) {
        close()
        gate.open()
        valid.set(true)
        val defaults = graph().settings.stegoDefaults
        options.stages = defaults.stages.toList()
        options.mode = if (localNote) MessageCredentialMode.LOCAL_BIOMETRIC_PASSWORD else MessageCredentialMode.PASSPHRASE
        options.kdf = MessageKdf.STANDARD
        options.transport = StegoTransport.DENSE_UNICODE
        wordingProfile = (fileUse?.profile ?: wording)?.takeIf { canChooseWording }
        if (naturalWordingSelected) options.stages = wordingStages()
        keyBits = defaults.masterBits.toString(); replaceBits = true
        vaultGeneration = graph().secureClipboard.captureGeneration()
        decrypt = carrier != null || fileUse?.decrypt == true
        reveal = false
        notice = null
        factor = file?.copyOf()
        if (file != null) options.mode = fileUse?.mode ?: MessageCredentialMode.KEY_FILE
        field = if (decrypt) {
            if (file != null && naturalWordingSelected) StegoField.CARRIER else StegoField.CREDENTIAL
        } else if (naturalWordingSelected) StegoField.SECRET else StegoField.COVER
        val generation = workspaceGeneration
        view.textStegoPanel.onAction = { if (generation == workspaceGeneration) act(it) }
        view.textStegoPanel.onCaret = { if (generation == workspaceGeneration) type(it) }
        view.textStegoPanel.onSelectAlgorithm = { id, selected ->
            if (generation == workspaceGeneration) updateAlgorithm(id) { options.select(it, selected) }
        }
        view.textStegoPanel.onAlgorithmWidth = { id ->
            if (generation == workspaceGeneration) updateAlgorithm(id) {
                options.cycleWidth(it); randomStageWidths = false
            }
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
        if (carrier != null) restoreCarrier(carrier, generation)
        main.postDelayed(expire, 1000)
        render()
    }

    private fun restoreCarrier(carrier: String, generation: Long) {
        if (!StegoPastePolicy.accepts(StegoField.CARRIER, carrier) ||
            drafts[StegoField.CARRIER]?.replaceCommittedText(carrier) != true) {
            field = StegoField.CARRIER
            notify(R.string.stego_carrier_invalid)
        } else if (naturalWordingSelected) {
            field = StegoField.CARRIER
            val interaction = draftInteraction
            if (current()) main.post {
                if (generation == workspaceGeneration && interaction == draftInteraction) inspectWordingDraft()
            }
        } else if (needsWordingChoice(carrier)) {
            field = StegoField.CARRIER
            notice = R.string.stego_choose_received_style
        } else try {
            val chars = carrier.toCharArray()
            val requirements = try { inspectCarrier(chars) } finally { chars.fill('\u0000') }
            applyExactRequirements(carrier, requirements)
        } catch (_: Exception) { field = StegoField.CARRIER; notify(R.string.stego_carrier_invalid) }
    }

    fun current(): Boolean = valid.get() && gate.current() && canUse() && view.isTextStegoOpen &&
        vaultGeneration == graph().secureClipboard.captureGeneration()

    fun invalidate() { valid.set(false); pending?.close(); main.post { close() } }

    fun dismissOptions(): Boolean = dialogs.dismissShowing()

    fun wordingFileUse(): StegoWordingFile? = wordingProfile?.takeIf { mode.file }?.let {
        StegoWordingFile(it, decrypt, mode)
    }

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
        val existingBytes = dev.zeroinput.ime.input.exactUtf8ByteLength(credential)
        val addedBytes = dev.zeroinput.ime.input.exactUtf8ByteLength(value)
        if (existingBytes != null && addedBytes != null && addedBytes <= MessageLimits.CREDENTIAL_BYTES - existingBytes &&
            value.none { it == '\u0000' }) {
            credential.append(value)
            credentialLimit = false
        } else {
            credentialLimit = true
            notify(R.string.stego_wording_credential_limit)
        }
    }

    private fun typeBits(value: String) {
        if (value.isEmpty() || value.any { it !in '0'..'9' }) return
        val next = if (replaceBits) value else keyBits + value
        if (next.length <= 4) { keyBits = next; replaceBits = false }
    }

    private fun isRandomMode(): Boolean = mode == MessageCredentialMode.RANDOM_KEY || mode == MessageCredentialMode.RANDOM_KEY_FILE

    /** A selection read may only return to the same receiving draft and carrier choice. */
    fun selectionAuthorization(): (() -> Boolean)? {
        if (!editable() || !decrypt) return null
        val generation = workspaceGeneration
        val interaction = draftInteraction
        return { editable() && workspaceGeneration == generation && draftInteraction == interaction }
    }

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
        when {
            !timely || !graph().settings.stegoDefaults.systemPaste -> notify(R.string.stego_paste_failed)
            value == null -> notify(R.string.stego_system_paste_no_text)
            !pasteText(target, value) && target != StegoField.CARRIER -> notify(R.string.stego_paste_failed)
        }
        render()
    }

    private fun pasteTargetAllowed(target: StegoField): Boolean = when (target) {
        StegoField.COVER -> !naturalWordingSelected &&
            (!decrypt || StegoCarrierCodec.isDetached(drafts[StegoField.CARRIER]?.committedText().orEmpty()))
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
        else {
            clearCredential()
            chars.forEach(credential::append)
            field = StegoField.CREDENTIAL
            reveal = false
            if (naturalWordingSelected) notice = R.string.stego_wording_key_ready
        }
        render()
    }

    fun importCarrier(value: String): Boolean {
        if (!editable()) return false
        if (!StegoPastePolicy.accepts(StegoField.CARRIER, value)) {
            notify(R.string.stego_carrier_invalid); render(); return false
        }
        val needsStyle = needsWordingChoice(value)
        val requirements = if (naturalWordingSelected || needsStyle) {
            if (!decrypt) return false
            null
        } else try {
            val parsed = StegoCarrierCodec.inspectable(value)
            try { MessageEnvelope().inspect(parsed.envelope) } finally { parsed.envelope.fill(0) }
        } catch (_: Exception) { notify(R.string.stego_carrier_invalid); render(); return false }
        if (drafts[StegoField.CARRIER]?.replaceCommittedText(value) != true) {
            notify(R.string.stego_carrier_invalid); render(); return false
        }
        touch(); clearSensitive()
        decrypt = true
        notice = if (needsStyle) R.string.stego_choose_received_style else null
        if (requirements != null) applyExactRequirements(value, requirements)
        else field = StegoField.CARRIER
        render()
        inspectWordingDraft()
        return true
    }

    private fun applyExactRequirements(carrier: String, requirements: MessageRequirements) {
        options.mode = requirements.mode
        options.kdf = requirements.kdf
        field = when {
            StegoCarrierCodec.isDetached(carrier) -> StegoField.COVER
            mode == MessageCredentialMode.KEY_FILE -> StegoField.CARRIER
            else -> StegoField.CREDENTIAL
        }
    }

    private fun needsWordingChoice(carrier: CharSequence): Boolean =
        acceptsUnmarkedCarrier && !naturalWordingSelected && carrier.isNotBlank() &&
            !StegoCarrierCodec.hasMarker(carrier)

    private fun requestWordingChoice(carrier: CharSequence): Boolean {
        if (!needsWordingChoice(carrier)) return false
        field = StegoField.CARRIER
        notice = R.string.stego_choose_received_style
        render()
        chooseWordingProfile()
        return true
    }

    /** Only a validated detached encrypted block may accompany a cover-source detour. */
    fun detachedCarrierForPaste(): CharArray? {
        if (!editable() || !decrypt || naturalWordingSelected) return null
        val value = drafts[StegoField.CARRIER]?.committedText() ?: return null
        if (!StegoCarrierCodec.isDetached(value)) return null
        val chars = value.toCharArray()
        return try { inspectCarrier(chars); chars } catch (_: Exception) { chars.fill('\u0000'); null }
    }

    private fun act(action: StegoAction) {
        if (action == StegoAction.CLOSE) { notify(R.string.stego_cleared); close(); view.returnToKeyboard(); return }
        if (!current()) { close(); view.closeAuxiliaryInput(); return }
        when (action) {
            StegoAction.CONFIRM -> if (gate.phase == Phase.EDITING) prepare(!naturalWordingSelected) else submit(false)
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
                field = if (decrypt) StegoField.CARRIER else if (naturalWordingSelected) StegoField.SECRET else StegoField.COVER
                clearSensitive()
            }
            StegoAction.COVER -> if (!naturalWordingSelected) field = StegoField.COVER
            StegoAction.RANDOM_POETRY -> if (!decrypt && field == StegoField.COVER) {
                val draft = drafts[field] ?: return
                if (states[field]?.snapshot?.isComposing == true) notify(R.string.stego_composition_unfinished)
                else if (!draft.replaceCommittedText(StegoPoetry.extend(draft.committedText())))
                    notify(R.string.stego_length_limit)
            }
            StegoAction.SECRET -> field = StegoField.SECRET
            StegoAction.CREDENTIAL -> field = StegoField.CREDENTIAL
            StegoAction.KEY_BITS -> { field = StegoField.KEY_BITS; replaceBits = true }
            StegoAction.GENERATE_KEY -> generateKey()
            StegoAction.CREDENTIAL_MODE -> if (!decrypt) dialogs.mode(options, portableOnly = naturalWordingSelected) {
                clearCredential()
                field = if (mode != MessageCredentialMode.KEY_FILE) StegoField.CREDENTIAL
                    else if (naturalWordingSelected) StegoField.SECRET else StegoField.COVER
                render()
            }
            StegoAction.CARRIER -> field = StegoField.CARRIER
            StegoAction.TRANSPORT -> chooseCarrier()
            StegoAction.OPTIONS -> if (naturalWordingSelected) {
                if (!decrypt && mode.password) dialogs.cost(options, ::render)
            } else dialogs.show(options, decrypt, ::chooseCarrier, ::render) {
                clearCredential()
                if (mode == MessageCredentialMode.KEY_FILE && field == StegoField.CREDENTIAL)
                    field = if (decrypt) StegoField.CARRIER else StegoField.COVER
                notify(R.string.stego_credentials_changed)
            }
            StegoAction.RANDOM_STAGE_WIDTHS -> if (naturalWordingSelected && !decrypt) randomStageWidths = !randomStageWidths
            StegoAction.NEXT -> nextField()
            StegoAction.CLEAR_FIELD -> clearField()
            StegoAction.PASTE_FIELD -> pasteMenu(field)
            StegoAction.PREPARE -> prepare()
            StegoAction.SELECTION -> onSource(action)
            StegoAction.VAULT, StegoAction.VAULT_COVER, StegoAction.VAULT_SECRET ->
                if (graph().settings.stegoDefaults.vaultPaste) onSource(action)
            StegoAction.KEY_FILE -> if (mode.file) dialogs.confirm(R.string.stego_file_warning) { onSource(StegoAction.KEY_FILE) }
            else -> Unit
        }
        reveal = false
        render()
    }

    private fun chooseCarrier() {
        val labels = if (decrypt) listOf(R.string.stego_exact_auto) else listOf(R.string.stego_dense, R.string.stego_visible)
        val choices = labels + if (canChooseWording) R.string.stego_wording_category else R.string.stego_wording_unavailable
        dialogs.choose(if (decrypt) R.string.stego_received_format else R.string.stego_transport,
            choices.map(view.context::getString), help = if (decrypt) R.string.stego_receive_help else R.string.stego_send_help) { index ->
            if (!editable()) return@choose
            when (val label = choices[index]) {
                R.string.stego_wording_unavailable -> dialogs.message(
                    if (localNoteSelected && StegoWordingCodec.available) R.string.stego_wording_local_note
                    else R.string.stego_wording_explanation)
                R.string.stego_wording_category -> chooseWordingProfile()
                else -> {
                    setWording(null)
                    if (!decrypt) options.transport = if (label == R.string.stego_dense) StegoTransport.DENSE_UNICODE
                        else StegoTransport.VISIBLE_BASE64
                    render()
                }
            }
        }
    }

    private fun chooseWordingProfile() {
        val profiles = StegoWordingProfile.entries
        dialogs.choose(R.string.stego_wording_theme, profiles.map { view.context.getString(it.label) }) { index ->
            setWording(profiles[index])
        }
    }

    private fun setWording(selected: StegoWordingProfile?) {
        if (!editable() || selected == wordingProfile || selected != null && !canChooseWording) return
        touch()
        val wasWording = naturalWordingSelected
        wordingProfile = selected
        if (wasWording != naturalWordingSelected) {
            options.mode = MessageCredentialMode.PASSPHRASE
            options.kdf = MessageKdf.STANDARD
            options.stages = if (naturalWordingSelected) wordingStages() else graph().settings.stegoDefaults.stages
            randomStageWidths = false
        }
        clearCredential(); factor?.fill(0); factor = null
        reveal = false; notice = null
        field = if (decrypt) StegoField.CARRIER else if (selected != null) StegoField.SECRET else StegoField.COVER
        render()
        inspectWordingDraft()
    }

    private fun inspectWordingDraft() {
        if (decrypt && naturalWordingSelected && (drafts[StegoField.CARRIER]?.length ?: 0) > 0)
            enqueue(MessageKdf.NONE, insert = false, inspectWording = true)
    }

    private fun wordingStages(): List<MessageStage> = listOf(MessageStage(MessageCipher.AES), MessageStage(MessageCipher.XCHACHA20))

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
        if (naturalWordingSelected) {
            if (decrypt && field == StegoField.CARRIER && (drafts[field]?.length ?: 0) > 0) {
                inspectWordingDraft()
                return
            }
            field = if (field == StegoField.CREDENTIAL || mode == MessageCredentialMode.KEY_FILE) {
                if (decrypt) StegoField.CARRIER else StegoField.SECRET
            } else StegoField.CREDENTIAL
            return
        }
        if (decrypt && field == StegoField.CARRIER) {
            val chars = submittedField(field) ?: return
            try {
                if (requestWordingChoice(java.nio.CharBuffer.wrap(chars))) return
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
        if (naturalWordingSelected) {
            prepareWording()
            return
        }
        if (!decrypt && options.stages.isEmpty()) { notify(R.string.stego_choose_algorithm); render(); return }
        if (insert && (decrypt || !isKeyboard || commitTarget() == null)) {
            notify(R.string.stego_commit_unavailable); render(); return
        }
        if (credentialLimit) { notify(R.string.stego_wording_credential_limit); render(); return }
        val kdf = try {
            if (decrypt) {
                val carrier = submittedField(StegoField.CARRIER) ?: return
                val requirements = try {
                    if (requestWordingChoice(java.nio.CharBuffer.wrap(carrier))) return
                    inspectCarrier(carrier)
                } finally { carrier.fill('\u0000') }
                options.mode = requirements.mode
                options.kdf = requirements.kdf
                render()
                requirements.kdf
            } else if (options.mode.password) options.kdf else MessageKdf.NONE
        } catch (_: Exception) { notify(R.string.stego_carrier_invalid); render(); return }
        if (options.mode.file && factor == null) { notify(R.string.stego_file_missing); return }
        if (isKeyboard && options.mode == MessageCredentialMode.LOCAL_BIOMETRIC_PASSWORD) {
            dialogs.confirm(R.string.stego_local_move) {
                moveEncryptedNote()
            }
            return
        }
        if (kdf != MessageKdf.NONE && !StegoOptions.available(kdf)) { notify(R.string.stego_memory_unavailable); return }
        if (kdf == MessageKdf.INCREASED || kdf == MessageKdf.HIGH_MEMORY)
            dialogs.confirm(R.string.stego_cost_warning) { enqueue(kdf, insert) }
        else enqueue(kdf, insert)
    }

    private fun prepareWording() {
        val input = if (decrypt) StegoField.CARRIER else StegoField.SECRET
        val draft = drafts[input] ?: return
        val credentialIssue = wordingCredentialIssue()
        val problem = when {
            draft.submissionBlockReason == LocalDraftInput.SubmissionBlockReason.LENGTH_LIMIT -> R.string.stego_length_limit
            draft.length == 0 && draft.submissionBlockReason == null ->
                if (decrypt) R.string.stego_wording_carrier_required else R.string.stego_secret_required
            !decrypt && options.stages.isEmpty() -> R.string.stego_choose_algorithm
            else -> null
        }
        if (problem != null) {
            field = input
            notice = problem
        } else if (!decrypt && credentialIssue != null) {
            if (credentialIssue != R.string.stego_file_missing) field = StegoField.CREDENTIAL
            notice = credentialIssue
        } else {
            val kdf = if (mode.password) options.kdf else MessageKdf.NONE
            if (credentialIssue == null && (kdf == MessageKdf.INCREASED || kdf == MessageKdf.HIGH_MEMORY)) {
                if (!StegoOptions.available(kdf)) { notify(R.string.stego_memory_unavailable); render() }
                else dialogs.confirm(R.string.stego_cost_warning) { enqueue(kdf, false) }
            } else enqueue(kdf, false)
            return
        }
        reveal = false
        render()
    }

    private fun wordingCredentialIssue(): Int? {
        if (mode.file && factor == null) return R.string.stego_file_missing
        if (mode == MessageCredentialMode.KEY_FILE) return null
        val bytes = dev.zeroinput.ime.input.exactUtf8ByteLength(credential)
        if (credentialLimit || bytes == null || bytes > MessageLimits.CREDENTIAL_BYTES) return R.string.stego_wording_credential_limit
        if (mode.password) return if (credential.isEmpty()) R.string.stego_wording_password_required else null
        if (credential.length in 43..683) return null
        return if (decrypt) R.string.stego_wording_receive_key else R.string.stego_wording_send_key
    }

    private fun moveEncryptedNote() {
        val launch = openLocalNote ?: return
        if (!editable() || !decrypt) return
        val carrier = submittedField(StegoField.CARRIER) ?: return
        val verified = try { inspectCarrier(carrier).mode == MessageCredentialMode.LOCAL_BIOMETRIC_PASSWORD }
            catch (_: Exception) { false }
        if (verified) launch(carrier) else { carrier.fill('\u0000'); notify(R.string.stego_failed) }
    }

    private fun authenticateLocal(kdf: MessageKdf, insert: Boolean) {
        val token = gate.begin() ?: return
        notice = R.string.stego_local_touch
        render()
        biometric = StegoBiometricPrompt(view.context, main) { mac ->
            biometric = null
            if (current() && gate.accepts(token)) {
                gate.fail(token)
                if (mac != null) enqueue(kdf, insert, mac)
                else { notify(R.string.stego_local_failed); render() }
            }
        }
        biometric?.start(!decrypt) { current() && gate.accepts(token) }
    }

    private fun enqueue(kdf: MessageKdf, insert: Boolean, mac: javax.crypto.Mac? = null, inspectWording: Boolean = false) {
        if (!editable()) return
        val cover = if (naturalWordingSelected) CharArray(0) else submittedField(StegoField.COVER) ?: return
        val secret = (if (naturalWordingSelected && decrypt) CharArray(0) else submittedField(StegoField.SECRET)) ?: run {
            cover.fill('\u0000'); return
        }
        val carrier = (if (naturalWordingSelected && !decrypt) CharArray(0) else submittedField(StegoField.CARRIER)) ?: run {
            cover.fill('\u0000'); secret.fill('\u0000'); return
        }
        val invalid = when {
            !decrypt && !naturalWordingSelected && cover.isEmpty() -> R.string.stego_cover_required
            !decrypt && secret.isEmpty() -> R.string.stego_secret_required
            decrypt && naturalWordingSelected && carrier.isEmpty() -> R.string.stego_wording_carrier_required
            !decrypt && !naturalWordingSelected && mode.password &&
                Character.codePointCount(credential, 0, credential.length) < 12 -> R.string.stego_passphrase_required
            else -> null
        }
        if (invalid != null) {
            cover.fill('\u0000'); secret.fill('\u0000'); carrier.fill('\u0000')
            notify(invalid); render(); return
        }
        // Recheck the exact owned buffer handed to the worker, including after a cost dialog.
        if (decrypt && !naturalWordingSelected) try {
            val requirements = inspectCarrier(carrier, String(cover))
            if (requirements.mode != options.mode || requirements.kdf != kdf) throw MessageFailure()
        } catch (_: Exception) {
            cover.fill('\u0000'); secret.fill('\u0000'); carrier.fill('\u0000')
            notify(R.string.stego_failed); render(); return
        }
        if (localNoteSelected && mac == null) {
            cover.fill('\u0000'); secret.fill('\u0000'); carrier.fill('\u0000')
            if (decrypt) dialogs.confirm(R.string.stego_local_warning) { authenticateLocal(kdf, insert) }
            else authenticateLocal(kdf, insert)
            return
        }
        val master = if (inspectWording) null else {
            val chars = CharArray(credential.length) { credential[it] }
            try { MessageCredential(mode, chars, if (mode.file) factor else null, mac) } finally { chars.fill('\u0000') }
        }
        val token = gate.begin() ?: run { master?.close(); cover.fill('\u0000'); secret.fill('\u0000'); carrier.fill('\u0000'); return }
        val lease = StegoCryptoOperation(token, SystemClock.elapsedRealtime() + 120_000)
        pending = lease
        notice = null
        val isDecrypt = decrypt
        val stages = options.stages.toList()
        val transport = options.transport
        val target = if (!isDecrypt && isKeyboard) commitTarget() else null
        boundCommit = target
        insertOnReady = insert
        val wordingAssets = if (naturalWordingSelected) view.context.applicationContext.assets else null
        val task = StegoCryptoTask(lease, WeakReference(this), main, isDecrypt, stages, transport, kdf,
            cover, secret, carrier, master, wordingProfile, wordingAssets, randomStageWidths,
            if (naturalWordingSelected && !inspectWording) wordingCredentialIssue() else null, inspectWording)
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
        if (pending !== lease) { lease.close(); return }
        if (!current()) { closeInactiveWorkspace(); return }
        val token = lease.token
        val delivery = if (gate.accepts(token)) lease.take() else null
        lease.close()
        pending = null
        if (delivery == null) {
            gate.fail(token)
            boundCommit = null
            insertOnReady = false
            notify(R.string.stego_failed)
            render()
            return
        }
        if (delivery.encoded == null && delivery.result == null) {
            gate.fail(token)
            if (gate.phase != Phase.EDITING) { delivery.close(); return }
            val requirements = delivery.requirements
            if (requirements != null) showWordingRequirements(requirements, delivery.inspectingWording)
            else {
                val failure = if (naturalWordingSelected && delivery.failure == R.string.stego_failed) {
                    if (decrypt) R.string.stego_wording_open_failed else R.string.stego_wording_generate_failed
                } else delivery.failure
                if (naturalWordingSelected && decrypt && failure != R.string.stego_file_missing &&
                    failure == wordingCredentialIssue()) field = StegoField.CREDENTIAL
                notify(failure)
            }
        } else if (gate.complete(token, delivery.result != null)) {
            prepared = delivery.encoded; result = delivery.result; delivery.result = null
            wordingPreview = delivery.wording
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

    private fun showWordingRequirements(requirements: MessageRequirements, inspectedOnly: Boolean) {
        if (mode != requirements.mode) clearCredential()
        options.mode = requirements.mode
        options.kdf = requirements.kdf
        reveal = false
        field = if (mode == MessageCredentialMode.KEY_FILE) StegoField.CARRIER else StegoField.CREDENTIAL
        val message = wordingCredentialIssue() ?: R.string.stego_wording_requirements.takeUnless { inspectedOnly }
        notice = message
        if (!inspectedOnly && message != null) notifyUser(message)
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
            preview -> wordingPreview?.text ?: drafts[StegoField.COVER]?.committedText().orEmpty()
            field == StegoField.CREDENTIAL -> if (reveal) credential.toString() else "•".repeat(credential.length.coerceAtMost(32))
            hidden && !reveal -> "•".repeat((drafts[field]?.length ?: 0).coerceAtMost(32))
            else -> fieldDisplay(field).text
        }
        val status = when {
            busy -> view.context.getString(if (notice == R.string.stego_local_touch) R.string.stego_local_touch else R.string.stego_busy)
            preview && naturalWordingSelected -> wordingPreview?.let(::wordingStatus).orEmpty()
            preview -> view.context.getString(R.string.stego_preview, text, prepared?.length ?: 0,
                prepared?.let(StegoCarrierCodec::utf8Length) ?: 0)
            hasResult -> view.context.getString(R.string.stego_result)
            gate.remainingMillis() <= 30_000 -> {
                val seconds = ((gate.remainingMillis() + 999) / 1000).toInt()
                view.resources.getQuantityString(R.plurals.stego_expiry_warning, seconds, seconds)
            }
            naturalWordingSelected && notice == null -> wordingHelp()
            field == StegoField.CREDENTIAL && notice == null -> view.context.getString(
                if (isRandomMode()) R.string.stego_random_key_help else R.string.stego_credential_input_help)
            else -> view.context.getString(notice ?: if (decrypt) R.string.stego_shared_ready else R.string.stego_intro)
        }
        val detached = decrypt && !naturalWordingSelected && StegoCarrierCodec.isDetached(drafts[StegoField.CARRIER]?.committedText().orEmpty())
        val pinNotice = decrypt || naturalWordingSelected || notice == R.string.stego_system_paste_no_text
        val actionNotice = if (pinNotice && !busy && !preview && !hasResult)
            notice?.let(view.context::getString).orEmpty() else ""
        view.textStegoPanel.render(StegoUiState(decrypt, field, text, busy, preview, reveal, hasResult,
            isKeyboard, if (detached && !hasResult && !busy && notice == null) view.context.getString(R.string.stego_detached_hint) else status,
            if (decrypt) view.context.getString(R.string.stego_decrypt_options_fixed)
                else options.summary(view, factor != null, wordingProfile?.label ?: options.transportLabel),
            mode != MessageCredentialMode.KEY_FILE,
            mode.file, detached, field != StegoField.CREDENTIAL &&
                ((drafts[field]?.length ?: 0) > 0 || states[field]?.snapshot?.isComposing == true),
            fields = fieldDisplays(), algorithms = stegoAlgorithmRows(options.stages),
            randomKey = isRandomMode(), credentialMode = view.context.getString(StegoOptions.MODE_LABELS[mode.ordinal]),
            canPaste = graph().settings.stegoDefaults.let { it.vaultPaste && graph().settings.secureClipboardEnabled || it.systemPaste },
            canVaultPaste = graph().settings.stegoDefaults.vaultPaste && graph().settings.secureClipboardEnabled,
            naturalWording = naturalWordingSelected,
            wordingPassword = mode.password, randomStageWidths = randomStageWidths,
            carrierChoice = if (canChooseWording) carrierLabel() else "",
            actionNotice = actionNotice, fileReady = factor != null),
            if (hidden && !reveal) null else carets[field])
        view.renderTextStegoInput(if (busy || preview || hasResult || field == StegoField.CREDENTIAL) null else states[field],
            readOnly = naturalWordingSelected && (preview || hasResult))
    }

    private fun carrierLabel(): String = view.context.getString(when {
        naturalWordingSelected -> requireNotNull(wordingProfile).label
        decrypt -> R.string.stego_exact_auto
        else -> options.transportLabel
    })

    private fun wordingHelp(): String = when {
        field == StegoField.CREDENTIAL && mode.password -> view.context.getString(R.string.stego_wording_password_help)
        field == StegoField.CREDENTIAL && isRandomMode() -> view.context.getString(R.string.stego_random_key_help)
        decrypt -> view.context.getString(R.string.stego_wording_receive)
        else -> view.context.getString(R.string.stego_wording_draft, drafts[StegoField.SECRET]?.committedUtf8Bytes() ?: 0)
    }

    private fun wordingStatus(carrier: StegoWordingCarrier): String =
        view.context.getString(R.string.stego_wording_preview, carrier.originalBytes, carrier.utf8Bytes,
            carrier.utilizationPercent)

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
    private fun touch() { draftInteraction++; gate.touch(); onDraftActivity() }
    private fun notify(id: Int) { notice = id; notifyUser(id) }
    private fun clearCredential() {
        for (i in credential.indices) credential.setCharAt(i, '\u0000')
        credential.setLength(0); credentialLimit = false
    }
    private fun clearSensitive() { clearCredential(); drafts[StegoField.SECRET]?.clear(); clearPrepared(); reveal = false }
    private fun clearPrepared() {
        prepared = null; boundCommit = null; insertOnReady = false; result?.fill('\u0000'); result = null
        wordingPreview = null
        outputs?.close(); outputs = null
    }

    private fun closeInactiveWorkspace() {
        if (valid.get() && !gate.current()) notify(R.string.stego_expired)
        close()
        view.closeAuxiliaryInput()
    }

    override fun close() {
        workspaceGeneration++
        valid.set(false)
        wordingProfile = null
        randomStageWidths = false
        biometric?.close(); biometric = null
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
