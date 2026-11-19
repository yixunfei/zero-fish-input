package dev.zeroinput.languagepack

import java.util.concurrent.CancellationException

/** Module-local by design; keep the policy aligned with app, ime-core and engine-rime. */
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
