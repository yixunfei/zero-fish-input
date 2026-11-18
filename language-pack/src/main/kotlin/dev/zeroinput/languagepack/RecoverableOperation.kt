package dev.zeroinput.languagepack

import java.util.concurrent.CancellationException

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
