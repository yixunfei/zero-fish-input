package dev.zeroinput.ime.input

import android.icu.text.BreakIterator
import dev.zeroinput.ime.ui.EmojiCatalog
import java.util.Locale

/** Bounded cursor-local grapheme deletion; the bundled RGI trie covers newer emoji than system ICU. */
internal object UnicodeDeletionBoundary {
    const val LOOKBEHIND_LIMIT = 64

    fun precedingLength(text: String): Int {
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
