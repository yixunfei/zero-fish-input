package dev.zeroinput.ime.textstego

import dev.zeroinput.ime.clipboard.ClipboardImportText
import dev.zeroinput.ime.core.stego.StegoCarrierCodec
import dev.zeroinput.ime.core.stego.StegoTransport
import org.junit.Assert.*
import org.junit.Test

class StegoImportBoundaryTest {
    @Test fun `recognized and damaged carriers cannot be forwarded to AI at the input model boundary`() {
        StegoTransport.entries.forEach { mode ->
            val encoded = StegoCarrierCodec.encode("public fixture", ByteArray(162), mode)
            listOf(encoded, encoded.dropLast(1)).forEach { text ->
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
