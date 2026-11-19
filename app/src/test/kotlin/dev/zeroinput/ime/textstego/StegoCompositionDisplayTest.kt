package dev.zeroinput.ime.textstego

import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import dev.zeroinput.engine.api.*
import dev.zeroinput.ime.R
import dev.zeroinput.ime.ZeroInputApplication
import dev.zeroinput.ime.input.LocalDraftInput
import dev.zeroinput.ime.ui.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StegoCompositionDisplayTest {
    @Test fun preeditAndCommittedInsertionAgreeAtStartMiddleAndEndIncludingEmoji() {
        val application = RuntimeEnvironment.getApplication() as ZeroInputApplication
        val view = ZeroInputView(ContextThemeWrapper(application, R.style.Theme_ZeroInput))
        view.openTextStego()
        val workspace = StegoWorkspace({ application.graph }, Handler(Looper.getMainLooper()), view,
            { true }, { null }, {}, {}, {}, false)
        try {
            workspace.open(InputLanguage.ENGLISH, ChineseInputOptions())
            @Suppress("UNCHECKED_CAST")
            val drafts = field(workspace, "drafts") as Map<StegoField, LocalDraftInput>
            val draft = drafts.getValue(StegoField.COVER)
            for (offset in listOf(0, 1, 3, 4)) {
                assertTrue(draft.replaceCommittedText("A😀B"))
                @Suppress("UNCHECKED_CAST")
                val carets = field(workspace, "carets") as Map<StegoField, DraftCaretUi>
                workspace.type(KeyboardAction.MoveDraftCaret(offset, carets.getValue(StegoField.COVER).revision))
                workspace.type(KeyboardAction.Text("x"))
                val expected = "A😀B".substring(0, offset) + "x" + "A😀B".substring(offset)
                val display = displays(workspace).getValue(StegoField.COVER)
                assertEquals(expected, display.text)
                assertEquals(1, display.preeditLength)
                assertEquals(offset, display.caret?.offset)
                workspace.type(KeyboardAction.Enter)
                assertEquals(expected, draft.committedText())
            }
        } finally { workspace.close(); view.release() }
    }

    @Test fun hiddenSecretNeverExposesCommittedTextOrPreedit() {
        val application = RuntimeEnvironment.getApplication() as ZeroInputApplication
        val view = ZeroInputView(ContextThemeWrapper(application, R.style.Theme_ZeroInput))
        view.openTextStego()
        val workspace = StegoWorkspace({ application.graph }, Handler(Looper.getMainLooper()), view,
            { true }, { null }, {}, {}, {}, false)
        try {
            workspace.open(InputLanguage.ENGLISH, ChineseInputOptions())
            @Suppress("UNCHECKED_CAST")
            val drafts = field(workspace, "drafts") as Map<StegoField, LocalDraftInput>
            val draft = drafts.getValue(StegoField.SECRET)
            draft.replaceCommittedText("AB")
            draft.handle(KeyboardAction.Text("x"))
            assertEquals("••", displays(workspace).getValue(StegoField.SECRET).text)
            assertNull(displays(workspace).getValue(StegoField.SECRET).caret)
            assertEquals(0, displays(workspace).getValue(StegoField.SECRET).preeditLength)
            view.textStegoPanel.onAction(StegoAction.REVEAL)
            assertEquals("ABx", displays(workspace).getValue(StegoField.SECRET).text)
            assertEquals(1, displays(workspace).getValue(StegoField.SECRET).preeditLength)
            view.textStegoPanel.onAction(StegoAction.REVEAL)
            assertEquals("••", displays(workspace).getValue(StegoField.SECRET).text)
        } finally { workspace.close(); view.release() }
    }

    @Test fun formattedChinesePreeditUsesItsDisplayedLengthInsteadOfTheRawCodeLength() {
        val application = RuntimeEnvironment.getApplication() as ZeroInputApplication
        val view = ZeroInputView(ContextThemeWrapper(application, R.style.Theme_ZeroInput))
        view.openTextStego()
        val workspace = StegoWorkspace({ application.graph }, Handler(Looper.getMainLooper()), view,
            { true }, { null }, {}, {}, {}, false)
        try {
            workspace.open(InputLanguage.CHINESE, ChineseInputOptions())
            @Suppress("UNCHECKED_CAST")
            val drafts = field(workspace, "drafts") as Map<StegoField, LocalDraftInput>
            drafts.getValue(StegoField.COVER).replaceCommittedText("AB")
            @Suppress("UNCHECKED_CAST")
            val carets = field(workspace, "carets") as Map<StegoField, DraftCaretUi>
            workspace.type(KeyboardAction.MoveDraftCaret(1, carets.getValue(StegoField.COVER).revision))
            @Suppress("UNCHECKED_CAST")
            val states = field(workspace, "states") as MutableMap<StegoField, dev.zeroinput.ime.core.InputSessionState>
            states[StegoField.COVER] = states.getValue(StegoField.COVER).copy(snapshot = EngineSnapshot("nihao", "ni hao"))
            val display = displays(workspace).getValue(StegoField.COVER)
            assertEquals("Ani haoB", display.text)
            assertEquals(6, display.preeditLength)
            assertEquals(1, display.caret?.offset)
        } finally { workspace.close(); view.release() }
    }

    private fun field(workspace: StegoWorkspace, name: String): Any = requireNotNull(workspace.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(workspace))

    @Suppress("UNCHECKED_CAST")
    private fun displays(workspace: StegoWorkspace) = workspace.javaClass.getDeclaredMethod("fieldDisplays")
        .apply { isAccessible = true }.invoke(workspace) as Map<StegoField, StegoFieldUi>
}
