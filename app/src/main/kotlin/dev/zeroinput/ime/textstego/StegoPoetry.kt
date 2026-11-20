package dev.zeroinput.ime.textstego

/** Reviewed complete couplets from the bundled Wanxiang shici table, with punctuation restored. */
internal object StegoPoetry {
    fun extend(cover: String): String {
        val previous = cover.trimEnd().substringAfterLast('\n')
        val next = DictionaryVerses.COUPLETS.filterNot { it == previous }.random()
        return if (cover.isEmpty() || cover.endsWith('\n')) cover + next else "$cover\n$next"
    }
}
