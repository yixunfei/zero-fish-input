package dev.zeroinput.ime.ai.device

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceAgentRoleLabelTest {
    @Test
    fun emptyAndMissingClassNamesFallBackToView() {
        assertEquals("View", DeviceAgentSafety.roleLabel(null))
        assertEquals("View", DeviceAgentSafety.roleLabel(""))
        assertEquals("View", DeviceAgentSafety.roleLabel("."))
    }

    @Test
    fun qualifiedNamesAreBoundedToTheSimpleClassName() {
        assertEquals("Button", DeviceAgentSafety.roleLabel("android.widget.Button"))
        assertEquals("View$1", DeviceAgentSafety.roleLabel("android.view.View$1"))
        assertEquals(128, DeviceAgentSafety.roleLabel("a".repeat(200)).length)
    }
}
