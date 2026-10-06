package dev.zeroinput.ime.clipboard

import android.content.Intent
import dev.zeroinput.userdata.SecureClipboardVault

internal object ClipboardImportIntent {
    fun parse(intent: Intent): ClipboardImportRequest? = try {
        parseChecked(intent)
    } catch (_: RuntimeException) {
        // A caller controls parcelled extras; never expose their exception text.
        null
    }

    private fun parseChecked(intent: Intent): ClipboardImportRequest? {
        if (intent.type != "text/plain" || intent.data != null || intent.selector != null) return null
        if (intent.hasExtra(Intent.EXTRA_STREAM) || intent.hasExtra(Intent.EXTRA_HTML_TEXT)) return null
        val key = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> Intent.EXTRA_PROCESS_TEXT
            Intent.ACTION_SEND -> Intent.EXTRA_TEXT
            else -> return null
        }
        val value = intent.getCharSequenceExtra(key) ?: return null
        if (value.length !in 1..SecureClipboardVault.MAX_VALUE_LENGTH) return null
        val clip = intent.clipData
        if (clip != null) {
            if (clip.itemCount != 1) return ClipboardImportText.parse(value)
            val item = clip.getItemAt(0)
            if (item.uri != null || item.intent != null || item.htmlText != null) return ClipboardImportText.parse(value)
            val duplicate = item.text
            if (duplicate == null || duplicate.length != value.length || duplicate.indices.any { duplicate[it] != value[it] }) {
                return ClipboardImportText.parse(value)
            }
        }
        return ClipboardImportText.parse(value)
    }
}
