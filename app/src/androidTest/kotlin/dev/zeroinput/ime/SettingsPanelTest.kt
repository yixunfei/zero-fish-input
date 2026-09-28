package dev.zeroinput.ime

import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.ChineseScript
import dev.zeroinput.engine.api.ChineseKeyboardLayout
import dev.zeroinput.ime.settings.ChineseEngineChoice
import dev.zeroinput.ime.settings.SettingsRepository
import dev.zeroinput.ime.settings.SettingsScreenState
import dev.zeroinput.ime.settings.SettingsScreenView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class SettingsPanelTest {
    @Test
    fun chineseControlsFitSmallScreensAndExposeTheSelectedModes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync {
            result = runCatching {
                for (locale in listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE)) {
                    for (night in listOf(false, true)) verifyPanel(locale, night)
                }
            }
        }
        checkNotNull(result).getOrThrow()
    }

    @Test
    fun chineseOptionsPersistAsOneValueSnapshot() {
        val repository = SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = repository.chineseInputOptions
        val originalEngine = repository.chineseEngine
        val originalModel = repository.experimentalModelRanking
        try {
            val changed = ChineseInputOptions(
                ChineseScript.TRADITIONAL,
                false,
                ChineseInputOptions.MAX_FUZZY_PINYIN_MASK,
                false,
                10,
                ChineseKeyboardLayout.NINE_KEY,
            )
            repository.chineseInputOptions = changed
            repository.chineseEngine = ChineseEngineChoice.DICTIONARY_TEST
            repository.experimentalModelRanking = true
            assertEquals(changed, SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext).chineseInputOptions)
            assertEquals(ChineseEngineChoice.DICTIONARY_TEST, SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext).chineseEngine)
            assertTrue(SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext).experimentalModelRanking)
        } finally {
            repository.chineseInputOptions = original
            repository.chineseEngine = originalEngine
            repository.experimentalModelRanking = originalModel
        }
    }

    @Test fun fuzzyMasterSwitchPersistsWithoutLosingSelectedRules() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = SettingsRepository(context)
        val original = repository.chineseInputOptions
        try {
            val selected = ChineseInputOptions().withFuzzy(dev.zeroinput.engine.api.FuzzyPinyinPair.N_L, true)
            repository.chineseInputOptions = selected.copy(fuzzyPinyinEnabled = false)
            val restored = SettingsRepository(context).chineseInputOptions
            assertEquals(selected.fuzzyPinyinMask, restored.fuzzyPinyinMask)
            assertTrue(!restored.fuzzyPinyinEnabled)
            repository.chineseInputOptions = restored.copy(fuzzyPinyinEnabled = true)
            assertEquals(selected, SettingsRepository(context).chineseInputOptions)
        } finally { repository.chineseInputOptions = original }
    }

    private fun verifyPanel(locale: Locale, night: Boolean) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(target.resources.configuration).apply {
            setLocale(locale)
            uiMode = Configuration.UI_MODE_TYPE_NORMAL or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val context = ContextThemeWrapper(target.createConfigurationContext(configuration), R.style.Theme_ZeroInput)
        val panel = SettingsScreenView(context)
        panel.render(SettingsScreenState(false, false, true, false, false, true, context.getString(R.string.engine_status_ready)))
        val width = (320 * context.resources.displayMetrics.density).toInt()
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(width * 2, View.MeasureSpec.EXACTLY))
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        val all = descendants(panel)
        val association = all.filterIsInstance<com.google.android.material.materialswitch.MaterialSwitch>()
            .single { it.text == context.getString(R.string.setting_word_associations) }
        assertTrue(association.isChecked)
        var enabled = true
        panel.onWordAssociationsChanged = { enabled = it }
        association.performClick()
        assertTrue(!enabled)
        val groups = all.filterIsInstance<MaterialButtonToggleGroup>()
        assertEquals(3, groups.size)
        for (group in groups) {
            val buttons = descendants(group).filterIsInstance<MaterialButton>()
            assertEquals(1, buttons.count { it.isChecked })
            val checked = buttons.single { it.isChecked }
            val tint = checkNotNull(checked.backgroundTintList)
            assertTrue("Checked mode needs a distinct visual state", tint.getColorForState(
                intArrayOf(android.R.attr.state_checked), 0) != tint.getColorForState(intArrayOf(), 0))
        }
        all.filterIsInstance<TextView>().filter { it.isClickable && it.text.isNotEmpty() }.forEach {
            val available = it.width - it.compoundPaddingLeft - it.compoundPaddingRight
            val required = it.paint.measureText(it.text.toString())
            // This panel uses only built-in resource labels and synthetic state.
            val diagnostic = "Setting labels must fit a 320dp screen: locale=${locale.toLanguageTag()}, " +
                "night=$night, view=${it.javaClass.simpleName}, label=${it.text}, " +
                "width=${it.width}, available=$available, required=$required"
            assertTrue(diagnostic, available >= required)
        }
    }

    private fun descendants(view: View): List<View> = if (view is ViewGroup) {
        listOf(view) + (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    } else listOf(view)
}
