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
import com.google.android.material.materialswitch.MaterialSwitch
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.ChineseScript
import dev.zeroinput.engine.api.ChineseKeyboardLayout
import dev.zeroinput.engine.api.DoublePinyinScheme
import dev.zeroinput.ime.settings.SettingsRepository
import dev.zeroinput.ime.settings.SettingsScreenState
import dev.zeroinput.ime.settings.SettingsCategory
import dev.zeroinput.ime.settings.SettingsScreenView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class SettingsPanelTest {
    @Test fun wholeVaultClearIsSeparateFromDisableAndPersonalizationAndRequiresConfirmation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph
        val original = graph.settings.secureClipboardEnabled
        val generation = graph.secureClipboard.captureGeneration()
        var activity: dev.zeroinput.ime.settings.MainActivity? = null
        try {
            onMain { graph.settings.secureClipboardEnabled = false }
            activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext,
                dev.zeroinput.ime.settings.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                as dev.zeroinput.ime.settings.MainActivity
            instrumentation.waitForIdleSync()
            val owner = activity
            onMain {
                descendants(owner.window.decorView).filterIsInstance<SettingsScreenView>().single()
                    .navigateTo(SettingsCategory.PRIVACY)
                descendants(owner.window.decorView).filterIsInstance<TextView>().single {
                    it.text == owner.getString(R.string.clear_secure_clipboard)
                }.performClick()
                val dialog = android.view.inspector.WindowInspector.getGlobalWindowViews().single {
                    it.findViewById<View>(android.R.id.button1) != null
                }
                assertTrue(descendants(dialog).filterIsInstance<TextView>().any {
                    it.text == owner.getString(R.string.clear_secure_clipboard_message)
                })
                dialog.findViewById<View>(android.R.id.button2).performClick()
            }
            instrumentation.waitForIdleSync()
            assertEquals(generation, graph.secureClipboard.captureGeneration())
            assertTrue(!graph.settings.secureClipboardEnabled)
        } finally {
            onMain { activity?.finish(); graph.settings.secureClipboardEnabled = original }
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun fuzzyMasterUpdatesEveryVisibleRuleAndIndividualRulesRemainUsable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync {
            result = runCatching {
                val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_ZeroInput)
                val panel = dev.zeroinput.ime.settings.ChineseSettingsView(context)
                var options = ChineseInputOptions()
                panel.onOptionsChanged = { options = it }
                panel.render(options, setOf(dev.zeroinput.engine.api.EngineCapability.FUZZY_PINYIN))
                val switches = (0 until panel.childCount).map(panel::getChildAt).filterIsInstance<MaterialSwitch>()
                val master = switches.single { it.text == context.getString(R.string.fuzzy_pinyin_enabled) }
                val pairs = switches.takeLast(dev.zeroinput.engine.api.FuzzyPinyinPair.entries.size)
                assertTrue(!master.isChecked)
                master.isChecked = true
                assertTrue(pairs.all { it.isChecked })
                assertEquals(ChineseInputOptions.MAX_FUZZY_PINYIN_MASK, options.effectiveFuzzyPinyinMask)
                master.isChecked = false
                assertTrue(pairs.none { it.isChecked })
                assertEquals(0, options.fuzzyPinyinMask)
                pairs.first().isChecked = true
                assertTrue(master.isChecked)
                assertEquals(1, pairs.count { it.isChecked })
            }
        }
        checkNotNull(result).getOrThrow()
    }

    @Test
    fun chineseControlsFitSmallScreensAndExposeTheSelectedModes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync {
            result = runCatching {
                for (locale in listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE)) {
                    for (night in listOf(false, true)) for (scale in listOf(1f, 1.5f)) verifyPanel(locale, night, scale)
                }
            }
        }
        checkNotNull(result).getOrThrow()
    }

    @Test
    fun chineseOptionsPersistAsOneValueSnapshot() {
        val repository = SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = repository.chineseInputOptions
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
            repository.experimentalModelRanking = true
            assertEquals(changed, SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext).chineseInputOptions)
            assertTrue(SettingsRepository(InstrumentationRegistry.getInstrumentation().targetContext).experimentalModelRanking)
        } finally {
            repository.chineseInputOptions = original
            repository.experimentalModelRanking = originalModel
        }
    }

    @Test fun fuzzyMasterSwitchPersistsAllSelectionsOrNone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = SettingsRepository(context)
        val original = repository.chineseInputOptions
        try {
            val selected = ChineseInputOptions().withFuzzy(dev.zeroinput.engine.api.FuzzyPinyinPair.N_L, true)
            repository.chineseInputOptions = selected.withAllFuzzy(false)
            val restored = SettingsRepository(context).chineseInputOptions
            assertEquals(0, restored.fuzzyPinyinMask)
            assertTrue(!restored.fuzzyPinyinEnabled)
            repository.chineseInputOptions = restored.withAllFuzzy(true)
            assertEquals(selected.withAllFuzzy(true), SettingsRepository(context).chineseInputOptions)
        } finally { repository.chineseInputOptions = original }
    }

    @Test fun doublePinyinSchemePersistsWithTheChineseOptions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = SettingsRepository(context)
        val original = repository.chineseInputOptions
        try {
            repository.chineseInputOptions = original.copy(doublePinyinScheme = DoublePinyinScheme.MICROSOFT)
            assertEquals(DoublePinyinScheme.MICROSOFT, SettingsRepository(context).chineseInputOptions.doublePinyinScheme)
            repository.chineseInputOptions = original.copy(doublePinyinScheme = DoublePinyinScheme.ZIRANMA)
            assertEquals(DoublePinyinScheme.ZIRANMA, SettingsRepository(context).chineseInputOptions.doublePinyinScheme)
        } finally { repository.chineseInputOptions = original }
    }

    @Test fun aiSwitchesExposeStateAndCanBeLockedDuringPersistence() = onMain {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val panel = SettingsScreenView(ContextThemeWrapper(target, R.style.Theme_ZeroInput))
        panel.render(SettingsScreenState(
            isEnabled = false,
            isCurrent = false,
            learningEnabled = true,
            incognitoMode = false,
            secureClipboardEnabled = false,
            hapticsEnabled = true,
            engineStatus = target.getString(R.string.engine_status_ready),
            aiEnabled = true,
            aiNetworkAllowed = true,
        ))
        panel.navigateTo(SettingsCategory.AI)
        val switches = descendants(panel).filterIsInstance<MaterialSwitch>()
        val enabled = switches.single { it.text == target.getString(R.string.ai_enabled) }
        val network = switches.single { it.text == target.getString(R.string.ai_network) }
        assertTrue(enabled.isChecked)
        assertTrue(network.isChecked)

        panel.setAiControlsEnabled(false)
        assertTrue(!enabled.isEnabled && !network.isEnabled)
        panel.setAiControlsEnabled(true)
        assertTrue(enabled.isEnabled && network.isEnabled)

        panel.render(SettingsScreenState(
            isEnabled = false,
            isCurrent = false,
            learningEnabled = true,
            incognitoMode = false,
            secureClipboardEnabled = false,
            hapticsEnabled = true,
            engineStatus = target.getString(R.string.engine_status_ready),
            aiEnabled = false,
            aiNetworkAllowed = true,
        ))
        assertTrue(!network.isEnabled && !network.isChecked)
    }

    private fun verifyPanel(locale: Locale, night: Boolean, scale: Float) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val configuration = Configuration(target.resources.configuration).apply {
            setLocale(locale)
            fontScale = scale
            uiMode = Configuration.UI_MODE_TYPE_NORMAL or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val context = ContextThemeWrapper(target.createConfigurationContext(configuration), R.style.Theme_ZeroInput)
        val panel = SettingsScreenView(context)
        panel.render(SettingsScreenState(false, false, true, false, false, true,
            engineStatus = context.getString(R.string.engine_status_ready),
            languagePacks = listOf(dev.zeroinput.ime.settings.LanguagePackScreenState(
                "public-fixture", "Public language pack with a long display name", "en", "1.0", true, false, true))))
        panel.navigateTo(SettingsCategory.INPUT)
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
        assertEquals(4, groups.size)
        for (group in groups) {
            val buttons = descendants(group).filterIsInstance<MaterialButton>()
            assertEquals(1, buttons.count { it.isChecked })
            val checked = buttons.single { it.isChecked }
            val tint = checkNotNull(checked.backgroundTintList)
            assertTrue("Checked mode needs a distinct visual state", tint.getColorForState(
                intArrayOf(android.R.attr.state_checked), 0) != tint.getColorForState(intArrayOf(), 0))
        }
        all.filterIsInstance<MaterialButtonToggleGroup>().forEach { group ->
            val heights = (0 until group.childCount).map { group.getChildAt(it).height }
            assertTrue("Mode buttons must share a row height", heights.distinct().size <= 1)
        }
        for (category in SettingsCategory.entries) {
            panel.navigateTo(category)
            panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(width * 2, View.MeasureSpec.EXACTLY))
            panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
            val visible = descendants(panel)
            if (category == SettingsCategory.GENERAL) {
                val languageGroup = visible.filterIsInstance<MaterialButtonToggleGroup>().single()
                assertEquals(1, descendants(languageGroup).filterIsInstance<MaterialButton>().count { it.isChecked })
            }
            visible.filterIsInstance<TextView>().filter { it.visibility == View.VISIBLE && it.text.isNotEmpty() }.forEach {
                assertLabelFits(it, "Setting label: ${locale.language}, $night, $scale, $category, ${it.text}")
            }
            savePublicFixtures(panel, locale, night, scale)
        }
    }

    private fun assertLabelFits(view: TextView, diagnostic: String) {
        val available = view.width - view.compoundPaddingLeft - view.compoundPaddingRight
        val layout = checkNotNull(view.layout)
        assertTrue(diagnostic, layout.lineCount > 0)
        for (line in 0 until layout.lineCount) {
            assertEquals(diagnostic, 0, layout.getEllipsisCount(line))
            // getLineWidth includes the invisible trailing space at a soft wrap.
            // getLineMax measures visible extent while retaining leading margins.
            assertTrue(diagnostic, layout.getLineMax(line) <= available + 1)
        }
        assertEquals(diagnostic, view.text.length, layout.getLineEnd(layout.lineCount - 1))
        assertTrue(diagnostic, layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
    }

    @Test fun labelMeasurementStillRejectsVisibleOverflowEllipsisAndClippedHeight() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync {
            result = runCatching {
                val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_ZeroInput)
                fun label(width: Int, height: Int, ellipsis: Boolean = false): TextView = TextView(context).apply {
                    text = "Public fixture label with deliberately overflowing text"
                    textSize = 24f
                    setSingleLine(true)
                    if (ellipsis) ellipsize = android.text.TextUtils.TruncateAt.END
                    measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    layout(0, 0, measuredWidth, measuredHeight)
                }
                for (view in listOf(label(50, 100), label(50, 100, true), label(2000, 1))) {
                    org.junit.Assert.assertThrows(AssertionError::class.java) { assertLabelFits(view, "Public negative fixture") }
                }
            }
        }
        checkNotNull(result).getOrThrow()
    }

    private fun savePublicFixtures(panel: SettingsScreenView, locale: Locale, night: Boolean, scale: Float) {
        val bitmap = android.graphics.Bitmap.createBitmap(panel.width, panel.height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            panel.draw(android.graphics.Canvas(bitmap))
            java.io.File(panel.context.cacheDir, "settings-${locale.language}-$night-$scale-${panel.category.name}.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { bitmap.recycle() }
    }

    @Test fun categoryNavigationPreservesPendingLocksAndRestoresSelectionWithoutMutations() = onMain {
        val context = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_ZeroInput)
        val panel = SettingsScreenView(context)
        var mutations = 0
        panel.onAiEnabledChanged = { mutations++ }
        panel.onLearningChanged = { mutations++ }
        panel.render(SettingsScreenState(false, false, true, false, false, true,
            engineStatus = context.getString(R.string.engine_status_ready), aiEnabled = true, aiNetworkAllowed = true))
        panel.setAiControlsEnabled(false)
        assertTrue(descendants(panel).none { it is MaterialSwitch })
        descendants(panel).single { it.tag == R.string.ai_title }.performClick()
        assertEquals(SettingsCategory.AI, panel.category)
        val aiSwitch = descendants(panel).filterIsInstance<MaterialSwitch>().single {
            it.text == context.getString(R.string.ai_enabled)
        }
        assertTrue(aiSwitch.isChecked && !aiSwitch.isEnabled)
        panel.navigateTo(SettingsCategory.HOME)
        panel.navigateTo(SettingsCategory.AI)
        assertTrue(!aiSwitch.isEnabled)
        val saved = android.os.Bundle()
        panel.saveNavigation(saved)
        val restored = SettingsScreenView(context)
        restored.restoreNavigation(saved)
        assertEquals(SettingsCategory.AI, restored.category)
        assertEquals(0, mutations)
    }

    private fun descendants(view: View): List<View> = if (view is ViewGroup) {
        listOf(view) + (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    } else listOf(view)

    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
