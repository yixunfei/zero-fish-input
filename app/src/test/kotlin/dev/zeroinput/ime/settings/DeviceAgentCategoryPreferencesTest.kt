package dev.zeroinput.ime.settings

import android.app.Application
import android.content.Context
import dev.zeroinput.ai.api.DeviceActionCategory
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class DeviceAgentCategoryPreferencesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences("zeroinput-settings", Context.MODE_PRIVATE)

    @Before fun clearPreferences() { preferences.edit().clear().commit() }

    @Test fun missingCategoriesNeverGrantActionsEvenAfterEnablingTheAgent() {
        val settings = SettingsRepository(context)
        assertTrue(settings.deviceAgentAllowedCategories.isEmpty())
        settings.deviceAgentEnabled = true
        settings.deviceAgentDisclosureAccepted = true
        assertTrue(SettingsRepository(context).deviceAgentAllowedCategories.isEmpty())
        assertFalse(preferences.contains("ai.device-agent.categories"))
    }

    @Test fun explicitEmptySubsetAndFullGrantsSurviveReload() {
        for (categories in listOf(emptySet(), setOf(DeviceActionCategory.entries.first()), DeviceActionCategory.entries.toSet())) {
            SettingsRepository(context).deviceAgentAllowedCategories = categories
            assertEquals(categories, SettingsRepository(context).deviceAgentAllowedCategories)
        }
    }

    @Test fun unknownStoredCategoriesGrantNothingAndAreNotRewritten() {
        preferences.edit().putStringSet("ai.device-agent.categories", setOf("UNKNOWN")).commit()
        assertTrue(SettingsRepository(context).deviceAgentAllowedCategories.isEmpty())
        assertEquals(setOf("UNKNOWN"), preferences.getStringSet("ai.device-agent.categories", null))
    }
}
