package dev.zeroinput.ime

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.testing.InputFixtureActivity
import dev.zeroinput.ime.ui.ZeroInputView
import dev.zeroinput.ime.ui.EmojiPanelView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class FullscreenInputTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun sameFullscreenEditorAcceptsInputAfterRepeatedLiveRotation() {
        val original = shell("settings get secure default_input_method").trim()
        val method = "dev.zeroinput.ime.debug/dev.zeroinput.ime.ZeroInputService"
        var activity: InputFixtureActivity? = null
        try {
            shell("ime enable $method")
            shell("ime set $method")
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, InputFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("fullscreen_fixture", true)) as InputFixtureActivity
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                onMain { activity.requestedOrientation = orientation }
                val panel = awaitPanel(orientation)
                var previousLength = 0
                onMain {
                    val point = IntArray(2)
                    panel.getLocationOnScreen(point)
                    assertTrue(point[1] >= 48 * panel.resources.displayMetrics.density)
                    previousLength = activity.editor.length()
                    panel.onKeyboardAction(dev.zeroinput.ime.ui.KeyboardAction.LiteralText("4"))
                }
                instrumentation.waitForIdleSync()
                onMain {
                    assertEquals(previousLength + 1, activity.editor.length())
                    panel.onKeyboardAction(dev.zeroinput.ime.ui.KeyboardAction.Backspace)
                }
                instrumentation.waitForIdleSync()
                onMain { assertEquals(previousLength, activity.editor.length()) }
            }
            savePublicFixture("repeated-rotation-landscape.png")
        } finally {
            activity?.let { onMain { it.finish() } }
            if (original.isNotBlank() && original != "null") shell("ime set $original")
        }
    }

    @Test fun attachedKaomojiSearchLeavesTheEditorUntouchedUntilSelection() {
        val original = shell("settings get secure default_input_method").trim()
        val method = "dev.zeroinput.ime.debug/dev.zeroinput.ime.ZeroInputService"
        var activity: InputFixtureActivity? = null
        try {
            shell("ime enable $method")
            shell("ime set $method")
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, InputFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("fullscreen_fixture", true)) as InputFixtureActivity
            onMain { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            val panel = awaitPanel(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            onMain {
                // Debug diagnostics occupy the candidate strip; open its tools first.
                click(panel, UiR.string.keyboard_tools)
                click(panel, UiR.string.expression_smileys)
            }
            instrumentation.waitForIdleSync()
            onMain { click(panel, UiR.string.expression_kaomoji) }
            instrumentation.waitForIdleSync()
            onMain {
                click(panel, UiR.string.expression_search)
                descendants(panel).filterIsInstance<EmojiPanelView>().single().appendQuery("kaixin")
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(500)
            savePublicFixture("attached-kaomoji-search.png")
            var selected = ""
            onMain {
                assertEquals("", activity.editor.text.toString())
                val expressions = descendants(panel).filterIsInstance<EmojiPanelView>().single()
                val label = descendants(expressions).filterIsInstance<TextView>()
                    .first { it.isShown && it.contentDescription?.toString() == panel.context.getString(UiR.string.expression_happy) }
                assertTrue(label.width > 0 && label.height > 0 && label.text.isNotEmpty())
                val grid = descendants(expressions).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().single()
                val cell = grid.getChildAt(0) as TextView
                selected = cell.text.toString()
                assertTrue(selected.isNotEmpty())
                val time = SystemClock.uptimeMillis()
                for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                    android.view.MotionEvent.obtain(time, time, action, cell.width / 2f, cell.height / 2f, 0).also {
                        cell.dispatchTouchEvent(it)
                        it.recycle()
                    }
                }
            }
            instrumentation.waitForIdleSync()
            onMain { assertEquals(selected, activity.editor.text.toString()) }
        } finally {
            activity?.let { onMain { it.finish() } }
            if (original.isNotBlank() && original != "null") shell("ime set $original")
        }
    }

    private fun click(root: View, label: Int) {
        descendants(root).first { it.isShown && it.contentDescription?.toString() == root.context.getString(label) }.performClick()
    }

    private fun savePublicFixture(name: String) {
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "expression-fixtures").apply { mkdirs() }
            java.io.File(directory, name).outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
    }

    @Test fun immersiveEditorsRemainVisibleAboveTheKeyboardAfterRotation() {
        val original = shell("settings get secure default_input_method").trim()
        val method = "dev.zeroinput.ime.debug/dev.zeroinput.ime.ZeroInputService"
        var activity: InputFixtureActivity? = null
        try {
            shell("ime enable $method")
            shell("ime set $method")
            for (orientation in listOf(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)) {
                activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, InputFixtureActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("fullscreen_fixture", true)) as InputFixtureActivity
                onMain { activity.requestedOrientation = orientation }
                val panel = awaitPanel(orientation)
                val fixtureImage = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                val folder = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "expression-fixtures").apply { mkdirs() }
                java.io.File(folder, "fullscreen-$orientation.png").outputStream().use {
                    fixtureImage.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                fixtureImage.recycle()
                onMain {
                    val point = IntArray(2)
                    panel.getLocationOnScreen(point)
                    assertTrue("The keyboard must leave host content visible", point[1] > 60 * panel.resources.displayMetrics.density)
                    assertTrue("Keyboard content must fit inside its window", panel.height <= panel.rootView.height)
                }
                // Only this constructed, solid-color fixture and the public keyboard are captured.
                val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    val pixel = image.getPixel(image.width / 2, image.height / 8)
                    assertTrue("Fullscreen host content must remain visible", android.graphics.Color.green(pixel) > 120 &&
                        android.graphics.Color.red(pixel) < 80)
                } finally { image.recycle() }
                onMain { activity.finish() }
                instrumentation.waitForIdleSync()
            }
        } finally {
            activity?.let { onMain { it.finish() } }
            if (original.isNotBlank() && original != "null") shell("ime set $original")
        }
    }

    private fun awaitPanel(orientation: Int): ZeroInputView {
        val desired = if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var panel: ZeroInputView? = null
            onMain {
                panel = WindowInspector.getGlobalWindowViews().flatMap(::descendants).filterIsInstance<ZeroInputView>()
                    .firstOrNull { it.isShown && it.height > 0 && it.resources.configuration.orientation == desired }
            }
            panel?.let { SystemClock.sleep(300); return it }
            SystemClock.sleep(100)
        }
        error("Fullscreen fixture keyboard unavailable")
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }
}
