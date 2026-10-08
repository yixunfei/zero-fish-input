package dev.zeroinput.ime.ai.page

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.checkbox.MaterialCheckBox
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.ui.ZeroInputView
import dev.zeroinput.userdata.AiConfiguration
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import dev.zeroinput.ime.ui.R as UiR

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class PageReferencePlatformTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val context get() = instrumentation.targetContext
    private val graph get() = (context.applicationContext as ZeroInputApplication).graph

    @Test fun actualExternalPageIsExplicitlySelectedAndRevokedWithoutReadingEditors() = withFixture {
        onMain { labelled(UiR.string.ai_open).performClick() }
        await { panelOrNull()?.isAiOpen == true }
        onMain { textButton(UiR.string.ai_page_reference).performClick() }
        await { visible().filterIsInstance<MaterialCheckBox>().any { it.text.toString().contains("Public first reference") } }
        onMain {
            val choices = visible().filterIsInstance<MaterialCheckBox>()
            assertTrue(choices.none { it.isChecked })
            assertTrue(choices.none { it.text.toString().contains("password fixture") ||
                it.text.toString().contains("editable fixture") || it.text.toString().contains("invisible fixture") ||
                it.text.toString().contains("sensitive fixture") })
            choices.single { it.text.toString().contains("Public first reference") }.isChecked = true
            textButton(UiR.string.ai_page_add).performClick()
            assertTrue(visible().filterIsInstance<TextView>().any { it.text.toString() == "Public first reference" })
            assertFalse(visible().filterIsInstance<TextView>().any { it.text.toString() == "Public unchecked reference" })
            assertEquals("", checkNotNull(panelOrNull()).findViewById<TextView>(UiR.id.ai_draft_text).text.toString())
        }
        shell("settings put secure enabled_accessibility_services null")
        await { !graph.pageReferences.connected }
        await { visible().filterIsInstance<TextView>().none { it.text.toString() == "Public first reference" } }
        onMain { assertFalse(textButton(UiR.string.ai_insert).isEnabled) }
    }

    @Test fun changedSourceAndDisabledOptInRejectCapture() = withFixture {
        var result: PageTextSnapshot? = null
        val done = CountDownLatch(1)
        onMain { graph.pageReferences.capture("invalid.fixture.package") { _, value -> result = value; done.countDown() } }
        assertTrue(done.await(8, TimeUnit.SECONDS))
        assertTrue(result?.texts.isNullOrEmpty())
        var token = -1L
        val captured = CountDownLatch(1)
        onMain {
            graph.pageReferences.capture(instrumentation.context.packageName) { request, value ->
                token = request; result = value; captured.countDown()
            }
        }
        assertTrue(captured.await(8, TimeUnit.SECONDS))
        assertTrue(result?.texts?.contains("Public first reference") == true)
        shell("input keyevent 3")
        await("Source switch did not revoke captured references") { !graph.pageReferences.isCurrent(token) }
        onMain { graph.settings.aiPageReferencesEnabled = false }
        val disabled = CountDownLatch(1)
        onMain { graph.pageReferences.capture(instrumentation.context.packageName) { _, value -> result = value; disabled.countDown() } }
        assertTrue(disabled.await(2, TimeUnit.SECONDS))
        assertNull(result)
    }

    private fun withFixture(test: () -> Unit) {
        val initialConfig = graph.aiConfigurationSnapshot() ?: AiConfiguration()
        assumeTrue("Page fixture must not clear saved chats", graph.aiConversations.list().isEmpty())
        val preferences = listOf(graph.settings.aiPageReferencesEnabled, graph.settings.learningEnabled, graph.settings.incognitoMode)
        val resolver = context.contentResolver
        val previousServices = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val previousEnabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        val previousIme = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val ownService = "${context.packageName}/dev.zeroinput.ime.ai.page.PageReferenceService"
        try {
            configure(AiConfiguration(enabled = true, networkAllowed = true))
            onMain {
                graph.settings.learningEnabled = true
                graph.settings.incognitoMode = false
                graph.settings.aiPageReferencesEnabled = true
            }
            shell("settings put secure enabled_accessibility_services $ownService")
            shell("settings put secure accessibility_enabled 1")
            shell("ime enable ${context.packageName}/dev.zeroinput.ime.ZeroInputService")
            shell("ime set ${context.packageName}/dev.zeroinput.ime.ZeroInputService")
            shell("am start -W -n ${instrumentation.context.packageName}/dev.zeroinput.ime.ai.page.ExternalPageFixture -f 0x10008000")
            await("Page service did not connect") { graph.pageReferences.connected }
            await("Fixture keyboard did not open") { panelOrNull() != null }
            test()
        } finally {
            repeat(3) { shell("input keyevent 4") }
            shell("settings put secure enabled_accessibility_services ${previousServices ?: "null"}")
            shell("settings put secure accessibility_enabled $previousEnabled")
            if (!previousIme.isNullOrBlank()) shell("ime set $previousIme")
            onMain {
                graph.settings.aiPageReferencesEnabled = preferences[0]
                graph.settings.learningEnabled = preferences[1]
                graph.settings.incognitoMode = preferences[2]
            }
            configure(initialConfig)
        }
    }

    private fun configure(value: AiConfiguration) {
        val done = CountDownLatch(1)
        var saved = false
        graph.updateAiConfiguration(value) { saved = it; done.countDown() }
        assertTrue(done.await(10, TimeUnit.SECONDS)); assertTrue(saved)
    }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun panelOrNull() = WindowInspector.getGlobalWindowViews().flatMap(::views)
        .filterIsInstance<ZeroInputView>().singleOrNull { it.isShown }
    private fun visible() = panelOrNull()?.let(::views).orEmpty().filter { it.isShown }
    private fun labelled(id: Int) = visible().first { it.contentDescription == context.getString(id) }
    private fun textButton(id: Int) = visible().filterIsInstance<TextView>().first { it.text == context.getString(id) }
    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    private fun await(message: String = "Public page fixture did not settle", condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 12_000
        while (SystemClock.uptimeMillis() < until) {
            if (onMain(condition)) return
            SystemClock.sleep(50)
        }
        assertTrue(message, onMain(condition))
    }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
}
