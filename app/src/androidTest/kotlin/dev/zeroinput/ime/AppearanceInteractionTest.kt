package dev.zeroinput.ime

import android.view.View
import android.view.ViewGroup
import android.view.ContextThemeWrapper
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import dev.zeroinput.ime.settings.AppearanceOptionsView
import dev.zeroinput.ime.ui.KeyboardAppearance
import dev.zeroinput.ime.ui.KeyboardBackground
import dev.zeroinput.ime.ui.KeyboardMaterial
import dev.zeroinput.ime.ui.KeyboardTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

@RunWith(AndroidJUnit4::class)
class AppearanceInteractionTest {
    @Test fun everyAppearanceSectionFitsSmallScreensAndLargeFontsInBothThemes() {
        var result: Result<Unit>? = null
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            result = runCatching {
                for (locale in listOf(java.util.Locale.ENGLISH, java.util.Locale.SIMPLIFIED_CHINESE)) {
                    for (night in listOf(false, true)) for (scale in listOf(1f, 1.5f)) {
                        val config = android.content.res.Configuration(instrumentation.targetContext.resources.configuration).apply {
                            setLocale(locale)
                            fontScale = scale
                            uiMode = android.content.res.Configuration.UI_MODE_TYPE_NORMAL or if (night)
                                android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
                        }
                        val context = ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(config), R.style.Theme_ZeroInput)
                        for (section in dev.zeroinput.ime.settings.AppearanceSection.entries) {
                            val panel = AppearanceOptionsView(context, KeyboardAppearance(), { _, _ -> }, {}, {}, {}, section)
                            val width = (320 * context.resources.displayMetrics.density).toInt()
                            panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                            panel.layout(0, 0, width, panel.measuredHeight)
                            descendants(panel).filterIsInstance<TextView>().forEach { label ->
                                val diagnostic = "${locale.language}/$night/$scale/$section: ${label.text}"
                                val layout = checkNotNull(label.layout)
                                val available = label.width - label.compoundPaddingLeft - label.compoundPaddingRight
                                for (line in 0 until layout.lineCount) {
                                    assertEquals(diagnostic, 0, layout.getEllipsisCount(line))
                                    assertTrue(diagnostic, layout.getLineMax(line) <= available + 1)
                                }
                                assertTrue(diagnostic, layout.height <= label.height - label.compoundPaddingTop - label.compoundPaddingBottom)
                                if (label.isClickable) assertTrue(diagnostic, label.height >= 48 * context.resources.displayMetrics.density)
                            }
                            if (section == dev.zeroinput.ime.settings.AppearanceSection.STYLE && scale == 1f) {
                                val image = android.graphics.Bitmap.createBitmap(width, panel.height, android.graphics.Bitmap.Config.ARGB_8888)
                                try {
                                    panel.draw(android.graphics.Canvas(image))
                                    java.io.File(context.cacheDir, "appearance-style-${locale.language}-$night.png").outputStream().use {
                                        image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                                    }
                                } finally { image.recycle() }
                            }
                        }
                    }
                }
            }
        }
        checkNotNull(result).getOrThrow()
    }

    @Test fun presetAndCustomControlsStayInSyncAcrossSectionsWithoutChangingBackground() {
        var result: Result<Unit>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result = runCatching {
                val context = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_ZeroInput)
                val initial = KeyboardAppearance(background = KeyboardBackground.GRADIENT, backgroundBlur = 7)
                var current = initial
                var writes = 0
                val panel = AppearanceOptionsView(context, initial, { value, commit ->
                    current = value
                    if (commit) writes++
                }, {}, {}, {})
                fun click(label: Int) = descendants(panel).filterIsInstance<MaterialButton>()
                    .first { it.text == context.getString(label) }.performClick()
                click(R.string.appearance_section_keys)
                assertEquals(0, writes)
                click(R.string.appearance_section_style)
                click(UiR.string.keyboard_theme_mint)
                assertEquals(KeyboardTheme.MINT, current.theme)
                assertEquals(KeyboardMaterial.SOFT, current.material)
                assertEquals(initial.background, current.background)
                assertEquals(initial.backgroundBlur, current.backgroundBlur)
                assertEquals(1, writes)
                click(R.string.appearance_section_keys)
                assertTrue(descendants(panel).filterIsInstance<TextView>().any {
                    it.text == context.getString(UiR.string.appearance_radius, current.cornerRadius)
                })
                click(R.string.appearance_section_style)
                click(UiR.string.material_flat)
                assertEquals(KeyboardMaterial.FLAT, current.material)
                assertEquals(2, writes)
                click(UiR.string.appearance_background)
                assertTrue(descendants(panel).filterIsInstance<MaterialButton>().single {
                    it.text == context.getString(UiR.string.background_gradient)
                }.isChecked)
                assertEquals(2, writes)
            }
        }
        checkNotNull(result).getOrThrow()
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
