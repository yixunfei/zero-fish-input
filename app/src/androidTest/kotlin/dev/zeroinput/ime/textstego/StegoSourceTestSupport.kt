package dev.zeroinput.ime.textstego

import dev.zeroinput.ime.R
import dev.zeroinput.ime.clipboardguard.ClipboardDeviceTestSupport as Device

/** The chooser adds public index time to the ordinal without exposing encrypted labels. */
internal fun clickStegoVaultEntry(ordinal: Int) {
    val prefix = Device.context.getString(R.string.stego_entry_time, ordinal, "")
    var label: String? = null
    Device.await {
        label = Device.roots().firstNotNullOfOrNull { root ->
            root.findAccessibilityNodeInfosByText(prefix).firstOrNull {
                it.text?.toString()?.startsWith(prefix) == true
            }?.text?.toString()
        }
        label != null
    }
    Device.click(requireNotNull(label))
}
