package dev.zeroinput.ime

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.settings.KeyboardAppearanceActivity
import dev.zeroinput.ime.ui.KeyboardAppearance
import dev.zeroinput.ime.ui.ZeroInputView
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppearanceSettingsLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun fixedPreviewAndScrollableControlsRemainReachableAfterRotation() {
        val settings = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph.settings
        val appearance = settings.keyboardAppearance
        val rotation = shell("settings get system user_rotation").trim()
        val auto = shell("settings get system accelerometer_rotation").trim()
        try {
            onMain { settings.keyboardAppearance = KeyboardAppearance() }
            shell("settings put system accelerometer_rotation 0")
            for (value in listOf(0, 1)) {
                shell("settings put system user_rotation $value")
                SystemClock.sleep(700)
                val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, KeyboardAppearanceActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as KeyboardAppearanceActivity
                try {
                    instrumentation.waitForIdleSync()
                    onMain {
                        val root = activity.window.decorView
                        assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
                        val keyboard = descendants(root).filterIsInstance<ZeroInputView>().single()
                        val scroll = descendants(root).filterIsInstance<ScrollView>().single { container ->
                            descendants(container).any { it is dev.zeroinput.ime.settings.AppearanceOptionsView }
                        }
                        assertTrue(keyboard.width >= 240 * activity.resources.displayMetrics.density)
                        assertTrue(keyboard.height > 0)
                        assertTrue(scroll.height >= 80 * activity.resources.displayMetrics.density)
                        val previewBounds = android.graphics.Rect()
                        assertTrue(keyboard.getGlobalVisibleRect(previewBounds))
                        assertEquals(keyboard.height, previewBounds.height())
                        val reset = descendants(scroll).filterIsInstance<TextView>().first {
                            it.text.toString() == activity.getString(dev.zeroinput.ime.ui.R.string.appearance_reset)
                        }
                        reset.requestRectangleOnScreen(android.graphics.Rect(0, 0, reset.width, reset.height), true)
                        assertTrue(reset.getGlobalVisibleRect(android.graphics.Rect()))
                        scroll.scrollTo(0, 0)
                        // Only default public decoration is rendered; no stored user image is loaded.
                        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                        try {
                            root.draw(Canvas(bitmap))
                            val folder = File(activity.getExternalFilesDir(null), "keyboard-fixtures").apply { mkdirs() }
                            File(folder, "appearance-layout-$value.png").outputStream().use {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                        } finally { bitmap.recycle() }
                    }
                } finally { onMain { activity.finish() } }
            }
        } finally {
            shell("settings put system user_rotation $rotation")
            shell("settings put system accelerometer_rotation $auto")
            onMain { settings.keyboardAppearance = appearance }
        }
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
