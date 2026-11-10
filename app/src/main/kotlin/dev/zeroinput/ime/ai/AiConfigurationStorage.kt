package dev.zeroinput.ime.ai

import dev.zeroinput.userdata.AiConfiguration
import dev.zeroinput.userdata.AiConfigurationRepository
import dev.zeroinput.userdata.AiConversationRepository

/** Runs on the serial AI storage worker, before configuration becomes usable. */
internal class AiConfigurationStorage(
    private val configuration: AiConfigurationRepository,
    private val conversations: AiConversationRepository,
    private val contextLibrary: dev.zeroinput.userdata.AiContextLibraryRepository? = null,
    private val skills: dev.zeroinput.userdata.AiSkillRepository? = null,
    private val connections: dev.zeroinput.userdata.AiConnectionRepository? = null,
) {
    fun read(): AiConfiguration = configuration.read().also {
        // Retry an interrupted/failed purge before publishing a disabled setting.
        if (!it.saveConversations) conversations.clear()
    }

    fun write(value: AiConfiguration) {
        configuration.write(value)
        if (!value.saveConversations) conversations.clear()
    }

    fun clear() {
        val config = runCatching { configuration.clear() }
        val history = runCatching { conversations.clear() }
        val context = runCatching { contextLibrary?.clear() }
        val skillData = runCatching { skills?.clear() }
        val connectionData = runCatching { connections?.clear() }
        config.getOrThrow()
        history.getOrThrow()
        context.getOrThrow()
        skillData.getOrThrow()
        connectionData.getOrThrow()
    }
}
