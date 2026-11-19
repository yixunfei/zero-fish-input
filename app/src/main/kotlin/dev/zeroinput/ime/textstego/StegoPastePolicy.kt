package dev.zeroinput.ime.textstego

import dev.zeroinput.ime.ui.StegoField

/** Exact-text budgets shared by explicit source reads and isolated field publication. */
internal object StegoPastePolicy {
    fun maxChars(field: StegoField): Int = when (field) {
        StegoField.COVER -> 512
        StegoField.SECRET -> 2048
        StegoField.CARRIER -> 8192
        else -> 0
    }

    fun accepts(field: StegoField, text: CharSequence): Boolean {
        if (text.length !in 1..maxChars(field) || text.any { it == '\u0000' }) return false
        val bytes = dev.zeroinput.ime.input.exactUtf8ByteLength(text) ?: return false
        return bytes <= maxBytes(field)
    }

    fun maxBytes(field: StegoField): Int = when (field) {
        StegoField.COVER -> 1536
        StegoField.SECRET -> 2048
        StegoField.CARRIER -> 16384
        else -> 0
    }
}
