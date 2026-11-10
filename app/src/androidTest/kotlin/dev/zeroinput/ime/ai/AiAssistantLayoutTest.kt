package dev.zeroinput.ime.ai

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ai.api.AiStreamEvent
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ui.AiAssistantPanelView
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

/** Detached native Views with fixed public samples only; never captures the live private host. */
@RunWith(AndroidJUnit4::class)
class AiAssistantLayoutTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun publicSamplesRemainUsableInPortraitAndLargeFontLandscapeInBothThemes() {
        val frames = mutableListOf<Pair<String, Bitmap>>()
        try {
            instrumentation.runOnMainSync {
                for (night in listOf(false, true)) for (landscape in listOf(false, true)) {
                    val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                        fontScale = 2f
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    }
                    val context = ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(config), R.style.Theme_ZeroInput)
                    val panel = AiAssistantPanelView(context)
                    panel.ready(context.getString(R.string.ai_assistant_network_disabled), false, true)
                    panel.findViewById<EditText>(UiR.id.ai_assistant_prompt).setText("Public layout sample")
                    panel.render(AiStreamEvent.Completed((1..6).joinToString("\n\n") { "Public answer section $it" }))
                    val density = context.resources.displayMetrics.density
                    val width = ((if (landscape) 600 else 360) * density).toInt()
                    val height = ((if (landscape) 320 else 720) * density).toInt()
                    panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    panel.layout(0, 0, width, height)
                    val device = panel.findViewById<android.widget.TextView>(UiR.id.ai_assistant_device)
                    assertTrue(device.layout.lineCount <= device.maxLines)
                    for (line in 0 until device.layout.lineCount) assertEquals(0, device.layout.getEllipsisCount(line))
                    val send = panel.findViewById<View>(UiR.id.ai_assistant_send)
                    assertTrue(send.width > 0 && send.width < width)
                    val body = panel.findViewById<android.widget.ScrollView>(UiR.id.ai_assistant_body)
                    if (landscape) assertTrue(body.canScrollVertically(1))
                    val sendRect = android.graphics.Rect()
                    assertTrue(send.getGlobalVisibleRect(sendRect))
                    assertTrue(sendRect.bottom <= height)
                    if (InstrumentationRegistry.getArguments().getString("assistantVisuals") == "true") {
                        frames += "assistant-$night-$landscape-top.png" to drawPublicView(panel, width, height)
                        body.scrollTo(0, body.getChildAt(0).height)
                        if (landscape) assertTrue(body.scrollY > 0)
                        frames += "assistant-$night-$landscape-bottom.png" to drawPublicView(panel, width, height)
                    }
                }
            }
            if (frames.isNotEmpty()) {
                val directory = File(instrumentation.targetContext.cacheDir, "ai-assistant-public-layout")
                check(directory.isDirectory || directory.mkdirs())
                frames.forEach { (name, bitmap) ->
                    File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                }
            }
        } finally {
            frames.forEach { it.second.recycle() }
        }
    }

    private fun drawPublicView(panel: View, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val color = TypedValue()
            panel.context.theme.resolveAttribute(android.R.attr.colorBackground, color, true)
            val canvas = Canvas(bitmap)
            canvas.drawColor(color.data)
            canvas.translate(-panel.scrollX.toFloat(), -panel.scrollY.toFloat())
            panel.draw(canvas)
            return bitmap
        } catch (error: Exception) { bitmap.recycle(); throw error }
    }
}
