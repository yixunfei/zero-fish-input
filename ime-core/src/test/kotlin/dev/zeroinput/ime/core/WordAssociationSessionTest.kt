package dev.zeroinput.ime.core

import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.NextWordPredictor
import dev.zeroinput.engine.api.NextWordSuggestion
import org.junit.Assert.*
import org.junit.Test
import java.nio.CharBuffer

class WordAssociationSessionTest {
    @Test fun `visible association refresh honors threshold and never restores hidden context`() {
        var count = 2
        val session = WordAssociationSession(NextWordPredictor { _, _, _ ->
            listOf(NextWordSuggestion("alpha"), NextWordSuggestion("beta"))
        }, frequencyProvider = { _, _ -> mapOf("beta" to count) })
        session.committed("hello", InputLanguage.ENGLISH)
        assertEquals("alpha", session.candidates.first().text)
        val oldId = session.candidates.first().id
        count = 3
        session.refresh(InputLanguage.ENGLISH) { it }
        assertEquals("beta", session.candidates.first().text)
        assertNull(session.selection(oldId))
        session.hide()
        session.refresh(InputLanguage.ENGLISH) { it }
        assertTrue(session.candidates.isEmpty())
    }

    @Test fun `context is bounded and clearing wipes the predictor view`() {
        var captured: CharSequence? = null
        val session = WordAssociationSession(NextWordPredictor { _, context, limit ->
            assertEquals(8, limit)
            assertTrue((context as CharBuffer).isReadOnly)
            captured = context
            listOf(NextWordSuggestion("world"))
        })
        session.committed("a".repeat(40), InputLanguage.ENGLISH)
        assertEquals("a".repeat(32), captured.toString())
        session.clear()
        assertTrue(checkNotNull(captured).all { it == '\u0000' })
        assertTrue(session.candidates.isEmpty())
    }

    @Test fun `punctuation starts fresh context and whitespace alone cannot query`() {
        val contexts = mutableListOf<String>()
        val session = WordAssociationSession(NextWordPredictor { _, context, _ ->
            contexts += context.toString()
            emptyList()
        })
        session.committed("hello", InputLanguage.ENGLISH)
        session.committed("!new", InputLanguage.ENGLISH)
        session.committed("\n  ", InputLanguage.ENGLISH)
        session.committed("word ", InputLanguage.ENGLISH)
        assertEquals(listOf("hello", "new", "  word "), contexts)
    }

    @Test fun `hiding revokes selections and fresh commits keep continuous context`() {
        var context = ""
        val session = WordAssociationSession(NextWordPredictor { _, value, _ ->
            context = value.toString()
            listOf(NextWordSuggestion("much", " much"))
        })
        session.committed("thank", InputLanguage.ENGLISH)
        val old = session.candidates.single().id
        session.hide()
        assertNull(session.selection(old))
        session.committed(" you", InputLanguage.ENGLISH)
        assertEquals("thank you", context)
        assertNotEquals(old, session.candidates.single().id)
    }

    @Test fun `unavailable public data cannot replay predictions after becoming ready`() {
        var ready = false
        var queries = 0
        val session = WordAssociationSession(NextWordPredictor { _, _, _ ->
            queries++
            if (ready) listOf(NextWordSuggestion("world")) else emptyList()
        })
        session.committed("hello", InputLanguage.ENGLISH)
        session.clear()
        ready = true
        assertTrue(session.candidates.isEmpty())
        assertEquals(1, queries)
        session.committed("hello", InputLanguage.ENGLISH)
        assertEquals(1, session.candidates.size)
    }

    @Test fun `normalization deduplicates script variants and rejects invalid output`() {
        val session = WordAssociationSession(NextWordPredictor { _, _, _ ->
            listOf(NextWordSuggestion("快乐"), NextWordSuggestion("快樂"),
                NextWordSuggestion("bad", "other"), NextWordSuggestion("\n"), NextWordSuggestion("a".repeat(25)))
        })
        session.committed("生日", InputLanguage.CHINESE) { it.replace('乐', '樂') }
        assertEquals(listOf("快樂"), session.candidates.map { it.text })
        assertEquals("快樂", session.selection(session.candidates.single().id)?.commitText)
    }

    @Test fun `frequency readiness never reranks the visible round and applies on the next commit`() {
        var ready = false
        val session = WordAssociationSession(
            NextWordPredictor { _, _, _ ->
                listOf(NextWordSuggestion("alpha"), NextWordSuggestion("beta"))
            },
            frequencyProvider = { _, _ -> if (ready) mapOf("beta" to 5) else emptyMap() },
        )

        session.committed("hello", InputLanguage.ENGLISH)
        val firstRound = session.candidates
        assertEquals(listOf("alpha", "beta"), firstRound.map { it.text })

        ready = true
        assertEquals(firstRound, session.candidates)

        session.committed(" ", InputLanguage.ENGLISH)
        assertEquals(listOf("beta", "alpha"), session.candidates.map { it.text })
    }

    @Test fun `frequency ranking is restricted to the public top eight`() {
        val labels = ('a'..'l').map { "word$it" }
        val offered = labels.map(::NextWordSuggestion)
        var lookupWords = emptyList<String>()
        val session = WordAssociationSession(
            NextWordPredictor { _, _, limit ->
            assertEquals(NextWordPredictor.MAX_SUGGESTIONS, limit)
                offered
            },
            frequencyProvider = { _, words ->
                lookupWords = words
                mapOf("wordh" to 10, "wordk" to 100)
            },
        )

        session.committed("hello", InputLanguage.ENGLISH)

        assertEquals(labels.take(8), lookupWords)
        assertEquals(8, session.candidates.size)
        assertEquals("wordh", session.candidates.first().text)
        assertFalse(session.candidates.any { it.text == "wordk" })
    }

    @Test fun `learned frequency reranks suggestions with a stable editorial fallback`() {
        val session = WordAssociationSession(
            NextWordPredictor { _, _, _ ->
                listOf(NextWordSuggestion("alpha"), NextWordSuggestion("beta"), NextWordSuggestion("gamma"))
            },
            frequencyProvider = { language, words ->
                assertEquals(InputLanguage.ENGLISH, language)
                assertEquals(listOf("alpha", "beta", "gamma"), words)
                mapOf("gamma" to 5, "alpha" to 0)
            },
        )
        session.committed("hello", InputLanguage.ENGLISH)
        assertEquals(listOf("gamma", "alpha", "beta"), session.candidates.map { it.text })
        // Candidate identities follow the reranked order, so a click learns
        // the displayed word rather than the table row at that position.
        assertEquals("gamma", session.selection(session.candidates.first().id)?.text)
    }

    @Test fun `empty or failing frequency answers keep editorial order`() {
        var calls = 0
        val session = WordAssociationSession(
            NextWordPredictor { _, _, _ -> listOf(NextWordSuggestion("alpha"), NextWordSuggestion("beta")) },
            frequencyProvider = { _, _ ->
                calls++
                if (calls == 1) emptyMap() else throw IllegalStateException("store down")
            },
        )
        session.committed("hello", InputLanguage.ENGLISH)
        assertEquals(listOf("alpha", "beta"), session.candidates.map { it.text })
        session.committed("again", InputLanguage.ENGLISH)
        assertEquals(listOf("alpha", "beta"), session.candidates.map { it.text })
        assertEquals(2, calls)
    }

    @Test fun `rerank never adds or removes suggestion rows`() {
        val session = WordAssociationSession(
            NextWordPredictor { _, _, _ -> listOf(NextWordSuggestion("alpha"), NextWordSuggestion("beta")) },
            frequencyProvider = { _, _ -> mapOf("injected" to 99, "beta" to 3) },
        )
        session.committed("hello", InputLanguage.ENGLISH)
        assertEquals(listOf("beta", "alpha"), session.candidates.map { it.text })
    }
}
