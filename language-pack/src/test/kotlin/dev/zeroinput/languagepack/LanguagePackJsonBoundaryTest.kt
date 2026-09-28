package dev.zeroinput.languagepack

import java.nio.file.Files
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class LanguagePackJsonBoundaryTest {
    @Test fun deeplyNestedManifestIsRejectedBeforeRecursiveParsing() {
        val nested = "[".repeat(10_000) + "0" + "]".repeat(10_000)
        assertThrows(IllegalArgumentException::class.java) {
            LanguagePackParser.parse("{\"extra\":$nested}")
        }
    }

    @Test fun manifestRejectsTrailingDocuments() {
        for (suffix in listOf(" {}", "\u0000{}")) {
            assertThrows(IllegalArgumentException::class.java) {
                LanguagePackParser.parse(manifest() + suffix)
            }
        }
    }

    @Test fun commentsCannotHideNestingFromTheDepthCheck() {
        for (prefix in listOf("/* \" */", "# \"\n", "// \"\n")) {
            assertThrows(IllegalArgumentException::class.java) {
                LanguagePackParser.parse(prefix + manifest())
            }
        }
    }

    @Test fun unquotedTokensCannotHideNestedContainersInsideQuotes() {
        val nested = "[".repeat(32) + "0" + "]".repeat(32)
        assertThrows(IllegalArgumentException::class.java) {
            LanguagePackJson.parse("{x\":$nested,y\":0}")
        }
    }

    @Test fun nonJsonTokensAndBareKeysAreRejected() {
        val invalid = listOf(
            "{'key':1}", "{key:1}", "{true:1}", "{1:1}",
            "[TRUE]", "[undefined]", "[NaN]", "[01]", "[0x10]",
            "[+1]", "[1.]", "[.1]", "[1e]", "[\"\\x41\"]", "[\"line\nfeed\"]",
        )
        for (json in invalid) {
            assertThrows(json, IllegalArgumentException::class.java) { LanguagePackJson.parse(json) }
        }
    }

    @Test fun malformedStructureAndDuplicateKeysAreRejected() {
        for (json in listOf(
            "[1,]", "[,1]", "[1,,2]", "{\"key\":1,}",
            "{\"key\":1,\"key\":2}", "{\"key\":1,\"\\u006bey\":2}",
        )) {
            assertThrows(json, IllegalArgumentException::class.java) { LanguagePackJson.parse(json) }
        }
    }

    @Test fun manifestRejectsCoercedAndFractionalFields() {
        val invalid = listOf(
            manifest().replace("\"formatVersion\":1", "\"formatVersion\":1.9"),
            manifest().replace("\"formatVersion\":1", "\"formatVersion\":\"1\""),
            manifest().replace("\"size\":0", "\"size\":0.9"),
            manifest().replace("\"id\":\"fixture\"", "\"id\":true"),
        )
        invalid.forEach { document ->
            assertThrows(document, IllegalArgumentException::class.java) { LanguagePackParser.parse(document) }
        }
    }

    @Test fun quotedBracketsAndEscapedQuotesDoNotIncreaseDepth() {
        val json = manifest().replace("Fixture", "[ \\\" { \\\" ]")
        assertEquals("[ \" { \" ]", LanguagePackParser.parse(json).displayName)
    }

    @Test fun invalidJsonCannotBeActivatedAsALineDictionary() {
        for (json in listOf(
            "{\"ni\":\"你\"} {}",
            "[".repeat(10_000) + "{\"ni\":\"你\"}" + "]".repeat(10_000),
            "ni\t你\n",
        )) {
            val directory = Files.createTempDirectory("pack-json-boundary").toFile()
            try {
                val dictionary = File(directory, "dictionary.json").apply { writeText(json) }
                val pack = InstalledLanguagePack(
                    LanguagePackManifest(1, "fixture", "Fixture", "zh", "1", "fixture",
                        listOf(LanguagePackFile(dictionary.name, "0".repeat(64), dictionary.length()))),
                    directory,
                )
                assertFalse(LanguagePackEngineFactory(pack).isAvailable())
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun invalidJsonDisablesTheWholePackEvenAfterValidTextEntries() {
        val directory = Files.createTempDirectory("pack-json-atomic").toFile()
        try {
            val text = File(directory, "dictionary.txt").apply { writeText("ni\t你\n") }
            val json = File(directory, "dictionary.json").apply { writeText("{\"hao\":\"好\"} {}") }
            val pack = InstalledLanguagePack(
                LanguagePackManifest(1, "fixture", "Fixture", "zh", "1", "fixture",
                    listOf(text, json).map { LanguagePackFile(it.name, "0".repeat(64), it.length()) }),
                directory,
            )
            assertFalse(LanguagePackEngineFactory(pack).isAvailable())
        } finally { directory.deleteRecursively() }
    }

    @Test fun jsonValidationIsNotSkippedAfterTheEntryLimit() {
        val directory = Files.createTempDirectory("pack-json-entry-limit").toFile()
        try {
            val text = File(directory, "dictionary.txt").apply {
                bufferedWriter().use { writer ->
                    repeat(50_000) { writer.appendLine("key$it\tvalue$it") }
                }
            }
            val json = File(directory, "dictionary.json").apply { writeText("{\"bad\":true} {}") }
            assertFalse(factory(directory, listOf(text, json)).isAvailable())
        } finally { directory.deleteRecursively() }
    }

    @Test fun jsonCannotBeSkippedBecauseItsDeclaredSizeExceedsTheFileBudget() {
        val directory = Files.createTempDirectory("pack-json-byte-limit").toFile()
        try {
            val text = File(directory, "dictionary.txt").apply { writeText("ni\t你\n") }
            val json = File(directory, "dictionary.json").apply { writeText("{}") }
            assertFalse(factory(directory, listOf(text, json), mapOf(json.name to 16L * 1024 * 1024 + 1))
                .isAvailable())
        } finally { directory.deleteRecursively() }
    }

    @Test fun jsonCannotBeSkippedWhenTheTotalTextBudgetIsExhausted() {
        val directory = Files.createTempDirectory("pack-json-total-limit").toFile()
        try {
            val first = File(directory, "first.json").apply { writeText("{\"ni\":\"你\"}") }
            val second = File(directory, "second.json").apply { writeText("{}") }
            val third = File(directory, "third.json").apply { writeText("{}") }
            val budget = 16L * 1024 * 1024
            assertFalse(factory(directory, listOf(first, second, third),
                mapOf(first.name to budget, second.name to budget)).isAvailable())
        } finally { directory.deleteRecursively() }
    }

    private fun factory(directory: File, files: List<File>, sizes: Map<String, Long> = emptyMap()) =
        LanguagePackEngineFactory(InstalledLanguagePack(
            LanguagePackManifest(1, "fixture", "Fixture", "zh", "1", "fixture", files.map {
                LanguagePackFile(it.name, "0".repeat(64), sizes[it.name] ?: it.length())
            }), directory,
        ))

    private fun manifest(): String = """
        {
          "formatVersion":1,"id":"fixture","displayName":"Fixture","languageTag":"zh",
          "version":"1","engineId":"fixture",
          "files":[{"path":"dictionary.txt","sha256":"${"0".repeat(64)}","size":0}]
        }
    """.trimIndent()
}
