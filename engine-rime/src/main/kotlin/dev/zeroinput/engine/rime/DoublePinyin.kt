package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.DoublePinyinScheme

/** Maps a public dictionary syllable to the physical keys of a scheme. */
internal object DoublePinyin {
    private val finals = listOf(
        "iang" to 'd', "uang" to 'd', "iong" to 's', "uai" to 'y',
        "ian" to 'm', "iao" to 'c', "uan" to 'r', "van" to 'r',
        "ing" to 'y', "ong" to 's', "eng" to 'g', "ang" to 'h',
        "uen" to 'p', "un" to 'p', "vn" to 'p',
        "iu" to 'q', "ia" to 'w', "ua" to 'w', "ue" to 't', "ve" to 't',
        "uo" to 'o', "ui" to 'v', "ie" to 'x', "in" to 'n',
        "en" to 'f', "an" to 'j', "ao" to 'k', "ai" to 'l',
        "ei" to 'z', "ou" to 'b',
    )

    fun encode(syllable: String, scheme: DoublePinyinScheme): String? {
        if (scheme == DoublePinyinScheme.OFF || syllable.isEmpty() ||
            syllable.any { it !in 'a'..'z' }) return null
        if (syllable == "eh") return syllable
        val initial = when {
            syllable.startsWith("zh") -> 'v' to 2
            syllable.startsWith("ch") -> 'i' to 2
            syllable.startsWith("sh") -> 'u' to 2
            syllable[0] in "aoe" -> (if (scheme == DoublePinyinScheme.MICROSOFT) 'o' else syllable[0]) to 0
            else -> syllable[0] to 1
        }
        val final = syllable.drop(initial.second)
        if (initial.second == 0 && final.length == 1) return "$final$final"
        if (final == "er") return if (scheme == DoublePinyinScheme.MICROSOFT) "or" else "er"
        if (scheme == DoublePinyinScheme.MICROSOFT && final == "ing") return "${initial.first};"
        if (scheme == DoublePinyinScheme.MICROSOFT && final == "v") return "${initial.first}y"
        val mapped = finals.firstOrNull { (ending, _) -> final == ending }?.second
        return when {
            mapped != null -> "${initial.first}$mapped"
            final.length == 1 -> "${initial.first}$final"
            else -> null
        }
    }

    fun rules(syllables: List<String>, scheme: DoublePinyinScheme): List<String> {
        require(scheme != DoublePinyinScheme.OFF && syllables.isNotEmpty())
        return syllables.distinct().mapNotNull { syllable ->
            encode(syllable, scheme)?.let { code -> "xform/^$syllable\u0024/${code.uppercase()}/" }
        } + "xlit/ABCDEFGHIJKLMNOPQRSTUVWXYZ/abcdefghijklmnopqrstuvwxyz/"
    }
}
