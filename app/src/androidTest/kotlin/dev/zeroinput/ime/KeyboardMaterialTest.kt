package dev.zeroinput.ime

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.settings.SettingsRepository
import dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
import dev.zeroinput.ime.ui.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardMaterialTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun materialsAndBordersKeepKeyGeometryAndActionsAtAllWidthsAndThemes() {
        for (night in listOf(false, true)) {
            val activity = launch(night)
            try {
                for (width in listOf(320, 411, 800)) onMain {
                    val keyboard = activity.keyboard
                    val density = keyboard.resources.displayMetrics.density
                    var expected: List<android.graphics.Rect>? = null
                    val hashes = mutableSetOf<Int>()
                    for (material in KeyboardMaterial.entries) for (borders in listOf(false, true)) {
                        keyboard.applyAppearance(KeyboardAppearance(material = material, borders = borders,
                            background = KeyboardBackground.GRADIENT))
                        keyboard.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec((600 * density).toInt(), View.MeasureSpec.AT_MOST))
                        keyboard.layout(0, 0, keyboard.measuredWidth, keyboard.measuredHeight)
                        val panel = descendants(keyboard).filterIsInstance<KeyboardPanel>().single()
                        val keys = descendants(panel).filterIsInstance<TextView>()
                        val geometry = keys.map { android.graphics.Rect().also { rect ->
                            it.getDrawingRect(rect); keyboard.offsetDescendantRectToMyCoords(it, rect)
                            assertTrue(rect.bottom <= keyboard.height)
                            assertTrue(rect.right <= keyboard.width)
                        } }
                        if (expected == null) expected = geometry else assertEquals(expected, geometry)
                        var action: KeyboardAction? = null
                        keyboard.onKeyboardAction = { action = it }
                        keys.first { it.text.toString() == "q" }.performClick()
                        assertEquals(KeyboardAction.Text("q"), action)
                        val image = Bitmap.createBitmap(keyboard.width, keyboard.height, Bitmap.Config.ARGB_8888)
                        try {
                            keyboard.draw(Canvas(image))
                            val pixels = IntArray(image.width * image.height)
                            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                            hashes += pixels.contentHashCode()
                            if (width == 411 && !borders) save(image, "material-${material.name.lowercase()}-${if (night) "dark" else "light"}.png")
                        } finally { image.recycle() }
                    }
                    assertEquals("Every material/border pair should render distinctly", 12, hashes.size)
                }
            } finally { onMain { activity.finish() } }
        }
    }

    @Test fun opacityChangesOnlyTheBackgroundAndPreferencesRoundTrip() {
        val activity = launch(false)
        val settings = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph.settings
        val original = settings.keyboardAppearance
        try {
            onMain {
                val keyboard = activity.keyboard
                val image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
                val value = KeyboardAppearance(material = KeyboardMaterial.FROSTED, borders = true,
                    cornerRadius = 14, keySpacing = 4, background = KeyboardBackground.IMAGE,
                    backgroundColor = Color.BLUE, backgroundOpacity = 67, backgroundDim = 22, backgroundBlur = 12)
                settings.keyboardAppearance = value
                assertEquals(value, SettingsRepository(activity).keyboardAppearance)
                keyboard.applyAppearance(value.copy(backgroundOpacity = 0), image)
                val hidden = Bitmap.createBitmap(keyboard.width, keyboard.height, Bitmap.Config.ARGB_8888)
                val shown = Bitmap.createBitmap(keyboard.width, keyboard.height, Bitmap.Config.ARGB_8888)
                try {
                    keyboard.draw(Canvas(hidden))
                    keyboard.applyAppearance(value.copy(backgroundOpacity = 100), image)
                    keyboard.draw(Canvas(shown))
                    assertNotEquals(hidden.getPixel(1, 1), shown.getPixel(1, 1))
                    descendants(keyboard).filterIsInstance<TextView>().forEach { assertEquals(1f, it.alpha) }
                    save(shown, "custom-background-public-fixture.png")
                } finally {
                    keyboard.applyAppearance(KeyboardAppearance())
                    image.recycle(); hidden.recycle(); shown.recycle()
                }
            }
        } finally { onMain { settings.keyboardAppearance = original; activity.finish() } }
    }

    private fun launch(night: Boolean): KeyboardPreviewFixtureActivity {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, KeyboardPreviewFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("night", night)) as KeyboardPreviewFixtureActivity
        instrumentation.waitForIdleSync()
        return activity
    }

    private fun save(image: Bitmap, name: String) {
        val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "keyboard-fixtures").apply { mkdirs() }
        File(folder, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
