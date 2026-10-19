package dev.zeroinput.ime.ai

/** Accepts only a current range in the committed AI draft, never caller supplied host text. */
internal fun validatedAiDraftSelection(source: String, committed: String, start: Int, end: Int): String? {
    if (source != committed || start < 0 || end <= start || end > committed.length) return null
    if (splitsSurrogatePair(committed, start) || splitsSurrogatePair(committed, end)) return null
    return committed.substring(start, end).trim().takeIf(String::isNotBlank)
}

private fun splitsSurrogatePair(value: String, boundary: Int): Boolean =
    boundary in 1 until value.length && value[boundary].isLowSurrogate() && value[boundary - 1].isHighSurrogate()
