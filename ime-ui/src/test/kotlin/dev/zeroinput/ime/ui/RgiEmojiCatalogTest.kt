package dev.zeroinput.ime.ui

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RgiEmojiCatalogTest {
    private val assets = File("src/main/assets/emoji/18.0")
    private lateinit var catalog: List<EmojiEntry>

    @Before fun preparePublicData() {
        catalog = assets.resolve("catalog.tsv").inputStream().use(RgiEmojiData::read)
        EmojiCatalog.install(catalog)
    }

    @Test fun catalogExactlyMatchesEveryQualifiedOfficialSequenceAndComponent() {
        val expected = officialRows()
        assertEquals(3972, expected.size)
        assertEquals(3963, catalog.count { !it.isComponent })
        assertEquals(9, catalog.count { it.isComponent })
        assertEquals(expected.keys, catalog.map { it.value }.toSet())
        assertEquals(catalog.size, catalog.map { it.value }.distinct().size)
        catalog.forEach { assertEquals(expected[it.value] == "component", it.isComponent) }
        assertTrue(catalog.all { it.artworkKey != null && it.name.isNotBlank() && it.englishName.isNotBlank() })
    }

    @Test fun everyCatalogSequenceHasOneChecksummedOfflineWebpWithoutExtraImages() {
        val keys = catalog.map { requireNotNull(it.artworkKey) }.toSet()
        assertEquals(keys, assets.resolve("images").listFiles().orEmpty().map { it.nameWithoutExtension }.toSet())
        val checksums = assets.resolve("checksums.sha256").readLines().associate {
            val fields = it.split("  ", limit = 2)
            fields[1] to fields[0]
        }
        assertEquals(3973, checksums.size)
        checksums.forEach { (path, expected) ->
            val bytes = assets.resolve(path).readBytes()
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(expected, actual)
            if (path.endsWith(".webp")) {
                assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
                assertEquals("WEBP", bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII))
            }
        }
    }

    @Test fun searchFindsOfficialEnglishChineseTraditionalAndExistingPinyin() {
        for (query in listOf("penguin", "企鹅", "企鵝")) {
            assertTrue(EmojiCatalog.search(query).any { it.value == "🐧" })
        }
        assertTrue(EmojiCatalog.search("1F427").any { it.value == "🐧" })
        assertTrue(EmojiCatalog.search("kaixin").any { it.value == "😀" })
        assertTrue(EmojiCatalog.search("裂开的脸").any { it.englishName == "cracking face" })
        assertTrue(EmojiCatalog.search("eraser").any { it.name == "橡皮擦" })
        assertTrue(EmojiCatalog.search("🇨🇳").any { it.category == EmojiCategory.FLAGS })
    }

    @Test fun skinGenderFamilyAndMixedToneHandshakeVariantsRemainExactSequences() {
        fun variants(value: String) = EmojiCatalog.variants(requireNotNull(EmojiCatalog.find(value))).map { it.value }.toSet()
        assertTrue(variants("🧑‍⚕️").containsAll(listOf("👩🏻‍⚕️", "👨🏿‍⚕️", "🧑‍⚕️")))
        assertTrue(variants("👨‍👩‍👧‍👦").containsAll(listOf("👨‍👩‍👧‍👦", "👩‍👩‍👦")))
        assertTrue(variants("🤝").containsAll(listOf("🤝", "🤝🏽", "🫱🏻‍🫲🏿")))
        assertTrue(variants("💏").containsAll(listOf("💏", "👩‍❤️‍💋‍👩")))
        assertTrue(variants("👩").containsAll(listOf("🧑", "👨", "👩‍🦰")))
    }

    @Test fun suffixLookupDeletesEveryWholeRgiSequenceRegardlessOfPlatformUnicodeVersion() {
        assertEquals(catalog.maxOf { it.value.length }, EmojiCatalog.maximumEmojiUtf16Length)
        assertTrue(EmojiCatalog.maximumEmojiUtf16Length <= 128)
        catalog.forEach { entry ->
            assertEquals(entry.value.length, EmojiCatalog.longestEmojiSuffixLength("public-prefix" + entry.value))
        }
        assertEquals("👩🏽‍🚀".length, EmojiCatalog.longestEmojiSuffixLength("🐧👩🏽‍🚀"))
        assertEquals(0, EmojiCatalog.longestEmojiSuffixLength("(ಠ_ಠ)"))
        assertEquals(0, EmojiCatalog.longestEmojiSuffixLength("text"))
        assertEquals(0, EmojiCatalog.longestEmojiSuffixLength(""))
        assertEquals(0, EmojiSuffixMatcher(KaomojiCatalog.entries).longestSuffixLength("^_^"))
    }

    @Test fun invalidMissingDuplicateAndTamperedCatalogDataFailClosed() {
        val valid = assets.resolve("catalog.tsv").readText()
        val rows = valid.lines().filter { it.isNotEmpty() && !it.startsWith('#') }
        assertThrows(IllegalArgumentException::class.java) { RgiEmojiData.parse(rows.dropLast(1).joinToString("\n")) }
        assertThrows(IllegalArgumentException::class.java) { RgiEmojiData.parse(rows.dropLast(1).plus(rows.first()).joinToString("\n")) }
        assertThrows(IllegalArgumentException::class.java) { RgiEmojiData.parse(valid.replaceFirst("SMILEYS", "CUSTOM")) }
        assertThrows(IllegalArgumentException::class.java) { RgiEmojiData.parse(valid.replaceFirst("1f600", "../escape")) }
        assertThrows(IllegalArgumentException::class.java) { RgiEmojiData.read((valid + " ").byteInputStream()) }
    }

    @Test fun capturedSearchCannotReadLaterPersonalStateAndRevocationHidesPrivateRows() {
        val custom = EmojiEntry("public-fixture", EmojiCategory.CUSTOM, "fixture", customId = "fixture")
        val state = ExpressionBrowserState()
        state.renderPersonal(true, PersonalExpressionsUi(listOf(custom), setOf(custom.value)), listOf(custom.value))
        state.category = EmojiCategory.FAVORITES
        val old = state.request()
        state.renderPersonal(false, PersonalExpressionsUi(), emptyList())
        val current = state.request()
        assertEquals(listOf(custom), old.resolve())
        assertTrue(current.resolve().isEmpty())
        state.category = EmojiCategory.PEOPLE
        assertTrue(state.visible().any { it.value == "👍🏽" })
    }

    @Test fun savedTextPresentationRemainsExactWhileCatalogContainsOnlyQualifiedSequences() {
        val state = ExpressionBrowserState()
        state.renderPersonal(true, PersonalExpressionsUi(favorites = setOf("∞")), listOf("∞"))
        for (category in listOf(EmojiCategory.FAVORITES, EmojiCategory.RECENT)) {
            state.category = category
            val saved = state.visible().single()
            assertEquals("∞", saved.value)
            assertNull(saved.artworkKey)
            assertEquals(EmojiCatalog.find(saved.value), saved)
        }
        assertFalse(catalog.any { it.value == "∞" })
        assertTrue(catalog.any { it.value == "♾️" })
    }

    @Test fun queuedQueriesDoNotAliasMutableHostCollections() {
        val custom = EmojiEntry("fixture", EmojiCategory.CUSTOM, "fixture", customId = "fixture")
        val supplied = mutableListOf(custom)
        val favorites = mutableSetOf(custom.value)
        val state = ExpressionBrowserState()
        state.renderPersonal(true, PersonalExpressionsUi(supplied, favorites), emptyList())
        state.category = EmojiCategory.FAVORITES
        val captured = state.request()
        supplied.clear()
        favorites.clear()
        assertEquals(listOf(custom), captured.resolve())
    }

    private fun officialRows(): Map<String, String> = buildMap {
        File("src/test/resources/emoji/18.0/emoji-test.txt").forEachLine { line ->
            if (line.isNotBlank() && !line.startsWith('#')) {
                val parts = line.substringBefore('#').split(';').map(String::trim)
                if (parts.size == 2 && parts[1] in setOf("fully-qualified", "component")) {
                    val value = buildString { parts[0].split(' ').forEach { appendCodePoint(it.toInt(16)) } }
                    put(value, parts[1])
                }
            }
        }
    }
}
