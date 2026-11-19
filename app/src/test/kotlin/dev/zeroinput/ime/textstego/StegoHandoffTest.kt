package dev.zeroinput.ime.textstego

import android.content.Context
import dev.zeroinput.ime.settings.SettingsRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StegoHandoffTest {
    @Test fun pastedTextIsOneUseBoundToOriginalEditorAndClearedOnRevocation() {
        val context: Context = RuntimeEnvironment.getApplication()
        val settings = SettingsRepository(context)
        var generation = 1L
        val handoff = StegoHandoff(context, settings) { generation }
        val editor = dev.zeroinput.ime.clipboard.PasteEditorIdentity("public.fixture", 1, 1)
        try {
            val chars = "public cover".toCharArray()
            handoff.offerPaste(chars, editor, dev.zeroinput.ime.ui.StegoField.COVER)
            handoff.leavePasteEditor()
            handoff.observePasteEditor(editor)
            assertNull(handoff.takeFile(editor))
            val accepted = requireNotNull(handoff.takePaste(editor))
            assertEquals(dev.zeroinput.ime.ui.StegoField.COVER, accepted.target)
            assertEquals("public cover", String(requireNotNull(accepted.text)))
            assertNull(handoff.takePaste(editor))
            accepted.close(); assertTrue(chars.all { it == '\u0000' })
            for (revoke in listOf<() -> Unit>({ generation++ }, { settings.incognitoMode = !settings.incognitoMode },
                    { handoff.takePaste(editor.copy(fieldId = 2)); Unit },
                    { handoff.observePasteEditor(editor); handoff.leavePasteEditor() }, { handoff.close() })) {
                val stale = "public stale".toCharArray()
                handoff.offerPaste(stale, editor, dev.zeroinput.ime.ui.StegoField.COVER)
                revoke()
                assertNull(handoff.takePaste(editor))
                assertTrue(stale.all { it == '\u0000' })
            }
        } finally { handoff.destroy() }
    }
    @Before fun allowTheFixtureToRegisterItsPrivateScreenReceiver() {
        val application = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(application)
            .grantPermissions("${application.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }

    @Test fun replayedOrMissingTokenCannotDiscardANewerSaveDraft() {
        val context: Context = RuntimeEnvironment.getApplication()
        val handoff = StegoHandoff(context, SettingsRepository(context)) { 1L }
        val first = "public old draft".toCharArray()
        val second = "public new draft".toCharArray()
        try {
            val old = handoff.put(StegoHandoff.Request(StegoHandoff.Kind.SAVE, first))
            val newest = handoff.put(StegoHandoff.Request(StegoHandoff.Kind.SAVE, second))
            assertTrue(first.all { it == '\u0000' })
            assertNull(handoff.take(old))
            assertNull(handoff.take(null))
            assertEquals("public new draft", String(second))
            val accepted = requireNotNull(handoff.take(newest))
            try { assertSame(second, accepted.text) } finally { accepted.close() }
            assertTrue(second.all { it == '\u0000' })
        } finally { handoff.destroy() }
    }

    @Test fun matchingTokenWithAnOldDeletionGenerationIsRejectedAndCleared() {
        val context: Context = RuntimeEnvironment.getApplication()
        var generation = 1L
        val handoff = StegoHandoff(context, SettingsRepository(context)) { generation }
        val chars = "public stale draft".toCharArray()
        try {
            val token = handoff.put(StegoHandoff.Request(StegoHandoff.Kind.SAVE, chars))
            generation++
            assertNull(handoff.take(token))
            assertTrue(chars.all { it == '\u0000' })
        } finally { handoff.destroy() }
    }
}
