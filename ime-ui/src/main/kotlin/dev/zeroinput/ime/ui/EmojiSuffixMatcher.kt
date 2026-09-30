package dev.zeroinput.ime.ui

/** Reverse trie constructed on the catalog worker; suffix lookup never scans the catalog. */
internal class EmojiSuffixMatcher(entries: List<EmojiEntry>) {
    private class Node {
        val children = HashMap<Char, Node>()
        var terminal = false
    }
    private val root = Node()
    val maximumLength: Int

    init {
        var longest = 0
        entries.filter { it.artworkKey != null }.forEach { entry ->
            require(entry.value.length <= 128)
            var node = root
            for (index in entry.value.lastIndex downTo 0) {
                node = node.children.getOrPut(entry.value[index]) { Node() }
            }
            node.terminal = true
            longest = maxOf(longest, entry.value.length)
        }
        maximumLength = longest
    }

    fun longestSuffixLength(text: CharSequence): Int {
        var node = root
        var length = 0
        val limit = minOf(maximumLength, text.length)
        for (offset in 1..limit) {
            node = node.children[text[text.length - offset]] ?: break
            if (node.terminal) length = offset
        }
        return length
    }
}
