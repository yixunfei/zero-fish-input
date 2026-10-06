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

    fun renderPersonal(allowed: Boolean, data: PersonalExpressionsUi, values: List<String>) {
        if (!allowed && personalizationAllowed) clearQuery()
        personalizationAllowed = allowed
        personal = if (allowed) PersonalExpressionsUi(data.custom.toList(), data.favorites.toSet()) else PersonalExpressionsUi()
        recent = if (allowed) values.take(128).distinct() else emptyList()
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

    fun replaceQuery(value: String) { clearQuery(); append(value) }

    /** Capture on the UI thread; resolve on the panel worker without retaining this mutable state. */
    fun request() = ExpressionQuery(EmojiCatalog.snapshot, category, group, searchActive, query, personal, recent)

    fun visible(): List<EmojiEntry> = request().resolve()

    fun clearSession() {
        renderPersonal(false, PersonalExpressionsUi(), emptyList())
        query = ""
        searchActive = false
    }
}

internal data class ExpressionQuery(
    val catalog: EmojiCatalogSnapshot,
    val category: EmojiCategory,
    val group: KaomojiGroup?,
    val searchActive: Boolean,
    val query: String,
    val personal: PersonalExpressionsUi,
    val recent: List<String>,
) {
    fun resolve(): List<EmojiEntry> {
        val source = when {
            category == EmojiCategory.RECENT -> {
                val custom = personal.custom.associateBy(EmojiEntry::value)
                recent.mapNotNull { custom[it] ?: catalog.find(it) }
            }
            category == EmojiCategory.FAVORITES ->
                personal.favorites.mapNotNull(catalog::find) + personal.custom.filter { it.value in personal.favorites }
            category == EmojiCategory.CUSTOM -> personal.custom
            category == EmojiCategory.KAOMOJI ->
                (catalog.categories[EmojiCategory.KAOMOJI].orEmpty() + personal.custom)
                    .filter { group == null || it.group == group }
            searchActive -> catalog.entries + personal.custom
            else -> catalog.categories[category].orEmpty()
        }
        return if (searchActive) EmojiCatalog.search(query, source) else source
    }
}
