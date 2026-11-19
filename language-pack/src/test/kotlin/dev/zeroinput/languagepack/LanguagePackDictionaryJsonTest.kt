package dev.zeroinput.languagepack

import java.io.Reader
import org.junit.Assert.*
import org.junit.Test

class LanguagePackDictionaryJsonTest {
    @Test fun dictionaryShapesKeepRowsAndExplicitRecordsOverrideTheirOtherFields() {
        val json = """[
          {"ni":["你", "妮"]},
          {"wrapped":[{"shortcut":"hao","value":"好"}]},
          ["zhong","中"], ["国","guo"],
          {"record":{"extra":"ignored","value":"是","shortcut":"shi"}}
        ]"""
        assertEquals(setOf("ni" to "你", "ni" to "妮", "hao" to "好", "zhong" to "中", "guo" to "国", "shi" to "是"),
            read(json).map { it.shortcut to it.value }.toSet())
    }

    @Test fun repeatedRecordsAreFoldedWithoutRetainingTheDocumentOrDuplicateObjects() {
        val row = """{"shortcut":"ni","value":"public"}"""
        // Generates the former 14 MB OOM input without allocating it in the test.
        val reader = object : Reader() {
            private var position = -1
            private val body = "$row,"
            private val end = body.length * 400_000
            override fun read(): Int {
                position++
                return when {
                    position == 0 -> '['.code
                    position == end -> ']'.code
                    position > end -> -1
                    else -> body[(position - 1) % body.length].code
                }
            }
            override fun read(target: CharArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                var count = 0
                while (count < length) { val value = read(); if (value < 0) break; target[offset + count++] = value.toChar() }
                return if (count == 0) -1 else count
            }
            override fun close() = Unit
        }
        assertEquals(setOf(PackDictionaryEntry("ni", "public")), LanguagePackDictionaryJson.read(reader, 50_000))
    }

    @Test fun invalidTailAndDuplicateKeysStillFailAfterTheOutputBudget() {
        for (json in listOf("""[{"ni":"你"}, {"bad":true}] {}""",
            """[{"ni":"你"}, {"key":1,"\u006bey":2}]""", """[{"ni":"你"}, [1,]]""",
            """{"ni":"\u１２３４"}""")) {
            assertThrows(IllegalArgumentException::class.java) { read(json, limit = 0) }
        }
    }

    @Test fun oversizedDictionaryValuesAreSkippedWithoutDroppingValidRows() {
        val large = "a".repeat(100_000)
        val json = """[{"shortcut":"bad","value":"$large"},{"ni":"你"},["$large","bad"],{"hao":"好"}]"""
        assertEquals(setOf("ni" to "你", "hao" to "好"), read(json).map { it.shortcut to it.value }.toSet())
        assertThrows(IllegalArgumentException::class.java) { read("""[{"bad":"$large\q"},{"ni":"你"}]""") }
        assertThrows(IllegalArgumentException::class.java) { read("""[{"bad":"$large"}, [1,]]""") }
        assertThrows(IllegalArgumentException::class.java) { read("{\"$large\":0}") }
        assertEquals(setOf(PackDictionaryEntry("ni", "你")), read("""[{"bad":1${"0".repeat(5000)}},{"ni":"你"}]"""))
        assertThrows(IllegalArgumentException::class.java) { read("""[{"bad":1${"0".repeat(5000)}e},{"ni":"你"}]""") }
    }

    @Test fun objectKeyBudgetsFailBeforeLargeTreesCanBeAllocated() {
        val keys = (0..50_000).joinToString(",") { "\"key$it\":0" }
        assertThrows(IllegalArgumentException::class.java) { read("{$keys}") }
    }

    private fun read(json: String, limit: Int = 50_000) = LanguagePackDictionaryJson.read(json.reader(), limit)
}
