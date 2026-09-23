package dev.zeroinput.engine.rime

import android.content.Context
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.ChineseKeyboardLayout
import dev.zeroinput.engine.api.EditorContext
import dev.zeroinput.engine.api.InputEngine
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class RimeRuntime(context: Context) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val lock = Any()
    private val activeEngines = AtomicInteger(0)
    private var configurationInstaller: RimeConfigurationInstaller? = null
    private var preparedSchemaId: String? = null
    private val publicSyllables by lazy {
        applicationContext.assets.open("pinyin-syllables.txt").bufferedReader().use {
            it.readLines()
        }
    }
    private val nineKeyReadings by lazy { NineKeyReadings(publicSyllables) }
    private val relatedReadings by lazy { RelatedReadings(publicSyllables) }

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var initializationError: Throwable? = null
        private set

    @Volatile
    var state: RimeRuntimeState = RimeRuntimeState.NOT_INITIALIZED
        private set

    @Volatile
    private var runtimeVersion: String = "unavailable"

    private val stateListeners = CopyOnWriteArrayList<(RimeRuntimeState) -> Unit>()

    fun addStateListener(listener: (RimeRuntimeState) -> Unit): AutoCloseable {
        stateListeners += listener
        runCatching { listener(state) }
        return object : AutoCloseable {
            override fun close() {
                stateListeners -= listener
            }
        }
    }

    fun initialize(): Boolean = synchronized(lock) {
        if (isReady) return true
        if (activeEngines.get() != 0) return false
        setState(RimeRuntimeState.INITIALIZING)
        initializationError = null
        if (!BuildConfig.HAS_NATIVE_RIME) {
            initializationError = IllegalStateException("Native Rime is unavailable in this build")
            isReady = false
            setState(RimeRuntimeState.FAILED)
            return false
        }
        if (!NativeRimeBridge.isLoaded) {
            initializationError = NativeRimeBridge.loadFailure()
                ?: IllegalStateException("Native Rime library could not be loaded")
            isReady = false
            setState(RimeRuntimeState.FAILED)
            return false
        }

        return try {
            val directories = RimeAssetInstaller(applicationContext).install()
            configurationInstaller = RimeConfigurationInstaller(directories)
            check(NativeRimeBridge.nativeInitialize(
                directories.shared.absolutePath,
                directories.user.absolutePath,
            )) { "Native Rime initialization returned false" }
            // A schema can create a session even when its translator has no
            // usable dictionary. Validate conversion before publishing readiness.
            RimeInputEngine().use { RimeSessionVerifier.verify(it) }
            runtimeVersion = NativeRimeBridge.nativeVersion().ifBlank { "unavailable" }
            initializationError = null
            preparedSchemaId = "zeroinput_pinyin"
            isReady = true
            setState(RimeRuntimeState.READY)
            true
        } catch (error: Throwable) {
            runCatching { NativeRimeBridge.nativeFinalize() }
            isReady = false
            initializationError = error
            setState(RimeRuntimeState.FAILED)
            false
        }
    }

    fun version(): String = if (isReady) runtimeVersion else "unavailable"

    internal fun createEngine(options: ChineseInputOptions, executor: java.util.concurrent.ExecutorService): InputEngine? = synchronized(lock) {
        check(isReady) { "Rime runtime is not initialized" }
        val id = PinyinAlgebra.schemaId(options)
        if (id != preparedSchemaId) {
            // Deploying a prism may perform substantial I/O under librime's lock.
            // The service first retires the idle native engine to a memory fallback.
            if (activeEngines.get() != 0) return null
            setState(RimeRuntimeState.INITIALIZING)
            try {
                val installer = checkNotNull(configurationInstaller)
                if (id != "zeroinput_pinyin") {
                    val (_, file) = installer.prepare(options)
                    check(NativeRimeBridge.nativeDeploySchema(file.absolutePath)) { "Input configuration deployment failed" }
                }
                installer.removeUnused(setOf(id))
                preparedSchemaId = id
            } catch (error: Exception) {
                markFailed(error)
                return null
            }
        }
        try {
            val primary = createVerifiedPrimary(id, options)
            try {
                val engine = if (options.keyboardLayout == ChineseKeyboardLayout.NINE_KEY) primary else {
                    // Secondary sessions remain lazy and use the same serial worker.
                    ExpandingRimeEngine(primary, relatedReadings, { context ->
                        createSecondaryEngine(id, options, context)
                    }, executor, ::markFailedIfReady)
                }
                if (state != RimeRuntimeState.READY) setState(RimeRuntimeState.READY)
                engine
            } catch (error: Throwable) {
                primary.close()
                throw error
            }
        } catch (error: Exception) {
            markFailed(error)
            null
        }
    }

    private fun createVerifiedPrimary(id: String, options: ChineseInputOptions): RimeInputEngine {
        val engine = RimeInputEngine(id, options, ::markFailed, ::releaseEngine,
            if (options.keyboardLayout == ChineseKeyboardLayout.NINE_KEY) nineKeyReadings else null)
            .also { activeEngines.incrementAndGet() }
        try {
            RimeSessionVerifier.verify(engine, options.keyboardLayout)
            return engine
        } catch (error: Throwable) {
            engine.close()
            throw error
        }
    }

    private fun releaseEngine() {
        if (activeEngines.decrementAndGet() == 0 && isReady && state == RimeRuntimeState.READY) {
            // A superseded prepared session can briefly delay a new configuration.
            // Its release makes that preparation retryable without polling or waits.
            setState(RimeRuntimeState.READY)
        }
    }

    private fun createSecondaryEngine(
        schemaId: String,
        options: ChineseInputOptions,
        context: EditorContext,
    ): RimeInputEngine = synchronized(lock) {
        check(isReady) { "Rime runtime is not initialized" }
        val engine = RimeInputEngine(schemaId, options, ::markFailed, ::releaseEngine)
            .also { activeEngines.incrementAndGet() }
        try {
            engine.start(context)
            engine
        } catch (error: Throwable) {
            engine.close()
            throw error
        }
    }

    private fun markFailedIfReady(error: Throwable) = synchronized(lock) {
        if (isReady) markFailed(error)
    }

    override fun close() = synchronized(lock) {
        if (isReady) runCatching { NativeRimeBridge.nativeFinalize() }
        isReady = false
        preparedSchemaId = null
        configurationInstaller = null
        runtimeVersion = "unavailable"
        initializationError = null
        setState(RimeRuntimeState.NOT_INITIALIZED)
    }

    /** Marks a runtime failure discovered while creating or using an engine. */
    internal fun markFailed(error: Throwable) = synchronized(lock) {
        if (isReady) runCatching { NativeRimeBridge.nativeFinalize() }
        isReady = false
        runtimeVersion = "unavailable"
        initializationError = error
        setState(RimeRuntimeState.FAILED)
    }

    private fun setState(value: RimeRuntimeState) {
        state = value
        stateListeners.forEach { listener ->
            runCatching { listener(value) }
        }
    }
}

enum class RimeRuntimeState {
    NOT_INITIALIZED,
    INITIALIZING,
    READY,
    FAILED,
}
