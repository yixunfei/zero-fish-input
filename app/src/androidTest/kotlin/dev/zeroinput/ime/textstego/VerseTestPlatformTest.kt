package dev.zeroinput.ime.textstego

import android.app.Activity
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.R
import dev.zeroinput.ime.settings.MainActivity
import dev.zeroinput.ime.settings.SettingsCategory
import dev.zeroinput.ime.settings.SettingsScreenView
import dev.zeroinput.ime.core.stego.StegoCarrierCodec
import dev.zeroinput.security.message.MessageCredential
import dev.zeroinput.security.message.MessageCredentialMode
import dev.zeroinput.security.message.NaturalWordingEnvelope
import java.io.DataInputStream
import java.io.File
import java.util.Base64
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VerseTestPlatformTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun settingsOmitsPublicFixtureAndFixtureRemainsPrivate() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val frame = activity.findViewById<ViewGroup>(android.R.id.content)
                (frame.getChildAt(0) as SettingsScreenView).navigateTo(SettingsCategory.STEGO)
                assertNull(activity.findViewById<View>(R.id.verse_test_entry))
            }
        }
        ActivityScenario.launch(VerseTestActivity::class.java).use { scenario ->
            scenario.onActivity { page ->
                assertNotEquals(0, page.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
                assertNull(page.findViewById<View>(R.id.verse_test_recovered).onCreateInputConnection(
                    android.view.inputmethod.EditorInfo(),
                ))
                assertFalse(page.packageManager.getActivityInfo(page.componentName, 0).exported)
            }
        }
    }

    @Test fun fullPageOpensHostAndLocalSamplesAndClearsOnRecreation() {
        val workspace = VerseTestWorkbench(instrumentation.targetContext.assets)
        val body = workspace.sample().toString(Charsets.UTF_8)
        ActivityScenario.launch(VerseTestActivity::class.java).use { scenario ->
            reachableButtons(scenario)
            click(scenario, R.id.verse_test_open_host)
            await(scenario) { viewText(it, R.id.verse_test_recovered) == body }
            click(scenario, R.id.verse_test_generate)
            await(scenario) { viewText(it, R.id.verse_test_carrier).isNotEmpty() &&
                viewText(it, R.id.verse_test_recovered).isEmpty() }
            click(scenario, R.id.verse_test_reopen)
            await(scenario) { viewText(it, R.id.verse_test_recovered) == body }
            scenario.recreate()
            scenario.onActivity {
                assertEquals("", viewText(it, R.id.verse_test_carrier))
                assertEquals("", viewText(it, R.id.verse_test_recovered))
                assertFalse(it.findViewById<View>(R.id.verse_test_reopen).isEnabled)
            }
        }
    }

    @Test fun androidCarrierMatchesFixtureAndCanBeCheckedByHost() {
        val context = instrumentation.targetContext
        val workspace = VerseTestWorkbench(context.assets)
        val original = workspace.sample()
        val output = workspace.seal(original)
        assertArrayEquals(original, workspace.open(output.text).second)
        assertEquals(418, output.envelopeBytes)
        assertTrue(output.passesTarget)
        if (InstrumentationRegistry.getArguments().getString("verseInteropEvidence") == "true") {
            File(context.cacheDir, "verse-public-android-carrier.txt").writeText(output.text, Charsets.UTF_8)
            instrumentation.sendStatus(2, Bundle().apply {
                putInt("verse_original_bytes", output.originalBytes)
                putInt("verse_envelope_bytes", output.envelopeBytes)
                putInt("verse_carrier_bytes", output.utf8Bytes)
                putInt("verse_carrier_units", output.text.length)
            })
        }
    }

    @Test fun wordingTrialOpensAnIndependentPublicHostCarrier() = publicWordingVector { key, original ->
        val context = instrumentation.targetContext
        val path = File(context.cacheDir, "wording-host-public.txt")
        val size = path.length()
        require(size in 1L..StegoCarrierCodec.MAX_BYTES.toLong())
        val carrier = DataInputStream(path.inputStream()).use { stream ->
            val buffer = ByteArray(size.toInt())
            stream.readFully(buffer)
            require(stream.read() == -1)
            buffer.decodeToString(throwOnInvalidSequence = true)
        }
        val frozen = VerseTestAssets(context.assets)
        val elapsed = LongArray(11) {
            val start = SystemClock.elapsedRealtimeNanos()
            val envelope = frozen.surface.decode(carrier)
            val packet = NaturalWordingEnvelope.open(frozen.digest, envelope, key)
            val result = frozen.body.decode(packet)
            (SystemClock.elapsedRealtimeNanos() - start).also {
                try { assertArrayEquals(original, result) } finally {
                    envelope.fill(0); packet.fill(0); result.fill(0)
                }
            }
        }
        reportWordingTimes("open", elapsed)
    }

    @Test fun wordingTrialProducesAPublicCarrierForTheIndependentHost() = publicWordingVector { key, original ->
        val context = instrumentation.targetContext
        val frozen = VerseTestAssets(context.assets)
        val elapsed = LongArray(11) { iteration ->
            val start = SystemClock.elapsedRealtimeNanos()
            val packet = frozen.body.encode(original)
            val envelope = NaturalWordingEnvelope.seal(frozen.digest, packet, key)
            val text = frozen.surface.encode(envelope)
            (SystemClock.elapsedRealtimeNanos() - start).also {
                assertTrue(text.toByteArray().size <= 1856 * 5)
                assertEquals(1856, original.size)
                if (iteration == 0) File(context.cacheDir, "wording-android-public.txt").writeText(text)
            }
        }
        reportWordingTimes("seal", elapsed)
    }

    private fun publicWordingVector(test: (MessageCredential, ByteArray) -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("wordingInteropEvidence") == "true")
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        val original = VerseTestWorkbench(instrumentation.targetContext.assets).sample()
        MessageCredential(MessageCredentialMode.RANDOM_KEY, encoded.toCharArray()).use { test(it, original) }
    }

    private fun reportWordingTimes(operation: String, nanoseconds: LongArray) {
        val warm = nanoseconds.drop(1).sorted()
        instrumentation.sendStatus(2, Bundle().apply {
            putString("wording_operation", operation)
            putLong("cold_ns", nanoseconds[0])
            putLong("warm_p50_ns", (warm[4] + warm[5]) / 2)
            putLong("warm_p95_ns", warm.last())
        })
    }

    private fun reachableButtons(scenario: ActivityScenario<VerseTestActivity>) {
        for (id in listOf(R.id.verse_test_generate, R.id.verse_test_reopen, R.id.verse_test_open_host)) {
            scenario.onActivity { activity ->
                val view = activity.findViewById<View>(id)
                view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val view = activity.findViewById<View>(id)
                val visible = Rect()
                assertTrue(view.getGlobalVisibleRect(visible))
                assertTrue(visible.height() >= view.height - 2)
                assertTrue(view.height >= (48 * view.resources.displayMetrics.density).toInt())
            }
        }
    }

    private fun click(scenario: ActivityScenario<VerseTestActivity>, id: Int) {
        scenario.onActivity {
            val view = it.findViewById<View>(id)
            assertTrue(view.isEnabled)
            assertTrue(view.performClick())
        }
    }

    private fun viewText(activity: Activity, id: Int): String = activity.findViewById<TextView>(id).text.toString()

    private fun await(scenario: ActivityScenario<VerseTestActivity>, predicate: (VerseTestActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var complete = false
            scenario.onActivity { complete = predicate(it) }
            if (complete) return
            SystemClock.sleep(25)
        }
        fail("Public verse operation did not finish")
    }
}
