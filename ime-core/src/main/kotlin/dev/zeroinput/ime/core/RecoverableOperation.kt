package dev.zeroinput.ime.core

import java.util.concurrent.CancellationException

/** Module-local by design; keep the policy aligned with app, engine-rime and language-pack. */
internal inline fun <T> recoverable(operation: () -> T): Result<T> = try {
    Result.success(operation())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (interrupted: InterruptedException) {
    Thread.currentThread().interrupt()
    throw interrupted
} catch (failure: Exception) {
    Result.failure(failure)
}

/** Only failures raised inside an engine call may enter the editor fallback. */
internal class EngineOperationFailure(cause: Throwable) : RuntimeException(cause)

/** Also used for cleanup after an accepted commit, where replaying the key is unsafe. */
internal inline fun <T> recoverableEngine(operation: () -> T): Result<T> = try {
    recoverable(operation)
} catch (failure: LinkageError) {
    Result.failure(failure)
}

internal inline fun <T> engineOperation(operation: () -> T): T =
    recoverableEngine(operation).getOrElse { throw EngineOperationFailure(it) }
