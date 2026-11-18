package dev.zeroinput.ime.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton

enum class StegoField { COVER, SECRET, CREDENTIAL, CARRIER }
enum class StegoAction {
    ENCRYPT, DECRYPT, COVER, SECRET, CREDENTIAL, CARRIER, REVEAL, OPTIONS,
    PREPARE, CONFIRM, EDIT, SELECTION, VAULT, KEY_FILE, SAVE, INSERT, CLOSE,
}
data class StegoUiState(
    val decrypt: Boolean = false,
    val field: StegoField = StegoField.COVER,
    val text: String = "",
    val busy: Boolean = false,
    val prepared: Boolean = false,
    val revealed: Boolean = false,
    val hasResult: Boolean = false,
    val canInsert: Boolean = false,
    val status: String = "",
    val options: String = "",
)

/** Focus-free controls. No platform editor, paste menu, saved text, autofill or crypto. */
class StegoPanelView(context: Context) : ScrollView(context) {
    var onAction: (StegoAction) -> Unit = {}
    var onCaret: (KeyboardAction.MoveDraftCaret) -> Unit = {}
    private val commands = mutableMapOf<StegoAction, MaterialButton>()
    private val status = TextView(context).apply { textSize = 14f; isSaveEnabled = false }
    private val options = TextView(context).apply { textSize = 13f; isSaveEnabled = false }
    private val draft = DraftTextView(context).apply {
        textSize = 17f
        minHeight = dp(56)
        maxLines = 4
        isSaveEnabled = false
        setTextIsSelectable(false)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        onCaretRequested = { this@StegoPanelView.onCaret(it) }
    }

    init {
        isSaveEnabled = false
        isSaveFromParentEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (android.os.Build.VERSION.SDK_INT >= 30)
            importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), dp(4))
            row(this, StegoAction.ENCRYPT to R.string.stego_encrypt, StegoAction.DECRYPT to R.string.stego_decrypt,
                StegoAction.CLOSE to R.string.stego_close)
            row(this, StegoAction.COVER to R.string.stego_cover, StegoAction.SECRET to R.string.stego_secret,
                StegoAction.CREDENTIAL to R.string.stego_credential, StegoAction.CARRIER to R.string.stego_carrier)
            addView(draft, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(status)
            addView(options)
            row(this, StegoAction.REVEAL to R.string.stego_reveal, StegoAction.OPTIONS to R.string.stego_options,
                StegoAction.KEY_FILE to R.string.stego_key_file)
            row(this, StegoAction.SELECTION to R.string.stego_selection, StegoAction.VAULT to R.string.stego_vault)
            row(this, StegoAction.PREPARE to R.string.stego_prepare, StegoAction.CONFIRM to R.string.stego_confirm,
                StegoAction.EDIT to R.string.stego_edit)
            row(this, StegoAction.SAVE to R.string.stego_save, StegoAction.INSERT to R.string.stego_insert)
        })
        render(StegoUiState(), null)
    }

    fun render(value: StegoUiState, caret: DraftCaretUi?) {
        draft.text = value.text.ifEmpty { context.getString(R.string.stego_draft_hint) }
        draft.renderCaret(if (value.busy || value.prepared || value.hasResult) null else caret)
        status.text = value.status
        options.text = value.options
        commands.forEach { (action, button) ->
            button.isEnabled = !value.busy || action == StegoAction.CLOSE
            button.visibility = when (action) {
                StegoAction.ENCRYPT -> if (value.canInsert) VISIBLE else GONE
                StegoAction.COVER, StegoAction.SECRET -> if (!value.decrypt && !value.prepared) VISIBLE else GONE
                StegoAction.CARRIER -> if (value.decrypt && !value.hasResult) VISIBLE else GONE
                StegoAction.CREDENTIAL -> if (!value.prepared && !value.hasResult) VISIBLE else GONE
                StegoAction.SELECTION, StegoAction.VAULT -> if (value.decrypt && !value.hasResult) VISIBLE else GONE
                StegoAction.PREPARE, StegoAction.OPTIONS, StegoAction.KEY_FILE -> if (!value.prepared && !value.hasResult) VISIBLE else GONE
                StegoAction.CONFIRM -> if (value.prepared) VISIBLE else GONE
                StegoAction.EDIT -> if (value.prepared || value.hasResult) VISIBLE else GONE
                StegoAction.SAVE -> if (value.hasResult) VISIBLE else GONE
                StegoAction.INSERT -> if (value.hasResult && value.canInsert) VISIBLE else GONE
                else -> VISIBLE
            }
            button.isSelected = when (action) {
                StegoAction.ENCRYPT -> !value.decrypt
                StegoAction.DECRYPT -> value.decrypt
                StegoAction.COVER -> value.field == StegoField.COVER
                StegoAction.SECRET -> value.field == StegoField.SECRET
                StegoAction.CREDENTIAL -> value.field == StegoField.CREDENTIAL
                StegoAction.CARRIER -> value.field == StegoField.CARRIER
                else -> false
            }
        }
        commands[StegoAction.REVEAL]?.setText(if (value.revealed) R.string.stego_mask else R.string.stego_reveal)
        commands[StegoAction.OPTIONS]?.setText(if (value.decrypt) R.string.stego_decrypt_options else R.string.stego_options)
    }

    fun clear() { draft.text = ""; draft.renderCaret(null); status.text = ""; options.text = "" }
    override fun onDetachedFromWindow() { clear(); super.onDetachedFromWindow() }

    private fun row(parent: LinearLayout, vararg actions: Pair<StegoAction, Int>) {
        parent.addView(LinearLayout(context).apply {
            // Two-line labels must not shift a full-height button below its row.
            isBaselineAligned = false
            actions.forEach { (action, label) ->
                val button = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                    setText(label)
                    isAllCaps = false
                    minWidth = 0; minimumWidth = 0
                    setPadding(dp(2), 0, dp(2), 0)
                    maxLines = 2
                    filterTouchesWhenObscured = true
                    setOnClickListener { onAction(action) }
                }
                commands[action] = button
                addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()
}
