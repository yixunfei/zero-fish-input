package dev.zeroinput.ime.ui

import dev.zeroinput.engine.api.InputLanguage

/** Engine semantics stay independent of the localized language-key label. */
internal data class KeyboardLanguage(val language: InputLanguage, val languagePack: Boolean = false) {
    val usesChinesePunctuation: Boolean get() = language == InputLanguage.CHINESE
    val supportsCompositionDelimiter: Boolean get() = language == InputLanguage.CHINESE || languagePack
    val labelResource: Int get() = when {
        languagePack -> R.string.language_short_pack
        language == InputLanguage.CHINESE -> R.string.language_short_chinese
        else -> R.string.language_short_english
    }
}
