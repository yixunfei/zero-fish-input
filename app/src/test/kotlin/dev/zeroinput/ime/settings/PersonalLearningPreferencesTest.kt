package dev.zeroinput.ime.settings

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class PersonalLearningPreferencesTest {
    @Test fun `implicit learning defaults off and threshold defaults to three and persists independently`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("zeroinput-settings", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = SettingsRepository(context)
        assertFalse(settings.learnFromCommittedText)
        assertEquals(3, settings.personalPromotionThreshold)
        settings.learnFromCommittedText = true
        settings.personalPromotionThreshold = 5
        assertEquals(5, context.getSharedPreferences("zeroinput-settings", Context.MODE_PRIVATE)
            .getInt("learning.personal-promotion-threshold", 0))
        assertTrue(SettingsRepository(context).learnFromCommittedText)
        assertEquals(5, SettingsRepository(context).personalPromotionThreshold)
        settings.personalPromotionThreshold = -1
        assertEquals(1, settings.personalPromotionThreshold)
        settings.personalPromotionThreshold = Int.MAX_VALUE
        assertEquals(100, settings.personalPromotionThreshold)
    }
}
