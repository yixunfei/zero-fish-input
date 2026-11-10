package dev.zeroinput.ime.ai

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiNativeAssistantPlatformTest {
    @Test fun componentsAreSystemBoundAndForegroundServiceCannotBeExternallyStarted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (type in listOf(AiVoiceInteractionService::class.java, AiVoiceSessionService::class.java)) {
            val info = context.packageManager.getServiceInfo(ComponentName(context, type), PackageManager.GET_META_DATA)
            assertTrue(info.exported); assertEquals("android.permission.BIND_VOICE_INTERACTION", info.permission)
        }
        for (type in listOf(AiTaskForegroundService::class.java, AiDisabledRecognitionService::class.java))
            assertFalse(context.packageManager.getServiceInfo(ComponentName(context, type), 0).exported)
        assertFalse(context.packageManager.getReceiverInfo(ComponentName(context, AiTaskStopReceiver::class.java), 0).exported)
    }
    @Test fun unsolicitedAssistDataAndScreenshotDoNotPopulateOrStartTheTextSurface() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val session = AiNativeSession(instrumentation.targetContext)
            val public = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            try {
                session.onHandleAssist(Bundle().apply { putString(Intent.EXTRA_TEXT, "Untrusted public fixture") }, null, null)
                session.onHandleAssistSecondary(Bundle(), null, null, 1, 2)
                session.onHandleScreenshot(public)
                val view = session.onCreateContentView()
                assertEquals("", view.findViewById<EditText>(R.id.ai_native_prompt).text.toString())
                assertFalse(descendants(view).filterIsInstance<TextView>().any { it.text.toString().contains("Untrusted public fixture") })
            } finally { public.recycle() }
        }
    }
    @Test fun compactPromptKeepsControlsVisibleInBothThemesAndLandscape() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (night in listOf(false, true)) for (landscape in listOf(false, true)) {
                val config = android.content.res.Configuration(instrumentation.targetContext.resources.configuration).apply {
                    fontScale = 1.5f
                    uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
                }
                val context = androidx.appcompat.view.ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(config), R.style.Theme_ZeroInput)
                val view = AiNativePromptView(context)
                view.findViewById<EditText>(R.id.ai_native_prompt).setText("Public draft\n".repeat(8)); view.result("Public answer\n".repeat(20))
                val width = ((if (landscape) 600 else 360) * context.resources.displayMetrics.density).toInt()
                val height = ((if (landscape) 260 else 420) * context.resources.displayMetrics.density).toInt()
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, width, view.measuredHeight)
                val send = descendants(view).filterIsInstance<android.widget.Button>().single { it.text.toString() == context.getString(R.string.ai_native_send) }
                val rect = android.graphics.Rect(); assertTrue(send.getGlobalVisibleRect(rect)); assertEquals(send.height, rect.height()); assertTrue(rect.bottom <= height)
            }
        }
    }
    private fun descendants(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) } else emptyList()
}
