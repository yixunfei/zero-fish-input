package dev.zeroinput.ime.input

import android.view.inputmethod.InputConnection
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class EditorSelectionStateTest {
    @Test fun `revoked connection ignores late selection without mutating its anchor`() {
        val adapter = AndroidEditorConnection(4, 4, current = { null })
        assertFalse(adapter.updateSelection(1, 3, previousStart = 0, previousEnd = 0))
        assertNull(adapter.selectedLength())
    }

    @Test fun `selection from an unrelated previous anchor cannot corrupt a new session`() {
        val state = EditorSelectionState(10, 10)
        state.replaced(2, composing = true)
        assertFalse(state.updated(2, 2, 0, 2, previousStart = 1, previousEnd = 1))
        assertEquals(10, state.insertionStart())
        assertFalse(state.updated(12, 12, 10, 12, previousStart = 10, previousEnd = 10))
        assertTrue(state.updated(8, 8, previousStart = 12, previousEnd = 12))
    }

    @Test fun `synchronous editor acknowledgements do not invalidate successive preedits or commit`() {
        var invalidations = 0
        lateinit var adapter: AndroidEditorConnection
        var cursor = 0
        val input = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader,
            arrayOf(InputConnection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "setComposingText" -> {
                    val text = args!![0].toString()
                    cursor = text.length
                    adapter.updateSelection(cursor, cursor, if (text.isEmpty()) -1 else 0,
                        if (text.isEmpty()) -1 else cursor)
                    true
                }
                "finishComposingText" -> {
                    adapter.updateSelection(cursor, cursor)
                    true
                }
                "commitText" -> {
                    cursor = args!![0].toString().length
                    adapter.updateSelection(cursor, cursor)
                    true
                }
                else -> false
            }
        } as InputConnection
        adapter = AndroidEditorConnection(0, 0, onContextInvalidated = { invalidations++ }, current = { input })

        adapter.setComposingText("d")
        adapter.setComposingText("da")
        adapter.commitText("da")

        assertEquals(0, invalidations)
    }

    @Test fun `finishing and reopening composition acknowledge span changes at the same cursor`() {
        val state = EditorSelectionState(0, 0)
        state.replaced(2, composing = true)
        assertFalse(state.updated(2, 2, 0, 2))
        state.finishComposition()
        assertFalse(state.updated(2, 2))
        state.reopened(EditorSelectionState.CommitRange(0, 2))
        assertFalse(state.updated(2, 2, 0, 2))
    }

    @Test fun `long burst with coalesced callbacks preserves the composing origin`() {
        val state = EditorSelectionState(4, 4)
        repeat(64) { state.replaced(it + 1, composing = true) }
        assertFalse(state.updated(68, 68, 4, 68))
        state.replaced(2, composing = false)
        assertFalse(state.updated(6, 6))
        assertEquals(EditorSelectionState.CommitRange(4, 6), state.verifiedRange(2))
    }

    @Test fun `old acknowledgement after more than thirty two edits does not end composition`() {
        val state = EditorSelectionState(0, 0)
        repeat(40) { state.replaced(it + 1, composing = true) }
        state.replaced(40, composing = false)

        assertFalse(state.updated(1, 1, 0, 1, previousStart = 0, previousEnd = 0))
        assertFalse(state.updated(40, 40, previousStart = 40, previousEnd = 40))
        assertEquals(EditorSelectionState.CommitRange(0, 40), state.verifiedRange(40))
        assertTrue(state.updated(10, 10, previousStart = 40, previousEnd = 40))
    }

    @Test fun `delayed acknowledgement from an earlier word does not end the next composition`() {
        val state = EditorSelectionState(0, 0)
        repeat(40) { state.replaced(it + 1, composing = true) }
        state.replaced(40, composing = false)
        state.replaced(1, composing = true)

        assertFalse(state.updated(1, 1, 0, 1, previousStart = 0, previousEnd = 0))
        assertFalse(state.updated(41, 41, 40, 41, previousStart = 40, previousEnd = 40))
    }

    @Test fun `delayed cursor move from before a new composition does not cancel it`() {
        val state = EditorSelectionState(2, 2)
        state.replaced(1, composing = true)

        assertFalse(state.updated(0, 0, previousStart = 2, previousEnd = 2))
        assertTrue(state.updated(0, 0, previousStart = 3, previousEnd = 3))
    }

    @Test fun `unknown initial cursor anchors on the first composing span without dropping rapid input`() {
        val state = EditorSelectionState(-1, -1)
        state.replaced(1, composing = true)
        state.replaced(2, composing = true)
        assertFalse(state.updated(7, 7, 5, 7))
        state.replaced(2, composing = false)
        assertFalse(state.updated(6, 6, 5, 6))
        assertFalse(state.updated(7, 7, 5, 7))
        assertFalse(state.updated(7, 7))
        assertEquals(EditorSelectionState.CommitRange(5, 7), state.verifiedRange(2))
    }

    @Test fun `unknown final cursor never invents a verified commit range`() {
        val state = EditorSelectionState(-1, -1)
        state.replaced(2, composing = true)
        state.replaced(2, composing = false)
        assertFalse(state.updated(7, 7))
        assertNull(state.verifiedRange(2))
        assertTrue(state.updated(3, 3))
    }

    @Test fun `unknown composing state still rejects an external cursor move`() {
        val state = EditorSelectionState(-1, -1)
        state.replaced(2, composing = true)
        assertTrue(state.updated(8, 8))
    }
    @Test fun `commit replaces the complete composition and waits for cursor acknowledgement`() {
        val state = EditorSelectionState(4, 4)
        state.replaced(2, composing = true)
        state.replaced(6, composing = true)
        state.replaced(2, composing = false)
        assertNull(state.verifiedRange(2))
        state.updated(6, 6, 4, 6)
        state.updated(10, 10, 4, 10)
        state.updated(6, 6)
        assertEquals(EditorSelectionState.CommitRange(4, 6), state.verifiedRange(2))
    }

    @Test fun `coalesced final callback acknowledges earlier preedit at the same position`() {
        val state = EditorSelectionState(0, 0)
        state.replaced(2, composing = true)
        state.replaced(5, composing = true)
        state.replaced(2, composing = false)
        assertFalse(state.updated(2, 2))
        assertEquals(EditorSelectionState.CommitRange(0, 2), state.verifiedRange(2))
    }

    @Test fun `cursor move selection invalidation and unknown initial location reject reopening`() {
        val state = EditorSelectionState(0, 0)
        state.replaced(2, composing = false)
        state.updated(2, 2)
        assertNotNull(state.verifiedRange(2))
        assertTrue(state.updated(0, 0))
        state.updated(2, 2)
        assertNull(state.verifiedRange(2))
        val unknown = EditorSelectionState(-1, -1)
        unknown.replaced(2, composing = false)
        assertNull(unknown.verifiedRange(2))
    }

    @Test fun `moving cursor to an acknowledged position invalidates the active composition`() {
        val state = EditorSelectionState(0, 0)
        state.replaced(1, composing = false)
        assertFalse(state.updated(1, 1, previousStart = 0, previousEnd = 0))
        state.replaced(1, composing = true)
        assertFalse(state.updated(2, 2, 1, 2, previousStart = 1, previousEnd = 1))

        assertFalse(state.updated(1, 1, previousStart = 0, previousEnd = 0))
        assertTrue(state.updated(1, 1, previousStart = 2, previousEnd = 2))
        assertNull(state.verifiedRange(2))
    }

    @Test fun `acknowledged batched edits can reopen but another edit revokes the old range`() {
        val state = EditorSelectionState(1, 4)
        state.replaced(3, composing = true)
        state.replaced(2, composing = false)
        state.updated(3, 3)
        val range = checkNotNull(state.verifiedRange(2))
        state.reopened(range)
        state.replaced(7, composing = true)
        state.updated(8, 8, 1, 8)
        assertNull(state.verifiedRange(2))
        state.replaced(2, composing = false)
        state.updated(3, 3)
        assertEquals(EditorSelectionState.CommitRange(1, 3), state.verifiedRange(2))
        state.invalidate()
        assertNull(state.verifiedRange(2))
    }
}
