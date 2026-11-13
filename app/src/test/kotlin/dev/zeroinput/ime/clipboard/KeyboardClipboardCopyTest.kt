package dev.zeroinput.ime.clipboard

import android.os.Handler
import android.os.Looper
import dev.zeroinput.ime.ui.ClipboardCopyUi
import dev.zeroinput.security.AuthenticationGrant
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.userdata.SecureClipboardVault
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class KeyboardClipboardCopyTest {
    @Test fun differentEditorRevokesTheDraftAndLateAuthenticationCannotRestoreIt() = withCopy { fixture ->
        fixture.reviewAndAuthenticate()
        fixture.copy.leaveEditor()
        fixture.copy.editorStarted(source.copy(fieldId = 9))
        fixture.completeAuthentication()
        fixture.copy.bindReturnedEditor(2, source)
        assertNull(fixture.ui)
        assertEquals(ClipboardImportRequest.Phase.CLOSED, fixture.draft.phase)
        assertEquals(0, fixture.vault.count())
    }

    @Test fun deletingTheVaultBetweenAuthenticationAndSaveInvalidatesTheDraft() = withCopy { fixture ->
        fixture.reviewAndAuthenticate()
        fixture.completeAuthentication()
        fixture.copy.bindReturnedEditor(2, source)
        assertEquals(ClipboardCopyUi.ConfirmSave, fixture.ui)
        fixture.vault.purge()
        fixture.copy.act(2, source)
        assertNull(fixture.ui)
        assertEquals(ClipboardImportRequest.Phase.CLOSED, fixture.draft.phase)
        assertEquals(0, fixture.vault.count())
    }

    @Test fun authorizedDraftExpiresWithoutSaving() = withCopy { fixture ->
        fixture.reviewAndAuthenticate()
        fixture.completeAuthentication()
        fixture.copy.bindReturnedEditor(2, source)
        assertEquals(ClipboardCopyUi.ConfirmSave, fixture.ui)
        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS)
        fixture.copy.act(2, source)
        assertNull(fixture.ui)
        assertEquals(ClipboardImportRequest.Phase.CLOSED, fixture.draft.phase)
        assertEquals(0, fixture.vault.count())
    }

    private fun withCopy(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try { test(fixture) } finally {
            fixture.copy.cancel()
            fixture.worker.shutdownNow()
        }
    }

    private class Fixture {
        val vault = SecureClipboardVault(MemoryStore(), MemoryStore())
        val worker = Executors.newSingleThreadExecutor()
        val draft = ClipboardImportRequest("public fixture".toCharArray())
        var ui: ClipboardCopyUi? = null
        private var authenticate: ((AuthenticationGrant?) -> Unit)? = null
        val copy = KeyboardClipboardCopy(Handler(Looper.getMainLooper()), worker, vault,
            authenticate = { authenticate = it; AutoCloseable {} }, render = { ui = it },
            onAuthenticated = {}, onAuthenticationFailed = { fail() },
            onSaved = { _, _ -> fail("A revoked draft cannot save") })

        fun reviewAndAuthenticate() {
            copy.offer(draft, vault.captureGeneration(), source, 1, enabled = true)
            copy.act(1, source)
            assertEquals(ClipboardCopyUi.Authenticating, ui)
        }

        fun completeAuthentication() {
            checkNotNull(authenticate).invoke(AuthenticationGrant.afterSuccessfulSystemAuthentication())
        }
    }

    private class MemoryStore : EncryptedStore {
        private var content: ByteArray? = null
        override fun read() = content?.copyOf()
        override fun write(plaintext: ByteArray) { content = plaintext.copyOf(); plaintext.fill(0) }
        override fun delete(deleteKey: Boolean) { content?.fill(0); content = null }
    }

    private companion object {
        val source = PasteEditorIdentity("dev.fixture", 7, android.text.InputType.TYPE_CLASS_TEXT)
    }
}
