package dev.zeroinput.ime.ui

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.withStyledAttributes
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import dev.zeroinput.engine.api.ChineseKeyboardLayout
import dev.zeroinput.ime.core.EditorInputOptions
import dev.zeroinput.ime.core.EditorLayout
import dev.zeroinput.ime.core.EnterAction

class KeyboardPanel @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    var onAction: (KeyboardAction) -> Unit = {}
    var onUserInteraction: () -> Unit = {}
    var onClearComposition: () -> Boolean = { false }
    private var backspaceRepeater: BackspaceRepeater? = null
    private var page = KeyboardPage.LETTERS
    private var shift = Shift.OFF
    private var languageLabel = "中"
    private var keyboardLayout = ChineseKeyboardLayout.FULL
    private var editor = EditorInputOptions()
    private var composing = false
    private var height = KeyboardHeight.STANDARD
    private var compact = false
    private var geometry = emptyList<List<Float>>()
    private val keys = mutableListOf<KeyboardKeyView>()
    private var radius = 0f
    private var inset = 0
    private var colorsResolved = false
    private var surfaceColor = 0
    private var surfaceVariantColor = 0
    private var primaryContainerColor = 0
    private var onSurfaceColor = 0
    private var outlineColor = 0
    private var enterSpec: KeySpec? = null

    init {
        orientation = VERTICAL
        isMotionEventSplittingEnabled = true
        context.withStyledAttributes(attrs = R.styleable.KeyboardKeyAppearance) {
            radius = getDimension(R.styleable.KeyboardKeyAppearance_keyboardKeyRadius, 0f)
            inset = getDimensionPixelSize(R.styleable.KeyboardKeyAppearance_keyboardKeyInset, 0)
        }
        render()
    }

    fun setLanguageLabel(value: String) {
        if (languageLabel == value) return
        languageLabel = value
        shift = Shift.OFF
        render()
    }

    fun setHeight(value: KeyboardHeight) {
        if (height == value) return
        cancelPendingGestures()
        height = value
        for (index in 0 until childCount) getChildAt(index).layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, rowHeight())
    }

    fun setCompactLandscape(value: Boolean) {
        if (compact == value) return
        compact = value
        render()
        for (index in 0 until childCount) getChildAt(index).layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, rowHeight())
    }

    fun startEditor(value: EditorInputOptions) {
        editor = value
        composing = false
        shift = Shift.OFF
        page = if (value.layout == EditorLayout.TEXT) KeyboardPage.LETTERS else KeyboardPage.NUMERIC
        render()
    }

    fun setComposing(value: Boolean) {
        if (composing == value) return
        composing = value
        // Only the action label changes when composition starts or ends.
        // Every layout exposes at most one Enter key, so the spec captured
        // during the last render is reused instead of rebuilding the layout.
        val spec = enterSpec ?: return
        keys.forEach { key -> if (key.boundAction == KeyboardAction.Enter) bind(key, spec) }
    }

    fun showLetters() {
        if (page == KeyboardPage.LETTERS) return
        page = KeyboardPage.LETTERS
        render()
    }

    fun setKeyboardLayout(value: ChineseKeyboardLayout) {
        if (keyboardLayout == value) return
        keyboardLayout = value
        shift = Shift.OFF
        if (editor.layout == EditorLayout.TEXT) page = KeyboardPage.LETTERS
        render()
    }

    private fun specs(): List<List<KeySpec>> = (when (page) {
        KeyboardPage.LETTERS -> if (keyboardLayout == ChineseKeyboardLayout.NINE_KEY)
            KeyboardLayouts.nineKey(context, languageLabel) else KeyboardLayouts.letters(context, shift != Shift.OFF, languageLabel)
        KeyboardPage.SYMBOLS -> KeyboardLayouts.symbols(context, languageLabel)
        KeyboardPage.MORE_SYMBOLS -> KeyboardLayouts.moreSymbols(context, languageLabel)
        KeyboardPage.NUMERIC -> NumericKeyboardLayout.rows(context, editor)
    }).let { if (compact) CompactKeyboardLayout.rows(it) else it }

    private fun render() {
        cancelPendingGestures()
        val rows = specs()
        val updated = rows.map { row -> row.map(KeySpec::widthWeight) }
        if (geometry != updated) rebuild(rows, updated)
        var enter: KeySpec? = null
        var index = 0
        for (row in rows) {
            for (spec in row) {
                if (spec.action == KeyboardAction.Enter) enter = spec
                bind(keys[index++], spec)
            }
        }
        enterSpec = enter
    }

    private fun rebuild(rows: List<List<KeySpec>>, updated: List<List<Float>>) {
        keys.forEach { it.isEnabled = false; it.onAction = {}; it.setOnLongClickListener(null) }
        removeAllViews()
        keys.clear()
        backspaceRepeater = null
        geometry = updated
        rows.forEach { specs ->
            val row = KeyboardRow(context).apply {
                weights = specs.map(KeySpec::widthWeight)
                layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight())
            }
            specs.forEach { spec -> row.addView(createKey(spec)) }
            addView(row)
        }
    }

    private fun createKey(spec: KeySpec) = KeyboardKeyView(context).apply {
        gravity = Gravity.CENTER
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        isAllCaps = false
        letterSpacing = 0f
        setSingleLine()
        ensureKeyColors()
        setTextColor(onSurfaceColor)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        onAction = ::handleAction
        if (spec.action == KeyboardAction.Backspace) {
            backspaceRepeater = BackspaceRepeater(this, { onClearComposition() }) { onAction(KeyboardAction.Backspace) }
        }
        if (spec.action == KeyboardAction.Shift) {
            setOnLongClickListener {
                onUserInteraction()
                shift = if (shift == Shift.LOCKED) Shift.OFF else Shift.LOCKED
                render()
                true
            }
            ViewCompat.replaceAccessibilityAction(this, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK,
                context.getString(R.string.key_caps_lock)) { _, _ -> performLongClick() }
        }
        keys += this
    }

    private fun bind(view: KeyboardKeyView, original: KeySpec) {
        ensureKeyColors()
        val spec = if (original.action == KeyboardAction.Enter) {
            val label = context.getString(if (composing) R.string.key_enter else enterLabel())
            original.copy(label = if (composing || editor.enterAction == EnterAction.NEW_LINE) "↵" else label, contentDescription = label)
        } else if (original.action == KeyboardAction.Shift) {
            original.copy(label = if (shift == Shift.LOCKED) "⇪" else "⇧")
        } else original
        view.bind(spec)
        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(view, 10,
            if (spec.label.length > 2) 13 else 19, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        val selected = spec.action == KeyboardAction.Shift && shift != Shift.OFF
        view.isSelected = selected
        ViewCompat.setStateDescription(view, if (spec.action == KeyboardAction.Shift)
            context.getString(when (shift) { Shift.OFF -> R.string.key_lowercase; Shift.ON -> R.string.key_uppercase; Shift.LOCKED -> R.string.key_caps_lock }) else null)
        view.setColors(
            if (selected) primaryContainerColor else when (spec.style) {
                KeyStyle.NORMAL -> surfaceColor
                KeyStyle.MODIFIER -> surfaceVariantColor
                KeyStyle.PRIMARY -> primaryContainerColor
            },
            primaryContainerColor,
            outlineColor,
            radius, inset,
        )
    }

    private fun enterLabel(): Int = when (editor.enterAction) {
        EnterAction.NEW_LINE -> R.string.key_enter
        EnterAction.GO -> R.string.key_go
        EnterAction.SEARCH -> R.string.key_search
        EnterAction.SEND -> R.string.key_send
        EnterAction.NEXT -> R.string.key_next
        EnterAction.DONE -> R.string.key_done
        EnterAction.PREVIOUS -> R.string.key_previous
    }

    private fun handleAction(action: KeyboardAction) {
        when (action) {
            KeyboardAction.Shift -> { onUserInteraction(); shift = if (shift == Shift.OFF) Shift.ON else Shift.OFF; render() }
            KeyboardAction.ShowLetters -> { onUserInteraction(); page = KeyboardPage.LETTERS; render() }
            KeyboardAction.ShowSymbols -> { onUserInteraction(); page = KeyboardPage.SYMBOLS; render() }
            KeyboardAction.ShowMoreSymbols -> { onUserInteraction(); page = KeyboardPage.MORE_SYMBOLS; render() }
            is KeyboardAction.Text -> {
                onAction(action)
                if (shift == Shift.ON) { shift = Shift.OFF; render() }
            }
            else -> onAction(action)
        }
    }

    fun cancelPendingGestures() { backspaceRepeater?.cancel(); keys.forEach { it.cancelTouch() } }
    internal val preferredHeight: Int get() = (0 until childCount).sumOf { getChildAt(it).layoutParams.height }
    override fun onDetachedFromWindow() { cancelPendingGestures(); super.onDetachedFromWindow() }
    private fun rowHeight() = dp(if (compact) 48 else height.rowHeight(
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE))
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun ensureKeyColors() {
        if (colorsResolved) return
        surfaceColor = color(com.google.android.material.R.attr.colorSurface)
        surfaceVariantColor = color(com.google.android.material.R.attr.colorSurfaceVariant)
        primaryContainerColor = color(com.google.android.material.R.attr.colorPrimaryContainer)
        onSurfaceColor = color(com.google.android.material.R.attr.colorOnSurface)
        outlineColor = androidx.core.graphics.ColorUtils.setAlphaComponent(color(com.google.android.material.R.attr.colorOutline), 80)
        colorsResolved = true
    }

    override fun onAttachedToWindow() {
        // A re-attach can carry a new theme; resolve the key palette again.
        colorsResolved = false
        super.onAttachedToWindow()
    }

    private fun color(attribute: Int): Int = com.google.android.material.color.MaterialColors.getColor(context, attribute, Color.GRAY)
    private enum class Shift { OFF, ON, LOCKED }
}
