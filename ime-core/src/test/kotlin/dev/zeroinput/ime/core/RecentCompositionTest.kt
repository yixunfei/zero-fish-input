package dev.zeroinput.ime.core

import org.junit.Assert.*
import org.junit.Test

class RecentCompositionTest {
    @Test fun `Microsoft double pinyin semicolon can be reopened`() {
        val draft = RecentComposition()
        draft.remember("b;", "冰")
        assertEquals("b;", draft.consume { input, _ -> input })
    }

    @Test fun `only the most recent complete Chinese draft can be consumed once`() {
        val draft = RecentComposition()
        draft.remember("ni", "你")
        draft.remember("nihao", "你好")
        assertEquals("nihao:你好", draft.consume { input, text -> "$input:$text" })
        assertFalse(draft.available)
        assertNull(draft.consume { _, _ -> true })
    }

    @Test fun `invalidation and failed reopening discard the draft`() {
        val draft = RecentComposition()
        draft.remember("nihao", "你好")
        assertEquals(false, draft.consume { _, _ -> false })
        assertFalse(draft.available)
        draft.remember("nihao", "你好")
        draft.clear()
        assertFalse(draft.available)
    }

    @Test fun `literal punctuation unknown readings and oversized words are not retained`() {
        val draft = RecentComposition()
        for ((input, value) in listOf("nihao" to "你好!", "hello" to "hello", "" to "你好",
            "A" to "你", "a".repeat(129) to "你", "ni" to "你".repeat(129))) {
            draft.remember(input, value)
            assertFalse(draft.available)
        }
    }
}
