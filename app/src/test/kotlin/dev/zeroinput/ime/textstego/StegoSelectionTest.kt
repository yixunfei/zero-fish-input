package dev.zeroinput.ime.textstego

import dev.zeroinput.security.message.MessageCipher
import org.junit.Assert.*
import org.junit.Test

class StegoSelectionTest {
    @Test fun checkboxOrderAndRecheckingDetermineTheExactCascade() {
        val options = StegoOptions()
        options.select(MessageCipher.AES, false)
        assertTrue(options.stages.isEmpty())
        listOf(MessageCipher.SM4, MessageCipher.XCHACHA20, MessageCipher.AES).forEach { options.select(it, true) }
        options.select(MessageCipher.AES, true)
        assertEquals(listOf(MessageCipher.SM4, MessageCipher.XCHACHA20, MessageCipher.AES), options.stages.map { it.cipher })
        options.cycleWidth(MessageCipher.AES)
        assertEquals(128, options.stages.last().bits)
        options.moveEarlier(MessageCipher.AES)
        assertEquals(listOf(MessageCipher.SM4, MessageCipher.AES, MessageCipher.XCHACHA20), options.stages.map { it.cipher })
        options.select(MessageCipher.SM4, false)
        options.select(MessageCipher.SM4, true)
        assertEquals(listOf(MessageCipher.AES, MessageCipher.XCHACHA20, MessageCipher.SM4), options.stages.map { it.cipher })
        assertEquals(128, options.stages.first().bits)
    }
}
