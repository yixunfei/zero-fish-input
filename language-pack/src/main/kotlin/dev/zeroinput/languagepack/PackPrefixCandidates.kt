package dev.zeroinput.languagepack

/** Shared immutable index: storage is linear in entries, independent of shortcut length. */
internal class PackPrefixCandidates(entries: List<Pair<String, String>>) {
    private data class RankedValue(val rank: Int, val text: String)
    private val shortcuts: List<String>
    private val leafCount: Int
    private val tree: Array<List<RankedValue>>
    val isEmpty: Boolean get() = shortcuts.isEmpty()

    init {
        val groups = entries.groupBy({ it.first }, { it.second })
        var rank = 0
        val ranked = groups.mapValues { (_, values) ->
            values.distinct().map { RankedValue(rank++, it) }.take(LIMIT)
        }
        shortcuts = groups.keys.sorted()
        var count = 1
        while (count < shortcuts.size) count *= 2
        leafCount = count
        tree = Array(leafCount * 2) { emptyList() }
        shortcuts.forEachIndexed { index, shortcut -> tree[leafCount + index] = ranked.getValue(shortcut) }
        for (node in leafCount - 1 downTo 1) tree[node] = merge(tree[node * 2], tree[node * 2 + 1])
    }

    fun find(prefix: String): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val first = boundary { it < prefix }
        val end = boundary(first) { it.startsWith(prefix) }
        var left = first + leafCount
        var right = end + leafCount
        var result = emptyList<RankedValue>()
        while (left < right) {
            if (left % 2 == 1) result = merge(result, tree[left++])
            if (right % 2 == 1) result = merge(result, tree[--right])
            left /= 2
            right /= 2
        }
        return result.map(RankedValue::text)
    }

    private fun boundary(start: Int = 0, before: (String) -> Boolean): Int {
        var low = start
        var high = shortcuts.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (before(shortcuts[middle])) low = middle + 1 else high = middle
        }
        return low
    }

    // Each subtree retains its first eight unique values in original dictionary order.
    // Merging summaries therefore preserves the old prefix buckets without creating them.
    private fun merge(left: List<RankedValue>, right: List<RankedValue>): List<RankedValue> {
        val result = ArrayList<RankedValue>(LIMIT)
        var first = 0
        var second = 0
        while (result.size < LIMIT && (first < left.size || second < right.size)) {
            val next = if (second >= right.size || first < left.size && left[first].rank < right[second].rank) {
                left[first++]
            } else right[second++]
            if (result.none { it.text == next.text }) result += next
        }
        return result
    }

    private companion object { const val LIMIT = 8 }
}
