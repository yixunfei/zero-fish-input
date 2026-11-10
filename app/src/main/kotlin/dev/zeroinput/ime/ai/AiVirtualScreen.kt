package dev.zeroinput.ime.ai

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.widget.TextView
import dev.zeroinput.ime.R

/** Public fixture only. This private display has no app-launch or input-injection port. */
internal class AiVirtualScreen(private val context: Context, private val stopped: () -> Unit) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private var virtual: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var generation = 0L
    private val expiry = Runnable { close() }
    val displayId get() = virtual?.display?.displayId
    fun start(surface: Surface): Boolean {
        close()
        if (!surface.isValid) return false
        val token = ++generation
        return try {
            val display = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
                "ZeroInput public fixture", WIDTH, HEIGHT, 160, surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                object : VirtualDisplay.Callback() {
                    override fun onStopped() { if (generation == token) close() }
                }, main) ?: return false
            virtual = display
            check(display.display.displayId != Display.DEFAULT_DISPLAY)
            presentation = Presentation(context, display.display).also {
                // The only content rendered here is a fixed public resource, never user text.
                it.setContentView(TextView(it.context).apply {
                    setText(R.string.ai_virtual_fixture); textSize = 28f; gravity = Gravity.CENTER
                    setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(24, 48, 64)); isSaveEnabled = false
                    importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
                })
                it.show()
            }
            main.postDelayed(expiry, 120_000)
            true
        } catch (_: RuntimeException) { close(); false }
    }
    override fun close() {
        val active = virtual != null
        generation++; main.removeCallbacks(expiry)
        val oldPresentation = presentation; presentation = null
        val oldDisplay = virtual; virtual = null
        try { oldPresentation?.dismiss() } finally { oldDisplay?.release() }
        if (active) stopped()
    }
    companion object { const val WIDTH = 640; const val HEIGHT = 360 }
}
