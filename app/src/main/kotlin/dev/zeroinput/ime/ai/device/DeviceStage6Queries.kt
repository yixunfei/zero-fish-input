package dev.zeroinput.ime.ai.device

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException

/** Owns one signal for all provider lookups within a single approved operation. */
internal class DeviceStage6Queries(
    private val resolver: ContentResolver,
    cancellation: DeviceStage6Cancellation,
    private val stillValid: () -> Boolean,
) : AutoCloseable {
    private val signal = CancellationSignal()
    private val registration = cancellation.register { signal.cancel() }

    fun query(
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? {
        if (!stillValid()) throw OperationCanceledException()
        signal.throwIfCanceled()
        return resolver.query(uri, projection, selection, selectionArgs, sortOrder, signal)
    }

    override fun close() = registration.close()
}
