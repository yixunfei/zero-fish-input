package dev.zeroinput.ime.ui

import dev.zeroinput.engine.api.InputLanguage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLanguageTest {
    @Test fun languagePacksKeepDelimiterSupportWithoutChangingLanguagePunctuation() {
        val chinese = KeyboardLanguage(InputLanguage.CHINESE, languagePack = true)
        val english = KeyboardLanguage(InputLanguage.ENGLISH, languagePack = true)
        assertTrue(chinese.supportsCompositionDelimiter)
        assertTrue(english.supportsCompositionDelimiter)
        assertTrue(chinese.usesChinesePunctuation)
        assertFalse(english.usesChinesePunctuation)
        assertFalse(KeyboardLanguage(InputLanguage.ENGLISH).supportsCompositionDelimiter)
    }
}
