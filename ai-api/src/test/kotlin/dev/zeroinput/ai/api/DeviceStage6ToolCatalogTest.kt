package dev.zeroinput.ai.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStage6ToolCatalogTest {
    @Test
    fun everyStage6ToolIsUniqueHighRiskAndMappedToAnAllowedOperation() {
        val names = DeviceStage6ToolCatalog.entries.map { it.definition.name }

        assertEquals(names.size, names.toSet().size)
        assertEquals(DeviceStage6Catalog.operations.values.sumOf { it.size }, names.size)
        DeviceStage6ToolCatalog.entries.forEach { entry ->
            assertEquals(DeviceActionRisk.HIGH, entry.definition.risk)
            assertTrue(entry.definition.requiresApproval)
            assertTrue(DeviceStage6Catalog.permits(entry.capability, entry.operation))
            assertNotNull(DeviceActionCatalog.find(entry.definition.name))
        }
    }

    @Test
    fun smsAndPhoneNumbersRejectInvalidArguments() {
        val send = requireNotNull(DeviceStage6ToolCatalog.find("send_sms"))
        assertTrue(DeviceActionArguments.valid(send.definition, mapOf("phone_number" to "+852 6123 4567", "body" to "Hello")))
        assertFalse(DeviceActionArguments.valid(send.definition, mapOf("phone_number" to "not-a-number", "body" to "Hello")))
        assertFalse(DeviceActionArguments.valid(send.definition, mapOf("phone_number" to "1234", "body" to " ")))
    }

    @Test
    fun providerIdsAndContactFieldsMatchThePlatformContract() {
        val updateContact = requireNotNull(DeviceStage6ToolCatalog.find("update_contact"))
        val deleteContact = requireNotNull(DeviceStage6ToolCatalog.find("delete_contact"))
        val createEvent = requireNotNull(DeviceStage6ToolCatalog.find("create_calendar_event"))
        val updateEvent = requireNotNull(DeviceStage6ToolCatalog.find("update_calendar_event"))

        assertTrue(DeviceActionArguments.valid(updateContact.definition, mapOf("display_name" to "Alice", "new_display_name" to "New name")))
        assertFalse(DeviceActionArguments.valid(updateContact.definition, mapOf("display_name" to "Alice")))
        assertTrue(DeviceActionArguments.valid(deleteContact.definition, mapOf("display_name" to "Alice")))
        assertTrue(DeviceActionArguments.valid(createEvent.definition, mapOf(
            "calendar_name" to "Personal", "title" to "Review", "start_epoch_ms" to "1000", "end_epoch_ms" to "2000",
        )))
        assertFalse(DeviceActionArguments.valid(createEvent.definition, mapOf(
            "calendar_name" to "Personal", "title" to "Review", "start_epoch_ms" to "2000", "end_epoch_ms" to "1000",
        )))
        assertTrue(DeviceActionArguments.valid(updateEvent.definition, mapOf("title" to "Review", "start_epoch_ms" to "1000", "new_title" to "Updated")))
        assertFalse(DeviceActionArguments.valid(updateEvent.definition, mapOf("title" to "Review", "start_epoch_ms" to "1000")))
    }

    @Test
    fun selectedContentToolsDoNotAcceptModelSuppliedUris() {
        val readFile = requireNotNull(DeviceStage6ToolCatalog.find("read_selected_file"))
        val deleteMedia = requireNotNull(DeviceStage6ToolCatalog.find("delete_selected_media"))

        assertTrue(DeviceActionArguments.valid(readFile.definition, emptyMap()))
        assertFalse(DeviceActionArguments.valid(readFile.definition, mapOf("uri" to "content://provider/item")))
        assertTrue(DeviceActionArguments.valid(deleteMedia.definition, emptyMap()))
    }
}
