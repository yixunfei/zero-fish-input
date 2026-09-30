package dev.zeroinput.ime.ui

import android.content.Context
import java.io.Closeable
import java.util.Collections
import java.util.Locale

/** Immutable public data only; personal entries and history remain owned by the host. */
object EmojiCatalog {
    // Mathematical infinity is a plain text expression, distinct from the RGI infinity emoji U+267E.
    private val textSymbols = listOf(EmojiEntry("∞", EmojiCategory.SYMBOLS, "无限 infinity wuxian",
        name = "无限", englishName = "infinity"))
    @Volatile internal var snapshot = EmojiCatalogSnapshot(KaomojiCatalog.entries + textSymbols, complete = false)
        private set
    val entries: List<EmojiEntry> get() = snapshot.entries
    val isReady: Boolean get() = snapshot.complete
    val unicodeVersion: String get() = RgiEmojiVersion.VERSION
    val maximumEmojiUtf16Length: Int get() = RgiEmojiVersion.MAX_UTF16_LENGTH
    private val queryWhitespace = Regex("\\s+")

    /** Starts bounded background preparation; closing the handle removes its UI callback. */
    fun prepare(context: Context, onReady: (Boolean) -> Unit = {}): Closeable =
        EmojiCatalogLoader.prepare(context.applicationContext, onReady)

    fun find(value: String): EmojiEntry? = snapshot.find(value)

    /** Longest complete RGI/component suffix in UTF-16 units; zero until preparation succeeds. */
    fun longestEmojiSuffixLength(text: CharSequence): Int = snapshot.suffixMatcher.longestSuffixLength(text)

    fun search(query: String): List<EmojiEntry> = search(query, entries)

    /** Call off the input thread when filtering the complete catalog. */
    fun search(query: String, source: List<EmojiEntry>): List<EmojiEntry> {
        val terms = query.take(128).trim().lowercase(Locale.ROOT).split(queryWhitespace)
            .filter(String::isNotEmpty).take(16)
        if (terms.isEmpty()) return source
        return source.filter { entry -> terms.all { it in entry.searchText } }
    }

    fun recent(values: List<String>): List<EmojiEntry> = values.take(128).distinct().mapNotNull(snapshot::find)

    internal fun variants(entry: EmojiEntry): List<EmojiEntry> =
        entry.variantKey?.let { snapshot.variants[it] }.orEmpty()

    internal fun install(entries: List<EmojiEntry>) {
        snapshot = EmojiCatalogSnapshot(entries + KaomojiCatalog.entries + textSymbols, complete = true)
    }
}

internal class EmojiCatalogSnapshot(entries: List<EmojiEntry>, val complete: Boolean) {
    val suffixMatcher = EmojiSuffixMatcher(entries)
    val entries: List<EmojiEntry> = Collections.unmodifiableList(entries.toList())
    val byValue: Map<String, EmojiEntry> = Collections.unmodifiableMap(entries.associateBy(EmojiEntry::value))
    private val unqualified = entries.filter { it.artworkKey != null && '\uFE0F' in it.value }
        .associateBy { it.value.replace("\uFE0F", "") }
    val categories: Map<EmojiCategory, List<EmojiEntry>> = Collections.unmodifiableMap(entries.groupBy(EmojiEntry::category)
        .mapValues { Collections.unmodifiableList(it.value) })
    val variants: Map<String, List<EmojiEntry>> = Collections.unmodifiableMap(entries.filter { it.variantKey != null }
        .groupBy { requireNotNull(it.variantKey) }.mapValues { Collections.unmodifiableList(it.value) })

    // Saved text remains byte-for-byte identical while sharing the qualified sequence's artwork.
    fun find(value: String): EmojiEntry? = byValue[value] ?: unqualified[value]?.copy(value = value)
}
