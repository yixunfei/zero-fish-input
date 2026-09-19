package dev.zeroinput.ime

import android.content.Intent
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import android.widget.Toast
import androidx.core.view.WindowCompat
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.ChineseKeyboardLayout
import dev.zeroinput.ime.core.privacy.PrivacyConfiguration
import dev.zeroinput.ime.settings.ChineseEngineChoice
import dev.zeroinput.engine.rime.RimeRuntimeState
import dev.zeroinput.ime.concurrency.BoundedExecutors
import dev.zeroinput.ime.core.InputCommand
import dev.zeroinput.ime.core.InputSessionController
import dev.zeroinput.ime.input.AndroidEditorConnection
import dev.zeroinput.ime.settings.MainActivity
import dev.zeroinput.ime.settings.SecureClipboardManagerActivity
import dev.zeroinput.ime.clipboardguard.ClipboardGuardSettingsActivity
import dev.zeroinput.ime.ui.KeyboardAction
import dev.zeroinput.ime.ui.SecureClipboardItemUi
import dev.zeroinput.ime.ui.ZeroInputView
import dev.zeroinput.ime.ui.InputEngineStatus
import dev.zeroinput.ime.ui.EmojiCatalog
import dev.zeroinput.ime.ui.EmojiEntry
import dev.zeroinput.ime.ui.PersonalExpressionsUi
import dev.zeroinput.ime.expressions.presentation
import dev.zeroinput.ime.expressions.ExpressionManagerActivity
import java.util.Locale
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ZeroInputService : InputMethodService() {
    private var clipboardGuardObserver: AutoCloseable? = null
    private var expressionObserver: AutoCloseable? = null
    private var personalExpressionCache = PersonalExpressionsUi()
    private var personalExpressionRevision = -1L
    private val graph: AppGraph
        get() = (application as ZeroInputApplication).graph

    private var inputView: ZeroInputView? = null
    private var inputViewActive = false
    private var currentAppearance: dev.zeroinput.ime.ui.KeyboardAppearance? = null
    private var controller: InputSessionController? = null
    private var editorConnection: AndroidEditorConnection? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val selectionReader = dev.zeroinput.ime.clipboard.ClipboardSelectionReader({ mainHandler.post(it) })
    private val modelRanking = dev.zeroinput.ime.model.ModelRankingCoordinator(
        createScorer = { dev.zeroinput.model.RobertaMiniScorer(applicationContext) },
        post = { mainHandler.post(it) },
        currentController = { controller },
        allowed = { modelRankingAllowed() },
    )
    private val reconversionExpiry = Runnable { controller?.invalidateReconversion() }
    private var reconversionExpiryScheduled = false
    private val secureClipboardExecutor = BoundedExecutors.singleThread(
        name = "zeroinput-secure-clipboard",
        queueCapacity = 1,
    )
    /**
     * Local encrypted indexes are intentionally kept off the IME main thread.
     * Keystore access can block on first use even when the payload is small.
     */
    private val localDataExecutor = BoundedExecutors.singleThread(
        name = "zeroinput-local-data",
        queueCapacity = 2,
    )
    @Volatile
    private var recentEmojiCache: List<String> = emptyList()
    @Volatile
    private var secureClipboardCache: List<SecureClipboardItemUi> = emptyList()
    @Volatile
    private var localPanelRevision = 0L
    private var localPanelTask: Future<*>? = null
    @Volatile
    private var sessionSequence = 0L
    /** Monotonic UI interaction counter used to invalidate pending pastes. */
    private var interactionSequence = 0L
    @Volatile
    private var activeSession: InputSession? = null
    private var languagePackObserver: AutoCloseable? = null
    private var settingsObserver: AutoCloseable? = null
    private var personalizationObserver: AutoCloseable? = null
    private var personalizationSuggestionObserver: AutoCloseable? = null
    private val personalizationRefreshPending = AtomicBoolean(false)
    private var runtimeObserver: AutoCloseable? = null
    private var engineWarmupCoordinator: EngineWarmupCoordinator? = null
    private var engineWarmupDelivery: EngineWarmupResultDelivery? = null
    private var engineWarmupTicket: Long? = null
    private var engineWarmupContext: EngineWarmupRequest? = null
    private var installedEngineWarmupContext: EngineWarmupRequest? = null
    private var unavailableEngineWarmupContext: EngineWarmupRequest? = null
    private var engineWarmupInFlight = false
    private var engineWarmupRetry = false
    private var languagePackReloadPending = false
    private var engineReloadPending = false
    private var sessionChineseOptions = ChineseInputOptions()
    private var sessionChineseEngine = ChineseEngineChoice.RIME
    private var nativeRetryRequested = false

    // Settings-derived values mirrored in memory so the per-keystroke engine
    // probe does not re-read SharedPreferences.  Refreshed synchronously by the
    // settings observer below, before its posted main-thread turn, so a
    // privacy tightening is visible to the next key dispatch without delay.
    // @Volatile: a commit() write could invoke the observer off the IME looper.
    @Volatile private var configuredChineseOptions = ChineseInputOptions()
    @Volatile private var configuredChineseEngine = ChineseEngineChoice.RIME
    @Volatile private var configuredPrivacy = PrivacyConfiguration()
    @Volatile private var configuredHapticFeedback = true

    private fun refreshConfiguredSettings() {
        configuredChineseOptions = graph.settings.chineseInputOptions
        configuredChineseEngine = graph.settings.chineseEngine
        configuredPrivacy = graph.settings.privacyConfiguration()
        configuredHapticFeedback = graph.settings.hapticFeedbackEnabled
    }
    @Volatile
    private var secureClipboardRequest: SecureClipboardRequest? = null
    private var pasteConsentObserver: AutoCloseable? = null
    /**
     * Invalidates asynchronous writes when the active privacy/session
     * context changes.  It is deliberately independent from the UI
     * interaction counter: ordinary key presses should not silently drop a
     * legitimate emoji-history write just because the worker was busy.
     */
    private val personalizationWriteGeneration = AtomicLong(0L)

    init {
        // InputMethodService selects Theme_InputMethod during onCreate and
        // does not apply the manifest's component theme to its SoftInputWindow.
        // Set the MaterialComponents-compatible theme before that lifecycle
        // step so MaterialButton can be inflated safely on every API level.
        setTheme(R.style.Theme_ZeroInput_InputMethod)
    }

    override fun onCreate() {
        super.onCreate()
        refreshConfiguredSettings()
        pasteConsentObserver = graph.securePaste.observe { bindPasteConsent(authenticationFinished = true) }
        expressionObserver = graph.observeExpressions {
            mainHandler.post {
                personalExpressionCache = PersonalExpressionsUi()
                personalExpressionRevision = -1L
                inputView?.let(::renderLocalPanels)
            }
        }
        graph.clipboardGuard.attachIme()
        clipboardGuardObserver = graph.clipboardGuard.observe { state ->
            inputView?.renderClipboardGuard(state.options.listening && state.options.keyboardReminder, state.ticket != null)
        }
        engineWarmupDelivery = EngineWarmupResultDelivery(
            post = { runnable -> mainHandler.post(runnable) },
            deliver = ::handleEngineWarmupResult,
        )
        engineWarmupCoordinator = EngineWarmupCoordinator(
            executor = graph.engineExecutor,
            prepare = graph::prepareEngine,
            dispatch = { result ->
                // Engine results are never applied from the worker.  Keeping
                // the handoff on the IME looper makes controller state and
                // InputConnection ownership single-threaded.
                val delivery = engineWarmupDelivery
                if (delivery != null) {
                    delivery.offer(result)
                } else {
                    (result as? EngineWarmupResult.Prepared)?.engine?.close()
                }
            },
        )
        // Language-pack discovery is deliberately asynchronous.  If the IME
        // starts before discovery completes, retry the selected pack when the
        // registry publishes its snapshot instead of silently sticking to the
        // base engine for the lifetime of the session.
        languagePackObserver = graph.addLanguagePackListener {
            mainHandler.post {
                val session = activeSession ?: return@post
                // Pack enable/disable/delete may be initiated from Settings
                // while this IME session remains alive.  Reconciliation can
                // replace the engine, so invalidate any pending authenticated
                // action tied to the old UI/engine state first.
                registerInteraction()
                reconcileLanguagePackSession(session)
            }
        }
        settingsObserver = graph.settings.addChangeListener {
            // SharedPreferences callbacks can arrive before the posted main
            // turn below. Advance the generation immediately so a queued
            // personal-data write cannot win a race with a privacy change.
            invalidatePendingPersonalization()
            // Refresh the mirrored settings here, still synchronously, so the
            // next key dispatch on the IME looper observes the new values.
            refreshConfiguredSettings()
            mainHandler.post {
                // Settings can be changed while the authentication activity
                // is in the foreground (for example by another settings
                // window). Treat every change as a new UI state so a grant
                // can never outlive the configuration under which it began.
                registerInteraction()
                inputView?.cancelPendingGestures()
                controller?.clearModelRanking()
                refreshKeyboardAppearance()
                val session = activeSession
                if (session != null) {
                    syncSessionPrivacy()
                    reconcileChineseOptions()
                    scheduleEngineWarmup(session)
                    if (session.controller.state.language != graph.settings.lastLanguage ||
                        session.controller.state.languagePackKey != graph.settings.lastLanguagePackKey
                    ) {
                        reconcileLanguagePackSession(session)
                    }
                    inputView?.let(::renderLocalPanels)
                }
            }
        }
        personalizationObserver = graph.addPersonalizationListener {
            invalidatePendingPersonalization()
            mainHandler.post {
                // Clearing personal data changes the visible candidate/history
                // state. Invalidate actions started before the clear.
                if (activeSession != null) registerInteraction()
                activeSession?.controller?.reset()
                inputView?.let(::renderLocalPanels)
            }
        }
        personalizationSuggestionObserver = graph.addPersonalizationSuggestionListener {
            schedulePersonalizationRefresh()
        }
        runtimeObserver = graph.rime.runtime.addStateListener { state ->
            mainHandler.post {
                val session = activeSession ?: return@post
                // A session opened while native Rime was warming up starts on
                // the safe fallback engine.  Prepare the native replacement
                // off the IME thread and install it only when the context is
                // still current.
                if (state == RimeRuntimeState.READY) scheduleEngineWarmup(session, force = true)
                renderEngineStatus()
            }
        }
    }

    override fun onCreateInputView(): View {
        modelRanking.invalidate()
        inputView?.release()
        val appearance = graph.settings.keyboardAppearance
        val view = ZeroInputView(dev.zeroinput.ime.settings.KeyboardThemeContext.create(this, appearance.theme))
        currentAppearance = appearance
        view.setKeyboardHeight(appearance.height)
        currentInputEditorInfo?.let { view.startEditor(dev.zeroinput.ime.core.EditorInputOptions.from(it)) }
        inputView = view
        bindView(view)
        renderLocalPanels(view)
        controller?.state?.let(view::renderSession)
        renderEngineStatus()
        return view
    }

    private fun refreshKeyboardAppearance() {
        if (inputView == null || currentAppearance == graph.settings.keyboardAppearance) return
        setInputView(onCreateInputView())
        updateNavigationBarAppearance()
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        endInputSession(reset = false)
        val token = ++sessionSequence
        val connectionBinding = SessionConnectionBinding(currentInputConnection)
        val currentSubtype = getSystemService(InputMethodManager::class.java)?.currentInputMethodSubtype
        val subtypeLanguage = languageForSubtype(currentSubtype)
        val initialLanguage = subtypeLanguage ?: graph.settings.lastLanguage
        if (subtypeLanguage != null && subtypeLanguage != graph.settings.lastLanguage) {
            // Android can deliver the subtype callback before this lifecycle
            // method, or omit it while restoring an IME. Persist the system's
            // choice so later key/settings callbacks cannot switch the session
            // back to the previous language. A pack selected for the old
            // language is no longer valid in that case.
            graph.settings.lastLanguage = subtypeLanguage
            graph.settings.lastLanguagePackKey = null
        }
        val initialPackKey = graph.settings.lastLanguagePackKey
        sessionChineseOptions = graph.settings.chineseInputOptions
        sessionChineseEngine = graph.settings.chineseEngine
        val editor = AndroidEditorConnection(attribute.initialSelStart, attribute.initialSelEnd,
            onCommitted = modelRanking::committed, onContextInvalidated = modelRanking::invalidate) {
            if (activeSession?.token == token) connectionBinding.resolve(currentInputConnection) else null
        }
        editorConnection = editor
        val newController = InputSessionController(
            connection = editor,
            // The first frame must not wait for librime construction or a
            // language-pack dictionary scan.  The warm-up coordinator will
            // replace this in-memory engine when the worker is ready.
            engineProvider = graph::createImmediateEngine,
            personalization = graph.personalization,
            onStateChanged = { state ->
                inputView?.renderSession(state)
                if (!state.canReconvert) {
                    mainHandler.removeCallbacks(reconversionExpiry)
                    reconversionExpiryScheduled = false
                } else if (!reconversionExpiryScheduled) {
                    reconversionExpiryScheduled = true
                    mainHandler.postDelayed(reconversionExpiry, 30_000)
                }
                renderEngineStatus()
                refreshModelRanking()
            },
            languagePackProvider = { null },
            languagePackDiscoveryComplete = graph::isLanguagePackDiscoveryComplete,
            deferHeavyEngineCreation = true,
        )
        val session = InputSession(token, newController, connectionBinding, attribute.packageName)
        activeSession = session
        controller = newController
        inputView?.startEditor(dev.zeroinput.ime.core.EditorInputOptions.from(attribute))
        newController.start(
            editorInfo = attribute,
            initialLanguage = initialLanguage,
            privacyConfiguration = graph.settings.privacyConfiguration(),
            languagePackKey = initialPackKey,
        )
        scheduleEngineWarmup(session, force = true)
        inputView?.let(::renderLocalPanels)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputViewActive = true
        refreshKeyboardAppearance()
        if (graph.clipboardGuard.state.status in setOf(
                dev.zeroinput.ime.clipboardguard.ClipboardGuardStatus.UNAVAILABLE,
                dev.zeroinput.ime.clipboardguard.ClipboardGuardStatus.BLOCKED,
                dev.zeroinput.ime.clipboardguard.ClipboardGuardStatus.FAILED,
            )) graph.clipboardGuard.retryMonitoring()
        updateNavigationBarAppearance()
        syncSessionPrivacy()
        reconcileChineseOptions()
        inputView?.let(::renderLocalPanels)
        bindPasteConsent()
    }

    private fun updateNavigationBarAppearance() {
        val imeWindow = window?.window ?: return
        val isNight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(imeWindow, imeWindow.decorView).isAppearanceLightNavigationBars = !isNight
        val context = inputView?.context ?: this
        @Suppress("DEPRECATION")
        imeWindow.navigationBarColor = com.google.android.material.color.MaterialColors.getColor(context,
            com.google.android.material.R.attr.colorSurface, android.graphics.Color.BLACK)
    }

    override fun onFinishInput() {
        endInputSession(reset = true)
        inputView?.let(::renderLocalPanels)
        super.onFinishInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        modelRanking.invalidate()
        controller?.clearModelRanking()
        controller?.invalidateReconversion()
        inputView?.cancelPendingGestures()
        inputViewActive = false
        cancelSecureClipboardRequest()
        graph.securePaste.leaveEditor()
        inputView?.renderPasteConfirmation(false)
        inputView?.renderCopySelectionAvailable(false)
        invalidatePendingPersonalization()
        clearLocalPanelCaches()
        inputView?.renderExpressions(false, PersonalExpressionsUi(), emptyList())
        super.onFinishInputView(finishingInput)
    }

    override fun onUnbindInput() {
        // A client can disappear without a new editor starting immediately.
        // Invalidate the session here as well so pending authentication can
        // never target a connection that is no longer owned by that client.
        endInputSession(reset = false)
        inputView?.let(::renderLocalPanels)
        super.onUnbindInput()
    }

    override fun onDestroy() {
        pasteConsentObserver?.close()
        pasteConsentObserver = null
        graph.securePaste.leaveEditor()
        modelRanking.close()
        expressionObserver?.close()
        expressionObserver = null
        clipboardGuardObserver?.close()
        clipboardGuardObserver = null
        graph.clipboardGuard.detachIme()
        endInputSession(reset = false)
        languagePackObserver?.close()
        languagePackObserver = null
        settingsObserver?.close()
        settingsObserver = null
        personalizationObserver?.close()
        personalizationObserver = null
        personalizationSuggestionObserver?.close()
        personalizationSuggestionObserver = null
        runtimeObserver?.close()
        runtimeObserver = null
        // Close the owner of results already posted to the main looper before
        // removing callbacks.  Otherwise a removed callback could orphan a
        // prepared native engine after the coordinator has released it.
        engineWarmupDelivery?.close()
        engineWarmupDelivery = null
        engineWarmupCoordinator?.close()
        engineWarmupCoordinator = null
        cancelSecureClipboardRequest()
        secureClipboardExecutor.shutdownNow()
        selectionReader.close()
        BoundedExecutors.purge(secureClipboardExecutor)
        localPanelTask?.cancel(true)
        BoundedExecutors.purge(localDataExecutor)
        localDataExecutor.shutdownNow()
        personalizationRefreshPending.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        inputView?.release()
        inputView = null
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (editorConnection?.updateSelection(newSelStart, newSelEnd, candidatesStart, candidatesEnd) == true) {
            cancelSecureClipboardRequest()
            graph.securePaste.editorChanged(pasteEditorIdentity())
            controller?.clearModelRanking()
            controller?.invalidateReconversion()
        }
    }

    /**
     * ZeroInput is a touch-first keyboard. Some Android 16 devices expose a
     * physical/virtual qwerty configuration even when no usable hardware
     * keyboard is attached; the framework default would then skip creating
     * the input view entirely.
     */
    override fun onEvaluateInputViewShown(): Boolean {
        // Keep the framework bookkeeping while overriding the hard-keyboard
        // heuristic for touch-first devices.
        super.onEvaluateInputViewShown()
        return true
    }

    /**
     * The default implementation also suppresses implicit show requests when
     * a hard-keyboard configuration is reported. Returning true keeps the
     * IME window available after switching from system settings and on OEM
     * builds that report that configuration conservatively.
     */
    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean = true

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        // A subtype change is an input-surface interaction even when the
        // locale is not one of our built-in labels.  It must invalidate a
        // pending authenticated paste just like a keyboard-page change.
        registerInteraction()
        invalidatePendingPersonalization()
        languageForSubtype(newSubtype)?.let(::switchTo)
        activeSession?.let { scheduleEngineWarmup(it, force = true) }
    }

    @Suppress("DEPRECATION")
    private fun languageForSubtype(subtype: InputMethodSubtype?): InputLanguage? {
        val values = sequenceOf(subtype?.languageTag.orEmpty(), subtype?.locale.orEmpty())
            .map(String::trim)
            .filter(String::isNotEmpty)
        return values.map { value ->
            value.substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)
        }.mapNotNull { language ->
            when (language) {
                "en" -> InputLanguage.ENGLISH
                "zh" -> InputLanguage.CHINESE
                else -> null
            }
        }.firstOrNull()
    }

    private fun bindView(view: ZeroInputView) {
        val guard = graph.clipboardGuard.state
        view.renderClipboardGuard(guard.options.listening && guard.options.keyboardReminder, guard.ticket != null)
        view.onClipboardGuardRequested = {
            registerInteraction()
            launchActivity(ClipboardGuardSettingsActivity::class.java)
        }
        view.onTouchStarted = modelRanking::interaction
        view.onTouchFinished = ::refreshModelRanking
        view.onUserInteraction = { modelRanking.invalidate(); registerInteraction(); syncSessionPrivacy() }
        view.onKeyboardAction = ::handleKeyboardAction
        view.onClearCompositionRequested = {
            registerInteraction()
            val hadComposition = controller?.state?.snapshot?.isComposing == true
            syncSessionPrivacy()
            val composing = controller?.state?.snapshot?.isComposing == true
            if (composing) controller?.reset()
            maybeReloadEngine()
            hadComposition || composing
        }
        view.onEngineRetryRequested = ::retryEngine
        view.onScriptSwitchRequested = {
            registerInteraction()
            val current = graph.settings.chineseInputOptions
            graph.settings.chineseInputOptions = current.copy(script =
                if (current.script == dev.zeroinput.engine.api.ChineseScript.SIMPLIFIED)
                    dev.zeroinput.engine.api.ChineseScript.TRADITIONAL
                else dev.zeroinput.engine.api.ChineseScript.SIMPLIFIED)
            refreshConfiguredSettings()
            reconcileChineseOptions()
        }
        view.onCandidateSelected = { handleControllerCommand(InputCommand.SelectCandidate(it)) }
        view.onReconvertRequested = { handleControllerCommand(InputCommand.ReconvertLast) }
        view.onUndoSelectionRequested = { handleControllerCommand(InputCommand.UndoSelection) }
        view.onSyllableRequested = { handleControllerCommand(InputCommand.SelectSyllable) }
        view.onReadingSelected = { handleControllerCommand(InputCommand.SelectReading(it)) }
        view.onLayoutSwitchRequested = {
            registerInteraction()
            val options = graph.settings.chineseInputOptions
            graph.settings.chineseInputOptions = options.copy(keyboardLayout =
                if (options.keyboardLayout == ChineseKeyboardLayout.FULL) ChineseKeyboardLayout.NINE_KEY else ChineseKeyboardLayout.FULL)
            refreshConfiguredSettings()
            reconcileChineseOptions()
            activeSession?.let { scheduleEngineWarmup(it) }
        }
        view.onCandidatePageChanged = { handleControllerCommand(InputCommand.ChangeCandidatePage(it)) }
        view.onEmojiSelected = ::commitEmoji
        view.onExpressionFavoriteRequested = ::setExpressionFavorite
        view.onExpressionManagementRequested = { id ->
            registerInteraction()
            syncSessionPrivacy()
            if (activeSession?.controller?.state?.privacy?.personalizationAllowed == true) {
                startActivity(Intent(this, ExpressionManagerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(ExpressionManagerActivity.EDIT_ID, id))
            }
        }
        view.onSecureClipboardSelected = ::unlockAndCommitSecureItem
        view.onCopySelectionRequested = ::copySelectedText
        view.onPasteConfirmed = ::confirmSecurePaste
        view.onPasteCancelled = { registerInteraction(); inputView?.returnToKeyboard() }
        view.onSettingsRequested = {
            registerInteraction()
            launchActivity(MainActivity::class.java)
        }
        view.onSecureClipboardManagementRequested = {
            launchActivity(SecureClipboardManagerActivity::class.java)
        }
    }

    private fun handleKeyboardAction(action: KeyboardAction) {
        registerInteraction()
        syncSessionPrivacy()
        maybeReloadLanguagePack()
        if (action is KeyboardAction.Text || action == KeyboardAction.Backspace) modelRanking.typing()
        else if (action != KeyboardAction.Space && action != KeyboardAction.Enter) modelRanking.invalidate()
        if (configuredHapticFeedback) {
            inputView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        when (action) {
            is KeyboardAction.Text -> controller?.handle(InputCommand.Text(action.value))
            is KeyboardAction.LiteralText -> controller?.handle(InputCommand.LiteralText(action.value))
            KeyboardAction.Backspace -> controller?.handle(InputCommand.Backspace)
            KeyboardAction.Space -> controller?.handle(InputCommand.Space)
            KeyboardAction.Enter -> controller?.handle(InputCommand.Enter)
            KeyboardAction.SwitchLanguage -> {
                controller?.switchLanguage()
                controller?.state?.language?.let {
                    graph.settings.lastLanguage = it
                    graph.settings.lastLanguagePackKey = null
                    selectSystemSubtype(it)
                }
            }
            KeyboardAction.OpenSettings -> launchActivity(MainActivity::class.java)
            KeyboardAction.ShowSecureClipboard -> Unit
            KeyboardAction.ShowEmoji -> Unit
            KeyboardAction.Shift,
            KeyboardAction.ShowLetters,
            KeyboardAction.ShowSymbols,
            KeyboardAction.ShowMoreSymbols,
            -> Unit
        }
        maybeReloadLanguagePack()
    }

    private fun handleControllerCommand(command: InputCommand) {
        if (command == InputCommand.ReconvertLast || command == InputCommand.UndoSelection ||
            command is InputCommand.SelectReading || command == InputCommand.SelectSyllable) modelRanking.invalidate()
        registerInteraction(preserveReconversion = command == InputCommand.ReconvertLast)
        syncSessionPrivacy()
        maybeReloadLanguagePack()
        controller?.handle(command)
        maybeReloadLanguagePack()
    }

    @Suppress("DEPRECATION")
    private fun selectSystemSubtype(language: InputLanguage) {
        val manager = getSystemService(InputMethodManager::class.java) ?: return
        val method = manager.inputMethodList.firstOrNull {
            it.packageName == packageName && it.serviceName == ZeroInputService::class.java.name
        } ?: return
        val subtype = (0 until method.subtypeCount).asSequence()
            .map(method::getSubtypeAt)
            .firstOrNull { languageForSubtype(it) == language }
            ?: return
        // Keep Android's selection in sync so a new editor or configuration
        // change restores the language chosen on the keyboard.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            switchInputMethod(method.id, subtype)
        } else {
            val token = window?.window?.attributes?.token ?: return
            manager.setInputMethodAndSubtype(token, method.id, subtype)
        }
    }

    private fun commitEmoji(entry: EmojiEntry) {
        modelRanking.invalidate()
        registerInteraction()
        syncSessionPrivacy()
        val session = activeSession ?: return
        val connection = currentInputConnection ?: return
        val boundConnection = session.connectionBinding.resolve(connection) ?: return
        if (!isSessionActive(session, boundConnection)) return
        val personalizationAllowed = session.controller.state.privacy.personalizationAllowed
        if (!expressionIsCurrent(entry, personalizationAllowed)) return
        val value = entry.value
        val emojiRevision = graph.emojiHistory.currentRevision()
        val writeGeneration = personalizationWriteGeneration.get()
        session.controller.reset()
        if (!isSessionActive(session, boundConnection)) return
        if (!boundConnection.commitText(value, 1)) return
        if (personalizationAllowed) {
            // Recording history is encrypted I/O and must not delay the key
            // event that just committed the emoji.
            runCatching {
                BoundedExecutors.purge(localDataExecutor)
                localDataExecutor.execute {
                    if (personalizationWriteGeneration.get() == writeGeneration) {
                        runCatching { graph.emojiHistory.recordIfRevision(value, emojiRevision) {
                            personalizationWriteGeneration.get() == writeGeneration
                        } }
                    }
                    mainHandler.post {
                        if (activeSession === session &&
                            personalizationWriteGeneration.get() == writeGeneration &&
                            inputView != null
                        ) {
                            inputView?.let(::renderLocalPanels)
                        }
                    }
                }
            }
        }
        inputView?.let(::renderLocalPanels)
    }

    private fun syncSessionPrivacy() {
        val session = activeSession ?: return
        if (session.controller.updatePrivacy(configuredPrivacy)) {
            inputView?.cancelPendingGestures()
            // A prepared engine carries the old policy. Invalidate it before
            // publishing the new state, then let the worker build a context
            // that matches the tightened policy.
            cancelEngineWarmup(clearInstalled = true)
            invalidatePendingPersonalization()
            // A policy change can happen while the authentication activity is
            // in the foreground.  Invalidate any grant started under the old
            // policy before publishing the new session state.
            registerInteraction()
            inputView?.let(::renderLocalPanels)
            scheduleEngineWarmup(session, force = true)
        }
    }

    private fun expressionIsCurrent(entry: EmojiEntry, allowed: Boolean): Boolean = if (entry.customId == null) {
        EmojiCatalog.find(entry.value) == entry
    } else {
        allowed && personalExpressionRevision == graph.expressions.revision() && entry in personalExpressionCache.custom
    }

    private fun setExpressionFavorite(entry: EmojiEntry, selected: Boolean) {
        registerInteraction()
        syncSessionPrivacy()
        val session = activeSession ?: return
        if (!session.controller.state.privacy.personalizationAllowed || !expressionIsCurrent(entry, true)) return
        val generation = personalizationWriteGeneration.get()
        val deletion = graph.expressions.generation()
        val contentRevision = graph.expressions.revision()
        val current = { personalizationWriteGeneration.get() == generation &&
            activeSession === session && (entry.customId == null || contentRevision == graph.expressions.revision()) }
        runCatching {
            localDataExecutor.execute {
                val result = runCatching { graph.expressions.favorite(entry.value, selected, deletion, current) }
                if (result.isSuccess) graph.notifyExpressionsChanged()
                mainHandler.post {
                    if (personalizationWriteGeneration.get() != generation || activeSession !== session) return@post
                    if (result.isFailure) Toast.makeText(this, R.string.expression_operation_failed, Toast.LENGTH_SHORT).show()
                    inputView?.let(::renderLocalPanels)
                }
            }
        }.onFailure { Toast.makeText(this, R.string.expression_operation_failed, Toast.LENGTH_SHORT).show() }
    }

    private fun unlockAndCommitSecureItem(id: String) {
        modelRanking.invalidate()
        registerInteraction()
        syncSessionPrivacy()
        if (!graph.settings.secureClipboardEnabled) {
            launchActivity(SecureClipboardManagerActivity::class.java)
            return
        }
        val session = activeSession ?: return
        if (session.controller.state.privacy.isSensitive) return
        val connection = currentInputConnection ?: return
        val boundConnection = session.connectionBinding.resolve(connection) ?: return
        if (!isSessionActive(session, boundConnection)) return
        val source = pasteEditorIdentity() ?: return
        graph.securePaste.request(id, source)
    }

    private fun bindPasteConsent(authenticationFinished: Boolean = false) {
        if (!inputViewActive) return
        val session = activeSession ?: return
        val identity = pasteEditorIdentity()
        // The OS credential screen may itself start a temporary sensitive editor.
        // It never receives a grant or a paste control; bind only after return.
        if (graph.securePaste.deferBinding(identity, authenticationFinished)) return
        if (identity == null || session.controller.state.privacy.isSensitive) cancelPasteConsent()
        else inputView?.renderPasteConfirmation(graph.securePaste.bind(session.token, identity))
    }

    private fun pasteEditorIdentity(): dev.zeroinput.ime.clipboard.PasteEditorIdentity? =
        currentInputEditorInfo?.let { info -> info.packageName?.let { name ->
            dev.zeroinput.ime.clipboard.PasteEditorIdentity(name, info.fieldId, info.inputType)
        } }

    private fun confirmSecurePaste() {
        syncSessionPrivacy()
        val session = activeSession ?: return
        val connection = session.connectionBinding.resolve(currentInputConnection) ?: return
        val identity = pasteEditorIdentity() ?: return
        if (!inputViewActive || session.controller.state.privacy.isSensitive) { cancelPasteConsent(); return }
        val consent = graph.securePaste.confirm(session.token, identity) ?: return
        val grant = consent.grant
        val id = consent.id
        val generation = consent.generation
        registerInteraction()
        val request = SecureClipboardRequest(id, session, connection, interactionSequence)
        secureClipboardRequest = request
        try {
            request.task = secureClipboardExecutor.submit {
                if (secureClipboardRequest !== request || !isSessionActive(session, connection) ||
                    !graph.settings.secureClipboardEnabled || generation != graph.secureClipboard.captureGeneration()) return@submit
                val result = runCatching {
                    graph.secureClipboard.read(id, grant, generation) {
                        secureClipboardRequest === request && isSessionActive(session, connection)
                    }
                }
                mainHandler.post {
                    if (secureClipboardRequest !== request) return@post
                    secureClipboardRequest = null
                    if (!isSessionActive(session, connection) || interactionSequence != request.interaction ||
                        !graph.settings.secureClipboardEnabled || generation != graph.secureClipboard.captureGeneration()) return@post
                    val value = result.getOrNull()
                    if (value == null) Toast.makeText(this, R.string.operation_failed, Toast.LENGTH_SHORT).show()
                    else {
                        session.controller.reset()
                        if (isSessionActive(session, connection)) connection.commitText(value, 1)
                        inputView?.returnToKeyboard()
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { clearSecureClipboardRequest(request) }
    }

    private fun cancelPasteConsent() {
        graph.securePaste.cancel()
        inputView?.renderPasteConfirmation(false)
    }

    private fun copySelectedText() {
        registerInteraction()
        syncSessionPrivacy()
        val session = activeSession ?: return
        if (!inputViewActive || session.controller.state.privacy.isSensitive) return
        val connection = session.connectionBinding.resolve(currentInputConnection) ?: return
        val length = editorConnection?.selectedLength() ?: 0
        val interaction = interactionSequence
        val generation = graph.secureClipboard.captureGeneration()
        val settingsGeneration = personalizationWriteGeneration.get()
        selectionReader.read(length, { connection.getSelectedText(0) }, {
            inputViewActive && isSessionActive(session, connection) && interactionSequence == interaction &&
                personalizationWriteGeneration.get() == settingsGeneration &&
                !session.controller.state.privacy.isSensitive && editorConnection?.selectedLength() == length
        }) { draft ->
            if (draft == null) {
                Toast.makeText(this, R.string.clipboard_selection_unavailable, Toast.LENGTH_SHORT).show()
            } else {
                val transfer = graph.clipboardSelectionTransfer
                val token = transfer.offer(dev.zeroinput.ime.clipboard.ClipboardImportDraft(draft, generation))
                try {
                    startActivity(Intent(this, dev.zeroinput.ime.clipboard.ClipboardSelectionImportActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(dev.zeroinput.ime.clipboard.ClipboardSelectionImportActivity.EXTRA_TOKEN, token))
                } catch (_: RuntimeException) {
                    transfer.close()
                    Toast.makeText(this, R.string.operation_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun renderLocalPanels(view: ZeroInputView) {
        val session = activeSession
        val personalizationAllowed = inputViewActive && session?.controller?.state?.privacy?.personalizationAllowed == true
        val sensitive = session?.controller?.state?.privacy?.isSensitive == true
        // Do not offer authenticated private snippets from a password/PIN
        // editor.  This prevents an accidental secure-clipboard paste into a
        // credential field while preserving the feature in ordinary editors.
        val enabled = session != null && graph.settings.secureClipboardEnabled && !sensitive
        view.renderCopySelectionAvailable(inputViewActive && session != null && !sensitive)
        if (!personalizationAllowed) recentEmojiCache = emptyList()
        if (!personalizationAllowed || personalExpressionRevision != graph.expressions.revision()) {
            personalExpressionCache = PersonalExpressionsUi()
        }
        if (!enabled) secureClipboardCache = emptyList()
        view.renderExpressions(personalizationAllowed, personalExpressionCache, if (personalizationAllowed) recentEmojiCache else emptyList())
        view.renderSecureClipboard(enabled, if (enabled) secureClipboardCache else emptyList())
        scheduleLocalPanelRefresh(personalizationAllowed, enabled)
    }

    private fun scheduleLocalPanelRefresh(personalizationAllowed: Boolean, secureClipboardEnabled: Boolean) {
        val revision = ++localPanelRevision
        val session = activeSession
        val generation = personalizationWriteGeneration.get()
        val deletion = graph.expressions.generation()
        val current = { revision == localPanelRevision && activeSession === session &&
            generation == personalizationWriteGeneration.get() }
        localPanelTask?.cancel(false)
        BoundedExecutors.purge(localDataExecutor)
        localPanelTask = runCatching {
            localDataExecutor.submit {
                if (!current()) return@submit
                val personal = if (personalizationAllowed) {
                    runCatching { graph.expressions.snapshot(deletion, current) }.getOrNull()
                } else null
                val recent = if (personalizationAllowed && current()) {
                    runCatching { graph.emojiHistory.recent(isCurrent = current) }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                val secureItems = if (secureClipboardEnabled) {
                    runCatching {
                        graph.secureClipboard.summaries().map {
                            SecureClipboardItemUi(it.id, it.displayName)
                        }
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                mainHandler.post {
                    if (!current() || inputView == null) return@post
                    val currentPersonalizationAllowed =
                        inputViewActive && activeSession?.controller?.state?.privacy?.personalizationAllowed == true
                    val currentSession = activeSession
                    val currentSecureClipboardEnabled = currentSession != null &&
                        graph.settings.secureClipboardEnabled &&
                        currentSession.controller.state.privacy.isSensitive.not()
                    // Do not retain personal panel data in memory after the
                    // session has become private/disabled, even if the worker
                    // completed a read that started under the old policy.
                    recentEmojiCache = if (currentPersonalizationAllowed) recent else emptyList()
                    secureClipboardCache = if (currentSecureClipboardEnabled) secureItems else emptyList()
                    personalExpressionRevision = personal?.revision ?: -1L
                    personalExpressionCache = if (currentPersonalizationAllowed && personal != null &&
                        personal.revision == graph.expressions.revision()) personal.data.presentation() else PersonalExpressionsUi()
                    inputView?.renderExpressions(currentPersonalizationAllowed, personalExpressionCache, recentEmojiCache)
                    inputView?.renderSecureClipboard(
                        currentSecureClipboardEnabled,
                        if (currentSecureClipboardEnabled) secureClipboardCache else emptyList(),
                    )
                }
            }
        }.getOrNull()
    }

    /**
     * Coalesces worker notifications into one main-thread refresh and binds
     * that refresh to the session that requested the query.  A burst of
     * prefixes therefore cannot enqueue an unbounded stream of UI work, and a
     * callback from an old editor cannot repaint a newly opened editor.
     */
    private fun schedulePersonalizationRefresh() {
        val session = activeSession ?: return
        if (!personalizationRefreshPending.compareAndSet(false, true)) return
        if (!mainHandler.post {
                personalizationRefreshPending.set(false)
                if (activeSession !== session) return@post
                if (!session.controller.state.privacy.personalizationAllowed) return@post
                session.controller.refreshPersonalization()
            }
        ) {
            personalizationRefreshPending.set(false)
        }
    }

    private fun scheduleEngineWarmup(session: InputSession, force: Boolean = false) {
        if (activeSession !== session) return
        reconcileChineseOptions()
        val request = session.warmupRequest(sessionChineseOptions, nativeRetryRequested, sessionChineseEngine)
        if (!request.privacy.suggestionsAllowed) {
            cancelEngineWarmup(clearInstalled = true)
            return
        }
        if (session.controller.state.snapshot.isComposing) {
            engineWarmupRetry = true
            return
        }
        if (installedEngineWarmupContext == request) return
        if (!force && unavailableEngineWarmupContext == request) return
        if (engineWarmupInFlight && engineWarmupContext == request) return

        if (nativeRetryRequested && graph.rime.runtime.state == RimeRuntimeState.FAILED) {
            session.controller.reloadEngineIfIdle()
        }

        unavailableEngineWarmupContext = null
        engineWarmupRetry = false
        engineWarmupContext = request
        engineWarmupInFlight = true
        val ticket = engineWarmupCoordinator?.request(request)
        if (ticket == null) {
            engineWarmupInFlight = false
            unavailableEngineWarmupContext = request
        } else {
            engineWarmupTicket = ticket
        }
        renderEngineStatus()
    }

    private fun handleEngineWarmupResult(result: EngineWarmupResult) {
        val currentRequest = engineWarmupContext
        if (!engineWarmupInFlight || result.ticket != engineWarmupTicket || result.request != currentRequest) {
            (result as? EngineWarmupResult.Prepared)?.engine?.close()
            return
        }
        engineWarmupInFlight = false
        engineWarmupTicket = null
        when (result) {
            is EngineWarmupResult.Unavailable -> {
                unavailableEngineWarmupContext = result.request
            }

            is EngineWarmupResult.Prepared -> {
                val session = activeSession
                if (session == null || !isWarmupSessionCurrent(session, result.request) ||
                    session.controller.state.snapshot.isComposing
                ) {
                    result.engine.close()
                    engineWarmupRetry = true
                    return
                }
                var transferred = false
                try {
                    // Engine replacement revokes editor writes. An unused paste consent
                    // contains no engine state and still requires a new foreground tap.
                    modelRanking.invalidate()
                    registerInteraction(preservePasteConsent = true)
                    transferred = session.controller.adoptPreparedEngine(result.engine)
                    if (transferred) {
                        installedEngineWarmupContext = result.request
                        unavailableEngineWarmupContext = null
                        engineWarmupRetry = false
                        inputView?.let(::renderLocalPanels)
                    } else {
                        engineWarmupRetry = true
                    }
                } finally {
                    if (!transferred) result.engine.close()
                }
            }
        }
        renderEngineStatus()
    }

    private fun isWarmupSessionCurrent(
        session: InputSession?,
        request: EngineWarmupRequest,
    ): Boolean = session != null && activeSession === session &&
        session.token == request.sessionToken &&
        session.packageName == request.packageName &&
        session.controller.state.language == request.language &&
            session.controller.state.languagePackKey == request.languagePackKey &&
            session.controller.state.privacy == request.privacy &&
            sessionChineseOptions == request.chineseOptions &&
            configuredChineseOptions == request.chineseOptions &&
            sessionChineseEngine == request.chineseEngine && configuredChineseEngine == request.chineseEngine

    private fun cancelEngineWarmup(clearInstalled: Boolean = false) {
        engineWarmupCoordinator?.cancel()
        engineWarmupTicket = null
        engineWarmupContext = null
        engineWarmupInFlight = false
        engineWarmupRetry = false
        unavailableEngineWarmupContext = null
        if (clearInstalled) installedEngineWarmupContext = null
    }

    private fun switchTo(language: InputLanguage) {
        val languageChanged = controller?.state?.language != language
        if (languageChanged) {
            cancelEngineWarmup(clearInstalled = true)
            val packKey = graph.settings.lastLanguagePackKey.takeIf {
                graph.settings.lastLanguage == language
            }
            controller?.setLanguage(language, packKey)
        }
        // A repeated subtype callback for the same language must not discard
        // a language pack selected for that language. Only a persisted
        // cross-language change makes the old pack invalid.
        if (graph.settings.lastLanguage != language) {
            graph.settings.lastLanguage = language
            graph.settings.lastLanguagePackKey = null
        }
        activeSession?.let { scheduleEngineWarmup(it, force = true) }
    }

    private fun launchActivity(activityClass: Class<*>) {
        startActivity(Intent(this, activityClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun endInputSession(reset: Boolean) {
        inputView?.cancelPendingGestures()
        registerInteraction(preservePasteConsent = true)
        graph.securePaste.leaveEditor()
        nativeRetryRequested = false
        invalidatePendingPersonalization()
        cancelEngineWarmup(clearInstalled = true)
        clearLocalPanelCaches()
        activeSession?.let { session ->
            if (reset) session.controller.reset()
            session.controller.close()
        }
        activeSession = null
        controller = null
        editorConnection = null
        mainHandler.removeCallbacks(reconversionExpiry)
        reconversionExpiryScheduled = false
        languagePackReloadPending = false
        engineReloadPending = false
        cancelSecureClipboardRequest()
    }

    private fun clearLocalPanelCaches() {
        localPanelRevision++
        localPanelTask?.cancel(false)
        BoundedExecutors.purge(localDataExecutor)
        recentEmojiCache = emptyList()
        personalExpressionCache = PersonalExpressionsUi()
        personalExpressionRevision = -1L
        inputView?.clearExpressionSession()
        secureClipboardCache = emptyList()
    }

    private fun reconcileLanguagePackSession(session: InputSession) {
        val configuredLanguage = graph.settings.lastLanguage
        val configuredKey = graph.settings.lastLanguagePackKey
        val currentLanguage = session.controller.state.language
        val currentKey = session.controller.state.languagePackKey
        if (configuredLanguage != currentLanguage || configuredKey != currentKey) {
            if (session.controller.state.snapshot.isComposing) {
                languagePackReloadPending = true
                cancelEngineWarmup(clearInstalled = true)
            } else {
                cancelEngineWarmup(clearInstalled = true)
                session.controller.setLanguage(configuredLanguage, configuredKey)
                languagePackReloadPending = false
                scheduleEngineWarmup(session, force = true)
            }
        } else if (configuredKey != null && graph.languagePackRegistry.contains(configuredKey)) {
            languagePackReloadPending = false
            scheduleEngineWarmup(session)
        } else if (configuredKey != null && graph.isLanguagePackDiscoveryComplete()) {
            // The registry has reached a terminal state and the selected key
            // is no longer available. Clear it before preparing the built-in
            // engine so the stale package cannot be resurrected by a callback.
            graph.settings.lastLanguagePackKey = null
            languagePackReloadPending = true
        } else {
            // Discovery may still be in flight.  Preserve the requested key
            // and retry after the registry callback instead of clearing it.
            languagePackReloadPending = configuredKey != null
        }
        inputView?.let(::renderLocalPanels)
    }

    private fun maybeReloadLanguagePack() {
        val session = activeSession ?: return
        val language = graph.settings.lastLanguage
        val key = graph.settings.lastLanguagePackKey
        if (language != session.controller.state.language || key != session.controller.state.languagePackKey) {
            if (session.controller.state.snapshot.isComposing) return
            cancelEngineWarmup(clearInstalled = true)
            session.controller.setLanguage(language, key)
            languagePackReloadPending = false
            scheduleEngineWarmup(session, force = true)
            inputView?.let(::renderLocalPanels)
            return
        }
        if (key == null) {
            languagePackReloadPending = false
            scheduleEngineWarmup(session)
            return
        }
        if (!graph.languagePackRegistry.contains(key)) return
        languagePackReloadPending = false
        scheduleEngineWarmup(session)
    }

    private fun maybeReloadEngine() {
        val session = activeSession ?: return
        if (graph.settings.lastLanguagePackKey != null) {
            engineReloadPending = false
            return
        }
        engineReloadPending = false
        scheduleEngineWarmup(session)
    }

    private fun reconcileChineseOptions() {
        val configured = configuredChineseOptions
        inputView?.renderChineseOptions(configured)
        val chosenEngine = configuredChineseEngine
        if (configured == sessionChineseOptions && chosenEngine == sessionChineseEngine) return
        val session = activeSession ?: return
        if (session.controller.state.snapshot.isComposing) {
            renderEngineStatus()
            return
        }
        cancelEngineWarmup(clearInstalled = true)
        sessionChineseOptions = configured
        sessionChineseEngine = chosenEngine
        if (session.controller.state.language == InputLanguage.CHINESE && session.controller.state.languagePackKey == null) {
            // Retire the native session before the worker can deploy a different prism.
            session.controller.reloadEngineIfIdle()
        }
        renderEngineStatus()
    }

    private fun renderEngineStatus() {
        val session = activeSession
        val state = session?.controller?.state
        val status = when {
            state == null || !state.privacy.suggestionsAllowed || state.language != InputLanguage.CHINESE ||
                state.languagePackKey != null -> InputEngineStatus.HIDDEN
            sessionChineseOptions != configuredChineseOptions || sessionChineseEngine != configuredChineseEngine -> InputEngineStatus.PENDING_CONFIGURATION
            (sessionChineseEngine == ChineseEngineChoice.RIME && graph.rime.runtime.state == RimeRuntimeState.FAILED) ||
                unavailableEngineWarmupContext != null -> InputEngineStatus.FAILED
            installedEngineWarmupContext == session.warmupRequest(sessionChineseOptions, nativeRetryRequested, sessionChineseEngine) -> InputEngineStatus.READY
            else -> InputEngineStatus.PREPARING
        }
        inputView?.renderEngineStatus(status)
        inputView?.renderChineseOptions(configuredChineseOptions)
        inputView?.renderActiveLayout(sessionChineseOptions.keyboardLayout)
    }

    private fun retryEngine() {
        registerInteraction()
        val session = activeSession ?: return
        nativeRetryRequested = true
        cancelEngineWarmup(clearInstalled = true)
        scheduleEngineWarmup(session, force = true)
        renderEngineStatus()
    }

    /** Records an interaction and cancels work that was authorized in an
     * older UI state.  All callers run on the IME main thread. */
    private fun registerInteraction(preserveReconversion: Boolean = false, preservePasteConsent: Boolean = false) {
        modelRanking.interaction()
        interactionSequence++
        if (!preservePasteConsent) cancelPasteConsent()
        cancelSecureClipboardRequest()
        if (!preserveReconversion) controller?.invalidateReconversion()
    }

    private fun invalidatePendingPersonalization() {
        modelRanking.invalidate()
        personalizationWriteGeneration.incrementAndGet()
        graph.personalization.invalidatePendingWrites()
    }

    private fun cancelSecureClipboardRequest() {
        selectionReader.cancel()
        secureClipboardRequest?.task?.cancel(true)
        BoundedExecutors.purge(secureClipboardExecutor)
        secureClipboardRequest = null
    }

    private fun modelRankingAllowed(): Boolean = inputViewActive && graph.settings.experimentalModelRanking &&
        graph.settings.learningEnabled && !graph.settings.incognitoMode &&
        activeSession?.connectionBinding?.resolve(currentInputConnection) != null &&
        controller?.state?.let { it.language == InputLanguage.CHINESE && it.languagePackKey == null &&
            it.privacy.personalizationAllowed && !it.privacy.isSensitive } == true

    private fun refreshModelRanking() {
        modelRanking.refresh(inputView?.modelRankingSurfaceAvailable == true)
    }

    private fun clearSecureClipboardRequest(request: SecureClipboardRequest) {
        if (secureClipboardRequest === request) {
            request.task?.cancel(true)
            BoundedExecutors.purge(secureClipboardExecutor)
            secureClipboardRequest = null
        }
    }

    private fun isSessionActive(session: InputSession, connection: InputConnection): Boolean =
        activeSession === session && session.token == sessionSequence &&
            session.connectionBinding.connection === connection &&
            currentInputConnection === connection

    private data class InputSession(
        val token: Long,
        val controller: InputSessionController,
        val connectionBinding: SessionConnectionBinding,
        val packageName: String?,
    ) {
        fun warmupRequest(options: ChineseInputOptions, retry: Boolean, engine: ChineseEngineChoice): EngineWarmupRequest = EngineWarmupRequest(
            sessionToken = token,
            language = controller.state.language,
            languagePackKey = controller.state.languagePackKey,
            packageName = packageName,
            privacy = controller.state.privacy,
            chineseOptions = options,
            retryInitialization = retry,
            chineseEngine = engine,
        )
    }

    private class SessionConnectionBinding(initial: InputConnection?) {
        /**
         * The connection is captured when the input session starts.  A null
         * connection is deliberately not resolved later: doing so could bind
         * an authentication result to a different editor after a client
         * switch.
         */
        val connection: InputConnection? = initial

        fun resolve(current: InputConnection?): InputConnection? =
            connection?.takeIf { it === current }
    }

    private data class SecureClipboardRequest(
        val id: String,
        val session: InputSession,
        val connection: InputConnection,
        val interaction: Long,
    ) {
        @Volatile
        var task: Future<*>? = null
    }
}
