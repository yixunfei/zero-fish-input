package dev.zeroinput.ime

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.dictionaries.DictionaryManagerActivity
import dev.zeroinput.ime.clipboardguard.ClipboardDeviceTestSupport as Device
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DictionaryManagerTaskTest {
    @Test fun liveSourceHomepagesReachTheNativeDownloadConfirmation() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePublicDictionary") == "true")
        ActivityScenario.launch(DictionaryManagerActivity::class.java).use { scenario ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            for (source in listOf(R.string.public_dictionary_qq, R.string.public_dictionary_sogou)) {
                await(scenario, "Installed list") { it.hasInstalledRows() }
                scenario.onActivity { it.control(source).performClick() }
                clickDialog(context.getString(R.string.public_dictionary_browse))
                await(scenario, "Homepage download command") { it.downloadRows().any() }
                scenario.onActivity { activity ->
                    activity.control(R.string.public_dictionary_categories).performClick()
                    assertTrue(activity.downloadRows().none())
                    activity.control(R.string.public_dictionary_show_downloads).performClick()
                    activity.downloadRows().first().performClick()
                }
                Device.await { dialogText(context.getString(R.string.public_dictionary_download)) != null }
                Device.shell("input keyevent KEYCODE_BACK")
                scenario.onActivity { it.control(R.string.public_dictionary_home).performClick() }
            }
        }
    }

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

    private fun DictionaryManagerActivity.downloadRows(): Sequence<TextView> = views(window.decorView)
        .filterIsInstance<TextView>().filter {
            it.isShown && it.isClickable && it.text.startsWith(getString(R.string.public_dictionary_download_item, ""))
        }

    private fun dialogText(text: String) =
        Device.findText(text) ?: Device.findText(text.uppercase(java.util.Locale.getDefault()))

    private fun clickDialog(text: String) {
        Device.await("Dialog action was not displayed") { dialogText(text) != null }
        Device.click(checkNotNull(dialogText(text)?.text).toString())
    }

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
