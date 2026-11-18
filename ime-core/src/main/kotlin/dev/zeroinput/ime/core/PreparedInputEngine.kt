package dev.zeroinput.ime.core

import dev.zeroinput.engine.api.EngineSnapshot
import dev.zeroinput.engine.api.EngineDescriptor
import dev.zeroinput.engine.api.InputEngine
import dev.zeroinput.engine.api.InputLanguage
import dev.zeroinput.ime.core.privacy.SessionPrivacy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An engine that was created and started away from the IME input thread.
 * Ownership is transferred exactly once when a controller accepts it; stale
 * results close themselves instead of leaking a native session or dictionary.
 */
class PreparedInputEngine(
    val language: InputLanguage,
    val languagePackKey: String?,
    val packageName: String?,
    val privacy: SessionPrivacy,
    val snapshot: EngineSnapshot,
    engine: InputEngine,
) : AutoCloseable {
    private val owned = AtomicBoolean(true)
    private val candidate = engine

    val descriptor: EngineDescriptor
        get() = candidate.descriptor

    /** Transfers the engine to [InputSessionController] once. */
    internal fun takeEngine(): InputEngine? =
        if (owned.compareAndSet(true, false)) candidate else null

    override fun close() {
        if (owned.compareAndSet(true, false)) {
            recoverable { candidate.close() }
        }
    }
}
