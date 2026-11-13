package dev.zeroinput.ime.clipboard

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.zeroinput.ime.R
import dev.zeroinput.ime.clipboardguard.ClipboardDeviceTestSupport as Device
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardExternalImportTest {
    @Test
    fun nativeSelectionMenuInAnotherApplicationContainsThePrivateImportAction() {
        Device.startSource()
        try {
            Device.longPressPublicText()
            try {
                Device.await { Device.findText("Native ZeroInput text action available") != null }
            } catch (failure: AssertionError) {
                val statuses = listOf("Native ZeroInput text action unavailable", "Native selection ended",
                    "Native selection requested", "Native selection rejected", "Source ready", "Source inactive")
                val visible = statuses.filter { Device.findText(it) != null }
                throw AssertionError(visible.joinToString().ifEmpty { "Public source status is no longer visible" }, failure)
            }
        } finally { closeSource() }
    }

    @Test
    fun nativeSelectionMenuOffersPrivateImportWhenAnEditableFieldSelectsAll() {
        Device.startSource()
        try {
            Device.longPressPublicText("public-editable-fixture")
            try {
                Device.await("The native menu should include ZeroInput after full selection") {
                    Device.findText("Native ZeroInput text action available") != null
                }
            } catch (failure: AssertionError) {
                val statuses = listOf("Native ZeroInput text action unavailable", "Native selection ended",
                    "Native editable selection was partial", "Source ready", "Source inactive")
                throw AssertionError(statuses.filter { Device.findText(it) != null }.joinToString(), failure)
            }
        } finally { closeSource() }
    }

    @Test
    fun systemTextSharesheetFromAnotherApplicationCanOpenThePrivateImportReview() {
        Device.startSource()
        try {
            Device.click("Share public fixture")
            Device.click(Device.context.getString(R.string.copy_to_zeroinput))
            assertReviewHasNoPlaintext()
        } finally { closeSource() }
    }

    private fun closeSource() {
        Device.shell("input keyevent KEYCODE_BACK")
        Device.startSource()
        Device.closeSource()
    }

    private fun assertReviewHasNoPlaintext() {
        val authenticate = Device.context.getString(R.string.clipboard_import_authenticate)
        val enable = Device.context.getString(R.string.enable_secure_clipboard)
        Device.await { Device.findText(authenticate) != null || Device.findText(enable) != null }
        assertTrue(Device.findText("public guard fixture") == null)
        assertNotNull(Device.instrumentation.uiAutomation.rootInActiveWindow)
    }

}
