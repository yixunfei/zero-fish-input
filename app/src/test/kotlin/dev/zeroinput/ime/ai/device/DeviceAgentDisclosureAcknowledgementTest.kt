package dev.zeroinput.ime.ai.device

import android.widget.Button
import android.widget.CheckBox
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DeviceAgentDisclosureAcknowledgementTest {
    @Test
    fun confirmationTracksAcknowledgementChangesAfterBinding() {
        val context = RuntimeEnvironment.getApplication()
        val acknowledgement = CheckBox(context)
        val confirmation = Button(context)

        DeviceAgentDisclosureAcknowledgement.bind(acknowledgement, confirmation)
        assertFalse(confirmation.isEnabled)

        acknowledgement.isChecked = true
        assertTrue(confirmation.isEnabled)

        acknowledgement.isChecked = false
        assertFalse(confirmation.isEnabled)
    }
}
