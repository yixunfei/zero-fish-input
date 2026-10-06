package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.*
import org.junit.Assert.*
import org.junit.Test

class AiAttachmentTest {
    @Test fun unsupportedTypesOversizedContentAndControlCharactersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { AiAttachment("application/zip", byteArrayOf(1), "file.zip") }
        assertThrows(IllegalArgumentException::class.java) { AiAttachment("image/png", ByteArray(1_000_001), "file.png") }
        assertThrows(IllegalArgumentException::class.java) { AiAttachment("text/plain", byteArrayOf(0), "file.txt") }
        assertThrows(Exception::class.java) { AiAttachment("text/plain", byteArrayOf(0xc0.toByte()), "file.txt") }
        assertThrows(IllegalArgumentException::class.java) {
            AiAttachment("text/plain", "x".repeat(AiLimits.MAX_INPUT_CHARS + 1).toByteArray(), "file.txt")
        }
    }

    @Test fun aggregateAttachmentLimitIsEnforcedBeforeQueueing() {
        val attachment = AiAttachment("image/png", ByteArray(600_000), "file.png")
        assertThrows(IllegalArgumentException::class.java) {
            AiRequest(null, AiAction.ASK, "fixture", attachments = listOf(attachment, attachment))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AiRequest(null, AiAction.ASK, "fixture", attachments = List(3) {
                AiAttachment("text/plain", "fixture".toByteArray(), "file.txt")
            })
        }
    }
}
