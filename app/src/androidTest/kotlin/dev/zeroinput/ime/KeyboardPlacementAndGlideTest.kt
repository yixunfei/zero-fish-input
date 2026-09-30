package dev.zeroinput.ime

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.engine.api.GlideLayout
import dev.zeroinput.engine.api.GlideRequest
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.ime.core.InputSessionState
import dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
import dev.zeroinput.ime.ui.*
import dev.zeroinput.ime.ui.R as imeUiR
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Constructed public fixtures only; no editor text, personal catalog or screenshots of secrets. */
@RunWith(AndroidJUnit4::class)
class KeyboardPlacementAndGlideTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun allModesStayInsideViewportAndRestoreFromMinimizedState() = withActivity { activity ->
        lateinit var host: KeyboardLayoutHost
        onMain {
            (activity.keyboard.parent as ViewGroup).removeView(activity.keyboard)
            host = KeyboardLayoutHost(activity.keyboard.context, activity.keyboard)
            activity.setContentView(FrameLayout(activity).apply {
                addView(host, FrameLayout.LayoutParams(-1, -1))
            })
        }
        for (mode in KeyboardPlacementMode.entries) {
            onMain { host.selectMode(mode) }
            instrumentation.waitForIdleSync()
            onMain {
                val bounds = host.inputBounds()
                assertTrue(bounds.left >= 0 && bounds.top >= 0)
                assertTrue(bounds.right <= host.width && bounds.bottom <= host.height)
                assertTrue(bounds.width() > 0 && bounds.height() > 0)
                if (mode == KeyboardPlacementMode.FLOATING) host.setMinimized(true)
            }
            instrumentation.waitForIdleSync()
            if (mode == KeyboardPlacementMode.FLOATING) {
                onMain {
                    assertTrue(host.minimized)
                    assertEquals(View.GONE, activity.keyboard.visibility)
                    val restore = views(host).first { it.contentDescription?.toString() == activity.getString(imeUiR.string.keyboard_restore) }
                    restore.performClick()
                }
                instrumentation.waitForIdleSync()
                onMain { assertFalse(host.minimized); assertEquals(View.VISIBLE, activity.keyboard.visibility) }
            }
            capture("placement-${mode.name.lowercase()}.png")
        }
    }

    @Test fun resizingAndBothHandsPreserveAllTouchTargets() = withActivity { activity ->
        onMain {
            (activity.keyboard.parent as ViewGroup).removeView(activity.keyboard)
            val host = KeyboardLayoutHost(activity, activity.keyboard)
            for (width in listOf(320, 411, 800)) for (mode in KeyboardPlacementMode.entries) {
                host.applyPlacement(KeyboardPlacement(mode, oneHandWidth = 0.65f, floatingWidth = 0.5f,
                    horizontalPosition = 1f, verticalPosition = 1f, heightScale = 1.4f))
                host.measure(View.MeasureSpec.makeMeasureSpec(dp(activity, width), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dp(activity, 650), View.MeasureSpec.AT_MOST))
                host.layout(0, 0, host.measuredWidth, host.measuredHeight)
                assertTrue(host.inputBounds().right <= host.width)
                assertTrue(host.inputBounds().bottom <= host.height)
                assertTrue(activity.keyboard.measuredWidth > 0)
            }
            host.release()
        }
    }

    @Test fun glideOwnsItsStrokeWithoutSendingTapKeysAndCancelDropsIt() = withActivity { activity ->
        instrumentation.waitForIdleSync()
        onMain {
            val keyboard = views(activity.keyboard).filterIsInstance<KeyboardPanel>().single()
            keyboard.configureGlide(GlideLayout.ENGLISH_QWERTY)
            val taps = mutableListOf<KeyboardAction>()
            var request: GlideRequest? = null
            keyboard.onAction = { taps += it }
            keyboard.onGlideRequest = { value, _ -> request = value }
            trace(keyboard, "hello", cancel = false)
            assertTrue(taps.isEmpty())
            assertEquals(GlideLayout.ENGLISH_QWERTY, request?.layout)
            assertTrue(requireNotNull(request).keys.size >= 26)
            request = null
            trace(keyboard, "world", cancel = true)
            assertNull(request)
            assertTrue(taps.isEmpty())
        }
    }

    @Test fun dragResizeAndAccessibilityMoveUpdateBoundedPersistentPlacement() = withActivity { activity ->
        lateinit var host: KeyboardLayoutHost
        val saved = mutableListOf<KeyboardPlacement>()
        onMain {
            (activity.keyboard.parent as ViewGroup).removeView(activity.keyboard)
            host = KeyboardLayoutHost(activity.keyboard.context, activity.keyboard)
            host.onPlacementChanged = { saved += it }
            host.applyPlacement(KeyboardPlacement(KeyboardPlacementMode.FLOATING,
                floatingWidth = 0.8f, horizontalPosition = 0.5f, verticalPosition = 0.5f))
            activity.setContentView(FrameLayout(activity).apply { addView(host, FrameLayout.LayoutParams(-1, -1)) })
        }
        instrumentation.waitForIdleSync()
        onMain {
            val move = views(host).first { it.contentDescription == activity.getString(imeUiR.string.keyboard_move) }
            drag(move, -10_000f, -10_000f)
            assertEquals(0f, host.placement.horizontalPosition)
            assertEquals(0f, host.placement.verticalPosition)
            assertEquals(host.placement, saved.last())
            assertTrue(move.performAccessibilityAction(androidx.core.view.accessibility.AccessibilityNodeInfoCompat
                .AccessibilityActionCompat.ACTION_SCROLL_RIGHT.id, null))
            assertEquals(0.1f, host.placement.horizontalPosition, 0.001f)
            val resize = views(host).first { it.contentDescription == activity.getString(imeUiR.string.keyboard_resize) }
            drag(resize, -40f, 50f)
            assertTrue(host.placement.floatingWidth < 0.8f)
            assertTrue(host.placement.heightScale > 1f)
            assertEquals(host.placement, saved.last())
        }
        instrumentation.waitForIdleSync()
        onMain {
            val bounds = host.inputBounds()
            assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= host.width && bounds.bottom <= host.height)
            host.selectMode(KeyboardPlacementMode.RIGHT_HAND)
        }
        instrumentation.waitForIdleSync()
        onMain {
            val initialWidth = host.placement.oneHandWidth
            val resize = views(host).first { it.contentDescription == activity.getString(imeUiR.string.keyboard_resize) }
            drag(resize, 30f, 0f)
            assertTrue(host.placement.oneHandWidth < initialWidth)
        }
    }

    private fun drag(view: View, dx: Float, dy: Float) {
        val time = SystemClock.uptimeMillis()
        send(view, time, time, MotionEvent.ACTION_DOWN, 10f, 10f)
        send(view, time, time + 50, MotionEvent.ACTION_MOVE, 10f + dx, 10f + dy)
        send(view, time, time + 100, MotionEvent.ACTION_UP, 10f + dx, 10f + dy)
    }

    @Test fun ordinaryTapStillSendsExactlyOneKeyWithGlideEnabled() = withActivity { activity ->
        instrumentation.waitForIdleSync()
        onMain {
            val keyboard = views(activity.keyboard).filterIsInstance<KeyboardPanel>().single()
            keyboard.configureGlide(GlideLayout.ENGLISH_QWERTY)
            val taps = mutableListOf<KeyboardAction>()
            keyboard.onAction = { taps += it }
            trace(keyboard, "h", cancel = false)
            assertEquals(listOf(KeyboardAction.Text("h")), taps)
        }
    }

    @Test fun passwordAndNumericEditorsDisableGlide() = withActivity { activity ->
        onMain {
            val password = android.view.inputmethod.EditorInfo().apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            activity.keyboard.startEditor(dev.zeroinput.ime.core.EditorInputOptions.from(password))
            activity.keyboard.renderSession(InputSessionState(privacy = dev.zeroinput.ime.core.privacy.EditorPrivacyPolicy()
                .evaluate(password, dev.zeroinput.ime.core.privacy.PrivacyConfiguration())))
            assertNull(activity.keyboard.availableGlideLayout)
        }
    }

    private fun trace(keyboard: KeyboardPanel, text: String, cancel: Boolean) {
        val start = SystemClock.uptimeMillis()
        val centers = text.map { code ->
            val key = views(keyboard).filterIsInstance<TextView>().first { it.text.toString() == code.toString() }
            val rect = android.graphics.Rect()
            key.getDrawingRect(rect)
            keyboard.offsetDescendantRectToMyCoords(key, rect)
            assertTrue("Public key $code bounds $rect exceed ${keyboard.width}x${keyboard.height}",
                rect.left >= 0 && rect.top >= 0 && rect.right <= keyboard.width && rect.bottom <= keyboard.height)
            rect.exactCenterX() to rect.exactCenterY()
        }
        centers.forEachIndexed { index, (x, y) ->
            send(keyboard, start, start + index * 35, if (index == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE, x, y)
        }
        val (x, y) = centers.last()
        send(keyboard, start, start + centers.size * 35, if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, x, y)
    }

    private fun send(view: View, down: Long, time: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, time, action, x, y, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun withActivity(action: (KeyboardPreviewFixtureActivity) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, KeyboardPreviewFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as KeyboardPreviewFixtureActivity
        try { action(activity) } finally { onMain { activity.finish() } }
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun dp(view: android.content.Context, value: Int) = (value * view.resources.displayMetrics.density).toInt()
    private fun capture(name: String) {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val folder = File(instrumentation.targetContext.getExternalFilesDir(null), "keyboard-fixtures").apply { mkdirs() }
            File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        requireNotNull(result).getOrThrow()
    }
}
