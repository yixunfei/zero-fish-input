package dev.zeroinput.languagepack

internal data class PackDictionaryEntry(val shortcut: String, val value: String) {
    companion object {
        fun create(shortcut: String, value: String): PackDictionaryEntry? {
            val cleanShortcut = shortcut.trim().lowercase()
            val cleanValue = value.trim()
            if (cleanShortcut.isEmpty() || cleanValue.isEmpty() || cleanShortcut.length > 64 || cleanValue.length > 128 ||
                cleanShortcut.any(Char::isISOControl) || cleanValue.any(Char::isISOControl)) return null
            return PackDictionaryEntry(cleanShortcut, cleanValue)
        }

        fun pair(first: String, second: String): PackDictionaryEntry? =
            if (!looksLikeShortcut(first) && looksLikeShortcut(second)) create(second, first) else create(first, second)

        private fun looksLikeShortcut(value: String): Boolean = value.length <= 64 &&
            value.all { it.isLetterOrDigit() || it == '\'' || it == '_' || it == '-' } &&
            (value.any { it in 'a'..'z' || it in 'A'..'Z' } || value.all(Char::isDigit))
    }
}
