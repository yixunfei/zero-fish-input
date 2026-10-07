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
            val parsed = withJsonReader(bytes, ::readConfiguration)
            when (parsed.legacyFormat) {
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
            }
            parsed.configuration.also(::validate)
        } catch (_: Exception) {
            throw IllegalStateException("AI configuration is invalid")
        } finally { bytes.fill(0) }
    }

    private class ParsedConfiguration(
        val legacyFormat: Long,
        val configuration: AiConfiguration,
    )

    private fun invalid(): Nothing = throw IllegalArgumentException("invalid")

    private fun nextString(reader: android.util.JsonReader): String {
        require(reader.peek() == android.util.JsonToken.STRING)
        return reader.nextString()
    }

    private fun nextLong(reader: android.util.JsonReader): Long {
        require(reader.peek() == android.util.JsonToken.NUMBER)
        return reader.nextString().toLongOrNull() ?: invalid()
    }

    private fun nextBoolean(reader: android.util.JsonReader): Boolean {
        require(reader.peek() == android.util.JsonToken.BOOLEAN)
        return reader.nextBoolean()
    }

    private fun nextNullableString(reader: android.util.JsonReader): String? = when (reader.peek()) {
        android.util.JsonToken.NULL -> { reader.nextNull(); null }
        android.util.JsonToken.STRING -> reader.nextString()
        else -> invalid()
    }

    private fun readStringSet(reader: android.util.JsonReader): Set<String> {
        val values = LinkedHashSet<String>()
        reader.beginArray()
        while (reader.hasNext()) values += nextString(reader)
        reader.endArray()
        return values
    }

    private fun readStringList(reader: android.util.JsonReader): List<String> {
        val values = ArrayList<String>()
        reader.beginArray()
        while (reader.hasNext()) values += nextString(reader)
        reader.endArray()
        return values
    }

    private fun readProviders(reader: android.util.JsonReader): List<AiProviderProfile> {
        val providers = ArrayList<AiProviderProfile>()
        reader.beginArray()
        while (reader.hasNext()) {
            var id: String? = null
            var name: String? = null
            var endpoint: String? = null
            var apiKey: String? = null
            var models: List<String>? = null
            var selectedModel: String? = null
            var imageModels = emptySet<String>()
            var audioModels = emptySet<String>()
            val fields = HashSet<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val field = reader.nextName()
                require(fields.add(field))
                when (field) {
                    "id" -> id = nextString(reader)
                    "name" -> name = nextString(reader)
                    "endpoint" -> endpoint = nextString(reader)
                    "apiKey" -> apiKey = nextString(reader)
                    "models" -> models = readStringList(reader)
                    "selectedModel" -> selectedModel = nextString(reader)
                    "imageModels" -> imageModels = readStringSet(reader)
                    "audioModels" -> audioModels = readStringSet(reader)
                    else -> invalid()
                }
            }
            reader.endObject()
            providers += AiProviderProfile(
                id = id ?: invalid(), name = name ?: invalid(),
                endpoint = endpoint ?: invalid(), apiKey = apiKey ?: invalid(),
                models = models ?: invalid(), selectedModel = selectedModel ?: invalid(),
                imageModels = imageModels, audioModels = audioModels,
            )
        }
        reader.endArray()
        return providers
    }

    private fun readConfiguration(reader: android.util.JsonReader): ParsedConfiguration {
        var format: Long? = null
        var enabled = false
        var networkAllowed = false
        var timeoutMs = 60_000L
        var saveConversations = false
        var selectedProviderId: String? = null
        var providers: List<AiProviderProfile> = emptyList()
        val fields = HashSet<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val field = reader.nextName()
            require(fields.add(field))
            when (field) {
                "format" -> format = nextLong(reader)
                "enabled" -> enabled = nextBoolean(reader)
                "networkAllowed" -> networkAllowed = nextBoolean(reader)
                "timeoutMs" -> timeoutMs = nextLong(reader)
                "saveConversations" -> saveConversations = nextBoolean(reader)
                "selectedProviderId" -> selectedProviderId = nextNullableString(reader)
                "providers" -> providers = readProviders(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        require(reader.peek() == android.util.JsonToken.END_DOCUMENT)
        val version = format ?: invalid()
        require(version == FORMAT.toLong() || version in 1..3)
        if (version == FORMAT.toLong()) {
            require(fields.containsAll(listOf("enabled", "networkAllowed", "timeoutMs", "saveConversations", "providers")))
        }
        val configuration = if (version == FORMAT.toLong()) AiConfiguration(
            enabled = enabled,
            networkAllowed = networkAllowed,
            timeoutMs = timeoutMs,
            saveConversations = saveConversations,
            providers = providers,
            selectedProviderId = selectedProviderId?.takeIf { it.isNotBlank() },
        ) else AiConfiguration()
        return ParsedConfiguration(version, configuration)
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
