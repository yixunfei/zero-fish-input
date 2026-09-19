package dev.zeroinput.ime.ui

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class ExpressionBrowserStateTest {
    @Test fun catalogHasDistinctBoundedUnicodeEntriesAndPopulatedPublicCategories() {
        val entries = EmojiCatalog.entries
        assertTrue(entries.count { !it.isWide } >= 350)
        assertTrue(entries.count { it.isWide } >= 150)
        assertEquals(entries.size, entries.map { it.value }.distinct().size)
        assertTrue(entries.all { it.value.length in 1..128 && it.keywords.isNotBlank() })
        for (category in EmojiCategory.entries - setOf(EmojiCategory.RECENT, EmojiCategory.FAVORITES, EmojiCategory.CUSTOM)) {
            assertTrue(entries.any { it.category == category })
        }
    }

    @Test fun chineseEnglishPinyinAndExactTextFindTheSameKaomoji() {
        for (query in listOf("开心", "happy", "KAIXIN", "^_^")) {
            assertTrue(EmojiCatalog.search(query).any { it.value == "^_^" })
        }
        assertTrue(EmojiCatalog.search("heart").any { it.value == "❤️" })
        assertTrue(EmojiCatalog.search("no-match-fixture-123").isEmpty())
    }

    @Test fun builtinEmojiAreReachableByPinyin() {
        // The main catalog carries pinyin aliases, so common emoji are reachable
        // from a pinyin keyboard alongside AdditionalEmoji and kaomoji.
        val kaixin = EmojiCatalog.search("kaixin").map { it.value }
        assertTrue(kaixin.containsAll(listOf("😀", "😃", "😄")))
        assertTrue(EmojiCatalog.search("shengqi").any { it.value == "😡" })
        assertTrue(EmojiCatalog.search("zan").any { it.value == "👍" })
        assertTrue(EmojiCatalog.search("qingzhu").any { it.value == "🎉" })
        assertTrue(EmojiCatalog.search("pingguo").any { it.value == "🍎" })
        assertTrue(EmojiCatalog.search("wanan").any { it.value == "🌙" })
        assertTrue(EmojiCatalog.search("zhukuanbucunzai").isEmpty())
    }

    @Test fun filteringIsIndependentOfTheSystemLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals(EmojiCatalog.search("smile"), EmojiCatalog.search("SMILE"))
        } finally { Locale.setDefault(original) }
    }

    @Test fun kaomojiSubtagsRemainScopedWhileSearching() {
        val state = ExpressionBrowserState()
        state.category = EmojiCategory.KAOMOJI
        state.group = KaomojiGroup.SAD
        assertTrue(state.visible().all { it.group == KaomojiGroup.SAD })
        state.searchActive = true
        state.append("happy")
        assertTrue(state.visible().isEmpty())
    }

    @Test fun recentsKeepOrderAndDropRemovedCustomEntriesAndDuplicates() {
        val state = ExpressionBrowserState()
        val custom = custom()
        state.renderPersonal(true, PersonalExpressionsUi(listOf(custom)), listOf(custom.value, "😀", custom.value, "removed-fixture"))
        state.category = EmojiCategory.RECENT
        assertEquals(listOf(custom.value, "😀"), state.visible().map { it.value })
        state.renderPersonal(true, PersonalExpressionsUi(), listOf(custom.value, "😀"))
        assertEquals(listOf("😀"), state.visible().map { it.value })
    }

    @Test fun privacyRevocationDropsFavoritesCustomRecentsAndTheQuery() {
        val state = ExpressionBrowserState()
        val custom = custom()
        state.renderPersonal(true, PersonalExpressionsUi(listOf(custom), setOf(custom.value, "😀")), listOf(custom.value))
        state.searchActive = true
        state.append("fixture")
        state.renderPersonal(false, PersonalExpressionsUi(listOf(custom), setOf("😀")), listOf("😀"))
        assertEquals("", state.query)
        for (category in listOf(EmojiCategory.RECENT, EmojiCategory.FAVORITES, EmojiCategory.CUSTOM)) {
            state.category = category
            assertTrue(state.visible().isEmpty())
        }
        state.category = EmojiCategory.SMILEYS
        assertFalse(state.visible().isEmpty())
    }

    @Test fun favoritesShowBothBuiltinAndCustomAndCanBeSearched() {
        val state = ExpressionBrowserState()
        val custom = custom()
        state.renderPersonal(true, PersonalExpressionsUi(listOf(custom), setOf(custom.value, "😀")), emptyList())
        state.category = EmojiCategory.FAVORITES
        assertEquals(setOf(custom.value, "😀"), state.visible().map { it.value }.toSet())
        state.searchActive = true
        state.append("fixture")
        assertEquals(listOf(custom), state.visible())
    }

    @Test fun searchInputIsBoundedByCodePointsAndBackspaceKeepsSurrogatesIntact() {
        val state = ExpressionBrowserState()
        state.searchActive = true
        state.append("😀".repeat(80))
        assertEquals(64, state.query.codePointCount(0, state.query.length))
        state.backspace()
        assertEquals("😀".repeat(63), state.query)
        state.clearSession()
        assertEquals("", state.query)
        assertFalse(state.searchActive)
    }

    private fun custom() = EmojiEntry("(public-fixture)", EmojiCategory.CUSTOM, "fixture hello",
        KaomojiGroup.GREETINGS, "fixture-id", "Greeting")
}
