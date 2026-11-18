package dev.zeroinput.ime.textstego

import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** One process-wide execution slot. Revoking a UI never frees a running KDF's slot. */
internal object StegoCryptoWorker {
    val executor = singleSlot("zeroinput-message-crypto")
}

/** A provider ignoring cancellation cannot create more readers through repeated Activity launches. */
internal object StegoSourceWorker {
    val executor = singleSlot("zeroinput-message-source")
}

private fun singleSlot(name: String) = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, SynchronousQueue(),
    { task -> Thread(task, name).apply { priority = Thread.NORM_PRIORITY - 1 } }, ThreadPoolExecutor.AbortPolicy())
