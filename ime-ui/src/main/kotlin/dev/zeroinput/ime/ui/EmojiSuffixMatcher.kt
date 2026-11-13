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
            // Bound optional-selector expansion even if a future catalog is malformed.
            require(entry.value.count { it == '\uFE0F' } <= 4)
            insert(root, entry.value, entry.value.lastIndex)
            longest = maxOf(longest, entry.value.length)
        }
        maximumLength = longest
    }

    private fun insert(node: Node, value: String, index: Int) {
        if (index < 0) {
            node.terminal = true
            return
        }
        val char = value[index]
        insert(node.children.getOrPut(char) { Node() }, value, index - 1)
        // Emoji presentation selectors may be omitted individually in text received
        // from other keyboards. ZWJ, keycap and tone components remain mandatory.
        if (char == '\uFE0F') insert(node, value, index - 1)
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
