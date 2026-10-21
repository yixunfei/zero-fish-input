package dev.zeroinput.ime.ai.device

import dev.zeroinput.ai.api.DeviceAgentLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceStage6NotificationSanitizerTest {
    @Test
    fun dropsNotificationsCarryingBlockedSecrets() {
        assertNull(DeviceStage6NotificationSanitizer.sanitize("Verification code", "123456"))
        assertNull(DeviceStage6NotificationSanitizer.sanitize("Bank alert", "Payment received"))
        assertNull(DeviceStage6NotificationSanitizer.sanitize("Build finished", "A".repeat(600) + " password reset"))
        assertNull(DeviceStage6NotificationSanitizer.sanitize("New mail", "alice@example.com"))
        assertNull(DeviceStage6NotificationSanitizer.sanitize("Visit", "https://example.com/private"))
    }

    @Test
    fun boundsPlatformTextBeforeConversion() {
        val huge = object : CharSequence {
            override val length: Int = DeviceAgentLimits.MAX_ELEMENT_TEXT + 1
            override fun get(index: Int): Char = 'x'
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = "unused"
            override fun toString(): String = error("oversized text must not be converted")
        }
        assertNull(DeviceAgentSafety.boundedText(huge))

        val value = requireNotNull(DeviceStage6NotificationSanitizer.sanitize("A".repeat(1_000), "hello"))
        assertEquals(256, value.title.length)
        assertEquals("hello", value.text)
    }

    @Test
    fun keepsOrdinaryNotifications() {
        assertEquals(NotificationValue("Build finished", "All checks passed"),
            DeviceStage6NotificationSanitizer.sanitize("Build finished", "All checks passed"))
        assertNull(DeviceStage6NotificationSanitizer.sanitize("", ""))
    }

    @Test
    fun truncationNeverSplitsASurrogatePair() {
        val value = requireNotNull(
            DeviceStage6NotificationSanitizer.sanitize("A".repeat(255) + "\uD83D\uDE00", "hello"),
        )
        assertEquals(255, value.title.length)
        assertEquals(false, value.title.last().isHighSurrogate())
    }
}
