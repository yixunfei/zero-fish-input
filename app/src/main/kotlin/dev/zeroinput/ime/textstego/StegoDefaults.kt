package dev.zeroinput.ime.textstego

import android.content.SharedPreferences
import androidx.core.content.edit
import dev.zeroinput.security.message.MessageCipher
import dev.zeroinput.security.message.MessageRandomKey
import dev.zeroinput.security.message.MessageStage

/** Public configuration only. Never contains message text or credentials. */
data class StegoDefaults(
    val masterBits: Int = 256,
    val stages: List<MessageStage> = listOf(MessageStage(MessageCipher.AES)),
    val vaultPaste: Boolean = true,
    val systemPaste: Boolean = false,
)

internal class StegoPreferences(private val preferences: SharedPreferences) {
    fun read(): StegoDefaults = StegoDefaults(
        masterBits = try { preferences.getInt(BITS, 256).takeIf(MessageRandomKey::validBits) ?: 256 }
            catch (_: ClassCastException) { 256 },
        stages = decodeStages(try { preferences.getString(STAGES, null) } catch (_: ClassCastException) { null }),
        vaultPaste = boolean(VAULT, true), systemPaste = boolean(SYSTEM, false),
    )

    fun write(value: StegoDefaults) {
        require(MessageRandomKey.validBits(value.masterBits))
        require(validStages(value.stages))
        preferences.edit {
            putInt(BITS, value.masterBits)
            putString(STAGES, value.stages.joinToString(",") { "${it.cipher.id}:${it.bits}" })
            putBoolean(VAULT, value.vaultPaste); putBoolean(SYSTEM, value.systemPaste)
        }
    }

    private fun boolean(key: String, fallback: Boolean): Boolean =
        try { preferences.getBoolean(key, fallback) } catch (_: ClassCastException) { false }

    companion object {
        private const val BITS = "stego.default-master-bits"
        private const val STAGES = "stego.default-stages"
        private const val VAULT = "stego.vault-paste"
        private const val SYSTEM = "stego.system-paste"

        fun validStages(stages: List<MessageStage>): Boolean = stages.size in 1..6 &&
            stages.map { it.cipher }.distinct().size == stages.size && stages.all { it.bits in it.cipher.keyBits }

        fun decodeStages(text: String?): List<MessageStage> {
            val fallback = listOf(MessageStage(MessageCipher.AES))
            if (text == null || text.length > 80) return fallback
            val stages = text.split(',').map { entry ->
                val pair = entry.split(':')
                if (pair.size != 2) return fallback
                val cipher = MessageCipher.entries.firstOrNull { it.id == pair[0].toIntOrNull() } ?: return fallback
                val bits = pair[1].toIntOrNull()?.takeIf { it in cipher.keyBits } ?: return fallback
                MessageStage(cipher, bits)
            }
            return stages.takeIf(::validStages) ?: fallback
        }
    }
}
