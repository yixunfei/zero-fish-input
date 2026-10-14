package dev.zeroinput.ime.settings

import android.content.Context
import android.app.Application
import dev.zeroinput.ime.ui.ExpandedCandidateAppearance
import dev.zeroinput.ime.ui.ExpandedCandidateDensity
import dev.zeroinput.ime.ui.ExpandedCandidateLayout
import dev.zeroinput.ime.ui.KeyboardAppearance
import dev.zeroinput.ime.ui.KeyboardTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class CandidateAppearancePreferencesTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test fun existingAppearanceKeepsItsPaletteAndGetsReadableCandidateDefaults() {
        val prefs = context.getSharedPreferences("candidate-defaults", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("keyboard.theme", "MINT").commit()
        val value = AppearancePreferences(prefs).read()
        assertEquals(KeyboardTheme.MINT, value.theme)
        assertEquals(ExpandedCandidateAppearance(), value.expandedCandidates)
    }

    @Test fun independentChoicesSurviveReopeningAndUnknownValuesFailToDefaults() {
        val prefs = context.getSharedPreferences("candidate-roundtrip", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val appearance = KeyboardAppearance(expandedCandidates = ExpandedCandidateAppearance(
            ExpandedCandidateLayout.LIST, ExpandedCandidateDensity.STANDARD, 24, false))
        AppearancePreferences(prefs).write(appearance)
        assertEquals(appearance, AppearancePreferences(prefs).read())
        prefs.edit().putString("keyboard.candidates.layout", "unknown")
            .putString("keyboard.candidates.density", "unknown").putInt("keyboard.candidates.text-size", 999).commit()
        val recovered = AppearancePreferences(prefs).read().expandedCandidates
        assertEquals(ExpandedCandidateLayout.ADAPTIVE, recovered.layout)
        assertEquals(ExpandedCandidateDensity.SPACIOUS, recovered.density)
        assertEquals(24, recovered.textSize)
        AppearancePreferences(prefs).write(KeyboardAppearance())
        assertEquals(ExpandedCandidateAppearance(), AppearancePreferences(prefs).read().expandedCandidates)
    }
}
