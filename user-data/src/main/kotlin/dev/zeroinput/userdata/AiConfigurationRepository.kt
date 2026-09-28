package dev.zeroinput.userdata

import android.content.Context
import dev.zeroinput.ai.api.AiLimits
import dev.zeroinput.security.EncryptedFileStore
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.security.SecurityAliases
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.net.URI

data class AiConfiguration(
    val enabled: Boolean = false,
    val networkAllowed: Boolean = false,
    val endpoint: String = "https://api.openai.com/v1/chat/completions",
    val model: String = "gpt-4o-mini",
    val apiKey: String = "",
    val timeoutMs: Long = 60_000L,
    val saveConversations: Boolean = false,
) {
    override fun toString(): String = "AiConfiguration(redacted)"
}

class AiConfigurationRepository(
    private val store: EncryptedStore,
) {
    constructor(context: Context) : this(
        EncryptedFileStore(context, "ai-configuration.bin", SecurityAliases.AI_CONFIGURATION),
    )

    private val lock = Any()
    private var cached: AiConfiguration? = null
    private var deletionFailed = false

    fun read(): AiConfiguration = synchronized(lock) {
        check(!deletionFailed) { "AI configuration unavailable" }
        cached ?: load().also { cached = it }
    }

    fun write(value: AiConfiguration) = synchronized(lock) {
        check(!deletionFailed) { "AI configuration unavailable" }
        validate(value)
        val root = JSONObject().apply {
            put("format", 1)
            put("enabled", value.enabled)
            put("networkAllowed", value.networkAllowed)
            put("endpoint", value.endpoint)
            put("model", value.model)
            put("apiKey", value.apiKey)
            put("timeoutMs", value.timeoutMs)
            put("saveConversations", value.saveConversations)
        }
        val bytes = root.toString().toByteArray(StandardCharsets.UTF_8)
        try { store.write(bytes) } finally { bytes.fill(0) }
        cached = value
    }

    fun clear() = synchronized(lock) {
        cached = null
        deletionFailed = true
        store.delete(deleteKey = true)
        deletionFailed = false
        cached = AiConfiguration()
    }

    private fun load(): AiConfiguration {
        val bytes = store.read() ?: return AiConfiguration()
        return try {
            require(bytes.size <= 16_384)
            val root = JSONObject(String(bytes, StandardCharsets.UTF_8))
            require(root.getInt("format") == 1)
            AiConfiguration(
                enabled = root.optBoolean("enabled", false),
                networkAllowed = root.optBoolean("networkAllowed", false),
                endpoint = root.optString("endpoint", AiConfiguration().endpoint),
                model = root.optString("model", AiConfiguration().model),
                apiKey = root.optString("apiKey", ""),
                timeoutMs = root.optLong("timeoutMs", 60_000L),
                saveConversations = root.optBoolean("saveConversations", false),
            ).also(::validate)
        } catch (_: Exception) {
            throw IllegalStateException("AI configuration is invalid")
        } finally { bytes.fill(0) }
    }

    private fun validate(value: AiConfiguration) {
        val endpoint = runCatching { URI(value.endpoint) }.getOrElse { throw IllegalArgumentException("AI endpoint is invalid") }
        require(endpoint.scheme == "https" && !endpoint.host.isNullOrBlank() && endpoint.userInfo == null &&
            endpoint.query == null && endpoint.fragment == null && endpoint.port in -1..65_535 &&
            endpoint.port != 0) { "AI endpoint must use HTTPS" }
        require(value.endpoint.length <= 512 && value.model.length in 1..128)
        require(value.apiKey.length <= 512 && value.apiKey.none(Char::isISOControl))
        require(value.timeoutMs in 1_000L..AiLimits.MAX_TIMEOUT_MS)
    }
}
