package dev.zeroinput.engine.dictionary

import dev.zeroinput.engine.api.InputLanguage
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class WordAssociationIndexTest {
    private val index = WordAssociationIndex.loadBundled()

    @Test fun `Chinese matches longest contiguous phrase and English matches word boundaries`() {
        assertEquals("你", predict(InputLanguage.CHINESE, "谢谢").first().text)
        assertEquals("的帮助", predict(InputLanguage.CHINESE, "谢谢你").first().text)
        assertEquals("morning", predict(InputLanguage.ENGLISH, "good").first().text)
        assertEquals("very", predict(InputLanguage.ENGLISH, "thank you").first().text)
        assertTrue(predict(InputLanguage.ENGLISH, "notgood").isEmpty())
        assertTrue(predict(InputLanguage.CHINESE, "unknown").isEmpty())
    }

    @Test fun `English adds exactly one separator when needed and remains locale independent`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(" am", predict(InputLanguage.ENGLISH, "I").first().commitText)
            assertEquals("morning", predict(InputLanguage.ENGLISH, "good ").first().commitText)
            assertEquals(" morning", predict(InputLanguage.ENGLISH, "Good").first().commitText)
        } finally { Locale.setDefault(previous) }
    }

    @Test fun `lookup is bounded and language isolated`() {
        assertTrue(index.suggest(InputLanguage.CHINESE, "你好", 0).isEmpty())
        assertEquals(1, index.suggest(InputLanguage.CHINESE, "你好", 1).size)
        assertTrue(index.suggest(InputLanguage.CHINESE, "我".repeat(33), 8).isEmpty())
        assertTrue(predict(InputLanguage.ENGLISH, "你好").isEmpty())
        assertTrue(predict(InputLanguage.CHINESE, "good").isEmpty())
    }

    @Test fun `Chinese single character anchors cannot match inside another word`() {
        assertEquals("想", predict(InputLanguage.CHINESE, "我").first().text)
        for (word in listOf("忘我", "自我", "无我", "舍我")) {
            assertTrue(predict(InputLanguage.CHINESE, word).isEmpty())
        }
        assertEquals("身体", predict(InputLanguage.CHINESE, "请你保重").first().text)
    }

    @Test fun `expanded everyday pairs use longest context and chain in either language`() {
        assertEquals("附件", predict(InputLanguage.CHINESE, "请查收").first().text)
        assertEquals("方式", predict(InputLanguage.CHINESE, "聯繫").first().text)
        assertEquals("了", predict(InputLanguage.CHINESE, "準備出發").first().text)
        assertEquals("your", predict(InputLanguage.ENGLISH, "we are looking forward to").first().text)
        assertEquals("coffee", predict(InputLanguage.ENGLISH, "a cup of ").first().commitText)
        assertEquals("care", predict(InputLanguage.ENGLISH, "take").first().text)
        assertEquals(" of", predict(InputLanguage.ENGLISH, "take care").first().commitText)
        assertEquals(" yourself", predict(InputLanguage.ENGLISH, "take care of").first().commitText)
        var request = "could "
        for (word in listOf("you", "please", "help")) {
            val next = predict(InputLanguage.ENGLISH, request).first()
            assertEquals(word, next.text)
            request += next.commitText
        }
        assertEquals("could you please help", request)
    }

    @Test fun `date qualified time phrases prefer arrangements over morning greetings`() {
        assertEquals("好", predict(InputLanguage.CHINESE, "上午").first().text)
        for (context in listOf("今天上午", "明天上午", "后天上午", "我们明天下午")) {
            assertEquals("见", predict(InputLanguage.CHINESE, context).first().text)
        }
        assertEquals("見", predict(InputLanguage.CHINESE, "後天上午").first().text)
    }

    @Test fun `malformed duplicate incomplete and oversized bundled tables fail closed`() {
        val valid = "zh\t你好\t世界\nen\tgood\tmorning\n"
        for (data in listOf(valid + "en\tgood\tmorning", valid + "en\tbad\twith space",
            valid + "ja\thi\tworld", "en\tgood\tmorning", valid + "#".repeat(129))) {
            assertThrows(IllegalArgumentException::class.java) { WordAssociationIndex.read(data.reader()) }
        }
    }

    private fun predict(language: InputLanguage, context: String) = index.suggest(language, context, 8)
}
