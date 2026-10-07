package dev.zeroinput.ime.clipboard

import android.content.Intent
import dev.zeroinput.ime.ZeroInputApplication

/** An internal selection snapshot uses the same authentication/save flow as external imports. */
class ClipboardSelectionImportActivity : ClipboardImportActivity() {
    internal override val acceptsReplacementDraft: Boolean = true

    internal override fun receiveDraft(intent: Intent): ClipboardImportDraft? =
        (application as ZeroInputApplication).graph.clipboardSelectionTransfer.take(intent.getStringExtra(EXTRA_TOKEN))

    internal companion object {
        const val EXTRA_TOKEN = "dev.zeroinput.ime.clipboard.SELECTION_TOKEN"
    }
}
