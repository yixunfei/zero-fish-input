package dev.zeroinput.ime.textstego

import android.content.Intent
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.clipboardguard.ClipboardDeviceTestSupport as Device
import dev.zeroinput.ime.core.stego.StegoCarrierCodec
import dev.zeroinput.ime.testing.InputFixtureActivity
import dev.zeroinput.ime.ui.*
import dev.zeroinput.security.message.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class StegoKeyboardIntegrationTest {
    private val graph get() = (Device.context.applicationContext as ZeroInputApplication).graph

    /** Run on a disposable emulator with a test fingerprint supplied at each system prompt. */
    @Test fun localDiaryRoundTripRequiresTwoFreshBiometricOperations() {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments()
            .getString("stegoTestFingerprint") == "true")
        withEditor { activity ->
            val password = "public fixture passphrase"
            val secret = "public diary fixture"
            Device.onMain {
                val view = keyboard()
                view.onTextStegoRequested()
                view.textStegoPanel.onAction(StegoAction.RANDOM_POETRY)
                view.textStegoPanel.onAction(StegoAction.SECRET)
                view.dispatchKeyboardAction(KeyboardAction.LiteralText(secret))
                view.textStegoPanel.onAction(StegoAction.CREDENTIAL_MODE)
            }
            Device.click(Device.context.getString(dev.zeroinput.ime.R.string.stego_local_mode))
            Device.click(Device.context.getString(android.R.string.ok))
            fun note() = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<StegoSourceActivity>().single()
            fun noteKeyboard() = views(note().window.decorView).filterIsInstance<ZeroInputView>().single()
            await { onMain { runCatching { noteKeyboard().isShown }.getOrDefault(false) } }
            Device.onMain {
                val view = noteKeyboard()
                assertFalse(secret in labels(view))
                view.textStegoPanel.onAction(StegoAction.RANDOM_POETRY)
                view.textStegoPanel.onAction(StegoAction.SECRET)
                view.dispatchKeyboardAction(KeyboardAction.LiteralText(secret))
                view.textStegoPanel.onAction(StegoAction.CREDENTIAL)
                view.dispatchKeyboardAction(KeyboardAction.LiteralText(password))
                view.textStegoPanel.onAction(StegoAction.PREPARE)
            }
            val saveLabel = Device.context.getString(dev.zeroinput.ime.ui.R.string.stego_save_carrier)
            await(45_000) { onMain { runCatching { saveLabel in labels(noteKeyboard()) }.getOrDefault(false) } }
            val encoded = onMain {
                noteKeyboard().textStegoPanel.onAction(StegoAction.SAVE_CARRIER)
                val host = note()
                val request = host.javaClass.getDeclaredField("request").apply { isAccessible = true }.get(host) as StegoHandoff.Request
                String(requireNotNull(request.text)).also { host.finish() }
            }
            val carrier = StegoCarrierCodec.decode(encoded)
            assertEquals(MessageCredentialMode.LOCAL_BIOMETRIC_PASSWORD, MessageEnvelope().inspect(carrier.envelope).mode)
            val unauthenticated = LocalMessageKey.begin(creatingMessage = false)
            assertThrows(java.security.ProviderException::class.java) { unauthenticated.doFinal(ByteArray(32)) }
            val token = onMain { graph.stegoHandoff.put(StegoHandoff.Request(StegoHandoff.Kind.CARRIER, encoded.toCharArray())) }
            Device.instrumentation.startActivitySync(Intent(Device.context, StegoSourceActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(StegoSourceActivity.EXTRA_TOKEN, token))
            Device.onMain {
                val view = noteKeyboard()
                view.dispatchKeyboardAction(KeyboardAction.LiteralText(password))
                view.textStegoPanel.onAction(StegoAction.PREPARE)
            }
            Device.click(Device.context.getString(android.R.string.ok))
            val result = Device.context.getString(dev.zeroinput.ime.R.string.stego_result)
            await(45_000) { onMain { runCatching { result in labels(noteKeyboard()) }.getOrDefault(false) } }
            Device.onMain {
                assertFalse(secret in labels(noteKeyboard()))
                noteKeyboard().textStegoPanel.onAction(StegoAction.REVEAL)
                assertTrue(secret in labels(noteKeyboard()))
                assertEquals("", activity.editor.text.toString())
                note().finish()
            }
        }
    }

    @Test fun authenticatedCoverPasteSurvivesRealEditorReturnAndWaitsForAnExplicitStegoTap() = authenticatedPaste(StegoField.COVER)
    @Test fun authenticatedSecretPasteReturnsMaskedOnlyAfterExplicitStegoTapWithoutWritingEditor() = authenticatedPaste(StegoField.SECRET)

    private fun authenticatedPaste(target: StegoField) {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("stegoTestPin") == "2468")
        assertEquals(0, graph.secureClipboard.count())
        val enabled = graph.settings.secureClipboardEnabled
        val cover = "public authenticated cover"
        val id = graph.secureClipboard.add("", cover, dev.zeroinput.security.AuthenticationGrant.afterSuccessfulSystemAuthentication()).id
        try {
            Device.onMain { graph.settings.secureClipboardEnabled = true }
            withEditor { activity ->
                var source: StegoSourceActivity? = null
                Device.onMain {
                    keyboard().onTextStegoRequested()
                    keyboard().textStegoPanel.onPasteRequested(target)
                }
                Device.click(Device.context.getString(dev.zeroinput.ime.R.string.stego_paste_vault))
                await { onMain { androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).any { it is StegoSourceActivity } } }
                Device.onMain {
                    source = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<StegoSourceActivity>().single()
                }
                clickStegoVaultEntry(1)
                StegoVaultPlatformTest().authenticate()
                await { onMain {
                    val host = requireNotNull(source)
                    !host.isFinishing && views(host.window.decorView).filterIsInstance<TextView>().any {
                        it.isShown && it.text.toString() == Device.context.getString(dev.zeroinput.ime.R.string.stego_confirm_read)
                    }
                } }
                Device.click(Device.context.getString(dev.zeroinput.ime.R.string.stego_confirm_read))
                await { onMain { activity.hasWindowFocus() } }
                await { onMain { runCatching { keyboard().isShown }.getOrDefault(false) } }
                Device.instrumentation.waitForIdleSync()
                Device.onMain {
                    assertEquals("", activity.editor.text.toString())
                    assertFalse(keyboard().isTextStegoOpen)
                    assertTrue(graph.stegoHandoff.hasPaste())
                    keyboard().onTextStegoRequested()
                    if (target == StegoField.SECRET) {
                        assertFalse(labels(keyboard().textStegoPanel).contains(cover))
                        keyboard().textStegoPanel.onAction(StegoAction.REVEAL)
                    }
                    assertTrue(labels(keyboard().textStegoPanel).contains(cover))
                    assertFalse(graph.stegoHandoff.hasPaste())
                    assertEquals("", activity.editor.text.toString())
                }
            }
        } finally {
            graph.secureClipboard.remove(id, dev.zeroinput.security.AuthenticationGrant.afterSuccessfulSystemAuthentication())
            Device.onMain { graph.stegoHandoff.close(); graph.settings.secureClipboardEnabled = enabled }
        }
    }

    @Test fun primaryButtonAutomaticallyInsertsOnceUsingInlineCheckboxesAndFields() = withEditor { activity ->
        val cover = "公开掩护文本 🌏"
        val secret = "公开测试秘密 中文"
        val passphrase = "public fixture passphrase"
        Device.onMain {
            val view = keyboard()
            view.onTextStegoRequested()
            view.dispatchKeyboardAction(KeyboardAction.LiteralText(cover))
        }
        tapLabel(dev.zeroinput.ime.ui.R.string.stego_secret)
        Device.onMain { keyboard().dispatchKeyboardAction(KeyboardAction.LiteralText(secret)) }
        tapLabel(dev.zeroinput.ime.ui.R.string.stego_credential)
        Device.onMain {
            val view = keyboard()
            view.dispatchKeyboardAction(KeyboardAction.LiteralText(passphrase))
            val checks = views(view.textStegoPanel).filterIsInstance<android.widget.CheckBox>()
            assertEquals(6, checks.size)
            checks.single { "SM4" in it.text }.performClick()
            checks.single { "XChaCha20" in it.text }.performClick()
            assertFalse(labels(view).any { secret in it || passphrase in it })
        }
        tapLabel(dev.zeroinput.ime.ui.R.string.stego_encrypt_insert)
        await(30_000) { onMain { activity.editor.text.isNotEmpty() } }
        val encoded = onMain { activity.editor.text.toString() }
        val carrier = StegoCarrierCodec.decode(encoded)
        MessageCredential(MessageCredentialMode.PASSPHRASE, passphrase.toCharArray()).use { key ->
            val opened = MessageEnvelope().open(carrier.cover.toCharArray(), carrier.envelope, key)
            assertArrayEquals(secret.toCharArray(), opened); opened.fill('\u0000')
        }
        carrier.envelope.fill(0)
        Device.onMain {
            assertEquals(cover, carrier.cover)
            assertFalse(keyboard().isTextStegoOpen)
            assertTrue(activity.editorActions.isEmpty())
            assertEquals(encoded, activity.editor.text.toString())
        }
    }

    @Test fun changingEditorSelectionDuringAutomaticEncryptionCannotInsertIntoTheNewSelection() = withEditor { activity ->
        Device.onMain { activity.editor.setText("public untouched editor"); activity.editor.selectAll() }
        Device.instrumentation.waitForIdleSync()
        await { onMain { service().let { service ->
            val editor = service.javaClass.getDeclaredField("editorConnection").apply { isAccessible = true }.get(service)
                as dev.zeroinput.ime.input.AndroidEditorConnection
            editor.selectedLength() == "public untouched editor".length
        } } }
        Device.onMain {
            val view = keyboard()
            view.onTextStegoRequested()
            view.dispatchKeyboardAction(KeyboardAction.LiteralText("public cover"))
            view.textStegoPanel.onAction(StegoAction.SECRET)
            view.dispatchKeyboardAction(KeyboardAction.LiteralText("public secret"))
            view.textStegoPanel.onAction(StegoAction.CREDENTIAL)
            view.dispatchKeyboardAction(KeyboardAction.LiteralText("public fixture passphrase"))
            view.textStegoPanel.onAction(StegoAction.CONFIRM)
            activity.editor.setSelection(0)
        }
        await { onMain { !keyboard().isTextStegoOpen } }
        // Allow a late worker result to return; it must not write into the changed editor.
        SystemClock.sleep(2500)
        Device.onMain { assertEquals("public untouched editor", activity.editor.text.toString()) }
    }

    private fun tapLabel(label: Int) {
        lateinit var button: TextView
        Device.onMain {
            button = views(keyboard().textStegoPanel).filterIsInstance<TextView>().single {
                it.isShown && it.text.toString() == it.context.getString(label)
            }
            button.requestRectangleOnScreen(android.graphics.Rect(0, 0, button.width, button.height), true)
        }
        Device.instrumentation.waitForIdleSync()
        // Field changes can start a posted smooth scroll after the main queue is idle.
        Device.instrumentation.uiAutomation.waitForIdle(300, 3000)
        val point = IntArray(2)
        Device.onMain {
            val visible = android.graphics.Rect()
            assertTrue("Public fixture control must be visible", button.getGlobalVisibleRect(visible))
            // Global-visible rectangles are relative to the root, while injected
            // events use screen coordinates. An IME window need not start at y=0.
            val origin = IntArray(2)
            button.rootView.getLocationOnScreen(origin)
            point[0] = visible.centerX() + origin[0]; point[1] = visible.centerY() + origin[1]
        }
        val down = SystemClock.uptimeMillis()
        for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
            val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, point[0].toFloat(), point[1].toFloat(), 0)
            try { assertTrue(Device.instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        Device.instrumentation.waitForIdleSync()
        Device.instrumentation.uiAutomation.waitForIdle(300, 3000)
        if (label == dev.zeroinput.ime.ui.R.string.stego_secret || label == dev.zeroinput.ime.ui.R.string.stego_credential)
            Device.onMain { assertTrue("Touched draft field ${button.resources.getResourceEntryName(label)} must become selected", button.isSelected) }
    }

    @Test fun isolatedDraftPreviewsThenCommitsOnlyAuthenticatedCarrierOnce() = withEditor { activity ->
        val cover = "public cover 🌏"
        val secret = "public secret fixture"
        val passphrase = "public fixture passphrase"
        var bindingRevision = 0L
        Device.onMain {
            val view = keyboard()
            view.onTextStegoRequested()
            assertTrue(view.isTextStegoOpen)
            assertTrue(WindowInspector.getGlobalWindowViews().any {
                it.layoutParams.let { p -> p is WindowManager.LayoutParams && p.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 }
            })
            view.dispatchKeyboardAction(KeyboardAction.LiteralText(cover))
            view.textStegoPanel.onAction(StegoAction.SECRET)
            view.dispatchKeyboardAction(KeyboardAction.LiteralText(secret))
            assertFalse(labels(view).any { secret in it })
            assertEquals("", activity.editor.text.toString())
            view.textStegoPanel.onAction(StegoAction.CREDENTIAL)
            view.dispatchKeyboardAction(KeyboardAction.LiteralText(passphrase))
            assertFalse(labels(view).any { passphrase in it })
            view.textStegoPanel.onAction(StegoAction.PREPARE)
            bindingRevision = interactionRevision(view)
        }
        await(30_000) { onMain { labels(keyboard()).any { "UTF-16" in it } } }
        Device.onMain {
            assertEquals("", activity.editor.text.toString())
            assertFalse(labels(keyboard()).any { secret in it })
            assertEquals("External interaction revoked the prepared binding", bindingRevision, interactionRevision(keyboard()))
            keyboard().textStegoPanel.onAction(StegoAction.CONFIRM)
            keyboard().textStegoPanel.onAction(StegoAction.CONFIRM)
        }
        await { onMain { activity.editor.text.isNotEmpty() } }
        val encoded = onMain { activity.editor.text.toString() }
        val carrier = StegoCarrierCodec.decode(encoded)
        assertEquals(cover, carrier.cover)
        MessageCredential(MessageCredentialMode.PASSPHRASE, passphrase.toCharArray()).use { key ->
            val opened = MessageEnvelope().open(carrier.cover.toCharArray(), carrier.envelope, key)
            assertArrayEquals(secret.toCharArray(), opened)
            opened.fill('\u0000')
        }
        Device.onMain {
            assertEquals(encoded, activity.editor.text.toString())
            assertTrue(activity.editorActions.isEmpty())
            assertFalse(keyboard().isTextStegoOpen)
        }
    }

    @Test fun selectionDecryptIsMaskedLocalAndSessionChangesRevokePreparedOutput() = withEditor { activity ->
        val secret = "public decrypted fixture"
        val passphrase = "public fixture passphrase"
        val envelope = MessageCredential(MessageCredentialMode.PASSPHRASE, passphrase.toCharArray()).use {
            MessageEnvelope().seal("public cover".toCharArray(), secret.toCharArray(), it)
        }
        val encoded = StegoCarrierCodec.encode("public cover", envelope, dev.zeroinput.ime.core.stego.StegoTransport.DENSE_UNICODE)
        Device.onMain { activity.editor.setText(encoded); activity.editor.selectAll() }
        Device.instrumentation.waitForIdleSync()
        // waitForIdleSync does not drain the editor-to-IME Binder selection update.
        await { onMain { service().let { service ->
            val editor = service.javaClass.getDeclaredField("editorConnection").apply { isAccessible = true }
                .get(service) as? dev.zeroinput.ime.input.AndroidEditorConnection
            editor?.selectedLength() == encoded.length
        } } }
        Device.onMain {
            keyboard().onTextStegoRequested()
            assertEquals("Opening the mode must preserve the selected carrier", encoded, activity.editor.text.toString())
            keyboard().textStegoPanel.onAction(StegoAction.DECRYPT)
            keyboard().textStegoPanel.onAction(StegoAction.SELECTION)
        }
        // Selection extraction is asynchronous; wait for the credential field to become selected.
        await { onMain { views(keyboard().textStegoPanel).filterIsInstance<TextView>().any {
            it.isSelected && it.text.toString() == it.context.getString(dev.zeroinput.ime.ui.R.string.stego_credential)
        } } }
        Device.onMain {
            keyboard().dispatchKeyboardAction(KeyboardAction.LiteralText(passphrase))
            keyboard().textStegoPanel.onAction(StegoAction.PREPARE)
        }
        val resultLabel = Device.context.getString(dev.zeroinput.ime.R.string.stego_result)
        val failureLabels = listOf(dev.zeroinput.ime.R.string.stego_failed,
            dev.zeroinput.ime.R.string.stego_memory_unavailable, dev.zeroinput.ime.R.string.stego_worker_busy)
        await(30_000) { onMain { labels(keyboard()).any { it == resultLabel || it in failureLabels.map(Device.context::getString) } } }
        Device.onMain {
            val failure = failureLabels.indexOfFirst { Device.context.getString(it) in labels(keyboard()) }
            assertTrue("Decrypt failed with public error category $failure", resultLabel in labels(keyboard()))
        }
        Device.onMain {
            assertEquals(encoded, activity.editor.text.toString())
            assertFalse(labels(keyboard()).any { secret in it })
            keyboard().textStegoPanel.onAction(StegoAction.REVEAL)
            assertTrue(labels(keyboard()).any { secret in it })
            activity.editor.setSelection(0)
        }
        Device.instrumentation.waitForIdleSync()
        await { onMain { !keyboard().isTextStegoOpen } }
        Device.onMain { assertEquals(encoded, activity.editor.text.toString()) }
    }

    @Test fun passwordAndNoPersonalizationEditorsCannotOpenSteganography() {
        for ((type, flags) in listOf((InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) to 0,
            InputType.TYPE_CLASS_TEXT to android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)) {
            withEditor(type, flags) { _ ->
                Device.onMain { keyboard().onTextStegoRequested(); assertFalse(keyboard().isTextStegoOpen) }
            }
        }
    }

    private fun withEditor(type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                           flags: Int = 0, action: (InputFixtureActivity) -> Unit) {
        val original = Device.shell("settings get secure default_input_method").trim()
        val incognito = graph.settings.incognitoMode
        val privateDefault = graph.settings.privateDraftEnabled
        assertFalse("Public isolated fixture requires private-default off", privateDefault)
        var activity: InputFixtureActivity? = null
        try {
            Device.onMain { graph.settings.incognitoMode = false }
            Device.shell("ime enable ${Device.context.packageName}/dev.zeroinput.ime.ZeroInputService")
            Device.shell("ime set ${Device.context.packageName}/dev.zeroinput.ime.ZeroInputService")
            activity = Device.instrumentation.startActivitySync(Intent(Device.context, InputFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("ordinary_fixture", true)
                .putExtra("input_type", type).putExtra("ime_options", flags)) as InputFixtureActivity
            Device.showFixtureKeyboard(activity)
            action(activity)
        } finally {
            activity?.let { Device.onMain { it.finish() } }
            Device.onMain { graph.settings.incognitoMode = incognito }
            if (original.isNotBlank() && original != "null") Device.shell("ime set $original")
        }
    }

    private fun keyboard() = requireNotNull(WindowInspector.getGlobalWindowViews().flatMap(::views)
        .filterIsInstance<ZeroInputView>().firstOrNull { it.isShown })
    private fun service(): dev.zeroinput.ime.ZeroInputService {
        var context = keyboard().context
        while (context is android.content.ContextWrapper && context !is dev.zeroinput.ime.ZeroInputService) context = context.baseContext
        return context as dev.zeroinput.ime.ZeroInputService
    }
    private fun interactionRevision(view: ZeroInputView): Long {
        var context = view.context
        while (context is android.content.ContextWrapper && context !is dev.zeroinput.ime.ZeroInputService) context = context.baseContext
        val service = context as dev.zeroinput.ime.ZeroInputService
        return dev.zeroinput.ime.ZeroInputService::class.java.getDeclaredField("interactionSequence").apply { isAccessible = true }.getLong(service)
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun labels(view: View) = views(view).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        Device.onMain { result = runCatching(block) }
        return requireNotNull(result).getOrThrow()
    }
    private fun await(timeout: Long = 6000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(25)
        assertTrue("Public steganography fixture did not settle", condition())
    }
}
