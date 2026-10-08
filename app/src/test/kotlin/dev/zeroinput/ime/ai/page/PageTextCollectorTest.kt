package dev.zeroinput.ime.ai.page

import org.junit.Assert.*
import org.junit.Test

class PageTextCollectorTest {
    @Test fun excludedSubtreesAreNeverReadAndOccludedTextIsNotCaptured() {
        val excluded = Node("never read", excluded = true, children = listOf(Node("hidden child")))
        val occluded = Node("behind keyboard", visible = false)
        val root = Node(null, children = listOf(Node("first"), excluded, occluded, Node("second"), Node("first")))
        assertEquals(listOf("first", "second"), PageTextCollector.collect(root) { true }.texts)
        assertEquals(0, excluded.reads)
        assertEquals(0, excluded.childReads)
        assertEquals(0, occluded.reads)
        assertTrue(root.closed && excluded.closed && occluded.closed)
    }

    @Test fun excessiveChildrenDepthAndTextReportPartialWithoutExceedingLimits() {
        val huge = Node("x".repeat(4097))
        val root = Node(null, children = List(600) { Node("block $it") } + huge)
        val result = PageTextCollector.collect(root) { true }
        assertTrue(result.incomplete)
        assertEquals(PageTextCollector.MAX_BLOCKS, result.texts.size)
        assertEquals(PageTextCollector.MAX_NODES - 1, root.childReads)
        val tooLong = PageTextCollector.collect(huge) { true }
        assertTrue(tooLong.incomplete)
        assertTrue(tooLong.texts.isEmpty())
        var deep = Node("leaf")
        repeat(40) { deep = Node(null, children = listOf(deep)) }
        assertTrue(PageTextCollector.collect(deep) { true }.incomplete)
    }

    @Test fun invalidationReleasesAllOwnedNodesAndYieldsNoSnapshot() {
        val children = List(10) { Node("public $it") }
        val root = Node(null, children = children)
        var checks = 0
        assertThrows(IllegalStateException::class.java) {
            PageTextCollector.collect(root) { ++checks < 14 }
        }
        assertTrue(root.closed)
        assertTrue(children.all { it.closed })
    }

    private class Node(
        private val value: String?,
        override val excluded: Boolean = false,
        private val visible: Boolean = true,
        private val children: List<Node> = emptyList(),
    ) : PageTextNode {
        var reads = 0
        var childReads = 0
        var closed = false
        override val fullyVisible get() = visible
        override val text: CharSequence? get() { reads++; return value }
        override val childCount get() = children.size
        override fun child(index: Int): PageTextNode { childReads++; return children[index] }
        override fun close() { closed = true }
    }
}
