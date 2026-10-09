package dev.zeroinput.engine.rime

import android.content.Context
import dev.zeroinput.engine.api.InputEngine
import dev.zeroinput.engine.api.ConfigurableChineseEngineFactory
import dev.zeroinput.engine.api.ChineseInputOptions
import java.util.concurrent.ExecutorService

/**
 * Creates Rime engines.  The [executor] runs any native work an engine defers,
 * notably the lazily created related-reading session of a full-pinyin engine;
 * it must be the shared bounded engine worker so that native session creation
 * stays off the IME input thread and serialized against schema deployment.
 */
class RimeEngineFactory(
    context: Context,
    private val executor: ExecutorService,
    dictionaries: dev.zeroinput.engine.api.PublicDictionarySource = dev.zeroinput.engine.api.PublicDictionarySource.Empty,
    resources: dev.zeroinput.engine.api.PublicResourceSource? = null,
) : ConfigurableChineseEngineFactory, AutoCloseable {
    val runtime = RimeRuntime(context, dictionaries, resources)

    override val descriptor = RimeInputEngine.Descriptor

    fun warmUp(): Boolean = runtime.initialize()

    override fun isAvailable(): Boolean = runtime.isReady

    /** Creates the in-memory fallback without touching the native runtime. */
    fun createFallback(options: ChineseInputOptions = ChineseInputOptions()): InputEngine =
        FallbackPinyinEngine(options)

    /**
     * Creates a native engine only when the runtime is already ready.  The
     * caller is expected to run this method on a bounded background worker;
     * unlike [create], it never silently substitutes the fallback.
     */
    fun createNativeOrNull(
        options: ChineseInputOptions = ChineseInputOptions(),
        executor: ExecutorService = this.executor,
    ): InputEngine? {
        if (!runtime.isReady) return null
        return runCatching { runtime.createEngine(options, executor) }
            .onFailure(runtime::markFailed)
            .getOrNull()
    }

    override fun create(): InputEngine = createNativeOrNull() ?: createFallback()

    override fun create(options: ChineseInputOptions): InputEngine = createNativeOrNull(options) ?: createFallback(options)

    override fun close() = runtime.close()
}
