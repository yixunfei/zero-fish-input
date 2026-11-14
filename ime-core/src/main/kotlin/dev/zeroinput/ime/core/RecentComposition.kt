package dev.zeroinput.ime.core

/** A single session-local draft. Cleared on another edit, invalidation or expiry. */
internal class RecentComposition {
    private var input = CharArray(0)
    private var text = CharArray(0)

    val available: Boolean get() = input.isNotEmpty() && text.isNotEmpty()

    fun remember(reading: String, committed: String) {
        clear()
        if (reading.length !in 1..128 || committed.length !in 1..128) return
        if (!ChinesePreeditCode.accepts(reading)) return
        if (committed.codePoints().anyMatch { !Character.isIdeographic(it) }) return
        input = reading.toCharArray()
        text = committed.toCharArray()
    }

    fun <T> consume(action: (String, String) -> T): T? {
        if (!available) return null
        return try { action(String(input), String(text)) } finally { clear() }
    }

    fun clear() {
        input.fill('\u0000')
        text.fill('\u0000')
        input = CharArray(0)
        text = CharArray(0)
    }
}
