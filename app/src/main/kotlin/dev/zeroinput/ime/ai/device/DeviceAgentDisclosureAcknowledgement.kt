package dev.zeroinput.ime.ai.device

import android.view.View
import android.widget.CompoundButton

/** Keeps a disclosure confirmation disabled until its acknowledgement is checked. */
internal object DeviceAgentDisclosureAcknowledgement {
    fun bind(acknowledgement: CompoundButton, confirmation: View) {
        confirmation.isEnabled = acknowledgement.isChecked
        acknowledgement.setOnCheckedChangeListener { _, checked -> confirmation.isEnabled = checked }
    }
}
