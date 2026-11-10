package dev.zeroinput.ime.ai

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ListView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ai.api.*
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.ai.device.*
import dev.zeroinput.ime.ui.AiAssistantPanelView
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

@RunWith(AndroidJUnit4::class)
class AiInteractionPanelTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val graph get() = (context.applicationContext as ZeroInputApplication).graph

    @Test fun queueAndInterruptAreDistinctExplicitActionsAndEditingKeepsCurrentDraft() {
        instrumentation.runOnMainSync {
            val themed = androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_ZeroInput)
            val panel = AiAssistantPanelView(themed)
            val sent = mutableListOf<String>()
            val interrupted = mutableListOf<String>()
            panel.onSubmit = { _, text, _ -> sent += text }
            panel.onInterrupt = { _, text, _ -> interrupted += text }
            panel.ready("Public fixture ready", true, false)
            panel.render(AiStreamEvent.Started)
            panel.renderQueue(listOf("Public queued message"), true)
            val prompt = panel.findViewById<EditText>(UiR.id.ai_assistant_prompt)
            prompt.setText("Public follow-up")
            panel.findViewById<View>(UiR.id.ai_assistant_send).performClick()
            assertEquals(listOf("Public follow-up"), sent)
            assertTrue(interrupted.isEmpty())
            panel.findViewById<View>(UiR.id.ai_assistant_interrupt).performClick()
            assertEquals(listOf("Public follow-up"), interrupted)
            panel.draftOccupied()
            assertEquals("Public follow-up", prompt.text.toString())
            panel.clear()
            assertFalse(panel.hasDraft())
            assertEquals(View.GONE, panel.findViewById<View>(UiR.id.ai_assistant_interrupt).visibility)
        }
    }

    @Test fun publicDeviceFormKeepsControlsVisibleAndDiscoveryNeverGrantsOrStarts() {
        graph.deviceAgentCoordinator.clearTimeline()
        val activity = instrumentation.startActivitySync(Intent(context, AiDeviceAgentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as AiDeviceAgentActivity
        val frames = mutableListOf<Pair<String, Bitmap>>()
        try {
            instrumentation.runOnMainSync {
                val policy = graph.deviceAgentPolicySnapshot()
                val overview = DeviceCapabilityOverview.show(activity, policy, false)
                overview.dismiss()
                val current = graph.deviceAgentPolicySnapshot()
                assertTrue(policy.enabled == current.enabled && policy.disclosureAccepted == current.disclosureAccepted &&
                    policy.stage6Capabilities == current.stage6Capabilities && policy.scope == current.scope &&
                    policy.allowScreenshots == current.allowScreenshots && policy.stage6DisclosureAccepted == current.stage6DisclosureAccepted)
                assertFalse(graph.deviceAgentCoordinator.hasActiveTask)
                checkAppSearch(activity)
                activity.findViewById<EditText>(R.id.ai_device_target).setText("public.fixture")
                activity.findViewById<EditText>(R.id.ai_device_task).setText("Public task layout sample")
                val root = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
                root.setPadding(0, 0, 0, 0)
                val density = activity.resources.displayMetrics.density
                for ((w, h) in listOf(360 to 640, 600 to 320, 360 to 280)) {
                    val width = (w * density).toInt()
                    val height = (h * density).toInt()
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, height)
                    for (id in listOf(R.id.ai_device_start, R.id.ai_device_stop)) {
                        val control = root.findViewById<View>(id)
                        val bounds = Rect(0, 0, control.width, control.height)
                        root.offsetDescendantRectToMyCoords(control, bounds)
                        assertTrue(bounds.top >= 0 && bounds.bottom <= height)
                        assertTrue(control.height >= (48 * density).toInt())
                    }
                    if (InstrumentationRegistry.getArguments().getString("assistantVisuals") == "true") {
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        val color = android.util.TypedValue()
                        activity.theme.resolveAttribute(android.R.attr.colorBackground, color, true)
                        canvas.drawColor(color.data)
                        root.draw(canvas)
                        frames += "device-$w-$h.png" to bitmap
                    }
                }
            }
            val directory = File(context.cacheDir, "ai-assistant-public-layout")
            check(directory.isDirectory || directory.mkdirs())
            frames.forEach { (name, bitmap) -> File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        } finally {
            frames.forEach { it.second.recycle() }
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun checkAppSearch(activity: AiDeviceAgentActivity) {
        var selected: DeviceAgentAppChoice? = null
        val choices = listOf(DeviceAgentAppChoice("Public Notes", "public.notes"), DeviceAgentAppChoice("Public Camera", "public.camera"))
        val dialog = DeviceAppChoiceDialog.show(activity, choices) { selected = it }
        val views = mutableListOf<View>()
        fun collect(view: View) {
            views += view
            if (view is ViewGroup) for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
        collect(requireNotNull(dialog.window).decorView)
        views.filterIsInstance<EditText>().single().setText("CAMERA")
        val list = views.filterIsInstance<ListView>().single()
        assertEquals(1, list.adapter.count)
        list.performItemClick(View(activity), 0, 0)
        assertEquals(choices[1], selected)
        assertFalse(graph.deviceAgentCoordinator.hasActiveTask)
        dialog.dismiss()
    }
}
