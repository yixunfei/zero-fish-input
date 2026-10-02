package dev.zeroinput.engine.rime

import android.content.Context
import dev.zeroinput.engine.api.ChineseInputOptions
import dev.zeroinput.engine.api.ChineseKeyboardLayout
import dev.zeroinput.engine.api.DoublePinyinScheme
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
    private var initializationGeneration = 0L
    /** Keeps a cancelled worker as the sole owner until its native work returns. */
    private var initializationInFlightGeneration: Long? = null
    private var finalizeWhenIdle = false
    @Volatile private var nativeInitialized = false
    private var nativeInitializationGeneration: Long? = null
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

    fun initialize(): Boolean {
        val generation = synchronized(lock) {
            if (isReady) return true
            if (activeEngines.get() != 0 || initializationInFlightGeneration != null || nativeInitialized) return false
            initializationGeneration += 1
            initializationInFlightGeneration = initializationGeneration
            initializationError = null
            setState(RimeRuntimeState.INITIALIZING)
            initializationGeneration
        }
        if (!BuildConfig.HAS_NATIVE_RIME) {
            return failInitialization(generation, IllegalStateException("Native Rime is unavailable in this build"))
        }
        if (!NativeRimeBridge.isLoaded) {
            return failInitialization(
                generation,
                NativeRimeBridge.loadFailure() ?: IllegalStateException("Native Rime library could not be loaded"),
            )
        }

        return try {
            // Asset deployment and native verification may perform substantial I/O.
            // Keep them outside the runtime monitor so input callbacks can fail fast.
            val directories = RimeAssetInstaller(applicationContext).install()
            val installer = RimeConfigurationInstaller(directories, publicSyllables)
            check(NativeRimeBridge.nativeInitialize(
                directories.shared.absolutePath,
                directories.user.absolutePath,
            )) { "Native Rime initialization returned false" }
            synchronized(lock) {
                nativeInitialized = true
                nativeInitializationGeneration = generation
            }
            // A schema can create a session even when its translator has no
            // usable dictionary. Validate conversion before publishing readiness.
            RimeInputEngine().use { RimeSessionVerifier.verify(it) }
            val version = NativeRimeBridge.nativeVersion().ifBlank { "unavailable" }
            synchronized(lock) {
                if (generation != initializationGeneration || state != RimeRuntimeState.INITIALIZING) {
                    finalizeNativeLocked()
                    finishInitializationLocked(generation)
                    return false
                }
                configurationInstaller = installer
                runtimeVersion = version
                initializationError = null
                preparedSchemaId = "zeroinput_pinyin"
                isReady = true
                finalizeWhenIdle = false
                setState(RimeRuntimeState.READY)
                finishInitializationLocked(generation)
            }
            true
        } catch (error: Throwable) {
            failInitialization(generation, error)
        }
    }

    private fun failInitialization(generation: Long, error: Throwable): Boolean = synchronized(lock) {
        if (generation != initializationGeneration) {
            if (nativeInitializationGeneration == generation) finalizeNativeLocked()
            finishInitializationLocked(generation)
            return false
        }
        finalizeNativeLocked()
        isReady = false
        initializationError = error
        setState(RimeRuntimeState.FAILED)
        finishInitializationLocked(generation)
        false
    }

    private fun finishInitializationLocked(generation: Long) {
        if (initializationInFlightGeneration == generation) initializationInFlightGeneration = null
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
                val engine = if (options.keyboardLayout == ChineseKeyboardLayout.NINE_KEY ||
                    options.effectiveDoublePinyinScheme != DoublePinyinScheme.OFF) primary else {
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
            .also { synchronized(lock) { activeEngines.incrementAndGet() } }
        try {
            RimeSessionVerifier.verify(engine, options.keyboardLayout, options.effectiveDoublePinyinScheme)
            return engine
        } catch (error: Throwable) {
            engine.close()
            throw error
        }
    }

    private fun releaseEngine() = synchronized(lock) {
        if (activeEngines.get() <= 0) return@synchronized
        if (activeEngines.decrementAndGet() != 0) return@synchronized
        if (finalizeWhenIdle) {
            finalizeNativeLocked()
            finalizeWhenIdle = false
        }
        if (isReady && state == RimeRuntimeState.READY) {
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
        initializationGeneration += 1
        if (initializationInFlightGeneration == null && activeEngines.get() == 0 && state != RimeRuntimeState.INITIALIZING) {
            finalizeNativeLocked()
            finalizeWhenIdle = false
        } else {
            // The worker owns native initialization until it reaches its final
            // generation check; a later initialize must wait for that check.
            finalizeWhenIdle = true
        }
        isReady = false
        preparedSchemaId = null
        configurationInstaller = null
        runtimeVersion = "unavailable"
        initializationError = null
        setState(RimeRuntimeState.NOT_INITIALIZED)
    }

    /** Marks a runtime failure discovered while creating or using an engine. */
    internal fun markFailed(error: Throwable) = synchronized(lock) {
        if (!isReady) return
        isReady = false
        runtimeVersion = "unavailable"
        initializationError = error
        if (activeEngines.get() == 0) {
            finalizeNativeLocked()
        } else {
            // Existing sessions must destroy themselves before the global runtime
            // is finalized; otherwise close() would use freed native state.
            finalizeWhenIdle = true
        }
        setState(RimeRuntimeState.FAILED)
    }

    private fun finalizeNativeLocked() {
        if (!nativeInitialized) return
        nativeInitialized = false
        nativeInitializationGeneration = null
        runCatching { NativeRimeBridge.nativeFinalize() }
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
