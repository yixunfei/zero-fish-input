package dev.zeroinput.ime

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.CompositionSelection
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.engine.api.PinyinCandidateMatch
import dev.zeroinput.ime.core.InputSessionState
import dev.zeroinput.ime.settings.KeyboardAppearanceActivity
import dev.zeroinput.ime.settings.SettingsRepository
import dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
import dev.zeroinput.ime.ui.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardAppearanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun attachedPublicAssociationsRenderInBothModes() {
        for (night in listOf(false, true)) {
            val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, KeyboardPreviewFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("night", night)) as KeyboardPreviewFixtureActivity
            try {
                instrumentation.waitForIdleSync()
                var height = 0
                onMain {
                    height = activity.keyboard.height
                    activity.keyboard.renderSession(InputSessionState(snapshot = EngineSnapshot(candidates =
                        listOf("世界", "朋友", "呀").mapIndexed { index, word -> Candidate("association:$index", word,
                            kind = dev.zeroinput.engine.api.CandidateKind.NEXT_WORD) })))
                }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(150)
                onMain { assertEquals(height, activity.keyboard.height) }
                capture("associations-${if (night) "dark" else "light"}.png", activity.keyboard)
            } finally { onMain { activity.finish() } }
        }
    }

    @Test fun appearanceSelectionPersistsAndUpdatesTheActualPreviewGeometry() {
        val settings = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph.settings
        val original = settings.keyboardAppearance
        var activity: KeyboardAppearanceActivity? = null
        try {
            onMain { settings.keyboardAppearance = KeyboardAppearance() }
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, KeyboardAppearanceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as KeyboardAppearanceActivity
            val layoutReady = java.util.concurrent.CountDownLatch(1)
            onMain {
                val root = activity.window.decorView
                button(root, dev.zeroinput.ime.ui.R.string.keyboard_theme_rose).performClick()
                button(root, R.string.appearance_section_keys).performClick()
                button(root, dev.zeroinput.ime.ui.R.string.keyboard_height_comfortable).performClick()
                assertEquals(KeyboardAppearance(KeyboardTheme.ROSE, KeyboardHeight.COMFORTABLE,
                    material = KeyboardMaterial.RAISED), SettingsRepository(activity).keyboardAppearance)
                val options = views(root).filterIsInstance<dev.zeroinput.ime.settings.AppearanceOptionsView>().single()
                val checked = views(options).filterIsInstance<com.google.android.material.button.MaterialButton>()
                    .filter { it.isChecked }.map { it.text.toString() }
                assertEquals(setOf(activity.getString(R.string.appearance_section_keys),
                    activity.getString(dev.zeroinput.ime.ui.R.string.keyboard_height_comfortable)), checked.toSet())
                assertEquals(2, checked.size)
                root.doOnPreDraw { layoutReady.countDown() }

            }
            assertTrue("Updated preview must finish layout", layoutReady.await(5, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            onMain {
                val preview = views(activity.window.decorView).filterIsInstance<ZeroInputView>().single()
                val keyboard = views(preview).filterIsInstance<KeyboardPanel>().single()
                val landscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                val rowHeight = (KeyboardHeight.COMFORTABLE.rowHeight(landscape) * keyboard.resources.displayMetrics.density).toInt()
                assertEquals(4, keyboard.childCount)
                for (index in 0 until keyboard.childCount) {
                    val row = keyboard.getChildAt(index)
                    assertEquals("The selected height must reach every key row", rowHeight, row.layoutParams.height)
                    assertTrue("Constrained rows must remain usable", row.height > 0)
                }
                val bounds = android.graphics.Rect()
                assertTrue(keyboard.getGlobalVisibleRect(bounds))
                assertEquals("Even short landscape windows must show all rows", keyboard.height, bounds.height())
                if (!landscape) assertTrue(keyboard.height >= rowHeight * keyboard.childCount)
            }
            SystemClock.sleep(250)
            capture("appearance-settings.png")
        } finally {
            activity?.let { onMain { it.finish() } }
            onMain { settings.keyboardAppearance = original }
        }
    }

    @Test fun attachedPublicPreviewsRenderEveryPaletteAndCandidateStateInBothModes() {
        for (night in listOf(false, true)) for (preset in KeyboardTheme.entries) {
            val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, KeyboardPreviewFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("night", night).putExtra("theme", preset.name)) as KeyboardPreviewFixtureActivity
            try {
                instrumentation.waitForIdleSync()
                SystemClock.sleep(100)
                capture("${preset.name.lowercase()}-${if (night) "dark" else "light"}-idle.png", activity.keyboard)
                onMain {
                    activity.keyboard.renderSession(InputSessionState(snapshot = EngineSnapshot("kanga", "kan ga",
                        listOf(Candidate("1", "看嘎", "kan ga", pinyinMatch = PinyinCandidateMatch("kan ga", 0, 5, true)),
                            Candidate("2", "抗啊"), Candidate("3", "看"), Candidate("4", "砍")),
                        selection = CompositionSelection(5))))
                    val preedit = views(activity.keyboard).filterIsInstance<TextView>()
                        .single { it.javaClass.simpleName == "CompositionEditorView" }
                    assertEquals("kan ga", preedit.text.toString())
                }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(100)
                capture("${preset.name.lowercase()}-${if (night) "dark" else "light"}-candidates.png", activity.keyboard)
            } finally { onMain { activity.finish() } }
        }
    }

    private fun capture(name: String, keyboard: View? = null) {
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "keyboard-fixtures").apply { mkdirs() }
            File(folder, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (keyboard != null) {
                val point = IntArray(2)
                var surface = 0
                onMain {
                    keyboard.getLocationOnScreen(point)
                    surface = com.google.android.material.color.MaterialColors.getColor(keyboard.context,
                        com.google.android.material.R.attr.colorSurface, 0)
                }
                assertEquals("The attached keyboard must render its selected surface", surface, image.getPixel(point[0] + 2, point[1] + 2))
            }
        } finally { image.recycle() }
    }

    private fun button(root: View, label: Int) = views(root).filterIsInstance<TextView>().first { it.text.toString() == root.context.getString(label) }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
