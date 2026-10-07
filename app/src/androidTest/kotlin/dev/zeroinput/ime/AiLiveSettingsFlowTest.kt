package dev.zeroinput.ime

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.AdapterView
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.settings.MainActivity
import dev.zeroinput.ime.testing.InputFixtureActivity
import dev.zeroinput.ime.ui.KeyboardAction
import dev.zeroinput.ime.ui.ZeroInputView
import dev.zeroinput.userdata.AiConfiguration
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import dev.zeroinput.ime.ui.R as UiR

/** Explicit live acceptance of the installed app, without injected providers or dialog callbacks. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class AiLiveSettingsFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph

    @Test fun realSettingsDiscoverSaveSelectProbeAndImeGenerateInsert() {
        val args = InstrumentationRegistry.getArguments()
        val key = args.getString("aiTestKey")
        assumeTrue("Explicit temporary credential required", !key.isNullOrBlank())
        args.remove("aiTestKey")
        val endpoint = checkNotNull(args.getString("aiTestEndpoint"))
        val model = checkNotNull(args.getString("aiTestModel"))
        val original = graph.aiConfiguration.read()
        // This dedicated emulator fixture must never erase an existing conversation store.
        assumeTrue("Use an emulator without saved AI history", !original.saveConversations)
        val method = shell("settings get secure default_input_method").trim()
        val learning = graph.settings.learningEnabled
        val incognito = graph.settings.incognitoMode
        val language = graph.settings.lastLanguage
        val pack = graph.settings.lastLanguagePackKey
        var settings: MainActivity? = null
        try {
            configure(AiConfiguration(enabled = true, networkAllowed = true))
            settings = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            enterProvider(settings, endpoint, checkNotNull(key), model)
            chooseAndProbe(settings, model)
            onMain { settings.finish() }
            await { settings.isDestroyed }
            verifyKeyboard(model)
        } finally {
            settings?.let { onMain { it.finish() } }
            onMain {
                graph.settings.learningEnabled = learning
                graph.settings.incognitoMode = incognito
                graph.settings.lastLanguage = language
                graph.settings.lastLanguagePackKey = pack
            }
            configure(original)
            if (method.isNotBlank() && method != "null") shell("ime set $method")
        }
    }

    private fun enterProvider(activity: MainActivity, endpoint: String, key: String, model: String) {
        await { views(activity.window.decorView).filterIsInstance<TextView>().any {
            it.text == activity.getString(R.string.ai_settings) && it.isEnabled } }
        onMain { views(activity.window.decorView).filterIsInstance<TextView>().first {
            it.text == activity.getString(R.string.ai_settings) }.performClick() }
        tap(R.string.ai_add_provider)
        onMain {
            field(R.string.ai_provider_name).setText("Live acceptance")
            field(R.string.ai_endpoint_hint).setText(endpoint)
            field(R.string.ai_key_hint).setText(key)
            assertTrue("New provider should start with no assumed models", field(R.string.ai_models_hint).text.isEmpty())
        }
        tap(R.string.ai_fetch_models)
        await(35_000) { texts().any { it.text == it.context.getString(R.string.ai_models_select) } }
        val choices = onMain {
            val list = top().filterIsInstance<android.widget.ListView>().single()
            (0 until list.adapter.count).map { list.adapter.getItem(it).toString() }
        }
        assertTrue("Requested live model is absent", model in choices)
        assertTrue("Live fixture must expose multiple models", choices.size > 1)
        selectText(model)
        selectText(choices.first { it != model })
        tap(R.string.ai_models_apply)
        onMain { assertEquals(0, graph.aiConfigurationSnapshot()?.providers?.size) }
        tap(R.string.save)
        await { graph.aiConfigurationSnapshot()?.providers?.singleOrNull()?.models?.size == 2 }
    }

    private fun chooseAndProbe(activity: MainActivity, model: String) {
        onMain { views(activity.window.decorView).filterIsInstance<TextView>().first {
            it.text == activity.getString(R.string.ai_settings) }.performClick() }
        tapLabel { it.text.toString().contains("Live acceptance · ") }
        tap(R.string.ai_choose_model)
        selectText(model)
        await { graph.aiConfigurationSnapshot()?.activeModel() == model }
        onMain { views(activity.window.decorView).filterIsInstance<TextView>().first {
            it.text == activity.getString(R.string.ai_settings) }.performClick() }
        tap(R.string.ai_test_model)
        await(35_000) { texts().any { it.text == it.context.getString(R.string.ai_test_available) } }
        assertEquals(model, graph.aiConfiguration.read().activeModel())
        assertEquals(2, graph.aiConfiguration.read().activeProvider()?.models?.size)
    }

    private fun verifyKeyboard(model: String) {
        onMain {
            graph.settings.learningEnabled = true
            graph.settings.incognitoMode = false
            graph.settings.lastLanguage = dev.zeroinput.engine.api.InputLanguage.ENGLISH
            graph.settings.lastLanguagePackKey = null
        }
        shell("ime enable dev.zeroinput.ime.debug/dev.zeroinput.ime.ZeroInputService")
        shell("ime set dev.zeroinput.ime.debug/dev.zeroinput.ime.ZeroInputService")
        val editor = instrumentation.startActivitySync(Intent(instrumentation.targetContext, InputFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as InputFixtureActivity
        try {
            await { all().any { it is ZeroInputView && it.isShown } }
            onMain {
                editor.editor.imeOptions = 0
                editor.getSystemService(android.view.inputmethod.InputMethodManager::class.java).restartInput(editor.editor)
            }
            await { all().any { it.isShown && it.contentDescription == it.context.getString(UiR.string.ai_open) } }
            onMain { all().first { it.isShown && it.contentDescription == it.context.getString(UiR.string.ai_open) }.performClick() }
            await { panel().isAiEditing }
            typeDraft("my number is 742619 reply ok")
            onMain { assertTrue("Public marker must reach the draft", panel().findViewById<TextView>(UiR.id.ai_draft_text)
                .text.contains("742619")) }
            generate()
            onMain { assertTrue("Generation must not auto-insert", editor.editor.text.isEmpty()) }
            tapUi(UiR.string.ai_edit)
            await { panel().isAiEditing }
            typeDraft("repeat my number output only digits")
            generate()
            onMain { assertTrue("Real follow-up must use history", resultText().contains("742619")) }
            tapUi(UiR.string.ai_new_conversation)
            onMain {
                assertTrue(panel().isAiEditing)
                assertTrue(resultText().isEmpty())
                assertTrue(panel().findViewById<TextView>(UiR.id.ai_draft_text).text.isEmpty())
                assertFalse(checkNotNull(uiText(UiR.string.ai_insert)).isEnabled)
            }
            typeDraft("public unsent draft")
            val other = checkNotNull(graph.aiConfigurationSnapshot()?.activeProvider()).models.first { it != model }
            quickSwitch(other)
            onMain {
                assertTrue(panel().isAiEditing)
                assertTrue(panel().findViewById<TextView>(UiR.id.ai_draft_text).text.isEmpty())
            }
            quickSwitch(model)
            assertEquals(model, graph.aiConfiguration.read().activeModel())
            typeDraft("reply with ready")
            generate()
            onMain { assertTrue("No generation may auto-insert", editor.editor.text.isEmpty()) }
            tapUi(UiR.string.ai_insert)
            await { editor.editor.text.isNotEmpty() && !panel().isAiOpen }
        } finally { onMain { editor.finish() } }
    }

    private fun quickSwitch(model: String) {
        onMain { panel().findViewById<View>(UiR.id.ai_model_button).performClick() }
        selectText(model)
        await { panel().findViewById<TextView>(UiR.id.ai_model_button).text ==
            panel().context.getString(UiR.string.ai_current_model, model) }
    }

    private fun typeDraft(text: String) = onMain {
        val existing = panel().findViewById<TextView>(UiR.id.ai_draft_text).text.length
        repeat(existing + 1) { panel().onAiDraftChanged(KeyboardAction.Backspace) }
        for (character in text) panel().onAiDraftChanged(
            if (character == ' ') KeyboardAction.Space else KeyboardAction.Text(character.toString()))
        panel().onAiDraftChanged(KeyboardAction.Space)
    }

    private fun generate() {
        tapUi(UiR.string.ai_submit)
        await(65_000) { uiText(UiR.string.ai_insert)?.isEnabled == true }
    }

    private fun resultText() = panel().findViewById<TextView>(UiR.id.ai_result_text).text.toString()

    private fun tap(id: Int) = tapLabel { it.text == it.context.getString(id) && it.isEnabled }
    private fun tapLabel(match: (TextView) -> Boolean) {
        await { texts().any { it.isLaidOut && it.width > 0 && match(it) } }
        onMain { click(texts().first { it.isLaidOut && it.width > 0 && match(it) }) }
        instrumentation.waitForIdleSync()
    }
    private fun selectText(value: String) {
        onMain { top().filterIsInstance<android.widget.ListView>().single().let { list ->
            val index = (0 until list.adapter.count).first { list.adapter.getItem(it).toString() == value }
            list.setSelection(index)
        } }
        tapLabel { it.text.toString() == value }
    }
    private fun click(view: View) {
        var ancestor = view.parent
        while (ancestor != null && ancestor !is AdapterView<*>) ancestor = ancestor.parent
        val parent = ancestor as? AdapterView<*>
        if (parent == null) view.performClick() else {
            val index = parent.getPositionForView(view)
            parent.performItemClick(view, index, parent.getItemIdAtPosition(index))
        }
    }
    private fun field(id: Int) = top().filterIsInstance<EditText>().single { it.hint == it.context.getString(id) }
    private fun panel() = all().filterIsInstance<ZeroInputView>().single { it.isShown }
    private fun uiText(id: Int) = views(panel()).filterIsInstance<TextView>().firstOrNull { it.isShown && it.text == it.context.getString(id) }
    private fun tapUi(id: Int) { await { uiText(id)?.isEnabled == true }; onMain { checkNotNull(uiText(id)).performClick() } }
    private fun top() = WindowInspector.getGlobalWindowViews().lastOrNull()?.let(::views).orEmpty()
    private fun texts() = top().filterIsInstance<TextView>().filter { it.isShown }
    private fun all() = WindowInspector.getGlobalWindowViews().flatMap(::views)
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun await(timeout: Long = 10_000, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) { if (onMain(check)) return; SystemClock.sleep(50) }
        val controls = onMain {
            val ids = listOf(R.string.ai_settings_title, R.string.ai_edit_provider, R.string.ai_choose_model,
                R.string.ai_test_model, R.string.ai_test_available, R.string.ai_models_select,
                R.string.ai_test_running, R.string.ai_test_cancelled)
            ids.filter { id -> texts().any { it.text == it.context.getString(id) } }
                .map { instrumentation.targetContext.resources.getResourceEntryName(it) }
        }
        fail("Live app workflow did not reach the required state; visible controls=$controls")
    }
    private fun configure(value: AiConfiguration) {
        val done = CountDownLatch(1)
        var success = false
        onMain { graph.updateAiConfiguration(value) { success = it; done.countDown() } }
        assertTrue(done.await(10, TimeUnit.SECONDS)); assertTrue(success)
    }
    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
    }
}
