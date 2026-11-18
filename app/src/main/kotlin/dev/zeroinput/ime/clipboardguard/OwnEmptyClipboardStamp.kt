package dev.zeroinput.ime.clipboardguard

/** Android 8 has no atomic clear-and-return-stamp API. Suppression is deliberately bounded. */
internal class OwnEmptyClipboardStamp {
    private var stamp: Long? = null
    private var expiresAt = 0L

    fun record(observed: Long?, writeStartedAt: Long, writeEndedAt: Long, elapsed: Long) {
        stamp = observed?.takeIf { writeEndedAt >= writeStartedAt && it in writeStartedAt..writeEndedAt }
        expiresAt = elapsed + 1_000L
    }

    fun hides(observed: Long?, elapsed: Long): Boolean {
        if (elapsed >= expiresAt || observed != stamp) stamp = null
        return stamp != null && observed == stamp
    }
}
