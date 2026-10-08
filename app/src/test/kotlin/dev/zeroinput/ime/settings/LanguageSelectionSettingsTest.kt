package dev.zeroinput.ime.settings

import android.app.Application
import dev.zeroinput.engine.api.InputLanguage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class LanguageSelectionSettingsTest {
    @Test fun everyNotificationSeesTheNewLanguageAndClearedPackageTogether() {
        val settings = SettingsRepository(RuntimeEnvironment.getApplication())
        settings.lastLanguage = InputLanguage.CHINESE
        settings.lastLanguagePackKey = "public-fixture@1"
        val observed = mutableListOf<Pair<InputLanguage, String?>>()
        settings.addChangeListener { observed += settings.lastLanguage to settings.lastLanguagePackKey }.use {
            settings.selectBuiltInLanguage(InputLanguage.ENGLISH)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        assertTrue(observed.isNotEmpty())
        assertTrue(observed.all { it.first == InputLanguage.ENGLISH && it.second == null })
    }
}
