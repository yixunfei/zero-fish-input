package dev.zeroinput.ime.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.Space
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.ChineseScript
import dev.zeroinput.engine.api.ChineseKeyboardLayout
import dev.zeroinput.engine.api.EngineCapability
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.engine.api.PageDirection
import dev.zeroinput.ime.core.InputSessionState
import dev.zeroinput.ime.core.EditorInputOptions
import dev.zeroinput.ime.core.EditorLayout

class ZeroInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    var onKeyboardAction: (KeyboardAction) -> Unit = {}
    var onCandidateSelected: (Int, String) -> Unit = { _, _ -> }
    var onCandidatePageChanged: (PageDirection) -> Unit = {}
    var onEmojiSelected: (EmojiEntry) -> Unit = {}
    var onExpressionFavoriteRequested: (EmojiEntry, Boolean) -> Unit = { _, _ -> }
    var onExpressionManagementRequested: (String?) -> Unit = {}
    var onSecureClipboardSelected: (String) -> Unit = {}
    var onCopySelectionRequested: () -> Unit = {}
    var onPasteConfirmed: () -> Unit = {}
    var onPasteCancelled: () -> Unit = {}
    var onSettingsRequested: () -> Unit = {}
    var onClipboardGuardRequested: () -> Unit = {}
    var onSecureClipboardManagementRequested: () -> Unit = {}
    /**
     * Notifies the service about UI-only interactions (panel changes and
     * search toggles) that do not otherwise produce an [KeyboardAction].
     * The service uses this signal to invalidate pending authenticated
     * clipboard requests.
     */
    var onUserInteraction: () -> Unit = {}
    var onTouchStarted: () -> Unit = {}
    var onTouchFinished: () -> Unit = {}
    private var touching = false
    val modelRankingSurfaceAvailable: Boolean get() = !touching && mode == PanelMode.KEYBOARD && !manualTools

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { touching = true; onTouchStarted() }
        return try { super.dispatchTouchEvent(event) } finally {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                touching = false
                onTouchFinished()
            }
        }
    }
    var onClearCompositionRequested: () -> Boolean = { false }
    var onEngineRetryRequested: () -> Unit = {}
    var onScriptSwitchRequested: () -> Unit = {}
    var onLayoutSwitchRequested: () -> Unit = {}
    var onReadingSelected: (Int) -> Unit = {}
    var onReconvertRequested: () -> Unit = {}
    var onUndoSelectionRequested: () -> Unit = {}
    var onSyllableRequested: () -> Unit = {}

    private var mode = PanelMode.KEYBOARD
    private var editorOptions = EditorInputOptions()
    private var manualTools = false
    private var maximumContentHeight = Int.MAX_VALUE
    private var lastViewport = -1
    private var engineStatus = InputEngineStatus.HIDDEN
    private val languageButton = toolbarButton("中", "切换中英文") {
        dispatchKeyboardAction(KeyboardAction.SwitchLanguage)
    }
    private val candidateStrip = CandidateStripView(context)
    private val enginePreparation = EnginePreparationView(context)
    private val clipboardGuard = ClipboardGuardReminderView(context).apply { onRequested = { onClipboardGuardRequested() } }
    private val expandedCandidates = ExpandedCandidatesView(context)
    private var currentSnapshot = EngineSnapshot.Empty
    private var currentLanguage = InputLanguage.CHINESE
    private var currentPack: String? = null
    private var sensitive = false
    private var canReconvert = false
    private val reconvertButton = panelIconButton(context, android.R.drawable.ic_menu_revert, R.string.reconvert_last_word) {
        onReconvertRequested()
    }.apply { visibility = GONE }
    private val scriptButton = toolbarButton(context.getString(R.string.script_short_simplified), context.getString(R.string.switch_chinese_script)) {
        onScriptSwitchRequested()
    }
    private val keyboard = KeyboardPanel(context)
    private val readings = ReadingChoicesView(context).apply { onSelected = { onReadingSelected(it) } }
    private var chineseLayout = ChineseKeyboardLayout.FULL
    private var capabilities: Set<EngineCapability> = emptySet()
    private val keyboardContainer = LinearLayout(context).apply {
        orientation = HORIZONTAL
        addView(readings, LayoutParams(dp(60), keyboard.preferredHeight))
        addView(keyboard, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }
    private val layoutButton = toolbarButton(context.getString(R.string.layout_nine_short), context.getString(R.string.switch_keyboard_layout)) {
        onLayoutSwitchRequested()
    }
    private val emoji = EmojiPanelView(context)
    private val secureClipboard = SecureClipboardPanelView(context)
    private val content = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }
    private val returnButton = toolbarButton("⌨", context.getString(R.string.keyboard_return)) {
        manualTools = false
        showMode(PanelMode.KEYBOARD)
    }
    private val toolbar = createToolbar()
    private val header = FrameLayout(context).apply {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(if (landscape) 48 else 72))
        addView(toolbar, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48), Gravity.CENTER_VERTICAL))
        addView(candidateStrip, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurface, 0xfffafafa.toInt()))
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }
        addView(enginePreparation, LayoutParams(LayoutParams.MATCH_PARENT, enginePreparation.preferredHeight))
        addView(header)
        addView(clipboardGuard)
        content.addView(emoji)
        content.addView(secureClipboard)
        content.addView(keyboardContainer)
        content.addView(expandedCandidates, LayoutParams(LayoutParams.MATCH_PARENT, dp(214)))
        addView(content)
        bindCallbacks()
        showMode(PanelMode.KEYBOARD)
    }

    fun renderSession(state: InputSessionState) {
        if (currentLanguage != state.language || currentPack != state.languagePackKey || sensitive != state.privacy.isSensitive) {
            cancelPendingGestures()
            if (mode == PanelMode.CANDIDATES) showMode(PanelMode.KEYBOARD)
        }
        currentLanguage = state.language
        currentPack = state.languagePackKey
        sensitive = state.privacy.isSensitive
        canReconvert = state.canReconvert
        capabilities = state.engineDescriptor?.capabilities.orEmpty()
        currentSnapshot = state.snapshot
        val label = if (state.languagePackKey != null) {
            "包"
        } else if (state.language == InputLanguage.CHINESE) {
            "中"
        } else {
            "En"
        }
        languageButton.text = if (state.privacy.isSensitive) "🔒" else label
        languageButton.contentDescription = if (state.privacy.isSensitive) "敏感输入保护中" else "切换中英文"
        keyboard.setLanguageLabel(label)
        keyboard.setComposing(state.snapshot.isComposing)
        candidateStrip.render(state.snapshot)
        scriptButton.visibility = if (state.language == InputLanguage.CHINESE && state.languagePackKey == null &&
            state.privacy.suggestionsAllowed && EngineCapability.CHINESE_SCRIPT in capabilities) View.VISIBLE else View.GONE
        layoutButton.visibility = if (mode == PanelMode.KEYBOARD && state.language == InputLanguage.CHINESE && state.languagePackKey == null &&
            state.privacy.suggestionsAllowed && EngineCapability.NINE_KEY_PINYIN in capabilities) View.VISIBLE else View.GONE
        readings.render(state.snapshot)
        updateKeyboardLayout()
        if (mode == PanelMode.CANDIDATES) {
            if (state.snapshot.candidates.isEmpty()) showMode(PanelMode.KEYBOARD, userInitiated = false)
            else expandedCandidates.render(state.snapshot)
        }
        if (!state.snapshot.isComposing) expandedCandidates.clear()
        if (!state.snapshot.isComposing) manualTools = false
        refreshHeader()
    }

    fun renderEngineStatus(status: InputEngineStatus) {
        engineStatus = status
        enginePreparation.render(status)
        candidateStrip.renderStatus(if (status == InputEngineStatus.PREPARING) InputEngineStatus.HIDDEN else status)
        refreshHeader()
    }

    fun startEditor(options: EditorInputOptions) {
        enginePreparation.resetEditor()
        editorOptions = options
        manualTools = false
        emoji.clearSession()
        keyboard.startEditor(options)
        showMode(PanelMode.KEYBOARD, userInitiated = false)
    }

    fun setKeyboardHeight(height: KeyboardHeight) {
        keyboard.setHeight(height)
        readings.layoutParams = LayoutParams(dp(60), keyboard.preferredHeight)
        updatePanelLayout()
    }

    fun renderClipboardGuard(enabled: Boolean, changed: Boolean) { clipboardGuard.render(enabled, changed) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val available = dp(resources.configuration.screenHeightDp)
        val parentLimit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) available
            else MeasureSpec.getSize(heightMeasureSpec)
        val limit = if (landscape && available > 0) minOf(parentLimit, (available - dp(48)).coerceAtLeast(dp(192))) else parentLimit
        val reminder = if (clipboardGuard.isVisible) dp(48) else 0
        val preparationHeight = if (enginePreparation.isVisible) enginePreparation.preferredHeight else 0
        val bodyLimit = (limit - header.layoutParams.height - paddingTop - paddingBottom - reminder - preparationHeight)
            .coerceAtLeast(dp(144))
        if (landscape && bodyLimit != lastViewport) {
            lastViewport = bodyLimit
            maximumContentHeight = bodyLimit
            keyboard.setCompactLandscape(bodyLimit < dp(224))
            readings.layoutParams = LayoutParams(dp(60), keyboard.preferredHeight)
            updatePanelLayout()
        }
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST))
    }

    fun renderChineseOptions(options: ChineseInputOptions) {
        val label = context.getString(if (options.script == ChineseScript.SIMPLIFIED)
            R.string.script_short_simplified else R.string.script_short_traditional)
        if (scriptButton.text != label) scriptButton.text = label
    }

    fun renderActiveLayout(layout: ChineseKeyboardLayout) {
        chineseLayout = layout
        updateKeyboardLayout()
    }

    private fun updateKeyboardLayout() {
        val nineKey = currentLanguage == InputLanguage.CHINESE && currentPack == null && !sensitive &&
            chineseLayout == ChineseKeyboardLayout.NINE_KEY && EngineCapability.NINE_KEY_PINYIN in capabilities &&
            mode != PanelMode.EMOJI && editorOptions.layout == EditorLayout.TEXT
        keyboard.setKeyboardLayout(if (nineKey) ChineseKeyboardLayout.NINE_KEY else ChineseKeyboardLayout.FULL)
        readings.visibility = if (nineKey) View.VISIBLE else View.GONE
        val label = context.getString(if (nineKey) R.string.layout_full_short else R.string.layout_nine_short)
        if (layoutButton.text != label) layoutButton.text = label
    }

    fun cancelPendingGestures() { keyboard.cancelPendingGestures() }

    /** Retire both public bindings and callbacks before replacing the themed view. */
    fun release() {
        enginePreparation.render(InputEngineStatus.HIDDEN)
        cancelPendingGestures()
        onKeyboardAction = {}
        onCandidateSelected = { _, _ -> }
        onCandidatePageChanged = {}
        onEmojiSelected = {}
        onExpressionFavoriteRequested = { _, _ -> }
        onExpressionManagementRequested = {}
        onSecureClipboardSelected = {}
        onCopySelectionRequested = {}
        onPasteConfirmed = {}
        onPasteCancelled = {}
        onSettingsRequested = {}
        onClipboardGuardRequested = {}
        onSecureClipboardManagementRequested = {}
        onUserInteraction = {}
        onTouchStarted = {}
        onTouchFinished = {}
        touching = false
        onClearCompositionRequested = { false }
        onEngineRetryRequested = {}
        onScriptSwitchRequested = {}
        onLayoutSwitchRequested = {}
        onReadingSelected = {}
        onReconvertRequested = {}
        onUndoSelectionRequested = {}
        onSyllableRequested = {}
        currentSnapshot = EngineSnapshot.Empty
        candidateStrip.render(currentSnapshot)
        expandedCandidates.clear()
        readings.render(currentSnapshot)
        emoji.clearSession()
        secureClipboard.render(false, emptyList())
        secureClipboard.renderCopyAvailable(false)
        secureClipboard.renderPasteConfirmation(false)
    }

    fun renderExpressions(allowed: Boolean, data: PersonalExpressionsUi, recent: List<String>) {
        emoji.renderPersonal(allowed, data, recent)
    }

    fun clearExpressionSession() { emoji.clearSession() }

    fun renderSecureClipboard(enabled: Boolean, items: List<SecureClipboardItemUi>) {
        secureClipboard.render(enabled, items)
    }

    fun renderCopySelectionAvailable(available: Boolean) { secureClipboard.renderCopyAvailable(available) }

    fun renderPasteConfirmation(visible: Boolean) {
        secureClipboard.renderPasteConfirmation(visible)
        if (visible) showMode(PanelMode.SECURE_CLIPBOARD, userInitiated = false)
    }

    fun returnToKeyboard() {
        showMode(PanelMode.KEYBOARD)
    }

    private fun createToolbar(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(48))
        addView(languageButton)
        addView(reconvertButton, LayoutParams(dp(48), dp(48)))
        addView(scriptButton)
        addView(layoutButton)
        addView(toolbarButton("☺", context.getString(R.string.expression_smileys)) { toggleMode(PanelMode.EMOJI) })
        addView(toolbarButton("🔒", context.getString(R.string.secure_clipboard_open)) { toggleMode(PanelMode.SECURE_CLIPBOARD) })
        addView(Space(context).apply { layoutParams = LayoutParams(0, 1, 1f) })
        // The return control replaces the layout switch while a secondary panel is open.
        addView(returnButton)
        addView(toolbarButton("⚙", context.getString(R.string.keyboard_settings)) { onSettingsRequested() })
    }

    private fun bindCallbacks() {
        keyboard.onAction = ::dispatchKeyboardAction
        keyboard.onUserInteraction = { onUserInteraction() }
        keyboard.onClearComposition = {
            if (mode == PanelMode.EMOJI && emoji.isSearchActive) {
                onUserInteraction()
                emoji.clearQuery()
                true
            } else onClearCompositionRequested()
        }
        candidateStrip.onCandidateSelected = ::selectVisibleCandidate
        candidateStrip.onUndoSelectionRequested = { onUndoSelectionRequested() }
        candidateStrip.onSyllableRequested = { onSyllableRequested() }
        candidateStrip.onExpandRequested = {
            if (mode == PanelMode.CANDIDATES) showMode(PanelMode.KEYBOARD)
            else if (currentSnapshot.candidates.isNotEmpty()) showMode(PanelMode.CANDIDATES)
        }
        candidateStrip.onRetryRequested = { onEngineRetryRequested() }
        candidateStrip.onToolsRequested = { onUserInteraction(); manualTools = true; refreshHeader() }
        expandedCandidates.onCandidateSelected = ::selectVisibleCandidate
        expandedCandidates.onPageChanged = { onCandidatePageChanged(it) }
        emoji.onEmojiSelected = { onEmojiSelected(it) }
        emoji.onFavoriteRequested = { entry, selected -> onExpressionFavoriteRequested(entry, selected) }
        emoji.onManageRequested = { onExpressionManagementRequested(it) }
        emoji.onSearchModeChanged = { searchActive -> updateEmojiSearchLayout(searchActive) }
        emoji.onUserInteraction = { onUserInteraction() }
        secureClipboard.onItemSelected = { onSecureClipboardSelected(it) }
        secureClipboard.onCopySelectionRequested = { onCopySelectionRequested() }
        secureClipboard.onPasteConfirmed = { onPasteConfirmed() }
        secureClipboard.onPasteCancelled = { onPasteCancelled() }
        secureClipboard.onManageRequested = {
            onUserInteraction()
            onSecureClipboardManagementRequested()
        }
    }

    private fun dispatchKeyboardAction(action: KeyboardAction) {
        if (mode == PanelMode.EMOJI && emoji.isSearchActive) {
            when (action) {
                is KeyboardAction.Text -> {
                    onUserInteraction()
                    emoji.appendQuery(action.value)
                }
                is KeyboardAction.LiteralText -> {
                    onUserInteraction()
                    emoji.appendQuery(action.value)
                }
                KeyboardAction.Backspace -> {
                    onUserInteraction()
                    emoji.removeQueryCharacter()
                }
                KeyboardAction.Space -> {
                    onUserInteraction()
                    emoji.appendQuery(" ")
                }
                KeyboardAction.Enter -> onUserInteraction()
                else -> onKeyboardAction(action)
            }
        } else {
            if (action is KeyboardAction.Text && currentLanguage == InputLanguage.CHINESE && !sensitive) {
                enginePreparation.onInputAttempt()
            }
            onKeyboardAction(action)
        }
    }

    private fun selectVisibleCandidate(index: Int) {
        currentSnapshot.candidates.getOrNull(index)?.let { onCandidateSelected(index, it.id) }
    }

    private fun toggleMode(target: PanelMode) {
        showMode(if (mode == target) PanelMode.KEYBOARD else target)
    }

    private fun showMode(target: PanelMode, userInitiated: Boolean = true) {
        val leavingSearch = mode == PanelMode.EMOJI && emoji.isSearchActive && target != PanelMode.EMOJI
        if (mode != target) {
            cancelPendingGestures()
            if (userInitiated) onUserInteraction()
        }
        mode = target
        if (leavingSearch) keyboard.startEditor(editorOptions)
        if (target == PanelMode.EMOJI && emoji.isSearchActive) keyboard.startEditor(EditorInputOptions())
        updateKeyboardLayout()
        returnButton.visibility = if (target == PanelMode.KEYBOARD) View.GONE else View.VISIBLE
        layoutButton.visibility = if (target == PanelMode.KEYBOARD && currentLanguage == InputLanguage.CHINESE &&
            EngineCapability.NINE_KEY_PINYIN in capabilities) View.VISIBLE else View.GONE
        setPanelVisible(expandedCandidates, target == PanelMode.CANDIDATES)
        candidateStrip.setExpanded(target == PanelMode.CANDIDATES)
        if (target == PanelMode.CANDIDATES) expandedCandidates.render(currentSnapshot)
        else expandedCandidates.clear()
        setPanelVisible(secureClipboard, target == PanelMode.SECURE_CLIPBOARD)
        setPanelVisible(emoji, target == PanelMode.EMOJI)
        setPanelVisible(
            keyboardContainer,
            target == PanelMode.KEYBOARD || emoji.isSearchActive && target == PanelMode.EMOJI,
        )
        updatePanelLayout()
        refreshHeader()
    }

    private fun refreshHeader() {
        reconvertButton.visibility = if (canReconvert && mode == PanelMode.KEYBOARD) VISIBLE else GONE
        val hasCandidates = currentSnapshot.isComposing || currentSnapshot.candidates.isNotEmpty()
        val needsStatus = engineStatus == InputEngineStatus.PENDING_CONFIGURATION ||
            engineStatus == InputEngineStatus.FAILED
        val showCandidates = (mode == PanelMode.KEYBOARD || mode == PanelMode.CANDIDATES) &&
            !manualTools && (hasCandidates || needsStatus)
        candidateStrip.visibility = if (showCandidates) VISIBLE else GONE
        toolbar.visibility = if (showCandidates) GONE else VISIBLE
        returnButton.visibility = if (mode != PanelMode.KEYBOARD || manualTools) VISIBLE else GONE
        if (returnButton.isVisible) layoutButton.visibility = GONE
        else layoutButton.visibility = if (currentLanguage == InputLanguage.CHINESE && currentPack == null && !sensitive &&
            EngineCapability.NINE_KEY_PINYIN in capabilities && editorOptions.layout == EditorLayout.TEXT) VISIBLE else GONE
        if (reconvertButton.isVisible) scriptButton.visibility = GONE
    }

    private fun setPanelVisible(panel: View, visible: Boolean) {
        panel.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateEmojiSearchLayout(searchActive: Boolean) {
        if (mode != PanelMode.EMOJI) return
        setPanelVisible(keyboardContainer, searchActive)
        keyboard.startEditor(if (searchActive) EditorInputOptions() else editorOptions)
        if (searchActive) keyboard.setKeyboardLayout(ChineseKeyboardLayout.FULL)
        updatePanelLayout()
    }

    private fun updatePanelLayout() {
        val searchActive = mode == PanelMode.EMOJI && emoji.isSearchActive
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val panelHeight = minOf(dp(if (landscape) EMOJI_SEARCH_HEIGHT_DP else EMOJI_PANEL_HEIGHT_DP), maximumContentHeight)
        val splitSearch = searchActive && landscape
        content.orientation = if (splitSearch) HORIZONTAL else VERTICAL
        emoji.layoutParams = when {
            splitSearch -> LayoutParams(0, panelHeight, 1f)
            searchActive -> LayoutParams(LayoutParams.MATCH_PARENT, dp(EMOJI_SEARCH_HEIGHT_DP))
            else -> LayoutParams(LayoutParams.MATCH_PARENT, panelHeight)
        }
        keyboardContainer.layoutParams = if (splitSearch) {
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 2f)
        } else {
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        secureClipboard.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, minOf(dp(PANEL_HEIGHT_DP), maximumContentHeight))
        expandedCandidates.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, keyboard.preferredHeight)
    }

    private fun toolbarButton(label: String, description: String, action: () -> Unit) = MaterialButton(context).apply {
        text = label
        contentDescription = description
        textSize = if (label.length > 1) 15f else 20f
        setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
        letterSpacing = 0f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        insetTop = 0
        insetBottom = 0
        setPadding(0, 0, 0, 0)
        setSingleLine()
        setBackgroundColor(Color.TRANSPARENT)
        elevation = 0f
        stateListAnimator = null
        layoutParams = LayoutParams(dp(48), dp(48))
        setOnClickListener { action() }
        setOnLongClickListener {
            Toast.makeText(context, description, Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun resolveColor(attribute: Int, fallback: Int): Int {
        val values = context.obtainStyledAttributes(intArrayOf(attribute))
        return values.getColor(0, fallback).also { values.recycle() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class PanelMode {
        KEYBOARD,
        EMOJI,
        SECURE_CLIPBOARD,
        CANDIDATES,
    }

    private companion object {
        const val PANEL_HEIGHT_DP = 260
        const val EMOJI_PANEL_HEIGHT_DP = 260
        const val EMOJI_SEARCH_HEIGHT_DP = 224
    }
}
