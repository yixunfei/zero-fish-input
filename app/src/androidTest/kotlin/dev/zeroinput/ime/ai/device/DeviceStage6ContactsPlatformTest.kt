package dev.zeroinput.ime.ai.device

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import androidx.test.platform.app.InstrumentationRegistry
import dev.zeroinput.ai.api.DeviceStage6Capability
import dev.zeroinput.ai.api.DeviceStage6Operation
import dev.zeroinput.ai.api.DeviceStage6OperationRequest
import dev.zeroinput.ai.api.DeviceStage6ResultStatus
import dev.zeroinput.ai.api.DeviceStage6Target
import org.junit.Assert.*
import org.junit.Test

/** Only creates and removes this test's synthetic local contacts. */
class DeviceStage6ContactsPlatformTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver

    @Test fun fullDisplayNameReplacesAnExistingFamilyNameAndCanBeReadByTheNewName() = withContacts { rawIds ->
        val token = "ZeroInputFixture${System.nanoTime()}"
        val rawId = insertContact(token, "OldFamily").also(rawIds::add)
        val oldName = displayName(rawId)
        val newName = "$token NewFamily"
        val result = execute(DeviceStage6Operation.UPDATE, oldName, mapOf("new_display_name" to newName))
        assertEquals(DeviceStage6ResultStatus.SUCCESS, result.status)
        assertEquals(newName, displayName(rawId))
        assertEquals(DeviceStage6ResultStatus.SUCCESS, execute(DeviceStage6Operation.READ, newName).status)
        assertEquals(DeviceStage6ResultStatus.FAILED, execute(DeviceStage6Operation.READ, oldName).status)
    }

    @Test fun aggregatedContactIsRejectedWithoutChangingEitherRawContact() = withContacts { rawIds ->
        val name = "ZeroInputAggregate${System.nanoTime()}"
        val first = insertContact(name, "Fixture").also(rawIds::add)
        val second = insertContact(name, "Fixture").also(rawIds::add)
        resolver.update(ContactsContract.AggregationExceptions.CONTENT_URI, ContentValues().apply {
            put(ContactsContract.AggregationExceptions.TYPE, ContactsContract.AggregationExceptions.TYPE_KEEP_TOGETHER)
            put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID1, first)
            put(ContactsContract.AggregationExceptions.RAW_CONTACT_ID2, second)
        }, null, null)
        assertEquals(contactId(first), contactId(second))
        val original = displayName(first)
        for (operation in listOf(DeviceStage6Operation.UPDATE, DeviceStage6Operation.DELETE)) {
            val result = execute(operation, original, mapOf("new_display_name" to "$name Changed"))
            assertEquals(DeviceStage6ResultStatus.REJECTED, result.status)
            assertTrue(result.summary.contains("multiple raw contacts"))
            assertEquals(original, displayName(first))
            assertEquals(original, displayName(second))
        }
    }

    private fun withContacts(action: (MutableList<Long>) -> Unit) {
        val rawIds = mutableListOf<Long>()
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        try { action(rawIds) } finally {
            try {
                rawIds.forEach { id -> resolver.delete(ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, id), null, null) }
            } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        }
    }

    private fun insertContact(given: String, family: String): Long {
        val uri = checkNotNull(resolver.insert(ContactsContract.RawContacts.CONTENT_URI, ContentValues().apply {
            putNull(ContactsContract.RawContacts.ACCOUNT_NAME)
            putNull(ContactsContract.RawContacts.ACCOUNT_TYPE)
        }))
        val rawId = ContentUris.parseId(uri)
        try {
            checkNotNull(resolver.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                put(ContactsContract.Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                put(StructuredName.GIVEN_NAME, given)
                put(StructuredName.FAMILY_NAME, family)
            }))
            return rawId
        } catch (failure: Throwable) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    private fun contactId(rawId: Long): Long = checkNotNull(resolver.query(
        ContentUris.withAppendedId(ContactsContract.RawContacts.CONTENT_URI, rawId),
        arrayOf(ContactsContract.RawContacts.CONTACT_ID), null, null, null,
    )).use { check(it.moveToFirst()); it.getLong(0) }

    private fun displayName(rawId: Long): String = checkNotNull(resolver.query(
        ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId(rawId)),
        arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null,
    )).use { check(it.moveToFirst()); it.getString(0) }

    private fun execute(operation: DeviceStage6Operation, name: String, fields: Map<String, String> = emptyMap()) =
        AndroidDeviceStage6Platform(context).execute(DeviceStage6OperationRequest(
            "contacts-test", 1, context.packageName, DeviceStage6Capability.CONTACTS, operation,
            DeviceStage6Target(DeviceStage6Capability.CONTACTS, name), fields,
        ), 0, { true }, DeviceStage6Cancellation())
}
