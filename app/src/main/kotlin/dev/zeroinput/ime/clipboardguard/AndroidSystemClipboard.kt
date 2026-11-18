package dev.zeroinput.ime.clipboardguard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.KeyguardManager
import android.os.Build

/** The only production system-clipboard adapter. It never requests the payload. */
internal class AndroidSystemClipboard(private val context: Context) : SystemClipboardPort {
    private val manager = context.applicationContext.getSystemService(ClipboardManager::class.java)
    private val ownEmpty = OwnEmptyClipboardStamp()

    override fun timestamp(): Long? {
        checkUnlocked()
        return try {
            val timestamp = manager.primaryClipDescription?.timestamp
            timestamp?.takeUnless { ownEmpty.hides(it, android.os.SystemClock.elapsedRealtime()) }
        } catch (_: SecurityException) { throw ClipboardAccessException(ClipboardAccessIssue.READ_DENIED) }
    }

    override fun clear() {
        checkUnlocked()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.clearPrimaryClip()
            } else {
                val started = System.currentTimeMillis()
                manager.setPrimaryClip(ClipData.newPlainText("", ""))
                val ended = System.currentTimeMillis()
                // Never learn a later replacement from the next callback/read.
                ownEmpty.record(manager.primaryClipDescription?.timestamp, started, ended,
                    android.os.SystemClock.elapsedRealtime())
            }
        } catch (_: SecurityException) { throw ClipboardAccessException(ClipboardAccessIssue.WRITE_DENIED) }
    }

    override fun listen(onChanged: () -> Unit): AutoCloseable {
        val listener = ClipboardManager.OnPrimaryClipChangedListener(onChanged)
        manager.addPrimaryClipChangedListener(listener)
        return AutoCloseable { manager.removePrimaryClipChangedListener(listener) }
    }

    private fun checkUnlocked() {
        if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            throw ClipboardAccessException(ClipboardAccessIssue.DEVICE_LOCKED)
        }
    }
}
