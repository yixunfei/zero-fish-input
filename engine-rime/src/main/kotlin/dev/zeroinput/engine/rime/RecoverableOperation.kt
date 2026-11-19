package dev.zeroinput.engine.rime

import java.util.concurrent.CancellationException

/** Module-local by design; keep the policy aligned with app, ime-core and language-pack. */
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
