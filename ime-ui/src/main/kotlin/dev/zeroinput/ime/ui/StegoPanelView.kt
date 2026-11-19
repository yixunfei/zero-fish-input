package dev.zeroinput.ime.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton

enum class StegoField { COVER, SECRET, CREDENTIAL, CARRIER, KEY_BITS }
enum class StegoAction {
    ENCRYPT, DECRYPT, COVER, SECRET, CREDENTIAL, CARRIER, REVEAL, OPTIONS,
    PREPARE, CONFIRM, EDIT, SELECTION, VAULT, KEY_FILE, SAVE, INSERT, CLOSE,
    SAVE_COVER, SAVE_CIPHERTEXT, SAVE_CARRIER, SAVE_FIELD, NEXT, ADVANCED,
    VAULT_COVER, CREDENTIAL_MODE, KEY_BITS, GENERATE_KEY,
}

data class StegoFieldUi(val text: String, val caret: DraftCaretUi? = null)
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
    val credentialRequired: Boolean = true,
    val fileRequired: Boolean = false,
    val detachedCiphertext: Boolean = false,
    val canSaveField: Boolean = false,
    val fields: Map<StegoField, StegoFieldUi> = emptyMap(),
    val algorithms: List<StegoAlgorithmUi> = emptyList(),
    val randomKey: Boolean = false,
    val credentialMode: String = "",
)

/** Focus-free controls. Long press emits an intent; only the host can read a source. */
class StegoPanelView(context: Context) : LinearLayout(context) {
    var onAction: (StegoAction) -> Unit = {}
    var onCaret: (KeyboardAction.MoveDraftCaret) -> Unit = {}
    var onSelectAlgorithm: (Int, Boolean) -> Unit = { _, _ -> }
    var onAlgorithmWidth: (Int) -> Unit = {}
    var onAlgorithmEarlier: (Int) -> Unit = {}
    var onPasteRequested: (StegoField) -> Unit = {}
    var onKeyBits: (Int) -> Unit = {}
    private val commands = mutableMapOf<StegoAction, MaterialButton>()
    private val fields = mutableMapOf<StegoField, DraftTextView>()
    private val fieldRows = mutableMapOf<StegoField, LinearLayout>()
    private var activeField = StegoField.COVER
    private var advanced = false
    private val algorithms = StegoAlgorithmView(context).apply {
        onSelect = { id, selected -> onSelectAlgorithm(id, selected) }
        onWidth = { onAlgorithmWidth(it) }
        onEarlier = { onAlgorithmEarlier(it) }
    }
    private val algorithmHint = TextView(context).apply { setText(R.string.stego_algorithm_hint); isSaveEnabled = false }
    private val status = TextView(context).apply { textSize = 14f; isSaveEnabled = false }
    private val options = TextView(context).apply { textSize = 13f; isSaveEnabled = false }
    private val keyPresets = LinearLayout(context).apply {
        isBaselineAligned = false
        listOf(256, 512, 1024, 4096).forEach { bits ->
            addView(MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                text = bits.toString(); isAllCaps = false; minWidth = 0; minimumWidth = 0
                textSize = 13f; letterSpacing = 0f; insetTop = 0; insetBottom = 0
                setPadding(0, 0, 0, 0); filterTouchesWhenObscured = true
                contentDescription = context.getString(R.string.stego_master_bits_value, bits)
                setOnClickListener { onKeyBits(bits) }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
    }
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
        orientation = VERTICAL
        isSaveEnabled = false
        isSaveFromParentEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        if (android.os.Build.VERSION.SDK_INT >= 30)
            importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), dp(4))
            row(this, StegoAction.ENCRYPT to R.string.stego_encrypt, StegoAction.DECRYPT to R.string.stego_decrypt,
                StegoAction.CLOSE to R.string.stego_close)
            field(this, StegoField.COVER, StegoAction.COVER, R.string.stego_cover)
            field(this, StegoField.SECRET, StegoAction.SECRET, R.string.stego_secret)
            field(this, StegoField.CARRIER, StegoAction.CARRIER, R.string.stego_carrier)
            row(this, StegoAction.CREDENTIAL_MODE to R.string.stego_credential, StegoAction.REVEAL to R.string.stego_reveal)
            field(this, StegoField.CREDENTIAL, StegoAction.CREDENTIAL, R.string.stego_credential)
            addView(keyPresets)
            field(this, StegoField.KEY_BITS, StegoAction.KEY_BITS, R.string.stego_master_bits)
            row(this, StegoAction.GENERATE_KEY to R.string.stego_generate_key, StegoAction.KEY_FILE to R.string.stego_key_file)
            addView(draft, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(algorithmHint)
            addView(algorithms)
            addView(status)
            row(this, StegoAction.SAVE to R.string.stego_save_plaintext, StegoAction.SAVE_COVER to R.string.stego_save_cover)
            row(this, StegoAction.SAVE_CIPHERTEXT to R.string.stego_save_ciphertext, StegoAction.SAVE_CARRIER to R.string.stego_save_carrier)
            row(this, StegoAction.ADVANCED to R.string.stego_advanced)
            row(this, StegoAction.SAVE_FIELD to R.string.stego_save_field)
            row(this, StegoAction.SELECTION to R.string.stego_selection, StegoAction.VAULT to R.string.stego_vault)
            row(this, StegoAction.OPTIONS to R.string.stego_options)
            addView(options)
        }
        addView(ScrollView(context).apply {
            isSaveEnabled = false
            addView(content)
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        row(this, StegoAction.CONFIRM to R.string.stego_encrypt_insert, StegoAction.PREPARE to R.string.stego_prepare,
            StegoAction.INSERT to R.string.stego_insert, StegoAction.EDIT to R.string.stego_edit)
        render(StegoUiState(), null)
    }

    fun render(value: StegoUiState, caret: DraftCaretUi?) {
        val editing = !value.prepared && !value.hasResult
        activeField = value.field
        fieldRows.forEach { (field, row) ->
            row.visibility = if (editing && when (field) {
                StegoField.COVER -> !value.decrypt || value.detachedCiphertext
                StegoField.SECRET -> !value.decrypt
                StegoField.CARRIER -> value.decrypt
                StegoField.CREDENTIAL -> value.credentialRequired
                StegoField.KEY_BITS -> value.randomKey && !value.decrypt
            }) VISIBLE else GONE
            val body = fields.getValue(field)
            body.maxLines = if (field == StegoField.CREDENTIAL && value.revealed) Int.MAX_VALUE else 3
            body.text = value.fields[field]?.text.orEmpty().ifEmpty { context.getString(R.string.stego_field_hint) }
            body.isEnabled = !value.busy
            body.setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(this,
                if (field == value.field) com.google.android.material.R.attr.colorSurfaceVariant
                else com.google.android.material.R.attr.colorSurface))
            body.renderCaret(if (editing && !value.busy && field == value.field) value.fields[field]?.caret else null)
        }
        algorithms.visibility = if (editing && !value.decrypt) VISIBLE else GONE
        algorithmHint.visibility = algorithms.visibility
        algorithms.render(value.algorithms, editing && !value.busy)
        keyPresets.visibility = if (editing && value.randomKey && !value.decrypt) VISIBLE else GONE
        for (i in 0 until keyPresets.childCount) keyPresets.getChildAt(i).isEnabled = !value.busy
        draft.visibility = if (editing) GONE else VISIBLE
        draft.text = value.text.ifEmpty { context.getString(R.string.stego_draft_hint) }
        draft.renderCaret(if (value.busy || value.prepared || value.hasResult) null else caret)
        status.text = value.status
        options.text = value.options
        commands.forEach { (action, button) ->
            button.isEnabled = !value.busy || action == StegoAction.CLOSE
            button.visibility = when (action) {
                StegoAction.ENCRYPT -> if (value.canInsert) VISIBLE else GONE
                StegoAction.COVER -> if ((!value.decrypt || value.detachedCiphertext) && !value.prepared && !value.hasResult) VISIBLE else GONE
                StegoAction.SECRET -> if (!value.decrypt && !value.prepared) VISIBLE else GONE
                StegoAction.CARRIER -> if (value.decrypt && !value.hasResult) VISIBLE else GONE
                StegoAction.CREDENTIAL -> if (value.credentialRequired && !value.prepared && !value.hasResult) VISIBLE else GONE
                StegoAction.SELECTION, StegoAction.VAULT -> if (advanced && value.decrypt && editing) VISIBLE else GONE
                StegoAction.PREPARE, StegoAction.NEXT -> if (editing) VISIBLE else GONE
                StegoAction.OPTIONS -> if (advanced && editing) VISIBLE else GONE
                StegoAction.KEY_FILE -> if (value.fileRequired && !value.prepared && !value.hasResult) VISIBLE else GONE
                StegoAction.GENERATE_KEY, StegoAction.KEY_BITS -> if (editing && value.randomKey && !value.decrypt) VISIBLE else GONE
                StegoAction.CREDENTIAL_MODE -> if (editing && !value.decrypt) VISIBLE else GONE
                StegoAction.CONFIRM -> if (value.canInsert && !value.decrypt && !value.hasResult) VISIBLE else GONE
                StegoAction.EDIT -> if (value.prepared || value.hasResult) VISIBLE else GONE
                StegoAction.SAVE, StegoAction.SAVE_COVER, StegoAction.SAVE_CIPHERTEXT, StegoAction.SAVE_CARRIER ->
                    if (value.prepared || value.hasResult) VISIBLE else GONE
                StegoAction.SAVE_FIELD -> if (advanced && !value.busy && editing && value.canSaveField) VISIBLE else GONE
                StegoAction.ADVANCED -> if (editing) VISIBLE else GONE
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
        commands[StegoAction.CREDENTIAL_MODE]?.text = value.credentialMode
        commands[StegoAction.CONFIRM]?.setText(if (value.prepared) R.string.stego_confirm else R.string.stego_encrypt_insert)
        commands[StegoAction.OPTIONS]?.setText(if (value.decrypt) R.string.stego_decrypt_options else R.string.stego_options)
        commands[StegoAction.PREPARE]?.setText(if (value.decrypt) R.string.stego_decrypt else R.string.stego_prepare)
        options.visibility = if (advanced && editing) VISIBLE else GONE
    }

    fun clear() {
        draft.text = ""; draft.renderCaret(null); status.text = ""; options.text = ""; advanced = false
        fields.values.forEach { it.text = ""; it.renderCaret(null) }
    }
    override fun onDetachedFromWindow() { clear(); super.onDetachedFromWindow() }

    private fun row(parent: LinearLayout, vararg actions: Pair<StegoAction, Int>) {
        parent.addView(LinearLayout(context).apply {
            // Two-line labels must not shift a full-height button below its row.
            isBaselineAligned = false
            actions.forEach { (action, label) ->
                val style = if (action == StegoAction.CONFIRM || action == StegoAction.INSERT)
                    com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.borderlessButtonStyle
                val button = MaterialButton(context, null, style).apply {
                    setText(label)
                    isAllCaps = false
                    textSize = 13f; letterSpacing = 0f; insetTop = 0; insetBottom = 0
                    minWidth = 0; minimumWidth = 0
                    setPadding(dp(2), 0, dp(2), 0)
                    maxLines = 2
                    filterTouchesWhenObscured = true
                    setOnClickListener {
                        if (action == StegoAction.ADVANCED) advanced = !advanced
                        onAction(action)
                    }
                }
                commands[action] = button
                addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun field(parent: LinearLayout, field: StegoField, action: StegoAction, label: Int) {
        val body = DraftTextView(context).apply {
            textSize = 16f; minHeight = dp(48); maxLines = 3
            setPadding(dp(8), dp(4), dp(8), dp(4))
            isClickable = true; isFocusable = false; isSaveEnabled = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener { if (activeField != field) onAction(action) }
            onCaretRequested = { if (activeField == field) onCaret(it) }
            if (field == StegoField.COVER || field == StegoField.CARRIER) setOnLongClickListener {
                onPasteRequested(field); true
            }
        }
        val group = LinearLayout(context).apply {
            isBaselineAligned = false
            val title = MaterialButton(context, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
                setText(label); isAllCaps = false; minWidth = 0; minimumWidth = 0
                textSize = 13f; letterSpacing = 0f; insetTop = 0; insetBottom = 0
                maxLines = 2; setPadding(0, 0, 0, 0)
                filterTouchesWhenObscured = true
                setOnClickListener { onAction(action) }
            }
            commands[action] = title
            addView(title, LinearLayout.LayoutParams(dp(80), dp(48)))
            addView(body, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
        fields[field] = body; fieldRows[field] = group
        parent.addView(group)
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()
}
