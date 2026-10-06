package dev.zeroinput.ime.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ui.KeyboardAppearance
import dev.zeroinput.ime.ui.KeyboardBackground
import dev.zeroinput.ime.ui.ZeroInputView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyboardAppearanceBindingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun sameImageTransfersToReplacementViewWithoutReloadOrSolidFrame() = onMain {
        val callbacks = mutableListOf<(Bitmap?) -> Unit>()
        val binding = KeyboardAppearanceBinding { _, callback -> callbacks += callback; AutoCloseable {} }
        val first = view()
        val second = view()
        val appearance = appearance("one")
        val pixels = bitmap(Color.BLUE)
        try {
            binding.apply(first, appearance)
            callbacks.single()(pixels)
            binding.apply(second, appearance)
            assertEquals(1, callbacks.size)
            assertFalse(pixels.isRecycled)
            assertNotNull(second.background)
            binding.close()
            assertTrue(pixels.isRecycled)
        } finally { binding.close(); first.release(); second.release() }
    }

    @Test fun changedImageKeepsOldPixelsUntilReplacementIsInstalledThenRecycles() = onMain {
        val callbacks = mutableListOf<(Bitmap?) -> Unit>()
        val binding = KeyboardAppearanceBinding { _, callback -> callbacks += callback; AutoCloseable {} }
        val view = view()
        val old = bitmap(Color.BLUE)
        val next = bitmap(Color.GREEN)
        try {
            binding.apply(view, appearance("one"))
            callbacks[0](old)
            binding.apply(view, appearance("two"))
            assertFalse(old.isRecycled)
            callbacks[1](next)
            assertNotNull(view.background)
            assertTrue(old.isRecycled)
            assertFalse(next.isRecycled)
        } finally { binding.close(); view.release() }
    }

    @Test fun pendingImageFollowsReplacementAndCloseRejectsLatePixels() = onMain {
        val callbacks = mutableListOf<(Bitmap?) -> Unit>()
        val binding = KeyboardAppearanceBinding { _, callback -> callbacks += callback; AutoCloseable {} }
        val first = view()
        val second = view()
        var completions = 0
        try {
            binding.apply(first, appearance("one")) { fail("Old owner must not receive completion") }
            binding.apply(second, appearance("one")) { completions++ }
            val pixels = bitmap(Color.BLUE)
            callbacks.single()(pixels)
            assertEquals(1, completions)
            assertNotNull(second.background)
            binding.apply(second, appearance("two")) { completions++ }
            binding.close()
            val late = bitmap(Color.GREEN)
            callbacks[1](late)
            assertTrue(late.isRecycled)
            assertTrue(pixels.isRecycled)
            assertEquals(1, completions)
        } finally { binding.close(); first.release(); second.release() }
    }

    private fun appearance(id: String) = KeyboardAppearance(background = KeyboardBackground.IMAGE, imageRevision = id)
    private fun view() = ZeroInputView(ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_ZeroInput_InputMethod))
    private fun bitmap(color: Int) = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    private fun backgroundPixel(view: ZeroInputView): Int {
        val rendered = bitmap(Color.BLACK)
        return try {
            view.background.setBounds(0, 0, 8, 8)
            view.background.draw(Canvas(rendered))
            rendered.getPixel(4, 4)
        } finally { rendered.recycle() }
    }
    private fun onMain(action: () -> Unit) {
        var result: Result<Unit>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        checkNotNull(result).getOrThrow()
    }
}
