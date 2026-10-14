package dev.zeroinput.userdata

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** A bounded binary payload inside a dedicated authenticated encrypted store. */
internal object DictionaryEditFormat {
    private const val MAGIC = 0x5a494445
    fun encode(data: DictionaryEdits): ByteArray {
        validate(data)
        val buffer = object : ByteArrayOutputStream() {
            fun erase() { buf.fill(0) }
            private fun reserve(extra: Int) {
                require(extra <= DictionaryEditLimits.BYTES - count)
                if (count + extra <= buf.size) return
                val old = buf
                buf = old.copyOf(maxOf(count + extra, minOf(old.size * 2, DictionaryEditLimits.BYTES)))
                old.fill(0)
            }
            override fun write(value: Int) { reserve(1); super.write(value) }
            override fun write(bytes: ByteArray, start: Int, length: Int) {
                reserve(length)
                super.write(bytes, start, length)
            }
        }
        return try {
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(1)
                output.writeInt(data.names.size)
                data.names.toSortedMap().forEach { (id, name) -> output.text(id); output.text(name) }
                output.writeInt(data.entries.size)
                data.entries.forEach { entry ->
                    output.text(entry.dictionaryId)
                    output.word(entry.original)
                    output.word(entry.replacement)
                }
            }
            require(buffer.size() <= DictionaryEditLimits.BYTES) { "Dictionary edit capacity exceeded" }
            buffer.toByteArray()
        } finally { buffer.erase() }
    }

    fun decode(bytes: ByteArray): DictionaryEdits {
        require(bytes.size <= DictionaryEditLimits.BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == 1)
            val names = LinkedHashMap<String, String>()
            repeat(input.count(144)) {
                val id = input.text(64)
                require(names.put(id, input.text(160)) == null)
            }
            val entries = List(input.count(DictionaryEditLimits.ENTRIES)) {
                DictionaryEdit(input.text(64), input.word(), input.word())
            }
            require(input.read() == -1)
            DictionaryEdits(names, entries).also(::validate)
        }
    }

    private fun validate(data: DictionaryEdits) {
        require(data.names.size <= 144 && data.entries.size <= DictionaryEditLimits.ENTRIES)
        data.names.forEach { (id, name) -> DictionaryEditLimits.id(id); DictionaryEditLimits.text(name, 160) }
        require(data.entries.distinctBy { it.dictionaryId to it.key }.size == data.entries.size)
        val replacements = data.entries.filter { it.replacement != null }
        require(replacements.distinctBy { Triple(it.dictionaryId, it.replacement?.text, it.replacement?.reading) }.size == replacements.size)
    }

    private fun DataOutputStream.text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        try { writeInt(bytes.size); write(bytes) } finally { bytes.fill(0) }
    }
    private fun DataOutputStream.word(value: DictionaryWord?) {
        writeBoolean(value != null)
        if (value != null) { text(value.text); text(value.reading); writeInt(value.weight) }
    }
    private fun DataInputStream.count(limit: Int): Int = readInt().also { require(it in 0..limit) }
    private fun DataInputStream.text(limit: Int): String {
        val bytes = ByteArray(count(limit * 4))
        val characters = java.nio.CharBuffer.allocate(bytes.size)
        return try {
            readFully(bytes)
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes), characters, true).also { if (it.isError) it.throwException() }
            decoder.flush(characters).also { if (it.isError) it.throwException() }
            characters.flip()
            characters.toString().also { require(it.length <= limit) }
        } finally { bytes.fill(0); characters.array().fill('\u0000') }
    }
    private fun DataInputStream.word(): DictionaryWord? = when (readUnsignedByte()) {
        0 -> null
        1 -> DictionaryWord(text(256), text(1024), readInt())
        else -> error("Invalid dictionary edit payload")
    }
}
