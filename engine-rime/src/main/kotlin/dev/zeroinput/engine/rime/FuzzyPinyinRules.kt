package dev.zeroinput.engine.rime

import dev.zeroinput.engine.api.FuzzyPinyinPair

/** Single source of truth for product fuzzy-pinyin pairs and their scopes. */
internal object FuzzyPinyinRules {
    enum class Scope { INITIAL, FINAL, EXACT }

    data class Rule(
        val pair: FuzzyPinyinPair,
        val left: String,
        val right: String,
        val scope: Scope,
        /**
         * When the right hand side shares the left prefix (for example z/zh),
         * only derive the longer spelling when its distinguishing suffix is
         * not already present. This preserves the canonical initial boundary.
         */
        val sharedInitialSuffix: String? = null,
    )

    val all: List<Rule> = listOf(
        Rule(FuzzyPinyinPair.Z_ZH, "z", "zh", Scope.INITIAL, sharedInitialSuffix = "h"),
        Rule(FuzzyPinyinPair.C_CH, "c", "ch", Scope.INITIAL, sharedInitialSuffix = "h"),
        Rule(FuzzyPinyinPair.S_SH, "s", "sh", Scope.INITIAL, sharedInitialSuffix = "h"),
        Rule(FuzzyPinyinPair.N_L, "n", "l", Scope.INITIAL),
        Rule(FuzzyPinyinPair.HU_FU, "hu", "fu", Scope.EXACT),
        Rule(FuzzyPinyinPair.AN_ANG, "an", "ang", Scope.FINAL),
        Rule(FuzzyPinyinPair.EN_ENG, "en", "eng", Scope.FINAL),
        Rule(FuzzyPinyinPair.IN_ING, "in", "ing", Scope.FINAL),
        Rule(FuzzyPinyinPair.R_L, "r", "l", Scope.INITIAL),
        Rule(FuzzyPinyinPair.H_F, "h", "f", Scope.INITIAL),
        Rule(FuzzyPinyinPair.IAN_IANG, "ian", "iang", Scope.FINAL),
        Rule(FuzzyPinyinPair.UAN_UANG, "uan", "uang", Scope.FINAL),
        Rule(FuzzyPinyinPair.ON_ONG, "on", "ong", Scope.FINAL),
    )

    fun rimeRules(rule: Rule): List<String> = when (rule.scope) {
        Scope.INITIAL -> if (rule.sharedInitialSuffix == null) {
            listOf(
                "derive/^${rule.left}/${rule.right}/",
                "derive/^${rule.right}/${rule.left}/",
            )
        } else {
            listOf(
                "derive/^${rule.right}/${rule.left}/",
                "derive/^${rule.left}([^${rule.sharedInitialSuffix}])/${rule.right}\u00241/",
            )
        }
        Scope.FINAL -> listOf(
            "derive/${rule.left}\u0024/${rule.right}/",
            "derive/${rule.right}\u0024/${rule.left}/",
        )
        Scope.EXACT -> listOf(
            "derive/^${rule.left}\u0024/${rule.right}/",
            "derive/^${rule.right}\u0024/${rule.left}/",
        )
    }
}
