package dev.zeroinput.ime.core

import dev.zeroinput.ime.core.stego.*
import org.junit.Assert.*
import org.junit.Test

class StegoCarrierTest {
    @Test fun `operation near editing expiry has a fresh bounded working window`() {
        var time = 0L
        val gate = StegoOperationGate { time }
        gate.open()
        time = 119_000
        assertEquals(1000L, gate.remainingMillis())
        val token = requireNotNull(gate.begin())
        assertEquals(120_000L, gate.remainingMillis())
        time = 121_000
        assertTrue(gate.accepts(token))
        assertTrue(gate.complete(token, false))
        time += 30_000
        assertFalse(gate.consume(StegoOperationGate.Phase.PREVIEW))
        gate.open()
        val slow = requireNotNull(gate.begin())
        time += 119_999
        assertTrue(gate.accepts(slow))
        assertEquals(1L, gate.remainingMillis())
        time++
        assertFalse(gate.complete(slow, true))
        gate.open()
        time += 120_000
        assertNull(gate.begin())
    }

    @Test fun `detached ciphertext requires exact explicit cover and retains strict framing budgets`() {
        val bytes = ByteArray(2372) { it.toByte() }
        val detached = StegoCarrierCodec.ciphertext(bytes)
        assertArrayEquals(bytes, StegoCarrierCodec.decode(detached, "汉".repeat(512)).envelope)
        assertThrows(StegoFormatFailure::class.java) { StegoCarrierCodec.decode(detached) }
        for (cover in listOf("", " ", "a".repeat(513), "\ud800", "x${StegoCarrierCodec.VISIBLE_START}"))
            assertThrows(StegoFormatFailure::class.java) { StegoCarrierCodec.decode(detached, cover) }
        for (invalid in listOf(detached + "x", detached + detached, "[ZIS1:ARR]", "[ZIS1:ARQ=]", "[ZIS1:]"))
            assertThrows(StegoFormatFailure::class.java) { StegoCarrierCodec.decode(invalid, "cover") }
        assertThrows(StegoFormatFailure::class.java) { StegoCarrierCodec.ciphertext(ByteArray(2373)) }
        assertThrows(StegoFormatFailure::class.java) { StegoCarrierCodec.ciphertext(byteArrayOf()) }
        val complete = StegoCarrierCodec.encode("original", bytes, StegoTransport.VISIBLE_BASE64)
        assertEquals("original", StegoCarrierCodec.decode(complete, "ignored").cover)
    }

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

    @Test fun `distributed blocks preserve both visible ends and reject damaged framing`() {
        val cover = "一二三四五六七八九十"
        val bytes = ByteArray(256) { it.toByte() }
        for (transport in StegoTransport.entries) {
            val encoded = StegoCarrierCodec.encode(cover, bytes, transport)
            assertTrue(encoded.startsWith("一"))
            assertTrue(encoded.endsWith("十"))
            val marker = if (transport == StegoTransport.DENSE_UNICODE) StegoCarrierCodec.DENSE_START else "[ZIS1:2:"
            assertTrue(encoded.indexOf(marker) < encoded.lastIndexOf(marker))
            for (bad in listOf(encoded.replaceFirst(marker, ""), encoded + encoded,
                    encoded.replaceFirst(marker, marker + "!"))) {
                assertThrows(StegoFormatFailure::class.java) { StegoCarrierCodec.decode(bad) }
            }
        }
        for (bad in listOf("cover[ZIS1:ARQ]", "cover${StegoCarrierCodec.DENSE_START}x${StegoCarrierCodec.DENSE_END}",
                "cover[ZIS1:2:ARQ=]", "cover[ZIS1:2:ARR]", "cover[ZIS1:2:]", "ordinary text")) {
            assertThrows(StegoFormatFailure::class.java) { StegoCarrierCodec.decode(bad) }
        }
    }

    @Test fun `visible framing has a fixed interoperable column order`() {
        val bytes = ByteArray(8) { it.toByte() }
        val vector = "A[ZIS1:2:Awc]B[ZIS1:2:BAA]C[ZIS1:2:BQE]D[ZIS1:2:BgI]E"
        assertEquals(vector, StegoCarrierCodec.encode("ABCDE", bytes, StegoTransport.VISIBLE_BASE64))
        assertArrayEquals(bytes, StegoCarrierCodec.decode(vector).envelope)
        for (cover in listOf("👩🏽‍💻", "e\u0301", "葛\uDB40\uDD00", "क्‍ष")) {
            assertTrue(StegoCarrierCodec.encode(cover, bytes, StegoTransport.DENSE_UNICODE).endsWith(cover))
        }
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
