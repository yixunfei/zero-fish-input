package dev.zeroinput.ime.textstego

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.button.MaterialButton
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.clipboardguard.ClipboardDeviceTestSupport as Device
import dev.zeroinput.ime.core.stego.StegoCarrierCodec
import dev.zeroinput.ime.core.stego.StegoTransport
import dev.zeroinput.ime.testing.InputFixtureActivity
import dev.zeroinput.ime.ui.ZeroInputView
import dev.zeroinput.security.message.*
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StegoResourcePlatformTest {
    @Test fun standardPasswordSixLayerWorkLeavesMainLooperResponsive() {
        val runtime = Runtime.getRuntime()
        assertTrue(StegoOptions.available(MessageKdf.STANDARD))
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        var elapsed = 0L
        var baseline = 0L
        var peak = 0L
        val started = CountDownLatch(1)
        StegoCryptoWorker.executor.execute {
            try {
                assertNotSame(Looper.getMainLooper(), Looper.myLooper())
                runtime.gc()
                baseline = runtime.totalMemory() - runtime.freeMemory()
                val begin = SystemClock.elapsedRealtime()
                started.countDown()
                MessageCredential(MessageCredentialMode.PASSPHRASE, "public fixture passphrase".toCharArray()).use { key ->
                    val envelope = MessageEnvelope().seal("public cover".toCharArray(), CharArray(2048) { 'x' }, key,
                        MessageCipher.entries.map(::MessageStage))
                    assertEquals(2372, envelope.size)
                    envelope.fill(0)
                }
                elapsed = SystemClock.elapsedRealtime() - begin
            } catch (error: Throwable) { failure.set(error) }
            finally { started.countDown(); completed.countDown() }
        }
        assertTrue(started.await(3, TimeUnit.SECONDS))
        val handler = Handler(Looper.getMainLooper())
        var maxMainDelay = 0L
        var pulses = 0
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (completed.count > 0 && SystemClock.elapsedRealtime() < deadline) {
            peak = maxOf(peak, runtime.totalMemory() - runtime.freeMemory())
            val pulse = CountDownLatch(1)
            val time = SystemClock.elapsedRealtime()
            assertTrue(handler.post { pulse.countDown() })
            assertTrue("Public crypto fixture blocked the main looper", pulse.await(2, TimeUnit.SECONDS))
            maxMainDelay = maxOf(maxMainDelay, SystemClock.elapsedRealtime() - time)
            pulses++
            completed.await(10, TimeUnit.MILLISECONDS)
        }
        assertTrue(completed.await(1, TimeUnit.SECONDS))
        failure.get()?.let { throw it }
        assertTrue(elapsed > 0 && pulses > 0)
        Device.instrumentation.sendStatus(2, Bundle().apply {
            putLong("stego_standard_ms", elapsed); putLong("stego_java_baseline_bytes", baseline)
            putLong("stego_java_peak_bytes", peak); putLong("stego_main_max_delay_ms", maxMainDelay)
            putInt("stego_main_pulses", pulses); putLong("stego_max_heap_bytes", runtime.maxMemory())
        })
    }

    @Test fun protectedPanelCommandsRemainScrollableWithUsableTouchTargets() = withStableDisplay {
        val graph = (Device.context.applicationContext as ZeroInputApplication).graph
        val shared = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        val carrier = MessageCredential(MessageCredentialMode.RANDOM_KEY, shared.toCharArray()).use {
            StegoCarrierCodec.encode("public cover", MessageEnvelope().seal("public cover".toCharArray(),
                "public fixture".toCharArray(), it, kdf = MessageKdf.NONE), StegoTransport.VISIBLE_BASE64)
        }
        var token = ""
        Device.onMain { token = graph.stegoHandoff.put(StegoHandoff.Request(StegoHandoff.Kind.CARRIER, carrier.toCharArray())) }
        val activity = Device.instrumentation.startActivitySync(Intent(Device.context, StegoSourceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(StegoSourceActivity.EXTRA_TOKEN, token)) as StegoSourceActivity
        try {
            Device.instrumentation.waitForIdleSync()
            var buttons = emptyList<MaterialButton>()
            Device.onMain {
                val keyboard = nodes(activity.window.decorView).filterIsInstance<ZeroInputView>().single()
                assertTrue(keyboard.isShown && keyboard.height > 0)
                buttons = nodes(keyboard.textStegoPanel).filterIsInstance<MaterialButton>().filter { it.visibility == View.VISIBLE }
                assertTrue(buttons.size >= 8)
            }
            for (button in buttons) {
                Device.onMain { button.requestRectangleOnScreen(Rect(0, 0, button.width, button.height), true) }
                Device.instrumentation.waitForIdleSync()
                Device.onMain {
                    val density = button.resources.displayMetrics.density
                    assertTrue("Public command touch target is too short", button.height >= (48 * density).toInt())
                    assertTrue("Public command touch target is too narrow", button.width >= (48 * density).toInt())
                    val visible = Rect()
                    assertTrue(button.getGlobalVisibleRect(visible))
                    assertTrue("Public command ${button.text} cannot be fully scrolled into view: ${button.width}x${button.height}, visible=$visible",
                        visible.height() >= button.height - 2)
                    assertTrue(visible.width() >= button.width - 2)
                }
            }
            Device.onMain {
                val configuration = activity.resources.configuration
                Device.instrumentation.sendStatus(2, Bundle().apply {
                    putInt("stego_layout_width_dp", configuration.screenWidthDp)
                    putInt("stego_layout_height_dp", configuration.screenHeightDp)
                    putInt("stego_layout_orientation", configuration.orientation)
                    putInt("stego_layout_night", configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    putInt("stego_layout_commands", buttons.size)
                })
            }
        } finally { Device.onMain { activity.finish(); graph.stegoHandoff.close() } }
    }

    private fun withStableDisplay(action: () -> Unit) {
        // The launcher is portrait-only. Let a public host settle the requested rotation
        // before opening a source whose deliberate recreation policy discards secrets.
        ActivityScenario.launch<InputFixtureActivity>(Intent(Device.context, InputFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { host ->
            Device.await("Public display must settle before opening the protected source") {
                var ready = false
                host.onActivity { ready = it.hasWindowFocus() && it.editor.height > 0 }
                ready
            }
            action()
        }
    }

    private fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
}
