package dev.zeroinput.ime.concurrency

import java.util.concurrent.CancellationException

/** Keep cancellation and fatal failures out of optional-work fallback values. */
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
