package dev.zeroinput.ime.input

import android.icu.text.BreakIterator
import dev.zeroinput.ime.ui.EmojiCatalog
import java.util.Locale

/** Bounded cursor-local grapheme deletion; the bundled RGI trie covers newer emoji than system ICU. */
internal object UnicodeDeletionBoundary {
    const val LOOKBEHIND_LIMIT = 64
    private val possibleLabel = Regex("""(?:\[[^\[\]\r\n]{1,32}]|【[^【】\r\n]{1,32}】|<[^<>\r\n]{1,32}>|:[\p{L}\p{N}_+\-]{1,32}:|/:[^\s]{1,32})$""")

    // This is only a dispatch hint, never permission to erase the matched text.
    // The host owns its custom label syntax and decides what a Delete key does.
    fun prefersEditorDelete(text: CharSequence): Boolean =
        possibleLabel.containsMatchIn(text.takeLast(LOOKBEHIND_LIMIT))

    fun precedingLength(value: CharSequence): Int {
        // Only an editor-provided replacement is atomic. Brackets and formatting
        // alone do not authorize deleting a larger piece of ordinary text.
        if (value is android.text.Spanned && value.isNotEmpty()) {
            val length = value.getSpans(value.length - 1, value.length, android.text.style.ReplacementSpan::class.java)
                .filter { value.getSpanEnd(it) == value.length && value.getSpanStart(it) > 0 }
                .maxOfOrNull { value.length - value.getSpanStart(it) }
            if (length != null) return length
        }
        val text = value.toString()
        if (text.isEmpty()) return 1
        val last = text.last()
        if (last.code in 0x20..0x7e && (text.length < 2 || text[text.lastIndex - 1] != '\r')) return 1
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        val boundary = iterator.preceding(text.length)
        val platformLength = if (boundary == BreakIterator.DONE) text.length else text.length - boundary
        val emojiLength = EmojiCatalog.longestEmojiSuffixLength(text)
        return maxOf(platformLength, emojiLength, 1).coerceAtMost(text.length)
    }
}
