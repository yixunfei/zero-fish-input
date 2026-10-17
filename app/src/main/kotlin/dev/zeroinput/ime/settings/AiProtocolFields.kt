package dev.zeroinput.ime.settings

import android.content.Context
import android.widget.*
import com.google.android.material.materialswitch.MaterialSwitch
import dev.zeroinput.ai.api.AiEndpoint
import dev.zeroinput.ai.api.AiProtocol
import dev.zeroinput.ime.R
import dev.zeroinput.userdata.AiProviderProfile

/** Explicit protocol selection and the exact resolved destination, with no network effects. */
internal class AiProtocolFields(
    private val context: Context,
    existing: AiProviderProfile?,
    private val address: () -> String,
    private val changed: () -> Unit,
) {
    var protocol: AiProtocol = existing?.protocol ?: AiProtocol.CHAT_COMPLETIONS
        private set
    private val streaming = MaterialSwitch(context).apply {
        setText(R.string.ai_protocol_streaming)
        isChecked = existing?.streaming ?: true
        setOnCheckedChangeListener { _, _ -> changed() }
    }
    val streams: Boolean get() = streaming.isChecked
    private val preview = TextView(context).apply { isSaveEnabled = false }
    private val choices = AiProviderChoiceField(context, R.string.ai_protocol_label, R.string.ai_protocol_label) { position ->
        val value = AiProtocol.entries[position]
        if (protocol != value) { protocol = value; changed() }
        render()
    }

    fun attach(parent: LinearLayout) {
        val group = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val names = context.resources.getStringArray(R.array.ai_protocol_names).toList()
        choices.render(names, names[protocol.ordinal])
        group.addView(choices)
        group.addView(streaming)
        group.addView(preview)
        parent.addView(group)
        render()
    }

    fun render() {
        preview.text = if (address().isBlank()) "" else try {
            val endpoint = AiEndpoint.parse(address(), protocol)
            context.getString(R.string.ai_protocol_routes, endpoint.generation.toString(), endpoint.models.toString())
        } catch (_: Exception) { context.getString(R.string.ai_protocol_address_invalid) }
        preview.visibility = if (preview.text.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }
}
