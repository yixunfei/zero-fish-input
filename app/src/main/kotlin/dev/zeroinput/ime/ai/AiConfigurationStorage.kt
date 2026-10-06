package dev.zeroinput.ime.ai

import dev.zeroinput.userdata.AiConfiguration
import dev.zeroinput.userdata.AiConfigurationRepository
import dev.zeroinput.userdata.AiConversationRepository

/** Runs on the serial AI storage worker, before configuration becomes usable. */
internal class AiConfigurationStorage(
    private val configuration: AiConfigurationRepository,
    private val conversations: AiConversationRepository,
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
        config.getOrThrow()
        history.getOrThrow()
    }
}
