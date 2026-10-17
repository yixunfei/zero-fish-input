package dev.zeroinput.ime

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.settings.AiProviderSettingsDialog
import dev.zeroinput.ime.settings.MainActivity
import dev.zeroinput.userdata.AiConfiguration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class AiProviderSetupTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun protocolSelectionPersistsExactRoutesAndStreamingPreference() {
        for (protocol in dev.zeroinput.ai.api.AiProtocol.entries) withEditor { editor, saved ->
            onMain { editor.show() }
            tap(R.string.ai_add_provider)
            onMain {
                field(R.string.ai_provider_name).setText("Public fixture")
                field(R.string.ai_endpoint_hint).setText("https://example.test/proxy/v1")
                field(R.string.ai_key_hint).setText("public-key")
                field(R.string.ai_models_hint).setText("model")
                val root = WindowInspector.getGlobalWindowViews().last()
                views(root).filterIsInstance<android.widget.AutoCompleteTextView>().single {
                    it.contentDescription == it.context.getString(R.string.ai_protocol_label)
                }.let { choice ->
                    choice.setText(choice.adapter.getItem(protocol.ordinal).toString(), false)
                    choice.onItemClickListener?.onItemClick(null, null, protocol.ordinal, protocol.ordinal.toLong())
                }
                texts().filterIsInstance<android.widget.CompoundButton>().single {
                    it.text == it.context.getString(R.string.ai_protocol_streaming)
                }.isChecked = false
            }
            instrumentation.waitForIdleSync()
            onMain {
                assertTrue(texts().any { it.text.contains("https://example.test/proxy/v1/${protocol.route}") })
            }
            tap(R.string.save)
            onMain {
                val profile = checkNotNull(saved.single().activeProvider())
                assertEquals(protocol, profile.protocol)
                assertFalse(profile.streaming)
                assertEquals("https://example.test/proxy/v1", profile.endpoint)
            }
        }
    }

    @Test fun mismatchedFullRouteFailsBeforeSaving() = withEditor { editor, saved ->
        onMain { editor.show() }
        tap(R.string.ai_add_provider)
        onMain {
            field(R.string.ai_provider_name).setText("Public fixture")
            field(R.string.ai_endpoint_hint).setText("https://example.test/v1/responses")
            field(R.string.ai_key_hint).setText("public-key")
            field(R.string.ai_models_hint).setText("model")
        }
        tap(R.string.save)
        onMain { assertTrue(saved.isEmpty()); assertNotNull(fieldError(R.string.ai_endpoint_hint)) }
    }

    @Test fun invalidDraftNeverReachesPersistenceOrRevokesWorkingNetworkSettings() = withEditor { editor, saved ->
        onMain { editor.show() }
        tap(R.string.ai_add_provider)
        onMain {
            field(R.string.ai_provider_name).setText("Public fixture")
            field(R.string.ai_endpoint_hint).setText("http://invalid.example/v1")
            field(R.string.ai_key_hint).setText("public-key")
            field(R.string.ai_models_hint).setText("model")
        }
        tap(R.string.save)
        onMain {
            assertEquals("Invalid editor input must not reach storage", 0, saved.size)
            assertNotNull(fieldError(R.string.ai_endpoint_hint))
        }
    }

    @Test fun blankCredentialMustBeCorrectedBeforeSavingANewProvider() = withEditor { editor, saved ->
        onMain { editor.show() }
        tap(R.string.ai_add_provider)
        onMain {
            field(R.string.ai_provider_name).setText("Public fixture")
            field(R.string.ai_models_hint).setText("model")
        }
        tap(R.string.save)
        onMain {
            assertTrue("A provider with no usable credential must not be saved", saved.isEmpty())
            assertNotNull(fieldError(R.string.ai_key_hint))
        }
    }

    private fun withEditor(test: (AiProviderSettingsDialog, List<AiConfiguration>) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val saved = mutableListOf<AiConfiguration>()
        val editor = onMain { AiProviderSettingsDialog(activity,
            { AiConfiguration(enabled = true, networkAllowed = true) },
            { value, done -> saved += value; done(false) }) }
        try { test(editor, saved) } finally { onMain { editor.dismiss(); activity.finish() } }
    }

    private fun field(id: Int) = texts().filterIsInstance<EditText>().single { it.hint == it.context.getString(id) }
    private fun fieldError(id: Int) = generateSequence(field(id).parent) { it.parent }
        .filterIsInstance<com.google.android.material.textfield.TextInputLayout>().first().error
    private fun texts() = WindowInspector.getGlobalWindowViews().last().let(::views).filterIsInstance<TextView>()
    private fun views(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
    private fun tap(id: Int) {
        val end = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < end) {
            if (onMain { texts().firstOrNull { it.isShown && it.isEnabled && it.text == it.context.getString(id) }
                    ?.let { it.performClick(); true } == true }) {
                instrumentation.waitForIdleSync()
                return
            }
            SystemClock.sleep(50)
        }
        fail("Setup action unavailable")
    }
    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return checkNotNull(result).getOrThrow()
    }
}
