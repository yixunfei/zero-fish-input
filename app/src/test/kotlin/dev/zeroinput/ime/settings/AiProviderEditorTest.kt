package dev.zeroinput.ime.settings

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.google.android.material.textfield.TextInputLayout
import dev.zeroinput.ai.api.AiProtocol
import androidx.appcompat.app.AlertDialog
import dev.zeroinput.ai.api.AiModelCatalogEvent
import dev.zeroinput.ai.api.AiNetworkFailure
import dev.zeroinput.ai.api.AiNetworkOperation
import dev.zeroinput.ai.api.AiProviderError
import dev.zeroinput.ime.R
import dev.zeroinput.userdata.AiConfiguration
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AiProviderEditorTest {
    @Test fun liveEditorFetchesSelectsAndSavesOnlyAfterExplicitActions() {
        val key = System.getenv("ZEROINPUT_AI_TEST_KEY")
        assumeTrue("Explicit temporary test credential required", !key.isNullOrBlank())
        val endpoint = checkNotNull(System.getenv("ZEROINPUT_AI_TEST_ENDPOINT"))
        val model = checkNotNull(System.getenv("ZEROINPUT_AI_TEST_MODEL"))
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val cancellation = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            Fixture(startDiscovery = { config, render ->
                val discovery = dev.zeroinput.ime.ai.AiModelDiscovery({ snapshot ->
                    dev.zeroinput.ime.ai.OpenAiCompatibleProvider({ snapshot }, worker, cancellation)
                }, { true }, { android.os.Handler(android.os.Looper.getMainLooper()).post(it) }, render)
                discovery.start(config)
                discovery
            }).use { f ->
                f.field(R.string.ai_provider_name).setText("Public live fixture")
                f.fillConnection(endpoint, checkNotNull(key))
                f.tap(R.string.ai_fetch_models)
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(35)
                while (f.dialogs.size == 1 && System.nanoTime() < deadline) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    Thread.sleep(20)
                }
                assertEquals("The actual editor must receive a successful model catalog", 2, f.dialogs.size)
                assertTrue(f.saved.isEmpty())
                val list = f.dialogs.last().listView
                val index = (0 until list.adapter.count).firstOrNull { list.adapter.getItem(it) == model }
                assertNotNull("Requested live model must be offered for selection", index)
                val position = checkNotNull(index)
                list.performItemClick(list.adapter.getView(position, null, list), position, position.toLong())
                f.dialogs.last().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertEquals(model, f.field(R.string.ai_models_hint).text.toString())
                assertTrue("Selecting models must not save the draft", f.saved.isEmpty())
                f.dialogs.first().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                val saved = f.saved.single()
                assertEquals(endpoint, saved.activeEndpoint())
                assertEquals(model, saved.activeModel())
                assertTrue("Saved credential must match the explicitly entered credential", saved.activeKey() == key)
            }
        } finally { worker.shutdownNow(); cancellation.shutdownNow() }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w360dp-h720dp-port-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun smallChinesePortraitKeepsOutlinedFieldsAndActionsWithinTheWindow() = renderPublicFixture("portrait-light", 360, 720)

    @Test
    @Config(qualifiers = "en-w640dp-h320dp-land-night-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun shortEnglishLandscapeScrollsWithoutMovingDialogActionsOutsideTheWindow() = renderPublicFixture("landscape-dark", 640, 320)

    private fun renderPublicFixture(name: String, width: Int, height: Int) = Fixture().use { f ->
        f.field(R.string.ai_provider_name).setText("Public fixture")
        f.fillConnection("https://example.test/v1", "public-fixture-key")
        f.field(R.string.ai_models_hint).setText("public-model-one, public-model-two-with-a-long-name")
        f.choose(R.string.ai_choose_model, 1)
        val selected = f.choice(R.string.ai_choose_model).input
        assertEquals(2, selected.maxLines)
        assertEquals(android.text.TextUtils.TruncateAt.END, selected.ellipsize)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
        val root = checkNotNull(f.dialogs.first().window).decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        for (field in f.views().filterIsInstance<AiProviderTextField>()) {
            assertTrue("Field must fit the form width", field.width > 0 && field.width <= width)
            assertEquals(TextInputLayout.BOX_BACKGROUND_OUTLINE, field.boxBackgroundMode)
        }
        val output = java.io.File("build/provider-form-validation").apply { mkdirs() }
        fun capture(suffix: String) {
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                root.draw(android.graphics.Canvas(bitmap))
                java.io.File(output, "$name-$suffix.png").outputStream().use {
                    assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { bitmap.recycle() }
        }
        capture("top")
        val save = f.dialogs.first().getButton(AlertDialog.BUTTON_POSITIVE)
        val rect = android.graphics.Rect(0, 0, save.width, save.height)
        (root as ViewGroup).offsetDescendantRectToMyCoords(save, rect)
        assertTrue("Save remains in the window: $rect within $width x $height", rect.top >= 0 && rect.bottom <= height)
        val scroll = generateSequence(f.field(R.string.ai_provider_name).parent) { it.parent }
            .filterIsInstance<android.widget.ScrollView>().first()
        scroll.scrollTo(0, scroll.getChildAt(0).height - scroll.height)
        capture("bottom")
    }

    @Test fun newProviderHasNoImplicitDestinationOrEmptyModelDropdown() = Fixture().use { f ->
        assertTrue(f.field(R.string.ai_endpoint_hint).text.isEmpty())
        assertFalse(f.views().any { it.isShown && it.contentDescription == f.text(R.string.ai_choose_model) })
        assertFalse(f.views().filterIsInstance<TextView>().any {
            it.isShown && it.text.toString().contains(f.text(R.string.ai_first_model))
        })
    }

    @Test fun materialFieldsKeepLabelsAndValidationOutsideTheEditableText() = Fixture().use { f ->
        for (id in listOf(R.string.ai_provider_name, R.string.ai_endpoint_hint, R.string.ai_key_hint, R.string.ai_models_hint)) {
            assertEquals(TextInputLayout.BOX_BACKGROUND_OUTLINE, f.layout(id).boxBackgroundMode)
            assertTrue("The edit control must use a Material outline, without its old underline",
                f.field(id).background is com.google.android.material.shape.MaterialShapeDrawable)
            assertFalse(f.field(id).isSaveEnabled)
        }
        assertEquals(f.text(R.string.ai_key_new), f.layout(R.string.ai_key_hint).helperText)
        f.fillConnection("https://example.test/v1\u200b", "public-key")
        f.tap(R.string.ai_fetch_models)
        assertTrue(f.requests.isEmpty())
        assertNotNull(f.layout(R.string.ai_endpoint_hint).error)
        f.fillConnection("https://example.test/v1\u00a0", "public-key")
        assertNull(f.layout(R.string.ai_endpoint_hint).error)
        f.tap(R.string.ai_fetch_models)
        assertEquals("https://example.test/v1", f.requests.single().activeEndpoint())
    }

    @Test fun removingModelsHidesTheSelectorAndCapabilitiesAgain() = Fixture().use { f ->
        f.field(R.string.ai_models_hint).setText("one, two")
        assertEquals(View.VISIBLE, f.choice(R.string.ai_choose_model).visibility)
        f.choose(R.string.ai_choose_model, 1)
        assertEquals("two", f.choice(R.string.ai_choose_model).input.text.toString())
        f.field(R.string.ai_models_hint).setText("")
        assertEquals(View.GONE, f.choice(R.string.ai_choose_model).visibility)
    }

    @Test fun changingProtocolCancelsTheOldCatalogAndNewDraftUsesTheSelectedProtocol() = Fixture().use { f ->
        f.fillConnection("https://example.test/v1", "public-key")
        f.tap(R.string.ai_fetch_models)
        val old = f.callbacks.single()
        f.choose(R.string.ai_protocol_label, AiProtocol.RESPONSES.ordinal)
        old(AiModelCatalogEvent.Completed(listOf("stale")))
        assertEquals(1, f.dialogs.size)
        f.tap(R.string.ai_fetch_models)
        assertEquals(AiProtocol.RESPONSES, f.requests.last().activeProvider()?.protocol)
        assertTrue(f.views().filterIsInstance<TextView>().any { it.text.contains("https://example.test/v1/responses") })
    }

    @Test fun catalog404CanRetryEditedDraftWithoutSavingOrReusingOldCredentials() = Fixture().use { f ->
        f.fillConnection("https://example.test/v1", "public-first-key")
        f.tap(R.string.ai_fetch_models)
        assertEquals("https://example.test/v1", f.requests.single().activeEndpoint())
        assertTrue(checkNotNull(f.requests.single().activeProvider()).models.isEmpty())
        val first = f.callbacks.single()
        first(AiModelCatalogEvent.Failed(AiProviderError.Network("Public fixture",
            reason = AiNetworkFailure.MODEL_OR_ENDPOINT, statusCode = 404, operation = AiNetworkOperation.MODELS)))
        assertTrue(f.views().filterIsInstance<TextView>().any { it.text.contains("HTTP 404") })
        f.fillConnection("https://example.test/proxy/v1", "public-second-key")
        f.tap(R.string.ai_fetch_models)
        assertEquals("public-second-key", f.requests.last().activeKey())
        assertEquals("https://example.test/proxy/v1", f.requests.last().activeEndpoint())
        first(AiModelCatalogEvent.Completed(listOf("stale")))
        assertEquals(1, f.dialogs.size)
        f.callbacks.last()(AiModelCatalogEvent.Completed(listOf("remote-one", "remote-two")))
        val choices = f.dialogs.last().listView
        choices.performItemClick(choices.adapter.getView(0, null, choices), 0, 0)
        f.dialogs.last().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals("remote-one", f.field(R.string.ai_models_hint).text.toString())
        assertTrue(f.saved.isEmpty())
    }

    private class Fixture(
        private val startDiscovery: ((AiConfiguration, (AiModelCatalogEvent) -> Unit) -> AutoCloseable)? = null,
    ) : AutoCloseable {
        private val activity = Robolectric.buildActivity(Activity::class.java).setup().get().apply {
            setTheme(R.style.Theme_ZeroInput)
        }
        val dialogs = mutableListOf<AlertDialog>()
        val requests = mutableListOf<AiConfiguration>()
        val callbacks = mutableListOf<(AiModelCatalogEvent) -> Unit>()
        val saved = mutableListOf<AiConfiguration>()
        private val editor = AiProviderEditor(activity, null,
            { AiConfiguration(enabled = true, networkAllowed = true) },
            { value, done -> saved += value; done(true) },
            { value, callback ->
                requests += value; callbacks += callback; callback(AiModelCatalogEvent.Started)
                startDiscovery?.invoke(value, callback) ?: AutoCloseable {}
            }, {}, {}, { dialog, clear ->
                dialogs += dialog
                dialog.setOnDismissListener { clear() }
                dialog.show()
                dialog
            }, {})
        init {
            editor.show()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        fun text(id: Int) = activity.getString(id)
        fun views(): List<View> = descendants(checkNotNull(dialogs.first().window).decorView)
        fun field(id: Int) = views().filterIsInstance<EditText>().single { it.hint == text(id) }
        fun layout(id: Int) = views().filterIsInstance<AiProviderTextField>().single { it.hint == text(id) }
        fun choice(description: Int) = views().filterIsInstance<AiProviderChoiceField>().single {
            it.input.contentDescription == text(description)
        }
        fun choose(description: Int, index: Int) {
            val input = choice(description).input
            input.setText(input.adapter.getItem(index).toString(), false)
            input.onItemClickListener?.onItemClick(null, null, index, index.toLong())
        }
        fun tap(id: Int) = views().filterIsInstance<TextView>().single { it.text == text(id) }.performClick()
        fun fillConnection(endpoint: String, key: String) {
            field(R.string.ai_endpoint_hint).setText(endpoint)
            field(R.string.ai_key_hint).setText(key)
        }
        override fun close() { dialogs.toList().forEach { it.dismiss() }; activity.finish() }
        private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    }
}
