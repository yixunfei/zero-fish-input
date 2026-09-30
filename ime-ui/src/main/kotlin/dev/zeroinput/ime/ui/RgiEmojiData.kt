package dev.zeroinput.ime.ui

import java.io.InputStream
import java.security.MessageDigest

/** Bounded parser for the checked-in public catalog; never accepts user files. */
internal object RgiEmojiData {
    private const val MAX_BYTES = 4 * 1024 * 1024
    private val imageKey = Regex("[0-9a-f]{4,6}(?:_[0-9a-f]{4,6}){0,15}")
    private val publicCategories = setOf(EmojiCategory.SMILEYS, EmojiCategory.PEOPLE, EmojiCategory.NATURE,
        EmojiCategory.FOOD, EmojiCategory.TRAVEL, EmojiCategory.ACTIVITY, EmojiCategory.OBJECTS,
        EmojiCategory.SYMBOLS, EmojiCategory.FLAGS)

    fun read(input: InputStream): List<EmojiEntry> {
        val bytes = input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { "Invalid bundled emoji data" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val checksum = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        require(checksum == RgiEmojiVersion.SHA256) { "Invalid bundled emoji checksum" }
        return parse(bytes.toString(Charsets.UTF_8))
    }

    internal fun parse(text: String): List<EmojiEntry> {
        require(text.length <= MAX_BYTES) { "Invalid bundled emoji data" }
        val entries = ArrayList<EmojiEntry>(RgiEmojiVersion.COUNT)
        text.lineSequence().filter { it.isNotBlank() && !it.startsWith('#') }.forEach { line ->
            require(line.length <= 4096 && entries.size < RgiEmojiVersion.COUNT) { "Invalid bundled emoji row" }
            val parts = line.split('\t')
            require(parts.size == 8 && parts[2].isNotBlank() && parts[3].isNotBlank()) { "Invalid bundled emoji row" }
            val value = decode(parts[0])
            val category = EmojiCategory.valueOf(parts[1])
            require(category in publicCategories && imageKey.matches(parts[5])) { "Invalid bundled emoji category" }
            require(parts[6].length <= 128 && parts[7] in setOf("fully-qualified", "component")) { "Invalid bundled emoji status" }
            entries += EmojiEntry(value, category, parts[4], name = parts[3], englishName = parts[2],
                artworkKey = parts[5], variantKey = parts[6], isComponent = parts[7] == "component")
        }
        require(entries.size == RgiEmojiVersion.COUNT && entries.map { it.value }.distinct().size == entries.size) {
            "Incomplete bundled emoji catalog"
        }
        return entries
    }

    private fun decode(sequence: String): String {
        val parts = sequence.split(' ')
        require(parts.size in 1..16) { "Invalid bundled emoji sequence" }
        return buildString {
            parts.forEach {
                val value = it.toInt(16)
                require(Character.isValidCodePoint(value) && value !in 0xD800..0xDFFF) { "Invalid bundled emoji code point" }
                appendCodePoint(value)
            }
        }
    }
}
