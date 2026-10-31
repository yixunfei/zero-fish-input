package dev.zeroinput.ime.clipboard

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.UUID

internal data class ClipboardImportDraft(val request: ClipboardImportRequest, val generation: Long)

/** Main-thread, one-use handoff to a nonexported page. No text crosses an Intent. */
internal class ClipboardSelectionTransfer(private val clock: () -> Long = SystemClock::elapsedRealtime) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private var token: String? = null
    private var draft: ClipboardImportDraft? = null
    private var expiresAt = 0L
    private val expire = Runnable { close() }

    fun offer(value: ClipboardImportDraft): String {
        close()
        draft = value
        expiresAt = clock() + 5_000L
        return UUID.randomUUID().toString().also {
            token = it
            main.postDelayed(expire, 5_000L)
        }
    }

    fun take(id: String?): ClipboardImportDraft? {
        // Handler delivery can be delayed by a busy main thread or device sleep.
        if (draft != null && clock() >= expiresAt) close()
        if (id == null || id != token) return null
        val value = draft
        draft = null
        close()
        return value
    }

    override fun close() {
        main.removeCallbacks(expire)
        token = null
        expiresAt = 0L
        draft?.request?.close()
        draft = null
    }
}
