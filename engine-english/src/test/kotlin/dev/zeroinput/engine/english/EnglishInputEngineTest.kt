package dev.zeroinput.engine.english

import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.EngineKey
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.LearnedSuggestionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishInputEngineTest {
    @Test fun `disabled prediction preserves editing without querying personal words`() {
        val engine = EnglishInputEngine(learnedSuggestions = LearnedSuggestionSource { _, _ ->
            error("Personal words must not be queried")
        })
        engine.start(context.copy(predictionsAllowed = false))
        "help".forEach { engine.handle(EngineKey.Character(it.toString())) }
        engine.handle(EngineKey.Backspace)
        assertEquals("hel", engine.snapshot.composition)
        assertTrue(engine.snapshot.candidates.isEmpty())
        assertFalse(engine.selectCandidate(0).consumed)
        assertEquals("hel ", engine.handle(EngineKey.Space).committedText)
        engine.start(context.copy(learningAllowed = false))
        "hel".forEach { engine.handle(EngineKey.Character(it.toString())) }
        assertTrue(engine.snapshot.candidates.any { it.text == "hello" })
        engine.close()
    }

    private val context = EditorContext(
        language = InputLanguage.ENGLISH,
        isSensitive = false,
        learningAllowed = true,
        packageName = "dev.example",
    )

    @Test
    fun `typing offers matching words and preserves composition`() {
        val engine = EnglishInputEngine()
        engine.start(context)

        "hel".forEach { engine.handle(EngineKey.Character(it.toString())) }

        assertEquals("hel", engine.snapshot.composition)
        assertTrue(engine.snapshot.candidates.any { it.text == "hello" })
    }

    @Test
    fun `space commits current word and clears composition`() {
        val engine = EnglishInputEngine()
        engine.start(context)
        "hello".forEach { engine.handle(EngineKey.Character(it.toString())) }

        val update = engine.handle(EngineKey.Space)

        assertEquals("hello ", update.committedText)
        assertFalse(update.snapshot.isComposing)
    }

    @Test
    fun `backspace with empty composition is not consumed`() {
        val engine = EnglishInputEngine()
        engine.start(context)

        assertFalse(engine.handle(EngineKey.Backspace).consumed)
    }

    @Test
    fun `disabled learning does not query or expose learned suggestions`() {
        var queried = false
        val engine = EnglishInputEngine(
            learnedSuggestions = LearnedSuggestionSource { _, _ ->
                queried = true
                listOf(dev.zeroinput.engine.api.WeightedTerm("private", 100))
            },
        )
        engine.start(context.copy(learningAllowed = false))

        "pri".forEach { engine.handle(EngineKey.Character(it.toString())) }

        assertFalse(queried)
        assertTrue(engine.snapshot.candidates.none { it.text == "private" })
    }

    @Test
    fun `snapshot keeps first case insensitive occurrence and stable score order`() {
        val engine = EnglishInputEngine(
            learnedSuggestions = LearnedSuggestionSource { _, _ ->
                listOf(
                    dev.zeroinput.engine.api.WeightedTerm("HELLO", 100),
                    dev.zeroinput.engine.api.WeightedTerm("hello", 99),
                )
            },
        )
        engine.start(context)

        "hel".forEach { engine.handle(EngineKey.Character(it.toString())) }

        assertEquals("hel", engine.snapshot.candidates.first().text)
        assertEquals(1, engine.snapshot.candidates.count { it.text.equals("hello", ignoreCase = true) && it.text.length > 3 })
    }
}
