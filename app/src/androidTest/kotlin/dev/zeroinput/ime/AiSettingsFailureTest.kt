package dev.zeroinput.ime

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import android.view.WindowManager
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.android.material.materialswitch.MaterialSwitch
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.settings.MainActivity
import dev.zeroinput.userdata.AiConfiguration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class AiSettingsFailureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val graph get() = (instrumentation.targetContext.applicationContext as ZeroInputApplication).graph

    @Test fun providerDraftSurvivesAppSwitchWithoutSavingUntilExplicitSave() = withSettings { screen ->
        val original = graph.aiConfiguration.read()
        val endpoint = "https://provider.example/unsaved"
        val model = "public-draft-model"
        val key = "public-draft-key"
        onMain {
            field(R.string.ai_endpoint_hint).setText(endpoint)
            field(R.string.ai_model_hint).setText(model)
            field(R.string.ai_key_hint).setText(key)
            conversationSwitch().isChecked = !original.saveConversations
            screen.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        }
        await { ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(screen) == Stage.STOPPED }
        assertEquals(original, graph.aiConfiguration.read())
        instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        await { ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(screen) == Stage.RESUMED }
        onMain {
            assertEquals(endpoint, field(R.string.ai_endpoint_hint).text.toString())
            assertEquals(model, field(R.string.ai_model_hint).text.toString())
            assertEquals(key, field(R.string.ai_key_hint).text.toString())
            assertEquals(!original.saveConversations, conversationSwitch().isChecked)
            dialogSave().performClick()
        }
        await { graph.aiConfigurationSnapshot()?.model == model }
        val saved = graph.aiConfiguration.read()
        assertEquals(endpoint, saved.endpoint)
        assertEquals(key, saved.apiKey)
        assertEquals(!original.saveConversations, saved.saveConversations)
    }

    @Test fun cancellingProviderDraftClearsFieldsAndDoesNotPersistIt() = withSettings { screen ->
        val original = graph.aiConfiguration.read()
        val drafts = fillDraft()
        onMain { windows().filterIsInstance<TextView>().single {
            it.isShown && it.id == android.R.id.button2
        }.performClick() }
        await { drafts.all { it.text.isNullOrEmpty() } }
        assertEquals(original, graph.aiConfiguration.read())
        openSettings(screen)
        onMain {
            assertEquals(original.endpoint, field(R.string.ai_endpoint_hint).text.toString())
            assertEquals(original.model, field(R.string.ai_model_hint).text.toString())
            assertTrue(field(R.string.ai_key_hint).text.isNullOrEmpty())
        }
    }

    @Test fun destroyingProviderPageClearsDraftAndPreservesWindowProtection() = withSettings { screen ->
        val drafts = fillDraft()
        onMain {
            for (field in drafts) {
                assertFalse(field.isSaveEnabled)
                val params = field.rootView.layoutParams as WindowManager.LayoutParams
                assertTrue(params.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
            screen.finish()
        }
        await { screen.isDestroyed && drafts.all { it.text.isNullOrEmpty() } }
    }

    private fun fillDraft(): List<EditText> = onMain {
        listOf(field(R.string.ai_endpoint_hint), field(R.string.ai_model_hint), field(R.string.ai_key_hint))
            .onEach { it.setText("public-unsaved-fixture") }
    }

    private fun conversationSwitch() = windows().filterIsInstance<MaterialSwitch>().single {
        it.isShown && it.text == it.context.getString(R.string.ai_save_conversations)
    }

    private fun withSettings(test: (MainActivity) -> Unit) {
        val original = graph.aiConfiguration.read()
        saveConfiguration(AiConfiguration())
        var activity: MainActivity? = null
        try {
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            openSettings(activity)
            test(activity)
        } finally {
            activity?.let { screen ->
                onMain { screen.finish() }
                await { screen.isDestroyed }
            }
            saveConfiguration(original)
        }
    }

    @Test fun rejectedEndpointRetainsSavedFieldsAndKeepsNetworkSwitchesOff() {
        val original = graph.aiConfiguration.read()
        val fixture = AiConfiguration(enabled = true, endpoint = "https://provider.example/fixture",
            model = "fixture-model", apiKey = "public-fixture-key")
        saveConfiguration(fixture)
        var activity: MainActivity? = null
        try {
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val screen = activity
            openSettings(screen)
            onMain {
                field(R.string.ai_endpoint_hint).setText("http://invalid.example/fixture")
                dialogSave().performClick()
            }
            await { graph.aiConfigurationSnapshot() == null && fieldOrNull(R.string.ai_endpoint_hint) == null }
            openSettings(screen)
            onMain {
                assertEquals(fixture.endpoint, field(R.string.ai_endpoint_hint).text.toString())
                assertEquals(fixture.model, field(R.string.ai_model_hint).text.toString())
                dialogSave().performClick()
            }
            await { graph.aiConfigurationSnapshot() != null }
            val saved = checkNotNull(graph.aiConfigurationSnapshot())
            assertFalse(saved.enabled)
            assertFalse(saved.networkAllowed)
            assertEquals(fixture.endpoint, saved.endpoint)
            assertEquals(fixture.model, saved.model)
            assertEquals(fixture.apiKey, saved.apiKey)
        } finally {
            activity?.let { onMain { it.finish() } }
            saveConfiguration(original)
        }
    }

    private fun saveConfiguration(value: AiConfiguration) {
        val done = CountDownLatch(1)
        var success = false
        onMain { graph.updateAiConfiguration(value) { success = it; done.countDown() } }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertTrue(success)
    }

    private fun openSettings(activity: MainActivity) = await {
        if (fieldOrNull(R.string.ai_endpoint_hint) == null) {
            views(activity.window.decorView).filterIsInstance<TextView>().single {
                it.text.toString() == activity.getString(R.string.ai_settings)
            }.performClick()
        }
        fieldOrNull(R.string.ai_endpoint_hint) != null
    }

    private fun field(id: Int) = checkNotNull(fieldOrNull(id))
    private fun fieldOrNull(id: Int) = windows().filterIsInstance<EditText>().firstOrNull {
        it.isShown && it.hint?.toString() == it.context.getString(id)
    }
    private fun dialogSave() = windows().filterIsInstance<TextView>().single {
        it.isShown && it.id == android.R.id.button1
    }
    private fun windows() = WindowInspector.getGlobalWindowViews().flatMap(::views)
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(condition)) return
            SystemClock.sleep(50)
        }
        fail("AI settings fixture did not reach the expected state")
    }
    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}
