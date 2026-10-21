package dev.zeroinput.ime.ai.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAgentSafetyTest {
    @Test
    fun sensitiveValuesAreDetectedWithoutBlockingOrdinaryNavigationLabels() {
        assertTrue(DeviceAgentSafety.sensitiveValue("alice@example.com"))
        assertTrue(DeviceAgentSafety.sensitiveValue("Contact: user+tag@example.co.uk"))
        assertTrue(DeviceAgentSafety.sensitiveValue("https://example.com/private?id=123"))
        assertTrue(DeviceAgentSafety.sensitiveValue("www.example.com/path"))

        assertFalse(DeviceAgentSafety.sensitiveValue("Email"))
        assertFalse(DeviceAgentSafety.sensitiveValue("Open Settings"))
        assertFalse(DeviceAgentSafety.sensitiveValue("Example text without a domain"))
    }
}
