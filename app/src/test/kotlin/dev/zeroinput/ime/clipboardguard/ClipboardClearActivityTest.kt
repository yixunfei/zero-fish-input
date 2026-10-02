package dev.zeroinput.ime.clipboardguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardClearActivityTest {
    @Test
    fun confirmationRequiresResumedFocusedValidIdleActivity() {
        assertTrue(canConfirmClear(resumed = true, windowHasFocus = true, busy = false, valid = true))
        assertFalse(canConfirmClear(resumed = false, windowHasFocus = true, busy = false, valid = true))
        assertFalse(canConfirmClear(resumed = true, windowHasFocus = false, busy = false, valid = true))
        assertFalse(canConfirmClear(resumed = true, windowHasFocus = true, busy = true, valid = true))
        assertFalse(canConfirmClear(resumed = true, windowHasFocus = true, busy = false, valid = false))
    }
}
