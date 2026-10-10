package dev.zeroinput.ime.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardImportRequestTest {
    @Test
    fun authenticationAloneNeverStartsSavingAndDuplicateActionsAreRejected() {
        val request = ClipboardImportRequest("public fixture".toCharArray())
        assertNull(request.copyText())
        assertFalse(request.beginSave())
        assertFalse(request.authorize())
        assertTrue(request.beginAuthentication())
        assertFalse(request.beginAuthentication())
        assertFalse(request.beginSave())
        assertNull(request.copyText())
        assertTrue(request.authorize())
        assertFalse(request.authorize())
        assertFalse(request.isSaving())
        assertEquals("public fixture", request.copyText())
        assertTrue(request.beginSave())
        assertFalse(request.beginSave())
        assertFalse(request.authorize())
        request.close()
    }

    @Test
    fun cancellationAtEveryStageWipesDraftAndRejectsDelayedCallbacks() {
        for (stage in 0..3) {
            val buffer = "public fixture".toCharArray()
            val request = ClipboardImportRequest(buffer)
            if (stage >= 1) request.beginAuthentication()
            if (stage >= 2) request.authorize()
            if (stage >= 3) request.beginSave()
            request.close()
            request.close()
            assertTrue(buffer.all { it == '\u0000' })
            assertNull(request.copyText())
            assertFalse(request.beginAuthentication())
            assertFalse(request.authorize())
            assertFalse(request.beginSave())
            assertFalse(request.isSaving())
        }
    }

    @Test
    fun oldRequestCannotAuthorizeOrSaveItsReplacement() {
        val old = ClipboardImportRequest("first fixture".toCharArray())
        old.beginAuthentication()
        old.close()
        val replacement = ClipboardImportRequest("second fixture".toCharArray())
        assertFalse(old.authorize())
        assertFalse(old.beginSave())
        assertEquals(ClipboardImportRequest.Phase.REVIEW, replacement.phase)
        assertNull(replacement.copyText())
        replacement.close()
    }

    @Test
    fun saveCopyUsesWipeableCharactersAndClosedRequestsRejectCopies() {
        val source = "private fixture".toCharArray()
        val request = ClipboardImportRequest(source)
        request.beginAuthentication()
        request.authorize()
        request.beginSave()

        val copy = request.copyChars()
        assertEquals("private fixture", String(checkNotNull(copy)))
        request.close()
        assertTrue(source.all { it == '\u0000' })
        assertTrue(checkNotNull(copy).any { it != '\u0000' })
        checkNotNull(copy).fill('\u0000')
        assertTrue(copy.all { it == '\u0000' })
        assertNull(request.copyChars())
    }
}
