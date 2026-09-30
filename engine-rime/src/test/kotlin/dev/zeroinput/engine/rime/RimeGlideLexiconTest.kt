package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.GlideLayout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException

class RimeGlideLexiconTest {
    private val dictionary = "---\nname: fixture\n...\n你好\tni hao\n明天\tming tian\n西安\txi an\n先\txian\n谢谢\txie xie\n"
    private val weights = "你好\t200\n明天\t120\n西安\t90\n先\t80\n谢谢\t110\n"

    @Test fun `phrases preserve full double and nine-key codes with public reading labels`() {
        val rows = RimeGlideLexicon.read(dictionary.reader(), weights.reader())
        val hello = rows.filter { it.displayText == "ni hao" }
        assertEquals(4, hello.size)
        assertEquals("nihao", hello.first { it.layout == GlideLayout.PINYIN_QWERTY }.inputCode)
        assertEquals("nihk", hello.first { it.layout == GlideLayout.DOUBLE_PINYIN_MICROSOFT }.inputCode)
        assertEquals("nihk", hello.first { it.layout == GlideLayout.DOUBLE_PINYIN_ZIRANMA }.inputCode)
        assertEquals("64426", hello.first { it.layout == GlideLayout.PINYIN_NINE_KEY }.inputCode)
        assertEquals("m;tm", rows.first { it.layout == GlideLayout.DOUBLE_PINYIN_MICROSOFT && it.displayText == "ming tian" }.inputCode)
        assertEquals("mytm", rows.first { it.layout == GlideLayout.DOUBLE_PINYIN_ZIRANMA && it.displayText == "ming tian" }.inputCode)
        assertTrue(rows.any { it.inputCode == "xxxx" && it.layout == GlideLayout.DOUBLE_PINYIN_ZIRANMA })
    }

    @Test fun `ambiguous pinyin codes retain strongest public pronunciation without duplicates`() {
        val rows = RimeGlideLexicon.read(dictionary.reader(), weights.reader())
        assertEquals("xi an", rows.first { it.layout == GlideLayout.PINYIN_QWERTY && it.inputCode == "xian" }.displayText)
        assertEquals(rows.size, rows.map { it.layout to it.inputCode }.distinct().size)
    }

    @Test fun `preset vocabulary expands bounded polyphone alternatives and preserves explicit phrase readings`() {
        val source = "...\n你\tni\n好\thao\n行\txing\t90%\n行\thang\t10%\n為\twei\n行為\txing wei\n"
        val rows = RimeGlideLexicon.read(source.reader(), "你好\t300\n好行\t120\n行為\t400\n".reader())
        val full = rows.filter { it.layout == GlideLayout.PINYIN_QWERTY }.associateBy { it.inputCode }
        assertTrue("nihao" in full)
        assertTrue("haoxing" in full && "haohang" in full)
        assertTrue(full.getValue("haoxing").frequency > full.getValue("haohang").frequency)
        assertTrue("xingwei" in full)
        assertFalse("hangwei" in full)
    }

    @Test fun `source bounds malformed rows and cancellation fail closed`() {
        for (source in listOf("no data section", "...\nmalformed", "...\n" + "x".repeat(1025))) {
            assertThrows(IllegalArgumentException::class.java) { RimeGlideLexicon.read(source.reader(), weights.reader()) }
        }
        for (source in listOf("你好\t-1", "你好\tinvalid", "你好\t4\textra")) {
            assertThrows(IllegalArgumentException::class.java) { RimeGlideLexicon.read(dictionary.reader(), source.reader()) }
        }
        assertThrows(CancellationException::class.java) {
            RimeGlideLexicon.read(dictionary.reader(), weights.reader()) { true }
        }
    }

    @Test fun `pinned production data includes public multi syllable vocabulary in every layout`() {
        File("src/main/assets/rime/luna_pinyin.dict.yaml").reader(Charsets.UTF_8).use { dictionaryReader ->
            File("src/main/assets/rime/essay.txt").reader(Charsets.UTF_8).use { frequenciesReader ->
                val rows = RimeGlideLexicon.read(dictionaryReader, frequenciesReader)
                for (layout in GlideLayout.entries.filter { it != GlideLayout.ENGLISH_QWERTY }) {
                    val matching = rows.filter { it.layout == layout }
                    assertTrue("Insufficient public phrase coverage", matching.size > 5_000)
                    assertTrue(matching.any { it.displayText == "ni hao" })
                    assertTrue(matching.any { it.displayText.count { c -> c == ' ' } >= 3 })
                }
            }
        }
    }
}
