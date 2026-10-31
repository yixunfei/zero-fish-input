package dev.zeroinput.ime.clipboard

import dev.zeroinput.userdata.SecureClipboardVault

/** Shared validation for explicit text imports; owns characters and drops spans. */
internal object ClipboardImportText {
    fun parse(value: CharSequence, allowsAiImport: Boolean = false): ClipboardImportRequest? {
        if (value.length !in 1..SecureClipboardVault.MAX_VALUE_LENGTH) return null
        if (value.isBlank() || value.any { it == '\u0000' }) return null
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (character.isHighSurrogate()) {
                if (index == value.length || !value[index++].isLowSurrogate()) return null
            } else if (character.isLowSurrogate()) return null
        }
        return ClipboardImportRequest(CharArray(value.length) { value[it] }, allowsAiImport)
    }
}
