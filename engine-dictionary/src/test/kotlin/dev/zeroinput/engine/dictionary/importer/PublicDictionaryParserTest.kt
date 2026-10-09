package dev.zeroinput.engine.dictionary.importer

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class PublicDictionaryParserTest {
    @Test fun tonesAndUmlautsNormalizeWithoutChangingWords() {
        val entries = parse("---\nname: fixture\n...\n女儿\tnǚ ér\t42\n")
        assertEquals(PublicDictionaryEntry("女儿", "nv er", 42), entries.single())
    }

    @Test fun missingReadingsRemainAvailableForPublicRimeCompletion() {
        assertEquals("", parse("---\nname: fixture\n...\n测试\t\t3\n").single().reading)
    }

    @Test fun declaredColumnOrderIsRespected() {
        assertEquals(PublicDictionaryEntry("测试", "ce shi", 12),
            parse("---\ncolumns: [code, text, weight]\n...\nce shi\t测试\t12\n").single())
    }

    @Test fun unsafeYamlAndMalformedRowsFailClosed() {
        for (source in listOf(
            "---\n!!java.lang.ProcessBuilder {}\n...\na\ta\t1\n",
            "---\nname: one\nname: two\n...\na\ta\t1\n",
            "---\nname: a\n...\na\ta\tNaN\n",
            "---\nname: a\n...\na\ta\t-1\n",
            "---\nname: a\n...\n" + "a".repeat(9000),
            "---\nname: a\n...\n",
        )) assertThrows(Exception::class.java) { parse(source) }
    }

    @Test fun invalidUtf8DoesNotBecomeReplacementText() {
        val bytes = "---\nname: a\n...\n".toByteArray() + byteArrayOf(0xc0.toByte(), 0xaf.toByte())
        assertThrows(Exception::class.java) { RimeTextDictionaryParser().parse(ByteArrayInputStream(bytes)) {} }
    }

    @Test fun truncatedCellDictionaryIsRejected() {
        assertThrows(Exception::class.java) {
            CellDictionaryParser(CellDictionaryParser.Source.QQ).parse(ByteArrayInputStream(ByteArray(12))) {}
        }
    }

    @Test fun verifiedLocalPublicSampleParsesAllRowsWhenProvided() {
        val path = System.getProperty("dictionary.sample") ?: return
        val entries = ArrayList<PublicDictionaryEntry>()
        File(path).inputStream().use { CellDictionaryParser(CellDictionaryParser.Source.QQ).parse(it, entries::add) }
        assertEquals(66418, entries.size)
        assertTrue(entries.all { it.reading.isNotEmpty() })
    }

    @Test fun sogouPublicSampleParsesWhenProvided() {
        val path = System.getProperty("dictionary.sogouSample") ?: return
        var count = 0
        File(path).inputStream().use { CellDictionaryParser(CellDictionaryParser.Source.SOGOU).parse(it) { count++ } }
        assertTrue(count > 100)
    }

    private fun parse(source: String): List<PublicDictionaryEntry> = buildList {
        RimeTextDictionaryParser().parse(source.byteInputStream(), ::add)
    }
}
