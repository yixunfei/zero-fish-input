package dev.zeroinput.ime

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.zeroinput.engine.api.Candidate
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.ime.ui.CandidateStripView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CandidateTouchBoundaryTest {
    @Test fun releaseOutsideCandidateDoesNotSelectEvenWithoutMoveEvent() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = owner.get().apply { setTheme(R.style.Theme_ZeroInput) }
        val strip = CandidateStripView(activity)
        activity.setContentView(strip)
        strip.render(EngineSnapshot("ni", "ni", listOf(Candidate("fixture", "你"))))
        strip.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(120, View.MeasureSpec.EXACTLY))
        strip.layout(0, 0, 400, 120)
        var selections = 0
        strip.onCandidateSelected = { selections++ }
        val item = descendants(strip).filterIsInstance<TextView>().single { it.text.toString() == "你" }
        val now = SystemClock.uptimeMillis()
        fun touch(action: Int, x: Float) {
            val event = MotionEvent.obtain(now, now, action, x, item.height / 2f, 0)
            try { item.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        try {
            touch(MotionEvent.ACTION_DOWN, item.width / 2f)
            touch(MotionEvent.ACTION_UP, item.width + 10f)
            assertEquals(0, selections)
            touch(MotionEvent.ACTION_DOWN, item.width / 2f)
            touch(MotionEvent.ACTION_UP, item.width / 2f)
            assertEquals(1, selections)
        } finally { owner.pause().stop().destroy() }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
}
