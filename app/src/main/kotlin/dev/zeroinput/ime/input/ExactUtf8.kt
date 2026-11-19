package dev.zeroinput.ime.input

/** Counts exact UTF-8 without creating a copy of a private draft; malformed UTF-16 fails closed. */
internal fun exactUtf8ByteLength(text: CharSequence): Int? {
    var bytes = 0
    var index = 0
    while (index < text.length) {
        val char = text[index++]
        bytes += when {
            char.code < 0x80 -> 1
            char.code < 0x800 -> 2
            Character.isHighSurrogate(char) -> {
                if (index >= text.length || !Character.isLowSurrogate(text[index++])) return null
                4
            }
            Character.isLowSurrogate(char) -> return null
            else -> 3
        }
    }
    return bytes
}
