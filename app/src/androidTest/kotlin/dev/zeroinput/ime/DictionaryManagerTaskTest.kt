package dev.zeroinput.ime

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.dictionaries.DictionaryManagerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DictionaryManagerTaskTest {
    @Test fun cancellingAnActiveLoadReturnsToTheInstalledDictionaries() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = (context.applicationContext as ZeroInputApplication).graph.publicDictionaries

        ActivityScenario.launch(DictionaryManagerActivity::class.java).use { scenario ->
            await(scenario, "Initial installed list") { it.hasInstalledRows() }
            val acquired = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val blocker = executor.submit {
                store.withEnabledFiles {
                    acquired.countDown()
                    check(release.await(15, TimeUnit.SECONDS))
                }
            }
            try {
                assertTrue("The installed snapshot read can be held in progress", acquired.await(5, TimeUnit.SECONDS))
                scenario.onActivity { activity ->
                    activity.control(R.string.public_dictionary_home).performClick()
                    assertEquals(activity.getString(R.string.public_dictionary_working), activity.status())
                    val cancel = activity.control(R.string.public_dictionary_cancel)
                    assertTrue(cancel.isEnabled && cancel.isShown)
                    cancel.performClick()
                }
            } finally {
                release.countDown()
                try { blocker.get(5, TimeUnit.SECONDS) } finally { executor.shutdownNow() }
            }

            await(scenario, "Installed rows after cancellation") { it.hasInstalledRows() }
            scenario.onActivity { activity ->
                assertEquals(activity.getString(R.string.public_dictionary_cancelled), activity.status())
                val cancel = activity.control(R.string.public_dictionary_cancel)
                assertTrue("Cancel is available only during a task", !cancel.isEnabled || !cancel.isShown)
            }
        }
    }

    private fun DictionaryManagerActivity.status(): String =
        findViewById<TextView>(R.id.management_status_message).text.toString()

    private fun DictionaryManagerActivity.hasInstalledRows(): Boolean = views(window.decorView)
        .filterIsInstance<TextView>().any { it.isShown && it.text.toString() == getString(R.string.public_dictionary_builtin) }

    private fun DictionaryManagerActivity.control(label: Int): TextView = views(window.decorView)
        .filterIsInstance<TextView>().single { it.isClickable && it.text.toString() == getString(label) }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(views(view.getChildAt(index)))
    }

    private fun await(scenario: ActivityScenario<DictionaryManagerActivity>, name: String,
        condition: (DictionaryManagerActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            scenario.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(50)
        }
        fail("$name was not displayed")
    }
}
