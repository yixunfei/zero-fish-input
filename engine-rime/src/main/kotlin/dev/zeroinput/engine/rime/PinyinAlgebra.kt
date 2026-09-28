package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.FuzzyPinyinPair
import dev.zeroinput.engine.api.ChineseKeyboardLayout

/** Rime compiles these rules into its syllable index, outside the input thread. */
internal object PinyinAlgebra {
    fun rules(options: ChineseInputOptions): List<String> = buildList {
        if (options.experimentalTypoCorrection && options.keyboardLayout == ChineseKeyboardLayout.FULL) {
            addAll(TypoPinyinAlgebra.rules())
        }
        for (pair in FuzzyPinyinPair.entries) {
            if (options.isFuzzyEnabled(pair)) {
                addAll(FuzzyPinyinRules.rimeRules(FuzzyPinyinRules.all.first { it.pair == pair }))
            }
        }
        if (options.abbreviatedPinyin) {
            add("abbrev/^([a-z]).+\u0024/\u00241/")
            add("abbrev/^([zcs]h).+\u0024/\u00241/")
        }
        if (options.keyboardLayout == ChineseKeyboardLayout.NINE_KEY) {
            for ((index, group) in listOf("abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz").withIndex()) {
                add("derive/[$group]/${index + 2}/")
            }
            // Retain both exact Latin syllables and numeric codes, discarding intermediate mixtures.
            add("erase/^(?=.*[a-z])(?=.*[2-9]).+\u0024/")
        }
    }

    fun schemaId(options: ChineseInputOptions): String =
        if (options.effectiveFuzzyPinyinMask == 0 && options.abbreviatedPinyin && options.candidatePageSize == 8 &&
            options.keyboardLayout == ChineseKeyboardLayout.FULL && !options.experimentalTypoCorrection) {
            "zeroinput_pinyin"
        } else {
            "zeroinput_pinyin_${options.effectiveFuzzyPinyinMask}_${if (options.abbreviatedPinyin) 1 else 0}_${options.candidatePageSize}" +
                (if (options.keyboardLayout == ChineseKeyboardLayout.NINE_KEY) "_9" else "") +
                (if (options.experimentalTypoCorrection && options.keyboardLayout == ChineseKeyboardLayout.FULL) "_2" else "")
        }

}
