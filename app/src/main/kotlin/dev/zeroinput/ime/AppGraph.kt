package dev.zeroinput.ime

import dev.zeroinput.engine.api.ChineseInputOptions

import android.content.Context
import dev.zeroinput.ime.clipboardguard.ClipboardGuardPreferences
import dev.zeroinput.ime.clipboardguard.ClipboardGuardRuntime
import dev.zeroinput.engine.api.InputEngine
import dev.zeroinput.engine.api.EngineDescriptor
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.engine.api.LearnedSuggestionSource
import dev.zeroinput.engine.api.WeightedTerm
import dev.zeroinput.engine.english.EnglishEngineFactory
import dev.zeroinput.engine.rime.RimeEngineFactory
import dev.zeroinput.ime.personalization.QueuedPersonalizationStore
import dev.zeroinput.ime.settings.SettingsRepository
import dev.zeroinput.ime.concurrency.BoundedExecutors
import dev.zeroinput.languagepack.LanguagePackInstaller
import dev.zeroinput.languagepack.LanguagePackRegistry
import dev.zeroinput.languagepack.InstalledLanguagePack
import dev.zeroinput.userdata.EmojiHistoryRepository
import dev.zeroinput.userdata.SecureClipboardVault
import dev.zeroinput.userdata.UserLexiconRepository
import dev.zeroinput.userdata.AiConfiguration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import dev.zeroinput.ime.ai.AiCoordinator
import dev.zeroinput.ime.ai.AiDataGeneration
import dev.zeroinput.ime.ai.OpenAiCompatibleProvider
import dev.zeroinput.ai.api.DeviceAgentPolicy
import dev.zeroinput.ai.api.DeviceApprovalMode
import dev.zeroinput.ai.api.DeviceCapabilityScope
import dev.zeroinput.ime.ai.device.DeviceAgentAccessibilityBroker
import dev.zeroinput.ime.ai.device.DeviceAgentCoordinator
import dev.zeroinput.ime.ai.device.DeviceStage6Controller
import dev.zeroinput.ime.ai.device.AndroidDeviceStage6Platform

class AppGraph(context: Context) : AutoCloseable {
    private val applicationContext = context.applicationContext
    /**
     * Shared engine worker.  Rime initialization, language-pack discovery and
     * prepared engine creation are serialized to avoid competing for storage
     * and to keep librime's process-wide runtime transitions ordered.  The
     * queue is deliberately bounded because session changes can arrive in a
     * burst while this worker is still warming up.
     */
    internal val engineExecutor: ExecutorService = BoundedExecutors.singleThread(
        name = "zeroinput-engine-worker",
        queueCapacity = 2,
    )
    /**
     * Optional index and pack discovery must not delay creation of the first
     * native session. Native runtime work remains serialized on
     * [engineExecutor].
     */
    private val engineMaintenanceExecutor: ExecutorService = BoundedExecutors.singleThread(
        name = "zeroinput-engine-maintenance",
        queueCapacity = 1,
    )
    internal val aiExecutor: ExecutorService = BoundedExecutors.singleThread(
        name = "zeroinput-ai-worker",
        queueCapacity = 1,
    )
    internal val aiProbeExecutor: ExecutorService = BoundedExecutors.singleThread(
        name = "zeroinput-ai-probe",
        queueCapacity = 1,
    )
    internal val aiCancellationExecutor: ExecutorService = BoundedExecutors.singleThread(
        name = "zeroinput-ai-cancel",
        queueCapacity = 2,
    )
    internal val aiDocumentExecutor: ExecutorService = BoundedExecutors.singleThread("zeroinput-ai-document", 1)
    private val pageReferenceExecutor = BoundedExecutors.singleThread("zeroinput-page-reference", 1)
    internal val aiPersistenceExecutor: ExecutorService = BoundedExecutors.singleThread(
        name = "zeroinput-ai-storage",
        queueCapacity = 2,
    )
    internal val deviceAgentExecutor: ExecutorService = BoundedExecutors.singleThread(
        name = "zeroinput-device-agent",
        queueCapacity = 4,
    )
    internal val deviceAgentScreenshotExecutor: ExecutorService = BoundedExecutors.singleThread(
        name = "zeroinput-device-agent-screenshot",
        queueCapacity = 1,
    )
    // Stage 6 providers may block in binder calls that do not accept cancellation.
    // Keep those reads off the broker/provider worker so one bad URI cannot stall
    // ordinary observation and action delivery.
    internal val deviceAgentStage6Executor: ExecutorService = BoundedExecutors.singleThread(
        name = "zeroinput-device-agent-stage6",
        queueCapacity = 2,
    )

    val settings = SettingsRepository(applicationContext)
    internal val keyboardBackgrounds = dev.zeroinput.ime.settings.KeyboardBackgroundStore(applicationContext, settings)
    internal val clipboardGuardPreferences = ClipboardGuardPreferences(applicationContext)
    internal val clipboardGuard = ClipboardGuardRuntime(applicationContext, clipboardGuardPreferences)
    val userLexicon = UserLexiconRepository(applicationContext)
    val dictionaryEdits = dev.zeroinput.userdata.DictionaryEditRepository(applicationContext)
    val publicDictionaries = dev.zeroinput.languagepack.PublicDictionaryStore(applicationContext)
    val builtInDictionaries = dev.zeroinput.languagepack.BuiltInDictionaryStore(applicationContext, publicDictionaries)
    private val dictionaryPersonalization = dev.zeroinput.ime.personalization.DictionaryPersonalizationStore(
        userLexicon, dictionaryEdits, builtInDictionaries::activeIds, { settings.chineseInputOptions })
    private val queuedPersonalization = QueuedPersonalizationStore(
        delegate = dictionaryPersonalization,
        preload = { userLexicon.warmUp(); dictionaryPersonalization.prepare() },
    )
    val personalization: dev.zeroinput.engine.api.PersonalizationStore = queuedPersonalization
    val emojiHistory = EmojiHistoryRepository(applicationContext)
    val expressions = dev.zeroinput.userdata.PersonalExpressionRepository(applicationContext)
    private val expressionListeners = CopyOnWriteArrayList<() -> Unit>()

    fun notifyExpressionsChanged() { expressionListeners.forEach { runCatching(it) } }

    fun observeExpressions(listener: () -> Unit): AutoCloseable {
        expressionListeners += listener
        return AutoCloseable { expressionListeners -= listener }
    }
    val secureClipboard = SecureClipboardVault(applicationContext)
    val aiConfiguration = dev.zeroinput.userdata.AiConfigurationRepository(applicationContext)
    val aiConversations = dev.zeroinput.userdata.AiConversationRepository(applicationContext)
    private val aiConfigurationStorage = dev.zeroinput.ime.ai.AiConfigurationStorage(aiConfiguration, aiConversations)
    private val aiConfigurationState = dev.zeroinput.ime.ai.AiConfigurationState()
    val aiCoordinator = AiCoordinator(OpenAiCompatibleProvider({ aiConfigurationSnapshot() ?: AiConfiguration() }, aiExecutor, aiCancellationExecutor))
    val aiDataGeneration = AiDataGeneration()
    internal val aiContentInbox = dev.zeroinput.ime.ai.AiContentInbox()
    internal val pageReferences = dev.zeroinput.ime.ai.page.PageReferenceBroker(
        pageReferenceExecutor, { android.os.Handler(android.os.Looper.getMainLooper()).post(it) },
        enabled = { settings.aiPageReferencesEnabled && settings.learningEnabled && !settings.incognitoMode &&
            aiConfigurationSnapshot()?.let { it.enabled && it.networkAllowed } == true },
    )
    internal val deviceAgentBroker = DeviceAgentAccessibilityBroker(
        executor = deviceAgentExecutor,
        post = { android.os.Handler(android.os.Looper.getMainLooper()).post(it) },
        runtimeAllowed = { policy -> settings.learningEnabled && !settings.incognitoMode &&
            aiConfigurationSnapshot()?.let { it.enabled && it.networkAllowed } == true &&
            policy.isWithin(deviceAgentPolicySnapshot()) },
    )
    /** Direct stage 6 data access remains a separate approval and generation gate. */
    internal val deviceStage6Controller = DeviceStage6Controller(
        executor = deviceAgentStage6Executor,
        platform = AndroidDeviceStage6Platform(applicationContext),
        unlocked = {
            !applicationContext.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
        },
        foregroundPackage = { deviceAgentBroker.currentWindow()?.packageName },
    )
    internal val deviceAgentCoordinator = DeviceAgentCoordinator(
        provider = OpenAiCompatibleProvider(
            { aiConfigurationSnapshot() ?: AiConfiguration() },
            deviceAgentExecutor,
            aiCancellationExecutor,
        ),
        broker = deviceAgentBroker,
        executor = deviceAgentExecutor,
        post = { android.os.Handler(android.os.Looper.getMainLooper()).post(it) },
        schedule = { delay, action ->
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val callback = Runnable(action)
            handler.postDelayed(callback, delay)
            AutoCloseable { handler.removeCallbacks(callback) }
        },
        text = dev.zeroinput.ime.ai.device.DeviceAgentDescriptions(applicationContext),
        stage6Controller = deviceStage6Controller,
    )
    private val aiImportSettingsObserver = settings.addChangeListener {
        aiContentInbox.clear()
        pageReferences.invalidate()
        deviceAgentCoordinator.stop()
        deviceAgentBroker.invalidate()
        deviceStage6Controller.invalidate()
    }

    fun deviceAgentPolicySnapshot(): DeviceAgentPolicy {
        val scope = settings.deviceAgentScope
        return DeviceAgentPolicy(
        enabled = settings.deviceAgentEnabled && scope != DeviceCapabilityScope.DISABLED,
        disclosureAccepted = settings.deviceAgentDisclosureAccepted,
        stage6DisclosureAccepted = settings.deviceAgentStage6DisclosureAccepted,
        stage6Capabilities = settings.deviceAgentStage6Capabilities,
        scope = scope,
        approvalMode = settings.deviceAgentApprovalMode,
        allowScreenshots = settings.deviceAgentAllowScreenshots,
        allowedPackages = settings.deviceAgentAllowedPackages,
        allowedCategories = settings.deviceAgentAllowedCategories,
        )
    }

    fun aiConfigurationSnapshot(): AiConfiguration? = aiConfigurationState.snapshot()

    internal val settingsDeletions = dev.zeroinput.ime.settings.SettingsDeletionCoordinator(
        post = { android.os.Handler(android.os.Looper.getMainLooper()).post(it) },
        delete = { kind, completed ->
            when (kind) {
                dev.zeroinput.ime.settings.SettingsDeletion.PERSONALIZATION -> {
                    clearPersonalizationData()
                    completed(true)
                }
                dev.zeroinput.ime.settings.SettingsDeletion.SECURE_CLIPBOARD -> {
                    securePaste.cancel()
                    secureClipboard.purge()
                    completed(true)
                }
                dev.zeroinput.ime.settings.SettingsDeletion.AI -> clearAiData(completed)
            }
        },
    )

    private val aiConfigurationListeners = CopyOnWriteArrayList<() -> Unit>()

    fun observeAiConfiguration(listener: () -> Unit): AutoCloseable {
        aiConfigurationListeners += listener
        return AutoCloseable { aiConfigurationListeners -= listener }
    }

    fun readAiConfiguration(completed: (Result<AiConfiguration>) -> Unit) {
        val token = aiConfigurationState.current()
        try {
            aiPersistenceExecutor.execute {
                val result = runCatching { aiConfigurationStorage.read() }
                if (aiConfigurationState.publish(token, result.getOrNull())) completed(result)
                else completed(Result.failure(IllegalStateException("AI configuration revoked")))
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            completed(Result.failure(IllegalStateException("AI settings unavailable")))
        }
    }

    /** Immediately revokes use of the old endpoint, then durably saves on the AI storage queue. */
    fun updateAiConfiguration(value: AiConfiguration, completed: (Boolean) -> Unit) {
        val token = revokeAiConfiguration()
        enqueueAiControl(completed) {
            check(aiConfigurationState.isCurrent(token))
            aiConfigurationStorage.write(value)
            check(aiConfigurationState.publish(token, value, explicitControl = true))
        }
    }

    fun clearAiData(completed: (Boolean) -> Unit) {
        val token = revokeAiConfiguration()
        enqueueAiControl(completed) {
            aiConfigurationStorage.clear()
            check(aiConfigurationState.publish(token, AiConfiguration(), explicitControl = true))
        }
    }

    private fun revokeAiConfiguration(): Long {
        aiContentInbox.clear()
        pageReferences.invalidate()
        val token = aiConfigurationState.revoke()
        aiDataGeneration.invalidate()
        aiCoordinator.invalidate()
        aiConfigurationListeners.forEach { it() }
        return token
    }

    private fun enqueueAiControl(completed: (Boolean) -> Unit, work: () -> Unit) {
        try {
            aiPersistenceExecutor.execute { completed(runCatching(work).isSuccess) }
        } catch (_: java.util.concurrent.RejectedExecutionException) { completed(false) }
    }
    internal val securePaste = dev.zeroinput.ime.clipboard.SecurePasteCoordinator(applicationContext, settings, secureClipboard)
    internal val clipboardSelectionTransfer = dev.zeroinput.ime.clipboard.ClipboardSelectionTransfer()
    val languagePacks = LanguagePackInstaller(applicationContext)
    val languagePackRegistry = LanguagePackRegistry(languagePacks)
    val publicResources = dev.zeroinput.languagepack.PublicResourceStore(applicationContext)
    val rime = RimeEngineFactory(applicationContext, engineExecutor, builtInDictionaries, publicResources)
        .also { factory ->
            publicDictionaries.onPublished = { factory.runtime.dictionariesChanged(); dictionaryDataChanged() }
            builtInDictionaries.onPublished = { factory.runtime.dictionariesChanged(); dictionaryDataChanged() }
            dictionaryEdits.onPublished = { dictionaryDataChanged() }
            publicResources.onPublished = { factory.runtime.dictionariesChanged() }
        }
    @Volatile private var associationPredictor: dev.zeroinput.engine.api.NextWordPredictor =
        dev.zeroinput.engine.api.NextWordPredictor.Empty
    @Volatile private var associationPredictorReady = false
    val nextWordPredictor = dev.zeroinput.engine.api.NextWordPredictor { language, context, limit ->
        associationPredictor.suggest(language, context, limit)
    }

    fun isAssociationPredictorReady(): Boolean = associationPredictorReady

    private val english = EnglishEngineFactory(
        learnedSuggestions = LearnedSuggestionSource { prefix, limit ->
            // Keep English personalization behind the same queued, encrypted
            // store used by the controller.  This avoids a synchronous
            // Keystore read on the IME thread and preserves privacy gating in
            // EnglishInputEngine.start().
            queuedPersonalization.suggestionsFor(prefix, InputLanguage.ENGLISH, limit)
                .map { suggestion -> WeightedTerm(suggestion.text, suggestion.frequency) }
        },
    )
    @Volatile
    private var languagePackSnapshot: List<InstalledLanguagePack> = emptyList()
    @Volatile
    private var languagePackDiscoveryComplete = false
    private val languagePackRefreshLock = Any()
    private val languagePackListeners = CopyOnWriteArrayList<() -> Unit>()
    private val associationPredictorListeners = CopyOnWriteArrayList<() -> Unit>()
    private val personalizationListeners = CopyOnWriteArrayList<() -> Unit>()
    private val dictionaryScriptObserver = rime.runtime.addStateListener {
        // Native OpenCC becomes available with the runtime. Discard worker results
        // prepared by the fallback before any newly prepared native engine uses them.
        queuedPersonalization.invalidatePendingWrites()
    }

    init {
        readAiConfiguration {}
        // Start the native runtime before any optional data work. Prepared
        // session creation shares this queue and therefore cannot be delayed
        // by word-association or language-pack discovery.
        runCatching {
            engineExecutor.execute {
                runCatching { rime.warmUp() }
            }
        }
        // These reads are independent of native runtime transitions. Keep
        // their bounded queue separate so a slow optional index cannot delay
        // the first Chinese composition.
        runCatching {
            engineMaintenanceExecutor.execute {
                associationPredictor = runCatching { dev.zeroinput.engine.dictionary.WordAssociationIndex.loadBundled() }
                    .getOrDefault(dev.zeroinput.engine.api.NextWordPredictor.Empty)
                associationPredictorReady = true
                associationPredictorListeners.forEach { listener -> runCatching(listener) }
                // A broken optional language pack must not prevent the core Rime
                // runtime from publishing its terminal READY/FAILED state.
                runCatching { refreshLanguagePacks() }
            }
        }
    }

    private fun dictionaryDataChanged() {
        queuedPersonalization.invalidatePendingWrites()
        personalizationListeners.forEach { listener -> runCatching(listener) }
    }

    fun createEngine(language: InputLanguage): InputEngine = when (language) {
        InputLanguage.CHINESE -> rime.create()
        InputLanguage.ENGLISH -> english.create()
    }

    /**
     * Returns a predictable, in-memory engine for the first input frame.  It
     * must stay cheap: callers invoke it from the IME lifecycle thread while
     * the native/data-backed replacement is prepared in the worker below.
     */
    fun createImmediateEngine(language: InputLanguage): InputEngine = when (language) {
        InputLanguage.CHINESE -> rime.createFallback(settings.chineseInputOptions)
        InputLanguage.ENGLISH -> english.create()
    }

    /** Drafts share only public conversion options, never editor or personal data. */
    fun createDraftEngine(language: InputLanguage, options: ChineseInputOptions = ChineseInputOptions()): InputEngine = when (language) {
        InputLanguage.CHINESE -> rime.createFallback(options)
        InputLanguage.ENGLISH -> english.create()
    }

    /**
     * Creates the engine requested by a warm-up ticket.  A null result means
     * that the runtime or language-pack registry is not ready yet; the caller
     * keeps the immediate fallback and retries on the corresponding state
     * callback.
     */
    internal fun prepareEngine(request: EngineWarmupRequest): InputEngine? {
        if (!request.privacy.suggestionsAllowed) return null
        if (request.language == InputLanguage.CHINESE && request.languagePackKey == null) {
            rime.runtime.prepareDictionariesIfIdle()
        }
        if (request.retryInitialization && request.language == InputLanguage.CHINESE &&
            request.languagePackKey == null && !rime.runtime.isReady) rime.warmUp()
        val candidate = if (request.languagePackKey != null) {
            createLanguagePackEngine(request.languagePackKey)
        } else {
            when (request.language) {
                InputLanguage.CHINESE -> rime.createNativeOrNull(request.chineseOptions, engineExecutor)
                InputLanguage.ENGLISH -> english.create()
            }
        }
        if (candidate == null) return null
        val supportsLanguage = runCatching {
            request.language in candidate.descriptor.languages
        }.getOrDefault(false)
        if (!supportsLanguage) runCatching { candidate.close() }
        return candidate.takeIf { supportsLanguage }
    }

    fun createLanguagePackEngine(packKey: String): InputEngine? = languagePackRegistry.create(packKey)

    fun refreshLanguagePacks() {
        val listeners = synchronized(languagePackRefreshLock) {
            // Keep discovery failures isolated from the core engine.  The
            // previous verified registry remains usable when a filesystem scan
            // is interrupted, while listeners still receive a terminal
            // notification so the UI/IME can retry or fall back deterministically.
            val registryResult = runCatching { languagePackRegistry.refresh() }
            languagePackSnapshot = runCatching { languagePacks.installedSnapshot() }
                .getOrDefault(languagePackSnapshot)
            if (registryResult.isSuccess) {
                // The scan has now reached a terminal state.  A key can only
                // be preserved while this first asynchronous scan is still in
                // flight; once it completes, clear a genuinely missing or
                // disabled package so the next session uses the base engine.
                languagePackDiscoveryComplete = true
                if (!languagePackRegistry.contains(settings.lastLanguagePackKey)) {
                    settings.lastLanguagePackKey = null
                }
            }
            languagePackListeners.toList()
        }
        listeners.forEach { listener -> runCatching(listener) }
    }

    fun isLanguagePackDiscoveryComplete(): Boolean = languagePackDiscoveryComplete

    /** Returns the last verified package snapshot without touching disk. */
    fun installedLanguagePacks(): List<InstalledLanguagePack> = languagePackSnapshot

    fun addLanguagePackListener(listener: () -> Unit): AutoCloseable {
        languagePackListeners += listener
        runCatching(listener)
        return object : AutoCloseable {
            override fun close() {
                languagePackListeners -= listener
            }
        }
    }

    fun addAssociationPredictorListener(listener: () -> Unit): AutoCloseable {
        associationPredictorListeners += listener
        if (associationPredictorReady) runCatching(listener)
        return object : AutoCloseable {
            override fun close() {
                associationPredictorListeners -= listener
            }
        }
    }

    /** Clears user phrases, frequencies, and emoji recency through their queued stores. */
    fun clearPersonalizationData() {
        // Settings invokes this on its worker.  Wait for the encrypted phrase
        // deletion so a process death immediately after the confirmation
        // cannot resurrect the old dictionary on the next launch.
        var failure: Throwable? = null
        fun attempt(operation: () -> Unit) {
            try {
                operation()
            } catch (error: Throwable) {
                failure = failure?.also { it.addSuppressed(error) } ?: error
            }
        }
        attempt(queuedPersonalization::clearAndAwait)
        attempt(emojiHistory::clear)
        attempt(expressions::clear)
        notifyExpressionsChanged()
        personalizationListeners.forEach { listener -> runCatching(listener) }
        if (failure != null) throw failure
    }

    fun addPersonalizationListener(listener: () -> Unit): AutoCloseable {
        personalizationListeners += listener
        return object : AutoCloseable {
            override fun close() {
                personalizationListeners -= listener
            }
        }
    }

    /**
     * Observes completion of a non-blocking personal-suggestion lookup.
     * Callbacks run on the personalization worker and must only post work to
     * the IME owner thread.
     */
    fun addPersonalizationSuggestionListener(listener: () -> Unit): AutoCloseable =
        queuedPersonalization.addSuggestionListener(listener)

    fun registeredEngineDescriptors(): List<EngineDescriptor> =
        listOf(rime.descriptor, english.descriptor) + languagePackRegistry.descriptors()

    override fun close() {
        settingsDeletions.close()
        pageReferences.invalidate()
        pageReferenceExecutor.shutdownNow()
        keyboardBackgrounds.close()
        securePaste.close()
        clipboardSelectionTransfer.close()
        clipboardGuard.close()
        engineExecutor.shutdownNow()
        engineMaintenanceExecutor.shutdownNow()
        aiConfigurationListeners.clear()
        aiCoordinator.close()
        deviceAgentCoordinator.close()
        deviceStage6Controller.close()
        deviceAgentBroker.close()
        aiContentInbox.clear()
        aiExecutor.shutdownNow()
        aiProbeExecutor.shutdownNow()
        aiDocumentExecutor.shutdownNow()
        aiImportSettingsObserver.close()
        aiCancellationExecutor.shutdown()
        aiPersistenceExecutor.shutdownNow()
        deviceAgentExecutor.shutdownNow()
        deviceAgentScreenshotExecutor.shutdownNow()
        deviceAgentStage6Executor.shutdownNow()
        queuedPersonalization.close()
        dictionaryScriptObserver.close()
        languagePackSnapshot = emptyList()
        languagePackListeners.clear()
        associationPredictorListeners.clear()
        personalizationListeners.clear()
        expressionListeners.clear()
        languagePackRegistry.close()
        rime.close()
    }
}
