package dev.zeroinput.ime.core

import dev.zeroinput.ime.core.stego.*
import org.junit.Assert.*
import org.junit.Test

class StegoCarrierTest {
    @Test fun `every byte round trips while preserving emoji IVS combining and line endings`() {
        val covers = listOf("公开文本", "👩🏽‍💻❤️ 葛\uDB40\uDD00 e\u0301\r\n", " leading and trailing ")
        val bytes = ByteArray(256) { it.toByte() }
        for (transport in StegoTransport.entries) for (cover in covers) {
            val encoded = StegoCarrierCodec.encode(cover, bytes, transport)
            val decoded = StegoCarrierCodec.decode(encoded)
            assertEquals(cover, decoded.cover)
            assertEquals(transport, decoded.transport)
            assertArrayEquals(bytes, decoded.envelope)
        }
    }

    @Test fun `malformed duplicated mixed noncanonical and nonterminal blocks reject`() {
        val dense = StegoCarrierCodec.encode("cover", byteArrayOf(1, 20), StegoTransport.DENSE_UNICODE)
        val visible = StegoCarrierCodec.encode("cover", byteArrayOf(1, 20), StegoTransport.VISIBLE_BASE64)
        val invalid = listOf(dense + "x", visible + "x", dense + dense, visible + visible, dense + visible,
            dense.dropLast(1), visible.dropLast(1), dense.replace("\ufe01", "x"),
            visible.replace("ARQ", "ARQ="), visible.replace("ARQ", "ARR"),
            "cover${StegoCarrierCodec.DENSE_START}${StegoCarrierCodec.DENSE_END}",
            "cover[ZIS1:]", "cover[ZIS1:ARQ\n]", "ordinary text", "\ud800[ZIS1:ARQ]", "a\u0000[ZIS1:ARQ]")
        invalid.forEach { assertThrows(it.length.toString(), StegoFormatFailure::class.java) { StegoCarrierCodec.decode(it) } }
    }

    @Test fun `largest envelope and cover fit both limits without truncation`() {
        val cover = "汉".repeat(512)
        val bytes = ByteArray(2372) { 255.toByte() }
        for (transport in StegoTransport.entries) {
            val encoded = StegoCarrierCodec.encode(cover, bytes, transport)
            assertTrue(encoded.length <= 8192)
            assertTrue(StegoCarrierCodec.utf8Length(encoded) <= 16384)
            assertEquals(cover, StegoCarrierCodec.decode(encoded).cover)
        }
        assertThrows(StegoFormatFailure::class.java) { StegoCarrierCodec.encode(cover + "a", bytes, StegoTransport.VISIBLE_BASE64) }
        assertThrows(StegoFormatFailure::class.java) { StegoCarrierCodec.encode("cover", ByteArray(2373), StegoTransport.DENSE_UNICODE) }
        assertThrows(StegoFormatFailure::class.java) { StegoCarrierCodec.decode("a".repeat(8193)) }
    }

    @Test fun `reserved markers cannot be nested in the authenticated cover`() {
        listOf(StegoCarrierCodec.DENSE_START, StegoCarrierCodec.DENSE_END, StegoCarrierCodec.VISIBLE_START).forEach {
            assertThrows(StegoFormatFailure::class.java) { StegoCarrierCodec.encode("a${it}b", byteArrayOf(1), StegoTransport.DENSE_UNICODE) }
        }
    }

    @Test fun `cancelled operation cannot publish consume or revive after reopen`() {
        var time = 0L
        val gate = StegoOperationGate { time }
        assertNull(gate.begin())
        gate.open()
        val token = requireNotNull(gate.begin())
        assertNull(gate.begin())
        gate.close()
        gate.open()
        assertFalse(gate.complete(token, false))
        val fresh = requireNotNull(gate.begin())
        assertTrue(gate.complete(fresh, false))
        time = 30_000
        assertFalse(gate.consume(StegoOperationGate.Phase.PREVIEW))
        gate.open()
        val result = requireNotNull(gate.begin())
        assertTrue(gate.complete(result, true))
        assertTrue(gate.consume(StegoOperationGate.Phase.RESULT))
        assertFalse(gate.consume(StegoOperationGate.Phase.RESULT))
    }
}
