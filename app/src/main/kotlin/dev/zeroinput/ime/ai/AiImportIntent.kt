package dev.zeroinput.ime.ai

import android.content.Intent
import dev.zeroinput.ai.api.AiLimits

/** No attachment URI or remote URL from a sender is ever opened. */
internal object AiImportIntent {
    fun text(intent: Intent): String? = try {
        if (intent.action == AiComposeActivity.ACTION_PICK) {
            intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().takeIf { it.length <= AiLimits.MAX_INPUT_CHARS }
        }
        else if (intent.type != "text/plain") null
        else {
            val value = when (intent.action) {
                Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
                Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
                else -> null
            }
            value?.takeIf { it.length in 1..AiLimits.MAX_INPUT_CHARS }?.toString()?.takeIf { it.isNotBlank() }
        }
    } catch (_: RuntimeException) { null }
}
