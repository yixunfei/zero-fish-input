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

    fun summary(view: View, fileReady: Boolean): String = view.context.getString(R.string.stego_options_summary,
        stages.joinToString(" → ") { label(it) },
        view.context.getString(MODE_LABELS[mode.ordinal]),
        view.context.getString(if (transport == StegoTransport.DENSE_UNICODE) R.string.stego_dense else R.string.stego_visible),
        view.context.getString(if (!mode.file) R.string.stego_file_not_required
            else if (fileReady) R.string.stego_file_ready else R.string.stego_file_absent))

    companion object {
        val MODE_LABELS = intArrayOf(R.string.stego_passphrase, R.string.stego_random_key,
            R.string.stego_file_only, R.string.stego_passphrase_file, R.string.stego_random_file)
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
internal class StegoOptionsDialog(private val owner: View, private val isCurrent: () -> Boolean) : AutoCloseable {
    private var dialog: AlertDialog? = null
    var attachToIme = true

    fun show(options: StegoOptions, decrypt: Boolean, changed: () -> Unit, fileChanged: () -> Unit) {
        if (decrypt) { message(R.string.stego_decrypt_options_fixed); return }
        val choices = buildList {
            add(R.string.stego_credential_mode)
            if (options.mode.password) add(R.string.stego_kdf)
            add(R.string.stego_transport)
        }
        choose(dev.zeroinput.ime.ui.R.string.stego_options, choices.map(owner.context::getString)) { index ->
            when (choices[index]) {
                R.string.stego_credential_mode -> mode(options) { fileChanged(); changed() }
                R.string.stego_kdf -> choose(R.string.stego_kdf, listOf("Argon2id · 64 MiB / 3", "Argon2id · 128 MiB / 3", "Argon2id · 256 MiB / 4")) {
                    val profile = MessageKdf.entries[it + 1]
                    if (!StegoOptions.available(profile)) message(R.string.stego_memory_unavailable)
                    else { options.kdf = profile; changed() }
                }
                R.string.stego_transport -> choose(R.string.stego_transport, listOf(R.string.stego_dense, R.string.stego_visible,
                    R.string.stego_wording_unavailable).map(owner.context::getString)) {
                    if (it == 2) message(R.string.stego_wording_explanation)
                    else { options.transport = StegoTransport.entries[it]; changed() }
                }
            }
        }
    }

    fun mode(options: StegoOptions, changed: () -> Unit) {
        choose(R.string.stego_credential_mode, StegoOptions.MODE_LABELS.map(owner.context::getString)) {
            options.mode = MessageCredentialMode.entries[it]
            if (options.mode.password && options.kdf == MessageKdf.NONE) options.kdf = MessageKdf.STANDARD
            changed()
        }
    }

    fun confirm(message: Int, action: () -> Unit) {
        lateinit var value: AlertDialog
        value = MaterialAlertDialogBuilder(owner.context).setMessage(message)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ ->
                if (isCurrent() && dialog === value) action()
            }.create()
        present(value)
    }

    fun message(message: Int) {
        present(MaterialAlertDialogBuilder(owner.context).setMessage(message).setPositiveButton(android.R.string.ok, null).create())
    }

    fun choose(title: Int, labels: List<String>, action: (Int) -> Unit) {
        if (labels.isEmpty()) return
        lateinit var value: AlertDialog
        value = MaterialAlertDialogBuilder(owner.context).setTitle(title).setItems(labels.toTypedArray()) { _, index ->
            if (isCurrent() && dialog === value) { dialog = null; action(index) }
        }.create()
        present(value)
    }

    private fun present(value: AlertDialog) {
        close()
        if (!isCurrent()) return
        dialog = value
        value.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (attachToIme) value.window?.apply {
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG)
            attributes = attributes.apply { token = owner.windowToken }
        }
        value.show()
    }

    override fun close() { dialog?.dismiss(); dialog = null }
}
