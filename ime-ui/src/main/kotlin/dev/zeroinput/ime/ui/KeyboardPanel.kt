package dev.zeroinput.ime.ui

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.graphics.Canvas
import android.graphics.Rect
import dev.zeroinput.engine.api.GlideLayout
import dev.zeroinput.engine.api.GlideKey
import dev.zeroinput.engine.api.GlideRequest
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.withStyledAttributes
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import dev.zeroinput.engine.api.ChineseKeyboardLayout
import dev.zeroinput.engine.api.DoublePinyinScheme
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
    private var doublePinyinScheme = DoublePinyinScheme.OFF
    private var editor = EditorInputOptions()
    private var composing = false
    private var heightPreset = KeyboardHeight.STANDARD
    private var heightScale = 1f
    private var compact = false
    private var pairedSymbolsEnabled = true
    private var symbolSwipeStartX = 0f
    private var symbolSwipeStartY = 0f
    private var symbolSwipeActive = false
    private var geometry = emptyList<List<Float>>()
    private val keys = mutableListOf<KeyboardKeyView>()
    private val glide = GlideTouchTracker(this, ::glideKeys)
    private var glideLayout: GlideLayout? = null
    var onGlideStarted: () -> Unit = {}
    var onGlideRequest: (GlideRequest, GlideLetterCase) -> Unit = { _, _ -> }
    private var radius = 0f
    private var inset = 0
    private var appearance = KeyboardAppearance()
    private var colorsResolved = false
    private var surfaceColor = 0
    private var surfaceVariantColor = 0
    private var primaryContainerColor = 0
    private var onSurfaceColor = 0
    private var outlineColor = 0
    private var enterSpec: KeySpec? = null
    var keySoundEffectsEnabled: Boolean = false
        set(value) {
            field = value
            keys.forEach { it.isSoundEffectsEnabled = value }
        }

    init {
        orientation = VERTICAL
        isMotionEventSplittingEnabled = true
        context.withStyledAttributes(attrs = R.styleable.KeyboardKeyAppearance) {
            radius = getDimension(R.styleable.KeyboardKeyAppearance_keyboardKeyRadius, 0f)
            inset = getDimensionPixelSize(R.styleable.KeyboardKeyAppearance_keyboardKeyInset, 0)
        }
        render()
        glide.onStarted = { onGlideStarted() }
        glide.onRecognize = { request ->
            val letterCase = when (shift) { Shift.OFF -> GlideLetterCase.LOWER; Shift.ON -> GlideLetterCase.INITIAL_CAPITAL; Shift.LOCKED -> GlideLetterCase.UPPER }
            onGlideRequest(request, letterCase)
            if (shift == Shift.ON) { shift = Shift.OFF; render() }
        }
    }

    fun configureGlide(layout: GlideLayout?) { glideLayout = layout; updateGlideLayout() }

    fun applyAppearance(value: KeyboardAppearance) {
        appearance = value.sanitized()
        radius = if (appearance.material == KeyboardMaterial.CLASSIC) 0f else dp(appearance.cornerRadius).toFloat()
        inset = if (appearance.material == KeyboardMaterial.CLASSIC) 0 else dp(appearance.keySpacing)
        render()
    }

    private fun updateGlideLayout() {
        glide.layout = if (page == KeyboardPage.LETTERS && editor.layout == EditorLayout.TEXT) glideLayout else null
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (page == KeyboardPage.SYMBOLS || page == KeyboardPage.MORE_SYMBOLS) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    symbolSwipeStartX = event.x
                    symbolSwipeStartY = event.y
                    symbolSwipeActive = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - symbolSwipeStartX
                    val dy = event.y - symbolSwipeStartY
                    if (!symbolSwipeActive && kotlin.math.abs(dx) > dp(24) && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.2f) {
                        symbolSwipeActive = true
                        cancelPendingGestures()
                        val cancel = MotionEvent.obtain(event)
                        try { cancel.action = MotionEvent.ACTION_CANCEL; super.dispatchTouchEvent(cancel) }
                        finally { cancel.recycle() }
                    }
                    if (symbolSwipeActive) return true
                }
                MotionEvent.ACTION_UP -> {
                    if (symbolSwipeActive) {
                        val direction = event.x - symbolSwipeStartX
                        onUserInteraction()
                        page = if (direction < 0) KeyboardPage.MORE_SYMBOLS else KeyboardPage.SYMBOLS
                        render()
                        symbolSwipeActive = false
                        return true
                    }
                    symbolSwipeActive = false
                }
                MotionEvent.ACTION_CANCEL -> symbolSwipeActive = false
            }
        }
        if (glide.touch(event)) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val cancel = MotionEvent.obtain(event)
                try { cancel.action = MotionEvent.ACTION_CANCEL; super.dispatchTouchEvent(cancel) }
                finally { cancel.recycle() }
            }
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchDraw(canvas: Canvas) { super.dispatchDraw(canvas); glide.draw(canvas) }

    private fun glideKeys(): List<GlideKey> {
        if (width <= 0 || height <= 0) return emptyList()
        return keys.mapNotNull { key ->
            val text = (key.boundAction as? KeyboardAction.Text)?.value ?: return@mapNotNull null
            val code = text.singleOrNull()?.lowercaseChar() ?: return@mapNotNull null
            if (code !in 'a'..'z' && code !in '2'..'9' && code != ';') return@mapNotNull null
            // TextView can scroll its text internally; map its viewport, not its content origin.
            val rect = Rect()
            key.getDrawingRect(rect)
            offsetDescendantRectToMyCoords(key, rect)
            if (!rect.intersect(0, 0, width, height) || rect.isEmpty) return@mapNotNull null
            GlideKey(code, rect.left.toFloat() / width, rect.top.toFloat() / height,
                rect.right.toFloat() / width, rect.bottom.toFloat() / height)
        }
    }

    fun setLanguageLabel(value: String) {
        if (languageLabel == value) return
        languageLabel = value
        shift = Shift.OFF
        render()
    }

    fun setHeight(value: KeyboardHeight) {
        if (heightPreset == value) return
        cancelPendingGestures()
        heightPreset = value
        for (index in 0 until childCount) getChildAt(index).layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, rowHeight())
    }

    fun setHeightScale(value: Float) {
        val normalized = value.takeIf { it.isFinite() }?.coerceIn(0.8f, 1.4f) ?: 1f
        if (heightScale == normalized) return
        cancelPendingGestures()
        heightScale = normalized
        for (index in 0 until childCount) getChildAt(index).layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, rowHeight())
    }

    fun setCompactLandscape(value: Boolean) {
        if (compact == value) return
        compact = value
        render()
        for (index in 0 until childCount) getChildAt(index).layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, rowHeight())
    }

    fun setPairedSymbolsEnabled(value: Boolean) {
        if (pairedSymbolsEnabled == value) return
        pairedSymbolsEnabled = value
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

    fun setDoublePinyinScheme(value: DoublePinyinScheme) {
        if (doublePinyinScheme == value) return
        doublePinyinScheme = value
        render()
    }

    val canNavigateBack: Boolean get() = page == KeyboardPage.SYMBOLS || page == KeyboardPage.MORE_SYMBOLS

    fun navigateBack(): Boolean {
        if (!canNavigateBack) return false
        onUserInteraction()
        page = if (page == KeyboardPage.MORE_SYMBOLS) KeyboardPage.SYMBOLS
            else if (editor.layout == EditorLayout.TEXT) KeyboardPage.LETTERS else KeyboardPage.NUMERIC
        render()
        return true
    }

    private fun specs(): List<List<KeySpec>> = (when (page) {
        KeyboardPage.LETTERS -> if (keyboardLayout == ChineseKeyboardLayout.NINE_KEY)
            KeyboardLayouts.nineKey(context, languageLabel) else KeyboardLayouts.letters(context, shift != Shift.OFF,
                languageLabel, doublePinyinScheme == DoublePinyinScheme.MICROSOFT)
        KeyboardPage.SYMBOLS -> KeyboardLayouts.symbols(context, languageLabel)
        KeyboardPage.MORE_SYMBOLS -> KeyboardLayouts.moreSymbols(context, languageLabel)
        KeyboardPage.NUMERIC -> NumericKeyboardLayout.rows(context, editor)
    }).let { if (compact) CompactKeyboardLayout.rows(it) else it }

    private fun render() {
        cancelPendingGestures()
        updateGlideLayout()
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
        isSoundEffectsEnabled = keySoundEffectsEnabled
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
        alternativesFor(spec)?.let { alternatives ->
            setOnLongClickListener { showAlternatives(this, alternatives); true }
        }
        keys += this
    }

    private fun alternativesFor(spec: KeySpec): List<String>? {
        val value = (spec.action as? KeyboardAction.Text)?.value ?: return null
        if (value.length != 1 || !value[0].isLetterOrDigit()) return null
        return when (value.lowercase()) {
            "q" -> listOf("1")
            "w" -> listOf("2")
            "e" -> listOf("3", "€")
            "r" -> listOf("4")
            "t" -> listOf("5")
            "y" -> listOf("6")
            "u" -> listOf("7")
            "i" -> listOf("8")
            "o" -> listOf("9", "°")
            "p" -> listOf("0")
            "a" -> listOf("@")
            "s" -> listOf("$", "§")
            "d" -> listOf("#")
            "f" -> listOf("%")
            "g" -> listOf("&")
            "h" -> listOf("-")
            "j" -> listOf("+")
            "k" -> listOf("(")
            "l" -> listOf(")")
            else -> null
        }
    }

    private fun showAlternatives(anchor: View, values: List<String>) {
        onUserInteraction()
        var popup: PopupWindow? = null
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(surfaceColor)
            values.forEach { value ->
                addView(TextView(context).apply {
                    text = value
                    contentDescription = value
                    gravity = Gravity.CENTER
                    textSize = 20f
                    setTextColor(onSurfaceColor)
                    minWidth = dp(48)
                    minHeight = dp(48)
                    isClickable = true
                    setOnClickListener {
                        onUserInteraction()
                        onAction(KeyboardAction.LiteralText(value))
                        popup?.dismiss()
                    }
                })
            }
        }
        popup = PopupWindow(row, ViewGroup.LayoutParams.WRAP_CONTENT, dp(56), true).apply {
            elevation = dp(8).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE))
            isOutsideTouchable = true
            showAsDropDown(anchor, 0, -anchor.height - dp(64))
        }
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
                KeyStyle.NORMAL -> if (appearance.material == KeyboardMaterial.CLASSIC) surfaceColor else
                    androidx.core.graphics.ColorUtils.blendARGB(surfaceColor, Color.WHITE,
                        if (androidx.core.graphics.ColorUtils.calculateLuminance(surfaceColor) > 0.5) 0.9f else 0.06f)
                KeyStyle.MODIFIER -> surfaceVariantColor
                KeyStyle.PRIMARY -> primaryContainerColor
            },
            primaryContainerColor,
            outlineColor,
            radius, inset, appearance,
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
            is KeyboardAction.PairedText -> onAction(
                if (pairedSymbolsEnabled) action else KeyboardAction.LiteralText(action.opening),
            )
            else -> onAction(action)
        }
    }

    fun cancelPendingGestures() { glide.cancel(); backspaceRepeater?.cancel(); keys.forEach { it.cancelTouch() } }
    internal val preferredHeight: Int get() = (0 until childCount).sumOf { getChildAt(it).layoutParams.height }
    override fun onDetachedFromWindow() { cancelPendingGestures(); super.onDetachedFromWindow() }
    private fun rowHeight() = (dp(if (compact) 48 else heightPreset.rowHeight(
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)) * heightScale).toInt()
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
