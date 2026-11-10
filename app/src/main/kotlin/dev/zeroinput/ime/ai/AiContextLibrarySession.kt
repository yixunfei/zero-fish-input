package dev.zeroinput.ime.ai

import dev.zeroinput.ai.api.AiContextLibrary
import dev.zeroinput.userdata.AiContextLibraryRepository
import java.util.concurrent.Executor

/** One private review surface; late reads/edits cannot populate another surface. */
internal class AiContextLibrarySession(
    private val repository: AiContextLibraryRepository,
    executor: Executor,
    data: AiDataGeneration,
    post: (() -> Unit) -> Boolean,
    deliver: (Result<AiContextLibrary>) -> Unit,
) : AutoCloseable {
    private val session = AiLibrarySession(executor, data, post, deliver)

    fun read() = session.run { current -> repository.read(current) }
    fun write(expectedRevision: Long, value: AiContextLibrary) = session.run { current -> repository.write(expectedRevision, value, current) }
    override fun close() = session.close()
}
