package dev.zeroinput.ai.api

import org.junit.Assert.*
import org.junit.Test

class DeviceAgentModelsTest {
    private val bounds = DeviceBounds(0, 0, 100, 100)
    private val element = DeviceElement(
        ref = "0.0", role = "Button", label = "Continue", text = "Continue", bounds = bounds,
        clickable = true, longClickable = false, scrollable = false, editable = false,
        password = false, sensitive = false, enabled = true,
    )
    private val snapshot = DeviceScreenSnapshot("com.example.app", 1, "snapshot-1", listOf(element))

    @Test fun policyDefaultsClosedAndSensitiveTextCannotBeRepresented() {
        assertFalse(DeviceAgentPolicy().enabled)
        assertThrows(IllegalArgumentException::class.java) {
            element.copy(sensitive = true, text = "secret")
        }
    }

    @Test fun snapshotsRejectDuplicateRefsAndOversizedScreenshots() {
        assertThrows(IllegalArgumentException::class.java) {
            DeviceScreenSnapshot("com.example.app", 1, "s", listOf(element, element))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DeviceScreenSnapshot("com.example.app", 1, "s", listOf(element), screenshotJpeg = ByteArray(DeviceAgentLimits.MAX_SCREENSHOT_BYTES + 1))
        }
    }

    @Test fun callsAreBoundedAndRedactedInDiagnostics() {
        val call = DeviceActionCall("call-1", "click", snapshot.snapshotId, mapOf("element_ref" to element.ref), 0)
        assertFalse(call.toString().contains(element.label!!))
        assertThrows(IllegalArgumentException::class.java) {
            call.copy(arguments = mapOf("unknown" to "x".repeat(DeviceAgentLimits.MAX_ARGUMENT_CHARS + 1)))
        }
    }
}
