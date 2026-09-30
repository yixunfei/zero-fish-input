package dev.zeroinput.ime.expressions

import dev.zeroinput.ime.core.KaomojiCandidateSource
import dev.zeroinput.ime.core.KaomojiSuggestion
import dev.zeroinput.ime.ui.EmojiCatalog
import dev.zeroinput.ime.ui.EmojiCategory
import dev.zeroinput.ime.ui.EmojiEntry
import dev.zeroinput.ime.ui.PersonalExpressionsUi
import java.util.Locale

/** Immutable public lookup; the caller supplies a current personal snapshot. */
internal class KaomojiCandidateIndex(
    private val personal: () -> PersonalExpressionsUi,
) : KaomojiCandidateSource {
    private val public = index(EmojiCatalog.entries.filter { it.category == EmojiCategory.KAOMOJI })

    override fun suggestions(input: String, personalAllowed: Boolean): List<KaomojiSuggestion> {
        if (input.length !in 2..32 || input.any { it !in 'a'..'z' }) return emptyList()
        val personalSuggestions = if (personalAllowed) {
            personal().custom.asSequence().filter { input in tokens(it.keywords) }
                .take(2).map { KaomojiSuggestion("custom:${it.customId}", it.value, true) }.toList()
        } else emptyList()
        val publicSuggestions = public[input].orEmpty().mapIndexed { index, entry ->
            KaomojiSuggestion("public:$input:$index", entry.value)
        }
        return (personalSuggestions + publicSuggestions).distinctBy { it.text }.take(4)
    }

    private fun index(entries: List<EmojiEntry>): Map<String, List<EmojiEntry>> = buildMap {
        entries.forEach { entry ->
            tokens(entry.keywords + " " + entry.group?.keywords.orEmpty()).forEach { keyword ->
                if (keyword.length in 2..32 && keyword.all { it in 'a'..'z' }) {
                    val matches = get(keyword).orEmpty()
                    if (matches.size < 4 && entry !in matches) put(keyword, matches + entry)
                }
            }
        }
    }

    private fun tokens(value: String): List<String> =
        value.lowercase(Locale.ROOT).split(' ', '\t', '\n').filter(String::isNotBlank)
}
