package dev.zeroinput.ime.testing

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue

/** Screen touches on attached fixtures; waits for the IME/window animation before hit testing. */
internal object DeviceTouch {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    fun tap(find: () -> View) = tapInternal(find, reveal = true)

    /** A discoverable control must already fit on screen without test-driven scrolling. */
    fun tapVisible(find: () -> View) = tapInternal(find, reveal = false)

    private fun tapInternal(find: () -> View, reveal: Boolean) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350)
        if (reveal) onMain { find().let { it.requestRectangleOnScreen(Rect(0, 0, it.width, it.height), true) } }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(100)
        val bounds = onMain {
            val target = find()
            val location = IntArray(2).also(target::getLocationOnScreen)
            val rect = Rect(location[0], location[1], location[0] + target.width, location[1] + target.height)
            val metrics = target.resources.displayMetrics
            assertTrue("Expected a laid out, visible touch target: $rect", target.isShown && rect.width() > 0 &&
                rect.height() > 0 && rect.left >= 0 && rect.top >= 0 && rect.right <= metrics.widthPixels &&
                rect.bottom <= metrics.heightPixels)
            rect
        }
        instrumentation.uiAutomation.executeShellCommand("input tap ${bounds.centerX()} ${bounds.centerY()}").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
        }
        instrumentation.waitForIdleSync()
    }

    private fun <T> onMain(action: () -> T): T {
        var value: Result<T>? = null
        instrumentation.runOnMainSync { value = runCatching(action) }
        return checkNotNull(value).getOrThrow()
    }
}
