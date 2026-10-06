package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiAttachment
import org.junit.Assert.*
import org.junit.Test

class AiContentInboxTest {
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
