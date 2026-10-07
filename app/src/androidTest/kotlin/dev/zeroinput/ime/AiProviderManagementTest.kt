package dev.zeroinput.ime

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.AdapterView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.settings.AiProviderSettingsDialog
import dev.zeroinput.ime.settings.MainActivity
import dev.zeroinput.ime.ai.AiProbeState
import dev.zeroinput.ai.api.AiNetworkFailure
import dev.zeroinput.ai.api.AiProviderError
import dev.zeroinput.userdata.AiConfiguration
import dev.zeroinput.userdata.AiProviderProfile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class AiProviderManagementTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun modelDetectionShowsFailureRetriesCurrentModelAndRejectsDismissedCallbacks() {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val profile = AiProviderProfile("fixture", "Fixture", "https://provider.example/chat/completions", "public-key",
            listOf("text", "vision"), "text")
        var value = AiConfiguration(enabled = true, networkAllowed = true, providers = listOf(profile), selectedProviderId = profile.id)
        val callbacks = mutableListOf<(AiProbeState) -> Unit>()
        var closes = 0
        val editor = onMain { AiProviderSettingsDialog(activity, { value }, { next, done -> value = next; done(true) },
            { callback -> callbacks += callback; callback(AiProbeState.Running); AutoCloseable { closes++ } }) }
        try {
            onMain { editor.show() }
            tap { it.text.toString() == context.getString(R.string.ai_test_model) && it is com.google.android.material.button.MaterialButton }
            onMain {
                assertTrue(topTexts().any { it.text == context.getString(R.string.ai_test_running) })
                assertFalse(topTexts().first { it.id == android.R.id.button1 }.isEnabled)
                callbacks.last()(AiProbeState.Failed(AiProviderError.Network("Public fixture",
                    reason = AiNetworkFailure.AUTHENTICATION)))
                assertTrue(topTexts().any { it.text == context.getString(dev.zeroinput.ime.ui.R.string.ai_authentication_failed) })
                value = value.copy(providers = listOf(profile.copy(selectedModel = "vision")))
            }
            tap { it.text.toString() == context.getString(R.string.ai_test_retry) }
            onMain {
                assertEquals(2, callbacks.size)
                assertEquals(1, closes)
                assertTrue(topTexts().any { it.text.toString().contains("Fixture · vision") })
                callbacks.first()(AiProbeState.Available)
                assertTrue(topTexts().any { it.text == context.getString(R.string.ai_test_running) })
                editor.cancelTests()
                assertTrue(topTexts().any { it.text == context.getString(R.string.ai_test_cancelled) })
                assertTrue(topTexts().first { it.id == android.R.id.button1 }.isEnabled)
                callbacks.last()(AiProbeState.Available)
                assertTrue(topTexts().any { it.text == context.getString(R.string.ai_test_cancelled) })
            }
            tap { it.text.toString() == context.getString(R.string.ai_test_retry) }
            onMain {
                assertEquals(3, callbacks.size)
                callbacks.last()(AiProbeState.Available)
                assertTrue(topTexts().any { it.text == context.getString(R.string.ai_test_available) })
            }
            tap { it.id == android.R.id.button2 }
            onMain {
                assertEquals(3, closes)
                callbacks.last()(AiProbeState.Available)
                assertFalse(topTexts().any { it.text == context.getString(R.string.ai_test_available) })
            }
        } finally { onMain { editor.dismiss(); activity.finish() } }
    }

    @Test fun modelSelectionSwitchesProviderAndDeletionPreservesOtherProfiles() {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val first = AiProviderProfile("one", "First", "https://first.example/chat/completions", "first-key",
            listOf("text", "vision"), "text", imageModels = setOf("vision"))
        val second = first.copy(id = "two", name = "Second", apiKey = "second-key")
        var value = AiConfiguration(providers = listOf(first, second), selectedProviderId = second.id)
        val editor = onMain { AiProviderSettingsDialog(activity, { value }, { next, done -> value = next; done(true) }) }
        try {
            onMain { editor.show() }
            tap { it.text.toString().contains("First · text") }
            tap { it.text.toString() == context.getString(R.string.ai_choose_model) }
            tap { it.text.toString() == "vision" }
            onMain {
                assertEquals("first-key", value.activeKey())
                assertEquals("vision", value.activeModel())
                assertTrue(value.activeProvider()?.supportsImages == true)
                editor.show()
            }
            tap { it.text.toString().contains("First · vision") }
            tap { it.text.toString() == context.getString(R.string.delete) }
            tap { it.id == android.R.id.button1 && it.text.toString() == context.getString(R.string.delete) }
            onMain {
                assertEquals(listOf(second), value.providers)
                assertEquals("second-key", value.activeKey())
                editor.show()
            }
            tap { it.text.toString().contains("Second · text") }
            tap { it.text.toString() == context.getString(R.string.delete) }
            tap { it.id == android.R.id.button1 && it.text.toString() == context.getString(R.string.delete) }
            onMain { assertTrue(value.providers.isEmpty()); assertTrue(value.activeKey().isEmpty()) }
        } finally { onMain { editor.dismiss(); activity.finish() } }
    }

    @Test fun discoveredModelsRequireExplicitSelectionAndSaveAndRetainManualModels() {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val profile = AiProviderProfile("fixture", "Fixture", "https://provider.example/v1", "public-key",
            listOf("manual"), "manual")
        var value = AiConfiguration(enabled = true, networkAllowed = true, providers = listOf(profile), selectedProviderId = profile.id)
        var callback: (dev.zeroinput.ai.api.AiModelCatalogEvent) -> Unit = {}
        var requested: AiConfiguration? = null
        val editor = onMain { AiProviderSettingsDialog(activity, { value }, { next, done -> value = next; done(true) },
            startDiscovery = { draft, listener ->
                requested = draft; callback = listener
                listener(dev.zeroinput.ai.api.AiModelCatalogEvent.Started)
                AutoCloseable {}
            }) }
        try {
            onMain { editor.show() }
            tap { it.text.toString().contains("Fixture · manual") }
            tap { it.text.toString() == context.getString(R.string.ai_edit_provider) }
            tap { it.text.toString() == context.getString(R.string.ai_fetch_models) }
            onMain {
                assertEquals(profile.endpoint, requested?.activeEndpoint())
                assertEquals(profile.apiKey, requested?.activeKey())
                callback(dev.zeroinput.ai.api.AiModelCatalogEvent.Completed(listOf("remote-one", "remote-two")))
                assertEquals(listOf("manual"), value.activeProvider()?.models)
            }
            tap { it.text.toString() == "remote-one" }
            tap { it.text.toString() == "remote-two" }
            tap { it.text.toString() == context.getString(R.string.ai_models_apply) }
            onMain {
                assertEquals(listOf("manual"), value.activeProvider()?.models)
                val field = topTexts().filterIsInstance<android.widget.EditText>().single {
                    it.hint == context.getString(R.string.ai_models_hint) }
                assertEquals("manual, remote-one, remote-two", field.text.toString())
                val params = field.rootView.layoutParams as android.view.WindowManager.LayoutParams
                assertTrue(params.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
                capturePublicFixture(field.rootView)
            }
            tap { it.id == android.R.id.button1 }
            assertEquals(listOf("manual", "remote-one", "remote-two"), value.activeProvider()?.models)
            assertEquals("manual", value.activeModel())
            assertTrue(value.activeProvider()?.imageModels.isNullOrEmpty())
        } finally { onMain { editor.dismiss(); activity.finish() } }
    }

    @Test fun editingEndpointOrBackgroundingRevokesPendingModelList() {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val profile = AiProviderProfile("fixture", "Fixture", "https://provider.example/v1", "public-key", listOf("manual"), "manual")
        val value = AiConfiguration(enabled = true, networkAllowed = true, providers = listOf(profile), selectedProviderId = profile.id)
        val callbacks = mutableListOf<(dev.zeroinput.ai.api.AiModelCatalogEvent) -> Unit>()
        var cancelled = 0
        val editor = onMain { AiProviderSettingsDialog(activity, { value }, { _, done -> done(true) },
            startDiscovery = { _, listener -> callbacks += listener; AutoCloseable { cancelled++ } }) }
        try {
            onMain { editor.show() }
            tap { it.text.toString().contains("Fixture · manual") }
            tap { it.text.toString() == context.getString(R.string.ai_edit_provider) }
            tap { it.text.toString() == context.getString(R.string.ai_fetch_models) }
            onMain {
                topTexts().filterIsInstance<android.widget.EditText>().single {
                    it.hint == context.getString(R.string.ai_endpoint_hint) }.setText("https://replacement.example/v1")
                callbacks.first()(dev.zeroinput.ai.api.AiModelCatalogEvent.Completed(listOf("stale")))
                assertFalse(topTexts().any { it.text.toString() == "stale" })
                assertEquals(1, cancelled)
            }
            tap { it.text.toString() == context.getString(R.string.ai_fetch_models) }
            onMain {
                editor.cancelTests()
                callbacks.last()(dev.zeroinput.ai.api.AiModelCatalogEvent.Completed(listOf("late")))
                assertFalse(topTexts().any { it.text.toString() == "late" })
                assertEquals(2, cancelled)
            }
        } finally { onMain { editor.dismiss(); activity.finish() } }
    }

    private fun capturePublicFixture(root: View) {
        // Only this test's fixed public profile is drawn; no real configuration is captured.
        val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            root.draw(android.graphics.Canvas(bitmap))
            val config = root.resources.configuration
            val name = "provider-${config.orientation}-${config.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK}.png"
            val file = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), name)
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }

    private fun tap(predicate: (TextView) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            val clicked = onMain {
                val target = WindowInspector.getGlobalWindowViews().lastOrNull()?.let(::views)
                    ?.filterIsInstance<TextView>()?.firstOrNull { it.isShown && predicate(it) }
                if (target == null) {
                    // Short landscape lists recycle off-screen rows; scroll as a user would.
                    WindowInspector.getGlobalWindowViews().lastOrNull()?.let(::views)
                        ?.filterIsInstance<android.widget.ListView>()?.forEach { list ->
                            val adapter = list.adapter
                            val position = (0 until adapter.count).firstOrNull { index ->
                                predicate(TextView(list.context).apply { text = adapter.getItem(index)?.toString() })
                            }
                            if (position != null) list.setSelection(position)
                        }
                    false
                } else {
                    val parent = target.parent as? AdapterView<*>
                    if (parent == null) target.performClick() else {
                        val position = parent.getPositionForView(target)
                        parent.performItemClick(target, position, parent.getItemIdAtPosition(position))
                    }
                    true
                }
            }
            if (clicked) { instrumentation.waitForIdleSync(); return }
            SystemClock.sleep(50)
        }
        fail("Provider action not available")
    }
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun topTexts() = WindowInspector.getGlobalWindowViews().last().let(::views).filterIsInstance<TextView>()
    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}
