package dev.zeroinput.ime.textstego

import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zeroinput.ime.R
import dev.zeroinput.ime.core.stego.StegoTransport
import dev.zeroinput.security.message.*

internal class StegoOptions {
    var mode = MessageCredentialMode.PASSPHRASE
    var kdf = MessageKdf.STANDARD
    var transport = StegoTransport.DENSE_UNICODE
    var stages = listOf(MessageStage(MessageCipher.AES))

    val transportLabel: Int get() = if (transport == StegoTransport.DENSE_UNICODE) R.string.stego_dense else R.string.stego_visible

    fun select(cipher: MessageCipher, selected: Boolean) {
        if (selected && stages.none { it.cipher == cipher }) stages = stages + MessageStage(cipher)
        if (!selected) stages = stages.filterNot { it.cipher == cipher }
    }

    fun cycleWidth(cipher: MessageCipher) {
        stages = stages.map { stage ->
            if (stage.cipher != cipher) stage else {
                val widths = cipher.keyBits
                MessageStage(cipher, widths[(widths.indexOf(stage.bits) + 1) % widths.size])
            }
        }
    }

    fun moveEarlier(cipher: MessageCipher) {
        val index = stages.indexOfFirst { it.cipher == cipher }
        if (index <= 0) return
        stages = stages.toMutableList().apply { add(index - 1, removeAt(index)) }
    }

    fun summary(view: View, fileReady: Boolean, carrierLabel: Int = transportLabel): String = view.context.getString(R.string.stego_options_summary,
        stages.joinToString(" → ") { label(it) },
        view.context.getString(MODE_LABELS[mode.ordinal]),
        view.context.getString(carrierLabel),
        view.context.getString(if (!mode.file) R.string.stego_file_not_required
            else if (fileReady) R.string.stego_file_ready else R.string.stego_file_absent))

    companion object {
        val MODE_LABELS = intArrayOf(R.string.stego_passphrase, R.string.stego_random_key,
            R.string.stego_file_only, R.string.stego_passphrase_file, R.string.stego_random_file, R.string.stego_local_mode)
        fun label(stage: MessageStage): String = when (stage.cipher) {
            MessageCipher.XCHACHA20 -> "XChaCha20-Poly1305 (256)"
            else -> "${stage.cipher.name}-${stage.bits}-GCM"
        }
        fun available(profile: MessageKdf): Boolean {
            return Runtime.getRuntime().maxMemory() >= requiredMemory(profile)
        }
        fun requiredMemory(profile: MessageKdf): Long = profile.memoryKiB * 1024L * 3 / 2 + 32L * 1024 * 1024
    }
}

/** Owned dialogs are protected, revocable and use no editable Android fields. */
internal class StegoOptionsDialog(
    private val owner: View,
    private val isCurrent: () -> Boolean,
    private val interaction: () -> Long = { 0L },
) : AutoCloseable {
    private var dialog: AlertDialog? = null
    var attachToIme = true
    var leaveForLocalNote: (() -> Unit)? = null

    fun show(options: StegoOptions, decrypt: Boolean, chooseCarrier: () -> Unit,
             changed: () -> Unit, fileChanged: () -> Unit) {
        if (decrypt) { message(R.string.stego_decrypt_options_fixed); return }
        val choices = buildList {
            add(R.string.stego_credential_mode)
            if (options.mode.password) add(R.string.stego_kdf)
            add(R.string.stego_transport)
        }
        choose(dev.zeroinput.ime.ui.R.string.stego_options, choices.map(owner.context::getString)) { index ->
            when (choices[index]) {
                R.string.stego_credential_mode -> mode(options) { fileChanged(); changed() }
                R.string.stego_kdf -> cost(options, changed)
                R.string.stego_transport -> chooseCarrier()
            }
        }
    }

    fun cost(options: StegoOptions, changed: () -> Unit) {
        choose(R.string.stego_kdf, listOf("Argon2id · 64 MiB / 3", "Argon2id · 128 MiB / 3", "Argon2id · 256 MiB / 4")) {
            val profile = MessageKdf.entries[it + 1]
            if (!StegoOptions.available(profile)) message(R.string.stego_memory_unavailable)
            else { options.kdf = profile; changed() }
        }
    }

    fun mode(options: StegoOptions, portableOnly: Boolean = false, changed: () -> Unit) {
        val modes = MessageCredentialMode.entries.filter { !portableOnly || it != MessageCredentialMode.LOCAL_BIOMETRIC_PASSWORD }
        choose(R.string.stego_credential_mode, modes.map { owner.context.getString(StegoOptions.MODE_LABELS[it.ordinal]) }) {
            val selected = modes[it]
            val applyMode = {
                options.mode = selected
                if (selected.password && options.kdf == MessageKdf.NONE) options.kdf = MessageKdf.STANDARD
                changed()
            }
            val leave = leaveForLocalNote
            if (selected == MessageCredentialMode.LOCAL_BIOMETRIC_PASSWORD && leave != null) confirm(R.string.stego_local_move, leave)
            else if (selected == MessageCredentialMode.LOCAL_BIOMETRIC_PASSWORD) confirm(R.string.stego_local_warning, applyMode)
            else applyMode()
        }
    }

    fun confirm(message: Int, action: () -> Unit) {
        val revision = interaction()
        lateinit var value: AlertDialog
        value = MaterialAlertDialogBuilder(owner.context).setMessage(message)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ ->
                if (isCurrent() && dialog === value && interaction() == revision) action()
            }.create()
        present(value)
    }

    fun message(message: Int) {
        present(MaterialAlertDialogBuilder(owner.context).setMessage(message).setPositiveButton(android.R.string.ok, null).create())
    }

    fun choose(title: Int, labels: List<String>, help: Int? = null, action: (Int) -> Unit) {
        if (labels.isEmpty()) return
        val revision = interaction()
        lateinit var value: AlertDialog
        value = MaterialAlertDialogBuilder(owner.context).setTitle(title).setItems(labels.toTypedArray()) { _, index ->
            if (isCurrent() && dialog === value && interaction() == revision) {
                close()
                action(index)
            }
        }.apply {
            if (help != null) setNeutralButton(R.string.stego_help_button) { _, _ ->
                if (isCurrent() && dialog === value && interaction() == revision) message(help)
            }
        }.create()
        present(value)
    }

    private fun present(value: AlertDialog) {
        close()
        if (!isCurrent()) return
        dialog = value
        value.setOnDismissListener { if (dialog === value) dialog = null }
        value.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (attachToIme) value.window?.apply {
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
            // These touch-only controls must leave the external editor focused.
            // A focusable IME dialog can cause Android to restart the input session on dismissal.
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
            // A second picker must be touchable as soon as its rows appear.
            setWindowAnimations(0)
            attributes = attributes.apply { token = owner.windowToken }
            value.setCanceledOnTouchOutside(true)
        }
        value.window?.decorView?.filterTouchesWhenObscured = true
        try {
            value.show()
        } catch (_: WindowManager.BadTokenException) {
            // The system can detach an input window between validation and presentation.
            if (dialog === value) dialog = null
        }
    }

    fun dismissShowing(): Boolean {
        if (dialog?.isShowing != true) return false
        close()
        return true
    }

    override fun close() { dialog?.dismiss(); dialog = null }
}
