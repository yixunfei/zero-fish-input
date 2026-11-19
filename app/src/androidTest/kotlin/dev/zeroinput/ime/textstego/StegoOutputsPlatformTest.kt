package dev.zeroinput.ime.textstego

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.clipboardguard.ClipboardDeviceTestSupport as Device
import dev.zeroinput.ime.core.stego.StegoCarrierCodec
import dev.zeroinput.ime.testing.KeyboardPreviewFixtureActivity
import dev.zeroinput.ime.ui.*
import dev.zeroinput.security.message.*
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StegoOutputsPlatformTest {
    private val graph get() = (Device.context.applicationContext as ZeroInputApplication).graph
    private val cover = "public output cover"
    private val secret = "public output secret"
    private val passphrase = "public output passphrase"

    @Test fun automaticInsertionConsumesARejectedCommitAndCannotRetry() = fixture { view, _ ->
        var writes = 0
        val notices = mutableListOf<Int>()
        val workspace = StegoWorkspace({ graph }, Handler(Looper.getMainLooper()), view, { true },
            { { _: String -> writes++; false } }, { notices += it }, {}, {})
        var stale: (StegoAction) -> Unit = {}
        try {
            Device.onMain {
                view.openTextStego(); workspace.open(InputLanguage.ENGLISH, ChineseInputOptions())
                workspace.type(KeyboardAction.LiteralText(cover))
                view.textStegoPanel.onAction(StegoAction.SECRET)
                workspace.type(KeyboardAction.LiteralText(secret))
                view.textStegoPanel.onAction(StegoAction.CREDENTIAL)
                workspace.type(KeyboardAction.LiteralText(passphrase))
                stale = view.textStegoPanel.onAction
                stale(StegoAction.CONFIRM)
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
            var completed = false
            while (!completed && android.os.SystemClock.elapsedRealtime() < deadline) {
                Device.onMain { completed = !view.isTextStegoOpen }
                if (!completed) android.os.SystemClock.sleep(25)
            }
            assertTrue("Public automatic insertion did not settle", completed)
            Device.onMain {
                stale(StegoAction.CONFIRM)
                assertEquals(1, writes)
                assertTrue(notices.contains(dev.zeroinput.ime.R.string.stego_commit_failed))
                assertFalse(workspace.current())
            }
        } finally { Device.onMain { workspace.close() } }
    }

    @Test fun everyPreparedOutputTransfersExactlyOnceWithoutCommittingToAnEditor() = fixture { view, host ->
        for (action in listOf(StegoAction.SAVE, StegoAction.SAVE_COVER, StegoAction.SAVE_CIPHERTEXT, StegoAction.SAVE_CARRIER)) {
            var saved: CharArray? = null
            var writes = 0
            val workspace = StegoWorkspace({ graph }, Handler(Looper.getMainLooper()), view, { true },
                { { _: String -> writes++; true } }, {}, {}, { assertNull(saved); saved = it })
            try {
                Device.onMain {
                    view.openTextStego(); workspace.open(InputLanguage.ENGLISH, ChineseInputOptions())
                    workspace.type(KeyboardAction.LiteralText(cover))
                    view.textStegoPanel.onAction(StegoAction.NEXT)
                    workspace.type(KeyboardAction.LiteralText(secret))
                    view.textStegoPanel.onAction(StegoAction.NEXT)
                    workspace.type(KeyboardAction.LiteralText(passphrase))
                    view.textStegoPanel.onAction(StegoAction.PREPARE)
                }
                awaitLabel(view, "UTF-16")
                Device.onMain {
                    assertFalse(labels(view).any { secret in it || passphrase in it })
                    view.textStegoPanel.onAction(action)
                    view.textStegoPanel.onAction(action)
                    assertFalse(workspace.current())
                    assertEquals(0, writes)
                }
                val value = String(requireNotNull(saved))
                when (action) {
                    StegoAction.SAVE -> assertEquals(secret, value)
                    StegoAction.SAVE_COVER -> assertEquals(cover, value)
                    else -> {
                        val parsed = StegoCarrierCodec.decode(value, cover)
                        MessageCredential(MessageCredentialMode.PASSPHRASE, passphrase.toCharArray()).use { credential ->
                            val opened = MessageEnvelope().open(parsed.cover.toCharArray(), parsed.envelope, credential)
                            assertArrayEquals(secret.toCharArray(), opened); opened.fill('\u0000')
                        }
                        parsed.envelope.fill(0)
                        assertEquals(action == StegoAction.SAVE_CIPHERTEXT, StegoCarrierCodec.isDetached(value))
                    }
                }
            } finally { saved?.fill('\u0000'); Device.onMain { workspace.close() } }
        }
        assertFalse(host.isFinishing)
    }

    @Test fun detachedCiphertextNeedsOriginalCoverAndCanSaveACompleteCarrierAfterDecrypting() = fixture { view, _ ->
        val key = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        val envelope = MessageCredential(MessageCredentialMode.RANDOM_KEY, key.toCharArray()).use {
            MessageEnvelope().seal(cover.toCharArray(), secret.toCharArray(), it, kdf = MessageKdf.NONE)
        }
        var saved: CharArray? = null
        val workspace = StegoWorkspace({ graph }, Handler(Looper.getMainLooper()), view, { true }, { null }, {}, {}, { saved = it })
        try {
            Device.onMain {
                view.openTextStego()
                workspace.open(InputLanguage.ENGLISH, ChineseInputOptions(), StegoCarrierCodec.ciphertext(envelope))
                workspace.type(KeyboardAction.LiteralText(cover))
                view.textStegoPanel.onAction(StegoAction.NEXT)
                workspace.type(KeyboardAction.LiteralText(key))
                view.textStegoPanel.onAction(StegoAction.PREPARE)
            }
            awaitLabel(view, Device.context.getString(dev.zeroinput.ime.R.string.stego_result))
            Device.onMain {
                assertFalse(labels(view).any { secret in it })
                view.textStegoPanel.onAction(StegoAction.SAVE_CARRIER)
            }
            val parsed = StegoCarrierCodec.decode(String(requireNotNull(saved)))
            assertEquals(cover, parsed.cover)
            assertArrayEquals(envelope, parsed.envelope)
            parsed.envelope.fill(0)
        } finally { envelope.fill(0); saved?.fill('\u0000'); Device.onMain { workspace.close() } }
    }

    @Test fun revokedWorkspaceCannotSaveAnyResultOrSubmitAndCredentialsCannotBeSaved() = fixture { view, _ ->
        var eligible = true
        var saves = 0
        var commits = 0
        val workspace = StegoWorkspace({ graph }, Handler(Looper.getMainLooper()), view, { eligible },
            { { _: String -> commits++; true } }, {}, {}, { saves++; it.fill('\u0000') })
        try {
            Device.onMain {
                view.openTextStego(); workspace.open(InputLanguage.ENGLISH, ChineseInputOptions())
                workspace.type(KeyboardAction.LiteralText(cover))
                view.textStegoPanel.onAction(StegoAction.NEXT)
                workspace.type(KeyboardAction.LiteralText(secret))
                view.textStegoPanel.onAction(StegoAction.NEXT)
                workspace.type(KeyboardAction.LiteralText(passphrase))
                view.textStegoPanel.onAction(StegoAction.SAVE_FIELD)
                assertEquals(0, saves)
                view.textStegoPanel.onAction(StegoAction.PREPARE)
            }
            awaitLabel(view, "UTF-16")
            Device.onMain {
                eligible = false
                val callback = view.textStegoPanel.onAction
                for (action in listOf(StegoAction.SAVE, StegoAction.SAVE_COVER, StegoAction.SAVE_CIPHERTEXT,
                    StegoAction.SAVE_CARRIER, StegoAction.CONFIRM)) callback(action)
                assertEquals(0, saves); assertEquals(0, commits)
            }
        } finally { Device.onMain { workspace.close() } }
    }

    private fun fixture(action: (ZeroInputView, KeyboardPreviewFixtureActivity) -> Unit) {
        ActivityScenario.launch<KeyboardPreviewFixtureActivity>(Intent(Device.context, KeyboardPreviewFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            var host: KeyboardPreviewFixtureActivity? = null
            scenario.onActivity { host = it }
            val activity = requireNotNull(host)
            try { action(activity.keyboard, activity) } finally { Device.onMain { activity.keyboard.release() } }
        }
    }

    private fun awaitLabel(view: View, text: String) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000
        var ready = false
        while (!ready && android.os.SystemClock.elapsedRealtime() < deadline) {
            Device.onMain { ready = labels(view).any { text in it } }
            if (!ready) android.os.SystemClock.sleep(25)
        }
        assertTrue("Public result did not settle", ready)
    }
    private fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
    private fun labels(view: View) = nodes(view).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
}
