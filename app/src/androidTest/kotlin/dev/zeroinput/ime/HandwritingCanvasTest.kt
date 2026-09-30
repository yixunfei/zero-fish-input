package dev.zeroinput.ime

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
import dev.zeroinput.ime.ui.ZeroInputView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import dev.zeroinput.ime.ui.R as UiR

@RunWith(AndroidJUnit4::class)
class HandwritingCanvasTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun rectangularSurfacePreservesPhysicalAspectAndNewStrokeInvalidatesOldCandidates() = withCanvas { panel, canvas ->
        val callbacks = ArrayList<List<FloatArray>>()
        panel.onHandwritingStrokesChanged = { values -> callbacks.add(values.map(FloatArray::clone)) }
        canvas.layout(0, 0, 400, 200)
        touch(canvas, MotionEvent.ACTION_DOWN, 100f, 50f)
        touch(canvas, MotionEvent.ACTION_UP, 200f, 150f)
        val points = callbacks.last().single()
        assertEquals(points[2] - points[0], points[3] - points[1], .0001f)
        panel.renderHandwritingCandidates(listOf("中"))
        assertTrue(panel.containsHandwritingCandidate("中"))
        touch(canvas, MotionEvent.ACTION_DOWN, 250f, 80f)
        assertTrue(callbacks.last().isEmpty())
        assertFalse(panel.containsHandwritingCandidate("中"))
        touch(canvas, MotionEvent.ACTION_CANCEL, 250f, 80f)
        assertEquals(1, callbacks.last().size)
        callbacks.flatten().forEach { it.fill(0f) }
    }

    @Test fun longStrokeKeepsItsEndpointAndCallbackBuffersAreWiped() = withCanvas { panel, canvas ->
        var lastCopy: List<FloatArray> = emptyList()
        var delivered: List<FloatArray> = emptyList()
        panel.onHandwritingStrokesChanged = { values ->
            lastCopy.forEach { it.fill(0f) }
            lastCopy = values.map(FloatArray::clone)
            delivered = values
        }
        canvas.layout(0, 0, 400, 200)
        touch(canvas, MotionEvent.ACTION_DOWN, 0f, 10f)
        repeat(700) { index -> touch(canvas, MotionEvent.ACTION_MOVE, (index % 300).toFloat(), (index % 100).toFloat()) }
        touch(canvas, MotionEvent.ACTION_UP, 380f, 170f)
        val points = lastCopy.single()
        assertTrue(points.size <= 1024)
        assertEquals(.95f, points[points.size - 2], .0001f)
        assertEquals(.425f, points.last(), .0001f)
        assertTrue(delivered.single().all { it == 0f })
        canvas.layout(0, 0, 300, 200)
        assertTrue(lastCopy.isEmpty())
    }

    private fun withCanvas(block: (ZeroInputView, View) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, KeyboardPreviewFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as KeyboardPreviewFixtureActivity
        try {
            instrumentation.runOnMainSync {
                val panel = activity.keyboard
                val open = descendants(panel).first { it.contentDescription == panel.context.getString(UiR.string.handwriting_open) }
                open.performClick()
                assertTrue(panel.isHandwritingOpen)
                val canvas = descendants(panel).first { it.contentDescription == panel.context.getString(UiR.string.handwriting_canvas) }
                block(panel, canvas)
            }
        } finally { instrumentation.runOnMainSync { activity.keyboard.release(); activity.finish() } }
    }

    private fun touch(view: View, action: Int, x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(time, time, action, x, y, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
