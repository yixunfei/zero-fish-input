package dev.zeroinput.engine.dictionary.importer

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class PublicDictionaryParserTest {
    @Test fun cellRecordTruncationUsesTheExistingBoundedFormatFailure() {
        val bytes = java.nio.ByteBuffer.allocate(0x2628 + 14).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x1540)
            put(byteArrayOf(0x44, 0x43, 0x53, 1))
            position(0x1540)
            putInt(720)
            repeat(720) { index ->
                putShort(index.toShort())
                val text = (if (index == 719) "hao" else "a").toByteArray(Charsets.UTF_16LE)
                putShort(text.size.toShort()); put(text)
            }
            assertEquals(0x2628, position())
            for (value in listOf(1, 2, 0, 2, '你'.code, 2, 1)) putShort(value.toShort())
        }.array()
        val parser = CellDictionaryParser(CellDictionaryParser.Source.QQ)
        val rows = mutableListOf<PublicDictionaryEntry>()
        parser.parse(bytes.inputStream(), rows::add)
        assertEquals(listOf(PublicDictionaryEntry("你", "a", 1)), rows)
        for (removed in 1..13) assertThrows(IllegalArgumentException::class.java) {
            parser.parse(bytes.copyOf(bytes.size - removed).inputStream()) {}
        }
    }

    @Test(timeout = 1000) fun zeroProgressCellStreamFailsWithoutSpinning() {
        val input = object : java.io.InputStream() {
            override fun read() = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int) = 0
        }
        assertThrows(IllegalArgumentException::class.java) {
            CellDictionaryParser(CellDictionaryParser.Source.QQ).parse(input) {}
        }
    }

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
