package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiAttachment
import org.junit.Assert.*
import org.junit.Test

class AiContentInboxTest {
    @Test fun rejectedImportCanBeRetriedWithoutExtendingItsOriginalDeadline() {
        var time = 0L
        val inbox = AiContentInbox { time }
        val value = content()
        inbox.put(value)
        assertEquals(false, inbox.claimIfAccepted { false })
        assertEquals("fixture", String(value.text))
        assertTrue(inbox.available())
        time = AiContentInbox.LIFETIME_MS - 1
        assertEquals(false, inbox.claimIfAccepted { false })
        time++
        assertNull(inbox.claimIfAccepted { error("Expired content must not be offered") })
        assertTrue(value.text.all { it == '\u0000' })
        assertTrue(value.attachments.single().bytes.all { it == 0.toByte() })
    }

    @Test fun acceptedRetryTransfersOwnershipExactlyOnce() {
        val inbox = AiContentInbox { 0L }
        val value = content()
        inbox.put(value)
        assertEquals(false, inbox.claimIfAccepted { false })
        assertEquals(true, inbox.claimIfAccepted { assertSame(value, it); true })
        assertNull(inbox.claimIfAccepted { true })
        inbox.clear()
        assertEquals("fixture", String(value.text))
        value.close()
    }

    @Test fun replacementExpiryAndClearWipeUnclaimedContent() {
        var time = 0L
        val inbox = AiContentInbox { time }
        val first = content()
        inbox.put(first)
        inbox.put(content())
        assertTrue(first.text.all { it == '\u0000' })
        assertTrue(first.attachments.single().bytes.all { it == 0.toByte() })
        assertTrue(inbox.available())
        time = AiContentInbox.LIFETIME_MS
        assertNull(inbox.take())
        val last = content()
        inbox.put(last)
        inbox.clear()
        assertFalse(inbox.available())
        assertTrue(last.attachments.single().bytes.all { it == 0.toByte() })
    }

    @Test fun contentCanOnlyBeClaimedOnceAndOwnershipMovesToTheSession() {
        val inbox = AiContentInbox { 0L }
        val value = content()
        inbox.put(value)
        assertSame(value, inbox.take())
        assertNull(inbox.take())
        inbox.clear()
        assertEquals("fixture", String(value.text))
        value.close()
        assertTrue(value.text.all { it == '\u0000' })
    }

    private fun content() = AiImportedContent("fixture".toCharArray(),
        listOf(AiAttachment("text/plain", "public fixture".toByteArray(), "note.txt")))
}
