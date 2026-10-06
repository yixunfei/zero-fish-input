package dev.zeroinput.userdata

import android.content.Context
import dev.zeroinput.ai.api.AiLimits
import dev.zeroinput.security.EncryptedFileStore
import dev.zeroinput.security.EncryptedStore
import dev.zeroinput.security.SecurityAliases
import org.json.JSONObject
import org.json.JSONArray
import java.nio.charset.StandardCharsets
import java.net.URI

data class AiConfiguration(
    val enabled: Boolean = false,
    val networkAllowed: Boolean = false,
    val timeoutMs: Long = 60_000L,
    val saveConversations: Boolean = false,
    val providers: List<AiProviderProfile> = emptyList(),
    val selectedProviderId: String? = null,
) {
    fun activeProvider(): AiProviderProfile? = providers.firstOrNull { it.id == selectedProviderId }
    fun activeEndpoint(): String = activeProvider()?.endpoint.orEmpty()
    fun activeModel(): String = activeProvider()?.selectedModel.orEmpty()
    fun activeKey(): String = activeProvider()?.apiKey.orEmpty()
    override fun toString(): String = "AiConfiguration(redacted)"
}

data class AiProviderProfile(
    val id: String,
    val name: String,
    val endpoint: String,
    val apiKey: String,
    val models: List<String>,
    val selectedModel: String,
    val imageModels: Set<String> = emptySet(),
    val audioModels: Set<String> = emptySet(),
) {
    val supportsImages: Boolean get() = selectedModel in imageModels
    val supportsAudio: Boolean get() = selectedModel in audioModels
    override fun toString(): String = "AiProviderProfile(redacted)"
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
            put("format", FORMAT)
            put("enabled", value.enabled)
            put("networkAllowed", value.networkAllowed)
            put("timeoutMs", value.timeoutMs)
            put("saveConversations", value.saveConversations)
            put("selectedProviderId", value.selectedProviderId)
            put("providers", JSONArray().apply { value.providers.forEach { profile ->
                put(JSONObject().apply {
                    put("id", profile.id); put("name", profile.name); put("endpoint", profile.endpoint)
                    put("apiKey", profile.apiKey); put("models", JSONArray(profile.models))
                    put("selectedModel", profile.selectedModel)
                    put("imageModels", JSONArray(profile.imageModels.toList()))
                    put("audioModels", JSONArray(profile.audioModels.toList()))
                })
            } })
        }
        val bytes = root.toString().toByteArray(StandardCharsets.UTF_8)
        try { require(bytes.size <= MAX_CONFIG_BYTES); store.write(bytes) } finally { bytes.fill(0) }
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
            require(bytes.size <= MAX_CONFIG_BYTES)
            val root = JSONObject(String(bytes, StandardCharsets.UTF_8))
            when (root.getInt("format")) {
                in 1..3 -> {
                    // The pre-provider format contained a single endpoint/key. The
                    // user explicitly chose to discard it instead of migrating it.
                    try {
                        store.delete(deleteKey = true)
                    } catch (error: Exception) {
                        // A failed purge must not permit a later read to reuse legacy
                        // credentials. Keep this repository fail-closed until clear succeeds.
                        deletionFailed = true
                        throw error
                    }
                    return AiConfiguration()
                }
                FORMAT -> Unit
                else -> throw IllegalStateException("AI configuration is invalid")
            }
            val entries = root.optJSONArray("providers") ?: JSONArray()
            val providers = (0 until entries.length()).map { index ->
                val entry = entries.getJSONObject(index)
                val models = entry.getJSONArray("models")
                AiProviderProfile(
                    id = entry.getString("id"), name = entry.getString("name"),
                    endpoint = entry.getString("endpoint"), apiKey = entry.getString("apiKey"),
                    models = (0 until models.length()).map(models::getString),
                    selectedModel = entry.getString("selectedModel"),
                    imageModels = entry.getJSONArray("imageModels").let { values ->
                        (0 until values.length()).map(values::getString).toSet()
                    },
                    audioModels = entry.getJSONArray("audioModels").let { values ->
                        (0 until values.length()).map(values::getString).toSet()
                    },
                )
            }
            AiConfiguration(
                enabled = root.optBoolean("enabled", false),
                networkAllowed = root.optBoolean("networkAllowed", false),
                timeoutMs = root.optLong("timeoutMs", 60_000L),
                saveConversations = root.optBoolean("saveConversations", false),
                providers = providers,
                selectedProviderId = root.optString("selectedProviderId").takeIf { it.isNotBlank() },
            ).also(::validate)
        } catch (_: Exception) {
            throw IllegalStateException("AI configuration is invalid")
        } finally { bytes.fill(0) }
    }

    private fun validate(value: AiConfiguration) {
        require(value.providers.size <= 16 && value.providers.map { it.id }.distinct().size == value.providers.size)
        require(value.providers.isEmpty() && value.selectedProviderId == null ||
            value.providers.any { it.id == value.selectedProviderId })
        value.providers.forEach { profile ->
            require(profile.id.length in 1..64 && profile.name.isNotBlank() && profile.name.length <= 64)
            require(profile.id.none(Char::isISOControl) && profile.name.none(Char::isISOControl))
            require(profile.models.size in 1..32 && profile.models.distinct().size == profile.models.size)
            require(profile.models.all { it.length in 1..128 && it.none(Char::isISOControl) })
            require(profile.selectedModel in profile.models)
            require(profile.imageModels.all { it in profile.models } && profile.audioModels.all { it in profile.models })
            require(profile.apiKey.length <= 512 && profile.apiKey.none(Char::isISOControl))
            validateEndpoint(profile.endpoint)
        }
        require(value.timeoutMs in 1_000L..AiLimits.MAX_TIMEOUT_MS)
    }

    private fun validateEndpoint(raw: String) {
        val endpoint = runCatching { URI(raw) }.getOrElse { throw IllegalArgumentException("AI endpoint is invalid") }
        require(endpoint.scheme == "https" && !endpoint.host.isNullOrBlank() && endpoint.userInfo == null &&
            endpoint.query == null && endpoint.fragment == null && endpoint.port in -1..65_535 &&
            endpoint.port != 0) { "AI endpoint must use HTTPS" }
        require(raw.length <= 512)
    }

    private companion object {
        const val FORMAT = 4
        const val MAX_CONFIG_BYTES = 512 * 1024
    }
}
