package dev.zeroinput.ime.settings

import android.content.Context
import android.view.View
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

    fun attach(parent: LinearLayout, index: Int) {
        val group = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        group.addView(TextView(context).apply { setText(R.string.ai_protocol_label) })
        group.addView(Spinner(context).apply {
            contentDescription = context.getString(R.string.ai_protocol_label)
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item,
                context.resources.getStringArray(R.array.ai_protocol_names).toList()).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(protocol.ordinal)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val value = AiProtocol.entries[position]
                    if (protocol != value) { protocol = value; changed() }
                    render()
                }
            }
        })
        group.addView(streaming)
        group.addView(preview)
        parent.addView(group, index)
        render()
    }

    fun render() {
        preview.text = try {
            val endpoint = AiEndpoint.parse(address(), protocol)
            context.getString(R.string.ai_protocol_routes, endpoint.generation.toString(), endpoint.models.toString())
        } catch (_: Exception) { context.getString(R.string.ai_protocol_address_invalid) }
    }
}
