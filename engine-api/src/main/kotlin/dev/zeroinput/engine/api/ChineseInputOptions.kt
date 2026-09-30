package dev.zeroinput.engine.api

enum class ChineseScript { SIMPLIFIED, TRADITIONAL }

enum class ChineseKeyboardLayout { FULL, NINE_KEY }

enum class DoublePinyinScheme { OFF, MICROSOFT, ZIRANMA }

enum class FuzzyPinyinPair {
    // Keep the original eight entries in this order: persisted bit masks use
    // the enum ordinal and existing settings must retain their meaning.
    Z_ZH, C_CH, S_SH, N_L, HU_FU, AN_ANG, EN_ENG, IN_ING,
    R_L, H_F, IAN_IANG, UAN_UANG, ON_ONG,
}

/** A value snapshot; masks keep the configuration immutable across worker boundaries. */
data class ChineseInputOptions(
    val script: ChineseScript = ChineseScript.SIMPLIFIED,
    val abbreviatedPinyin: Boolean = true,
    val fuzzyPinyinMask: Int = 0,
    val chinesePunctuation: Boolean = true,
    val candidatePageSize: Int = 8,
    val keyboardLayout: ChineseKeyboardLayout = ChineseKeyboardLayout.FULL,
    val experimentalTypoCorrection: Boolean = false,
    val fuzzyPinyinEnabled: Boolean = true,
    val doublePinyinScheme: DoublePinyinScheme = DoublePinyinScheme.OFF,
) {
    init {
        require(fuzzyPinyinMask in 0..MAX_FUZZY_PINYIN_MASK)
        require(candidatePageSize in PAGE_SIZES)
    }

    val effectiveFuzzyPinyinMask: Int get() = if (fuzzyPinyinEnabled) fuzzyPinyinMask else 0
    val effectiveDoublePinyinScheme: DoublePinyinScheme get() =
        if (keyboardLayout == ChineseKeyboardLayout.NINE_KEY) DoublePinyinScheme.OFF else doublePinyinScheme

    fun isFuzzySelected(pair: FuzzyPinyinPair): Boolean = fuzzyPinyinMask and (1 shl pair.ordinal) != 0

    fun isFuzzyEnabled(pair: FuzzyPinyinPair): Boolean = fuzzyPinyinEnabled && isFuzzySelected(pair)

    fun withFuzzy(pair: FuzzyPinyinPair, enabled: Boolean): ChineseInputOptions {
        val bit = 1 shl pair.ordinal
        return copy(fuzzyPinyinMask = if (enabled) fuzzyPinyinMask or bit else fuzzyPinyinMask and bit.inv())
    }

    companion object {
        val PAGE_SIZES: List<Int> = listOf(5, 8, 10)
        val MAX_FUZZY_PINYIN_MASK: Int = (1 shl FuzzyPinyinPair.entries.size) - 1
    }
}

enum class EngineCapability {
    CHINESE_SCRIPT, ABBREVIATED_PINYIN, FUZZY_PINYIN, PUNCTUATION_MODE, CANDIDATE_PAGE_SIZE, NINE_KEY_PINYIN,
    TYPO_CORRECTION, SEGMENT_SELECTION, DOUBLE_PINYIN,
}
