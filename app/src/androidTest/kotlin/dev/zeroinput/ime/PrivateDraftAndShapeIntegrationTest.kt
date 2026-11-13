package dev.zeroinput.ime

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ime.auth.SecureClipboardUnlockActivity
import dev.zeroinput.ime.clipboardguard.ClipboardDeviceTestSupport as Device
import dev.zeroinput.ime.testing.InputFixtureActivity
import dev.zeroinput.ime.ui.KeyboardAction
import dev.zeroinput.ime.ui.ZeroInputView
import dev.zeroinput.security.AuthenticationGrant
import dev.zeroinput.userdata.SecureClipboardVault
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CancellationException
import dev.zeroinput.ime.ui.R as UiR

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class PrivateDraftAndShapeIntegrationTest {
    private val graph get() = (Device.context.applicationContext as ZeroInputApplication).graph

    @Test fun privateDraftAuthenticatesToggleAndWritesOnlyOnExplicitCompletion() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("clipboardTestPin") == "2468")
        assumeTrue(!graph.settings.privateDraftEnabled && graph.secureClipboard.count() == 0)
        val clipboardEnabled = graph.settings.secureClipboardEnabled
        val saveCopy = graph.settings.savePrivateDraftOnSubmit
        val monitor = Device.instrumentation.addMonitor(SecureClipboardUnlockActivity::class.java.name, null, false)
        val activity = startEditor()
        try {
            Device.showFixtureKeyboard(activity)
            clickDescription(UiR.string.private_draft_open)
            authenticate()
            Device.await("Authenticated toggle should enable private input") { graph.settings.privateDraftEnabled }
            Device.showFixtureKeyboard(activity)
            awaitButton(UiR.string.private_draft_save_close)
            assertEquals(1, monitor.hits)
            selectPrivatePinyinCandidate(activity)
            rejectTruncatedPrivateDraft(activity)
            enterDraft(activity, "public draft")
            clickButton(UiR.string.private_draft_preview)
            Device.onMain {
                assertTrue(labels(keyboard()).contains("public draft"))
                keyboard().onKeyboardAction(KeyboardAction.LiteralText("!"))
                assertTrue(labels(keyboard()).contains("public draft!"))
                keyboard().onKeyboardAction(KeyboardAction.Backspace)
            }
            clickButton(UiR.string.private_draft_mask)
            Device.onMain { assertFalse(labels(keyboard()).contains("public draft")) }
            assertEquals(0, graph.secureClipboard.count())
            clickButton(UiR.string.private_draft_save_close)
            Device.await { graph.secureClipboard.count() == 1 }
            val id = graph.secureClipboard.summaries().single().id
            assertEquals("public draft".length, graph.secureClipboard.metadata(grant()).single().valueLength)
            val grant = grant()
            assertEquals("public draft", graph.secureClipboard.read(id, grant))
            assertThrows(IllegalArgumentException::class.java) { graph.secureClipboard.read(id, grant) }
            Device.onMain {
                assertEquals("", activity.editor.text.toString())
                assertFalse(keyboard().isPrivateDraftOpen)
            }
            assertEquals(1, monitor.hits)
            pasteFirstItem(activity)
            assertEquals(2, monitor.hits)
            Device.onMain { graph.settings.savePrivateDraftOnSubmit = true }
            reopenDraft(activity)
            enterDraft(activity, "public saved submit")
            clickButton(UiR.string.private_draft_save_submit)
            Device.await { graph.secureClipboard.count() == 2 }
            Device.await { onMain { activity.editor.text.toString() == "public saved submit" } }
            Device.onMain { activity.editor.setText("") }
            reopenDraft(activity)
            enterDraft(activity, "public direct submit")
            clickButton(UiR.string.private_draft_submit_only)
            Device.await { onMain { activity.editor.text.toString() == "public direct submit" } }
            assertEquals(2, graph.secureClipboard.count())
            Device.onMain { graph.settings.savePrivateDraftOnSubmit = false; activity.editor.setText("") }
            reopenDraft(activity)
            enterDraft(activity, "public submit only")
            clickButton(UiR.string.private_draft_save_submit)
            Device.await { onMain { activity.editor.text.toString() == "public submit only" } }
            assertEquals(2, graph.secureClipboard.count())
            assertEquals(2, monitor.hits)
            reopenDraft(activity)
            clickDescription(UiR.string.private_draft_disable)
            authenticate()
            Device.await { !graph.settings.privateDraftEnabled }
            assertEquals(3, monitor.hits)
            assertThrows(CancellationException::class.java) {
                graph.secureClipboard.addPrivateDraft("public rejected".toCharArray(),
                    graph.secureClipboard.captureGeneration()) { true }
            }
            assertEquals(2, graph.secureClipboard.count())
        } finally {
            Device.instrumentation.removeMonitor(monitor)
            Device.onMain {
                if (graph.settings.privateDraftEnabled) graph.settings.setPrivateDraftEnabled(false, grant())
                graph.settings.savePrivateDraftOnSubmit = saveCopy
                graph.settings.secureClipboardEnabled = clipboardEnabled
                activity.finish()
            }
            graph.secureClipboard.summaries().forEach { graph.secureClipboard.remove(it.id, grant()) }
        }
    }

    @Test fun pinyinSeedAndRadicalLookupCommitOnlyTheChosenResult() {
        assumeTrue(!graph.settings.privateDraftEnabled)
        val activity = startEditor()
        try {
            Device.showFixtureKeyboard(activity)
            openShape()
            "qing".forEach { letter -> clickText(letter.toString()) }
            Device.await("The offline index should return the pinyin seed") { onMain { choice("青") != null } }
            Device.onMain { assertEquals("", activity.editor.text.toString()); checkNotNull(choice("青")).performClick() }
            Device.await("Characters containing the selected shape should be available") { onMain { choice("清") != null } }
            Device.onMain {
                assertEquals("", activity.editor.text.toString())
                assertTrue(checkNotNull(choice("清")).contentDescription.toString().contains("qīng"))
                checkNotNull(choice("清")).performClick()
            }
            Device.await { onMain { activity.editor.text.toString() == "清" } }
            openShape()
            Device.onMain { checkNotNull(choice("水")).performClick() }
            Device.await { onMain { views(keyboard()).any { it.isShown && it.contentDescription?.startsWith("法 fǎ") == true } } }
            val results = onMain {
                views(keyboard()).filterIsInstance<androidx.recyclerview.widget.RecyclerView>()
                    .single { it.isShown && (it.adapter?.itemCount ?: 0) > 12 }
            }
            Device.onMain {
                assertTrue(results.canScrollVertically(1))
                results.scrollToPosition(12)
            }
            Device.await {
                onMain { (results.layoutManager as androidx.recyclerview.widget.GridLayoutManager)
                    .findLastVisibleItemPosition() >= 12 }
            }
            Device.onMain { assertEquals("清", activity.editor.text.toString()) }
        } finally {
            Device.onMain { activity.finish() }
        }
    }

    private fun startEditor() = Device.instrumentation.startActivitySync(
        Intent(Device.context, InputFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("ordinary_fixture", true),
    ) as InputFixtureActivity

    private fun reopenDraft(activity: InputFixtureActivity) {
        Device.onMain {
            activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).restartInput(activity.editor)
        }
        Device.showFixtureKeyboard(activity)
        awaitButton(UiR.string.private_draft_save_close)
    }

    private fun pasteFirstItem(activity: InputFixtureActivity) {
        clickDescription(UiR.string.secure_clipboard_open)
        val item = Device.context.getString(R.string.secure_clipboard_ordinal, 1)
        Device.await("The saved draft should appear in the clipboard") { onMain { item in labels(keyboard()) } }
        clickText(item)
        authenticate()
        Device.await("The source editor should regain focus") { onMain { activity.hasWindowFocus() } }
        Device.onMain {
            activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(activity.editor, 0)
        }
        awaitButton(UiR.string.secure_clipboard_confirm_paste)
        Device.onMain { assertEquals("", activity.editor.text.toString()) }
        clickButton(UiR.string.secure_clipboard_confirm_paste)
        Device.await { onMain { activity.editor.text.toString() == "public draft" } }
        Device.onMain { activity.editor.setText("") }
    }

    private fun authenticate() {
        Device.await("System credential input should be focused") {
            Device.shell("dumpsys window").lineSequence().any { line ->
                "mCurrentFocus=Window{" in line && line.trimEnd().endsWith(" BiometricPrompt}")
            }
        }
        android.os.SystemClock.sleep(450)
        Device.shell("input text 2468")
        Device.shell("input keyevent KEYCODE_ENTER")
    }

    private fun enterDraft(activity: InputFixtureActivity, value: String) = Device.onMain {
        val view = keyboard()
        val hostText = activity.editor.text.toString()
        view.onKeyboardAction(KeyboardAction.LiteralText(value))
        assertEquals(hostText, activity.editor.text.toString())
        assertTrue(labels(view).any { it.startsWith("••") && it.endsWith(value.takeLast(3)) })
        assertFalse(labels(view).contains(value))
    }

    private fun selectPrivatePinyinCandidate(activity: InputFixtureActivity) {
        Device.onMain {
            val view = keyboard()
            val languageKey = views(view).filterIsInstance<TextView>().single {
                it.isShown && it.contentDescription == view.context.getString(UiR.string.key_language)
            }
            if (languageKey.text.toString() == view.context.getString(UiR.string.language_short_english)) {
                languageKey.performClick()
            }
            view.onKeyboardAction(KeyboardAction.Text("n"))
            view.onKeyboardAction(KeyboardAction.Text("i"))
        }
        Device.await { onMain { "你" in labels(keyboard()) } }
        clickText("你")
        Device.onMain {
            assertEquals("", activity.editor.text.toString())
            assertTrue(labels(keyboard()).contains("你"))
            keyboard().onKeyboardAction(KeyboardAction.Backspace)
        }
    }

    private fun rejectTruncatedPrivateDraft(activity: InputFixtureActivity) {
        Device.onMain {
            val view = keyboard()
            view.onKeyboardAction(KeyboardAction.LiteralText("a".repeat(SecureClipboardVault.MAX_VALUE_LENGTH)))
            view.onKeyboardAction(KeyboardAction.Text("n"))
            view.onKeyboardAction(KeyboardAction.Text("i"))
        }
        Device.await { onMain { "你" in labels(keyboard()) } }
        clickButton(UiR.string.private_draft_save_close)
        Device.onMain {
            assertTrue(keyboard().isPrivateDraftOpen)
            assertEquals("", activity.editor.text.toString())
        }
        assertEquals(0, graph.secureClipboard.count())
        reopenDraft(activity)
    }

    private fun openShape() {
        clickDescription(UiR.string.shape_open)
    }

    private fun clickDescription(id: Int) = Device.onMain {
        val view = keyboard()
        checkNotNull(views(view).firstOrNull { it.isShown && it.contentDescription == view.context.getString(id) }).performClick()
    }

    private fun clickText(text: String) = Device.onMain {
        checkNotNull(views(keyboard()).filterIsInstance<TextView>().firstOrNull { it.isShown && it.text == text }).performClick()
    }

    private fun clickButton(id: Int) = clickText(Device.context.getString(id))

    private fun awaitButton(id: Int) = Device.await {
        onMain { currentKeyboard()?.let { labels(it).contains(Device.context.getString(id)) } == true }
    }

    private fun keyboard() = checkNotNull(currentKeyboard())

    private fun currentKeyboard() = WindowInspector.getGlobalWindowViews().flatMap(::views)
        .filterIsInstance<ZeroInputView>().firstOrNull { it.isShown }

    private fun choice(value: String) = views(keyboard()).firstOrNull {
        it.isShown && it.contentDescription?.toString()?.substringBefore(' ') == value
    }

    private fun labels(view: View) = views(view).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
    } else emptyList()

    private fun <T> onMain(action: () -> T): T {
        var value: Result<T>? = null
        Device.onMain { value = runCatching(action) }
        return checkNotNull(value).getOrThrow()
    }

    private fun grant() = AuthenticationGrant.afterSuccessfulSystemAuthentication()
}
