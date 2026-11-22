package dev.zeroinput.ime.textstego

import dev.zeroinput.ime.clipboard.ClipboardImportText
import dev.zeroinput.ime.core.stego.StegoCarrierCodec
import dev.zeroinput.ime.core.stego.StegoTransport
import org.junit.Assert.*
import org.junit.Test

class StegoImportBoundaryTest {
    @Test fun `plain text cannot consume the import through the decrypt action`() {
        val request = requireNotNull(ClipboardImportText.parse("public plain text", allowsAiImport = true))
        try {
            assertNull(request.takeForStego())
            assertEquals("public plain text", request.copyForAi())
            assertTrue(request.beginAuthentication())
        } finally { request.close() }
    }

    @Test fun `recognized and damaged carriers cannot be forwarded to AI at the input model boundary`() {
        StegoTransport.entries.forEach { mode ->
            val encoded = StegoCarrierCodec.encode("public fixture", ByteArray(162), mode)
            val detached = StegoCarrierCodec.ciphertext(ByteArray(162))
            listOf(encoded, encoded.dropLast(1), detached, detached.dropLast(1),
                "public${StegoCarrierCodec.DENSE_END}", "public[ZIS1", "public[ZIS1:2").forEach { text ->
                val request = requireNotNull(ClipboardImportText.parse(text, allowsAiImport = true))
                assertFalse(request.allowsAiImport)
                assertNull(request.copyForAi())
                val chars = requireNotNull(request.takeForStego())
                assertTrue(text.contentEquals(String(chars)))
                chars.fill('\u0000')
                assertNull(request.takeForStego())
                assertFalse(request.beginAuthentication())
            }
        }
    }
}
