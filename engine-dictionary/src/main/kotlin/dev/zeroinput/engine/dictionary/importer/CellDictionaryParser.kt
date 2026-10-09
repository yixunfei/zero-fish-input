package dev.zeroinput.engine.dictionary.importer

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/** QQ DCS and Sogou SCEL cell dictionaries share a bounded little-endian syllable table. */
class CellDictionaryParser(private val source: Source) : PublicDictionaryParser {
    enum class Source { QQ, SOGOU }

    override fun parse(input: InputStream, emit: (PublicDictionaryEntry) -> Unit) {
        val bytes = input.readBytesLimited(32 * 1024 * 1024)
        require(bytes.size >= 0x2628) { "Truncated cell dictionary" }
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(data.int == 0x1540) { "Unknown cell dictionary format" }
        val signature = ByteArray(4).also(data::get)
        require(when (source) {
            Source.QQ -> signature.contentEquals(byteArrayOf(0x44, 0x43, 0x53, 1))
            Source.SOGOU -> signature.contentEquals(byteArrayOf(0x44, 0x43, 0x53, 1)) ||
                signature.contentEquals(byteArrayOf(0x44, 0x43, 0x53, 2))
        }) { "Unsupported cell dictionary version" }
        data.position(0x1540)
        val syllableCount = data.int
        require(syllableCount in 1..1024)
        val syllables = HashMap<Int, String>()
        repeat(syllableCount) {
            val id = data.u16()
            val syllable = PublicPinyin.normalize(data.text(data.u16(), 24))
            require(syllable.isNotEmpty() && syllable.all { it in 'a'..'z' })
            require(syllables.put(id, syllable) == null)
        }
        // Known v1 tables end at 0x2628. Extended versions are rejected rather than guessed.
        require(data.position() == 0x2628) { "Unsupported cell dictionary table" }
        var count = 0
        while (data.hasRemaining()) {
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
            val homophones = data.u16()
            val codeBytes = data.u16()
            require(homophones in 1..4096 && codeBytes in 2..512 && codeBytes % 2 == 0)
            val reading = (0 until codeBytes / 2).joinToString(" ") {
                syllables[data.u16()] ?: error("Unknown syllable")
            }
            repeat(homophones) {
                require(++count <= 1_000_000)
                val text = data.text(data.u16(), 512)
                val extraLength = data.u16()
                require(extraLength in 2..1024 && extraLength <= data.remaining())
                val weight = data.u16().toLong()
                data.position(data.position() + extraLength - 2)
                emit(PublicDictionaryEntry(text, reading, weight))
            }
        }
        require(count > 0)
    }

    private fun ByteBuffer.u16(): Int {
        require(remaining() >= 2) { "Truncated cell dictionary" }
        return short.toInt() and 0xffff
    }

    private fun ByteBuffer.text(size: Int, maximum: Int): String {
        require(size in 2..maximum && size % 2 == 0 && size <= remaining())
        val bytes = ByteArray(size).also(::get)
        return Charsets.UTF_16LE.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun InputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val size = read(buffer)
            if (size < 0) break
            require(output.size() + size <= limit) { "Cell dictionary too large" }
            output.write(buffer, 0, size)
        }
        return output.toByteArray()
    }
}
