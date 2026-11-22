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
    @Test fun authenticatedWordingPastePreservesTheExplicitModeForOneEditorBoundDelivery() {
        val context: Context = RuntimeEnvironment.getApplication()
        val settings = SettingsRepository(context)
        val handoff = StegoHandoff(context, settings) { 1L }
        val editor = dev.zeroinput.ime.clipboard.PasteEditorIdentity("public.fixture", 1, 1)
        val verse = "江水浸云光，山径通幽处。".toCharArray()
        try {
            assertTrue(handoff.offerPaste(verse, editor, dev.zeroinput.ime.ui.StegoField.CARRIER, 1L,
                wording = StegoWordingProfile.SEVEN_VERSE))
            requireNotNull(handoff.takePaste(editor)).use { received ->
                assertEquals(StegoWordingProfile.SEVEN_VERSE, received.wording)
                assertSame(verse, received.text)
            }
            assertTrue(verse.all { it == '\u0000' })
            assertNull(handoff.takePaste(editor))
        } finally { handoff.destroy() }
    }

    @Test fun secretPasteIsOneUseAndRejectsDisabledPasteOversizeAndCredentialTargets() {
        val context: Context = RuntimeEnvironment.getApplication()
        val settings = SettingsRepository(context)
        val original = settings.stegoDefaults
        val handoff = StegoHandoff(context, settings) { 1L }
        val editor = dev.zeroinput.ime.clipboard.PasteEditorIdentity("public.fixture", 1, 1)
        try {
            settings.stegoDefaults = original.copy(vaultPaste = true)
            val text = "public secret fixture".toCharArray()
            assertTrue(handoff.offerPaste(text, editor, dev.zeroinput.ime.ui.StegoField.SECRET, expectedGeneration = 1L))
            val accepted = requireNotNull(handoff.takePaste(editor))
            assertEquals(dev.zeroinput.ime.ui.StegoField.SECRET, accepted.target)
            accepted.close(); assertTrue(text.all { it == '\u0000' })
            for (field in listOf(dev.zeroinput.ime.ui.StegoField.CREDENTIAL, dev.zeroinput.ime.ui.StegoField.KEY_BITS)) {
                val rejected = "public denied".toCharArray()
                assertFalse(handoff.offerPaste(rejected, editor, field, expectedGeneration = 1L))
                assertNull(handoff.takePaste(editor)); assertTrue(rejected.all { it == '\u0000' })
            }
            val oversized = CharArray(2049) { 'x' }
            handoff.offerPaste(oversized, editor, dev.zeroinput.ime.ui.StegoField.SECRET, expectedGeneration = 1L)
            assertNull(handoff.takePaste(editor)); assertTrue(oversized.all { it == '\u0000' })
            val revoked = "public revoked".toCharArray()
            handoff.offerPaste(revoked, editor, dev.zeroinput.ime.ui.StegoField.SECRET, expectedGeneration = 1L)
            settings.stegoDefaults = original.copy(vaultPaste = false)
            assertNull(handoff.takePaste(editor)); assertTrue(revoked.all { it == '\u0000' })
            val denied = "public denied".toCharArray()
            assertFalse(handoff.offerPaste(denied, editor, dev.zeroinput.ime.ui.StegoField.SECRET, expectedGeneration = 1L))
            assertNull(handoff.takePaste(editor)); assertTrue(denied.all { it == '\u0000' })
        } finally { handoff.destroy(); settings.stegoDefaults = original }
    }
    @Test fun pastedTextIsOneUseBoundToOriginalEditorAndClearedOnRevocation() {
        val context: Context = RuntimeEnvironment.getApplication()
        val settings = SettingsRepository(context)
        var generation = 1L
        val handoff = StegoHandoff(context, settings) { generation }
        val editor = dev.zeroinput.ime.clipboard.PasteEditorIdentity("public.fixture", 1, 1)
        try {
            val chars = "public cover".toCharArray()
            handoff.offerPaste(chars, editor, dev.zeroinput.ime.ui.StegoField.COVER, expectedGeneration = generation)
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
                handoff.offerPaste(stale, editor, dev.zeroinput.ime.ui.StegoField.COVER, expectedGeneration = generation)
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
        SettingsRepository(application).secureClipboardEnabled = true
    }

    @Test fun lateReadCannotRequeueAfterVaultDisableOrBecomePartOfANewerDeletionGeneration() {
        val context: Context = RuntimeEnvironment.getApplication()
        val settings = SettingsRepository(context)
        var generation = 1L
        val handoff = StegoHandoff(context, settings) { generation }
        val editor = dev.zeroinput.ime.clipboard.PasteEditorIdentity("public.fixture", 1, 1)
        try {
            settings.secureClipboardEnabled = false
            val disabled = "public revoked".toCharArray()
            assertFalse(handoff.offerPaste(disabled, editor, dev.zeroinput.ime.ui.StegoField.SECRET, 1L))
            assertTrue(disabled.all { it == '\u0000' })
            settings.secureClipboardEnabled = true
            generation++
            val stale = "public old generation".toCharArray()
            val carrier = "public old carrier".toCharArray()
            assertFalse(handoff.offerPaste(stale, editor, dev.zeroinput.ime.ui.StegoField.COVER, 1L, carrier))
            assertTrue(stale.all { it == '\u0000' } && carrier.all { it == '\u0000' })
            assertFalse(handoff.hasPaste())
            assertNull(handoff.takePaste(editor))
        } finally { handoff.destroy() }
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
