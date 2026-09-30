package dev.zeroinput.engine.dictionary

import dev.zeroinput.engine.api.GlideKey
import dev.zeroinput.engine.api.GlideLayout
import dev.zeroinput.engine.api.GlideLexiconEntry
import dev.zeroinput.engine.api.GlideRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class DictionaryGlideDecoderTest {
    @Test fun `all layouts return their own code and never another layout candidate`() {
        val codes = mapOf(
            GlideLayout.ENGLISH_QWERTY to "hello", GlideLayout.PINYIN_QWERTY to "nihao",
            GlideLayout.DOUBLE_PINYIN_MICROSOFT to "m;tj", GlideLayout.DOUBLE_PINYIN_ZIRANMA to "mytj",
            GlideLayout.PINYIN_NINE_KEY to "64426",
        )
        val decoder = DictionaryGlideDecoder(codes.map { (layout, code) -> GlideLexiconEntry(layout, code) })
        for ((layout, code) in codes) {
            val result = decoder.decode(GlideFixtures.request(code, layout)) { false }
            assertEquals(code, result.first().inputCode)
            assertEquals(1, result.size)
        }
    }

    @Test fun `repeated letters preserve word ambiguity while frequency ranks alternatives`() {
        val decoder = decoder("red" to 10, "reed" to 100, "read" to 80, "rod" to 20)
        val result = decoder.decode(GlideFixtures.request("red")) { false }
        assertEquals("reed", result.first().inputCode)
        assertTrue(result.map { it.inputCode }.contains("red"))
        assertEquals("hello", decoder("hello" to 200, "help" to 100).decode(GlideFixtures.request("helo")) { false }.first().inputCode)
    }

    @Test fun `contractions use letter trajectories without requiring apostrophe key`() {
        val result = decoder("don't" to 400, "done" to 300).decode(GlideFixtures.request("dont")) { false }
        assertEquals("don't", result.first().inputCode)
    }

    @Test fun `nine key repeated digits remain distinct selectable input codes`() {
        val decoder = DictionaryGlideDecoder(listOf("64426" to 300, "6426" to 200, "644426" to 100).map { (code, weight) ->
            GlideLexiconEntry(GlideLayout.PINYIN_NINE_KEY, code, frequency = weight)
        })
        val result = decoder.decode(GlideFixtures.request("6426", GlideLayout.PINYIN_NINE_KEY)) { false }
        assertEquals(listOf("64426", "6426", "644426"), result.map { it.inputCode })
    }

    @Test fun `cancelled indexing never exposes a partial dictionary`() {
        assertThrows(CancellationException::class.java) {
            DictionaryGlideDecoder(listOf(GlideLexiconEntry(GlideLayout.ENGLISH_QWERTY, "hello"))) { true }
        }
    }

    @Test fun `row offsets and resized floating keyboard use actual geometry`() {
        val decoder = decoder("keyboard" to 400, "keypad" to 300, "board" to 100)
        val resized = GlideFixtures.qwertyKeys().map { key ->
            GlideKey(key.code, key.left * 0.67f + 0.15f, key.top * 0.55f + 0.21f,
                key.right * 0.67f + 0.15f, key.bottom * 0.55f + 0.21f)
        }
        assertEquals("keyboard", decoder.decode(GlideFixtures.request("keyboard", keys = resized)) { false }.first().inputCode)
        val compactRows = resized.map { key ->
            if (key.code in "asdfghjkl;") key.copy(left = key.left + 0.025f, right = key.right + 0.025f) else key
        }
        assertEquals("keyboard", decoder.decode(GlideFixtures.request("keyboard", keys = compactRows)) { false }.first().inputCode)
    }

    @Test fun `density and moderate noisy paths keep the same public word`() {
        val decoder = decoder("privacy" to 300, "private" to 300, "primary" to 200, "party" to 400)
        for (density in listOf(2, 6, 16)) for (noise in listOf(0f, 0.009f, -0.012f)) {
            assertEquals("privacy", decoder.decode(GlideFixtures.request("privacy", density = density,
                perturbation = noise)) { false }.first().inputCode)
        }
    }

    @Test fun `cancellation and interrupted workers discard all candidates`() {
        val decoder = decoder("hello" to 400, "help" to 300)
        val request = GlideFixtures.request("hello")
        assertTrue(decoder.decode(request) { true }.isEmpty())
        var checks = 0
        assertTrue(decoder.decode(request) { ++checks >= 3 }.isEmpty())
        Thread.currentThread().interrupt()
        try { assertTrue(decoder.decode(request) { false }.isEmpty()) }
        finally { Thread.interrupted() }
    }

    @Test fun `missing physical keys fail closed and requests respect candidate bound`() {
        val decoder = decoder("hello" to 400, "helo" to 300, "helllo" to 200)
        val request = GlideFixtures.request("hello")
        assertTrue(decoder.decode(GlideRequest(request.layout, request.points,
            request.keys.filter { it.code != 'l' })) { false }.isEmpty())
        assertEquals(1, decoder.decode(GlideRequest(request.layout, request.points, request.keys, 1)) { false }.size)
    }

    @Test fun `duplicate codes produce one candidate with strongest public prior`() {
        val decoder = decoder("hello" to 0, "hello" to 400, "help" to 300)
        val result = decoder.decode(GlideFixtures.request("hello")) { false }
        assertEquals(result.map { it.inputCode }.distinct().size, result.size)
        assertEquals("hello", result.first().inputCode)
    }

    private fun decoder(vararg words: Pair<String, Int>) = DictionaryGlideDecoder(words.map { (word, frequency) ->
        GlideLexiconEntry(GlideLayout.ENGLISH_QWERTY, word, frequency = frequency)
    })
}
