package dev.zeroinput.ime.settings

import android.app.Application
import android.content.Context
import dev.zeroinput.ime.settings.SettingsRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class AiThinkingVisibilityPreferencesTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before fun clearDisplayPreference() {
        context.getSharedPreferences("zeroinput-ai-display", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun reasoningVisibilityDefaultsOnAndPersistsIndependently() {
        val settings = SettingsRepository(context)
        assertTrue(settings.aiThinkingVisible)
        settings.aiThinkingVisible = false
        assertFalse(SettingsRepository(context).aiThinkingVisible)
        settings.aiThinkingVisible = true
        assertTrue(SettingsRepository(context).aiThinkingVisible)
    }
}
