package dev.zeroinput.ime.ai

internal fun String.takeWholeCodePoints(limit: Int): String {
    val end = limit.coerceAtMost(length)
    return substring(0, if (end in 1 until length && this[end - 1].isHighSurrogate() &&
        this[end].isLowSurrogate()) end - 1 else end)
}

internal fun String.takeLastWholeCodePoints(limit: Int): String {
    val start = (length - limit).coerceAtLeast(0)
    return substring(if (start in 1 until length && this[start - 1].isHighSurrogate() &&
        this[start].isLowSurrogate()) start + 1 else start)
}
