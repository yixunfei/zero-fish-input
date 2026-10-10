package dev.zeroinput.ime.settings

import android.content.Context
import android.annotation.SuppressLint
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import dev.zeroinput.ime.R
import dev.zeroinput.userdata.AiConfiguration

/** Programmatic-only view: settings dependencies are provided by the dialog owner, never XML inflation. */
@SuppressLint("ViewConstructor")
internal class AiNetworkSettingsView(
    context: Context,
    private val configuration: () -> AiConfiguration,
    private val save: (AiConfiguration, (Boolean) -> Unit) -> Unit,
) : LinearLayout(context) {
    private var rendering = false
    private var pending = false
    private val enabled = MaterialSwitch(context).apply { setText(R.string.ai_enabled) }
    private val network = MaterialSwitch(context).apply { setText(R.string.ai_network) }
    private val status = TextView(context)

    init {
        orientation = VERTICAL
        addView(enabled); addView(network); addView(status)
        enabled.setOnCheckedChangeListener { _, checked ->
            update { copy(enabled = checked, networkAllowed = checked && networkAllowed) }
        }
        network.setOnCheckedChangeListener { _, checked -> update { copy(networkAllowed = checked) } }
        render()
    }

    private fun update(change: AiConfiguration.() -> AiConfiguration) {
        if (rendering || pending) return
        pending = true
        val next = configuration().change()
        render()
        save(next) { pending = false; render() }
    }

    fun render() {
        rendering = true
        val value = configuration()
        enabled.isChecked = value.enabled
        network.isChecked = value.enabled && value.networkAllowed
        enabled.isEnabled = !pending
        network.isEnabled = !pending && value.enabled
        status.setText(when {
            pending -> R.string.ai_settings_saving
            !value.enabled || !value.networkAllowed -> R.string.ai_setup_network_required
            else -> R.string.ai_setup_network_ready
        })
        rendering = false
    }
}
