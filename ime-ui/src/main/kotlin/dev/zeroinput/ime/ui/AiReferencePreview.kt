package dev.zeroinput.ime.ui

/** A bounded display copy; complete context remains available through explicit preview. */
internal fun referencePreview(text: String): String {
    var end = minOf(text.length, 160)
    if (end > 0 && end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
    return text.substring(0, end).replace('\n', ' ') + if (end < text.length) "…" else ""
}
