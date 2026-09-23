package dev.zeroinput.ime.ui

/** Bounded, in-memory filtering only. The host owns all personal data access. */
internal class ExpressionBrowserState {
    var category = EmojiCategory.SMILEYS
    var group: KaomojiGroup? = null
    var searchActive = false
    var query = ""
        private set
    var personalizationAllowed = false
        private set
    var personal = PersonalExpressionsUi()
        private set
    private var recent = emptyList<String>()
    // visible() runs on every expression-panel keystroke.  The catalog is
    // constant and personal data only changes through renderPersonal, so the
    // concatenated list and the RECENT index are rebuilt only at that point
    // instead of on every call.
    private var allCache: List<EmojiEntry>? = null
    private var recentIndexCache: Map<String, EmojiEntry>? = null

    fun renderPersonal(allowed: Boolean, data: PersonalExpressionsUi, values: List<String>) {
        if (!allowed && personalizationAllowed) clearQuery()
        personalizationAllowed = allowed
        personal = if (allowed) data else PersonalExpressionsUi()
        recent = if (allowed) values.take(128).distinct() else emptyList()
        allCache = null
        recentIndexCache = null
    }

    fun append(value: String) {
        if (!searchActive) return
        val room = 64 - query.codePointCount(0, query.length)
        if (room <= 0) return
        val count = minOf(room, value.codePointCount(0, value.length))
        query += value.substring(0, value.offsetByCodePoints(0, count))
    }

    fun backspace() {
        if (searchActive && query.isNotEmpty()) query = query.substring(0, query.offsetByCodePoints(query.length, -1))
    }

    fun clearQuery() { query = "" }

    fun visible(): List<EmojiEntry> {
        val all = allCache ?: (EmojiCatalog.entries + personal.custom).also { allCache = it }
        val source = when {
            category == EmojiCategory.RECENT -> {
                val indexed = recentIndexCache
                    ?: all.associateBy(EmojiEntry::value).also { recentIndexCache = it }
                recent.mapNotNull(indexed::get)
            }
            category == EmojiCategory.FAVORITES -> all.filter { it.value in personal.favorites }
            category == EmojiCategory.CUSTOM -> personal.custom
            category == EmojiCategory.KAOMOJI -> all.filter { it.isWide && (group == null || it.group == group) }
            searchActive -> all
            else -> all.filter { it.category == category }
        }
        return if (searchActive) EmojiCatalog.search(query, source) else source
    }

    fun clearSession() {
        renderPersonal(false, PersonalExpressionsUi(), emptyList())
        query = ""
        searchActive = false
    }
}
