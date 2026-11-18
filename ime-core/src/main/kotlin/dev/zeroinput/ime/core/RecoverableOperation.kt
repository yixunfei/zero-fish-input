package dev.zeroinput.ime.core

import java.util.concurrent.CancellationException

/** Optional conversion work may degrade for ordinary failures, never cancellation or VM errors. */
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
internal class EngineOperationFailure(cause: Exception) : RuntimeException(cause)

internal inline fun <T> engineOperation(operation: () -> T): T =
    recoverable(operation).getOrElse { throw EngineOperationFailure(it as Exception) }
