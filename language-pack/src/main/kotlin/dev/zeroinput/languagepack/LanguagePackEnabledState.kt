package dev.zeroinput.languagepack

import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Worker-owned enabled flags. Recovery records an explicit disabled flag for every unknown pack. */
internal class LanguagePackEnabledState(
    private val file: File,
    private val installedKeys: () -> Set<String>,
) {
    var invalid = false
        private set
    private var cache: Map<String, Boolean>? = null

    fun read(): MutableMap<String, Boolean> {
        if (!file.isFile) {
            invalid = false
            return cache?.toMutableMap() ?: mutableMapOf()
        }
        return try {
            require(file.length() <= MAX_BYTES)
            val root = JSONObject(file.readText(StandardCharsets.UTF_8))
            val state = root.keys().asSequence().associateWith { key ->
                val value = root.get(key)
                require(value is Boolean)
                value
            }
            invalid = false
            cache = state
            state.toMutableMap()
        } catch (_: Exception) {
            invalid = true
            cache?.toMutableMap() ?: mutableMapOf()
        }
    }

    fun write(state: Map<String, Boolean>) {
        val recovered = state.toMutableMap()
        if (invalid) installedKeys().forEach { recovered.putIfAbsent(it, false) }
        val root = JSONObject().apply { recovered.forEach { (name, value) -> put(name, value) } }
        val bytes = root.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeBytes(bytes)
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        invalid = false
        cache = recovered
    }

    private companion object { const val MAX_BYTES = 256 * 1024 }
}
