package dev.zeroinput.ime.ai.device

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import dev.zeroinput.ai.api.AiAttachment
import dev.zeroinput.ai.api.AiLimits
import dev.zeroinput.ai.api.DeviceStage6Capability
import dev.zeroinput.ai.api.DeviceStage6Operation
import dev.zeroinput.ai.api.DeviceStage6OperationRequest
import dev.zeroinput.ai.api.DeviceStage6Result
import dev.zeroinput.ai.api.DeviceStage6ResultStatus
import dev.zeroinput.ime.ai.AiAttachmentReader

/** Android adapter for direct, target-scoped stage 6 operations. */
internal class AndroidDeviceStage6Platform(private val context: Context) : DeviceStage6Platform {
    private val resolver: ContentResolver = context.contentResolver

    override fun execute(
        request: DeviceStage6OperationRequest,
        maxAttachmentBytes: Int,
        stillValid: () -> Boolean,
    ): DeviceStage6Result {
        if (!stillValid()) return expired()
        return when (request.capability) {
            DeviceStage6Capability.FILES -> file(request, maxAttachmentBytes, stillValid)
            DeviceStage6Capability.MEDIA -> media(request, maxAttachmentBytes, stillValid)
            DeviceStage6Capability.SYSTEM_SETTINGS -> settings(request, stillValid)
            DeviceStage6Capability.NOTIFICATIONS -> notifications(request, stillValid)
            DeviceStage6Capability.SMS -> sms(request, stillValid)
            DeviceStage6Capability.TELEPHONY -> telephony(request, stillValid)
            DeviceStage6Capability.CONTACTS -> contacts(request, stillValid)
            DeviceStage6Capability.CALENDAR -> calendar(request, stillValid)
            DeviceStage6Capability.CAMERA -> if (hasPermission(Manifest.permission.CAMERA)) {
                DeviceStage6SensorCapture.camera(context, maxAttachmentBytes) {
                    stillValid() && hasPermission(Manifest.permission.CAMERA)
                }
            } else rejected("Camera permission is unavailable")
            DeviceStage6Capability.MICROPHONE -> if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
                DeviceStage6SensorCapture.microphone(context, maxAttachmentBytes) {
                    stillValid() && hasPermission(Manifest.permission.RECORD_AUDIO)
                }
            } else rejected("Microphone permission is unavailable")
        }
    }

    private fun file(request: DeviceStage6OperationRequest, maxAttachmentBytes: Int, stillValid: () -> Boolean): DeviceStage6Result {
        val uri = contentUri(request) ?: return rejected("A user-granted content URI is required")
        return when (request.operation) {
            DeviceStage6Operation.READ -> readAttachment(uri, maxAttachmentBytes, stillValid)
            DeviceStage6Operation.DELETE -> if (stillValid()) delete(uri) else expired()
            DeviceStage6Operation.CREATE,
            DeviceStage6Operation.UPDATE -> rejected("File writes require a user-selected destination")
            else -> rejected("Unsupported file operation")
        }
    }

    private fun media(request: DeviceStage6OperationRequest, maxAttachmentBytes: Int, stillValid: () -> Boolean): DeviceStage6Result {
        val uri = contentUri(request) ?: return rejected("A target media URI is required")
        if (!isMediaUri(uri)) return rejected("Target is not a media item")
        return when (request.operation) {
            DeviceStage6Operation.READ -> readAttachment(uri, maxAttachmentBytes, stillValid)
            DeviceStage6Operation.DELETE -> if (stillValid()) delete(uri) else expired()
            else -> rejected("Unsupported media operation")
        }
    }

    private fun readAttachment(uri: Uri, maxAttachmentBytes: Int, stillValid: () -> Boolean): DeviceStage6Result {
        if (!stillValid()) return expired()
        return try {
            val attachment = AiAttachmentReader.read(resolver, uri, maxBytes = maxAttachmentBytes, stillValid = stillValid)
            if (!stillValid()) {
                attachment.bytes.fill(0)
                expired()
            } else DeviceStage6Result(DeviceStage6ResultStatus.SUCCESS, "Selected data is ready", attachment)
        } catch (_: java.util.concurrent.CancellationException) {
            expired()
        } catch (_: Exception) {
            DeviceStage6Result(DeviceStage6ResultStatus.FAILED, "Selected data could not be read")
        }
    }

    private fun settings(request: DeviceStage6OperationRequest, stillValid: () -> Boolean): DeviceStage6Result {
        val key = request.fields["key"]?.takeIf { it in SAFE_SYSTEM_KEYS } ?: return rejected("System setting is not allowed")
        if (request.operation == DeviceStage6Operation.READ) {
            if (!stillValid()) return expired()
            val value = Settings.System.getString(resolver, key) ?: return success("Setting is unset")
            return success("Setting $key is set to ${value.take(MAX_SETTING_VALUE)}")
        }
        if (request.operation != DeviceStage6Operation.MODIFY || !Settings.System.canWrite(context)) {
            return rejected("System settings write access is unavailable")
        }
        val value = request.fields["value"]?.takeIf { it.length <= MAX_SETTING_VALUE }
            ?: return rejected("Setting value is invalid")
        if (!validSettingValue(key, value)) return rejected("Setting value is invalid")
        if (!stillValid()) return expired()
        return if (Settings.System.putString(resolver, key, value)) success("System setting changed")
        else failed("System setting was not changed")
    }

    private fun validSettingValue(key: String, value: String): Boolean =
        dev.zeroinput.ai.api.DeviceStage6ToolCatalog.validSettingValue(key, value)

    private fun notifications(request: DeviceStage6OperationRequest, stillValid: () -> Boolean): DeviceStage6Result {
        if (request.operation != DeviceStage6Operation.READ) return rejected("Notification actions are read-only")
        val packageName = request.target.packageName ?: return rejected("Notification package is required")
        if (!stillValid()) return expired()
        if (!hasNotificationAccess()) return rejected("Notification access is unavailable")
        val values = DeviceStage6NotificationStore.findForPackage(packageName)
        if (!stillValid()) return expired()
        if (values.isEmpty()) return failed("No current notifications were found for this package")
        return success(values.joinToString("\n") { "${it.title} ${it.text}" }.take(DeviceStage6ResultLimit))
    }

    private fun sms(request: DeviceStage6OperationRequest, stillValid: () -> Boolean): DeviceStage6Result {
        return when (request.operation) {
            DeviceStage6Operation.READ -> {
                if (!stillValid()) return expired()
                if (!hasPermission(Manifest.permission.READ_SMS)) return rejected("SMS read permission is unavailable")
                val address = request.target.id.takeIf(String::isNotBlank) ?: return rejected("SMS phone number is required")
                val result = resolver.query(Telephony.Sms.CONTENT_URI, SMS_PROJECTION,
                    "${Telephony.Sms.ADDRESS}=?", arrayOf(address), "${Telephony.Sms.DATE} DESC")?.use { cursor ->
                    if (!cursor.moveToFirst()) return failed("SMS is unavailable")
                    success("SMS ${cursor.getString(1)}: ${cursor.getString(2)}".take(DeviceStage6ResultLimit))
                } ?: failed("SMS is unavailable")
                if (!stillValid()) expired() else result
            }
            DeviceStage6Operation.SEND -> {
            if (!hasPermission(Manifest.permission.SEND_SMS)) return rejected("SMS send permission is unavailable")
                val address = request.target.id.takeIf(String::isNotBlank) ?: return rejected("SMS address is required")
                val body = request.fields["body"]?.takeIf(String::isNotBlank) ?: return rejected("SMS body is required")
                if (body.length > AiLimits.MAX_INPUT_CHARS) return rejected("SMS body is too long")
                if (!stillValid()) return expired()
            val smsManager = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)
                else @Suppress("DEPRECATION") SmsManager.getDefault()
            smsManager?.sendTextMessage(address, null, body, null, null)
                ?: return unavailable("SMS service is unavailable")
                success("SMS send requested")
            }
            DeviceStage6Operation.DELETE -> rejected("SMS deletion is not enabled")
            else -> rejected("Unsupported SMS operation")
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun telephony(request: DeviceStage6OperationRequest, stillValid: () -> Boolean): DeviceStage6Result {
        return when (request.operation) {
        DeviceStage6Operation.READ -> {
            if (!stillValid()) return expired()
            if (!hasPermission(Manifest.permission.READ_CALL_LOG)) return rejected("Call log permission is unavailable")
            val number = request.target.id.takeIf(String::isNotBlank) ?: return rejected("Call phone number is required")
            val result = resolver.query(CallLog.Calls.CONTENT_URI, CALL_PROJECTION,
                "${CallLog.Calls.NUMBER}=?", arrayOf(number), "${CallLog.Calls.DATE} DESC")?.use { cursor ->
                if (!cursor.moveToFirst()) failed("Call is unavailable")
                else success("Call ${cursor.getString(1)} ${cursor.getLong(2)}".take(DeviceStage6ResultLimit))
            } ?: failed("Call is unavailable")
            if (!stillValid()) expired() else result
        }
        DeviceStage6Operation.SEND -> {
            if (!hasPermission(Manifest.permission.CALL_PHONE)) return rejected("Call permission is unavailable")
            val number = request.target.id.takeIf(String::isNotBlank) ?: return rejected("Call number is required")
            if (!stillValid()) return expired()
            val telecom = context.getSystemService(TelecomManager::class.java) ?: return unavailable("Telephony service unavailable")
            return try {
                telecom.placeCall(Uri.fromParts("tel", number, null), null)
                success("Call request started")
            } catch (_: SecurityException) {
                rejected("Call permission is unavailable")
            }
        }
        else -> rejected("Unsupported phone operation")
        }
    }

    private fun contacts(request: DeviceStage6OperationRequest, stillValid: () -> Boolean): DeviceStage6Result {
        return when (request.operation) {
            DeviceStage6Operation.READ -> readContact(request, stillValid)
            DeviceStage6Operation.CREATE -> createContact(request, stillValid)
            DeviceStage6Operation.UPDATE -> updateContact(request, stillValid)
            DeviceStage6Operation.DELETE -> deleteContact(request, stillValid)
            else -> rejected("Unsupported contact operation")
        }
    }

    private fun readContact(request: DeviceStage6OperationRequest, stillValid: () -> Boolean): DeviceStage6Result {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return rejected("Contact read permission is unavailable")
        val displayName = request.target.id.takeIf(String::isNotBlank) ?: return rejected("Contact name is required")
        if (!stillValid()) return expired()
        val contact = findContact(displayName) ?: return failed("Contact is unavailable or ambiguous")
        val id = contact.first
        val name = contact.second
        val phones = readContactValues(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.CONTACT_ID, id)
        val emails = readContactValues(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            ContactsContract.CommonDataKinds.Email.ADDRESS, ContactsContract.CommonDataKinds.Email.CONTACT_ID, id)
        if (!stillValid()) return expired()
        return success((listOf("Contact $name") + phones.map { "Phone $it" } + emails.map { "Email $it" })
            .joinToString("; ").take(DeviceStage6ResultLimit))
    }

    private fun readContactValues(uri: Uri, column: String, idColumn: String, id: Long): List<String> {
        val values = mutableListOf<String>()
        resolver.query(uri, arrayOf(column), "$idColumn=?", arrayOf(id.toString()), null)?.use { cursor ->
            while (cursor.moveToNext() && values.size < MAX_CONTACT_VALUES) {
                cursor.getString(0)?.take(MAX_CONTACT_VALUE_CHARS)?.takeIf(String::isNotBlank)?.let(values::add)
            }
        }
        return values
    }

    private fun createContact(request: DeviceStage6OperationRequest, stillValid: () -> Boolean): DeviceStage6Result {
        if (!hasPermission(Manifest.permission.WRITE_CONTACTS)) return rejected("Contact write permission is unavailable")
        val name = request.fields["display_name"]?.takeIf(String::isNotBlank) ?: return rejected("Contact name is required")
        if (!stillValid()) return expired()
        val operations = arrayListOf(
            android.content.ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI).build(),
            android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, name).build(),
        )
        request.fields["phone"]?.takeIf(String::isNotBlank)?.let { phone ->
            operations += android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone).build()
        }
        request.fields["email"]?.takeIf(String::isNotBlank)?.let { email ->
            operations += android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email).build()
        }
        if (!stillValid()) return expired()
        resolver.applyBatch(ContactsContract.AUTHORITY, operations)
        return success("Contact created")
    }

    private fun updateContact(request: DeviceStage6OperationRequest, stillValid: () -> Boolean): DeviceStage6Result {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return rejected("Contact read permission is unavailable")
        if (!hasPermission(Manifest.permission.WRITE_CONTACTS)) return rejected("Contact write permission is unavailable")
        val contactName = request.target.id.takeIf(String::isNotBlank) ?: return rejected("Contact name is required")
        val newName = request.fields["new_display_name"]?.takeIf(String::isNotBlank) ?: return rejected("New contact name is required")
        if (!stillValid()) return expired()
        val contact = findContact(contactName) ?: return failed("Contact is unavailable or ambiguous")
        val rawId = findRawContact(contact.first) ?: return failed("Contact is unavailable or ambiguous")
        if (!stillValid()) return expired()
        val values = android.content.ContentValues().apply { put(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, newName) }
        val selection = "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?"
        val args = arrayOf(rawId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
        val changed = resolver.update(ContactsContract.Data.CONTENT_URI, values, selection, args)
        if (changed > 0) return success("Contact changed")
        val inserted = resolver.insert(ContactsContract.Data.CONTENT_URI, android.content.ContentValues().apply {
            put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
            put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            put(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, newName)
        })
        return if (inserted != null) success("Contact changed") else failed("Contact was not changed")
    }

    private fun deleteContact(request: DeviceStage6OperationRequest, stillValid: () -> Boolean): DeviceStage6Result {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) return rejected("Contact read permission is unavailable")
        if (!hasPermission(Manifest.permission.WRITE_CONTACTS)) return rejected("Contact write permission is unavailable")
        val contactName = request.target.id.takeIf(String::isNotBlank) ?: return rejected("Contact name is required")
        if (!stillValid()) return expired()
        val contact = findContact(contactName) ?: return failed("Contact is unavailable or ambiguous")
        val rawId = findRawContact(contact.first) ?: return failed("Contact is unavailable or ambiguous")
        if (!stillValid()) return expired()
        val deleted = resolver.delete(ContactsContract.RawContacts.CONTENT_URI,
            "${ContactsContract.RawContacts._ID}=?", arrayOf(rawId.toString()))
        return if (deleted == 1) success("Selected contact entry deleted") else failed("Contact was not deleted")
    }

    private fun findContact(displayName: String): Pair<Long, String>? {
        val matches = resolver.query(ContactsContract.Contacts.CONTENT_URI, CONTACT_PROJECTION,
            "${ContactsContract.Contacts.DISPLAY_NAME}=?", arrayOf(displayName), null)?.use { cursor ->
            buildList { while (cursor.moveToNext() && size < 2) add(cursor.getLong(0) to cursor.getString(1)) }
        }.orEmpty()
        return matches.singleOrNull()
    }

    private fun findRawContact(contactId: Long): Long? = resolver.query(
        ContactsContract.RawContacts.CONTENT_URI,
        arrayOf(ContactsContract.RawContacts._ID),
        "${ContactsContract.RawContacts.CONTACT_ID}=?",
        arrayOf(contactId.toString()),
        "${ContactsContract.RawContacts._ID} ASC",
    )?.use { cursor ->
        buildList { while (cursor.moveToNext() && size < 2) add(cursor.getLong(0)) }.singleOrNull()
    }

    private fun calendar(request: DeviceStage6OperationRequest, stillValid: () -> Boolean): DeviceStage6Result {
        return when (request.operation) {
            DeviceStage6Operation.READ -> {
                if (!stillValid()) return expired()
                if (!hasPermission(Manifest.permission.READ_CALENDAR)) return rejected("Calendar read permission is unavailable")
                val event = findCalendarEvent(request.target.id, request.fields["start_epoch_ms"]?.toLongOrNull())
                    ?: return failed("Calendar event is unavailable or ambiguous")
                val result = success("Calendar event ${event.title} from ${event.start} to ${event.end}".take(DeviceStage6ResultLimit))
                if (!stillValid()) expired() else result
            }
            DeviceStage6Operation.CREATE -> createCalendarEvent(request, stillValid)
            DeviceStage6Operation.UPDATE -> {
                if (!hasPermission(Manifest.permission.READ_CALENDAR)) return rejected("Calendar read permission is unavailable")
                if (!hasPermission(Manifest.permission.WRITE_CALENDAR)) return rejected("Calendar write permission is unavailable")
                val event = findCalendarEvent(request.target.id, request.fields["start_epoch_ms"]?.toLongOrNull())
                    ?: return failed("Calendar event is unavailable or ambiguous")
                val values = calendarUpdateValues(request.fields) ?: return rejected("Calendar update fields are invalid")
                if (!stillValid()) return expired()
                val changed = resolver.update(CalendarContract.Events.CONTENT_URI, values,
                    "${CalendarContract.Events._ID}=?", arrayOf(event.id.toString()))
                if (changed == 1) success("Calendar event changed") else failed("Calendar event was not changed")
            }
            DeviceStage6Operation.DELETE -> {
                if (!hasPermission(Manifest.permission.READ_CALENDAR)) return rejected("Calendar read permission is unavailable")
                if (!hasPermission(Manifest.permission.WRITE_CALENDAR)) return rejected("Calendar write permission is unavailable")
                if (!stillValid()) return expired()
                val event = findCalendarEvent(request.target.id, request.fields["start_epoch_ms"]?.toLongOrNull())
                    ?: return failed("Calendar event is unavailable or ambiguous")
                if (!stillValid()) return expired()
                val removed = resolver.delete(CalendarContract.Events.CONTENT_URI,
                    "${CalendarContract.Events._ID}=?", arrayOf(event.id.toString()))
                if (removed == 1) success("Calendar event deleted") else failed("Calendar event was not deleted")
            }
            else -> rejected("Unsupported calendar operation")
        }
    }

    private fun createCalendarEvent(request: DeviceStage6OperationRequest, stillValid: () -> Boolean): DeviceStage6Result {
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) return rejected("Calendar read permission is unavailable")
        if (!hasPermission(Manifest.permission.WRITE_CALENDAR)) return rejected("Calendar write permission is unavailable")
        val calendarName = request.target.id.takeIf(String::isNotBlank) ?: return rejected("Calendar name is required")
        val calendarId = findCalendarId(calendarName) ?: return failed("Calendar is unavailable or ambiguous")
        val values = calendarValues(request.fields, requireTitle = true) ?: return rejected("Calendar event fields are invalid")
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId)
        if (!stillValid()) return expired()
        val uri = resolver.insert(CalendarContract.Events.CONTENT_URI, values)
        return if (uri != null) success("Calendar event created") else failed("Calendar event was not created")
    }

    private fun findCalendarEvent(title: String, start: Long?): CalendarEventValue? {
        if (title.isBlank() || start == null || start <= 0L) return null
        val matches = resolver.query(
            CalendarContract.Events.CONTENT_URI,
            CALENDAR_PROJECTION,
            "${CalendarContract.Events.TITLE}=? AND ${CalendarContract.Events.DTSTART}=?",
            arrayOf(title, start.toString()),
            null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext() && size < 2) {
                    add(CalendarEventValue(cursor.getLong(0), cursor.getString(1), cursor.getLong(2), cursor.getLong(3)))
                }
            }
        }.orEmpty()
        return matches.singleOrNull()
    }

    private fun findCalendarId(displayName: String): Long? {
        val matches = resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME}=? AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?",
            arrayOf(displayName, CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null,
        )?.use { cursor -> buildList { while (cursor.moveToNext() && size < 2) add(cursor.getLong(0)) } }.orEmpty()
        return matches.singleOrNull()
    }

    private fun calendarValues(fields: Map<String, String>, requireTitle: Boolean): android.content.ContentValues? {
        val title = fields["title"]?.takeIf(String::isNotBlank)
        if (requireTitle && title == null) return null
        val start = fields["start_epoch_ms"]?.toLongOrNull()
        val end = fields["end_epoch_ms"]?.toLongOrNull()
        if ((start == null) != (end == null)) return null
        if (start != null && end != null && (end <= start || end - start > MAX_EVENT_DURATION_MS)) return null
        val description = fields["description"]?.takeIf(String::isNotBlank)
        if (title == null && start == null && description == null) return null
        return android.content.ContentValues().apply {
            title?.let { put(CalendarContract.Events.TITLE, it.take(128)) }
            if (start != null && end != null) {
                put(CalendarContract.Events.DTSTART, start)
                put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            }
            description?.let { put(CalendarContract.Events.DESCRIPTION, it.take(256)) }
        }
    }

    private fun calendarUpdateValues(fields: Map<String, String>): android.content.ContentValues? {
        val updateFields = buildMap {
            fields["new_title"]?.let { put("title", it) }
            fields["new_start_epoch_ms"]?.let { put("start_epoch_ms", it) }
            fields["new_end_epoch_ms"]?.let { put("end_epoch_ms", it) }
            fields["description"]?.let { put("description", it) }
        }
        return calendarValues(updateFields, requireTitle = false)
    }

    private fun contentUri(request: DeviceStage6OperationRequest): Uri? {
        val target = request.target.id
        val selected = request.fields["selected_uri"]
        if (selected != null && selected != target) return null
        return target.toUri().takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
    }

    private fun delete(uri: Uri): DeviceStage6Result = try {
        if (resolver.delete(uri, null, null) == 1) success("Selected item deleted") else failed("Selected item was not deleted")
    } catch (_: SecurityException) { rejected("Delete permission is unavailable") }

    private fun isMediaUri(uri: Uri): Boolean = resolver.getType(uri)?.lowercase()?.let {
        it.startsWith("image/") || it.startsWith("audio/") || it.startsWith("video/")
    } == true

    private fun hasNotificationAccess(): Boolean = runCatching {
        android.provider.Settings.Secure.getString(resolver, "enabled_notification_listeners")
            ?.split(':')?.any { ComponentName.unflattenFromString(it)?.packageName == context.packageName } == true
    }.getOrDefault(false)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun success(value: String) = DeviceStage6Result(DeviceStage6ResultStatus.SUCCESS, value)
    private fun expired() = DeviceStage6Result(DeviceStage6ResultStatus.EXPIRED, "Task grant expired")
    private fun failed(value: String) = DeviceStage6Result(DeviceStage6ResultStatus.FAILED, value)
    private fun rejected(value: String) = DeviceStage6Result(DeviceStage6ResultStatus.REJECTED, value)
    private fun unavailable(value: String) = DeviceStage6Result(DeviceStage6ResultStatus.UNAVAILABLE, value)

    private companion object {
        const val MAX_SETTING_VALUE = 128
        const val MAX_CONTACT_VALUES = 3
        const val MAX_CONTACT_VALUE_CHARS = 128
        const val MAX_EVENT_DURATION_MS = 24 * 60 * 60 * 1_000L
        const val DeviceStage6ResultLimit = 2_048
        val SAFE_SYSTEM_KEYS = setOf(
            Settings.System.SCREEN_BRIGHTNESS,
            Settings.System.SCREEN_OFF_TIMEOUT,
            Settings.System.ACCELEROMETER_ROTATION,
            Settings.System.SOUND_EFFECTS_ENABLED,
        )
        val SMS_PROJECTION = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY)
        val CALL_PROJECTION = arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.DATE)
        val CONTACT_PROJECTION = arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME)
        val CALENDAR_PROJECTION = arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND)
    }

    private data class CalendarEventValue(val id: Long, val title: String, val start: Long, val end: Long)
}
