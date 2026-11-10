package dev.zeroinput.ime.ai

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import dev.zeroinput.ai.api.*
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

internal class AiFileOutput(val name: String, val mime: String, val content: String) {
    override fun toString() = "AiFileOutput(redacted)"
}

/** The destination callback only returns a fresh CREATE_DOCUMENT result after local review. */
internal class AiSaveAsToolExecutor(private val resolver: ContentResolver, private val worker: Executor,
    private val destination: (AiFileOutput, (Uri?) -> Unit) -> AutoCloseable,
    existing: Set<Uri> = emptySet()) : AiToolExecutor {
    private val usedDestinations = existing.toMutableSet()
    override fun execute(call: AiToolCall, current: () -> Boolean, completed: (AiToolResult) -> Unit): AiRequestHandle {
        val schema = AiToolJson.schema(AiFileTools.definitions(emptyList()).single().schema)
        require(call.name == AiFileTools.SAVE)
        val args = AiToolJson.arguments(schema, call.arguments)
        val output = AiFileOutput(args.getString("name"), AiFileTools.mimeForName(args.getString("name")), args.getString("content"))
        val cancelled = AtomicBoolean(); val consumed = AtomicBoolean(); val signal = CancellationSignal()
        val live = { !cancelled.get() && current() }
        val pending = destination(output) { uri ->
            if (live() && consumed.compareAndSet(false, true)) {
                if (uri == null) completed(AiToolResult(call.id, AiToolResultStatus.REJECTED, "The user declined Save As."))
                else write(call.id, output, uri, signal, live, completed)
            }
        }
        return object : AiRequestHandle {
            override fun cancel() { cancelled.set(true); pending.close(); signal.cancel() }
        }
    }

    private fun write(callId: String, output: AiFileOutput, uri: Uri, signal: CancellationSignal,
        current: () -> Boolean, completed: (AiToolResult) -> Unit) {
        val resolver = this.resolver
        val destinations = usedDestinations
        try {
            worker.execute work@{
                if (!current()) return@work
                var dispatched = false
                val bytes = output.content.toByteArray(Charsets.UTF_8)
                val result = try {
                    require(uri.scheme == ContentResolver.SCHEME_CONTENT && bytes.size in 1..65_536)
                    check(current()); signal.throwIfCanceled()
                    synchronized(destinations) { require(destinations.size < 48 && destinations.add(uri)) }
                    // Opening in write mode may already change the document; any later failure is uncertain.
                    dispatched = true
                    val descriptor = resolver.openAssetFileDescriptor(uri, "w", signal) ?: error("Document unavailable")
                    signal.setOnCancelListener { runCatching { descriptor.close() } }
                    descriptor.use { asset -> asset.createOutputStream().use { stream ->
                        check(current()); signal.throwIfCanceled()
                        stream.write(bytes); stream.flush()
                    } }
                    check(current()); signal.throwIfCanceled()
                    AiToolResult(callId, AiToolResultStatus.SUCCESS, "The selected new document was written.")
                } catch (_: Exception) {
                    AiToolResult(callId, if (dispatched) AiToolResultStatus.UNKNOWN else AiToolResultStatus.FAILED,
                        if (dispatched) "The write outcome is uncertain. Do not retry automatically." else "The new document could not be written.")
                } finally { signal.setOnCancelListener(null); bytes.fill(0) }
                if (current()) completed(result)
            }
        } catch (_: RejectedExecutionException) {
            if (current()) completed(AiToolResult(callId, AiToolResultStatus.FAILED, "Document worker unavailable."))
        }
    }
}
