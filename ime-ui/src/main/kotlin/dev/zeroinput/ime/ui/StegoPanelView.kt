package dev.zeroinput.ime.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.ProgressBar
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox

enum class StegoField { COVER, SECRET, CREDENTIAL, CARRIER, KEY_BITS }
enum class StegoAction {
    ENCRYPT, DECRYPT, COVER, SECRET, CREDENTIAL, CARRIER, REVEAL, OPTIONS,
    PREPARE, CONFIRM, EDIT, SELECTION, VAULT, KEY_FILE, SAVE, INSERT, CLOSE,
    SAVE_COVER, SAVE_CIPHERTEXT, SAVE_CARRIER, SAVE_FIELD, NEXT, ADVANCED,
    VAULT_COVER, CREDENTIAL_MODE, KEY_BITS, GENERATE_KEY, ALGORITHMS, VAULT_SECRET,
    CLEAR_FIELD, PASTE_FIELD, RANDOM_POETRY, TRANSPORT, RANDOM_STAGE_WIDTHS,
}

data class StegoFieldUi(val text: String, val caret: DraftCaretUi? = null, val preeditLength: Int = 0)
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
    val canPaste: Boolean = true,
    val canVaultPaste: Boolean = true,
    val naturalWording: Boolean = false,
    val wordingPassword: Boolean = false,
    val randomStageWidths: Boolean = false,
    val carrierChoice: String = "",
    val actionNotice: String = "",
    val fileReady: Boolean = false,
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
    private val fields = mutableMapOf<StegoField, StegoFieldView>()
    private var activeField = StegoField.COVER
    private var advanced = false
    private var algorithmExpanded = false
    private var wasEditing = true
    private val algorithmSummary = TextView(context).apply { textSize = 13f; isSaveEnabled = false }
    private val progress = ProgressBar(context).apply { isIndeterminate = true; visibility = GONE }
    private val scroll = ScrollView(context).apply { isSaveEnabled = false; isFillViewport = true }
    private val algorithms = StegoAlgorithmView(context).apply {
        onSelect = { id, selected -> onSelectAlgorithm(id, selected) }
        onWidth = { onAlgorithmWidth(it) }
        onEarlier = { onAlgorithmEarlier(it) }
    }
    private val algorithmHint = TextView(context).apply { setText(R.string.stego_algorithm_hint); isSaveEnabled = false }
    private val randomWidths = MaterialCheckBox(context).apply {
        setText(R.string.stego_random_stage_widths)
        isSaveEnabled = false
        minHeight = dp(48)
        filterTouchesWhenObscured = true
        setOnClickListener { onAction(StegoAction.RANDOM_STAGE_WIDTHS) }
    }
    private val status = TextView(context).apply {
        textSize = 13f; isSaveEnabled = false; setPadding(dp(4), dp(8), dp(4), dp(8))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val actionNotice = TextView(context).apply {
        textSize = 13f
        isSaveEnabled = false
        setPadding(dp(12), dp(4), dp(12), dp(4))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        visibility = GONE
    }
    private val keyActions: LinearLayout
    private val primaryActions: LinearLayout
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
        row(this, StegoAction.ENCRYPT to R.string.stego_encrypt, StegoAction.DECRYPT to R.string.stego_decrypt,
            StegoAction.CLOSE to R.string.stego_close)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), dp(4))
            row(this, StegoAction.TRANSPORT to R.string.stego_carrier_choice)
            field(this, StegoField.COVER, StegoAction.COVER, R.string.stego_cover, R.string.stego_cover_hint)
            row(this, StegoAction.RANDOM_POETRY to R.string.stego_random_poetry)
            field(this, StegoField.SECRET, StegoAction.SECRET, R.string.stego_secret, R.string.stego_secret_hint)
            field(this, StegoField.CARRIER, StegoAction.CARRIER, R.string.stego_carrier, R.string.stego_carrier_hint)
            row(this, StegoAction.SELECTION to R.string.stego_selection, StegoAction.VAULT to R.string.stego_vault)
            row(this, StegoAction.CREDENTIAL_MODE to R.string.stego_credential, StegoAction.REVEAL to R.string.stego_reveal)
            field(this, StegoField.CREDENTIAL, StegoAction.CREDENTIAL, R.string.stego_credential, R.string.stego_credential_hint)
            addView(keyPresets)
            field(this, StegoField.KEY_BITS, StegoAction.KEY_BITS, R.string.stego_master_bits, R.string.stego_bits_hint)
            row(this, StegoAction.PASTE_FIELD to R.string.stego_paste_field,
                StegoAction.CLEAR_FIELD to R.string.stego_clear_field, StegoAction.NEXT to R.string.stego_next)
            keyActions = row(this, StegoAction.GENERATE_KEY to R.string.stego_generate_key, StegoAction.KEY_FILE to R.string.stego_key_file)
            row(this, StegoAction.ALGORITHMS to R.string.stego_layers_short)
            addView(algorithmSummary)
            addView(algorithmHint)
            addView(algorithms)
            addView(randomWidths)
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(progress, LayoutParams(dp(24), dp(24)))
                addView(status, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            })
            addView(draft, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            row(this, StegoAction.SAVE to R.string.stego_save_plaintext, StegoAction.SAVE_COVER to R.string.stego_save_cover)
            row(this, StegoAction.SAVE_CIPHERTEXT to R.string.stego_save_ciphertext, StegoAction.SAVE_CARRIER to R.string.stego_save_carrier)
            row(this, StegoAction.ADVANCED to R.string.stego_advanced)
            row(this, StegoAction.SAVE_FIELD to R.string.stego_save_field)
            row(this, StegoAction.OPTIONS to R.string.stego_options)
            addView(options)
        }
        scroll.addView(content)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(actionNotice, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        primaryActions = row(this, StegoAction.CONFIRM to R.string.stego_encrypt_insert, StegoAction.PREPARE to R.string.stego_prepare,
            StegoAction.INSERT to R.string.stego_insert, StegoAction.EDIT to R.string.stego_edit)
        render(StegoUiState(), null)
    }

    fun render(value: StegoUiState, caret: DraftCaretUi?) {
        val editing = !value.prepared && !value.hasResult
        val changedField = activeField != value.field
        activeField = value.field
        fields.forEach { (field, row) ->
            row.visibility = if (editing && when (field) {
                StegoField.COVER -> !value.naturalWording && (!value.decrypt || value.detachedCiphertext)
                StegoField.SECRET -> !value.decrypt
                StegoField.CARRIER -> value.decrypt
                StegoField.CREDENTIAL -> value.credentialRequired
                StegoField.KEY_BITS -> value.randomKey && !value.decrypt
            }) VISIBLE else GONE
            row.render(value.fields[field], editing && field == value.field, !value.busy,
                (field == StegoField.CREDENTIAL || field == StegoField.SECRET) && value.revealed)
            row.body.isLongClickable = value.canPaste && !value.busy && field in
                listOf(StegoField.COVER, StegoField.SECRET, StegoField.CARRIER)
            if (changedField && row.visibility == VISIBLE && field == value.field) row.post {
                if (row.isShown && activeField == field) row.requestRectangleOnScreen(
                    Rect(0, 0, row.width, row.height.coerceAtMost(dp(180))), false)
            }
        }
        renderAlgorithms(value, editing)
        placeKeyAction(value.naturalWording)
        renderCommands(value, editing)
        renderOutput(value, caret, editing)
        if (editing != wasEditing) scroll.scrollTo(0, 0)
        wasEditing = editing
    }

    private fun renderAlgorithms(value: StegoUiState, editing: Boolean) {
        val showAlgorithms = editing && !value.decrypt
        algorithms.visibility = if (showAlgorithms && algorithmExpanded) VISIBLE else GONE
        algorithmHint.visibility = algorithms.visibility
        randomWidths.visibility = if (value.naturalWording) algorithms.visibility else GONE
        randomWidths.isEnabled = editing && !value.busy
        randomWidths.isChecked = value.randomStageWidths
        algorithms.render(value.algorithms, editing && !value.busy)
        algorithmSummary.visibility = if (showAlgorithms && !algorithmExpanded) VISIBLE else GONE
        algorithmSummary.text = value.algorithms.filter { it.order >= 0 }.sortedBy { it.order }
            .joinToString(" → ") { if (value.randomStageWidths) it.name else "${it.name} · ${it.bits}" }
            .ifEmpty { context.getString(R.string.stego_no_algorithm) }
    }

    private fun renderOutput(value: StegoUiState, caret: DraftCaretUi?, editing: Boolean) {
        keyPresets.visibility = if (editing && value.randomKey && !value.decrypt) VISIBLE else GONE
        for (i in 0 until keyPresets.childCount) keyPresets.getChildAt(i).isEnabled = !value.busy
        draft.visibility = if (editing) GONE else VISIBLE
        val fullPreview = value.prepared && value.naturalWording
        draft.maxLines = if (fullPreview) Int.MAX_VALUE else 4
        draft.importantForAccessibility = if (fullPreview) IMPORTANT_FOR_ACCESSIBILITY_YES else IMPORTANT_FOR_ACCESSIBILITY_NO
        draft.text = value.text.ifEmpty { context.getString(R.string.stego_draft_hint) }
        draft.renderCaret(if (value.busy || value.prepared || value.hasResult) null else caret)
        val showNotice = value.actionNotice.isNotEmpty()
        status.text = if (showNotice) "" else value.status
        status.visibility = if (showNotice) GONE else VISIBLE
        actionNotice.visibility = if (showNotice) VISIBLE else GONE
        if (actionNotice.text.toString() != value.actionNotice) actionNotice.text = value.actionNotice
        progress.visibility = if (value.busy) VISIBLE else GONE
        options.text = value.options
        options.visibility = if (advanced && editing && (!value.naturalWording || !value.decrypt)) VISIBLE else GONE
    }

    private fun placeKeyAction(naturalWording: Boolean) {
        val button = commands.getValue(StegoAction.GENERATE_KEY)
        val destination = if (naturalWording) primaryActions else keyActions
        val parent = button.parent as LinearLayout
        if (parent === destination) return
        val params = button.layoutParams
        parent.removeView(button)
        destination.addView(button, 0, params)
    }

    private fun renderCommands(value: StegoUiState, editing: Boolean) {
        commands.forEach { (action, button) ->
            button.isEnabled = if (action == StegoAction.CLOSE) true else !value.busy &&
                (action != StegoAction.ENCRYPT && action != StegoAction.DECRYPT || editing)
            button.visibility = when (action) {
                StegoAction.ENCRYPT -> if (value.canInsert) VISIBLE else GONE
                StegoAction.COVER -> if (editing && !value.naturalWording && (!value.decrypt || value.detachedCiphertext)) VISIBLE else GONE
                StegoAction.SECRET -> if (!value.decrypt && !value.prepared) VISIBLE else GONE
                StegoAction.CARRIER -> if (value.decrypt && !value.hasResult) VISIBLE else GONE
                StegoAction.CREDENTIAL -> if (value.credentialRequired && !value.prepared && !value.hasResult) VISIBLE else GONE
                StegoAction.SELECTION -> if (value.canInsert && value.decrypt && editing) VISIBLE else GONE
                StegoAction.VAULT -> if (value.canVaultPaste && value.decrypt && editing) VISIBLE else GONE
                StegoAction.PASTE_FIELD -> if (value.canPaste && editing && value.field in
                    listOf(StegoField.COVER, StegoField.SECRET, StegoField.CARRIER)) VISIBLE else GONE
                StegoAction.CLEAR_FIELD -> if (editing) VISIBLE else GONE
                StegoAction.RANDOM_POETRY -> if (editing && !value.decrypt && !value.naturalWording && value.field == StegoField.COVER) VISIBLE else GONE
                StegoAction.PREPARE, StegoAction.NEXT -> if (editing) VISIBLE else GONE
                StegoAction.TRANSPORT -> if (editing && value.carrierChoice.isNotEmpty()) VISIBLE else GONE
                StegoAction.OPTIONS -> if (advanced && editing &&
                    (!value.naturalWording || !value.decrypt && value.wordingPassword)) VISIBLE else GONE
                StegoAction.ALGORITHMS -> if (editing && !value.decrypt) VISIBLE else GONE
                StegoAction.KEY_FILE -> if (value.fileRequired && !value.prepared && !value.hasResult) VISIBLE else GONE
                StegoAction.GENERATE_KEY -> if (editing && value.randomKey && !value.decrypt) VISIBLE else GONE
                StegoAction.KEY_BITS -> if (editing && value.randomKey && !value.decrypt) VISIBLE else GONE
                StegoAction.CREDENTIAL_MODE -> if (editing && !value.decrypt) VISIBLE else GONE
                StegoAction.CONFIRM -> if (value.canInsert && !value.decrypt && !value.hasResult &&
                    (!value.naturalWording || value.prepared)) VISIBLE else GONE
                StegoAction.EDIT -> if (value.prepared || value.hasResult) VISIBLE else GONE
                StegoAction.SAVE, StegoAction.SAVE_CARRIER -> if (editing) GONE else VISIBLE
                StegoAction.SAVE_COVER, StegoAction.SAVE_CIPHERTEXT -> if (editing || value.naturalWording) GONE else VISIBLE
                StegoAction.SAVE_FIELD -> if (advanced && !value.busy && editing && value.canSaveField) VISIBLE else GONE
                StegoAction.ADVANCED -> if (editing) VISIBLE else GONE
                StegoAction.REVEAL -> if (editing && (value.credentialRequired || !value.decrypt) || value.hasResult) VISIBLE else GONE
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
        commands[StegoAction.TRANSPORT]?.apply {
            text = context.getString(R.string.stego_carrier_choice, value.carrierChoice)
            setIconResource(R.drawable.stego_expand_more)
            iconGravity = MaterialButton.ICON_GRAVITY_END
        }
        commands[StegoAction.CREDENTIAL_MODE]?.apply {
            text = context.getString(R.string.stego_credential_choice, value.credentialMode)
            setIconResource(R.drawable.stego_expand_more)
            iconGravity = MaterialButton.ICON_GRAVITY_END
        }
        commands[StegoAction.CONFIRM]?.setText(when {
            value.busy -> R.string.stego_working
            value.prepared -> R.string.stego_confirm
            else -> R.string.stego_encrypt_insert
        })
        commands[StegoAction.OPTIONS]?.setText(when {
            value.decrypt -> R.string.stego_decrypt_options
            value.naturalWording -> R.string.stego_wording_kdf_cost
            else -> R.string.stego_options
        })
        commands[StegoAction.KEY_FILE]?.setText(
            if (value.naturalWording && value.fileReady) R.string.stego_key_file_ready else R.string.stego_key_file)
        commands[StegoAction.PREPARE]?.setText(when {
            value.busy -> R.string.stego_working
            value.decrypt -> R.string.stego_decrypt_start
            value.naturalWording -> R.string.stego_wording_prepare
            else -> R.string.stego_prepare
        })
        renderDisclosure(StegoAction.ADVANCED, advanced, R.string.stego_advanced)
        renderDisclosure(StegoAction.ALGORITHMS, algorithmExpanded, R.string.stego_layers_short)
    }

    private fun renderDisclosure(action: StegoAction, expanded: Boolean, label: Int) {
        commands[action]?.apply {
            isSelected = expanded
            setIconResource(if (expanded) R.drawable.stego_expand_less else R.drawable.stego_expand_more)
            iconGravity = MaterialButton.ICON_GRAVITY_END
            contentDescription = context.getString(if (expanded) R.string.stego_collapse_section else R.string.stego_expand_section,
                context.getString(label))
        }
    }

    fun clear() {
        draft.text = ""; draft.renderCaret(null); status.text = ""; options.text = ""
        actionNotice.text = ""; actionNotice.visibility = GONE
        algorithmSummary.text = ""; advanced = false; algorithmExpanded = false; wasEditing = true; progress.visibility = GONE
        scroll.scrollTo(0, 0)
        fields.values.forEach { it.clear() }
    }
    override fun onDetachedFromWindow() { clear(); super.onDetachedFromWindow() }

    private fun row(parent: LinearLayout, vararg actions: Pair<StegoAction, Int>): LinearLayout =
        LinearLayout(context).apply {
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
                    if (action == StegoAction.ENCRYPT || action == StegoAction.DECRYPT) {
                        backgroundTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
                            intArrayOf(com.google.android.material.color.MaterialColors.getColor(this,
                                com.google.android.material.R.attr.colorSecondaryContainer), android.graphics.Color.TRANSPARENT))
                    }
                    setOnClickListener {
                        if (action == StegoAction.ADVANCED) advanced = !advanced
                        if (action == StegoAction.ALGORITHMS) algorithmExpanded = !algorithmExpanded
                        onAction(action)
                    }
                }
                commands[action] = button
                addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            parent.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

    private fun field(parent: LinearLayout, field: StegoField, action: StegoAction, label: Int, hint: Int) {
        val group = StegoFieldView(context, label, hint)
        if (field == StegoField.COVER) group.body.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        group.body.apply {
            setOnClickListener { if (activeField != field) onAction(action) }
            onCaretRequested = { if (activeField == field) onCaret(it) }
            if (field == StegoField.COVER || field == StegoField.SECRET || field == StegoField.CARRIER) setOnLongClickListener {
                onPasteRequested(field); true
            }
        }
        group.title.setOnClickListener { onAction(action) }
        commands[action] = group.title
        fields[field] = group
        parent.addView(group)
    }

    private fun dp(value: Int): Int = (resources.displayMetrics.density * value).toInt()
}
