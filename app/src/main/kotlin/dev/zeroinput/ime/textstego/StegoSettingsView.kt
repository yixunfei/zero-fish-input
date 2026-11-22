package dev.zeroinput.ime.textstego

import android.content.Context
import android.content.Intent
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.ui.StegoAlgorithmView
import dev.zeroinput.ime.ui.StegoAlgorithmUi
import dev.zeroinput.security.message.MessageCipher
import dev.zeroinput.security.message.MessageRandomKey
import dev.zeroinput.security.message.MessageStage

/** Edits public defaults only; message credentials and field text never enter settings. */
internal class StegoSettingsView(context: Context) : LinearLayout(context) {
    var onChanged: (StegoDefaults) -> Unit = {}
    private var value = StegoDefaults()
    private var rendering = false
    private var dialog: AlertDialog? = null
    private val bits = MaterialButton(context).apply {
        isAllCaps = false; minHeight = dp(48)
        setOnClickListener { chooseBits() }
    }
    private val summary = TextView(context).apply { textSize = 14f; setPadding(0, dp(8), 0, dp(8)) }
    private val ciphers = StegoAlgorithmView(context).apply {
        onSelect = { id, checked -> changeStages {
            if (checked) it.select(id) else it.filterNot { stage -> stage.cipher.id == id }
        } }
        onWidth = { id -> changeStages { stages -> stages.map { stage ->
            if (stage.cipher.id != id) stage else stage.copy(bits = stage.cipher.keyBits.let { widths ->
                widths[(widths.indexOf(stage.bits) + 1) % widths.size]
            })
        } } }
        onEarlier = { id -> changeStages { stages ->
            val index = stages.indexOfFirst { it.cipher.id == id }
            if (index <= 0) stages else stages.toMutableList().apply { add(index - 1, removeAt(index)) }
        } }
    }
    private val vault = toggle(R.string.stego_settings_vault) { update(value.copy(vaultPaste = it)) }
    private val system = toggle(R.string.stego_settings_system) { update(value.copy(systemPaste = it)) }

    init {
        orientation = VERTICAL
        note(R.string.stego_settings_help)
        if (StegoWordingCodec.available) addView(MaterialButton(context).apply {
            setText(R.string.stego_export_key_entry)
            filterTouchesWhenObscured = true
            minHeight = dp(48)
            setOnClickListener { exportKeyFile() }
        })
        addView(bits, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        note(R.string.stego_settings_bits_help)
        heading(R.string.stego_settings_algorithms)
        addView(summary)
        addView(ciphers)
        note(R.string.stego_settings_algorithms_help)
        heading(R.string.stego_settings_paste)
        addView(vault)
        addView(system)
        note(R.string.stego_settings_paste_help)
        render(value)
    }

    private fun exportKeyFile() {
        val app = context.applicationContext as? ZeroInputApplication ?: return
        val handoff = app.graph.stegoHandoff
        val token = handoff.put(StegoHandoff.Request(StegoHandoff.Kind.EXPORT_KEY_FILE))
        try {
            context.startActivity(Intent(context, StegoSourceActivity::class.java).putExtra(StegoSourceActivity.EXTRA_TOKEN, token))
        } catch (_: RuntimeException) {
            handoff.discard(token)
            android.widget.Toast.makeText(context, R.string.stego_failed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun render(defaults: StegoDefaults) {
        value = defaults.copy(stages = defaults.stages.toList())
        rendering = true
        try {
            bits.text = context.getString(R.string.stego_settings_bits_value, value.masterBits)
            summary.text = value.stages.joinToString(" → ", transform = StegoOptions::label)
            ciphers.render(stegoAlgorithmRows(value.stages), true)
            vault.isChecked = value.vaultPaste; system.isChecked = value.systemPaste
        } finally { rendering = false }
    }

    private fun update(next: StegoDefaults) { if (!rendering) { render(next); onChanged(next) } }

    private fun changeStages(change: (List<MessageStage>) -> List<MessageStage>) {
        val next = change(value.stages)
        if (StegoPreferences.validStages(next)) update(value.copy(stages = next))
        else {
            render(value)
            android.widget.Toast.makeText(context, R.string.stego_choose_algorithm, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun List<MessageStage>.select(id: Int): List<MessageStage> {
        val cipher = MessageCipher.entries.firstOrNull { it.id == id } ?: return this
        return if (any { it.cipher == cipher }) this else this + MessageStage(cipher)
    }

    private fun chooseBits() {
        dialog?.dismiss()
        val presets = listOf(256, 512, 1024, 4096)
        dialog = MaterialAlertDialogBuilder(context).setTitle(R.string.stego_settings_bits)
            .setSingleChoiceItems((presets.map { context.getString(R.string.stego_settings_bit_choice, it) } +
                context.getString(R.string.stego_settings_custom_bits)).toTypedArray(), presets.indexOf(value.masterBits)) { chooser, index ->
                chooser.dismiss()
                if (index < presets.size) update(value.copy(masterBits = presets[index])) else customBits()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun customBits() {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; filters = arrayOf(InputFilter.LengthFilter(4))
            setText(String.format(java.util.Locale.ROOT, "%d", value.masterBits)); selectAll(); isSaveEnabled = false
            contentDescription = context.getString(R.string.stego_settings_bits)
        }
        val chooser = MaterialAlertDialogBuilder(context).setTitle(R.string.stego_settings_bits)
            .setMessage(R.string.stego_bits_range).setView(input)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
        dialog = chooser
        chooser.setOnShowListener { chooser.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val bits = input.text.toString().toIntOrNull() ?: 0
            if (MessageRandomKey.validBits(bits)) { update(value.copy(masterBits = bits)); chooser.dismiss() }
            else input.error = context.getString(R.string.stego_invalid_bits)
        } }
        chooser.show()
    }

    private fun toggle(label: Int, change: (Boolean) -> Unit) = MaterialSwitch(context).apply {
        setText(label); minHeight = dp(56); filterTouchesWhenObscured = true
        setOnCheckedChangeListener { _, checked -> if (!rendering) change(checked) }
    }
    private fun note(label: Int) { addView(TextView(context).apply {
        setText(label); textSize = 14f; setPadding(0, dp(8), 0, dp(12))
    }) }
    private fun heading(label: Int) { addView(TextView(context).apply {
        setText(label); textSize = 16f; setPadding(0, dp(20), 0, dp(4))
        androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
    }) }
    override fun onDetachedFromWindow() { dialog?.dismiss(); dialog = null; super.onDetachedFromWindow() }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
}

internal fun stegoAlgorithmRows(stages: List<MessageStage>): List<StegoAlgorithmUi> = MessageCipher.entries.map { cipher ->
    val stage = stages.firstOrNull { it.cipher == cipher } ?: MessageStage(cipher)
    StegoAlgorithmUi(cipher.id, if (cipher == MessageCipher.XCHACHA20) "XChaCha20-Poly1305" else "${cipher.name}-GCM",
        stage.bits, stages.indexOfFirst { it.cipher == cipher }, cipher.keyBits.size > 1)
}
