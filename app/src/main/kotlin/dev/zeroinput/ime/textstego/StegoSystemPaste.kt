package dev.zeroinput.ime.textstego

import android.content.ClipboardManager
import android.content.Context
import dev.zeroinput.ime.ui.StegoField

/** User-triggered plain-text read only. Never observes, coerces URI/Intent data, or writes. */
internal class StegoSystemPaste(context: Context) {
    private val manager = context.getSystemService(ClipboardManager::class.java)
    private val settings = dev.zeroinput.ime.settings.SettingsRepository(context)

    fun read(field: StegoField, isCurrent: () -> Boolean): String? {
        val max = StegoPastePolicy.maxChars(field)
        if (max == 0 || !settings.stegoDefaults.systemPaste || !isCurrent()) return null
        return try {
            val clip = manager?.primaryClip ?: return null
            if (clip.itemCount != 1) return null
            if (!clip.description.hasMimeType("text/plain") && !clip.description.hasMimeType("text/html")) return null
            val item = clip.getItemAt(0)
            if (item.uri != null || item.intent != null) return null
            val text = item.text ?: return null
            if (text.length !in 1..max) return null
            val value = text.toString()
            if (!isCurrent() || !settings.stegoDefaults.systemPaste || !StegoPastePolicy.accepts(field, value)) null else value
        } catch (_: RuntimeException) { null }
    }
}
