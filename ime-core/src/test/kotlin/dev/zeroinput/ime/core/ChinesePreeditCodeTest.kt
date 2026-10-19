package dev.zeroinput.ime.core

import org.junit.Assert.*
import org.junit.Test

class ChinesePreeditCodeTest {
    @Test fun acceptsAllSupportedChineseInputCodesForDraftHandoff() {
        for (value in listOf("ni'hao", "64426", "n;hk")) assertTrue(ChinesePreeditCode.accepts(value))
        for (value in listOf("", "你好", "ni hao", "a0", "a1", "ni\n")) assertFalse(ChinesePreeditCode.accepts(value))
    }
}
