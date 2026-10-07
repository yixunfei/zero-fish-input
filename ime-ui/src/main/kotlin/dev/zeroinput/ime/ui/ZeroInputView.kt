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
import dev.zeroinput.engine.api.DoublePinyinScheme
import dev.zeroinput.engine.api.EngineCapability
import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.engine.api.PageDirection
import dev.zeroinput.engine.api.GlideLayout
import dev.zeroinput.engine.api.GlideRequest
import dev.zeroinput.engine.api.GlideCandidate
import dev.zeroinput.ime.core.InputSessionState
import dev.zeroinput.ime.core.EditorInputOptions
import dev.zeroinput.ime.core.EditorLayout
import dev.zeroinput.ai.api.AiAction
import dev.zeroinput.ai.api.AiConversation
import dev.zeroinput.ai.api.AiConversationSummary
import dev.zeroinput.ai.api.AiStreamEvent

class ZeroInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    var onKeyboardAction: (KeyboardAction) -> Unit = {}
    var onCandidateSelected: (Int, String) -> Unit = { _, _ -> }
    var onCandidatePageChanged: (PageDirection) -> Unit = {}
    var onEmojiSelected: (EmojiEntry) -> Unit = {}
    var onSearchVisibilityChanged: (Boolean) -> Unit = {}
    var onSearchAction: (KeyboardAction) -> Unit = {}
    var onSearchClearRequested: () -> Unit = {}
    val isSearchEditing: Boolean get() = mode == PanelMode.EMOJI && emoji.isSearchActive
    private var searchSnapshot = EngineSnapshot.Empty
    private var searchCandidatesExpanded = false
    var onExpressionFavoriteRequested: (EmojiEntry, Boolean) -> Unit = { _, _ -> }
    var onExpressionManagementRequested: (String?) -> Unit = {}
    var onSecureClipboardSelected: (String) -> Unit = {}
    var onCopySelectionRequested: () -> Unit = {}
    var onPasteConfirmed: () -> Unit = {}
    var onPasteCancelled: () -> Unit = {}
    var onSettingsRequested: () -> Unit = {}
    var onGlideStarted: () -> Unit = {}
    var onGlideRequested: (GlideRequest, GlideLetterCase) -> Unit = { _, _ -> }
    var onGlideCandidateSelected: (GlideCandidate) -> Unit = {}
    private var glideEnabled = true
    private var glidePolicyAllowed = true
    private var glideSuggestionsActive = false
    val availableGlideLayout: GlideLayout? get() = when {
        !glideEnabled || !glidePolicyAllowed || sensitive || currentPack != null ||
            mode != PanelMode.KEYBOARD || editorOptions.layout != EditorLayout.TEXT -> null
        currentLanguage == InputLanguage.ENGLISH -> GlideLayout.ENGLISH_QWERTY
        chineseLayout == ChineseKeyboardLayout.NINE_KEY && EngineCapability.NINE_KEY_PINYIN in capabilities -> GlideLayout.PINYIN_NINE_KEY
        doublePinyinScheme == DoublePinyinScheme.MICROSOFT -> GlideLayout.DOUBLE_PINYIN_MICROSOFT
        doublePinyinScheme == DoublePinyinScheme.ZIRANMA -> GlideLayout.DOUBLE_PINYIN_ZIRANMA
        else -> GlideLayout.PINYIN_QWERTY
    }
    var onPlacementRequested: (View) -> Unit = {}
    var onPanelExpansionChanged: () -> Unit = {}
    private var externalInsets = false
    var onClipboardGuardRequested: () -> Unit = {}
    var onSecureClipboardManagementRequested: () -> Unit = {}
    var onAiSubmit: (AiAction, String, String?) -> Unit = { _, _, _ -> }
    var onAiCancel: () -> Unit = {}
    var onAiInsert: (String) -> Unit = {}
    var onAiConversationsRequested: () -> Unit = {}
    var onAiConversationSelected: (String) -> Unit = {}
    var onAiConversationDeleted: (String) -> Unit = {}
    var onAiNewConversation: () -> Unit = {}
    var onAiModelSelected: (String) -> Unit = {}
    var onAiVisibilityChanged: (Boolean) -> Unit = {}
    var onAiDraftChanged: (KeyboardAction) -> Unit = {}
    var onAiSettingsRequested: () -> Unit = {}
    var onAiAddContentRequested: () -> Unit = {}
    var onAiImportContentRequested: () -> Unit = {}
    var onAiRemoveAttachment: (Int) -> Unit = {}
    val isAiOpen: Boolean get() = mode == PanelMode.AI
    val isAiEditing: Boolean get() = mode == PanelMode.AI && ai.editing
    private var aiSnapshot = EngineSnapshot.Empty
    private var aiCandidatesExpanded = false
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
                backNavigation.refresh()
            }
        }
    }
    var onClearCompositionRequested: () -> Boolean = { false }
    var onEngineRetryRequested: () -> Unit = {}
    var onScriptSwitchRequested: () -> Unit = {}
    var onLayoutSwitchRequested: () -> Unit = {}
    var onFuzzySwitchRequested: () -> Unit = {}
    var onFuzzySettingsRequested: () -> Unit = {}
    var onReadingSelected: (Int) -> Unit = {}
    var onReconvertRequested: () -> Unit = {}
    var onUndoSelectionRequested: () -> Unit = {}
    var onSyllableRequested: () -> Unit = {}
    var onHandwritingStrokesChanged: (List<FloatArray>) -> Unit = {}
    var onHandwritingCandidateSelected: (String) -> Boolean = { false }
    var onHandwritingVisibilityChanged: (Boolean) -> Unit = {}
    val isHandwritingOpen: Boolean get() = mode == PanelMode.HANDWRITING
    fun containsHandwritingCandidate(value: String): Boolean = isHandwritingOpen && handwriting.containsCandidate(value)
    fun renderHandwritingCandidates(values: List<String>) { if (isHandwritingOpen) handwriting.renderCandidates(values, completed = true) }
    fun renderHandwritingFailure() { if (isHandwritingOpen) handwriting.showFailure() }
    fun clearHandwriting() { handwriting.clear() }
    fun closeHandwriting() {
        if (isHandwritingOpen) showMode(PanelMode.KEYBOARD, userInitiated = false)
        else handwriting.clear()
    }

    private var mode = PanelMode.KEYBOARD
    private var editorOptions = EditorInputOptions()
    private var manualTools = false
    private var released = false
    val canNavigateBack: Boolean get() = !released && (manualTools || mode != PanelMode.KEYBOARD || keyboard.canNavigateBack)
    private var availablePanelHeight = 0
    private var panelChromeHeight = 0
    private var panelExpansion = PanelExpansion.COMPACT
    val isPanelExpanded: Boolean get() = panelExpansion != PanelExpansion.COMPACT
    private var lastViewport = -1
    private var lastLandscape = false
    private var engineStatus = InputEngineStatus.HIDDEN
    /** Rendered session status, independent of the optional diagnostic text. */
    val renderedEngineStatus: InputEngineStatus get() = engineStatus
    private var diagnosticsVisible = false
    private val languageButton = toolbarButton("中", "切换中英文") {
        dispatchKeyboardAction(KeyboardAction.SwitchLanguage)
    }
    private val candidateStrip = CandidateStripView(context)
    private val glideSuggestions = GlideSuggestionsView(context).apply {
        visibility = GONE
        onSelected = { onGlideCandidateSelected(it) }
        onCancelled = { onUserInteraction(); clearGlideCandidates() }
    }
    private val enginePreparation = EnginePreparationView(context)
    private val clipboardGuard = ClipboardGuardReminderView(context).apply { onRequested = { onClipboardGuardRequested() } }
    private val expandedCandidates = ExpandedCandidatesView(context)
    private var currentSnapshot = EngineSnapshot.Empty
    private var currentLanguage = InputLanguage.CHINESE
    private var currentPack: String? = null
    private var sensitive = false
    private var aiAvailable = false
    private var canReconvert = false
    private val reconvertButton = panelIconButton(context, android.R.drawable.ic_menu_revert, R.string.reconvert_last_word) {
        onReconvertRequested()
    }.apply { visibility = GONE }
    private val scriptButton = toolbarButton(context.getString(R.string.script_short_simplified), context.getString(R.string.switch_chinese_script)) {
        onScriptSwitchRequested()
    }
    private val fuzzyButton = toolbarButton(context.getString(R.string.fuzzy_short), context.getString(R.string.fuzzy_toggle)) {
        onFuzzySwitchRequested()
    }
    private val keyboard = KeyboardPanel(context)
    private val readings = ReadingChoicesView(context).apply { onSelected = { onReadingSelected(it) } }
    private var chineseLayout = ChineseKeyboardLayout.FULL
    private var doublePinyinScheme = DoublePinyinScheme.OFF
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
    private val handwriting = HandwritingPanelView(context)
    private val secureClipboard = SecureClipboardPanelView(context)
    private val ai = AiWorkbenchPanelView(context)
    private val content = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }
    private val returnButton = toolbarButton("⌨", context.getString(R.string.keyboard_return)) {
        navigateBack()
    }
    private val aiButton = aiEntryButton(context, ::requestAi)
        .apply { layoutParams = LayoutParams(dp(48), dp(48)) }
    private val toolbar = android.widget.HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(createToolbar())
    }
    private val expansionButton = panelIconButton(context, R.drawable.ic_panel_expand, R.string.panel_expand) {
        changePanelExpansion(if (isPanelExpanded) PanelExpansion.COMPACT else PanelExpansion.EXPANDED)
    }
    private val header = FrameLayout(context).apply {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(if (landscape) 48 else 72))
        addView(toolbar, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(48), Gravity.CENTER_VERTICAL))
        addView(candidateStrip, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(glideSuggestions, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(expansionButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL))
    }
    private val backNavigation = PanelBackNavigation(this, { canNavigateBack }) { navigateBack() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { backNavigation.refresh() }
    }

    override fun onDetachedFromWindow() {
        backNavigation.clear()
        super.onDetachedFromWindow()
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurface, 0xfffafafa.toInt()))
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            if (externalInsets) return@setOnApplyWindowInsetsListener insets
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            if (view.paddingLeft != bars.left || view.paddingRight != bars.right || view.paddingBottom != bars.bottom) {
                view.setPadding(bars.left, 0, bars.right, bars.bottom)
                // Insets can arrive after InputMethodService has measured its
                // WRAP_CONTENT child. Re-run measurement with the new budget.
                view.requestLayout()
            }
            insets
        }
        addView(enginePreparation, LayoutParams(LayoutParams.MATCH_PARENT, enginePreparation.preferredHeight))
        addView(header)
        addView(clipboardGuard)
        content.addView(emoji)
        content.addView(handwriting)
        content.addView(secureClipboard)
        content.addView(ai)
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
        glidePolicyAllowed = state.privacy.suggestionsAllowed &&
            (state.language != InputLanguage.ENGLISH || state.privacy.predictionsAllowed)
        aiAvailable = !state.privacy.isSensitive && state.privacy.suggestionsAllowed && state.privacy.independentAiAllowed
        if (!aiAvailable && mode == PanelMode.AI) showMode(PanelMode.KEYBOARD, userInitiated = false)
        canReconvert = state.canReconvert
        capabilities = state.engineDescriptor?.capabilities.orEmpty()
        currentSnapshot = state.snapshot
        if (mode != PanelMode.AI && !isSearchEditing) {
            renderEditorLanguage()
            keyboard.setComposing(state.snapshot.isComposing)
            candidateStrip.render(state.snapshot)
        }
        scriptButton.visibility = if (mode != PanelMode.AI && state.language == InputLanguage.CHINESE && state.languagePackKey == null &&
            state.privacy.suggestionsAllowed && EngineCapability.CHINESE_SCRIPT in capabilities) View.VISIBLE else View.GONE
        layoutButton.visibility = if (mode == PanelMode.KEYBOARD && state.language == InputLanguage.CHINESE && state.languagePackKey == null &&
            state.privacy.suggestionsAllowed && EngineCapability.NINE_KEY_PINYIN in capabilities) View.VISIBLE else View.GONE
        if (mode != PanelMode.AI) readings.render(state.snapshot)
        updateKeyboardLayout()
        if (mode == PanelMode.CANDIDATES) {
            if (state.snapshot.candidates.isEmpty()) showMode(PanelMode.KEYBOARD, userInitiated = false)
            else expandedCandidates.render(state.snapshot)
        }
        if (mode != PanelMode.AI && !isSearchEditing && !state.snapshot.isComposing) expandedCandidates.clear()
        refreshHeader()
    }

    private fun renderEditorLanguage() {
        val label = when {
            currentPack != null -> "包"
            currentLanguage == InputLanguage.CHINESE -> "中"
            else -> "En"
        }
        languageButton.text = if (sensitive) "🔒" else label
        languageButton.contentDescription = context.getString(
            if (sensitive) R.string.language_sensitive_protection else R.string.language_switch,
        )
        keyboard.setLanguageLabel(label)
    }

    private fun renderDraftLanguage(language: InputLanguage) {
        val label = if (language == InputLanguage.CHINESE) "中" else "En"
        keyboard.setLanguageLabel(label)
        languageButton.text = label
        languageButton.contentDescription = context.getString(R.string.language_switch)
    }

    fun renderEngineStatus(status: InputEngineStatus) {
        engineStatus = status
        enginePreparation.render(status)
        candidateStrip.renderStatus(if (status == InputEngineStatus.PREPARING) InputEngineStatus.HIDDEN else status)
        refreshHeader()
    }

    /** Renders a non-sensitive Debug diagnostic in the candidate header. */
    fun renderDiagnostics(value: String?) {
        diagnosticsVisible = !value.isNullOrBlank()
        candidateStrip.renderDiagnostics(value)
        refreshHeader()
    }

    /** Announces text only after the editor confirms a successful commit. */
    fun announceCommittedText(value: String) {
        if (value.isNotBlank()) candidateStrip.announceForAccessibility(value)
    }

    fun startEditor(options: EditorInputOptions) {
        enginePreparation.resetEditor()
        editorOptions = options
        manualTools = false
        emoji.clearSession()
        handwriting.clear()
        keyboard.startEditor(options)
        showMode(PanelMode.KEYBOARD, userInitiated = false)
    }

    fun applyAppearance(appearance: KeyboardAppearance, image: android.graphics.Bitmap? = null) {
        if (released) return
        val value = appearance.sanitized()
        keyboard.applyAppearance(value)
        setKeyboardHeight(value.height)
        val surface = resolveColor(com.google.android.material.R.attr.colorSurface, Color.GRAY)
        // Arbitrary user photos/colors must not compromise candidate and toolbar contrast.
        header.setBackgroundColor(if (value.background != KeyboardBackground.SOLID || value.backgroundColor != null)
            androidx.core.graphics.ColorUtils.setAlphaComponent(surface, 242) else Color.TRANSPARENT)
        background = KeyboardBackgroundDrawable(value, surface,
            resolveColor(com.google.android.material.R.attr.colorPrimaryContainer, Color.GRAY),
            image, resources.displayMetrics.density)
    }

    fun setKeyboardHeight(height: KeyboardHeight) {
        keyboard.setHeight(height)
        readings.layoutParams = LayoutParams(dp(60), keyboard.preferredHeight)
        updatePanelLayout()
    }

    fun setExternalInsets(value: Boolean) {
        externalInsets = value
        if (value) setPadding(0, 0, 0, 0)
    }

    fun setLayoutHeightScale(value: Float) {
        keyboard.setHeightScale(value)
        readings.layoutParams = LayoutParams(dp(60), keyboard.preferredHeight)
        updatePanelLayout()
    }

    fun configureSoundEffects(value: Boolean) {
        keyboard.keySoundEffectsEnabled = value
    }

    fun configurePairedSymbols(value: Boolean) {
        keyboard.setPairedSymbolsEnabled(value)
    }

    fun configureGlide(enabled: Boolean) { glideEnabled = enabled; keyboard.configureGlide(availableGlideLayout) }

    fun renderGlideCandidates(values: List<GlideCandidate>, busy: Boolean = false, failed: Boolean = false) {
        if (availableGlideLayout == null) return
        glideSuggestionsActive = true
        glideSuggestions.render(values, busy, failed)
        refreshHeader()
    }

    fun clearGlideCandidates() {
        if (!glideSuggestionsActive) return
        glideSuggestionsActive = false
        glideSuggestions.clear()
        refreshHeader()
    }

    fun renderClipboardGuard(enabled: Boolean, changed: Boolean) { clipboardGuard.render(enabled, changed) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val available = dp(resources.configuration.screenHeightDp)
        val requestedLimit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) available
            else MeasureSpec.getSize(heightMeasureSpec)
        // The previous IME root height is the collapsed window, not a limit on
        // its next layout. Only the current parent measure spec is authoritative.
        val parentLimit = requestedLimit
        // Insets are part of this view's measured size. Reserve them before
        // measuring children so the keyboard cannot extend below the IME window.
        val insetHeight = paddingTop + paddingBottom
        val contentLimit = (parentLimit - insetHeight).coerceAtLeast(0)
        val limit = if (landscape && available > 0 && panelExpansion != PanelExpansion.FULLSCREEN) {
            minOf(contentLimit, (available - dp(48)).coerceAtLeast(dp(192)))
        } else {
            contentLimit
        }
        val reminder = if (clipboardGuard.isVisible) dp(48) else 0
        val preparationHeight = if (enginePreparation.isVisible) enginePreparation.preferredHeight else 0
        panelChromeHeight = header.layoutParams.height + reminder + preparationHeight
        availablePanelHeight = limit
        val bodyLimit = (limit - panelChromeHeight).coerceAtLeast(0)
        if (bodyLimit != lastViewport || landscape != lastLandscape) {
            lastViewport = bodyLimit
            lastLandscape = landscape
            keyboard.setCompactLandscape(bodyLimit < dp(if (isSearchEditing) 480 else 224))
            readings.layoutParams = LayoutParams(dp(60), keyboard.preferredHeight)
            updatePanelLayout()
        }
        val outerLimit = (limit + insetHeight).coerceAtMost(parentLimit)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(outerLimit, MeasureSpec.AT_MOST))
        // FrameLayout may retain a larger WRAP_CONTENT child measurement while
        // the IME window is resized for navigation-bar insets. Clamp the final
        // outer size as well as the child budget to avoid stale overflow.
        if (measuredHeight > outerLimit) setMeasuredDimension(measuredWidth, outerLimit)
    }

    fun renderChineseOptions(options: ChineseInputOptions) {
        val label = context.getString(if (options.script == ChineseScript.SIMPLIFIED)
            R.string.script_short_simplified else R.string.script_short_traditional)
        if (scriptButton.text != label) scriptButton.text = label
        fuzzyButton.isSelected = options.effectiveFuzzyPinyinMask != 0
        fuzzyButton.contentDescription = context.getString(when {
            fuzzyButton.isSelected -> R.string.fuzzy_disable
            else -> R.string.fuzzy_enable
        })
        ViewCompat.setStateDescription(fuzzyButton, context.getString(
            if (fuzzyButton.isSelected) R.string.fuzzy_enabled_state else R.string.fuzzy_disabled_state))
        fuzzyButton.setTextColor(resolveColor(if (fuzzyButton.isSelected)
            com.google.android.material.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurface, Color.BLACK))
    }

    fun renderActiveLayout(layout: ChineseKeyboardLayout) {
        chineseLayout = layout
        updateKeyboardLayout()
    }

    fun renderActiveDoublePinyin(scheme: DoublePinyinScheme) {
        doublePinyinScheme = scheme
        updateKeyboardLayout()
    }

    private fun updateKeyboardLayout() {
        val nineKey = currentLanguage == InputLanguage.CHINESE && currentPack == null && !sensitive &&
            chineseLayout == ChineseKeyboardLayout.NINE_KEY && EngineCapability.NINE_KEY_PINYIN in capabilities &&
            mode != PanelMode.EMOJI && mode != PanelMode.AI && editorOptions.layout == EditorLayout.TEXT
        keyboard.setKeyboardLayout(if (nineKey) ChineseKeyboardLayout.NINE_KEY else ChineseKeyboardLayout.FULL)
        keyboard.setDoublePinyinScheme(if (currentLanguage == InputLanguage.CHINESE && currentPack == null &&
            !sensitive && !nineKey && mode != PanelMode.EMOJI && mode != PanelMode.AI &&
            editorOptions.layout == EditorLayout.TEXT) doublePinyinScheme else DoublePinyinScheme.OFF)
        readings.visibility = if (nineKey) View.VISIBLE else View.GONE
        val label = context.getString(if (nineKey) R.string.layout_full_short else R.string.layout_nine_short)
        if (layoutButton.text != label) layoutButton.text = label
        keyboard.configureGlide(availableGlideLayout)
    }

    fun cancelPendingGestures() { keyboard.cancelPendingGestures() }

    /** Retire both public bindings and callbacks before replacing the themed view. */
    fun release() {
        background = null
        emoji.clearSession()
        released = true
        backNavigation.clear()
        enginePreparation.render(InputEngineStatus.HIDDEN)
        cancelPendingGestures()
        onKeyboardAction = {}
        onCandidateSelected = { _, _ -> }
        onCandidatePageChanged = {}
        onEmojiSelected = {}
        onSearchVisibilityChanged = {}
        onSearchAction = {}
        onSearchClearRequested = {}
        onExpressionFavoriteRequested = { _, _ -> }
        onExpressionManagementRequested = {}
        onSecureClipboardSelected = {}
        onCopySelectionRequested = {}
        onPasteConfirmed = {}
        onPasteCancelled = {}
        onSettingsRequested = {}
        onGlideStarted = {}
        onGlideRequested = { _, _ -> }
        onGlideCandidateSelected = {}
        glideSuggestions.clear()
        onPlacementRequested = {}
        onPanelExpansionChanged = {}
        onClipboardGuardRequested = {}
        onSecureClipboardManagementRequested = {}
        onAiSubmit = { _, _, _ -> }
        onAiCancel = {}
        onAiInsert = {}
        onAiConversationsRequested = {}
        onAiConversationSelected = {}
        onAiConversationDeleted = {}
        onAiNewConversation = {}
        onAiModelSelected = {}
        onAiVisibilityChanged = {}
        onAiDraftChanged = {}
        onAiSettingsRequested = {}
        onAiAddContentRequested = {}
        onAiImportContentRequested = {}
        onAiRemoveAttachment = {}
        onUserInteraction = {}
        onTouchStarted = {}
        onTouchFinished = {}
        touching = false
        onClearCompositionRequested = { false }
        onEngineRetryRequested = {}
        onScriptSwitchRequested = {}
        onLayoutSwitchRequested = {}
        onFuzzySwitchRequested = {}
        onFuzzySettingsRequested = {}
        onReadingSelected = {}
        onReconvertRequested = {}
        onUndoSelectionRequested = {}
        onSyllableRequested = {}
        onHandwritingStrokesChanged = {}
        onHandwritingCandidateSelected = { false }
        onHandwritingVisibilityChanged = {}
        currentSnapshot = EngineSnapshot.Empty
        candidateStrip.render(currentSnapshot)
        diagnosticsVisible = false
        candidateStrip.renderDiagnostics(null)
        expandedCandidates.clear()
        readings.render(currentSnapshot)
        emoji.clearSession()
        handwriting.clear()
        secureClipboard.render(false, emptyList())
        secureClipboard.renderCopyAvailable(false)
        secureClipboard.renderPasteConfirmation(false)
        ai.reset()
        aiAvailable = false
        aiButton.renderAiEntryAvailability(false)
        candidateStrip.renderAiEntry(available = false, visible = false)
    }

    fun renderExpressions(allowed: Boolean, data: PersonalExpressionsUi, recent: List<String>) {
        emoji.renderPersonal(allowed, data, recent)
    }

    fun clearExpressionSession() { emoji.clearSession() }

    fun renderSearchDraft(text: String, state: InputSessionState) {
        if (!isSearchEditing) return
        searchSnapshot = state.snapshot
        emoji.renderQuery(text)
        candidateStrip.render(state.snapshot)
        keyboard.setComposing(state.snapshot.isComposing)
        renderDraftLanguage(state.language)
        if (searchCandidatesExpanded) {
            if (state.snapshot.candidates.isEmpty()) setSearchCandidatesExpanded(false)
            else expandedCandidates.render(state.snapshot)
        }
        refreshHeader()
    }

    fun renderSecureClipboard(enabled: Boolean, items: List<SecureClipboardItemUi>) {
        secureClipboard.render(enabled, items)
    }

    fun renderAi(event: AiStreamEvent) { ai.render(event) }

    fun renderAiModels(models: List<String>, selected: String?) { ai.renderModels(models, selected) }

    fun renderAiImportedContent(available: Boolean, names: List<String>) {
        ai.renderImportedContent(available, names)
        updatePanelLayout()
    }

    fun clearAiSession() {
        ai.reset()
        aiSnapshot = EngineSnapshot.Empty
        aiCandidatesExpanded = false
        if (mode == PanelMode.AI) showMode(PanelMode.KEYBOARD, userInitiated = false)
    }

    fun renderAiDraft(text: String, state: InputSessionState) {
        ai.renderDraft(text, state.language)
        aiSnapshot = state.snapshot
        if (mode == PanelMode.AI) {
            candidateStrip.render(state.snapshot)
            if (aiCandidatesExpanded) {
                if (state.snapshot.candidates.isEmpty()) setAiCandidatesExpanded(false)
                else expandedCandidates.render(state.snapshot)
            }
            keyboard.setComposing(state.snapshot.isComposing)
            renderDraftLanguage(state.language)
            refreshHeader()
        }
    }

    fun renderAiConversations(values: List<AiConversationSummary>) { ai.renderConversations(values) }

    fun renderAiConversation(value: AiConversation?) { ai.renderConversation(value) }

    fun renderCopySelectionAvailable(available: Boolean) { secureClipboard.renderCopyAvailable(available) }

    fun renderPasteConfirmation(visible: Boolean) {
        secureClipboard.renderPasteConfirmation(visible)
        if (visible) showMode(PanelMode.SECURE_CLIPBOARD, userInitiated = false)
    }

    fun returnToKeyboard() {
        manualTools = false
        showMode(PanelMode.KEYBOARD)
    }

    /** Return one UI level; the service hides the IME only at the keyboard root. */
    fun navigateBack(): Boolean {
        if (!canNavigateBack) return false
        cancelPendingGestures()
        when {
            manualTools -> { onUserInteraction(); manualTools = false; refreshHeader() }
            isSearchEditing && searchCandidatesExpanded -> { onUserInteraction(); setSearchCandidatesExpanded(false) }
            mode == PanelMode.AI && aiCandidatesExpanded -> { onUserInteraction(); setAiCandidatesExpanded(false) }
            mode == PanelMode.EMOJI && emoji.closeSearch() -> Unit
            mode != PanelMode.KEYBOARD -> showMode(PanelMode.KEYBOARD)
            else -> return keyboard.navigateBack()
        }
        return true
    }

    private fun createToolbar(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(48))
        addView(languageButton)
        addView(aiButton)
        addView(panelIconButton(context, android.R.drawable.ic_menu_crop, R.string.keyboard_placement) {
            onPlacementRequested(toolbar)
        }, LayoutParams(dp(48), dp(48)))
        addView(reconvertButton, LayoutParams(dp(48), dp(48)))
        addView(scriptButton)
        addView(layoutButton)
        addView(fuzzyButton)
        addView(toolbarButton("☺", context.getString(R.string.expression_smileys)) { toggleMode(PanelMode.EMOJI) })
        addView(panelIconButton(context, android.R.drawable.ic_menu_edit, R.string.handwriting_open) {
            toggleMode(PanelMode.HANDWRITING)
        }, LayoutParams(dp(48), dp(48)))
        addView(toolbarButton("🔒", context.getString(R.string.secure_clipboard_open)) { toggleMode(PanelMode.SECURE_CLIPBOARD) })
        addView(Space(context).apply { layoutParams = LayoutParams(0, 1, 1f) })
        // The return control replaces the layout switch while a secondary panel is open.
        addView(returnButton)
        addView(toolbarButton("⚙", context.getString(R.string.keyboard_settings)) { onSettingsRequested() })
    }

    private fun bindCallbacks() {
        keyboard.onGlideStarted = { onGlideStarted() }
        keyboard.onGlideRequest = { request, letterCase -> onGlideRequested(request, letterCase) }
        keyboard.onAction = ::dispatchKeyboardAction
        keyboard.onUserInteraction = { onUserInteraction() }
        keyboard.onClearComposition = {
            if (mode == PanelMode.EMOJI && emoji.isSearchActive) {
                onUserInteraction()
                onClearCompositionRequested()
            } else onClearCompositionRequested()
        }
        candidateStrip.onCandidateSelected = ::selectVisibleCandidate
        candidateStrip.onUndoSelectionRequested = { onUndoSelectionRequested() }
        candidateStrip.onSyllableRequested = { onSyllableRequested() }
        candidateStrip.onExpandRequested = {
            if (isSearchEditing) setSearchCandidatesExpanded(!searchCandidatesExpanded)
            else if (mode == PanelMode.AI) setAiCandidatesExpanded(!aiCandidatesExpanded)
            else if (mode == PanelMode.CANDIDATES) showMode(PanelMode.KEYBOARD)
            else if (currentSnapshot.candidates.isNotEmpty()) showMode(PanelMode.CANDIDATES)
        }
        candidateStrip.onRetryRequested = { onEngineRetryRequested() }
        candidateStrip.onAiRequested = ::requestAi
        candidateStrip.onReconvertRequested = { onReconvertRequested() }
        candidateStrip.onToolsRequested = {
            onUserInteraction()
            if (mode == PanelMode.AI) setAiCandidatesExpanded(false)
            if (isSearchEditing) setSearchCandidatesExpanded(false)
            manualTools = true
            toolbar.scrollTo(0, 0)
            refreshHeader()
        }
        expandedCandidates.onCandidateSelected = ::selectVisibleCandidate
        expandedCandidates.onPageChanged = { onCandidatePageChanged(it) }
        candidateStrip.onPageChanged = { onCandidatePageChanged(it) }
        emoji.onEmojiSelected = { onEmojiSelected(it) }
        emoji.onFavoriteRequested = { entry, selected -> onExpressionFavoriteRequested(entry, selected) }
        emoji.onManageRequested = { onExpressionManagementRequested(it) }
        emoji.onSearchModeChanged = { searchActive ->
            searchSnapshot = EngineSnapshot.Empty
            searchCandidatesExpanded = false
            onSearchVisibilityChanged(searchActive && mode == PanelMode.EMOJI)
            updateEmojiSearchLayout(searchActive)
            refreshHeader()
        }
        emoji.onQueryClearRequested = { onSearchClearRequested() }
        emoji.onUserInteraction = { onUserInteraction() }
        handwriting.onStrokesChanged = { onHandwritingStrokesChanged(it) }
        handwriting.onCandidateSelected = { onHandwritingCandidateSelected(it) }
        handwriting.onEditAction = { onKeyboardAction(it) }
        handwriting.onFullScreenChanged = { fullScreen ->
            changePanelExpansion(if (fullScreen) PanelExpansion.FULLSCREEN else PanelExpansion.COMPACT)
        }
        secureClipboard.onItemSelected = { onSecureClipboardSelected(it) }
        secureClipboard.onCopySelectionRequested = { onCopySelectionRequested() }
        secureClipboard.onPasteConfirmed = { onPasteConfirmed() }
        secureClipboard.onPasteCancelled = { onPasteCancelled() }
        secureClipboard.onManageRequested = {
            onUserInteraction()
            onSecureClipboardManagementRequested()
        }
        bindAiCallbacks()
    }

    private fun bindAiCallbacks() {
        ai.onSubmit = { action, input, target -> onAiSubmit(action, input, target) }
        ai.onCancel = { onAiCancel() }
        ai.onInsert = { onAiInsert(it) }
        ai.onConversationSelected = { onAiConversationSelected(it) }
        ai.onConversationDeleted = { onAiConversationDeleted(it) }
        ai.onNewConversation = { onAiNewConversation() }
        ai.onModelSelected = { onAiModelSelected(it) }
        ai.onSettings = { onAiSettingsRequested() }
        ai.onAddContent = { onAiAddContentRequested() }
        ai.onImportContent = { onAiImportContentRequested() }
        ai.onRemoveAttachment = { onAiRemoveAttachment(it) }
        ai.onEditingChanged = {
            if (mode == PanelMode.AI) {
                if (!it) setAiCandidatesExpanded(false)
                setPanelVisible(keyboardContainer, it && !aiCandidatesExpanded)
                keyboard.startEditor(EditorInputOptions())
                keyboard.setKeyboardLayout(ChineseKeyboardLayout.FULL)
                updatePanelLayout()
                refreshHeader()
            }
        }
    }

    private fun dispatchKeyboardAction(action: KeyboardAction) {
        manualTools = false
        refreshHeader()
        if (mode == PanelMode.EMOJI && emoji.isSearchActive) {
            onSearchAction(action)
        } else if (mode == PanelMode.AI && ai.editing) {
            onAiDraftChanged(action)
        } else if (mode == PanelMode.AI) {
            onUserInteraction()
        } else {
            if (action is KeyboardAction.Text && currentLanguage == InputLanguage.CHINESE && !sensitive) {
                enginePreparation.onInputAttempt()
            }
            onKeyboardAction(action)
        }
    }

    private fun selectVisibleCandidate(index: Int) {
        val snapshot = visibleSnapshot()
        snapshot.candidates.getOrNull(index)?.let { onCandidateSelected(index, it.id) }
    }

    private fun toggleMode(target: PanelMode) {
        showMode(if (mode == target) PanelMode.KEYBOARD else target)
    }

    private fun requestAi() {
        // Refresh the editor policy before opening or explaining this entry.
        onUserInteraction()
        if (!aiAvailable) {
            Toast.makeText(context, R.string.ai_entry_privacy_blocked, Toast.LENGTH_LONG).show()
            return
        }
        showMode(if (mode == PanelMode.AI) PanelMode.KEYBOARD else PanelMode.AI, userInitiated = false)
    }

    private fun showMode(target: PanelMode, userInitiated: Boolean = true) {
        if (target == PanelMode.AI && !aiAvailable) return
        val aiChanged = (mode == PanelMode.AI) != (target == PanelMode.AI)
        val handwritingChanged = (mode == PanelMode.HANDWRITING) != (target == PanelMode.HANDWRITING)
        val leavingSearch = mode == PanelMode.EMOJI && emoji.isSearchActive && target != PanelMode.EMOJI
        if (mode != target) {
            cancelPendingGestures()
            if (userInitiated) onUserInteraction()
            if (target == PanelMode.AI && !aiAvailable) return
            manualTools = false
            panelExpansion = PanelExpansion.COMPACT
            handwriting.setFullScreen(false)
            onPanelExpansionChanged()
        }
        if (leavingSearch) emoji.closeSearch()
        mode = target
        if (target != PanelMode.AI && !isSearchEditing) {
            renderEditorLanguage()
            keyboard.setComposing(currentSnapshot.isComposing)
        }
        if (handwritingChanged) {
            handwriting.clear()
            onHandwritingVisibilityChanged(target == PanelMode.HANDWRITING)
        }
        if (aiChanged) {
            onAiVisibilityChanged(target == PanelMode.AI)
            if (target == PanelMode.AI) ai.setEditing(true)
            else {
                ai.reset()
                aiSnapshot = EngineSnapshot.Empty
                aiCandidatesExpanded = false
                candidateStrip.render(currentSnapshot)
                keyboard.startEditor(editorOptions)
            }
        }
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
        setPanelVisible(ai, target == PanelMode.AI && aiAvailable)
        setPanelVisible(emoji, target == PanelMode.EMOJI)
        setPanelVisible(handwriting, target == PanelMode.HANDWRITING)
        setPanelVisible(
            keyboardContainer,
            target == PanelMode.KEYBOARD || emoji.isSearchActive && target == PanelMode.EMOJI ||
                target == PanelMode.AI && ai.editing,
        )
        if (target == PanelMode.AI && aiChanged) onAiConversationsRequested()
        updatePanelLayout()
        refreshHeader()
    }

    private fun setAiCandidatesExpanded(expanded: Boolean) {
        if (mode != PanelMode.AI) return
        aiCandidatesExpanded = expanded && aiSnapshot.candidates.isNotEmpty()
        candidateStrip.setExpanded(aiCandidatesExpanded)
        setPanelVisible(expandedCandidates, aiCandidatesExpanded)
        setPanelVisible(ai, !aiCandidatesExpanded)
        setPanelVisible(keyboardContainer, !aiCandidatesExpanded && ai.editing)
        if (aiCandidatesExpanded) expandedCandidates.render(aiSnapshot) else expandedCandidates.clear()
        updatePanelLayout()
        refreshHeader()
    }

    private fun setSearchCandidatesExpanded(expanded: Boolean) {
        if (!isSearchEditing) return
        searchCandidatesExpanded = expanded && searchSnapshot.candidates.isNotEmpty()
        candidateStrip.setExpanded(searchCandidatesExpanded)
        setPanelVisible(expandedCandidates, searchCandidatesExpanded)
        setPanelVisible(emoji, !searchCandidatesExpanded)
        setPanelVisible(keyboardContainer, !searchCandidatesExpanded)
        if (searchCandidatesExpanded) expandedCandidates.render(searchSnapshot) else expandedCandidates.clear()
        updatePanelLayout()
        refreshHeader()
    }

    private fun visibleSnapshot(): EngineSnapshot = when {
        mode == PanelMode.AI -> aiSnapshot
        isSearchEditing -> searchSnapshot
        else -> currentSnapshot
    }

    private fun changePanelExpansion(value: PanelExpansion) {
        onUserInteraction()
        cancelPendingGestures()
        panelExpansion = value
        onPanelExpansionChanged()
        handwriting.setFullScreen(value == PanelExpansion.FULLSCREEN)
        updatePanelLayout()
        refreshHeader()
        requestLayout()
    }

    private fun refreshHeader() {
        backNavigation.refresh()
        expansionButton.visibility = if (mode == PanelMode.KEYBOARD) GONE else VISIBLE
        expansionButton.rotation = if (isPanelExpanded) 180f else 0f
        expansionButton.contentDescription = context.getString(if (isPanelExpanded) R.string.panel_collapse else R.string.panel_expand)
        for (view in listOf(toolbar, candidateStrip)) {
            val params = view.layoutParams as FrameLayout.LayoutParams
            val margin = if (expansionButton.isVisible) dp(48) else 0
            if (params.marginEnd != margin) { params.marginEnd = margin; view.layoutParams = params }
        }
        aiButton.renderAiEntryAvailability(aiAvailable)
        reconvertButton.visibility = if (canReconvert && mode == PanelMode.KEYBOARD) VISIBLE else GONE
        candidateStrip.renderReconversion(canReconvert && mode == PanelMode.KEYBOARD)
        val visibleSnapshot = visibleSnapshot()
        val hasCandidates = visibleSnapshot.isComposing || visibleSnapshot.candidates.isNotEmpty()
        // Keep the idle entry discoverable without reducing the four-candidate
        // capacity of a compact composing row. Tools retains its leading AI entry.
        candidateStrip.renderAiEntry(aiAvailable, visible = mode != PanelMode.AI && !hasCandidates)
        val needsStatus = engineStatus == InputEngineStatus.PENDING_CONFIGURATION ||
            engineStatus == InputEngineStatus.FAILED
        fuzzyButton.visibility = if (mode == PanelMode.KEYBOARD && currentLanguage == InputLanguage.CHINESE &&
            currentPack == null && !sensitive && EngineCapability.FUZZY_PINYIN in capabilities &&
            editorOptions.layout == EditorLayout.TEXT) VISIBLE else GONE
        // Explicit tool navigation takes priority over diagnostics and candidate
        // refreshes. Secondary panels must keep their navigation controls.
        val showCandidates = !manualTools && when (mode) {
            PanelMode.AI -> ai.editing && hasCandidates
            PanelMode.KEYBOARD, PanelMode.CANDIDATES -> diagnosticsVisible || hasCandidates || needsStatus
            PanelMode.EMOJI -> isSearchEditing && hasCandidates
            PanelMode.SECURE_CLIPBOARD, PanelMode.HANDWRITING -> false
        }
        candidateStrip.visibility = if (showCandidates) VISIBLE else GONE
        toolbar.visibility = if (showCandidates) GONE else VISIBLE
        glideSuggestions.visibility = if (glideSuggestionsActive && mode == PanelMode.KEYBOARD) VISIBLE else GONE
        if (glideSuggestions.visibility == VISIBLE) { candidateStrip.visibility = GONE; toolbar.visibility = GONE }
        returnButton.visibility = if (mode != PanelMode.KEYBOARD || manualTools) VISIBLE else GONE
        if (mode == PanelMode.AI || isSearchEditing) { scriptButton.visibility = GONE; layoutButton.visibility = GONE }
        else if (returnButton.isVisible) layoutButton.visibility = GONE
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
        setPanelVisible(expandedCandidates, false)
        setPanelVisible(emoji, true)
        candidateStrip.setExpanded(false)
        candidateStrip.render(if (searchActive) searchSnapshot else currentSnapshot)
        keyboard.startEditor(if (searchActive) EditorInputOptions() else editorOptions)
        if (searchActive) {
            keyboard.setKeyboardLayout(ChineseKeyboardLayout.FULL)
            keyboard.setDoublePinyinScheme(DoublePinyinScheme.OFF)
            // Reserve room for both results and all key rows on entering search.
            panelExpansion = PanelExpansion.EXPANDED
            onPanelExpansionChanged()
        } else {
            renderEditorLanguage()
            keyboard.setComposing(currentSnapshot.isComposing)
        }
        updatePanelLayout()
    }

    private fun updatePanelLayout() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val aiEditing = mode == PanelMode.AI && ai.editing && !aiCandidatesExpanded
        val searchActive = isSearchEditing && !searchCandidatesExpanded
        val editing = searchActive || aiEditing
        val splitSearch = editing && landscape
        // AI keeps three primary commands visible; reserve their width in a short landscape window.
        val detailWeight = if (aiEditing) 5f else 1f
        val keyboardWeight = if (aiEditing) 7f else 2f
        val compactPanel = dp(if (landscape) EMOJI_SEARCH_HEIGHT_DP else PANEL_HEIGHT_DP)
        val defaultHeight = when {
            editing && !splitSearch -> keyboard.preferredHeight + dp(if (searchActive) 224 else ai.editingHeightDp)
            mode == PanelMode.CANDIDATES || aiCandidatesExpanded || searchCandidatesExpanded -> keyboard.preferredHeight
            mode == PanelMode.AI && !editing -> dp(360)
            else -> compactPanel
        }
        val viewport = availablePanelHeight.takeIf { it > 0 } ?: dp(resources.configuration.screenHeightDp)
        val body = PanelSizePolicy.bodyHeight(panelExpansion, viewport, panelChromeHeight, defaultHeight)
        val detailHeight = if (editing && !splitSearch) (body - keyboard.preferredHeight).coerceAtLeast(0) else body
        content.orientation = if (splitSearch) HORIZONTAL else VERTICAL
        emoji.layoutParams = if (splitSearch) LayoutParams(0, body, 1f)
            else LayoutParams(LayoutParams.MATCH_PARENT, if (searchActive) detailHeight else body)
        keyboardContainer.layoutParams = if (splitSearch) LayoutParams(0, LayoutParams.WRAP_CONTENT, keyboardWeight)
            else LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        secureClipboard.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, body)
        handwriting.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, body)
        ai.layoutParams = if (splitSearch) LayoutParams(0, body, detailWeight)
            else LayoutParams(LayoutParams.MATCH_PARENT, if (aiEditing) detailHeight else body)
        expandedCandidates.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, body)
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
        AI,
        HANDWRITING,
        CANDIDATES,
    }

    private companion object {
        const val PANEL_HEIGHT_DP = 260
        const val EMOJI_SEARCH_HEIGHT_DP = 224
    }
}
