package dev.zeroinput.ime.ai.page

import dev.zeroinput.ai.api.AiReference

/** Adapter owns platform nodes; collection never accesses text before checking its metadata. */
internal interface PageTextNode : AutoCloseable {
    val excluded: Boolean
    val fullyVisible: Boolean
    val text: CharSequence?
    val childCount: Int
    fun child(index: Int): PageTextNode?
}

internal class PageTextSnapshot(val texts: List<String>, val incomplete: Boolean) {
    override fun toString() = "PageTextSnapshot(redacted)"
}

internal object PageTextCollector {
    fun collect(root: PageTextNode, current: () -> Boolean): PageTextSnapshot {
        val pending = ArrayDeque<Pair<PageTextNode, Int>>()
        pending.addLast(root to 0)
        val texts = linkedSetOf<String>()
        var allocated = 1
        var chars = 0
        var incomplete = false
        try {
            while (pending.isNotEmpty()) {
                check(current()) { "Page request expired" }
                val (node, depth) = pending.removeLast()
                node.use {
                    if (node.excluded) return@use
                    if (node.fullyVisible) {
                        val raw = node.text
                        if (raw != null && raw.length > AiReference.MAX_REFERENCE_CHARS) incomplete = true
                        else if (raw != null) {
                            val text = raw.toString().trim()
                            if (text.isNotEmpty() && text.none { it.isISOControl() && it !in "\n\r\t" } && text !in texts) {
                                if (texts.size == MAX_BLOCKS || chars + text.length > MAX_CHARS) incomplete = true
                                else { texts += text; chars += text.length }
                            }
                        }
                    }
                    val count = node.childCount
                    val permitted = if (depth >= MAX_DEPTH) 0 else minOf(count, MAX_NODES - allocated)
                    if (permitted < count) incomplete = true
                    // Allocate in reverse so the snapshot follows the source's reading order.
                    for (index in (0 until permitted).reversed()) {
                        check(current()) { "Page request expired" }
                        allocated++ // Missing or stale nodes still consume an IPC/visit attempt.
                        node.child(index)?.let { pending.addLast(it to depth + 1) }
                    }
                }
            }
            check(current()) { "Page request expired" }
            return PageTextSnapshot(texts.toList(), incomplete)
        } finally {
            pending.forEach { (node, _) -> node.close() }
            pending.clear()
        }
    }

    const val MAX_NODES = 512
    const val MAX_DEPTH = 32
    const val MAX_BLOCKS = 128
    const val MAX_CHARS = 32_768
}
