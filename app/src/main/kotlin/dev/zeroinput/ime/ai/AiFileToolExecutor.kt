package dev.zeroinput.ime.ai

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import dev.zeroinput.ai.api.*
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** Only the task's picker-produced URI map is reachable; model arguments never name a URI. */
internal class AiFileToolExecutor(private val resolver: ContentResolver, files: Map<String, Uri>,
    private val worker: Executor) : AiToolExecutor {
    private val files = files.toMap()
    private val definitions = AiFileTools.definitions(files.keys.toList()).associateBy { it.name }
    init { require(files.values.all { it.scheme == ContentResolver.SCHEME_CONTENT }) }

    override fun execute(call: AiToolCall, current: () -> Boolean, completed: (AiToolResult) -> Unit): AiRequestHandle {
        val cancelled = AtomicBoolean(); val signal = CancellationSignal()
        val live = { !cancelled.get() && current() }
        try {
            worker.execute work@{
                if (!live()) return@work
                val result = runCatching {
                    require(call.name in setOf(AiFileTools.READ, AiFileTools.SEARCH))
                    val args = AiToolJson.arguments(AiToolJson.schema(definitions.getValue(call.name).schema), call.arguments)
                    val uri = files.getValue(args.getString("file"))
                    val text = AiDocumentTextReader.readFile(resolver, uri, signal, live)
                    if (call.name == AiFileTools.READ) AiFileTools.excerpt(text, args.getInt("offset"), args.getInt("length"))
                    else AiFileTools.search(text, args.getString("query"), args.getBoolean("ignore_case"))
                }
                if (live()) completed(result.fold({ AiToolResult(call.id, AiToolResultStatus.SUCCESS, it) },
                    { AiToolResult(call.id, AiToolResultStatus.FAILED, "Selected document unavailable or invalid.") }))
            }
        } catch (_: RejectedExecutionException) {
            if (live()) completed(AiToolResult(call.id, AiToolResultStatus.FAILED, "Document worker unavailable."))
        }
        return object : AiRequestHandle {
            override fun cancel() { cancelled.set(true); signal.cancel() }
        }
    }
}
